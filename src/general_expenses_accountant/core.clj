(ns general-expenses-accountant.core
  "Bot API and business logic (core functionality)"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :refer [<! go timeout]]

            [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [slingshot.slingshot :as slingshot]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.domain.chat :as chats]
            [general-expenses-accountant.domain.tlog :as tlogs]
            [general-expenses-accountant.nums :as nums]
            [general-expenses-accountant.tg-bot-api :as tg-api]
            [general-expenses-accountant.tg-client :as tg-client])
  (:import [java.util Locale]))

;; STATE

(defonce ^:private *bot-user (atom nil))

;; TODO: Normally, this should be transformed into a 'cloffeine' cache
;;       which periodically auto-evicts the cast-off chats data. Then,
;;       the initial data should be truncated, e.g. by an 'updated_at'
;;       timestamps, and the data for chats from the incoming requests
;;       should be (re)loaded from the DB on demand.
(defonce ^:private *bot-data (atom {}))

(defn init!
  []
  (let [token (config/get-prop :bot-api-token)
        bot-user (get (tg-client/get-me token) :result)]
    (log/debug "Identified myself:" bot-user)
    (reset! *bot-user bot-user))

  ;; TODO: Send the list of supported commands w/ 'setMyCommands'.

  (let [chats (chats/select-all)
        ids (map :id chats)]
    (log/debug "Total chats uploaded from the DB:" (count chats))
    (reset! *bot-data (zipmap ids chats))))

(defn- get-bot-data
  []
  @*bot-data)

(defn- update-bot-data!
  ([upd-fn]
   (swap! *bot-data upd-fn))
  ([upd-fn & upd-fn-args]
   (apply swap! *bot-data upd-fn upd-fn-args)))

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

;; TODO: Cerebrate the approach to the design of the "virtual members"
;;       (those who aren't real chat members, but nevertheless occupy
;;       a personal account) in a personal accounting case.
;;       - Do they count when determining the use case for a chat?
;;         At the moment, they DO NOT count.
;;       - Are they merely different accounts for personal budgeting?
;;         This bot was not intended for a full-featured accounting.
(defn- is-chat-for-group-accounting?
  "Determines the use case for a chat by the number of its members."
  [chat-members-count]
  (and (some? chat-members-count) ;; a private chat with the bot
       (>= chat-members-count min-chat-members-for-group-accounting)))

(defn- get-number-of-missing-personal-accounts
  "Returns the number of missing personal accounts in a group chat,
   which have to be created before the group chat is ready for the
   general expenses accounting."
  [chat-members-count number-of-existing-personal-accounts]
  (- chat-members-count number-of-existing-personal-accounts 1))


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

(def ^:private cd-group-chat-prefix "gc::")
(def ^:private cd-expense-item-prefix "ei::")
(def ^:private cd-account-prefix "ac::")
(def ^:private cd-account-type-prefix "at::")

(def ^:private cd-digits-set #{"1" "2" "3" "4" "5" "6" "7" "8" "9" "0" ","})
(def ^:private cd-ar-ops-set #{"+" "–"})
(def ^:private cd-cancel "C")
(def ^:private cd-clear "<-")
(def ^:private cd-enter "OK")

;; TODO: Proper localization (with fn).
(def ^:private back-button-text "<< назад")
(def ^:private undo-button-text "Отмена")
(def ^:private done-button-text "Готово")
(def ^:private default-general-acc-name "общие расходы")
(def ^:private account-types-names {:acc-type/personal "Личный*" ;; TODO: Make this '*' optional.
                                    :acc-type/group "Групповой"
                                    :acc-type/general "Общий"})
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

(defn- format-currency
  [amount lang]
  (String/format (Locale/forLanguageTag lang)
                 "%.2f" (to-array [amount])))

(defn- build-select-items-options
  [items name-extr-fn key-extr-fn val-extr-fn]
  (let [select-items (for [item items]
                       [(tg-api/build-inline-kbd-btn (name-extr-fn item)
                                                     (key-extr-fn item)
                                                     (val-extr-fn item))])]
    (tg-api/build-message-options
      {:reply-markup (tg-api/build-reply-markup :inline-keyboard (vec select-items))})))

(defn- append-extra-buttons
  [inline-kbd-markup extra-buttons]
  (let [extra-buttons (for [extra-button extra-buttons] [extra-button])]
    (update-in inline-kbd-markup [:reply_markup :inline_keyboard] into extra-buttons)))

(defn- group-refs->options
  [group-refs]
  (build-select-items-options group-refs
                              :title
                              (constantly :callback_data)
                              #(str cd-group-chat-prefix (:id %))))

(defn- expense-items->options
  [expense-items]
  (build-select-items-options expense-items
                              :desc
                              (constantly :callback_data)
                              #(str cd-expense-item-prefix (:code %))))

(defn- accounts->options
  [accounts & extra-buttons]
  (append-extra-buttons
    (build-select-items-options accounts
                                :name
                                (constantly :callback_data)
                                #(str cd-account-prefix (name (:type %)) "::" (:id %)))
    extra-buttons))

(defn- account-types->options
  [account-types & extra-buttons]
  (append-extra-buttons
    (build-select-items-options account-types
                                account-types-names
                                (constantly :callback_data)
                                #(str cd-account-type-prefix (name %)))
    extra-buttons))

(def ^:private back-button
  (tg-api/build-inline-kbd-btn back-button-text :callback_data cd-back))

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
- доли — по умолчанию равные для всех счетов и статей расходов." ;; TODO: Rewrite copy for "доли".
                 (if (true? first-time?) "Также меня" "Меня"))
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Счета" :callback_data cd-accounts)
                                 (tg-api/build-inline-kbd-btn "Статьи" :callback_data cd-expense-items)
                                 (tg-api/build-inline-kbd-btn "Доли" :callback_data cd-shares)]])})})

(def ^:private waiting-for-user-input-notification
  {:type :callback
   :options {:text "Ожидание ответа пользователя в чате"}})

(def ^:private message-already-in-use-notification
  {:type :callback
   :options {:text "С этим сообщением уже взаимодействуют"}})

(def ^:private ignored-callback-query-notification
  {:type :callback
   :options {:text "Запрос не может быть обработан"}})

(defn- get-accounts-mgmt-options-msg
  [accounts-by-type]
  {:type :text
   :text (str "Список счетов данной группы\n\n"
              ;; TODO: Display the general account, if any, in a special way ("Счёт для общих расходов.").
              (->> accounts-by-type
                   (map (fn [[acc-type accounts]]
                          (str (get account-types-names acc-type) ":\n"
                               (str/join "\n" (map #(str "- " (:name %)) accounts))))) ;; TODO: Escape MDv2!
                   (str/join "\n\n"))
              "\n\nВыберите, что вы хотите сделать:")
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Создать новый" :callback_data cd-accounts-create)]
                                [(tg-api/build-inline-kbd-btn "Переименовать" :callback_data cd-accounts-rename)]
                                [(tg-api/build-inline-kbd-btn "Упразднить" :callback_data cd-accounts-revoke)]
                                [(tg-api/build-inline-kbd-btn "Восстановить" :callback_data cd-accounts-reinstate)]
                                [back-button]])})})

(defn- get-account-selection-msg
  ([accounts txt]
   (get-account-selection-msg accounts txt nil))
  ([accounts txt extra-buttons]
   {:pre [(seq accounts)]}
   {:type :text
    :text txt
    :options (apply accounts->options accounts extra-buttons)}))

(defn- get-account-type-selection-msg
  [account-types extra-buttons]
  {:pre [(seq account-types)]}
  {:type :text
   :text "Выберите тип счёта:
*) на случай, если пользователя нет в Telegram"
   :options (apply account-types->options account-types extra-buttons)})

(defn- get-new-account-name-request-msg
  [user]
  {:type :text
   :text (str (tg-api/get-user-mention-text user)
              ", как бы вы хотели назвать новый счёт?")
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup :force-reply {:selective true})
               :parse-mode "MarkdownV2"})})

(defn- get-new-member-selection-msg
  [accounts]
  (get-account-selection-msg
    accounts
    "Выберите члена группы:"
    [(tg-api/build-inline-kbd-btn undo-button-text :callback_data cd-undo)
     (tg-api/build-inline-kbd-btn done-button-text :callback_data cd-done)]))

(defn- get-account-rename-request-msg
  [user acc-name]
  {:type :text
   :text (str (tg-api/get-user-mention-text user)
              ", как бы вы хотели переименовать счёт \"" (escape-markdown-v2 acc-name) "\"?")
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup :force-reply {:selective true})
               :parse-mode "MarkdownV2"})})

(def ^:private no-eligible-accounts-notification
  {:type :callback
   :options {:text "Подходящих счетов не найдено"}})

(def ^:private successful-changes-msg
  {:type :text
   :text "Изменения внесены успешно."})

;; TODO: Add messages for 'expense items' here.

;; TODO: Add messages for 'shares' here.

(defn- get-personal-account-name-request-msg
  [?chat-members]
  {:type :text
   :text (let [request-txt "как будет называться ваш личный счёт?"]
           (if (some? ?chat-members)
             (let [mentions (for [user ?chat-members]
                              (tg-api/get-user-mention-text user))]
               (str (str/join " " mentions) ", " request-txt))
             (str/capitalize request-txt)))
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
                    (str "*" (escape-markdown-v2 payer-acc-name) "*\n"))
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
    [[(tg-api/build-inline-kbd-btn "7" :callback_data "7")
      (tg-api/build-inline-kbd-btn "8" :callback_data "8")
      (tg-api/build-inline-kbd-btn "9" :callback_data "9")
      (tg-api/build-inline-kbd-btn "C" :callback_data cd-cancel)]
     [(tg-api/build-inline-kbd-btn "4" :callback_data "4")
      (tg-api/build-inline-kbd-btn "5" :callback_data "5")
      (tg-api/build-inline-kbd-btn "6" :callback_data "6")
      (tg-api/build-inline-kbd-btn "+" :callback_data "+")]
     [(tg-api/build-inline-kbd-btn "1" :callback_data "1")
      (tg-api/build-inline-kbd-btn "2" :callback_data "2")
      (tg-api/build-inline-kbd-btn "3" :callback_data "3")
      (tg-api/build-inline-kbd-btn "–" :callback_data "–")]
     [(tg-api/build-inline-kbd-btn "0" :callback_data "0")
      (tg-api/build-inline-kbd-btn "," :callback_data ",")
      (tg-api/build-inline-kbd-btn "←" :callback_data cd-clear)
      (tg-api/build-inline-kbd-btn "OK" :callback_data cd-enter)]]))

(defn- new-expense-msg
  ([text]
   (new-expense-msg text nil))
  ([text extra-opts]
   {:type :text
    :text (str (escape-markdown-v2 "Новый расход:\n= ") text)
    :options (tg-api/build-message-options
               (merge {:parse-mode "MarkdownV2"} extra-opts))}))

(defn- get-interactive-input-msg
  [user-input]
  (new-expense-msg
    (escape-markdown-v2 (if (empty? user-input) "_" user-input))
    {:reply-markup inline-calculator-markup}))

(defn- get-calculation-success-msg
  [amount]
  (new-expense-msg
    (escape-markdown-v2 (format-currency amount "ru"))))

(defn- get-calculation-failure-msg
  [amount]
  (new-expense-msg
    (str/join "\n"
              [amount
               ;; TODO: Make this disclaimer permanent, i.e. always show it in the 'interactive input' mode.
               (escape-markdown-v2 "Введите /cancel, чтобы выйти из режима калькуляции и ввести данные вручную.")])
    {:reply-markup inline-calculator-markup}))

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
   :options (group-refs->options group-refs)})

(defn- get-expense-item-selection-msg
  [expense-items]
  {:pre [(seq expense-items)]}
  {:type :text
   :text "Выберите статью расходов:"
   :options (expense-items->options expense-items)})

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

(defn- setup-new-group-chat!
  [chat-id chat-title chat-members-count]
  (setup-new-chat! chat-id {:type :chat-type/group
                            :data {:state default-chat-state
                                   :title chat-title
                                   :members-count chat-members-count
                                   :is-bot-member true
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

(defn- update-chat-data!
  [chat-id upd-fn & upd-fn-args]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        bot-data (apply update-bot-data!
                        update-in [real-chat-id :data] upd-fn upd-fn-args)
        upd-chat (get bot-data real-chat-id)]
    (chats/update! upd-chat) ;; TODO: Check if the update actually happened.
    (:data upd-chat)))

(defn- assoc-in-chat-data!
  [chat-id [key & ks] value]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        full-path (concat [real-chat-id :data key] ks)
        bot-data (if (nil? value)
                   (update-bot-data! update-in (butlast full-path)
                                     dissoc (last full-path))
                   (update-bot-data! assoc-in full-path value))
        upd-chat (get bot-data real-chat-id)]
    (chats/update! upd-chat) ;; TODO: Check if the update actually happened.
    (:data upd-chat)))

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
  [chat-id chat-states new-state]
  {:pre [(does-chat-exist? chat-id)]}
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [curr-state (get-chat-state chat-data)
                  possible-new-states (or (-> chat-states curr-state :to)
                                          (-> chat-states curr-state))]
              (if (contains? possible-new-states new-state)
                (let [state-init-fn (-> chat-states new-state :init-fn)]
                  (cond-> chat-data
                          (some? state-init-fn) (state-init-fn)
                          :and-most-importantly (assoc :state new-state)))
                (do
                  (log/errorf "Failed to change state to '%s' for chat=%s with current state '%s'"
                              new-state chat-id curr-state)
                  chat-data)))))]
    (get-chat-state updated-chat-data)))

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
  [id name created members]
  {:id id
   :type :acc-type/group
   :name name
   :created created
   :members (set members)})

(defn- is-account-revoked?
  [acc]
  (contains? acc :revoked))

(defn- is-account-active?
  [acc]
  (not (is-account-revoked? acc)))

;; - CHATS > ACCOUNTS

(defn- get-accounts-of-type
  ([chat-data acc-type]
   (map val (get-in chat-data [:accounts acc-type])))
  ([chat-data acc-type ?filter-pred]
   (cond->> (get-accounts-of-type chat-data acc-type)
            (some? ?filter-pred) (filter ?filter-pred))))

(defn- get-accounts-next-id
  [chat-data]
  (-> chat-data :accounts :last-id inc))

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
                    (if (is-chat-for-group-accounting? (:members-count cd))
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

(defn- find-personal-account-by-name
  [chat-data acc-name]
  ;; NB: A personal account's name must be unique.
  (first (get-accounts-of-type chat-data
                               :acc-type/personal
                               #(= (:name %) acc-name))))

(defn- get-personal-account-id
  [chat-data {?user-id :user-id ?acc-name :name :as _ids}]
  {:pre [(or (some? ?user-id) (some? ?acc-name))]}
  (if (some? ?user-id)
    (get-in chat-data [:user-account-mapping ?user-id])
    (:id (find-personal-account-by-name chat-data ?acc-name))))

(defn- set-personal-account-id
  [chat-data user-id pers-acc-id]
  {:pre [(some? user-id)]}
  (assoc-in chat-data [:user-account-mapping user-id] pers-acc-id))

(defn- get-personal-account
  [chat-data {?user-id :user-id ?acc-name :name ?acc-id :acc-id :as ids}]
  {:pre [(or (some? ?user-id) (some? ?acc-name) (some? ?acc-id))]}
  (cond
    (some? ?user-id)
    (let [pers-acc-id (get-personal-account-id chat-data ids)]
      (get-in chat-data [:accounts :acc-type/personal pers-acc-id]))

    (some? ?acc-name)
    (find-personal-account-by-name chat-data ?acc-name)

    (some? ?acc-id)
    (get-in chat-data [:accounts :acc-type/personal ?acc-id])))

;; TODO: Rename optional args in all other fns to start w/ '?...' as well.
(defn- create-personal-account!
  [chat-id acc-name created-dt & {?user-id :user-id ?first-msg-id :first-msg-id :as _opts}]
  ;; TODO: Add some check for the 'acc-name' uniqueness and an account name re-request flow.
  (when (or (nil? ?user-id)
            (let [pers-acc (get-personal-account (get-chat-data chat-id) {:user-id ?user-id})] ;; petty RC
              (or (nil? pers-acc) (is-account-revoked? pers-acc))))
    (let [updated-chat-data
          (update-chat-data!
            chat-id
            (fn [chat-data]
              (let [next-id (get-accounts-next-id chat-data)

                    upd-accounts-next-id-fn
                    (fn [cd]
                      (assoc-in cd [:accounts :last-id] next-id))

                    add-new-personal-acc-fn
                    (fn [cd]
                      (let [pers-acc (->personal-account
                                       next-id acc-name created-dt
                                       ?user-id ?first-msg-id)]
                        (cond-> (assoc-in cd [:accounts :acc-type/personal next-id] pers-acc)
                                (some? ?user-id) (set-personal-account-id ?user-id next-id))))]
                (-> chat-data
                    upd-accounts-next-id-fn
                    add-new-personal-acc-fn))))]
      (get-personal-account updated-chat-data {:user-id ?user-id :name acc-name}))))

(defn- update-personal-account!
  [chat-id
   {?user-id :user-id ?acc-name :name :as ids}
   {:keys [new-name revoke? reinstate? datetime] :as _upd}]
  (let [pers-acc-id (get-personal-account-id (get-chat-data chat-id) ids)] ;; petty RC
    (when (some? pers-acc-id)
      (let [updated-chat-data
            (update-chat-data!
              chat-id
              (fn [chat-data]
                (let [upd-acc-name-fn
                      (fn [cd]
                        (if (some? new-name)
                          (assoc-in cd [:accounts :acc-type/personal pers-acc-id :name] new-name)
                          cd))

                      upd-acc-revoked-fn
                      (fn [cd]
                        (if (true? revoke?)
                          (assoc-in cd [:accounts :acc-type/personal pers-acc-id :revoked] datetime)
                          cd))

                      upd-acc-reinstated-fn
                      (fn [cd]
                        (if (true? reinstate?)
                          (update-in cd [:accounts :acc-type/personal pers-acc-id] dissoc :revoked)
                          cd))]
                  (-> chat-data
                      upd-acc-name-fn
                      upd-acc-revoked-fn
                      upd-acc-reinstated-fn))))]
        (get-personal-account updated-chat-data {:user-id ?user-id
                                                 :name (or new-name ?acc-name)})))))

;; - CHATS > ACCOUNTS > GROUP

(defn- find-group-accounts
  [chat-data members]
  (get-accounts-of-type chat-data
                        :acc-type/group
                        #(= (:members %) members)))

(defn- get-latest-group-account
  [chat-data members]
  (->> (find-group-accounts chat-data members)
       (sort-by :created)
       first))

(defn- create-group-account!
  [chat-id members acc-name created-dt]
  ;; TODO: Add some check for the 'acc-name' uniqueness.
  ;; TODO: Extract the common part ('updated-chat-data'...'next-id') into a fn.
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [next-id (get-accounts-next-id chat-data)

                  upd-accounts-next-id-fn
                  (fn [cd]
                    (assoc-in cd [:accounts :last-id] next-id))

                  add-new-group-acc-fn
                  (fn [cd]
                    (let [group-acc (->group-account
                                      next-id acc-name created-dt members)]
                      (assoc-in cd [:accounts :acc-type/group next-id] group-acc)))]
              (-> chat-data
                  upd-accounts-next-id-fn
                  add-new-group-acc-fn))))]
    (get-latest-group-account updated-chat-data members)))

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

(defn- is-group-acc-member?
  [group-acc chat-id user-id]
  (let [group-chat-data (get-chat-data chat-id)
        pers-acc-id (get-personal-account-id group-chat-data {:user-id user-id})]
    (contains? (:members group-acc) pers-acc-id)))

(defn- can-rename-account?
  [chat-id user-id]
  (fn [acc]
    (and (is-account-active? acc)
         (or (and (= :acc-type/personal (:type acc))
                  (contains? #{user-id nil} (:user-id acc)))
             (and (= :acc-type/group (:type acc))
                  (is-group-acc-member? acc chat-id user-id))))))

(defn- can-revoke-account?
  [chat-id user-id]
  (fn [acc]
    (and (is-account-active? acc)
         (or (and (= :acc-type/personal (:type acc))
                  (not= (:user-id acc) user-id))
             (and (= :acc-type/group (:type acc))
                  (is-group-acc-member? acc chat-id user-id))))))

(defn- can-reinstate-account?
  [_chat-id _user-id]
  (fn [acc]
    (is-account-revoked? acc)))

(defn- get-group-chat-account
  [group-chat-data acc-type acc-id]
  (get-in group-chat-data [:accounts acc-type acc-id]))

(defn- is-group-chat-eligible-for-selection?
  [chat-id user-id]
  (let [group-chat-data (get-chat-data chat-id)
        pers-acc-id (get-personal-account-id group-chat-data {:user-id user-id})
        pers-acc (get-group-chat-account group-chat-data :acc-type/personal pers-acc-id)]
    (is-account-active? pers-acc)))

(defn- data->account
  "Retrieves a group chat's account by parsing the callback button data."
  [callback-btn-data group-chat-data]
  (let [account (str/replace-first callback-btn-data cd-account-prefix "")
        account-path (str/split account #"::")]
    (get-group-chat-account group-chat-data
                            (keyword "acc-type" (nth account-path 0))
                            (.intValue (biginteger (nth account-path 1))))))

(defn- change-personal-and-related-accounts!
  [chat-id acc-to-change
   {:keys [revoke? reinstate? datetime] :as upd}]
  (let [changed-pers-acc (update-personal-account! chat-id acc-to-change upd)
        changed-pers-acc-id (:id changed-pers-acc)
        member-opts (cond
                      (true? revoke?) [:remove-member changed-pers-acc-id]
                      (true? reinstate?) [:add-member changed-pers-acc-id])]
    ;; TODO: Update group accs of which the 'changed-pers-acc-id' is a member.
    (apply create-general-account! chat-id datetime member-opts)
    changed-pers-acc-id))

;; - CHATS > GROUP CHAT > BOT MESSAGES

(defn- get-bot-msg-id
  [chat-id msg-keys]
  (let [ensured-msg-keys (if (coll? msg-keys) msg-keys [msg-keys])]
    (get-in (get-chat-data chat-id) (into [:bot-messages] ensured-msg-keys))))

(defn- set-bot-msg-id!
  [chat-id msg-keys msg-id]
  (let [full-path ((if (coll? msg-keys) into conj) [:bot-messages] msg-keys)]
    (assoc-in-chat-data! chat-id full-path msg-id))
  msg-id)

(defn- is-reply-to-bot?
  [chat-id message bot-msg-keys]
  (tg-api/is-reply-to? (get-bot-msg-id chat-id bot-msg-keys) message))

(defn- get-bot-msg-state
  [chat-data msg-id]
  (get-in chat-data [:bot-messages :states msg-id]))

(defn- change-bot-msg-state!
  [chat-id msg-type msg-id msg-states new-state]
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [curr-state (get-bot-msg-state chat-data msg-id)]
              (if (or (nil? curr-state)
                      (and (= msg-type (first curr-state))
                           (contains? (get msg-states (second curr-state)) new-state)))
                (assoc-in chat-data [:bot-messages :states msg-id] [msg-type new-state])
                (do
                  (log/errorf "Failed to change state to '%s' for message=%s in chat=%s with current state '%s'"
                              new-state msg-id chat-id curr-state)
                  chat-data)))))]
    (get-bot-msg-state updated-chat-data msg-id)))

;; - CHATS > GROUP CHAT > USER INPUT

(defn- get-user-input-data
  [chat-data user-id input-name]
  (get-in chat-data [:input user-id input-name]))

(defn- set-user-input-data!
  [chat-id user-id input-name input-data]
  (if (nil? input-data)
    (update-chat-data! chat-id
                       update-in [:input user-id] dissoc input-name)
    (update-chat-data! chat-id
                       assoc-in [:input user-id input-name] input-data)))

(defn- drop-user-input-data!
  [chat-id user-id]
  (update-chat-data! chat-id
                     update-in [:input] dissoc user-id))

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
            (-> trimmed
                (subs 0 (dec (count trimmed)))
                str/trim)))
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

(defn- is-user-input-error?
  [chat-id]
  (when (does-chat-exist? chat-id)
    (true? (get (get-chat-data chat-id) :user-error))))

(defn- update-user-input-error-status!
  [chat-id val]
  (let [boolean-val (boolean val)]
    (assoc-in-chat-data! chat-id [:user-error] boolean-val)
    boolean-val))

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


;; STATES & STATE TRANSITIONS

;; TODO: Re-write with an existing state machine (FSM) library.

(def ^:private group-chat-states
  {:initial #{:waiting}
   :waiting #{:waiting
              :ready}
   :ready #{:waiting
            :ready}})

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

(defn- change-chat-state!*
  [chat-type chat-id new-state]
  (let [chat-states (case chat-type
                      :chat-type/group group-chat-states
                      :chat-type/private private-chat-states)]
    (change-chat-state! chat-id chat-states new-state)))

(def ^:private chat-state-transitions
  {:chat-type/group
   {:request-acc-names {:to-state :waiting
                        :message-fn get-personal-account-name-request-msg
                        :message-params [:chat-members]}

    :request-acc-name {:to-state :waiting
                       :message-fn get-new-account-name-request-msg
                       :message-params [:user]}
    :select-group-members {:to-state :waiting
                           :message-fn get-new-member-selection-msg
                           :message-params [:accounts]}
    :request-acc-new-name {:to-state :waiting
                           :message-fn get-account-rename-request-msg
                           :message-params [:user :acc-name]}

    :declare-readiness {:to-state :ready
                        :message-fn get-bot-readiness-msg
                        :message-params [:bot-username]}
    :notify-changes-success {:to-state :ready
                             :message successful-changes-msg}}

   :chat-type/private
   {:request-amount {:to-state :input
                     :message-fn get-private-introduction-msg
                     :message-params [:first-name]}
    :show-calculator {:to-state :interactive-input
                      :message-fn get-interactive-input-msg
                      :message-params [:user-input]}
    :select-group {:to-state :group-selection
                   :message-fn get-group-selection-msg
                   :message-params [:group-refs]}
    :select-expense-item {:to-state :expense-detailing
                          :message-fn get-expense-item-selection-msg
                          :message-params [:expense-items]}
    :request-expense-desc {:to-state :expense-detailing
                           :message-fn get-expense-manual-description-msg
                           :message-params [:first-name]}
    :select-account {:to-state :account-selection
                     :message-fn get-account-selection-msg
                     :message-params [:accounts :txt]}

    :cancel-input {:to-state :input}
    :notify-input-success {:to-state :input
                           :message expense-added-successfully-msg}
    :notify-input-failure {:to-state :input
                           :message-fn get-failed-to-add-new-expense-msg
                           :message-params [:reason]}}})

;; TODO: Use a generalized version of this fn, 'handle-state-transition!'.
(defn- handle-chat-state-transition!
  [chat-id event]
  (let [chat-type (first (:transition event))
        transition (get-in chat-state-transitions (:transition event))
        message (:message transition)
        message-fn (:message-fn transition)
        message-params (:message-params transition)]
    (change-chat-state!* chat-type chat-id (:to-state transition))
    (if (some? message)
      {:message message}
      (when (some? message-fn)
        (let [param-values (or (:params event) {})]
          {:message (apply message-fn (map param-values message-params))})))))


(def ^:private settings-msg-states
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

   :shares-mgmt #{:initial}})

(defn- change-bot-msg-state!*
  [chat-id msg-type msg-id new-state]
  (let [msg-states (case msg-type
                     :settings settings-msg-states)]
    (change-bot-msg-state! chat-id msg-type msg-id msg-states new-state)))

(def ^:private msg-state-transitions
  {:settings
   {:manage-accounts {:to-state :accounts-mgmt
                      :message-fn get-accounts-mgmt-options-msg
                      :message-params [:accounts-by-type]}
    :select-acc-type {:to-state :account-type-selection
                      :message-fn get-account-type-selection-msg
                      :message-params [:account-types :extra-buttons]}
    :rename-account {:to-state :account-renaming
                     :message-fn get-account-selection-msg
                     :message-params [:accounts :txt :extra-buttons]}
    :revoke-account {:to-state :account-revocation
                     :message-fn get-account-selection-msg
                     :message-params [:accounts :txt :extra-buttons]}
    :reinstate-account {:to-state :account-reinstatement
                        :message-fn get-account-selection-msg
                        :message-params [:accounts :txt :extra-buttons]}

    :manage-expense-items {:to-state :expense-items-mgmt}

    :manage-shares {:to-state :shares-mgmt}

    :restore {:to-state :initial
              :message (get-settings-msg false)}}})

;; TODO: Use a generalized version of this fn, 'handle-state-transition!'.
(defn- handle-msg-state-transition!
  [chat-id msg-id event]
  (let [msg-type (first (:transition event))
        transition (get-in msg-state-transitions (:transition event))
        message (:message transition)
        message-fn (:message-fn transition)
        message-params (:message-params transition)]
    (change-bot-msg-state!* chat-id msg-type msg-id (:to-state transition))
    (if (some? message)
      {:message message}
      (when (some? message-fn)
        (let [param-values (or (:params event) {})]
          {:message (apply message-fn (map param-values message-params))})))))


;; RECIPROCAL ACTIONS

;; TODO: Switch to Event-Driven model. Simplifies?
;; HTTP requests should be transformed into events
;; that are handled by appropriate listeners (fns)
;; that, in turn, may result in emitting events.

;; - ABSTRACT ACTIONS
;; TODO: Combine them into a single façade fn w/ a set of opts
;;       (:replace true, :response-handler, :async true, etc.)?

;; TODO: Re-implement as a 'multimethod' with 3 _different_ implementations!
;; TODO: As a precondition, check if ':is-bot-member' is true for 'chat-id'.
;; TODO: Re-implement it in asynchronous fashion, with logging the feedback.
(defn- respond!
  "Uniformly responds to the user action, whether it a message, inline or callback query.
   NB: Properly wrapped in try-catch and logged to highlight the exact HTTP client error."
  [{:keys [type chat-id text inline-query-id results callback-query-id options]
    :as response}]
  (try
    (let [token (config/get-prop :bot-api-token)
          tg-response (case type
                        :text (m-api/send-text token chat-id options text)
                        :inline (m-api/answer-inline token inline-query-id options results)
                        :callback (tg-client/answer-callback-query token callback-query-id options))]
      (log/debug "Telegram returned:" tg-response)
      tg-response)
    (catch Exception e
      ;; TODO: Add 'response' to a 'failed-responses' queue to be able to manually handle it later?
      (log/error e "Failed to respond with:" response))))

(defn- proceed-and-respond!
  "Continues the course of transitions between states and sends a message
   in response to a user (or a group)."
  [chat-id event]
  (let [result (handle-chat-state-transition! chat-id event)]
    (respond! (assoc (:message result) :chat-id chat-id))))

;; TODO: Re-implement it in asynchronous fashion (or adding it as option).
(defn- respond-attentively!
  "Responds and synchronously awaits for a feedback (Telegram's response)
   which is then processed by the provided handler function."
  [response tg-response-handler-fn]
  (let [tg-response (respond! response)]
    (when (:ok tg-response)
      (tg-response-handler-fn (:result tg-response)))))

(defn- proceed-and-respond-attentively!
  "Continues the course of transitions between states, sends a message in
   response to a user (or a group), and awaits for a feedback (Telegram's
   response) which is then processed by the provided handler function."
  [chat-id event tg-response-handler-fn]
  (let [result (handle-chat-state-transition! chat-id event)]
    (respond-attentively! (assoc (:message result) :chat-id chat-id)
                          tg-response-handler-fn)))

;; TODO: Re-implement reusing the common part extracted from the 'respond!' multimethod!
;; TODO: As a precondition, check if ':is-bot-member' is true for 'chat-id'.
(defn- replace-response!
  "Uniformly replaces the existing response to the user, either by update or delete+send.
   NB: Properly wrapped in try-catch and logged to highlight the exact HTTP client error."
  [{:keys [chat-id msg-id text options via-delete?]
    :as response-update}]
  (try
    (let [token (config/get-prop :bot-api-token)
          tg-response (if (true? via-delete?)
                        (do
                          (m-api/delete-text token chat-id msg-id)
                          (m-api/send-text token chat-id options text))
                        (m-api/edit-text token chat-id msg-id options text))]
      (log/debug "Telegram returned:" tg-response)
      tg-response)
    (catch Exception e
      (log/error e "Failed to replace response with:" response-update))))

(defn- proceed-and-replace-response!
  "Continues the course of transitions between states of some existing response
   message with the specified 'msg-id' and replaces its content."
  [chat-id msg-id msg-event]
  (let [result (handle-msg-state-transition! chat-id msg-id msg-event)]
    (replace-response!
      (assoc (:message result) :chat-id chat-id :msg-id msg-id))))

;; - SPECIFIC ACTIONS

; private chats

(defn- report-added-to-new-chat!
  [user-id chat-title]
  (when (can-write-to-user? user-id)
    (respond! (assoc (get-added-to-new-group-msg chat-title) :chat-id user-id))))

(defn- report-removed-from-chat!
  [user-id chat-title]
  (when (can-write-to-user? user-id)
    (respond! (assoc (get-removed-from-group-msg chat-title) :chat-id user-id))))

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
        (proceed-and-respond! chat-id {:transition [:chat-type/private :notify-input-failure]
                                       :params {:reason data-persistence-error}}))
      (else
        (let [pers-accs-count (->> {:acc-types [:acc-type/personal]}
                                   (get-group-chat-accounts chat-id)
                                   count)
              payer-acc-name (when-not (or (= pers-accs-count 1)
                                           (= payer-acc-id debtor-acc-id))
                               (:name (get-group-chat-account
                                        group-chat-data :acc-type/personal payer-acc-id)))
              debtor-acc-name (when-not (= pers-accs-count 1)
                                (:name debtor-acc))
              new-expense-msg (get-new-expense-msg expense-amount
                                                   expense-details
                                                   payer-acc-name
                                                   debtor-acc-name)]
          (respond! (assoc new-expense-msg :chat-id group-chat-id)))
        (proceed-and-respond! chat-id {:transition [:chat-type/private :notify-input-success]})))))

;; TODO: Abstract this away — "selecting 1 of N, with a special case for N=1".
(defn- proceed-with-account!
  [chat-id]
  (let [group-chat-id (:group (get-chat-data chat-id))
        active-accounts (get-group-chat-accounts group-chat-id)]
    (cond
      (< 1 (count active-accounts))
      (let [other-accounts (filter #(not= (:user-id %) chat-id) active-accounts)]
        (proceed-and-respond! chat-id {:transition [:chat-type/private :select-account]
                                       :params {:accounts other-accounts
                                                :txt select-payer-account-txt}}))

      (empty? active-accounts)
      (do
        (log/error "No eligible accounts to select a debtor account from")
        (proceed-and-respond! chat-id {:transition [:chat-type/private :notify-input-failure]
                                       :params {:reason no-debtor-account-error}}))

      :else
      (let [debtor-acc (first active-accounts)]
        (log/debug "Debtor account auto-selected:" debtor-acc)
        (proceed-with-adding-new-expense! chat-id debtor-acc)))))

(defn- proceed-with-expense-details!
  [chat-id group-chat-id first-name]
  (let [expense-items (get-group-chat-expense-items group-chat-id)
        event (if (seq expense-items)
                {:transition [:chat-type/private :select-expense-item]
                 :params {:expense-items expense-items}}
                {:transition [:chat-type/private :request-expense-desc]
                 :params {:first-name first-name}})]
    (proceed-and-respond! chat-id event)))

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
        (proceed-and-respond! chat-id {:transition [:chat-type/private :select-group]
                                       :params {:group-refs group-refs}}))

      (empty? groups)
      (do
        (log/error "No eligible groups to record expenses to")
        (proceed-and-respond! chat-id {:transition [:chat-type/private :notify-input-failure]
                                       :params {:reason no-group-to-record-error}}))

      :else
      (let [group-chat-id (first groups)]
        (log/debug "Group chat auto-selected:" group-chat-id)
        (assoc-in-chat-data! chat-id [:group] group-chat-id)
        (proceed-with-expense-details! chat-id group-chat-id first-name)))))

;; TODO: Abstract away the "selecting N of {M; optional ALL}, M>=N>0" scenario.

; group chats

;; TODO: It would be super cool to have all these abstracted away to "plain data" + a single fn,
;;       akin to how it was made for 'proceed-and-respond!' and 'handle-chat-state-transition!'.

(defn- send-group-chat-intro!
  [chat-id chat-members-count first-name]
  (let [intro-msg (get-introduction-msg chat-members-count first-name)]
    (respond! (assoc intro-msg :chat-id chat-id))))

(defn- restore-group-chat-intro!
  [chat-id user-id msg-id]
  (release-message-lock! chat-id user-id msg-id)
  (proceed-and-replace-response! chat-id msg-id
                                 {:transition [:settings :restore]}))

(defn- send-accounts-left-notification!
  [chat-id uncreated-count]
  (let [accs-left-msg (get-personal-accounts-left-msg uncreated-count)]
    (respond! (assoc accs-left-msg :chat-id chat-id))))

(defn- send-group-chat-settings!
  [chat-id first-time?]
  (let [settings-msg (get-settings-msg first-time?)]
    (respond-attentively!
      (assoc settings-msg :chat-id chat-id)
      #(change-bot-msg-state!* chat-id :settings (:message_id %) :initial))))

(defn- send-waiting-for-user-input-notification!
  [callback-query-id]
  (respond! (assoc waiting-for-user-input-notification
              :callback-query-id callback-query-id)))

(defn- send-message-already-in-use-notification!
  [callback-query-id]
  (respond! (assoc message-already-in-use-notification
              :callback-query-id callback-query-id)))

(defn- send-changes-success-notification!
  [chat-id]
  (respond! (assoc successful-changes-msg :chat-id chat-id)))

(defn- proceed-with-personal-accounts-creation!
  [chat-id ?new-chat-members]
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:chat-type/group :request-acc-names]
     :params {:chat-members ?new-chat-members}}
    #(->> % :message_id (set-bot-msg-id! chat-id :name-request-msg-id))))

(defn- proceed-with-group-chat-finalization!
  [chat-id]
  ;; NB: Tries to create a new version of the "general account"
  ;;     even if the group chat already existed before.
  (create-general-account! chat-id (get-datetime-in-tg-format))
  (proceed-and-respond! chat-id {:transition [:chat-type/group :declare-readiness]
                                 :params {:bot-username (get @*bot-user :username)}})
  ;; TODO: Send the settings conditionally, depending on whether it is the first time.
  (send-group-chat-settings! chat-id true))

(defn- proceed-with-new-chat-members!
  [chat-id new-chat-members]
  (update-chat-data! chat-id
                     update :members-count (partial + (count new-chat-members)))
  (proceed-with-personal-accounts-creation! chat-id new-chat-members))

(defn- proceed-with-left-chat-member!
  [chat-id left-chat-member]
  (update-chat-data! chat-id
                     update :members-count dec)
  (let [chat-data (get-chat-data chat-id)
        user-id (:id left-chat-member)
        pers-acc (get-personal-account chat-data {:user-id user-id})]
    ;; TODO: Double check why the 'pers-acc' doesn't get revoked.
    (change-personal-and-related-accounts! chat-id pers-acc
                                           {:revoke? true
                                            :datetime (get-datetime-in-tg-format)})
    (drop-user-input-data! chat-id user-id)
    (report-removed-from-chat! user-id (get-chat-title chat-data))))

(defn- proceed-with-account-type-selection!
  [chat-id msg-id state-transition-name]
  (proceed-and-replace-response! chat-id msg-id
                                 {:transition [:settings state-transition-name]
                                  :params {:account-types [:acc-type/group
                                                           :acc-type/personal]
                                           :extra-buttons [back-button]}}))

(defn- proceed-with-account-naming!
  [chat-id {user-id :id :as user}]
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:chat-type/group :request-acc-name]
     :params {:user user}}
    #(->> % :message_id
          (set-bot-msg-id! chat-id [:to-user user-id :request-acc-name-msg-id]))))

(defn- proceed-with-account-creation!
  [chat-id acc-type {user-id :id :as user}]
  (let [input-data {:account-type acc-type}]
    (set-user-input-data! chat-id user-id :create-account input-data))
  (case acc-type
    :acc-type/personal
    (proceed-with-account-naming! chat-id user)
    :acc-type/group
    (let [personal-accs (get-group-chat-accounts chat-id
                                                 {:acc-types [:acc-type/personal]})]
      (proceed-and-respond! chat-id {:transition [:chat-type/group :select-group-members]
                                     :params {:accounts personal-accs}}))))

(defn- proceed-with-account-member-selection!
  [chat-id msg-id already-selected-account-members]
  (let [personal-accs (set (get-group-chat-accounts chat-id
                                                    {:acc-types [:acc-type/personal]}))
        selected-accs (set already-selected-account-members)
        remaining-accs (set/difference personal-accs selected-accs)
        accounts-remain? (seq remaining-accs)]
    (when accounts-remain?
      (replace-response! (assoc (get-new-member-selection-msg remaining-accs)
                           :chat-id chat-id :msg-id msg-id)))
    accounts-remain?))

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
       (proceed-and-replace-response! chat-id msg-id
                                      {:transition [:settings state-transition-name]
                                       :params {:accounts eligible-accs
                                                :txt select-account-txt
                                                :extra-buttons [back-button]}})
       (respond! (assoc no-eligible-accounts-notification
                   :callback-query-id callback-query-id))))))

(defn- proceed-with-account-renaming!
  [chat-id {user-id :id :as user} acc-to-rename]
  (let [input-data {:account-type (:type acc-to-rename)
                    :account-id (:id acc-to-rename)}]
    (set-user-input-data! chat-id user-id :rename-account input-data))
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:chat-type/group :request-acc-new-name]
     :params {:user user
              :acc-name (:name acc-to-rename)}}
    #(->> % :message_id
          (set-bot-msg-id! chat-id [:to-user user-id :request-rename-msg-id]))))

(defmacro do-when-chat-is-ready-or-send-notification!
  [chat-state callback-query-id & body]
  `(if (= :ready ~chat-state)
     (do ~@body)
     (send-waiting-for-user-input-notification! ~callback-query-id)))

(defmacro try-with-message-lock-or-send-notification!
  [chat-id user-id msg-id callback-query-id & body]
  `(if (acquire-message-lock! ~chat-id ~user-id ~msg-id)
    (do ~@body)
    (send-message-already-in-use-notification! ~callback-query-id)))

;; - COMMANDS ACTIONS

; private chats

(defn- cmd-private-start!
  [{chat-id :id :as chat}
   {first-name :first_name :as _user}]
  (log/debug "Conversation started in a private chat:" chat)
  (proceed-and-respond! chat-id {:transition [:chat-type/private :request-amount]
                                 :params {:first-name first-name}}))

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-private-help!
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a private chat:" chat)
  (respond! {:type :text
             :chat-id chat-id
             :text "Help is on the way!"}))

(defn- cmd-private-calc!
  [{chat-id :id :as chat}]
  (when (= :input (-> chat-id get-chat-data get-chat-state))
    (log/debug "Calculator opened in a private chat:" chat)
    (proceed-and-respond! chat-id {:transition [:chat-type/private :show-calculator]})))

(defn- cmd-private-cancel!
  [{chat-id :id :as chat}]
  (log/debug "The operation is canceled in a private chat:" chat)
  (handle-chat-state-transition! chat-id {:transition [:chat-type/private :cancel-input]}))

; group chats

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-group-help!
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a group chat:" chat)
  (respond! {:type :text
             :chat-id chat-id
             :text "Help is on the way!"}))

(defn- cmd-group-settings!
  [{chat-id :id :as chat}]
  (log/debug "Settings requested in a group chat:" chat)
  (send-group-chat-settings! chat-id false))


;; API RESPONSES

;; IMPORTANT: The main idea of a group of handlers of any type (message, callback query, etc.)
;;            is to organize a flow of the following nature:
;;            - the first handler of a type have to log the incoming object
;;            - any handler "in between" have to either:
;;              - result in 'op-succeed' — which will stop the further processing
;;              - result in 'nil' — which will pass the request for processing to the next handler
;;                                  (in the order of their declaration in the 'defhandler' body)
;;            - the last handler, in case the request wasn't processed, have to result in 'ignore'

;; NB: Any non-nil will do.
(def ^:private op-succeed {:ok true})

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
  (tg-api/build-immediate-response "answerCallbackQuery"
                                   (assoc ignored-callback-query-notification
                                     :callback_query_id callback-query-id)))

;; TODO: This have to be combined with an Event-Driven model,
;;       i.e. in case of long-term request processing the bot
;;       should immediately respond with sending the 'typing'
;;       chat action (use "sendChatAction" method and see the
;;       /sendChatAction specs for the request parameters).


;; BOT API

;; TODO: Add a rate limiter. Use the 'limiter' from Encore? Or some full-featured RPC library?
; The Bots FAQ on the official Telegram website lists the following limits on server requests:
; - No more than 1 message per second in a single chat,
; - No more than 20 messages per minute in one group,
; - No more than 30 messages per second in total.

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
          chat-data (or (get-chat-data (:id chat))
                        (get-chat-data (:id (:chat msg))))]
      (cond-> nil
              (some? chat)
              (merge {:chat-type (cond
                                   (tg-api/is-private? chat) :chat-type/private
                                   (tg-api/is-group? chat) :chat-type/group)
                      :chat-state (get-chat-state chat-data)})
              (some? msg)
              (merge {:msg-state (get-bot-msg-state chat-data (:message_id msg))}))))

  ;; - BOT COMMANDS

  ; group chats

  (tg-client/command-fn
    "help"
    (fn [{chat :chat :as message}]
      (when (= :chat-type/group (:chat-type message))
        (cmd-group-help! chat)
        op-succeed)))

  (tg-client/command-fn
    "settings"
    (fn [{chat :chat :as message}]
      (when (= :chat-type/group (:chat-type message))
        (cmd-group-settings! chat)
        op-succeed)))

  ; private chats

  (tg-client/command-fn
    "start"
    (fn [{user :from chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-start! chat user)
        op-succeed)))

  (tg-client/command-fn
    "help"
    (fn [{chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-help! chat)
        op-succeed)))

  (tg-client/command-fn
    "calc"
    (fn [{chat :chat :as message}]
      (when (and (= :chat-type/private (:chat-type message))
                 (cmd-private-calc! chat))
        op-succeed)))

  (tg-client/command-fn
    "cancel"
    (fn [{chat :chat :as message}]
      (when (= :chat-type/private (:chat-type message))
        (cmd-private-cancel! chat)
        op-succeed)))

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
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :initial] (:msg-state callback-query))
                 (= cd-accounts callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [accounts-by-type (get-group-chat-accounts-by-type chat-id)]
              (proceed-and-replace-response! chat-id msg-id
                                             {:transition [:settings :manage-accounts]
                                              :params {:accounts-by-type accounts-by-type}}))))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :accounts-mgmt] (:msg-state callback-query)))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (if-let [respond! (condp = callback-btn-data
                                cd-accounts-create #(proceed-with-account-type-selection!
                                                      chat-id msg-id :select-acc-type)
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
              (release-message-lock! chat-id user-id msg-id)))))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id :as user} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :account-type-selection] (:msg-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-type-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id

          (restore-group-chat-intro! chat-id user-id msg-id)
          (let [acc-type-name (str/replace-first callback-btn-data
                                                 cd-account-type-prefix "")
                acc-type (keyword "acc-type" acc-type-name)]
            (proceed-with-account-creation! chat-id acc-type user)))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id :as user} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      ;; TODO: Think out if this message also requires (by design) state mgmt.
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :waiting (:chat-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              new-member (data->account callback-btn-data chat-data)
              input-data (-> (get-user-input-data chat-data user-id :create-account)
                             (update :account-members conj new-member))
              can-proceed? (proceed-with-account-member-selection!
                             chat-id msg-id (:account-members input-data))]
          (set-user-input-data! chat-id user-id :create-account input-data)
          (when-not can-proceed?
            (proceed-with-account-naming! chat-id user)))
        (cb-succeed callback-query-id))))

  ;; TODO: Implement an 'undo' button processing: remove the last added member.

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          user :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      ;; TODO: Think out if this message also requires (by design) state mgmt.
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :waiting (:chat-state callback-query))
                 (= cd-done callback-btn-data))
        ;; TODO: Replace the 'msg-id' w/ a text listing all selected members.
        (proceed-with-account-naming! chat-id user)
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id :as user} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :account-renaming] (:msg-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (restore-group-chat-intro! chat-id user-id msg-id)
            (let [chat-data (get-chat-data chat-id)
                  account-to-rename (data->account callback-btn-data chat-data)]
              (proceed-with-account-renaming! chat-id user account-to-rename))))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :account-revocation] (:msg-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [chat-data (get-chat-data chat-id)
                  account-to-revoke (data->account callback-btn-data chat-data)
                  datetime (get-datetime-in-tg-format)]
              (case (:type account-to-revoke)
                :acc-type/personal (change-personal-and-related-accounts!
                                     chat-id account-to-revoke {:revoke? true
                                                                :datetime datetime})
                :acc-type/group (throw (Exception. "Not implemented yet")))) ;; TODO!

            (restore-group-chat-intro! chat-id user-id msg-id)
            (send-changes-success-notification! chat-id)))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :account-reinstatement] (:msg-state callback-query))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (let [chat-data (get-chat-data chat-id)
                  account-to-reinstate (data->account callback-btn-data chat-data)
                  datetime (get-datetime-in-tg-format)]
              ;; TODO: Check whether we can simply update an existing personal account
              ;;       rather than create a new version w/ 'create-personal-account!'.
              (case (:type account-to-reinstate)
                :acc-type/personal (change-personal-and-related-accounts!
                                     chat-id account-to-reinstate {:reinstate? true
                                                                   :datetime datetime})
                :acc-type/group (throw (Exception. "Not implemented yet")))) ;; TODO!

            (restore-group-chat-intro! chat-id user-id msg-id)
            (send-changes-success-notification! chat-id)))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :initial] (:msg-state callback-query))
                 (= cd-expense-items callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-and-replace-response! chat-id msg-id
                                           {:transition [:settings :manage-expense-items]})))
        (cb-succeed callback-query-id))))

  ;; TODO: Implement handlers for ':expense-items-mgmt'.

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= [:settings :initial] (:msg-state callback-query))
                 (= cd-shares callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (proceed-and-replace-response! chat-id msg-id
                                           {:transition [:settings :manage-shares]})))
        (cb-succeed callback-query-id))))

  ;; TODO: Implement handlers for ':shares-mgmt'.

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/group (:chat-type callback-query))
                 (= :settings (first (:msg-state callback-query)))
                 (= cd-back callback-btn-data))
        (do-when-chat-is-ready-or-send-notification!
          (:chat-state callback-query) callback-query-id
          (try-with-message-lock-or-send-notification!
            chat-id user-id msg-id callback-query-id

            (case (second (:msg-state callback-query))
              (:initial ;; for when something went wrong
                :accounts-mgmt :expense-items-mgmt :shares-mgmt)
              (restore-group-chat-intro! chat-id user-id msg-id)

              (:account-type-selection :account-renaming :account-revocation :account-reinstatement)
              (let [accounts-by-type (get-group-chat-accounts-by-type chat-id)]
                (proceed-and-replace-response! chat-id msg-id
                                               {:transition [:settings :manage-accounts]
                                                :params {:accounts-by-type accounts-by-type}})))))
        (cb-succeed callback-query-id))))

  ; private chats

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
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
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {{chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :expense-detailing (:chat-state callback-query))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (assoc-in-chat-data! chat-id [:expense-item] expense-item)
          (proceed-with-account! chat-id))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
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
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
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
          (update-user-input-error-status! chat-id false)
          (let [old-user-input (get-user-input (get-chat-data chat-id))
                new-user-input (update-user-input! chat-id non-terminal-operation)]
            (when (not= old-user-input new-user-input)
              (replace-response! (assoc (get-interactive-input-msg new-user-input)
                                   :chat-id chat-id :msg-id msg-id))))
          (cb-succeed callback-query-id)))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {first-name :first_name} :from
          {msg-id :message_id {chat-id :id} :chat} :message
          callback-btn-data :data
          :as callback-query}]
      (when (and (= :chat-type/private (:chat-type callback-query))
                 (= :interactive-input (:chat-state callback-query))
                 (= cd-enter callback-btn-data))
        (let [user-input (get-user-input (get-chat-data chat-id))
              parsed-val (nums/parse-arithmetic-expression user-input)]
          (if (and (some? parsed-val) (number? parsed-val))
            (do
              (log/debug "User input:" parsed-val)
              (assoc-in-chat-data! chat-id [:amount] parsed-val)

              (update-user-input-error-status! chat-id false)
              (replace-response! (assoc (get-calculation-success-msg parsed-val)
                                   :chat-id chat-id :msg-id msg-id))

              (proceed-with-group! chat-id first-name))
            (do
              (log/debugf "Invalid user input: \"%s\"" parsed-val)
              (respond! (assoc invalid-input-notification
                          :callback-query-id callback-query-id))

              (when-not (is-user-input-error? chat-id)
                (update-user-input-error-status! chat-id true)
                (replace-response! (assoc (get-calculation-failure-msg parsed-val)
                                     :chat-id chat-id :msg-id msg-id))))))
        (cb-succeed callback-query-id))))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-instance :chat_instance callback-btn-data :data
          :as _callback-query}]
      (ignore "callback query id=%s, data=%s" callback-query-id callback-btn-data)
      (cb-ignored callback-query-id)))

  ;; - CHAT MEMBER STATUS UPDATES

  (tg-client/bot-chat-member-status-fn
    (fn [{{chat-id :id _type :type chat-title :title _username :username :as chat} :chat
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
              chat-members-count (tg-client/get-chat-members-count token chat-id)
              new-chat (setup-new-group-chat! chat-id chat-title chat-members-count)]
          (if (some? new-chat)
            (do
              (send-group-chat-intro! chat-id chat-members-count first-name)
              (proceed-with-personal-accounts-creation! chat-id nil))
            (do
              (log/debugf "The chat=%s already exists" chat-id)
              (update-chat-data! chat-id
                                 assoc :members-count chat-members-count)
              (update-chat-data! chat-id
                                 assoc :is-bot-member true)
              ;; NB: It would be nice to update the list of personal
              ;;     accounts with new ones for those users who have
              ;;     joined the group chat during the bot's absence.
              (proceed-with-personal-accounts-creation! chat-id nil)
              (proceed-with-group-chat-finalization! chat-id)))
          op-succeed)

        (tg-api/has-left? my-chat-member-updated)
        (do
          (update-chat-data! chat-id
                             update :members-count dec)
          (update-chat-data! chat-id
                             assoc :is-bot-member false)
          ;; TODO: What else do we need to perform here?
          op-succeed)

        :else
        (ignore "bot chat member status update dated %s in chat=%s" date chat-id))))

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
    (fn [{{chat-id :id :as chat} :chat
          new-chat-members :new_chat_members
          :as _message}]
      (when (some? new-chat-members)
        (log/debug "New chat members in chat:" chat)
        (let [new-chat-members (filter #(not (:is_bot %)) new-chat-members)]
          (when (seq new-chat-members)
            (proceed-with-new-chat-members! chat-id new-chat-members)
            op-succeed)))))

  (m-hlr/message-fn
    (fn [{{chat-id :id :as chat} :chat
          left-chat-member :left_chat_member
          :as _message}]
      (when (some? left-chat-member)
        (log/debug "Chat member left chat:" chat)
        (when-not (:is_bot left-chat-member)
          (proceed-with-left-chat-member! chat-id left-chat-member)
          op-succeed))))

  (m-hlr/message-fn
    (fn [{msg-id :message_id group-chat-created :group_chat_created :as _message}]
      (when (some? group-chat-created)
        (ignore "message id=%s" msg-id))))

  (m-hlr/message-fn
    (fn [{msg-id :message_id date :date text :text
          {user-id :id} :from
          {chat-id :id chat-title :title} :chat
          :as message}]
      ;; TODO: Figure out how to proceed if someone accidentally closes the reply.
      (when (and (= :chat-type/group (:chat-type message))
                 ;(= :waiting (:chat-state message)) ;; TODO: May conflict w/ new acc/renaming. Check & fix.
                 (is-reply-to-bot? chat-id message :name-request-msg-id))
        ;; NB: Here the 'user-id' exists for sure, since it is the User's response.
        (when-some [_new-chat (setup-new-private-chat! user-id chat-id)]
          (update-private-chat-groups! user-id chat-id))

        (if (create-personal-account! chat-id text date
                                      :user-id user-id :first-msg-id msg-id)
          (report-added-to-new-chat! user-id chat-title)
          (update-personal-account! chat-id {:user-id user-id} {:new-name text}))

        (let [pers-accs-count (->> {:acc-types [:acc-type/personal]}
                                   (get-group-chat-accounts chat-id)
                                   count)
              uncreated-count (get-number-of-missing-personal-accounts
                                (:members-count (get-chat-data chat-id))
                                pers-accs-count)]
          (if (> uncreated-count 0)
            (send-accounts-left-notification! chat-id uncreated-count)
            (do
              (set-bot-msg-id! chat-id :name-request-msg-id nil)
              (proceed-with-new-group-chat-finalization! chat-id))))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{date :date text :text
          {user-id :id} :from
          {chat-id :id} :chat
          :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 ;(= :waiting (:chat-state message)) ;; TODO: May conflict w/ new chat members. Check & fix.
                 (is-reply-to-bot? chat-id message
                                   [:to-user user-id :request-acc-name-msg-id]))
        ;; NB: Here the 'user-id' exists for sure, since it is the User's response.
        (let [chat-data (get-chat-data chat-id)
              input-data (get-user-input-data chat-data user-id :create-account)
              acc-type (:account-type input-data)
              members (map :id (:account-members input-data))]
          (case acc-type
            :acc-type/personal (do
                                 (create-personal-account! chat-id text date)
                                 (create-general-account! chat-id date))
            :acc-type/group (create-group-account! chat-id members text date))

          ;; TODO: Extract this common functionality into a dedicated cleanup fn.
          (set-bot-msg-id! chat-id [:to-user user-id :request-acc-name-msg-id] nil)
          (set-user-input-data! chat-id user-id :create-account nil)

          (proceed-and-respond!
            chat-id {:transition [:chat-type/group :notify-changes-success]}))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id} :from
          {chat-id :id} :chat
          :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 ;(= :waiting (:chat-state message)) ;; TODO: May conflict w/ new chat members. Check & fix.
                 (is-reply-to-bot? chat-id message
                                   [:to-user user-id :request-rename-msg-id]))
        ;; NB: Here the 'user-id' exists for sure, since it is the User's response.
        (let [chat-data (get-chat-data chat-id)
              input-data (get-user-input-data chat-data user-id :rename-account)
              acc-type (:account-type input-data)
              acc-id (:account-id input-data)]
          (update-chat-data! chat-id
                             assoc-in [:accounts acc-type acc-id :name] text)

          ;; TODO: Extract this common functionality into a dedicated cleanup fn.
          (set-bot-msg-id! chat-id [:to-user user-id :request-rename-msg-id] nil)
          (set-user-input-data! chat-id user-id :rename-account nil)

          (proceed-and-respond!
            chat-id {:transition [:chat-type/group :notify-changes-success]}))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id} :chat
          new-chat-title :new_chat_title
          :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (some? new-chat-title))
        (log/debugf "Chat %s title was changed to '%s'" chat-id new-chat-title)
        (set-chat-title! chat-id new-chat-title)
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id} :chat
          migrate-to-chat-id :migrate_to_chat_id
          :as message}]
      (when (and (= :chat-type/group (:chat-type message))
                 (some? migrate-to-chat-id))
        (log/debugf "Group %s has been migrated to a supergroup %s" chat-id migrate-to-chat-id)
        (migrate-group-chat-to-supergroup! chat-id migrate-to-chat-id)
        op-succeed)))

  ; private chats

  (m-hlr/message-fn
    (fn [{text :text
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
            (respond! (assoc invalid-input-msg :chat-id chat-id)))))))

  (m-hlr/message-fn
    (fn [{text :text {chat-id :id} :chat
          :as message}]
      (when (and (= :chat-type/private (:chat-type message))
                 (= :expense-detailing (:chat-state message)))
        (log/debugf "Expense description: \"%s\"" text)
        (assoc-in-chat-data! chat-id [:expense-desc] text)
        (proceed-with-account! chat-id)
        op-succeed)))

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
