(ns general-expenses-accountant.core
  "Bot API and business logic (core functionality)"
  (:require [clojure.set :as set]
            [clojure.string :as str]

            [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [slingshot.slingshot :as slingshot]
            [taoensso
             [encore :as encore]
             [timbre :as log]]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.domain.chat :as chats]
            [general-expenses-accountant.domain.tlog :as tlogs]
            [general-expenses-accountant.nums :as nums]
            [general-expenses-accountant.tg-bot-api :as tg-api]
            [general-expenses-accountant.tg-client :as tg-client])
  (:import [java.util Locale]))

;; STATE

;; TODO: Normally, this should be transformed into a 'cloffeine' cache
;;       which periodically auto-evicts the cast-off chats data. Then,
;;       the initial data should be truncated, e.g. by an 'updated_at'
;;       timestamps, and the data for chats from the incoming requests
;;       should be (re)loaded from the DB on demand.
(defonce ^:private *bot-user (atom nil))

(defonce ^:private *bot-data (atom {}))

(defn init!
  []
  (let [token (config/get-prop :bot-api-token)
        bot-user (get (tg-client/get-me token) :result)]
    (log/debug "Identified myself:" bot-user)
    (reset! *bot-user bot-user))

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
;; notification messages.

(def ^:private min-chat-members-for-group-accounting
  "The number of users in a group chat (including the bot itself)
   required for it to be used for the general expenses accounting."
  3)

(defn- is-chat-for-group-accounting?
  "Determines the use case for a chat by the number of its members."
  [chat-data]
  (let [chat-members-count (:members-count chat-data)]
    (and (some? chat-members-count) ;; a private chat with the bot
         (>= chat-members-count min-chat-members-for-group-accounting))))

(defn- get-number-of-missing-personal-accounts
  "Returns the number of missing personal accounts in a group chat,
   which have to be created before the group chat is ready for the
   general expenses accounting."
  [chat-data number-of-existing-personal-accounts]
  (let [chat-members-count (:members-count chat-data)]
    (- chat-members-count number-of-existing-personal-accounts 1)))


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
(def ^:private default-general-acc-name "общие")
(def ^:private account-types-names {:personal "Личный*"
                                    :group "Групповой"
                                    :general "Общий"})
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
                                #(str cd-account-prefix (name (:type %)) "-" (:id %)))
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
(def ^:private introduction-msg
  {:type :text
   :text "Привет, народ! Я — бот-бухгалтер. И я призван помочь вам с учётом ваших общих расходов.\n
Для того, чтобы начать работу, просто ответьте на следующее сообщение.\n
Также меня можно настроить, чтобы учитывались:
- счета — не только личные, но и групповые;
- статьи расходов — подходящие по смыслу и удобные вам;
- доли — по умолчанию равные для всех счетов и статей расходов."
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Счета" :callback_data cd-accounts)
                                 (tg-api/build-inline-kbd-btn "Статьи" :callback_data cd-expense-items)
                                 (tg-api/build-inline-kbd-btn "Доли" :callback_data cd-shares)]])})})

(def ^:private accounts-mgmt-options-msg
  {:type :text
   :text "Выберите, что вы хотите сделать со счетами в данной группе:"
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

(defn- get-no-eligible-accounts-notification
  [callback-query-id]
  {:type :callback
   :callback-query-id callback-query-id
   :options {:text "Подходящих счетов не найдено"}})

(def ^:private successful-changes-msg
  {:type :text
   :text "Изменения внесены успешно."})

;; TODO: Add messages for 'expense items' here.

;; TODO: Add messages for 'shares' here.

(def ^:private personal-account-name-request-msg
  {:type :text
   :text "Как будет называться ваш личный счёт?"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup :force-reply)})})

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

(defn- get-new-personal-expense-msg
  [expense-amount expense-details]
  (get-new-expense-msg expense-amount expense-details nil nil))

(defn- get-new-group-expense-msg
  [payer-acc-name debtor-acc-name expense-amount expense-details]
  (get-new-expense-msg expense-amount expense-details
                       payer-acc-name debtor-acc-name))

; private chats

(defn- get-private-introduction-msg
  [first-name]
  {:type :text
   :text (str "Привет, " first-name "! Чтобы добавить новый расход просто напиши мне сумму.")})

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
               (str "_" (escape-markdown-v2 "Ошибка в выражении! Вычисление невозможно.") "_\n") ;; italic
               ;; TODO: Make this disclaimer permanent, i.e. always show it in the 'interactive input' mode.
               (escape-markdown-v2 "Введите /cancel, чтобы выйти из режима калькуляции и ввести данные вручную.")])
    {:reply-markup inline-calculator-markup}))

(defn- get-added-to-new-group-msg
  [chat-title]
  {:type :text
   :text (str "Вас добавили в группу \"" chat-title "\".")})

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

(defn- setup-new-chat!
  [chat-id new-chat]
  (when-not (does-chat-exist? chat-id) ;; petty RC
    (let [new-chat (chats/create! (assoc new-chat :id chat-id))]
      (update-bot-data! assoc chat-id new-chat)
      new-chat)))

(defn- setup-new-group-chat!
  [chat-id chat-title chat-members-count]
  (setup-new-chat! chat-id {:type :chat-type/group
                            :data {:state :initial
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
                            :data {:state :initial
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
  "Returns the state of the given chat.
   NB: Be aware that calling that function, e.g. during a state change,
       can cause a race condition (RC) and result in an obsolete value."
  [chat-id]
  (get (get-chat-data chat-id) :state))

(defn- change-chat-state!
  "Returns the new state of the chat if it was set, or 'nil' otherwise."
  [chat-states chat-id new-state]
  {:pre [(does-chat-exist? chat-id)]}
  (let [real-chat-id (get-real-chat-id chat-id)
        bot-data (update-bot-data!
                   (fn [bot-data]
                     (let [curr-state (get-chat-state chat-id)
                           possible-new-states (or (-> chat-states curr-state :to)
                                                   (-> chat-states curr-state))]
                       (if (contains? possible-new-states new-state)
                         (let [state-init-fn (-> chat-states new-state :init-fn)]
                           (as-> bot-data $
                                 (if (some? state-init-fn)
                                   (update-in $ [real-chat-id :data] state-init-fn)
                                   $)
                                 (assoc-in $ [real-chat-id :data :state] new-state)))
                         (do
                           (log/errorf "Failed to change state to '%s' for chat %s with current state '%s'"
                                       new-state chat-id curr-state)
                           bot-data)))))
        upd-chat (get bot-data real-chat-id)]
    (chats/update! upd-chat) ;; TODO: Check if the update actually happened.
    new-state))

(defn- can-write-to-user?
  [user-id]
  (not= :initial (get-chat-state user-id)))

;; - ACCOUNTS

(defn- ->general-account
  [id name created members]
  {:id id
   :type :general
   :name name
   :created created
   :members (set members)})

(defn- ->personal-account
  [id name created ?user-id ?msg-id]
  (cond-> {:id id
           :type :personal
           :name name
           :created created}
          (some? ?user-id) (assoc :user-id ?user-id)
          (some? ?msg-id) (assoc :msg-id ?msg-id)))

(defn- ->group-account
  [id name created members]
  {:id id
   :type :group
   :name name
   :created created
   :members (set members)})

(defn- get-accounts-next-id
  [chat-id]
  (-> (get-chat-data chat-id) :accounts :last-id inc))

(defn- get-accounts-of-type
  [acc-type chat-data]
  (map val (get-in chat-data [:accounts acc-type])))

(defn- get-account-ids-of-type
  [acc-type chat-data]
  (map key (get-in chat-data [:accounts acc-type])))

;; - ACCOUNTS > GENERAL

(defn- get-current-general-account
  [chat-data]
  (let [gen-accs (get-accounts-of-type :general chat-data)]
    (when (seq gen-accs)
      (apply max-key :id gen-accs))))

(defn- create-general-account!
  [chat-id created-dt]
  (let [updated-chat-data
        (update-chat-data!
          chat-id
          (fn [chat-data]
            (let [real-chat-id (get-real-chat-id chat-id)
                  next-id (get-accounts-next-id real-chat-id)

                  upd-accounts-next-id-fn
                  (fn [cd]
                    (assoc-in cd [:accounts :last-id] next-id))

                  upd-old-general-acc-fn
                  (fn [cd]
                    (let [curr-gen-acc (get-current-general-account chat-data)
                          curr-gen-acc-id (:id curr-gen-acc)]
                      (if-not (nil? curr-gen-acc)
                        (assoc-in cd
                                  [:accounts :general curr-gen-acc-id :revoked]
                                  created-dt)
                        cd)))

                  add-new-general-acc-fn
                  (fn [cd]
                    (let [curr-gen-acc (get-current-general-account chat-data)
                          acc-name (if (nil? curr-gen-acc)
                                     default-general-acc-name
                                     (:name curr-gen-acc))
                          members (if (nil? curr-gen-acc)
                                    (get-account-ids-of-type :personal chat-data)
                                    (:members curr-gen-acc))
                          general-acc (->general-account
                                        next-id acc-name created-dt members)]
                      (assoc-in cd [:accounts :general next-id] general-acc)))]
              (-> chat-data
                  upd-accounts-next-id-fn
                  upd-old-general-acc-fn
                  add-new-general-acc-fn))))]
    (get-current-general-account updated-chat-data)))

(defn- add-general-account-member
  [general-account new-pers-acc-id]
  (update general-account :members conj new-pers-acc-id))

(defn- remove-general-account-member
  [general-account old-pers-acc-id]
  (update general-account :members disj old-pers-acc-id))

;; - ACCOUNTS > PERSONAL

(defn- find-personal-account-by-name
  [chat-data acc-name]
  (->> (get-accounts-of-type :personal chat-data)
       (filter #(= (:name %) acc-name))
       first))

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
  [chat-data {?user-id :user-id ?acc-name :name :as ids}]
  {:pre [(or (some? ?user-id) (some? ?acc-name))]}
  (if (some? ?user-id)
    (let [pers-acc-id (get-personal-account-id chat-data ids)]
      (get-in chat-data [:accounts :personal pers-acc-id]))
    (find-personal-account-by-name chat-data ?acc-name)))

;; TODO: Rename optional args in all other fns to start w/ '?...' as well.
(defn- create-personal-account!
  ([chat-id acc-name created-dt]
   (create-personal-account! chat-id acc-name created-dt nil nil))
  ([chat-id acc-name created-dt ?user-id ?first-msg-id]
   ;; TODO: Add some check for the 'acc-name' uniqueness and a re-request flow.
   (when (or (nil? ?user-id)
             (nil? (get-personal-account-id (get-chat-data chat-id) {:user-id ?user-id}))) ;; petty RC
     (let [updated-chat-data
           (update-chat-data!
             chat-id
             (fn [chat-data]
               (let [real-chat-id (get-real-chat-id chat-id)
                     next-id (get-accounts-next-id real-chat-id)

                     upd-accounts-next-id-fn
                     (fn [cd]
                       (assoc-in cd [:accounts :last-id] next-id))

                     add-new-personal-acc-fn
                     (fn [cd]
                       (let [pers-acc (->personal-account
                                        next-id acc-name created-dt
                                        ?user-id ?first-msg-id)]
                         (cond-> (assoc-in cd [:accounts :personal next-id] pers-acc)
                                 (nil? ?user-id) (set-personal-account-id ?user-id next-id))))

                     upd-general-acc-members-fn
                     (fn [cd]
                       (encore/if-let [curr-gen-acc-id (:id (get-current-general-account chat-data))
                                       curr-gen-acc (get-in cd [:accounts :general curr-gen-acc-id])]
                         (assoc-in cd [:accounts :general curr-gen-acc-id]
                                   (add-general-account-member curr-gen-acc next-id))
                         cd))]
                 (-> chat-data
                     upd-accounts-next-id-fn
                     add-new-personal-acc-fn
                     upd-general-acc-members-fn))))]
       (get-personal-account updated-chat-data {:user-id ?user-id :name acc-name})))))

(defn- update-personal-account!
  [chat-id {?user-id :user-id ?acc-name :name :as ids}
   {:keys [new-name revoke? reinstate? datetime] :as _opts}]
  (let [pers-acc-id (get-personal-account-id (get-chat-data chat-id) ids)] ;; petty RC
    (when (some? pers-acc-id)
      (let [updated-chat-data
            (update-chat-data!
              chat-id
              (fn [chat-data]
                (let [upd-acc-name-fn
                      (fn [cd]
                        (if (some? new-name)
                          (assoc-in cd [:accounts :personal pers-acc-id :name] new-name)
                          cd))

                      upd-acc-revoked-fn
                      (fn [cd]
                        (if (true? revoke?)
                          (assoc-in cd [:accounts :personal pers-acc-id :revoked] datetime)
                          cd))

                      upd-acc-reinstated-fn
                      (fn [cd]
                        (if (true? reinstate?)
                          (update-in cd [:accounts :personal pers-acc-id] dissoc :revoked)
                          cd))

                      upd-general-acc-members-fn
                      (fn [cd]
                        (if (and (or (true? revoke?) (true? reinstate?))
                                 (some? (get-current-general-account cd)))
                          (let [new-gen-acc (create-general-account! chat-id datetime)]
                            (cond
                              (true? revoke?)
                              (assoc-in cd [:accounts :general (:id new-gen-acc)]
                                        (remove-general-account-member new-gen-acc pers-acc-id))

                              (true? reinstate?)
                              (assoc-in cd [:accounts :general (:id new-gen-acc)]
                                        (add-general-account-member new-gen-acc pers-acc-id))))
                          cd))]
                  (-> chat-data
                      upd-acc-name-fn
                      upd-acc-revoked-fn
                      upd-acc-reinstated-fn
                      ;; TODO: Need to update group accs that the 'pers-acc-id' is a member of?
                      upd-general-acc-members-fn))))]
        (get-personal-account updated-chat-data {:user-id ?user-id
                                                 :name (or new-name ?acc-name)})))))

;; - ACCOUNTS > GROUP

(defn- find-group-accounts
  [chat-data members]
  (->> (get-accounts-of-type :group chat-data)
       (filter #(= (:members %) members))))

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
            (let [real-chat-id (get-real-chat-id chat-id)
                  next-id (get-accounts-next-id real-chat-id)

                  upd-accounts-next-id-fn
                  (fn [cd]
                    (assoc-in cd [:accounts :last-id] next-id))

                  add-new-group-acc-fn
                  (fn [cd]
                    (let [group-acc (->group-account
                                      next-id acc-name created-dt members)]
                      (assoc-in cd [:accounts :group next-id] group-acc)))]
              (-> chat-data
                  upd-accounts-next-id-fn
                  add-new-group-acc-fn))))]
    (get-latest-group-account updated-chat-data members)))

;; - USER INPUT

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

;; - CHATS > GROUP CHAT

(defn- get-group-chat-expense-items
  [chat-id]
  (let [chat-data (get-chat-data chat-id)]
    ;; TODO: Sort them according popularity.
    (get-in chat-data [:expenses :items])))

(defn- get-group-chat-accounts
  ([chat-id]
   ;; TODO: Sort them according popularity.
   (->> [:general :group :personal]
        (mapcat (partial get-group-chat-accounts chat-id))))
  ([chat-id acc-type]
   (when-let [chat-data (get-chat-data chat-id)]
     (if (= acc-type :general)
       (when-let [gen-acc (get-current-general-account chat-data)]
         [gen-acc])
       (get-accounts-of-type acc-type chat-data)))))

(defn- get-active-group-chat-accounts
  [chat-id & acc-type]
  (->> (apply get-group-chat-accounts chat-id acc-type)
       (filter #(not (contains? % :revoked)))))

(defn- get-group-chat-account
  [group-chat-data acc-type acc-id]
  (get-in group-chat-data [:accounts acc-type acc-id]))

(defn- data->account
  "Retrieves a group chat's account by parsing the callback button data."
  [callback-btn-data group-chat-data]
  (let [account (str/replace-first callback-btn-data cd-account-prefix "")
        account-path (str/split account #"-")]
    (get-group-chat-account group-chat-data
                            (keyword (nth account-path 0))
                            (.intValue (biginteger (nth account-path 1))))))

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
  [chat-id bot-msg-keys message]
  (tg-api/is-reply-to? (get-bot-msg-id chat-id bot-msg-keys) message))

;; - CHATS > PRIVATE CHAT

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
  [group-chat-id]
  {:id group-chat-id
   :title (-> group-chat-id get-chat-data :title)})


;; STATES & STATE TRANSITIONS

;; TODO: Re-write with an existing state machine (FSM) library.

(def ^:private group-chat-states
  {:initial #{:waiting}
   :waiting #{:waiting
              :ready}
   :ready #{:waiting
            :manage-accounts
            :select-group-members
            :request-new-account-name
            :request-account-rename
            :manage-expense-items
            :manage-shares
            :ready}
   :manage-accounts #{:select-acc-type
                      :rename-account
                      :revoke-account
                      :reinstate-account
                      :ready}
   :select-acc-type #{:manage-accounts
                      :ready}
   :select-group-members #{:select-group-members
                           :request-new-account-name
                           :ready}
   :request-new-account-name #{:ready}
   :rename-account #{:manage-accounts
                     :ready}
   :request-account-rename #{:ready}
   :revoke-account #{:manage-accounts
                     :ready}
   :reinstate-account #{:manage-accounts
                        :ready}
   :manage-expense-items #{:ready}
   :manage-shares #{:ready}})

(def ^:private private-chat-states
  {:initial #{:input}
   :input {:to #{:select-group
                 :detail-expense
                 :interactive-input
                 :input}
           :init-fn (fn [chat-data]
                      (select-keys chat-data [:groups]))}
   :interactive-input #{:select-group
                        :detail-expense
                        :input}
   :select-group #{:detail-expense
                   :input}
   :detail-expense #{:select-account
                     :input}
   :select-account #{:input}})

(defn- change-chat-state!*
  [chat-type chat-id new-state]
  (let [chat-states (case chat-type
                      :group group-chat-states
                      :private private-chat-states)]
    (change-chat-state! chat-states chat-id new-state)))

;; TODO: The names of transitions and states are confused. Fix this!
(def ^:private state-transitions
  {:group {:show-intro {:to-state :waiting
                        :message introduction-msg}
           :request-account-names {:to-state :waiting
                                   :message personal-account-name-request-msg}
           :waiting-for-others {:to-state :waiting
                                :message-fn get-personal-accounts-left-msg
                                :message-params [:uncreated-count]}
           :declare-readiness {:to-state :ready
                               :message-fn get-bot-readiness-msg
                               :message-params [:bot-username]}

           :accounts-mgmt {:to-state :manage-accounts
                           :message accounts-mgmt-options-msg}
           :account-type-selection {:to-state :select-acc-type
                                    :message-fn get-account-type-selection-msg
                                    :message-params [:account-types :extra-buttons]}
           :request-acc-name {:to-state :request-new-account-name
                              :message-fn get-new-account-name-request-msg
                              :message-params [:user]}
           :request-group-members {:to-state :select-group-members
                                   :message-fn get-new-member-selection-msg
                                   :message-params [:accounts]}
           :account-renaming {:to-state :rename-account
                              :message-fn get-account-selection-msg
                              :message-params [:accounts :txt :extra-buttons]}
           :request-acc-rename {:to-state :request-account-rename
                                :message-fn get-account-rename-request-msg
                                :message-params [:user :acc-name]}
           :account-revocation {:to-state :revoke-account
                                :message-fn get-account-selection-msg
                                :message-params [:accounts :txt :extra-buttons]}
           :account-reinstatement {:to-state :reinstate-account
                                   :message-fn get-account-selection-msg
                                   :message-params [:accounts :txt :extra-buttons]}

           :expense-items-mgmt {:to-state :manage-expense-items}
           :shares-mgmt {:to-state :manage-shares}

           :restore-intro {:to-state :ready
                           :message introduction-msg}
           :successful-changes {:to-state :ready
                                :message successful-changes-msg}}

   :private {:amount-input {:to-state :input
                            :message-fn get-private-introduction-msg
                            :message-params [:first-name]}
             :interactive-input {:to-state :interactive-input
                                 :message-fn get-interactive-input-msg
                                 :message-params [:user-input]}
             :group-selection {:to-state :select-group
                               :message-fn get-group-selection-msg
                               :message-params [:group-refs]}
             :expense-item-selection {:to-state :detail-expense
                                      :message-fn get-expense-item-selection-msg
                                      :message-params [:expense-items]}
             :manual-expense-description {:to-state :detail-expense
                                          :message-fn get-expense-manual-description-msg
                                          :message-params [:first-name]}
             :accounts-selection {:to-state :select-account
                                  :message-fn get-account-selection-msg
                                  :message-params [:accounts :txt]}

             :successful-input {:to-state :input
                                :message expense-added-successfully-msg}
             :failed-input {:to-state :input
                            :message-fn get-failed-to-add-new-expense-msg
                            :message-params [:reason]}
             :canceled-input {:to-state :input}}})

(defn- handle-state-transition
  [chat-id event]
  (let [chat-type (first (:transition event))
        transition (get-in state-transitions (:transition event))
        message (:message transition)
        message-fn (:message-fn transition)
        message-params (:message-params transition)]
    (change-chat-state!* chat-type chat-id (:to-state transition))
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
  (let [result (handle-state-transition chat-id event)]
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
  (let [result (handle-state-transition chat-id event)]
    (respond-attentively! (assoc (:message result) :chat-id chat-id)
                          tg-response-handler-fn)))

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
  "Continues the course of transitions between states and replaces some
   existing response to a user (or a group)."
  [chat-id event msg-id]
  (let [result (handle-state-transition chat-id event)]
    (replace-response! (assoc (:message result) :chat-id chat-id
                                                :msg-id msg-id))))

;; - SPECIFIC ACTIONS

; private chats

(defn- proceed-with-adding-new-expense!
  [chat-id user-id debtor-acc]
  (let [chat-data (get-chat-data chat-id)
        group-chat-id (:group chat-data)
        group-chat-data (get-chat-data group-chat-id)
        payer-acc-id (get-personal-account-id
                       group-chat-data {:user-id user-id})
        expense-amount (:amount chat-data)
        expense-details (or (:expense-item chat-data)
                            (:expense-desc chat-data))
        new-transaction {:chat-id group-chat-id
                         :payer-acc-id payer-acc-id
                         :debtor-acc-id (:id debtor-acc)
                         :expense-amount expense-amount
                         :expense-details expense-details}]
    (slingshot/try+
      (add-transaction! (tlogs/create! new-transaction))
      (catch Exception e
        ;; TODO: Retry to log the failed transaction?
        (log/error e "Failed to log transaction:" new-transaction)
        (proceed-and-respond! chat-id {:transition [:private :failed-input]
                                       :params {:reason data-persistence-error}}))
      (else
        (let [payer-acc (get-group-chat-account group-chat-data
                                                :personal payer-acc-id)
              new-expense-msg (if (is-chat-for-group-accounting? group-chat-data)
                                (get-new-group-expense-msg (:name payer-acc)
                                                           (:name debtor-acc)
                                                           expense-amount
                                                           expense-details)
                                (get-new-personal-expense-msg expense-amount
                                                              expense-details))]
          (respond! (assoc new-expense-msg :chat-id group-chat-id)))
        (proceed-and-respond! chat-id {:transition [:private :successful-input]})))))

;; TODO: Abstract this away — "selecting 1 of N, with a special case for N=1".
(defn- proceed-with-account!
  [chat-id user-id]
  (let [group-chat-id (:group (get-chat-data chat-id))
        accounts (get-active-group-chat-accounts group-chat-id)]
    (cond
      (< 1 (count accounts))
      (let [other-accounts (filter #(not= (:user-id %) user-id) accounts)]
        (proceed-and-respond! chat-id {:transition [:private :accounts-selection]
                                       :params {:accounts other-accounts
                                                :txt select-payer-account-txt}}))
      (empty? accounts)
      (do
        (log/error "No eligible accounts to select a debtor account from")
        (proceed-and-respond! chat-id {:transition [:private :failed-input]
                                       :params {:reason no-debtor-account-error}}))
      :else
      (let [debtor-acc (first accounts)]
        (log/debug "Debtor account auto-selected:" debtor-acc)
        (proceed-with-adding-new-expense! chat-id user-id debtor-acc)))))

(defn- proceed-with-expense-details!
  [chat-id group-chat-id first-name]
  (let [expense-items (get-group-chat-expense-items group-chat-id)
        event (if (seq expense-items)
                {:transition [:private :expense-item-selection]
                 :params {:expense-items expense-items}}
                {:transition [:private :manual-expense-description]
                 :params {:first-name first-name}})]
    (proceed-and-respond! chat-id event)))

;; TODO: Abstract this away — "selecting 1 of N, with a special case for N=1".
(defn- proceed-with-group!
  [chat-id first-name]
  (let [is-eligible? (fn [group-chat-id]
                       (let [group-chat-data (get-chat-data group-chat-id)
                             pers-acc-id (get-personal-account-id
                                           group-chat-data {:user-id chat-id})
                             pers-acc (get-group-chat-account group-chat-data
                                                              :personal
                                                              pers-acc-id)]
                         (not (contains? pers-acc :revoked))))
        groups (->> (get-chat-data chat-id)
                    get-private-chat-groups
                    (filter is-eligible?))]
    (cond
      (< 1 (count groups))
      (let [group-refs (map ->group-ref groups)]
        (proceed-and-respond! chat-id {:transition [:private :group-selection]
                                       :params {:group-refs group-refs}}))
      (empty? groups)
      (do
        (log/error "No eligible groups to record expenses to")
        (proceed-and-respond! chat-id {:transition [:private :failed-input]
                                       :params {:reason no-group-to-record-error}}))
      :else
      (let [group-chat-id (first groups)]
        (log/debug "Group chat auto-selected:" group-chat-id)
        (assoc-in-chat-data! chat-id [:group] group-chat-id)
        (proceed-with-expense-details! chat-id group-chat-id first-name)))))

;; TODO: Abstract away the "selecting N of {M; optional ALL}, M>=N>0" scenario.

; group chats

(defn- proceed-with-introduction!
  [chat-id]
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:group :show-intro]}
    #(->> % :message_id (set-bot-msg-id! chat-id :intro-msg-id))))

(defn- proceed-with-personal-accounts-creation!
  [chat-id]
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:group :request-account-names]}
    #(->> % :message_id (set-bot-msg-id! chat-id :name-request-msg-id))))

(defn- proceed-with-account-type-selection!
  [chat-id msg-id state-transition-name]
  (proceed-and-replace-response! chat-id
                                 {:transition [:group state-transition-name]
                                  :params {:account-types [:group :personal]
                                           :extra-buttons [back-button]}}
                                 msg-id))

(defn- proceed-with-account-naming!
  [chat-id {user-id :id :as user}]
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:group :request-acc-name]
     :params {:user user}}
    #(->> % :message_id
          (set-bot-msg-id! chat-id [:to-user user-id :request-acc-name-msg-id]))))

(defn- proceed-with-account-creation!
  [chat-id acc-type {user-id :id :as user}]
  (let [input-data {:account-type acc-type}]
    ;; TODO: Extract into a generalized fn for assoc/dissoc-ing the input data.
    (update-chat-data! chat-id
                       assoc-in [:input user-id :create-account] input-data))
  (case acc-type
    :personal
    (proceed-with-account-naming! chat-id user)
    :group
    (let [personal-accs (get-accounts-of-type :personal (get-chat-data chat-id))]
      (proceed-and-respond! chat-id {:transition [:group :request-group-members]
                                     :params {:accounts personal-accs}}))))

(defn- proceed-with-account-member-selection!
  [chat-id msg-id already-selected-account-members]
  (let [chat-data (get-chat-data chat-id)
        personal-accs (set (get-accounts-of-type :personal chat-data))
        selected-accs (set already-selected-account-members)
        remaining-accs (set/difference personal-accs selected-accs)]
    (when (seq remaining-accs)
      (proceed-and-replace-response! chat-id
                                     {:transition [:group :request-group-members]
                                      :params {:accounts remaining-accs}}
                                     msg-id))))

(defn- proceed-with-account-selection!
  ([chat-id msg-id callback-query-id
    state-transition-name]
   ;; TODO: Re-write more effectively, w/o the '(constantly true)'.
   (proceed-with-account-selection! chat-id msg-id callback-query-id
                                    state-transition-name (constantly true)))
  ([chat-id msg-id callback-query-id
    state-transition-name filter-pred]
   (let [eligible-accounts (->> [:group :personal]
                                (mapcat (partial get-group-chat-accounts chat-id))
                                (filter filter-pred))]
     (if (seq eligible-accounts)
       (proceed-and-replace-response! chat-id
                                      {:transition [:group state-transition-name]
                                       :params {:accounts eligible-accounts
                                                :txt select-account-txt
                                                :extra-buttons [back-button]}}
                                      msg-id)
       (respond! (get-no-eligible-accounts-notification callback-query-id))))))

(defn- proceed-with-account-renaming!
  [chat-id {user-id :id :as user} acc-to-rename]
  (let [input-data {:account-type (:type acc-to-rename)
                    :account-id (:id acc-to-rename)}]
    ;; TODO: Extract into a generalized fn for assoc/dissoc-ing the input data.
    (update-chat-data! chat-id
                       assoc-in [:input user-id :rename-account] input-data))
  (proceed-and-respond-attentively!
    chat-id
    {:transition [:group :request-acc-rename]
     :params {:user user
              :acc-name (:name acc-to-rename)}}
    #(->> % :message_id
          (set-bot-msg-id! chat-id [:to-user user-id :request-rename-msg-id]))))

;; - COMMANDS ACTIONS

;; TODO: Rename properly, with '!' in the end (they modify state).

; private chats

(defn- cmd-private-start
  [{chat-id :id :as chat}
   {first-name :first_name :as _user}]
  (log/debug "Conversation started in a private chat:" chat)
  (proceed-and-respond! chat-id {:transition [:private :amount-input]
                                 :params {:first-name first-name}}))

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-private-help
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a private chat:" chat)
  (respond! {:type :text
             :chat-id chat-id
             :text "Help is on the way!"}))

(defn- cmd-private-calc
  [{chat-id :id :as chat}]
  (when (= :input (get-chat-state chat-id))
    (log/debug "Calculator opened in a private chat:" chat)
    (proceed-and-respond! chat-id {:transition [:private :interactive-input]})))

(defn- cmd-private-cancel
  [{chat-id :id :as chat}]
  (log/debug "The operation is canceled in a private chat:" chat)
  (handle-state-transition chat-id {:transition [:private :canceled-input]}))

; group chats

;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
(defn- cmd-group-help
  [{chat-id :id :as chat}]
  (log/debug "Help requested in a group chat:" chat)
  (respond! {:type :text
             :chat-id chat-id
             :text "Help is on the way!"}))


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

;; TODO: Implement an 'immediate response'.
;;
;; Telegram is able to handle the following response form:
;; {
;;   "method": "sendMessage",
;;   "chat_id": body.message.chat.id,
;;   "reply_to_message_id": body.message.message_id,
;;   "text": "..."
;; };
;;
;; NB: This have to be combined with an Event-Driven model,
;;     i.e. in case of long-term request processing the bot
;;;    should immediately respond with sending the 'typing'
;;     chat action (see /sendChatAction specs).


;; BOT API

;; TODO: Add a rate limiter. Use the 'limiter' from Encore? Or some full-featured RPC library?
; The Bots FAQ on the official Telegram website lists the following limits on server requests:
; - No more than 1 message per second in a single chat,
; - No more than 20 messages per minute in one group,
; - No more than 30 messages per second in total.

;; TODO: Add a common context for handlers, which includes e.g. a 'chat state'.
(m-hlr/defhandler
  handler

  ;; - BOT COMMANDS

  ; Each bot has to handle '/start' and '/help' commands.
  (tg-client/command-fn
    "start"
    (fn [{user :from chat :chat :as _message}]
      (when (tg-api/is-private? chat)
        (cmd-private-start chat user)
        op-succeed)))

  (tg-client/command-fn
    "help"
    (fn [{chat :chat :as _message}]
      (cond
        (tg-api/is-private? chat)
        (cmd-private-help chat)

        (tg-api/is-group? chat)
        (cmd-group-help chat))
      op-succeed))

  (tg-client/command-fn
    "calc"
    (fn [{chat :chat :as _message}]
      (when (and (tg-api/is-private? chat)
                 (cmd-private-calc chat))
        op-succeed)))

  (tg-client/command-fn
    "cancel"
    (fn [{chat :chat :as _message}]
      (when (tg-api/is-private? chat)
        (cmd-private-cancel chat)
        op-succeed)))

  ;; - INLINE QUERIES

  (m-hlr/inline-fn
    (fn [inline-query]
      (log/debug "Inline query:" inline-query)))

  ;; TODO: Try to implement an inline query answered w/ 'switch_pm_text' parameter?

  (m-hlr/inline-fn
    (fn [{inline-query-id :id _user :from query-str :query _offset :offset :as _inline-query}]
      (ignore "inline query id=%s, query=%s" inline-query-id query-str)))

  ;; - CALLBACK QUERIES

  (m-hlr/callback-fn
    (fn [callback-query]
      (log/debug "Callback query:" callback-query)))

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :ready (get-chat-state chat-id))
                 (= cd-accounts callback-btn-data))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :accounts-mgmt]}
                                       msg-id)
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id
          {user-id :id :as _user} :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :manage-accounts (get-chat-state chat-id)))
        (encore/when-let [;; TODO: Extract all these predicates into a namespace predefined fns.
                          filter-revoked-pred (fn [acc]
                                                (contains? acc :revoked))
                          filter-active-pred (fn [acc]
                                               (not (filter-revoked-pred acc)))
                          handler-fn
                          (condp = callback-btn-data
                            cd-accounts-create #(proceed-with-account-type-selection!
                                                  chat-id msg-id :account-type-selection)
                            cd-accounts-rename #(proceed-with-account-selection!
                                                  chat-id msg-id callback-query-id
                                                  :account-renaming
                                                  ;; TODO: Specify the predicate more precisely.
                                                  filter-active-pred)
                            cd-accounts-revoke #(proceed-with-account-selection!
                                                  chat-id msg-id callback-query-id
                                                  :account-revocation
                                                  (fn [acc]
                                                    (and (filter-active-pred acc)
                                                         (not= (:user-id acc) user-id))))
                            cd-accounts-reinstate #(proceed-with-account-selection!
                                                     chat-id msg-id callback-query-id
                                                     :account-reinstatement
                                                     filter-revoked-pred)
                            nil)]
          (handler-fn)
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{user :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :select-acc-type (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-type-prefix))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :restore-intro]}
                                       msg-id)
        (let [acc-type-name (str/replace-first callback-btn-data
                                               cd-account-type-prefix "")
              acc-type (keyword acc-type-name)]
          (proceed-with-account-creation! chat-id acc-type user))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as user} :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :select-group-members (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              new-member (data->account callback-btn-data chat-data)
              input-data (-> (get-in chat-data [:input user-id :create-account])
                             (update :account-members conj new-member))
              acc-members (:account-members input-data)]
          ;; TODO: Extract into a generalized fn for assoc/dissoc-ing the input data.
          (update-chat-data! chat-id
                             assoc-in [:input user-id :create-account] input-data)
          (when-not
            (proceed-with-account-member-selection! chat-id msg-id acc-members)
            (proceed-with-account-naming! chat-id user)))
        op-succeed)))

  ;; TODO: Implement an 'undo' button processing: remove the last added member.

  (m-hlr/callback-fn
    (fn [{user :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :select-group-members (get-chat-state chat-id))
                 (= cd-done callback-btn-data))
        ;; TODO: Replace the 'msg-id' w/ a text listing all selected members.
        (proceed-with-account-naming! chat-id user)
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{user :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :rename-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :restore-intro]}
                                       msg-id)
        (let [chat-data (get-chat-data chat-id)
              account-to-rename (data->account callback-btn-data chat-data)]
          (proceed-with-account-renaming! chat-id user account-to-rename))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :revoke-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              account-to-revoke (data->account callback-btn-data chat-data)
              datetime (get-datetime-in-tg-format)]
          (case (:type account-to-revoke)
            :personal (update-personal-account! chat-id
                                                account-to-revoke
                                                {:revoke? true
                                                 :datetime datetime})
            :group (throw (Exception. "Not implemented yet"))))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :restore-intro]}
                                       msg-id)
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :reinstate-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              account-to-reinstate (data->account callback-btn-data chat-data)]
          ;; TODO: Check whether we can simply update an existing personal account
          ;;       rather than create a new version w/ 'create-personal-account!'.
          (case (:type account-to-reinstate)
            :personal (update-personal-account! chat-id
                                                account-to-reinstate
                                                {:reinstate? true})
            :group (throw (Exception. "Not implemented yet"))))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :restore-intro]}
                                       msg-id)
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :ready (get-chat-state chat-id))
                 (= cd-expense-items callback-btn-data))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :expense-items-mgmt]}
                                       msg-id)
        op-succeed)))

  ;; TODO: Implement handlers for ':manage-expense-items'.

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= :ready (get-chat-state chat-id))
                 (= cd-shares callback-btn-data))
        (proceed-and-replace-response! chat-id
                                       {:transition [:group :shares-mgmt]}
                                       msg-id)
        op-succeed)))

  ;; TODO: Implement handlers for ':manage-shares'.

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-group? chat)
                 (= cd-back callback-btn-data))
        (when-let [state-transition-name
                   (condp contains? (get-chat-state chat-id)
                     #{:manage-accounts :manage-expense-items :manage-shares} :restore-intro
                     #{:select-acc-type :rename-account :revoke-account :reinstate-account} :accounts-mgmt
                     nil)]
          (proceed-and-replace-response! chat-id
                                         {:transition [:group state-transition-name]}
                                         msg-id)
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{first-name :first_name :as _user} :from
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :select-group (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-group-chat-prefix))
        (let [group-chat-id-str (str/replace-first callback-btn-data cd-group-chat-prefix "")
              group-chat-id (nums/parse-int group-chat-id-str)]
          (assoc-in-chat-data! chat-id [:group] group-chat-id)
          (proceed-with-expense-details! chat-id group-chat-id first-name))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :detail-expense (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (assoc-in-chat-data! chat-id [:expense-item] expense-item)
          (proceed-with-account! chat-id user-id))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :select-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [group-chat-id (:group (get-chat-data chat-id))
              group-chat-data (get-chat-data group-chat-id)
              debtor-acc (data->account callback-btn-data group-chat-data)]
          (proceed-with-adding-new-expense! chat-id user-id debtor-acc))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :interactive-input (get-chat-state chat-id)))
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
              (proceed-and-replace-response! chat-id
                                             {:transition [:private :interactive-input]
                                              :params {:user-input new-user-input}}
                                             msg-id)))
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{first-name :first_name :as _user} :from
          {msg-id :message_id {chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :interactive-input (get-chat-state chat-id))
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
              ;; TODO: Send a notification if the input is invalid.
              (when-not (is-user-input-error? chat-id)
                (update-user-input-error-status! chat-id true)
                (replace-response! (assoc (get-calculation-failure-msg parsed-val)
                                     :chat-id chat-id :msg-id msg-id))))))
        op-succeed)))

  ;; TODO: Add universal callback for ignored queries ("Запрос не может быть обработан").

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-instance :chat_instance callback-btn-data :data :as _callback-query}]
      (ignore "callback query id=%s, data=%s" callback-query-id callback-btn-data)))

  ;; - CHAT MEMBER STATUS UPDATES

  (tg-client/bot-chat-member-status-fn
    (fn [{{chat-id :id _type :type chat-title :title _username :username :as chat} :chat
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          date :date
          {_old-user :user _old-status :status :as _old-chat-member} :old_chat_member
          {_new-user :user _new-status :status :as _new-chat-member} :new_chat_member
          :as my-chat-member-updated}]
      (log/debug "Bot chat member status updated in:" chat)
      (if (tg-api/has-joined? my-chat-member-updated)
        (do
          (let [token (config/get-prop :bot-api-token)
                chat-members-count (tg-client/get-chat-members-count token chat-id)
                new-chat (setup-new-group-chat! chat-id chat-title chat-members-count)
                chat-data (if (some? new-chat)
                            (:data new-chat)
                            (get-chat-data chat-id))]
            ;; NB: Create a new version of the general account,
            ;;     even if the group chat already existed.
            (when (is-chat-for-group-accounting? chat-data)
              (create-general-account! chat-id date)))

          (when (= :initial (get-chat-state chat-id))
            (proceed-with-introduction! chat-id)
            ;; NB: It would be nice to update the list of personal
            ;;     accounts with new ones for those users who have
            ;;     joined the group chat during the bot's absence,
            ;;     but this is not feasible, since the chat state
            ;;     may be not ':waiting' at this point.
            (proceed-with-personal-accounts-creation! chat-id))
          op-succeed)
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
      ;; TODO: State transition of the group to ':waiting'.
      ;; TODO: Inc-/decrement the ':members-count' in this group.
      ;; TODO: Create a general account anew (and check its ':members').
      (ignore "chat member status update dated %s in chat=%s" date chat-id)))

  ;; - PLAIN MESSAGES

  (m-hlr/message-fn
    (fn [{msg-id :message_id group-chat-created :group_chat_created :as _message}]
      (when (some? group-chat-created)
        (ignore "message id=%s" msg-id))))

  (m-hlr/message-fn
    (fn [{msg-id :message_id date :date text :text
          {user-id :id :as _user} :from
          {chat-id :id chat-title :title :as chat} :chat
          :as message}]
      (when (and (tg-api/is-group? chat)
                 (= :waiting (get-chat-state chat-id))
                 (is-reply-to-bot? chat-id :name-request-msg-id message))
        (let [group-chat-id (get-real-chat-id chat-id)
              new-chat (setup-new-private-chat! user-id group-chat-id)]
          (when (nil? new-chat)
            (update-private-chat-groups! user-id group-chat-id)))

        (if (create-personal-account! chat-id text date user-id msg-id)
          (when (can-write-to-user? user-id)
            (respond! (assoc (get-added-to-new-group-msg chat-title) :chat-id user-id)))
          (update-personal-account! chat-id {:user-id user-id} {:new-name text}))

        (let [chat-data (get-chat-data chat-id)
              pers-accs-count (count (get-account-ids-of-type :personal chat-data))
              uncreated-count (get-number-of-missing-personal-accounts
                                chat-data pers-accs-count)
              event (if (zero? uncreated-count)
                      {:transition [:group :declare-readiness]
                       :params {:bot-username (get @*bot-user :username)}}
                      {:transition [:group :waiting-for-others]
                       :params {:uncreated-count uncreated-count}})]
          (proceed-and-respond! chat-id event))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{date :date text :text
          {user-id :id :as _user} :from
          {chat-id :id :as chat} :chat
          :as message}]
      (when (and (tg-api/is-group? chat)
                 ;; TODO: Check if it blocks other users' interactions.
                 ;;       Re-implement w/o the state transition, if so.
                 (= :request-new-account-name (get-chat-state chat-id))
                 (is-reply-to-bot? chat-id
                                   [:to-user user-id :request-acc-name-msg-id]
                                   message))
        (let [chat-data (get-chat-data chat-id)
              input-data (get-in chat-data [:input user-id :create-account])
              acc-type (:account-type input-data)
              members (map :id (:account-members input-data))]
          (case acc-type
            :personal (create-personal-account! chat-id text date)
            :group (create-group-account! chat-id members text date))

          ;; TODO: Extract this common function-ty into a dedicated cleanup fn.
          (update-chat-data! chat-id
                             update-in [:to-user user-id] dissoc :request-acc-name-msg-id)
          (update-chat-data! chat-id
                             update-in [:input user-id] dissoc :create-account)

          (proceed-and-respond! chat-id {:transition [:group :successful-changes]}))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id :as _user} :from
          {chat-id :id :as chat} :chat
          :as message}]
      (when (and (tg-api/is-group? chat)
                 ;; TODO: Check if it blocks other users' interactions.
                 ;;       Re-implement w/o the state transition, if so.
                 (= :request-account-rename (get-chat-state chat-id))
                 (is-reply-to-bot? chat-id
                                   [:to-user user-id :request-rename-msg-id]
                                   message))
        (let [chat-data (get-chat-data chat-id)
              input-data (get-in chat-data [:input user-id :rename-account])
              acc-type (:account-type input-data)
              acc-id (:account-id input-data)]
          (update-chat-data! chat-id
                             assoc-in [:accounts acc-type acc-id :name] text)

          ;; TODO: Extract this common function-ty into a dedicated cleanup fn.
          (update-chat-data! chat-id
                             update-in [:to-user user-id] dissoc :request-rename-msg-id)
          (update-chat-data! chat-id
                             update-in [:input user-id] dissoc :rename-account)

          (proceed-and-respond! chat-id {:transition [:group :successful-changes]}))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id :as chat} :chat
          new-chat-title :new_chat_title
          :as _message}]
      (when (and (tg-api/is-group? chat)
                 (some? new-chat-title))
        (log/debugf "Chat %s title was changed to '%s'" chat-id new-chat-title)
        (assoc-in-chat-data! chat-id [:title] new-chat-title)
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id :as chat} :chat
          migrate-to-chat-id :migrate_to_chat_id
          :as _message}]
      (when (and (tg-api/is-group? chat)
                 (some? migrate-to-chat-id))
        (log/debugf "Group %s has been migrated to a supergroup %s" chat-id migrate-to-chat-id)
        (let [new-chat (setup-new-supergroup-chat! migrate-to-chat-id chat-id)
              chat-data (if (some? new-chat)
                          (:data new-chat)
                          (get-chat-data chat-id))
              group-users (-> chat-data
                              (get :user-account-mapping)
                              keys)]
          (doseq [user-id group-users]
            (update-private-chat-groups! user-id chat-id migrate-to-chat-id)))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{text :text
          {first-name :first_name :as _user} :from
          {chat-id :id :as chat} :chat
          :as _message}]
      (when (and (tg-api/is-private? chat)
                 (= :input (get-chat-state chat-id)))
        (let [input (nums/parse-number text)]
          ;; TODO: Send a notification if the input is invalid.
          (when (number? input)
            (log/debug "User input:" input)
            (assoc-in-chat-data! chat-id [:amount] input)
            (proceed-with-group! chat-id first-name)
            op-succeed)))))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id :as _user} :from
          {chat-id :id :as chat} :chat
          :as _message}]
      (when (and (tg-api/is-private? chat)
                 (= :detail-expense (get-chat-state chat-id)))
        (log/debugf "Expense description: \"%s\"" text)
        (assoc-in-chat-data! chat-id [:expense-desc] text)
        (proceed-with-account! chat-id user-id)
        op-succeed)))

  ; A "match-all catch-through" case.
  (m-hlr/message-fn
    (fn [{msg-id :message_id _date :date _text :text
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          {_chat-id :id _type :type _chat-title :title _username :username :as chat} :chat
          _original-msg :reply_to_message ;; for replies
          _new-chat-members :new_chat_members
          _left-chat-member :left_chat_member
          _group-chat-created :group_chat_created
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
