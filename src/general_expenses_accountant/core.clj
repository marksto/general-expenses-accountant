(ns general-expenses-accountant.core
  "Bot API and business logic (core functionality)"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :refer [go chan timeout >! <! close!]]

            [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [mount.core :refer [defstate]]
            [slingshot.slingshot :as slingshot]
            [taoensso
             [encore :as encore]
             [timbre :as log]]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.domain.chat :as chats]
            [general-expenses-accountant.domain.tlog :as tlogs]
            [general-expenses-accountant.nums :as nums]
            [general-expenses-accountant.tg-bot-api :as tg-api]
            [general-expenses-accountant.tg-client :as tg-client]
            [general-expenses-accountant.utils :as utils])
  (:import [java.util Locale]))

;; STATE

(defstate ^:private bot-user
  :start (let [token (config/get-prop :bot-api-token)
               bot-user (tg-client/get-me token)]
           (log/debug "Identified myself:" bot-user)
           bot-user))

(defn get-bot-username
  []
  (get bot-user :username))


;; TODO: Send the list of supported commands w/ 'setMyCommands'.


;; TODO: Normally, this should be transformed into a 'cloffeine' cache
;;       which periodically auto-evicts the cast-off chats data. Then,
;;       the initial data should be truncated, e.g. by an 'updated_at'
;;       timestamps, and the data for chats from the incoming requests
;;       should be (re)loaded from the DB on demand.
(defstate ^:private *bot-data
  :start (let [chats (chats/select-all)
               ids (map :id chats)]
           (log/debug "Total chats uploaded from the DB:" (count chats))
           (atom (zipmap ids chats))))

(defn- get-bot-data
  []
  @*bot-data)

(defn- update-bot-data!
  ([upd-fn]
   (swap! *bot-data upd-fn))
  ([upd-fn & upd-fn-args]
   (apply swap! *bot-data upd-fn upd-fn-args)))

(defn- conditionally-update-bot-data!
  ([pred upd-fn]
   (with-local-vars [succeed true]
     (let [updated-bot-data
           (update-bot-data!
             (fn [bot-data]
               (if (pred bot-data)
                 (upd-fn bot-data)
                 (do
                   (var-set succeed false)
                   bot-data))))]
       (when @succeed
         updated-bot-data))))
  ([pred upd-fn & upd-fn-args]
   (conditionally-update-bot-data! pred #(apply upd-fn % upd-fn-args))))


;; TODO: Get rid of this atom after debugging is complete. This is not needed for the app.
(defonce ^:private *transactions (atom {}))

(defn- add-transaction!
  [new-transaction]
  {:pre [(contains? new-transaction :chat-id)]}
  (swap! *transactions update (:chat-id new-transaction) conj new-transaction))


;; ACCOUNTING TYPES

;; From the business logic perspective there are 2 main use cases for the bot:
;;   1. personal accounting — when a user creates a group chat for himself
;;                            and the bot;
;;   2. group accounting    — when multiple users create a group chat and add
;;                            the bot there to track their general expenses.
;; The only difference between the two is that an account of a ':general' type
;; is automatically created for a group accounting and what format is used for
;; new expense notification messages.

(def ^:private min-chat-members-for-group-accounting
  "The number of users in a group chat (including the bot itself)
   required for it to be used for the general expenses accounting."
  3)

;; NB: The approach to the design of the "virtual members" (those who aren't
;;     backed with a user but nevertheless occupy a personal account) in the
;;     personal accounting case.
;;     - Do they count when determining the use case for a chat?
;;       At the moment, they DO NOT count. Only real chat members do.
;;     - Are they merely different accounts for personal budgeting?
;;       This bot was not intended for a full-featured accounting.
(defn- is-chat-for-group-accounting?
  "Determines the use case for a chat by the number of its members."
  [chat-members-count]
  (and (some? chat-members-count) ;; a private chat with the bot
       (>= chat-members-count min-chat-members-for-group-accounting)))


;; MESSAGE TEMPLATES & CALLBACK DATA

(def ^:private cd-back "<back>")
(def ^:private cd-undo "<undo>")
(def ^:private cd-done "<done>")

(def ^:private cd-accounts "<accounts>")
(def ^:private cd-accounts-create "<accounts/create>")
(def ^:private cd-accounts-rename "<accounts/rename>")
(def ^:private cd-accounts-revoke "<accounts/revoke>")
(def ^:private cd-accounts-reinstate "<accounts/reinstate>")

(def ^:private cd-expense-items "<expense_items>")

(def ^:private cd-shares "<shares>")

;; TODO: What about <language_and_currency>?

(def ^:private id-separator "::")

(def ^:private cd-group-chat-prefix (str "gc" id-separator))
(def ^:private cd-expense-item-prefix (str "ei" id-separator))
(def ^:private cd-account-prefix (str "ac" id-separator))
(def ^:private cd-account-type-prefix (str "at" id-separator))

(def ^:private cd-digits-set #{"1" "2" "3" "4" "5" "6" "7" "8" "9" "0" ","})
(def ^:private cd-ar-ops-set #{"+" "–"})
(def ^:private cd-cancel "C")
(def ^:private cd-clear "<-")
(def ^:private cd-enter "OK")


;; TODO: Proper localization (with fn).
(def ^:private back-button-text "<< назад")
(def ^:private undo-button-text "Отмена")
(def ^:private done-button-text "Готово")
(def ^:private unknown-failure-text "Что-то пошло не так.")
(def ^:private default-general-acc-name "общие расходы")
(def ^:private account-types-names {:acc-type/personal {:single "Личный" :plural "Личные"}
                                    :acc-type/group {:single "Групповой" :plural "Групповые"}
                                    :acc-type/general {:single "Общий"}})
(def ^:private select-account-txt "Выберите счёт:")
(def ^:private select-payer-account-txt "Выберите тех, кто понёс расход:")
;;                                    "Error while saving data. Please, try again later." (en)
(def ^:private data-persistence-error "Ошибка при сохранении данных. Пожалуйста, повторите попытку позже.")
(def ^:private no-debtor-account-error "Нет возможности выбрать счёт для данного расхода.")
(def ^:private no-group-to-record-error "Нет возможности выбрать группу для записи расходов.")


(defn- escape-markdown-v2
  "A minor part of the Markdown V2 escaping features that is absolutely necessary."
  [markdown-str]
  (str/replace markdown-str #"[_*\[\]()~`>#+\-=|{}.!]" #(str "\\" %)))

(defn- format-bold
  [text-str]
  (str "*" text-str "*"))

(defn- format-list
  ([items-coll]
   (format-list items-coll nil))
  ([items-coll {:keys [bullet item-sep text-map-fn escape-md?]
                :or {bullet "•"
                     item-sep "\n"
                     text-map-fn identity}
                :as _?opts}]
   (let [list-str (->> items-coll
                       (map #(str (when (some? bullet)
                                    (str bullet " "))
                                  (text-map-fn %)))
                       (str/join item-sep))]
     (if escape-md? (escape-markdown-v2 list-str) list-str))))

(defn- format-currency
  [amount lang]
  (String/format (Locale/forLanguageTag lang)
                 "%.2f" ;; receives BigDecimal
                 (to-array [(bigdec amount)])))

(def ^:private force-reply-options
  (tg-api/build-message-options
    {:reply-markup (tg-api/build-reply-markup :force-reply {:selective true})
     :parse-mode "MarkdownV2"}))

(defn- build-items-selection-markup
  [items-buttons ?extra-buttons]
  (let [extra-buttons (when (some? ?extra-buttons)
                        (for [extra-btn ?extra-buttons]
                          [extra-btn]))
        ;; NB: A 'vec' is used to keep extra buttons below the regular ones.
        all-kbd-btns (into (vec items-buttons) extra-buttons)]
    {:reply-markup (tg-api/build-reply-markup :inline-keyboard all-kbd-btns)}))

(defn- get-select-one-item-markup
  ([items name-extr-fn key-extr-fn val-extr-fn]
   (get-select-one-item-markup items name-extr-fn key-extr-fn val-extr-fn nil))
  ([items name-extr-fn key-extr-fn val-extr-fn ?extra-buttons]
   (let [items-btns (for [item items]
                      [(tg-api/build-inline-kbd-btn (name-extr-fn item)
                                                    (key-extr-fn item)
                                                    (val-extr-fn item))])]
     (build-items-selection-markup items-btns ?extra-buttons))))

(defn- get-select-multiple-items-markup
  ([selected-items remaining-items
    name-extr-fn key-extr-fn val-extr-fn]
   (get-select-multiple-items-markup selected-items remaining-items
                                     name-extr-fn key-extr-fn val-extr-fn nil))
  ([selected-items remaining-items
    name-extr-fn key-extr-fn val-extr-fn
    ?extra-buttons]
   (let [sel-items-btns (for [item selected-items]
                          [(tg-api/build-inline-kbd-btn (str "✔ " (name-extr-fn item))
                                                        (key-extr-fn item)
                                                        (val-extr-fn item))])
         rem-items-btns (for [item remaining-items]
                          [(tg-api/build-inline-kbd-btn (name-extr-fn item)
                                                        (key-extr-fn item)
                                                        (val-extr-fn item))])
         all-items-btns (concat sel-items-btns rem-items-btns)]
     (build-items-selection-markup all-items-btns ?extra-buttons))))

(defn- get-group-ref-option-id
  [group-ref]
  (str cd-group-chat-prefix (:id group-ref)))

(defn- group-refs->options
  [group-refs]
  (get-select-one-item-markup group-refs
                              :title
                              (constantly :callback-data)
                              get-group-ref-option-id))

(defn- get-expense-item-option-id
  [exp-item]
  (str cd-expense-item-prefix (:code exp-item)))

(defn- expense-items->options
  [expense-items]
  (get-select-one-item-markup expense-items
                              :desc
                              (constantly :callback-data)
                              get-expense-item-option-id))

(defn- get-account-option-id
  [acc]
  (str cd-account-prefix (name (:type acc)) id-separator (:id acc)))

(defn- accounts->options
  [accounts & extra-buttons]
  (get-select-one-item-markup accounts
                              :name
                              (constantly :callback-data)
                              get-account-option-id
                              extra-buttons))

(defn- get-account-type-option-id
  [acc-type]
  (str cd-account-type-prefix (name acc-type)))

(defn- account-types->options
  [account-types & extra-buttons]
  (get-select-one-item-markup account-types
                              #(get-in account-types-names [% :single])
                              (constantly :callback-data)
                              get-account-type-option-id
                              extra-buttons))

(def ^:private back-button
  (tg-api/build-inline-kbd-btn back-button-text :callback-data cd-back))


(defn- get-retry-commands-msg
  [commands]
  {:type :text
   :text (format
           (str unknown-failure-text " Пожалуйста, повторите %s %s %s.")
           (if (next commands) "команды" "команду")
           (->> commands
                (map (partial str "/"))
                (str/join ", "))
           (if (next commands) "по-отдельности" "через какое-то время"))})

(def ^:private retry-resend-message-msg
  {:type :text
   :text (str unknown-failure-text " Пожалуйста, отправьте сообщение снова.")})

(def ^:private retry-callback-query-notification
  {:type :callback
   :options {:text (str unknown-failure-text " Пожалуйста, повторите действие.")}})


; group chats

;; TODO: Make messages texts localizable:
;;       - take the ':language_code' of the chat initiator (no personal settings)
;;       - externalize texts, keep only their keys (to get them via 'l10n')
(defn- get-introduction-msg
  [chat-members-count first-name]
  {:type :text
   :text (apply format "Привет, %s! Я — бот-бухгалтер. И я призван помочь %s с учётом %s общих расходов.\n
Для того, чтобы начать работу, просто %s на следующее сообщение."
                (if (= chat-members-count 2)
                  [first-name "тебе" "любых" "ответь"]
                  ["народ" "вам" "ваших" "ответьте каждый"]))})

(defn- get-settings-msg
  [first-time?]
  {:type :text
   ;; TODO: Shorten this text and spreading its content between the mgmt views?
   :text (format "%s можно настроить, чтобы учитывались:
- счета — не только личные, но и групповые;
- статьи расходов — подходящие по смыслу и удобные вам;
- доли — для статей расходов и ситуаций, когда 50/50 вам не подходит." ;; "По умолчанию равны для любых счетов и статей расходов."
                 (if (true? first-time?) "Также меня" "Меня"))
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Счета" :callback-data cd-accounts)
                                 (tg-api/build-inline-kbd-btn "Статьи" :callback-data cd-expense-items)
                                 (tg-api/build-inline-kbd-btn "Доли" :callback-data cd-shares)]])})})

(def ^:private waiting-for-user-input-notification
  {:type :callback
   :options {:text "Ожидание ответа пользователя в чате"}})

(def ^:private waiting-for-other-user-notification
  {:type :callback
   :options {:text "Ответ ожидается от другого пользователя"}})

(def ^:private message-already-in-use-notification
  {:type :callback
   :options {:text "С этим сообщением уже взаимодействуют"}})

(def ^:private ignored-callback-query-notification
  {:type :callback
   :options {:text "Запрос не может быть обработан"}})

(defn- join-into-text
  ([strings-or-colls]
   (join-into-text strings-or-colls "\n\n"))
  ([strings-or-colls sep]
   (str/join sep (flatten strings-or-colls))))

(defn- get-accounts-mgmt-options-msg
  [accounts-by-type]
  {:type :text
   :text (join-into-text
           ["Список счетов данной группы:"
            (map (fn [[acc-type accounts]]
                   (case acc-type
                     (:acc-type/personal :acc-type/group)
                     (str (format-bold (get-in account-types-names [acc-type :plural])) "\n"
                          (format-list accounts {:text-map-fn :name
                                                 :escape-md? true}))
                     :acc-type/general
                     (format-bold "Счёт для общих расходов")))
                 accounts-by-type)
            "Выберите, что вы хотите сделать:"])
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Создать новый" :callback-data cd-accounts-create)]
                                [(tg-api/build-inline-kbd-btn "Переименовать" :callback-data cd-accounts-rename)]
                                [(tg-api/build-inline-kbd-btn "Упразднить" :callback-data cd-accounts-revoke)]
                                [(tg-api/build-inline-kbd-btn "Восстановить" :callback-data cd-accounts-reinstate)]
                                [back-button]])
               :parse-mode "MarkdownV2"})})

(defn- get-account-selection-msg
  ([accounts txt]
   (get-account-selection-msg accounts txt nil))
  ([accounts txt ?extra-buttons]
   {:pre [(seq accounts)]}
   {:type :text
    :text txt
    :options (tg-api/build-message-options
               (apply accounts->options accounts ?extra-buttons))}))

(defn- get-account-type-selection-msg
  [account-types extra-buttons]
  {:pre [(seq account-types)]}
  {:type :text
   ;; Group account is used to share the expenses between the group members.
   ;; Personal account is needed in case the person is not present in Telegram.
   :text (join-into-text
           ["Какие счета для чего нужны?"
            (str (format-bold (get-in account-types-names [:acc-type/group :single])) " — "
                 (escape-markdown-v2 "используется для распределения расходов между членами группы."))
            (str (format-bold (get-in account-types-names [:acc-type/personal :single])) " — "
                 (escape-markdown-v2 "используется в случае, если человека нет в Telegram, но учитывать его при расчётах нужно."))
            "Выберите тип счёта:"])
   :options (tg-api/build-message-options
              (merge (apply account-types->options account-types extra-buttons)
                     {:parse-mode "MarkdownV2"}))})

(defn- get-new-account-name-request-msg
  [user]
  {:type :text
   :text (str (tg-api/get-user-mention-text user)
              (escape-markdown-v2 ", как бы вы хотели назвать новый счёт?"))
   :options force-reply-options})

(defn- get-the-name-is-already-taken-msg
  [user]
  {:type :text
   :text (str (tg-api/get-user-mention-text user)
              (escape-markdown-v2 ", данное имя счёта уже занято. Выберите другое имя."))
   :options force-reply-options})

(defn- get-group-members-selection-msg
  [user selected remaining]
  (let [extra-buttons [(tg-api/build-inline-kbd-btn undo-button-text :callback-data cd-undo)
                       (tg-api/build-inline-kbd-btn done-button-text :callback-data cd-done)]]
    {:type :text
     :text (str (tg-api/get-user-mention-text user)
                (escape-markdown-v2 ", выберите члена(ов) группы:"))
     :options (tg-api/build-message-options
                (merge (get-select-multiple-items-markup selected remaining
                                                         :name
                                                         (constantly :callback-data)
                                                         get-account-option-id
                                                         extra-buttons)
                       {:parse-mode "MarkdownV2"}))}))

(defn- get-new-group-members-msg
  [acc-names]
  {:pre [(seq acc-names)]}
  {:type :text
   :text (str "Члены новой группы:\n"
              (format-list acc-names))})

(def ^:private no-group-members-selected-notification
  {:type :callback
   :options {:text "Не выбрано ни одного участника группы"}})

(defn- get-account-rename-request-msg
  [user acc-name]
  {:type :text
   :text (str (tg-api/get-user-mention-text user)
              ", как бы вы хотели переименовать счёт \"" (escape-markdown-v2 acc-name) "\"?")
   :options force-reply-options})

(def ^:private no-eligible-accounts-notification
  {:type :callback
   :options {:text "Подходящих счетов не найдено"}})

(def ^:private successful-changes-msg
  {:type :text
   :text "Изменения внесены успешно."})

(def ^:private canceled-changes-msg
  {:type :text
   :text "Внесение изменений отменено."})

;; TODO: Add messages for 'expense items' here.

;; TODO: Add messages for 'shares' here.

(defn- get-personal-account-name-request-msg
  [existing-chat? ?chat-members]
  {:type :text
   :text (let [request-txt (escape-markdown-v2 "как будет называться ваш личный счёт?")
               part-one (if (some? ?chat-members)
                          (let [mentions (for [user ?chat-members]
                                           (tg-api/get-user-mention-text user))]
                            (str (str/join " " mentions) ", " request-txt))
                          (str/capitalize request-txt))
               part-two (escape-markdown-v2
                          (if (and existing-chat? (empty? ?chat-members))
                            "Пожалуйста, проигнорийруте это сообщение, если у вас уже есть личный счёт в данной группе."
                            "Пожалуйста, ответьте на данное сообщение."))]
           (join-into-text [part-one part-two]))
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :force-reply {:selective (some? ?chat-members)})
               :parse-mode "MarkdownV2"})})

(defn- get-personal-accounts-left-msg
  [count]
  {:type :text
   :text (format "Ожидаем остальных... Осталось %s." count)})

(defn- get-bot-readiness-msg
  [bot-username]
  {:type :text
   :text "Я готов к ведению учёта. Давайте же начнём!"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Перейти в чат для ввода расходов"
                                                              :url (str "https://t.me/" bot-username))]])})})

(defn- get-new-expense-msg
  [expense-amount expense-details payer-acc-name debtor-acc-name]
  (let [formatted-amount (format-currency expense-amount "ru")
        title-txt (when (some? payer-acc-name)
                    (str (format-bold (escape-markdown-v2 payer-acc-name)) "\n"))
        details-txt (->> [(str formatted-amount "₽")
                          debtor-acc-name
                          expense-details]
                         (filter some?)
                         (str/join " / ")
                         escape-markdown-v2)]
    {:type :text
     :text (str title-txt details-txt)
     :options (tg-api/build-message-options
                {:parse-mode "MarkdownV2"})}))

; private chats

(defn- get-private-introduction-msg
  [first-name]
  {:type :text
   :text (str "Привет, " first-name "! Чтобы добавить новый расход просто напиши мне сумму.")})

(def ^:private invalid-input-msg
  {:type :text
   :text "Пожалуйста, введите число. Например, \"145,99\"."})

(def ^:private inline-calculator-markup
  (tg-api/build-reply-markup
    :inline-keyboard
    [[(tg-api/build-inline-kbd-btn "7" :callback-data "7")
      (tg-api/build-inline-kbd-btn "8" :callback-data "8")
      (tg-api/build-inline-kbd-btn "9" :callback-data "9")
      (tg-api/build-inline-kbd-btn "C" :callback-data cd-cancel)]
     [(tg-api/build-inline-kbd-btn "4" :callback-data "4")
      (tg-api/build-inline-kbd-btn "5" :callback-data "5")
      (tg-api/build-inline-kbd-btn "6" :callback-data "6")
      (tg-api/build-inline-kbd-btn "+" :callback-data "+")]
     [(tg-api/build-inline-kbd-btn "1" :callback-data "1")
      (tg-api/build-inline-kbd-btn "2" :callback-data "2")
      (tg-api/build-inline-kbd-btn "3" :callback-data "3")
      (tg-api/build-inline-kbd-btn "–" :callback-data "–")]
     [(tg-api/build-inline-kbd-btn "0" :callback-data "0")
      (tg-api/build-inline-kbd-btn "," :callback-data ",")
      (tg-api/build-inline-kbd-btn "←" :callback-data cd-clear)
      (tg-api/build-inline-kbd-btn "OK" :callback-data cd-enter)]]))

(defn- get-interactive-input-msg
  ([text]
   (get-interactive-input-msg text nil))
  ([text ?extra-opts]
   {:type :text
    :text (str (escape-markdown-v2 "Новый расход:\n= ") text)
    :options (tg-api/build-message-options
               (merge {:parse-mode "MarkdownV2"} ?extra-opts))}))

(defn- get-inline-calculator-msg
  [user-input]
  (get-interactive-input-msg
    (escape-markdown-v2 (str user-input "_"))
    {:reply-markup inline-calculator-markup}))

(defn- get-interactive-input-success-msg
  [amount]
  (get-interactive-input-msg
    (escape-markdown-v2 (format-currency amount "ru"))))

(def ^:private interactive-input-disclaimer-msg
  {:type :text
   :text "Введите /cancel, чтобы выйти из режима калькуляции и ввести данные вручную."})

(def ^:private invalid-input-notification
  {:type :callback
   :options {:text "Ошибка в выражении! Вычисление невозможно."}})

(defn- get-added-to-new-group-msg
  [chat-title]
  {:type :text
   :text (str "Вас добавили в группу \"" chat-title "\".")})

(defn- get-removed-from-group-msg
  [chat-title]
  {:type :text
   :text (str "Вы покинули группу \"" chat-title "\".")})

(defn- get-group-selection-msg
  [group-refs]
  {:pre [(seq group-refs)]}
  {:type :text
   :text "Выберите, к какой группе отнести расход:"
   :options (tg-api/build-message-options
              (group-refs->options group-refs))})

(defn- get-expense-item-selection-msg
  [expense-items]
  {:pre [(seq expense-items)]}
  {:type :text
   :text "Выберите статью расходов:"
   :options (tg-api/build-message-options
              (expense-items->options expense-items))})

(defn- get-expense-manual-description-msg
  [user-name]
  {:type :text
   :text (str user-name ", опишите расход в двух словах:")
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup :force-reply)})})

(def ^:private expense-added-successfully-msg
  {:type :text
   :text "Запись успешно внесена в ваш гроссбух."})

(defn- get-failed-to-add-new-expense-msg
  [reason]
  {:type :text
   :text (str/join " " ["Не удалось добавить новый расход." reason])})


;; AUXILIARY FUNCTIONS

(defn get-datetime-in-tg-format
  []
  (quot (System/currentTimeMillis) 1000))

(defn- is-failure?
  "Checks if something resulted in a failure."
  [res]
  (and (keyword? res)
       (= "failure" (namespace res))))

;; - CHATS

(defn- does-chat-exist?
  [chat-id]
  (contains? (get-bot-data) chat-id))

(def ^:private default-chat-state :initial)

(defn- setup-new-chat!
  [chat-id new-chat]
  (when-not (does-chat-exist? chat-id) ;; petty RC
    (let [new-chat (chats/create! (assoc new-chat :id chat-id))]
      (update-bot-data! assoc chat-id new-chat)
      new-chat)))

;; TODO: Add a logical ID attr, e.g. UID, to track all group versions.
(defn- setup-new-group-chat!
  [chat-id chat-title chat-members-count]
  (setup-new-chat! chat-id {:type :chat-type/group
                            :data {:state default-chat-state
                                   :title chat-title
                                   :members-count chat-members-count
                                   :accounts {:last-id -1}}}))

(defn- setup-new-supergroup-chat!
  [chat-id migrate-from-id]
  (setup-new-chat! chat-id {:type :chat-type/supergroup
                            :data migrate-from-id}))

(defn- setup-new-private-chat!
  [chat-id group-chat-id]
  (setup-new-chat! chat-id {:type :chat-type/private,
                            :data {:state default-chat-state
                                   :groups #{group-chat-id}}}))

(defn- get-real-chat-id
  "Returns the chat 'id' with built-in insurance for the 'supergroup' chats."
  ([chat-id]
   (get-real-chat-id (get-bot-data) chat-id))
  ([bot-data chat-id]
   (let [chat (get bot-data chat-id)]
     (if (= :chat-type/supergroup (:type chat))
       (:data chat) ;; target group chat-id
       chat-id))))

(defn- get-chat-data
  "Returns the JSON data associated with a chat with the specified 'chat-id'."
  ([chat-id]
   (get-chat-data (get-bot-data) chat-id))
  ([bot-data chat-id]
   (when-let [real-chat-id (get-real-chat-id bot-data chat-id)]
     (get-in bot-data [real-chat-id :data]))))

(defn- -update-chat!
  [chat-to-upd]
  (if (chats/update! chat-to-upd)
    (:data chat-to-upd)
    (throw (ex-info "Failed to persist an updated chat" {:chat chat-to-upd}))))

(defn- update-chat-data!
  [chat-id upd-fn & upd-fn-args]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        bot-data (apply update-bot-data!
                        update-in [real-chat-id :data] upd-fn upd-fn-args)
        upd-chat (get bot-data real-chat-id)]
    (-update-chat! upd-chat)))

(defn- conditionally-update-chat-data!
  [chat-id pred upd-fn & upd-fn-args]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        bot-data (apply conditionally-update-bot-data!
                        #(pred (get-in % [real-chat-id :data]))
                        update-in [real-chat-id :data] upd-fn upd-fn-args)
        upd-chat (get bot-data real-chat-id)]
    (when (some? upd-chat)
      (-update-chat! upd-chat))))

(defn- assoc-in-chat-data!
  [chat-id [key & ks] ?value]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        full-path (concat [real-chat-id :data key] ks)
        bot-data (if (nil? ?value)
                   (update-bot-data! update-in (butlast full-path)
                                     dissoc (last full-path))
                   (update-bot-data! assoc-in full-path ?value))
        upd-chat (get bot-data real-chat-id)]
    (-update-chat! upd-chat)))

(defn- get-chat-state
  "Determines the state of the given chat. Returns 'nil' in case the group chat
   or user is unknown (the input is 'nil') and a valid default in case there is
   no preset state.

   NB: Be aware that calling this function, e.g. during a state change,
       may cause a race condition (RC) and result in an obsolete value."
  [chat-data]
  (get chat-data :state
       (when (some? chat-data) default-chat-state)))

(defn- change-chat-state!
  [chat-id chat-type-states new-state]
  {:pre [(does-chat-exist? chat-id)]}
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [curr-state (get-chat-state chat-data)
                  possible-new-states (or (-> chat-type-states curr-state :to)
                                          (-> chat-type-states curr-state))]
              (if (contains? possible-new-states new-state)
                (let [state-init-fn (-> chat-type-states new-state :init-fn)]
                  (cond-> chat-data
                          (some? state-init-fn) (state-init-fn)
                          :and-most-importantly (assoc :state new-state)))
                (do
                  (log/errorf "Failed to change state to '%s' for chat=%s with current state '%s'"
                              new-state chat-id curr-state)
                  chat-data)))))]
    (get-chat-state updated-chat-data)))

;; - BOT STATUS

(defn- does-bot-manage-the-chat?
  [{?chat-id :chat-id ?chat-state :chat-state}]
  {:pre [(or (some? ?chat-id) (some? ?chat-state))]}
  (let [chat-state (or ?chat-state
                       (and (does-chat-exist? ?chat-id)
                            (get-chat-state (get-chat-data ?chat-id))))]
    (= :managed chat-state)))

(defn- is-bot-evicted-from-chat?
  [chat-id]
  (and (does-chat-exist? chat-id)
       (= :evicted (get-chat-state (get-chat-data chat-id)))))

;; - ACCOUNTS

(defn- ->general-account
  [id name created members]
  {:id id
   :type :acc-type/general
   :name name
   :created created
   :members (set members)})

(defn- ->personal-account
  [id name created ?user-id ?msg-id]
  (cond-> {:id id
           :type :acc-type/personal
           :name name
           :created created}
          (some? ?user-id) (assoc :user-id ?user-id)
          (some? ?msg-id) (assoc :msg-id ?msg-id)))

(defn- ->group-account
  [id name created created-by members]
  {:id id
   :type :acc-type/group
   :name name
   :created created
   :created-by created-by
   :members (set members)})

(defn- is-account-revoked?
  [acc]
  (contains? acc :revoked))

(defn- is-account-active?
  [acc]
  (not (is-account-revoked? acc)))

;; - CHATS > ACCOUNTS

(defn- get-members-count
  [chat-data]
  (:members-count chat-data))

(defn- update-members-count!
  [chat-id update-fn]
  (update-chat-data! chat-id
                     update :members-count update-fn))

(defn- get-accounts-of-type
  ([chat-data acc-type]
   (map val (get-in chat-data [:accounts acc-type])))
  ([chat-data acc-type ?filter-pred]
   (cond->> (get-accounts-of-type chat-data acc-type)
            (some? ?filter-pred) (filter ?filter-pred))))

(defn- get-account-by-id
  [chat-data acc-type acc-id]
  (get-in chat-data [:accounts acc-type acc-id]))

(defn- get-accounts-next-id
  [chat-data]
  (-> chat-data :accounts :last-id inc))

(defn- find-account-by-name
  [chat-data acc-type acc-name]
  ;; NB: Personal and group account names must be unique.
  (first (get-accounts-of-type chat-data acc-type
                               #(= (:name %) acc-name))))

(defn- create-named-account!
  "Attempts to create a named type account with a name uniqueness check."
  [chat-id acc-type acc-name constructor-fn]
  (let [updated-chat-data
        (conditionally-update-chat-data!
          chat-id
          #(nil? (find-account-by-name % acc-type acc-name))
          (fn [chat-data]
            (let [next-id (get-accounts-next-id chat-data)
                  upd-accounts-next-id #(assoc-in % [:accounts :last-id] next-id)]
              (constructor-fn (upd-accounts-next-id chat-data) next-id))))]
    (if (some? updated-chat-data)
      (find-account-by-name updated-chat-data acc-type acc-name)
      :failure/the-account-name-is-already-taken)))

;; - CHATS > ACCOUNTS > GENERAL

(defn- get-current-general-account
  "Retrieves the current (the one that will be used) account of 'general' type
   from the provided chat data.
   NB: This function differs from the plain vanilla 'get-accounts-of-type' one
       in that it filters out inactive (revoked) accounts by default. However,
       this behavior can be overridden by providing the exact '?filter-pred'.
       In the latter case, the most recent general account will be returned."
  ([chat-data]
   (get-current-general-account chat-data is-account-active?))
  ([chat-data ?filter-pred]
   (let [gen-accs (get-accounts-of-type chat-data
                                        :acc-type/general
                                        ?filter-pred)]
     (if (< 1 (count gen-accs))
       (do
         (log/warnf "There is more than 1 suitable general account in chat=%s"
                    (:id chat-data)) ;; it might be ok for custom 'filter-pred'
         (apply max-key :id gen-accs))
       (first gen-accs)))))

(defn- create-general-account!
  [chat-id created-dt & {:keys [remove-member add-member] :as _opts}]
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [old-gen-acc (get-current-general-account chat-data)
                  old-members (->> (get-accounts-of-type chat-data
                                                         :acc-type/personal
                                                         is-account-active?)
                                   (map :id)
                                   set)
                  new-members (cond-> old-members
                                      (some? add-member) (conj add-member)
                                      (some? remove-member) (disj remove-member))

                  upd-old-general-acc-fn
                  (fn [cd]
                    (if (some? old-gen-acc)
                      (assoc-in cd
                                [:accounts :acc-type/general (:id old-gen-acc) :revoked]
                                created-dt)
                      cd))

                  add-new-general-acc-fn
                  (fn [cd]
                    ;; IMPLEMENTATION NOTE:
                    ;; Here the chat 'members-count' have to already be updated!
                    (if (is-chat-for-group-accounting? (get-members-count cd))
                      (let [next-id (get-accounts-next-id cd)
                            acc-name (:name old-gen-acc default-general-acc-name)
                            new-general-acc (->general-account
                                              next-id acc-name created-dt new-members)]
                        (-> cd
                            (assoc-in [:accounts :last-id] next-id)
                            (assoc-in [:accounts :acc-type/general next-id] new-general-acc)))
                      cd))]
              (-> chat-data
                  upd-old-general-acc-fn
                  add-new-general-acc-fn))))]
    (get-current-general-account updated-chat-data)))

;; - CHATS > ACCOUNTS > PERSONAL

(defn- get-personal-account-id
  "A convenient way to get both user and virtual accounts ID."
  [chat-data {?user-id :user-id ?acc-name :name :as _ids}]
  {:pre [(or (some? ?user-id) (some? ?acc-name))]}
  (if (some? ?user-id)
    (get-in chat-data [:user-account-mapping ?user-id])
    (:id (find-account-by-name chat-data :acc-type/personal ?acc-name))))

(defn- set-personal-account-id
  [chat-data user-id pers-acc-id]
  {:pre [(some? user-id)]}
  (assoc-in chat-data [:user-account-mapping user-id] pers-acc-id))

(defn- get-personal-account
  [chat-data {?user-id :user-id ?acc-name :name ?acc-id :id :as ids}]
  {:pre [(or (some? ?user-id) (some? ?acc-name) (some? ?acc-id))]}
  (cond
    (some? ?acc-id)
    (get-account-by-id chat-data :acc-type/personal ?acc-id)

    (some? ?acc-name)
    (find-account-by-name chat-data :acc-type/personal ?acc-name)

    (some? ?user-id)
    (let [pers-acc-id (get-personal-account-id chat-data ids)]
      (get-account-by-id chat-data :acc-type/personal pers-acc-id))))

(defn- create-personal-account!
  [chat-id acc-name created-dt & {?user-id :user-id ?first-msg-id :first-msg-id :as _opts}]
  (if (or (nil? ?user-id)
          (let [pers-acc (get-personal-account (get-chat-data chat-id) {:user-id ?user-id})] ;; petty RC
            (or (nil? pers-acc) (is-account-revoked? pers-acc))))
    (create-named-account!
      chat-id :acc-type/personal acc-name
      (fn [chat-data acc-id]
        (let [pers-acc (->personal-account acc-id acc-name created-dt
                                           ?user-id ?first-msg-id)]
          (cond-> (assoc-in chat-data [:accounts :acc-type/personal acc-id] pers-acc)
                  (some? ?user-id) (set-personal-account-id ?user-id acc-id)))))
    :failure/user-already-has-an-active-account))

;; - CHATS > ACCOUNTS > GROUP

(defn- get-group-account
  [chat-data {?acc-name :name ?acc-id :id :as _ids}]
  {:pre [(or (some? ?acc-name) (some? ?acc-id))]}
  (cond
    (some? ?acc-id)
    (get-account-by-id chat-data :acc-type/group ?acc-id)

    (some? ?acc-name)
    (find-account-by-name chat-data :acc-type/group ?acc-name)))

(defn- create-group-account!
  [chat-id acc-name created-dt created-by members-ids]
  (create-named-account!
    chat-id :acc-type/group acc-name
    (fn [chat-data acc-id]
      (let [group-acc (->group-account acc-id acc-name created-dt created-by members-ids)]
        (assoc-in chat-data [:accounts :acc-type/group acc-id] group-acc)))))

;; - CHATS > BOT MESSAGES

(defn- get-bot-msg
  [chat-data msg-id]
  (get-in chat-data [:bot-messages msg-id]))

(defn- set-bot-msg!
  [chat-id msg-id {:keys [type] :as ?props}]
  {:pre [(or (nil? ?props) (some? type))]}
  (assoc-in-chat-data! chat-id [:bot-messages msg-id] ?props))

(defn- find-bot-messages
  [chat-data {:keys [type to-user] :as _props}]
  (cond->> (:bot-messages chat-data)
           (some? type) (filter #(= type (-> % val :type)))
           (some? to-user) (filter #(= to-user (-> % val :to-user)))))

(defn- drop-bot-msg!
  [chat-id {:keys [type to-user] :as props}]
  {:pre [(or (some? type) (some? to-user))]}
  (let [to-drop (map key (find-bot-messages (get-chat-data chat-id) props))]
    (update-chat-data! chat-id
                       update-in [:bot-messages] #(apply dissoc % to-drop))
    to-drop))

(defn- get-original-bot-msg
  [chat-id reply-msg]
  ;; NB: There's always only one original msg.
  (->> (:bot-messages (get-chat-data chat-id))
       (filter #(tg-api/is-reply-to? (key %) reply-msg))
       (map (fn [[k v]] (assoc v :msg-id k)))
       first))

(defn- check-bot-msg
  [bot-msg {:keys [type to-user] :as props}]
  {:pre [(or (some? type) (some? to-user))]}
  (when (some? bot-msg)
    (every? #(= (val %) (-> % key bot-msg)) props)))

(defn- change-bot-msg-state!
  [chat-id msg-id msg-type-states new-state]
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [bot-msg (get-bot-msg chat-data msg-id)
                  curr-state (:state bot-msg)]
              (if (or (nil? curr-state) ;; to set an initial state
                      (contains? (get msg-type-states curr-state) new-state))
                (assoc-in chat-data [:bot-messages msg-id :state] new-state)
                (do
                  (log/errorf "Failed to change state from '%s' to '%s' for message=%s in chat=%s"
                              curr-state new-state msg-id chat-id)
                  chat-data)))))]
    (:state (get-bot-msg updated-chat-data msg-id))))

;; - CHATS > GROUP CHAT

(defn- get-chat-title
  [chat-data]
  (:title chat-data))

(defn- set-chat-title!
  [chat-id chat-title]
  (assoc-in-chat-data! chat-id [:title] chat-title))

;; - CHATS > GROUP CHAT > EXPENSE ITEMS

(defn- get-group-chat-expense-items
  [chat-id]
  (let [chat-data (get-chat-data chat-id)]
    ;; TODO: Sort them according popularity.
    (get-in chat-data [:expenses :items])))

;; TODO: Implement the "expense items"-related business logic here.

;; - CHATS > GROUP CHAT > ACCOUNTS

(def ^:private all-account-types
  [:acc-type/personal :acc-type/group :acc-type/general])

(defn- get-group-chat-accounts
  "Retrieves accounts of all or specific types (if the 'acc-types' is present)
   for a group chat with the specified 'chat-id'.
   NB: This function differs from the plain vanilla 'get-accounts-of-type' one
       in that it filters out inactive (revoked) accounts by default. However,
       this behavior can be overridden by providing the exact 'filter-pred'."
  ;; TODO: Sort them according popularity.
  ([chat-id]
   (get-group-chat-accounts chat-id
                            {:acc-types all-account-types}))
  ([chat-id {:keys [acc-types filter-pred sort-by-popularity]
             :or {acc-types all-account-types
                  filter-pred is-account-active?}}]
   (if (< 1 (count acc-types))
     ;; multiple types
     (->> acc-types
          (mapcat #(get-group-chat-accounts chat-id
                                            {:acc-types [%]
                                             :filter-pred filter-pred})))
     ;; single type
     (when-let [chat-data (get-chat-data chat-id)]
       (get-accounts-of-type chat-data (first acc-types) filter-pred)))))

(defn- get-group-chat-accounts-by-type
  "Retrieves accounts, grouped in a map by account type, for a group chat with
   the specified 'chat-id'.
   NB: This function differs from the plain vanilla 'get-accounts-of-type' one
       in that it filters out inactive (revoked) accounts by default. However,
       this behavior can be overridden by providing the exact '?filter-pred'."
  ([chat-id]
   (get-group-chat-accounts-by-type chat-id is-account-active?))
  ([chat-id ?filter-pred]
   (group-by :type
             (get-group-chat-accounts chat-id
                                      {:acc-types all-account-types
                                       :filter-pred ?filter-pred}))))

(defn- is-not-virtual-member?
  [acc]
  (and (is-account-active? acc)
       (some? (:user-id acc))))

(defn- get-number-of-missing-personal-accounts
  "Returns the number of missing personal accounts in a group chat,
   which have to be created before the group chat is ready for the
   expenses accounting."
  [chat-id]
  (let [chat-members-count (get-members-count (get-chat-data chat-id))
        existing-pers-accs (->> {:acc-types [:acc-type/personal]
                                 :filter-pred is-not-virtual-member?}
                                (get-group-chat-accounts chat-id)
                                count)]
    (- chat-members-count existing-pers-accs 1)))

(defn- is-group-acc-member?
  [chat-id group-acc user-id]
  (let [chat-data (get-chat-data chat-id)
        pers-acc-id (get-personal-account-id chat-data {:user-id user-id})
        group-acc-members (:members group-acc)]
    (contains? group-acc-members pers-acc-id)))

(defn- all-group-acc-members-are-inactive?
  [chat-id group-acc]
  (let [group-acc-members (:members group-acc)]
    (->> {:acc-types [:acc-type/personal]
          :filter-pred #(and (is-account-active? %)
                             (contains? group-acc-members (:id %)))}
         (get-group-chat-accounts chat-id)
         empty?)))

(defn- can-rename-account?
  [chat-id user-id]
  (fn [acc]
    (and (is-account-active? acc)
         (or (and (= :acc-type/personal (:type acc))
                  (contains? #{user-id nil} (:user-id acc)))
             (and (= :acc-type/group (:type acc))
                  (or (= (:created-by acc) user-id)
                      (is-group-acc-member? chat-id acc user-id)
                      (all-group-acc-members-are-inactive? chat-id acc)))))))

(defn- can-revoke-account?
  [chat-id user-id]
  (fn [acc]
    (and (is-account-active? acc)
         (or (and (= :acc-type/personal (:type acc))
                  (not= (:user-id acc) user-id))
             (and (= :acc-type/group (:type acc))
                  (or (= (:created-by acc) user-id)
                      (is-group-acc-member? chat-id acc user-id)
                      (all-group-acc-members-are-inactive? chat-id acc)))))))

(defn- can-reinstate-account?
  [_chat-id _user-id]
  (fn [acc]
    (is-account-revoked? acc)))

(defn- get-group-chat-account
  [chat-data {acc-type :type :as props}]
  (case acc-type
    :acc-type/personal
    (get-personal-account chat-data props)
    :acc-type/group
    (get-group-account chat-data props)
    :acc-type/general
    (get-current-general-account chat-data)))

(defn- is-group-chat-eligible-for-selection?
  "Checks the ability of a specified user to select a given group chat."
  [chat-id user-id]
  (let [chat-data (get-chat-data chat-id)
        pers-acc (get-group-chat-account chat-data {:type :acc-type/personal
                                                    :user-id user-id})]
    (is-account-active? pers-acc)))

(defn- data->account
  "Retrieves a group chat's account by parsing the callback button data."
  [callback-btn-data chat-data]
  (let [account (str/replace-first callback-btn-data cd-account-prefix "")
        account-path (str/split account (re-pattern id-separator))]
    (get-group-chat-account chat-data
                            {:type (keyword "acc-type" (nth account-path 0))
                             :id (nums/parse-int (nth account-path 1))})))

(defn- create-group-chat-account!
  [chat-id {:keys [acc-type acc-name created-dt created-by members]}]
  {:pre [(some? acc-type) (some? acc-name) (some? created-dt)]}
  (case acc-type
    :acc-type/personal (when-some [pers-acc (create-personal-account! chat-id acc-name created-dt)]
                         (create-general-account! chat-id created-dt)
                         pers-acc)
    :acc-type/group (let [members-ids (map :id members)]
                      (create-group-account! chat-id acc-name created-dt created-by members-ids))))

(defn- change-group-chat-account-name!
  [chat-id {:keys [acc-type acc-id new-acc-name]}]
  {:pre [(some? acc-type) (some? acc-id) (some? new-acc-name)]}
  (let [updated-chat-data
        (conditionally-update-chat-data!
          chat-id
          #(nil? (find-account-by-name % acc-type new-acc-name))
          assoc-in [:accounts acc-type acc-id :name] new-acc-name)]
    (if (some? updated-chat-data)
      (get-group-chat-account updated-chat-data {:type acc-type
                                                 :id acc-id})
      :failure/the-account-name-is-already-taken)))

(defn- change-account-activity-status!
  "Changes the activity status of a personal or group account (just marks it as
   'revoked' or removes this mark) and, for personal accounts only, updates the
   general account (revokes an existing and creates a new version, if needed)."
  [chat-id acc {:keys [revoke? reinstate? datetime] :as _activity_status_upd}]
  {:pre [(or (some? revoke?) (some? reinstate?))
         (not (and (some? revoke?) (some? reinstate?)))
         (contains? #{:acc-type/personal :acc-type/group} (:type acc))]}
  (let [acc-id (:id acc)
        acc-type (:type acc)
        updated-chat-data
        (conditionally-update-chat-data!
          chat-id
          #(some? (get-account-by-id % acc-type acc-id))
          (fn [chat-data]
            (cond-> chat-data
                    (true? revoke?)
                    (assoc-in [:accounts acc-type acc-id :revoked] datetime)
                    (true? reinstate?)
                    (update-in [:accounts acc-type acc-id] dissoc :revoked))))]
    (when (= :acc-type/personal (:type acc))
      (let [member-action (cond
                            (true? revoke?) :remove-member
                            (true? reinstate?) :add-member)]
        (create-general-account! chat-id datetime member-action acc-id)))
    (get-account-by-id updated-chat-data acc-type acc-id)))

;; - CHATS > GROUP CHAT > USER INPUT

(defn- get-user-input-data
  [chat-data user-id input-name]
  (get-in chat-data [:input user-id input-name]))

(defn- set-user-input-data!
  [chat-id user-id input-name ?input-data]
  (if (nil? ?input-data)
    (update-chat-data! chat-id
                       update-in [:input user-id] dissoc input-name)
    (update-chat-data! chat-id
                       assoc-in [:input user-id input-name] ?input-data)))

(defn- drop-user-input-data!
  [chat-id user-id]
  (update-chat-data! chat-id
                     update-in [:input] dissoc user-id))

(defn- clean-up-chat-data!
  [chat-id {:keys [bot-messages input]}]
  (doseq [bot-msg bot-messages]
    (drop-bot-msg! chat-id bot-msg))
  (doseq [user-input input]
    (if (contains? user-input :name)
      (set-user-input-data! chat-id (:user user-input) (:name user-input) nil)
      (drop-user-input-data! chat-id (:user user-input)))))

(defn- release-message-lock!
  "Releases the \"input lock\" acquired by the user for the message in chat."
  [chat-id user-id msg-id]
  (update-chat-data!
    chat-id
    (fn [chat-data]
      (let [user-locked (get-user-input-data chat-data user-id :locked-messages)]
        (if (contains? user-locked msg-id)
          (update-in chat-data [:input user-id :locked-messages] set/difference #{msg-id})
          chat-data))))
  nil)

(defn- acquire-message-lock!
  "Acquires an \"input lock\" for a specific message in chat and the specified
   user, but only if the message has not yet been locked by another user.

   IMPLEMENTATION NOTE:
   The chat data stores IDs of all locked message for each member in ':input',
   so that none of the users can intercept the input of another and interfere
   with it. These locks are time-limited — to protect against forgetful users'
   inactivity.
   Moreover, since the user-specific input data is auto-erased when the user
   leaves/is removed from the chat, these message locks will also be released
   automatically, and immediately."
  [chat-id user-id msg-id]
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [all-locked (->> (vals (:input chat-data))
                                  (map :locked-messages)
                                  (reduce #(set/union %1 %2) #{}))
                  user-locked (get-user-input-data chat-data user-id :locked-messages)]
              (if (or (not (contains? all-locked msg-id))
                      (contains? user-locked msg-id))
                (update-in chat-data [:input user-id :locked-messages] set/union #{msg-id})
                chat-data))))
        user-locked (get-user-input-data updated-chat-data user-id :locked-messages)
        has-message-lock? (contains? user-locked msg-id)]
    (when has-message-lock?
      (go
        (<! (timeout (* 5 60 1000))) ;; after 5 minutes
        (release-message-lock! chat-id user-id msg-id)))
    has-message-lock?))

;; - CHATS > PRIVATE CHAT

(defn- can-write-to-user?
  [user-id]
  (let [chat-state (-> user-id get-chat-data get-chat-state)]
    (and (some? chat-state)
         (not= default-chat-state chat-state))))

(defn- get-private-chat-groups
  [chat-data]
  (get chat-data :groups))

;; NB: This is a design decision to only accumulate groups and not delete them.
(defn- update-private-chat-groups!
  ([chat-id new-group-chat-id]
   (let [updated-chat-data
         (update-chat-data! chat-id
                            update :groups conj new-group-chat-id)]
     (get-private-chat-groups updated-chat-data)))
  ([chat-id old-group-chat-id new-group-chat-id]
   (let [swap-ids-fn
         (comp set (partial replace {old-group-chat-id new-group-chat-id}))
         updated-chat-data (update-chat-data! chat-id
                                              update :groups swap-ids-fn)]
     (get-private-chat-groups updated-chat-data))))

(defn- ->group-ref
  [group-chat-id group-chat-title]
  {:id group-chat-id
   :title group-chat-title})

;; - CHATS > PRIVATE CHAT > USER INPUT

(defn- get-user-input
  [chat-data]
  (get chat-data :user-input))

(defn- update-user-input!
  [chat-id {:keys [type data] :as _operation}]
  (let [append-digit
        (fn [old-val]
          (if (or (nil? old-val)
                  (empty? old-val))
            (when (not= "0" data) data)
            (str (or old-val "") data)))
        append-ar-op
        (fn [old-val]
          (if (or (nil? old-val)
                  (empty? old-val))
            old-val ;; do not allow
            (let [last-char (-> old-val
                                str/trim
                                last
                                str)]
              (if (contains? cd-ar-ops-set
                             last-char)
                old-val ;; do not allow
                (str (or old-val "")
                     " " data " ")))))
        cancel
        (fn [old-val]
          (let [trimmed (str/trim old-val)]
            (if (< 0 (count trimmed))
              (-> trimmed
                  (subs 0 (dec (count trimmed)))
                  str/trim)
              trimmed)))
        clear
        (constantly nil)

        updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [old-val (get chat-data :user-input)
                  new-val ((case type
                             :append-digit append-digit
                             :append-ar-op append-ar-op
                             :cancel cancel
                             :clear clear) old-val)]
              (assoc chat-data :user-input new-val))))]
    (get-user-input updated-chat-data)))

;; - CHATS > SUPERGROUP CHAT

(defn- migrate-group-chat-to-supergroup!
  [chat-id migrate-to-chat-id]
  (let [new-chat (setup-new-supergroup-chat! migrate-to-chat-id chat-id)
        chat-data (if (some? new-chat)
                    (:data new-chat)
                    (get-chat-data chat-id))
        group-users (-> chat-data
                        (get :user-account-mapping)
                        keys)]
    ;; NB: Here we iterate only real users, and this is exactly what we need.
    (doseq [user-id group-users]
      (update-private-chat-groups! user-id chat-id migrate-to-chat-id))))


;; RESPONSES

(def ^:private responses
  {:chat-type/group
   {:introduction-msg
    {:response-fn get-introduction-msg
     :response-params [:chat-members-count :first-name]}

    :personal-account-name-request-msg
    {:response-fn get-personal-account-name-request-msg
     :response-params [:existing-chat? :chat-members]}

    :personal-accounts-left-msg
    {:response-fn get-personal-accounts-left-msg
     :response-params [:count]}

    :bot-readiness-msg
    {:response-fn get-bot-readiness-msg
     :response-params [:bot-username]}

    :settings-msg
    {:response-fn get-settings-msg
     :response-params [:first-time?]}

    :new-account-name-request-msg
    {:response-fn get-new-account-name-request-msg
     :response-params [:user]}

    :the-name-is-already-taken-msg
    {:response-fn get-the-name-is-already-taken-msg
     :response-params [:user]}

    :group-members-selection-msg
    {:response-fn get-group-members-selection-msg
     :response-params [:user :selected :remaining]}

    :new-group-members-msg
    {:response-fn get-new-group-members-msg
     :response-params [:acc-names]}

    :account-rename-request-msg
    {:response-fn get-account-rename-request-msg
     :response-params [:user :acc-name]}

    :successful-changes-msg
    {:response-fn (constantly successful-changes-msg)}
    :canceled-changes-msg
    {:response-fn (constantly canceled-changes-msg)}

    :waiting-for-user-input-notification
    {:response-fn (constantly waiting-for-user-input-notification)}
    :waiting-for-other-user-notification
    {:response-fn (constantly waiting-for-other-user-notification)}
    :message-already-in-use-notification
    {:response-fn (constantly message-already-in-use-notification)}

    :no-eligible-accounts-notification
    {:response-fn (constantly no-eligible-accounts-notification)}
    :no-group-members-selected-notification
    {:response-fn (constantly no-group-members-selected-notification)}

    ; message-specific

    :settings
    {:accounts-mgmt-options-msg
     {:response-fn get-accounts-mgmt-options-msg
      :response-params [:accounts-by-type]}

     :account-type-selection-msg
     {:response-fn get-account-type-selection-msg
      :response-params [:account-types :extra-buttons]}

     :account-selection-msg
     {:response-fn get-account-selection-msg
      :response-params [:accounts :txt :extra-buttons]}

     :restored-settings-msg
     {:response-fn (partial get-settings-msg false)}}}

   :chat-type/private
   {:private-introduction-msg
    {:response-fn get-private-introduction-msg
     :response-params [:first-name]}

    :inline-calculator-msg
    {:response-fn get-inline-calculator-msg
     :response-params [:new-user-input]}
    :interactive-input-success-msg
    {:response-fn get-interactive-input-success-msg
     :response-params [:parsed-val]}
    :interactive-input-disclaimer-msg
    {:response-fn (constantly interactive-input-disclaimer-msg)}

    :invalid-input-msg
    {:response-fn (constantly invalid-input-msg)}

    :group-selection-msg
    {:response-fn get-group-selection-msg
     :response-params [:group-refs]}
    :expense-item-selection-msg
    {:response-fn get-expense-item-selection-msg
     :response-params [:expense-items]}
    :expense-manual-description-msg
    {:response-fn get-expense-manual-description-msg
     :response-params [:first-name]}
    :account-selection-msg
    {:response-fn get-account-selection-msg
     :response-params [:accounts :txt]}
    :new-expense-msg
    {:response-fn get-new-expense-msg
     :response-params [:expense-amount :expense-details
                       :payer-acc-name :debtor-acc-name]}

    :added-to-new-group-msg
    {:response-fn get-added-to-new-group-msg
     :response-params [:chat-title]}
    :removed-from-group-msg
    {:response-fn get-removed-from-group-msg
     :response-params [:chat-title]}

    :invalid-input-notification
    {:response-fn (constantly invalid-input-notification)}

    :expense-added-successfully-msg
    {:response-fn (constantly expense-added-successfully-msg)}
    :failed-to-add-new-expense-msg
    {:response-fn get-failed-to-add-new-expense-msg
     :response-params [:reason]}}})


;; STATES & STATE TRANSITIONS

;; TODO: Re-write with an existing state machine (FSM) library.

(defn- to-response
  [{:keys [response-fn response-params]} ?param-vals]
  (when (some? response-fn)
    (apply response-fn (map (or ?param-vals {}) response-params))))

(defn- handle-state-transition!
  [event state-transitions change-state-fn]
  (let [transition-keys (:transition event)
        transition (get-in state-transitions transition-keys)
        chat-type (first transition-keys)
        response-keys (utils/collect [chat-type] (:response transition))
        response-data (get-in responses response-keys)]
    (change-state-fn (:to-state transition))
    (to-response response-data (:param-vals event))))

; chat states

;; TODO: There is a serious issue of a chat transition happening right before the exception happens.
;;       Both private chats state and group chats' message state are vulnerable to this.
;;       Solution: surround all update handlers with transaction boundaries.

(def ^:private group-chat-states
  {:initial #{:managed
              :evicted}
   :managed #{:managed
              :evicted}
   :evicted #{:managed}})

(def ^:private private-chat-states
  {:initial #{:input}
   :input {:to #{:group-selection
                 :expense-detailing
                 :interactive-input
                 :input}
           :init-fn (fn [chat-data]
                      (select-keys chat-data [:groups]))}
   :interactive-input #{:group-selection
                        :expense-detailing
                        :input}
   :group-selection #{:expense-detailing
                      :input}
   :expense-detailing #{:account-selection
                        :input}
   :account-selection #{:input}})

(def ^:private chat-state-transitions
  {:chat-type/group
   {:mark-managed
    {:to-state :managed
     :response :bot-readiness-msg}
    :mark-evicted
    {:to-state :evicted}}

   :chat-type/private
   {:request-amount
    {:to-state :input
     :response :private-introduction-msg}
    :show-calculator
    {:to-state :interactive-input
     :response :inline-calculator-msg}
    :select-group
    {:to-state :group-selection
     :response :group-selection-msg}
    :select-expense-item
    {:to-state :expense-detailing
     :response :expense-item-selection-msg}
    :request-expense-desc
    {:to-state :expense-detailing
     :response :expense-manual-description-msg}
    :select-account
    {:to-state :account-selection
     :response :account-selection-msg}

    :cancel-input
    {:to-state :input}
    :notify-input-success
    {:to-state :input
     :response :expense-added-successfully-msg}
    :notify-input-failure
    {:to-state :input
     :response :failed-to-add-new-expense-msg}}})

(defn- handle-chat-state-transition!
  [chat-id event]
  (let [chat-type (first (:transition event))
        chat-type-states (case chat-type
                           :chat-type/group group-chat-states
                           :chat-type/private private-chat-states)
        change-state-fn (partial change-chat-state! chat-id chat-type-states)]
    (handle-state-transition! event chat-state-transitions change-state-fn)))

; message states

(def ^:private group-msg-states
  {:settings
   {:initial #{:accounts-mgmt
               :expense-items-mgmt
               :shares-mgmt}

    :accounts-mgmt #{:account-type-selection
                     :account-renaming
                     :account-revocation
                     :account-reinstatement
                     :initial}
    :account-type-selection #{:accounts-mgmt
                              :initial}
    :account-renaming #{:accounts-mgmt
                        :initial}
    :account-revocation #{:accounts-mgmt
                          :initial}
    :account-reinstatement #{:accounts-mgmt
                             :initial}

    :expense-items-mgmt #{:initial}

    :shares-mgmt #{:initial}}})

(def ^:private msg-state-transitions
  {:chat-type/group
   {:settings
    {:manage-accounts
     {:to-state :accounts-mgmt
      :response [:settings :accounts-mgmt-options-msg]}
     :select-acc-type
     {:to-state :account-type-selection
      :response [:settings :account-type-selection-msg]}
     :rename-account
     {:to-state :account-renaming
      :response [:settings :account-selection-msg]}
     :revoke-account
     {:to-state :account-revocation
      :response [:settings :account-selection-msg]}
     :reinstate-account
     {:to-state :account-reinstatement
      :response [:settings :account-selection-msg]}

     :manage-expense-items
     {:to-state :expense-items-mgmt}

     :manage-shares
     {:to-state :shares-mgmt}

     :restore
     {:to-state :initial
      :response [:settings :restored-settings-msg]}}}})

(defn- handle-msg-state-transition!
  [chat-id msg-id msg-event]
  (let [chat-type (first (:transition msg-event))
        all-msg-types-states (case chat-type
                               :chat-type/group group-msg-states)
        msg-type (second (:transition msg-event))
        msg-type-states (get all-msg-types-states msg-type)
        change-state-fn (partial change-bot-msg-state! chat-id msg-id msg-type-states)]
    (handle-state-transition! msg-event msg-state-transitions change-state-fn)))


;; RECIPROCAL ACTIONS

;; TODO: Switch to Event-Driven model. Simplifies?
;; HTTP requests should be transformed into events
;; that are handled by appropriate listeners (fns)
;; that, in turn, may result in emitting events.

;; - ABSTRACT ACTIONS

(defmulti ^:private send!
          (fn [_token _ids response _opts] (:type response)))

(defmethod send! :text
  [token {:keys [chat-id msg-id] :as _ids}
   {:keys [text options] :as _response} {:keys [replace?] :as _opts}]
  (if (is-bot-evicted-from-chat? chat-id)
    (log/debug "Dropped sending a message to an evicted chat=" chat-id)
    ;; NB: Looks for the 'replace?' among the passed options to replace
    ;;     the existing response message rather than sending a new one.
    (if-not (true? replace?)
      (m-api/send-text token chat-id options text)
      (m-api/edit-text token chat-id msg-id options text))))

(defmethod send! :inline
  [token {:keys [inline-query-id] :as _ids}
   {:keys [results options] :as _response} _opts]
  (m-api/answer-inline token inline-query-id options results))

(defmethod send! :callback
  [token {:keys [callback-query-id] :as _ids}
   {:keys [options] :as _response} _opts]
  (tg-client/answer-callback-query token callback-query-id options))

(defn- make-tg-bot-api-request!
  "Makes a request to the Telegram Bot API. By default, this function awaits the feedback
   (a Telegram's response) synchronously and returns a pre-defined code ':finished-sync'.

   Options are key-value pairs and may be one of:
   :async?
     An indicator to make the whole process asynchronous (causes the fn
     to return immediately with the ':launched-async' code)
   :on-failure
     A fn used to handle an exception in case of the request failure
   :on-success
     A fn used to handle the Telegram's response in case of the successful request

   NB: Properly wrapped in try-catch and logged to highlight the exact HTTP client error."
  [request-fn {:keys [async? on-failure on-success] :as _?options}]
  (let [token (config/get-prop :bot-api-token)
        handle-tg-bot-api-req (fn []
                                (try
                                  (request-fn token)
                                  (catch Throwable t
                                    (if (some? on-failure)
                                      (on-failure t)
                                      (log/error t "Failed making a Telegram Bot API request"))
                                    :req-failed)))
        handle-tg-response-fn (fn [tg-response]
                                (log/debug "Telegram returned:" tg-response)
                                (try
                                  ;; TODO: There are 2 possible outcomes. Do we need to differentiate them somehow?
                                  ;;       - successful request ('ok' equals true)
                                  ;;       - unsuccessful request ('ok' equals false, error is in the 'description')
                                  (when (and (some? on-success)
                                             (true? (:ok tg-response)))
                                    (on-success (:result tg-response)))
                                  (catch Exception e
                                    (log/error e "Failed to handle the response from Telegram"))))
        handle-when-succeeded (fn [res]
                                (when-not (= :req-failed res)
                                  (handle-tg-response-fn res)))]
    (if (true? async?)
      (letfn [(with-one-off-channel [req-fn handle-fn]
                (let [resp-chan (chan)]
                  (go (handle-fn (<! resp-chan))
                      (close! resp-chan))
                  (go (>! resp-chan (req-fn)))))]
        (with-one-off-channel handle-tg-bot-api-req handle-when-succeeded)
        :launched-async)
      (do
        (handle-when-succeeded (handle-tg-bot-api-req))
        :finished-sync))))

(defn- respond!
  "Uniformly responds to the user action, whether it a message, inline or callback query,
   or some event (e.g. service message or chat member status update).
   This fn takes an optional parameter, which is an options map of a specific API method."
  ([ids response]
   (respond! ids response nil))
  ([ids response ?options]
   (make-tg-bot-api-request!
     (fn [token]
       (send! token ids response ?options))
     (assoc ?options
       ;; TODO: Add the 'response' to the 'failed-responses' queue for the bot Admin
       ;;       to be able to manually handle it later, if this feature is necessary.
       :on-failure #(log/errorf % "Failed to respond to %s with %s" ids response)))))

(defn- respond!*
  "A variant of the 'respond!' function that is used with the 'keys' of one of
   the predefined responses, rather than with an arbitrary response value."
  [ids response-keys & {:keys [param-vals] :as ?options}]
  {:pre [(vector? response-keys)]}
  (encore/when-let [response-data (get-in responses response-keys)
                    response (to-response response-data param-vals)]
    (respond! ids response
              (dissoc ?options :param-vals))))

;; TODO: GET RID OF MOST OF THESE !-FNS MAKING THEM PURE AND PASSING THEM DATA!

(defn- proceed-with-chat-and-respond!
  "Continues the course of transitions between the states of the chat and sends
   a message (answers an inline/callback query) in response to a user/an event."
  [{:keys [chat-id] :as ids} event & {:as ?options}]
  {:pre [(some? chat-id)]}
  (when-let [response (handle-chat-state-transition! chat-id event)]
    (respond! ids response ?options)))

(defn- proceed-with-msg-and-respond!
  "Continues the course of transitions between the states of the extant message
   in some chat and replaces its content in response to a user/an event."
  [{:keys [chat-id msg-id] :as ids} msg-event & {:as ?options}]
  {:pre [(some? chat-id) (some? msg-id)]}
  (when-let [response (handle-msg-state-transition! chat-id msg-id msg-event)]
    (respond! ids response
              (assoc ?options :replace? true))))

(defn- delete-text!
  [token {:keys [chat-id msg-id] :as _ids}]
  (if (is-bot-evicted-from-chat? chat-id)
    (log/debug "Dropped deleting the bot's response from an evicted chat=" chat-id)
    (m-api/delete-text token chat-id msg-id)))

(defn- delete-response!
  "Deletes one of the bot's previous response messages.
   NB: - A message can only be deleted if it was sent less than 48 hours ago.
       - Bots can delete outgoing messages in private chats, groups, and supergroups.
   This fn takes an optional parameter, which is an options map of a specific API method."
  ([ids]
   (delete-response! ids nil))
  ([ids ?options]
   (make-tg-bot-api-request!
     (fn [token]
       (delete-text! token ids))
     (assoc ?options
       :on-failure #(log/errorf % "Failed to delete the response message %s" ids)))))

;; - SPECIFIC ACTIONS

(defn- send-retry-command!
  [{{chat-id :id} :chat :as message}]
  (let [commands-to-retry (tg-client/get-commands {:message message})]
    (respond! {:chat-id chat-id}
              (get-retry-commands-msg commands-to-retry))))

(defn- send-retry-message!
  [{{chat-id :id} :chat :as _message}]
  (respond! {:chat-id chat-id}
            retry-resend-message-msg))

(defn- send-retry-callback-query!
  [{callback-query-id :id :as _callback-query}]
  (respond! {:callback-query-id callback-query-id}
            retry-callback-query-notification))

(defn- notify-of-inconsistent-chat-state!
  [{{chat-id :id} :chat :as _chat-member-updated-or-message}]
  (log/errorf "Chat=%s has entered an inconsistent state" chat-id)
  '(notify-bot-admin)) ;; TODO: Implement Admin notifications feature.

; private chats

(defn- report-to-user!
  [user-id response-keys param-vals]
  (when (can-write-to-user? user-id)
    (respond!* {:chat-id user-id} response-keys :param-vals param-vals)))


(defn- proceed-with-adding-new-expense!
  [chat-id debtor-acc]
  (let [chat-data (get-chat-data chat-id)
        group-chat-id (:group chat-data)
        group-chat-data (get-chat-data group-chat-id)
        payer-acc-id (get-personal-account-id
                       group-chat-data {:user-id chat-id})
        debtor-acc-id (:id debtor-acc)
        expense-amount (:amount chat-data)
        expense-details (or (:expense-item chat-data)
                            (:expense-desc chat-data))
        new-transaction {:chat-id group-chat-id
                         :payer-acc-id payer-acc-id
                         :debtor-acc-id debtor-acc-id
                         :expense-amount expense-amount
                         :expense-details expense-details}]
    (slingshot/try+
      (add-transaction! (tlogs/create! new-transaction))
      (catch Exception e
        ;; TODO: Retry to log the failed transaction?
        (log/error e "Failed to log transaction:" new-transaction)
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :notify-input-failure]
           :param-vals {:reason data-persistence-error}}))
      (else
        (let [pers-accs-count (->> {:acc-types [:acc-type/personal]}
                                   (get-group-chat-accounts chat-id)
                                   count)
              payer-acc-name (when-not (or (= pers-accs-count 1)
                                           (= payer-acc-id debtor-acc-id))
                               (:name (get-group-chat-account
                                        group-chat-data {:type :acc-type/personal
                                                         :id payer-acc-id})))
              debtor-acc-name (when-not (= pers-accs-count 1)
                                (:name debtor-acc))]
          (respond!* {:chat-id group-chat-id}
                     [:chat-type/private :new-expense-msg]
                     :param-vals {:expense-amount expense-amount
                                  :expense-details expense-details
                                  :payer-acc-name payer-acc-name
                                  :debtor-acc-name debtor-acc-name}))
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :notify-input-success]})))))

;; TODO: Abstract this away — "selecting 1 of N, with a special case for N=1".
(defn- proceed-with-account!
  [chat-id]
  (let [group-chat-id (:group (get-chat-data chat-id))
        active-accounts (get-group-chat-accounts group-chat-id)]
    (cond
      (< 1 (count active-accounts))
      (let [other-accounts (filter #(not= (:user-id %) chat-id) active-accounts)]
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :select-account]
           :param-vals {:accounts other-accounts
                        :txt select-payer-account-txt}}))

      (empty? active-accounts)
      (do
        (log/error "No eligible accounts to select a debtor account from")
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :notify-input-failure]
           :param-vals {:reason no-debtor-account-error}}))

      :else
      (let [debtor-acc (first active-accounts)]
        (log/debug "Debtor account auto-selected:" debtor-acc)
        (proceed-with-adding-new-expense! chat-id debtor-acc)))))

(defn- proceed-with-expense-details!
  [chat-id group-chat-id first-name]
  (let [expense-items (get-group-chat-expense-items group-chat-id)
        event (if (seq expense-items)
                {:transition [:chat-type/private :select-expense-item]
                 :param-vals {:expense-items expense-items}}
                {:transition [:chat-type/private :request-expense-desc]
                 :param-vals {:first-name first-name}})]
    (proceed-with-chat-and-respond! {:chat-id chat-id} event)))

;; TODO: Abstract this away — "selecting 1 of N, with a special case for N=1".
(defn- proceed-with-group!
  [chat-id first-name]
  (let [is-eligible? (fn [group-chat-id]
                       (is-group-chat-eligible-for-selection? group-chat-id chat-id))
        groups (->> (get-chat-data chat-id)
                    get-private-chat-groups
                    (filter is-eligible?))]
    (cond
      (< 1 (count groups))
      (let [group-refs (->> groups
                            (map #(vector % (-> % get-chat-data get-chat-title)))
                            (map #(apply ->group-ref %)))]
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :select-group]
           :param-vals {:group-refs group-refs}}))

      (empty? groups)
      (do
        (log/error "No eligible groups to record expenses to")
        (proceed-with-chat-and-respond!
          {:chat-id chat-id}
          {:transition [:chat-type/private :notify-input-failure]
           :param-vals {:reason no-group-to-record-error}}))

      :else
      (let [group-chat-id (first groups)]
        (log/debug "Group chat auto-selected:" group-chat-id)
        (assoc-in-chat-data! chat-id [:group] group-chat-id)
        (proceed-with-expense-details! chat-id group-chat-id first-name)))))

;; TODO: Abstract away the "selecting N of {M; optional ALL}, M>=N>0" scenario.

(defn- clean-up-interactive-input-data!
  [chat-id]
  (let [dropped-msgs (concat (drop-bot-msg! chat-id {:type :inline-calculator})
                             (drop-bot-msg! chat-id {:type :cancel-disclaimer}))]
    (doseq [msg-id dropped-msgs]
      (delete-response! {:chat-id chat-id :msg-id msg-id}))))

; group chats

(defn- proceed-with-bot-eviction!
  [chat-id]
  (proceed-with-chat-and-respond!
    {:chat-id chat-id}
    {:transition [:chat-type/group :mark-evicted]}))

(defn- proceed-with-personal-accounts-name-request!
  [chat-id existing-chat? ?new-chat-members]
  (respond!* {:chat-id chat-id}
             [:chat-type/group :personal-account-name-request-msg]
             :param-vals {:existing-chat? existing-chat?
                          :chat-members ?new-chat-members}
             :on-success #(set-bot-msg! chat-id (:message_id %)
                                        {:type :name-request})))

(defn- proceed-with-group-chat-finalization!
  [chat-id show-settings?]
  ;; NB: Tries to create a new version of the "general account"
  ;;     even if the group chat already existed before.
  (create-general-account! chat-id (get-datetime-in-tg-format))
  (proceed-with-chat-and-respond!
    {:chat-id chat-id}
    {:transition [:chat-type/group :mark-managed]
     :param-vals {:bot-username (get-bot-username)}})
  (when show-settings?
    (respond!* {:chat-id chat-id}
               [:chat-type/group :settings-msg]
               :param-vals {:first-time? true}
               :on-success #(set-bot-msg! chat-id (:message_id %)
                                          {:type :settings
                                           :state :initial}))))

(defn- check-personal-accounts-and-proceed!
  ([chat-id]
   (let [existing-chat? (does-bot-manage-the-chat? {:chat-id chat-id})]
     (check-personal-accounts-and-proceed! chat-id existing-chat?)))
  ([chat-id existing-chat?]
   ;; NB: We need to update the list of chat personal accounts with new ones
   ;;     for those users:
   ;;     - who were members of the previously non-existent group chat at the
   ;;       time the bot was added there (or were added along with the bot),
   ;;     - who have joined the existing group chat during the absence of the
   ;;       previously evicted bot, if there are any. (Users with an existing
   ;;       active personal account should be ignored in this case.)
   (let [pers-accs-missing (get-number-of-missing-personal-accounts chat-id)]
     (if (> pers-accs-missing 0)
       (if (and (seq (find-bot-messages (get-chat-data chat-id) {:type :name-request}))
                (not (is-bot-evicted-from-chat? chat-id))) ;; just in case, to not stuck
         (respond!* {:chat-id chat-id}
                    [:chat-type/group :personal-accounts-left-msg]
                    :param-vals {:count pers-accs-missing})
         (proceed-with-personal-accounts-name-request! chat-id existing-chat? nil))
       (do
         (drop-bot-msg! chat-id {:type :name-request})
         (proceed-with-group-chat-finalization! chat-id (not existing-chat?)))))))

(defn- proceed-with-new-chat-members!
  [chat-id new-chat-members]
  (update-members-count! chat-id (partial + (count new-chat-members)))
  (proceed-with-personal-accounts-name-request! chat-id true new-chat-members))

(defn- proceed-with-left-chat-member!
  [chat-id left-chat-member]
  (update-members-count! chat-id dec)
  ;; NB: It is necessary to trigger the personal accounts check, just in case we
  ;;     are in the middle of the accounts creation process. If so, it will help
  ;;     us to avoid stale ':name-request' message in chat data and, what's even
  ;;     more important, will run the group chat finalization routine.
  (check-personal-accounts-and-proceed! chat-id)

  (encore/when-let [chat-data (get-chat-data chat-id)
                    user-id (:id left-chat-member)
                    pers-acc (get-personal-account chat-data {:user-id user-id})
                    datetime (get-datetime-in-tg-format)]
    (change-account-activity-status! chat-id pers-acc
                                     {:revoke? true :datetime datetime})
    (drop-user-input-data! chat-id user-id)
    (report-to-user! user-id
                     [:chat-type/private :removed-from-group-msg]
                     {:chat-title (get-chat-title chat-data)})))

(defn- proceed-with-account-type-selection!
  [chat-id msg-id]
  (proceed-with-msg-and-respond!
    {:chat-id chat-id :msg-id msg-id}
    {:transition [:chat-type/group :settings :select-acc-type]
     :param-vals {:account-types [:acc-type/group :acc-type/personal]
                  :extra-buttons [back-button]}}))

(defn- proceed-with-account-naming!
  [chat-id {user-id :id :as user}]
  (respond!* {:chat-id chat-id}
             [:chat-type/group :new-account-name-request-msg]
             :param-vals {:user user}
             :on-success #(set-bot-msg! chat-id (:message_id %)
                                        {:to-user user-id
                                         :type :request-acc-name})))

(defn- proceed-with-the-name-is-already-taken!
  [chat-id {user-id :id :as user} bot-msg-type]
  (respond!* {:chat-id chat-id}
             [:chat-type/group :the-name-is-already-taken-msg]
             :param-vals {:user user}
             :on-success #(set-bot-msg! chat-id (:message_id %)
                                        {:to-user user-id
                                         :type bot-msg-type})))

(defn- proceed-with-next-account-creation-steps!
  [chat-id acc-type {user-id :id :as user}]
  (let [input-data {:acc-type acc-type}]
    (set-user-input-data! chat-id user-id :create-account input-data))
  (case acc-type
    :acc-type/personal
    (proceed-with-account-naming! chat-id user)
    :acc-type/group
    (let [personal-accs (get-group-chat-accounts chat-id
                                                 {:acc-types [:acc-type/personal]})]
      (respond!* {:chat-id chat-id}
                 [:chat-type/group :group-members-selection-msg]
                 :param-vals {:user user
                              :selected nil
                              :remaining personal-accs}
                 :on-success #(set-bot-msg! chat-id (:message_id %)
                                            {:to-user user-id
                                             :type :group-members-selection})))))

(defn- proceed-with-account-member-selection!
  [chat-id msg-id user already-selected-account-members]
  (let [personal-accs (set (get-group-chat-accounts chat-id
                                                    {:acc-types [:acc-type/personal]}))
        selected-accs (set already-selected-account-members)
        remaining-accs (set/difference personal-accs selected-accs)
        accounts-remain? (seq remaining-accs)]
    (when accounts-remain?
      (respond!* {:chat-id chat-id :msg-id msg-id}
                 [:chat-type/group :group-members-selection-msg]
                 :param-vals {:user user
                              :selected selected-accs
                              :remaining remaining-accs}
                 :replace? true))
    accounts-remain?))

(defn- proceed-with-end-of-account-members-selection!
  [chat-id msg-id {user-id :id :as user} members]
  (drop-bot-msg! chat-id {:to-user user-id
                          :type :group-members-selection})
  (respond!* {:chat-id chat-id :msg-id msg-id}
             [:chat-type/group :new-group-members-msg]
             :param-vals {:acc-names (map :name members)}
             :replace? true)
  (proceed-with-account-naming! chat-id user))

(defn- proceed-with-next-group-account-creation-steps!
  [chat-id msg-id {user-id :id :as user}]
  (let [members (-> (get-chat-data chat-id)
                    (get-user-input-data user-id :create-account)
                    (get :members))
        can-proceed? (proceed-with-account-member-selection! chat-id msg-id user members)]
    (when-not can-proceed?
      (proceed-with-end-of-account-members-selection! chat-id msg-id user members))))

(defn- proceed-with-account-selection!
  ([chat-id msg-id callback-query-id
    state-transition-name]
   (proceed-with-account-selection! chat-id msg-id callback-query-id
                                    state-transition-name nil))
  ([chat-id msg-id callback-query-id
    state-transition-name ?filter-pred]
   (let [eligible-accs (get-group-chat-accounts chat-id
                                                {:acc-types [:acc-type/group
                                                             :acc-type/personal]
                                                 :filter-pred ?filter-pred})]
     (if (seq eligible-accs)
       (proceed-with-msg-and-respond!
         {:chat-id chat-id :msg-id msg-id}
         {:transition [:chat-type/group :settings state-transition-name]
          :param-vals {:accounts eligible-accs
                       :txt select-account-txt
                       :extra-buttons [back-button]}})
       (respond!* {:callback-query-id callback-query-id}
                  [:chat-type/group :no-eligible-accounts-notification])))))

(defn- proceed-with-account-renaming!
  [chat-id {user-id :id :as user} acc-to-rename]
  (let [input-data {:acc-type (:type acc-to-rename)
                    :acc-id (:id acc-to-rename)}]
    (set-user-input-data! chat-id user-id :rename-account input-data))
  (respond!* {:chat-id chat-id}
             [:chat-type/group :account-rename-request-msg]
             :param-vals {:user user
                          :acc-name (:name acc-to-rename)}
             :on-success #(set-bot-msg! chat-id (:message_id %)
                                        {:to-user user-id
                                         :type :request-rename})))

(defn- proceed-with-personal-account-creation!
  [chat-id chat-title
   acc-name created-dt {user-id :id :as user} first-msg-id]
  (let [create-acc-res (create-personal-account! chat-id acc-name created-dt
                                                 :user-id user-id :first-msg-id first-msg-id)]
    (if (is-failure? create-acc-res)
      (case create-acc-res
        :failure/user-already-has-an-active-account
        (respond!* {:chat-id chat-id} [:chat-type/group :canceled-changes-msg])

        :failure/the-account-name-is-already-taken
        (proceed-with-the-name-is-already-taken! chat-id user :name-request))
      (report-to-user! user-id
                       [:chat-type/private :added-to-new-group-msg]
                       {:chat-title chat-title}))))

(defn- proceed-with-account-operation!
  [chat-id {user-id :id :as user}
   bot-msg-type input-name acc-op-fn]
  (let [chat-data (get-chat-data chat-id)
        input-data (get-user-input-data chat-data user-id input-name)
        acc-op-res (acc-op-fn chat-id input-data)]
    (if (is-failure? acc-op-res)
      (case acc-op-res
        :failure/the-account-name-is-already-taken
        (proceed-with-the-name-is-already-taken! chat-id user bot-msg-type)
        (throw (ex-info "Unexpected operation result" {:result acc-op-res})))
      (do
        (clean-up-chat-data! chat-id {:bot-messages [{:to-user user-id
                                                      :type bot-msg-type}]
                                      :input [{:user user-id
                                               :name input-name}]})
        (respond!* {:chat-id chat-id}
                   [:chat-type/group :successful-changes-msg])))))

(defn- proceed-with-restoring-group-chat-intro!
  [chat-id user-id msg-id]
  (release-message-lock! chat-id user-id msg-id)
  (proceed-with-msg-and-respond!
    {:chat-id chat-id :msg-id msg-id}
    {:transition [:chat-type/group :settings :restore]}))


;; TODO: Linearize calls to these macros with a wrapper macro & a map.

(defmacro do-when-chat-is-ready-or-send-notification!
  [chat-state callback-query-id & body]
  `(if (does-bot-manage-the-chat? {:chat-state ~chat-state})
     (do ~@body)
     (respond!* {:callback-query-id ~callback-query-id}
                [:chat-type/group :waiting-for-user-input-notification])))

(defmacro do-with-expected-user-or-send-notification!
  [chat-id user-id msg-id callback-query-id & body]
  `(if (check-bot-msg (get-bot-msg (get-chat-data ~chat-id) ~msg-id)
                      {:to-user ~user-id})
     (do ~@body)
     (respond!* {:callback-query-id ~callback-query-id}
                [:chat-type/group :waiting-for-other-user-notification])))

(defmacro try-with-message-lock-or-send-notification!
  [chat-id user-id msg-id callback-query-id & body]
  `(if (acquire-message-lock! ~chat-id ~user-id ~msg-id)
     (do ~@body)
     (respond!* {:callback-query-id ~callback-query-id}
                [:chat-type/group :message-already-in-use-notification])))

;; - COMMANDS ACTIONS

; private chats

(defn- cmd-private-start!
  [{chat-id :id :as chat}
   {first-name :first_name :as _user}]
  (log/debug "Conversation started in a private chat:" chat)
  (proceed-with-chat-and-respond!
    {:chat-id chat-id}
    {:transition [:chat-type/private :request-amount]
     :param-vals {:first-name first-name}}))

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-private-help!
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a private chat:" chat)
  (respond! {:chat-id chat-id}
            {:type :text
             :text "Help is on the way!"}))

(defn- cmd-private-calc!
  [{chat-id :id :as chat}]
  (when (= :input (-> chat-id get-chat-data get-chat-state))
    (log/debug "Calculator opened in a private chat:" chat)
    (proceed-with-chat-and-respond!
      {:chat-id chat-id}
      {:transition [:chat-type/private :show-calculator]}
      :on-success #(set-bot-msg! chat-id (:message_id %)
                                 {:type :inline-calculator}))
    (respond!* {:chat-id chat-id}
               [:chat-type/private :interactive-input-disclaimer-msg]
               :on-success #(set-bot-msg! chat-id (:message_id %)
                                          {:type :cancel-disclaimer}))))

(defn- cmd-private-cancel!
  [{chat-id :id :as chat}]
  (log/debug "The operation is canceled in a private chat:" chat)

  ;; clean-up the messages
  (case (get-chat-state (get-chat-data chat-id))
    :interactive-input
    (clean-up-interactive-input-data! chat-id)
    nil)

  (proceed-with-chat-and-respond!
    {:chat-id chat-id}
    {:transition [:chat-type/private :cancel-input]}))

; group chats

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-group-help!
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a group chat:" chat)
  (respond! {:chat-id chat-id}
            {:type :text
             :text "Help is on the way!"}))

(defn- cmd-group-settings!
  [{chat-id :id :as chat}]
  (log/debug "Settings requested in a group chat:" chat)
  (respond!* {:chat-id chat-id}
             [:chat-type/group :settings-msg]
             :param-vals {:first-time? false}
             :on-success #(set-bot-msg! chat-id (:message_id %)
                                        {:type :settings
                                         :state :initial})))


;; API RESPONSES

;; IMPORTANT: The main idea of this API is to organize handlers of any type (message, command, any
;;            query, etc.) into a chain (in the order of their declaration within the 'defhandler'
;;            body) that will process updates in the following way:
;;            - the first handler of the type have to log an incoming update (its payload object);
;;            - any intermediate handler have to either:
;;              - result in an "operation [result] code" ('succeed', 'failed') OR in an "immediate
;;                response" (only for Webhook updates) — which will stop the further processing;
;;              - result in 'nil' — which will pass the update for processing to the next handler;
;;            - the last handler, in case the update wasn't processed, have to "ignore" it — which
;;              is also interpreted as the whole operation had 'succeed'.

;; NB: Any non-nil will do.
(def op-succeed {:ok true})
(def op-failed {:ok false})

(defmacro ^:private ignore
  [msg & args]
  `(do
     (log/debugf (str "Ignored: " ~msg) ~@args)
     op-succeed))

(defn- cb-succeed
  [callback-query-id]
  (tg-api/build-immediate-response "answerCallbackQuery"
                                   {:callback_query_id callback-query-id}))

(defn- cb-ignored
  [callback-query-id]
  ;; TODO: For a long-polling version, respond as usual.
  (tg-api/build-immediate-response "answerCallbackQuery"
                                   (assoc ignored-callback-query-notification
                                     :callback_query_id callback-query-id)))

;; TODO: This have to be combined with an Event-Driven model,
;;       i.e. in case of long-term request processing the bot
;;       should immediately respond with sending the 'typing'
;;       chat action (use "sendChatAction" method and see the
;;       /sendChatAction specs for the request parameters).


;; BOT API

;; TODO: The design should be similar to the Lupapiste web handlers with their 'in-/outjects'.

;; TODO: Add a rate limiter. Use the 'limiter' from Encore? Or some full-featured RPC library?
;;       This is to overcome the recent error — HTTP 429 — "Too many requests, retry after X".
; The Bots FAQ on the official Telegram website lists the following limits on server requests:
; - No more than 1 message per second in a single chat,
; - No more than 20 messages per minute in one group,
; - No more than 30 messages per second in total.

(defmacro handle-with-care!
  [bindings & handler-body-and-care-fn]
  (let [body (butlast handler-body-and-care-fn)
        care-fn (last handler-body-and-care-fn)]
    `(fn [arg#]
       (try
         ((fn ~bindings ~@body) arg#)
         (catch Exception e#
           (log/error e# "Failed to handle an incoming update:" arg#)
           (when-some [ex-data# (ex-data e#)]
             (log/error "The associated exception data:" ex-data#))
           (~care-fn arg#)
           op-failed)))))

(tg-client/defhandler
  handler

  ;; NB: This function is applied to the arguments of all handlers that follow
  ;;     and merges its result with the original value of the argument.
  (fn [upd upd-type]
    (let [chat (case upd-type
                 (:message :my_chat_member) (-> upd upd-type :chat)
                 (:callback_query) (-> upd upd-type :message :chat)
                 nil)
          msg (case upd-type
                :message (:message upd)
                :callback_query (-> upd :callback_query :message)
                nil)
          chat-data (get-chat-data (or (:id chat)
                                       (:id (:chat msg))))]
      (cond-> nil
              (some? chat)
              (merge {:chat-type (cond
                                   (tg-api/is-private? chat) :chat-type/private
                                   (tg-api/is-group? chat) :chat-type/group)
                      :chat-state (get-chat-state chat-data)})
              (some? msg)
              (merge (when-some [bot-msg (get-bot-msg chat-data (:message_id msg))]
                       {:bot-msg bot-msg})))))

  ;; - BOT COMMANDS

  ; group chats

  (tg-client/command-fn
    "help"
    (handle-with-care!
      [{chat :chat :as message}]
      (when (= :chat-type/group (:chat-type message))
        (cmd-group-help! chat)
        op-succeed)
      send-retry-command!))

  (tg-client/command-fn
    "settings"
    (handle-with-care!
      [{chat :chat :as message}]
      (when (= :chat-type/group (:chat-type message))
        (cmd-group-settings! chat)
        op-succeed)
      send-retry-command!))

  ; private chats

  (tg-client/command-fn
    "start"
    (handle-with-care!
      [{user :from chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-start! chat user)
        op-succeed)
      send-retry-command!))

  (tg-client/command-fn
    "help"
    (handle-with-care!
      [{chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-help! chat)
        op-succeed)
      send-retry-command!))

  (tg-client/command-fn
    "calc"
    (handle-with-care!
      [{chat :chat :as message}]
      (when (and (= :chat-type/private (:chat-type message))
                 (cmd-private-calc! chat))
        op-succeed)
      send-retry-command!))

  (tg-client/command-fn
    "cancel"
    (handle-with-care!
      [{chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-cancel! chat)
        op-succeed)
      send-retry-command!))

  ;; - INLINE QUERIES

  (m-hlr/inline-fn
    (fn [inline-query]
      (log/debug "Inline query:" inline-query)))

  ;; TODO: Try to implement an inline query answered w/ 'switch_pm_text' parameter?

  (m-hlr/inline-fn
    (fn [{inline-query-id :id _user :from query-str :query _offset :offset
          :as _inline-query}]
      (ignore "inline query id=%s, query=%s" inline-query-id query-str)))

  ;; - CALLBACK QUERIES

  (m-hlr/callback-fn
    (fn [callback-query]
      (log/debug "Callback query:" callback-query)))

  ; group chats

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :initial (-> callback-query :bot-msg :state))
                 (= cd-accounts callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [accounts-by-type (get-group-chat-accounts-by-type chat-id)]
              (proceed-with-msg-and-respond!
                {:chat-id chat-id :msg-id msg-id}
                {:transition [:chat-type/group :settings :manage-accounts]
                 :param-vals {:accounts-by-type accounts-by-type}}))))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :accounts-mgmt (-> callback-query :bot-msg :state)))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (if-let [respond! (condp = callback-btn-data
                                cd-accounts-create #(proceed-with-account-type-selection!
                                                      chat-id msg-id)
                                cd-accounts-rename #(proceed-with-account-selection!
                                                      chat-id msg-id callback-query-id
                                                      :rename-account
                                                      (can-rename-account? chat-id user-id))
                                cd-accounts-revoke #(proceed-with-account-selection!
                                                      chat-id msg-id callback-query-id
                                                      :revoke-account
                                                      (can-revoke-account? chat-id user-id))
                                cd-accounts-reinstate #(proceed-with-account-selection!
                                                         chat-id msg-id callback-query-id
                                                         :reinstate-account
                                                         (can-reinstate-account? chat-id user-id))
                                nil)]
              (do
                (respond!)
                (cb-succeed callback-query-id))
              (release-message-lock! chat-id user-id msg-id)))))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id :as user} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :account-type-selection (-> callback-query :bot-msg :state))
                 (str/starts-with? callback-btn-data cd-account-type-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-with-restoring-group-chat-intro! chat-id user-id msg-id)
            (let [acc-type-name (str/replace-first callback-btn-data
                                                   cd-account-type-prefix "")
                  acc-type (keyword "acc-type" acc-type-name)]
              (proceed-with-next-account-creation-steps! chat-id acc-type user))))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id :as user} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :group-members-selection (-> callback-query :bot-msg :type))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (do-with-expected-user-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [chat-data (get-chat-data chat-id)
                  picked-acc (data->account callback-btn-data chat-data)
                  input-data (-> chat-data
                                 (get-user-input-data user-id :create-account)
                                 (update :members #(utils/add-or-remove picked-acc %)))]
              (set-user-input-data! chat-id user-id :create-account input-data))
            (proceed-with-next-group-account-creation-steps! chat-id msg-id user)))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :group-members-selection (-> callback-query :bot-msg :type))
                 (= cd-undo callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (do-with-expected-user-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (drop-bot-msg! chat-id {:to-user user-id
                                    :type :group-members-selection})
            (set-user-input-data! chat-id user-id :create-account nil)
            (delete-response! {:chat-id chat-id :msg-id msg-id})

            (respond!* {:chat-id chat-id}
                       [:chat-type/group :canceled-changes-msg])))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id :as user} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :group-members-selection (-> callback-query :bot-msg :type))
                 (= cd-done callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (do-with-expected-user-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [members (-> (get-chat-data chat-id)
                              (get-user-input-data user-id :create-account)
                              (get :members))]
              (if (seq members)
                (do
                  (proceed-with-end-of-account-members-selection! chat-id msg-id user members)
                  (cb-succeed callback-query-id))
                (respond!* {:callback-query-id callback-query-id}
                           [:chat-type/group :no-group-members-selected-notification]))))))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id :as user} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :account-renaming (-> callback-query :bot-msg :state))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-with-restoring-group-chat-intro! chat-id user-id msg-id)
            (let [chat-data (get-chat-data chat-id)
                  account-to-rename (data->account callback-btn-data chat-data)]
              (proceed-with-account-renaming! chat-id user account-to-rename))))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :account-revocation (-> callback-query :bot-msg :state))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [chat-data (get-chat-data chat-id)
                  account-to-revoke (data->account callback-btn-data chat-data)
                  datetime (get-datetime-in-tg-format)]
              ;; NB: It makes sense to ask the user if the revoked account owner
              ;;     should be excluded from the chat, but this is possible only
              ;;     for bots with 'admin' rights.
              (change-account-activity-status! chat-id account-to-revoke
                                               {:revoke? true :datetime datetime}))

            (proceed-with-restoring-group-chat-intro! chat-id user-id msg-id)
            (respond!* {:chat-id chat-id} [:chat-type/group :successful-changes-msg])))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :account-reinstatement (-> callback-query :bot-msg :state))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [chat-data (get-chat-data chat-id)
                  account-to-reinstate (data->account callback-btn-data chat-data)
                  datetime (get-datetime-in-tg-format)]
              (change-account-activity-status! chat-id account-to-reinstate
                                               {:reinstate? true :datetime datetime}))

            (proceed-with-restoring-group-chat-intro! chat-id user-id msg-id)
            (respond!* {:chat-id chat-id} [:chat-type/group :successful-changes-msg])))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :initial (-> callback-query :bot-msg :state))
                 (= cd-expense-items callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-with-msg-and-respond!
              {:chat-id chat-id :msg-id msg-id}
              {:transition [:chat-type/group :settings :manage-expense-items]})))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  ;; TODO: Implement handlers for ':expense-items-mgmt'.

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= :initial (-> callback-query :bot-msg :state))
                 (= cd-shares callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-with-msg-and-respond!
              {:chat-id chat-id :msg-id msg-id}
              {:transition [:chat-type/group :settings :manage-shares]})))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  ;; TODO: Implement handlers for ':shares-mgmt'.

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {user-id :id} :from
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (-> callback-query :bot-msg :type))
                 (= cd-back callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (case (-> callback-query :bot-msg :state)
              (:initial ;; for when something went wrong
                :accounts-mgmt :expense-items-mgmt :shares-mgmt)
              (proceed-with-restoring-group-chat-intro! chat-id user-id msg-id)

              (:account-type-selection :account-renaming :account-revocation :account-reinstatement)
              (let [accounts-by-type (get-group-chat-accounts-by-type chat-id)]
                (proceed-with-msg-and-respond!
                  {:chat-id chat-id :msg-id msg-id}
                  {:transition [:chat-type/group :settings :manage-accounts]
                   :param-vals {:accounts-by-type accounts-by-type}})))))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  ; private chats

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {first-name :first_name} :from
        {{chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :group-selection (:chat-state callback-query))
                 (str/starts-with? callback-btn-data cd-group-chat-prefix))
        (let [group-chat-id-str (str/replace-first callback-btn-data cd-group-chat-prefix "")
              group-chat-id (nums/parse-int group-chat-id-str)]
          (assoc-in-chat-data! chat-id [:group] group-chat-id)
          (proceed-with-expense-details! chat-id group-chat-id first-name))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {{chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :expense-detailing (:chat-state callback-query))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (assoc-in-chat-data! chat-id [:expense-item] expense-item)
          (proceed-with-account! chat-id))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {{chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :account-selection (:chat-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [group-chat-id (:group (get-chat-data chat-id))
              group-chat-data (get-chat-data group-chat-id)
              debtor-acc (data->account callback-btn-data group-chat-data)]
          (proceed-with-adding-new-expense! chat-id debtor-acc))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {msg-id :message_id {chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :interactive-input (:chat-state callback-query)))
        (when-let [non-terminal-operation (condp apply [callback-btn-data]
                                            cd-digits-set {:type :append-digit
                                                           :data callback-btn-data}
                                            cd-ar-ops-set {:type :append-ar-op
                                                           :data callback-btn-data}
                                            (partial = cd-clear) {:type :cancel}
                                            (partial = cd-cancel) {:type :clear}
                                            nil)]
          (let [old-user-input (get-user-input (get-chat-data chat-id))
                new-user-input (update-user-input! chat-id non-terminal-operation)]
            (when (not= old-user-input new-user-input)
              (respond!* {:chat-id chat-id :msg-id msg-id}
                         [:chat-type/private :inline-calculator-msg]
                         :param-vals {:new-user-input new-user-input}
                         :replace? true)))
          (cb-succeed callback-query-id)))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (handle-with-care!
      [{callback-query-id :id
        {first-name :first_name} :from
        {{chat-id :id} :chat} :message
        callback-btn-data :data
        :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :interactive-input (:chat-state callback-query))
                 (= cd-enter callback-btn-data))
        (let [user-input (get-user-input (get-chat-data chat-id))
              parsed-val (nums/parse-arithmetic-expression user-input)]
          (if (and (number? parsed-val) (pos? parsed-val))
            (do
              (log/debug "User input:" parsed-val)
              (assoc-in-chat-data! chat-id [:amount] parsed-val)
              (clean-up-interactive-input-data! chat-id)

              (respond!* {:chat-id chat-id}
                         [:chat-type/private :interactive-input-success-msg]
                         :param-vals {:parsed-val parsed-val})
              (proceed-with-group! chat-id first-name))
            (do
              (log/debugf "Invalid user input: \"%s\"" parsed-val)
              (respond!* {:callback-query-id callback-query-id}
                         [:chat-type/private :invalid-input-notification]))))
        (cb-succeed callback-query-id))
      send-retry-callback-query!))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-instance :chat_instance callback-btn-data :data
          :as _callback-query}]
      (ignore "callback query id=%s, data=%s" callback-query-id callback-btn-data)
      (cb-ignored callback-query-id)))

  ;; - CHAT MEMBER STATUS UPDATES

  (tg-client/bot-chat-member-status-fn
    (handle-with-care!
      [{{chat-id :id _type :type chat-title :title _username :username :as chat} :chat
        {_user-id :id first-name :first_name _last-name :last_name
         _username :username _is-bot :is_bot _lang :language_code :as _user} :from
        date :date
        {_old-user :user _old-status :status :as _old-chat-member} :old_chat_member
        {_new-user :user _new-status :status :as _new-chat-member} :new_chat_member
        :as my-chat-member-updated}]
      (log/debug "Bot chat member status updated in:" chat)
      (cond
        (tg-api/has-joined? my-chat-member-updated)
        (let [token (config/get-prop :bot-api-token)
              chat-members-count (tg-client/get-chat-member-count token chat-id)
              new-chat (setup-new-group-chat! chat-id chat-title chat-members-count)
              existing-chat? (nil? new-chat)]
          (if existing-chat?
            (do
              (log/debugf "The chat=%s already exists" chat-id)
              (update-members-count! chat-id (constantly chat-members-count)))
            (respond!* {:chat-id chat-id}
                       [:chat-type/group :introduction-msg]
                       :param-vals {:chat-members-count chat-members-count
                                    :first-name first-name}))
          (check-personal-accounts-and-proceed! chat-id existing-chat?)
          op-succeed)

        (tg-api/has-left? my-chat-member-updated)
        (do
          (update-members-count! chat-id dec)
          (proceed-with-bot-eviction! chat-id)
          op-succeed)

        :else
        (ignore "bot chat member status update dated %s in chat=%s" date chat-id))
      notify-of-inconsistent-chat-state!))

  (tg-client/chat-member-status-fn
    (fn [{{chat-id :id _type :type _chat-title :title _username :username :as chat} :chat
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          date :date
          {_old-user :user _old-status :status :as _old-chat-member} :old_chat_member
          {_new-user :user _new-status :status :as _new-chat-member} :new_chat_member
          :as _chat-member-updated}]
      (log/debug "Chat member status updated in:" chat)
      ;; NB: The bot must be an administrator in the chat to receive 'chat_member' updates
      ;;     about other chat members. By default, only 'my_chat_member' updates about the
      ;;     bot itself are received.
      (ignore "chat member status update dated %s in chat=%s" date chat-id)))

  ;; - PLAIN MESSAGES

  ; group chats

  (m-hlr/message-fn
    (handle-with-care!
      [{{chat-id :id :as chat} :chat
        new-chat-members :new_chat_members
        :as _message}]
      (when (some? new-chat-members)
        (log/debug "New chat members in chat:" chat)
        (let [new-chat-members (filter #(not (:is_bot %)) new-chat-members)]
          (when (seq new-chat-members)
            (proceed-with-new-chat-members! chat-id new-chat-members)
            op-succeed)))
      notify-of-inconsistent-chat-state!))

  (m-hlr/message-fn
    (handle-with-care!
      [{{chat-id :id :as chat} :chat
        left-chat-member :left_chat_member
        :as _message}]
      (when (some? left-chat-member)
        (log/debug "Chat member left chat:" chat)
        (when-not (:is_bot left-chat-member)
          (proceed-with-left-chat-member! chat-id left-chat-member)
          op-succeed))
      notify-of-inconsistent-chat-state!))

  (m-hlr/message-fn
    (fn [{msg-id :message_id group-chat-created :group_chat_created :as _message}]
      (when (some? group-chat-created)
        (ignore "message id=%s" msg-id))))

  (m-hlr/message-fn
    (handle-with-care!
      [{msg-id :message_id date :date text :text
        {user-id :id :as user} :from
        {chat-id :id chat-title :title} :chat
        :as message}]
      ;; TODO: Figure out how to proceed if someone accidentally closes the reply.
      (when (and (= :chat-type/group (:chat-type message))
                 (check-bot-msg (get-original-bot-msg chat-id message)
                                {:type :name-request}))
        ;; NB: Here the 'user-id' exists for sure, since it is the User's response.
        (when-some [_new-chat (setup-new-private-chat! user-id chat-id)]
          (update-private-chat-groups! user-id chat-id))

        (proceed-with-personal-account-creation! chat-id chat-title
                                                 text date user msg-id)

        (check-personal-accounts-and-proceed! chat-id)
        op-succeed)
      send-retry-message!))

  (m-hlr/message-fn
    (handle-with-care!
      [{date :date text :text
        {user-id :id :as user} :from
        {chat-id :id} :chat
        :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (check-bot-msg (get-original-bot-msg chat-id message)
                                {:to-user user-id
                                 :type :request-acc-name}))
        (let [input-data (-> (get-chat-data chat-id)
                             (get-user-input-data user-id :create-account)
                             (assoc :acc-name text)
                             (assoc :created-dt date)
                             (assoc :created-by user-id))]
          (set-user-input-data! chat-id user-id :create-account input-data))
        (proceed-with-account-operation! chat-id user
                                         :request-acc-name
                                         :create-account
                                         create-group-chat-account!)
        op-succeed)
      send-retry-message!))

  (m-hlr/message-fn
    (handle-with-care!
      [{text :text
        {user-id :id :as user} :from
        {chat-id :id} :chat
        :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (check-bot-msg (get-original-bot-msg chat-id message)
                                {:to-user user-id
                                 :type :request-rename}))
        (let [input-data (-> (get-chat-data chat-id)
                             (get-user-input-data user-id :rename-account)
                             (assoc :new-acc-name text))]
          (set-user-input-data! chat-id user-id :rename-account input-data))
        (proceed-with-account-operation! chat-id user
                                         :request-rename
                                         :rename-account
                                         change-group-chat-account-name!)
        op-succeed)
      send-retry-message!))

  (m-hlr/message-fn
    (handle-with-care!
      [{{chat-id :id} :chat
        new-chat-title :new_chat_title
        :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (some? new-chat-title))
        (log/debugf "Chat %s title was changed to '%s'" chat-id new-chat-title)
        (set-chat-title! chat-id new-chat-title)
        op-succeed)
      notify-of-inconsistent-chat-state!))

  (m-hlr/message-fn
    (handle-with-care!
      [{{chat-id :id} :chat
        migrate-to-chat-id :migrate_to_chat_id
        :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (some? migrate-to-chat-id))
        (log/debugf "Group %s has been migrated to a supergroup %s" chat-id migrate-to-chat-id)
        (migrate-group-chat-to-supergroup! chat-id migrate-to-chat-id)
        op-succeed)
      notify-of-inconsistent-chat-state!))

  ; private chats

  (m-hlr/message-fn
    (handle-with-care!
      [{text :text
        {first-name :first_name} :from
        {chat-id :id} :chat
        :as message}]
      (when (and (= :chat-type/private (:chat-type message))
                 (= :input (:chat-state message)))
        (let [input (nums/parse-number text)]
          (if (number? input)
            (do
              (log/debug "User input:" input)
              (assoc-in-chat-data! chat-id [:amount] input)
              (proceed-with-group! chat-id first-name)
              op-succeed)
            (respond!* {:chat-id chat-id}
                       [:chat-type/private :invalid-input-msg]))))
      send-retry-message!))

  (m-hlr/message-fn
    (handle-with-care!
      [{text :text {chat-id :id} :chat
        :as message}]
      (when (and (= :chat-type/private (:chat-type message))
                 (= :expense-detailing (:chat-state message)))
        (log/debugf "Expense description: \"%s\"" text)
        (assoc-in-chat-data! chat-id [:expense-desc] text)
        (proceed-with-account! chat-id)
        op-succeed)
      send-retry-message!))

  ;; NB: A "match-all catch-through" case. Excessive list of parameters is for clarity.
  (m-hlr/message-fn
    (fn [{msg-id :message_id _date :date _text :text
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          {_chat-id :id _type :type _chat-title :title _username :username :as chat} :chat
          _sender-chat :sender_chat
          _forward-from :forward_from
          _forward-from-chat :forward_from_chat
          _forward-from-message-id :forward_from_message_id
          _forward-signature :forward_signature
          _forward-sender-name :forward_sender_name
          _forward-date :forward_date
          _original-msg :reply_to_message ;; for replies
          _via-bot :via_bot
          _edit-date :edit_date
          _author-signature :author_signature
          _entities :entities
          _new-chat-members :new_chat_members
          _left-chat-member :left_chat_member
          _new-chat-title :new_chat_title
          _new-chat-photo :new_chat_photo
          _delete-chat-photo :delete_chat_photo
          _group-chat-created :group_chat_created
          _supergroup-chat-created :supergroup_chat_created
          _channel-chat-created :channel_chat_created
          _message-auto-delete-timer-changed :message_auto_delete_timer_changed
          _migrate-to-chat-id :migrate_to_chat_id
          _migrate-from-chat-id :migrate_from_chat_id
          _pinned-message :pinned_message
          _reply-markup :reply_markup
          :as _message}]
      (log/debug "Unprocessed message in chat:" chat)
      (ignore "message id=%s" msg-id))))

(defn bot-api
  [update]
  (log/debug "Received update:" update)
  (handler update))
