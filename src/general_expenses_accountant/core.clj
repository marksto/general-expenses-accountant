(ns general-expenses-accountant.core
  "Bot API and business logic (core functionality)"
  (:require [morse.handlers :as m-hlr]
            [taoensso.timbre :as log]

            [general-expenses-accountant.nums :as nums]
            [general-expenses-accountant.tg-bot-api :as tg-api]
            [general-expenses-accountant.tg-client :as tg-client]
            [clojure.string :as str]))

;; STATE

;; TODO: Move to a dedicated 'db' ns.
(defonce ^:private *bot-data (atom {}))

#_(def sample-data
    ;; private chat-id -> user-specific
    {280000000 {:groups [{:id -560000000
                          :title "Family"}
                         {:id -1001000000000
                          :title "Family"}]

                ;; for data input
                :state :input
                :input 3
                :group -560000000
                :expense-item "food"}}

    ;; group chat-id -> group-level settings
    {-560000000 {:state :initial

                 ;; messages to delete/check replies to
                 :bot-messages {:intro-msg-id 1
                                :name-request-msg-id 2}

                 ;; configured by users
                 :accounts {:general {0 {:id 0
                                         :type :general
                                         :created 426300760
                                         :revoked 432500000
                                         :members [1 2 3]}}
                            :personal {1 {:id 1
                                          :type :personal
                                          :name "Alice"
                                          :created 426300760
                                          :msg-id 3
                                          :user-id 1400000000
                                          :user-input "100"}
                                       2 {:id 2
                                          :type :personal
                                          :name "Bob"
                                          :created 426301230
                                          :msg-id 4
                                          :user-id 1200000000}
                                       3 {:id 3
                                          :type :personal
                                          :name "Carl"
                                          :created 426320300
                                          :revoked 432500000
                                          :msg-id 5
                                          :user-id 2000000000}}
                            :group {4 {:id 4
                                       :type :group
                                       :name "Alice & Bob"
                                       :created 426307670
                                       :revoked 432500000
                                       :members [1 2]}}}
                 :expenses {:items [{:code "food"
                                     :desc "foodstuffs & manufactured goods"}
                                    {:code "out"
                                     :desc "cafes and coffee (eating out)"}
                                    {:code "gas"
                                     :desc "gasoline & car expenses"}]
                            ;; TODO: Implement smart sorting of items.
                            :popularity {"food" 5
                                         "out" 2
                                         "gas" 1}}
                 :data-store {:type :google-sheets
                              :url "..."
                              :api-key "..."}

                 ;; precomputed values
                 :user-account-mapping {1400000000 1
                                        1200000000 2
                                        2000000000 3}}

     ;; supergroup chat-id -> group chat-id (special case for 'admin' bots)
     -1001000000000 -560000000})

;; TODO: Use somewhere or get rid of it.
(defonce ^:private *bot-user (atom nil))


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
(def op-succeed {:ok true})

(defmacro ignore
  [msg & args]
  `(do
     (log/debugf (str "Ignored: " ~msg) ~@args)
     op-succeed))


;; MESSAGE TEMPLATES & CALLBACK DATA

;; TODO: Can be made namespaced keywords?
(def cd-accounts "<accounts>")
(def cd-expense-items "<expense_items>")
(def cd-data-store "<data_store>")
(def cd-expense-item-prefix "ei::")
(def cd-group-id-prefix "gr::")
(def cd-account-prefix "acc::")

;; TODO: Make messages texts localizable:
;;       - take the ':language_code' of the chat initiator (no personal settings)
;;       - externalize texts, keep only their keys (to get them via 'l10n')
(def introduction-msg
  {:type :text
   :text "Привет, народ! Я — бот-бухгалтер. И я призван помочь вам с учётом ваших общих расходов.\n
Для того, чтобы начать работу, просто ответьте на следующее сообщение.\n
Также меня можно настроить, чтобы:
- учитывались счета (не только личные, но и групповые)
- учитывались статьи расходов (подходящие по смыслу и удобные вам)
- данные отправлялись в ваше хранилище (пока что я храню их только у себя)"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "счета" :callback_data cd-accounts)
                                 (tg-api/build-inline-kbd-btn "статьи" :callback_data cd-expense-items)
                                 (tg-api/build-inline-kbd-btn "хранилище" :callback_data cd-data-store)]])})})

(def personal-account-name-msg
  {:type :text
   :text "Как будет называться ваш личный счёт?"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup :force-reply)})})

(defn get-personal-accounts-left-msg
  [count]
  {:type :text
   :text (format "Ожидаем остальных... Осталось %s." count)})

(def bot-readiness-msg
  {:type :text
   :text "Я готов к ведению учёта. Давайте же начнём!"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Перейти в чат для ввода расходов"
                                                              ;; TODO: Make this bot name configurable.
                                                              :url (str "https://t.me/gen_exp_acc_bot"))]])})})

(defn get-private-introduction-msg
  [first-name]
  {:type :text
   :text (str "Привет, " first-name "! Чтобы добавить новый расход просто напиши мне сумму.")})

(defn build-select-items-options
  [items name-extr-fn key-extr-fn val-extr-fn]
  (let [select-items (for [item items]
                       [(tg-api/build-inline-kbd-btn (name-extr-fn item)
                                                     (key-extr-fn item)
                                                     (val-extr-fn item))])]
    (tg-api/build-message-options
      {:reply-markup (tg-api/build-reply-markup :inline-keyboard (vec select-items))})))

(defn build-groups-options
  [groups]
  (build-select-items-options groups
                              :title
                              (constantly :callback_data)
                              #(str cd-group-id-prefix (:id %))))

(defn get-group-selection-msg
  [groups]
  (assert (seq groups))
  {:type :text
   :text "Выберите, к чему отнести расход:"
   :options (build-groups-options groups)})

(defn build-expense-items-options
  [expense-items]
  (build-select-items-options expense-items
                              :desc
                              (constantly :callback_data)
                              #(str cd-expense-item-prefix (:code %))))

(defn get-expense-item-selection-msg
  [expense-items]
  (assert (seq expense-items))
  {:type :text
   :text "Выберите статью расходов:"
   :options (build-expense-items-options expense-items)})

(defn get-expense-manual-description-msg
  [user-name user-id]
  {:type :text
   :text (str "[" user-name "](tg://user?id=" user-id "), опишите расход в двух словах:")
   :options (tg-api/build-message-options
              {:parse-mode "MarkdownV2"
               :reply-markup (tg-api/build-reply-markup :force-reply {:selective true})})})

(defn build-accounts-options
  [accounts]
  (build-select-items-options accounts
                              :name
                              (constantly :callback_data)
                              #(str cd-account-prefix (name (:type %)) "-" (:id %))))

(defn get-account-selection-msg
  [accounts]
  (assert (seq accounts))
  {:type :text
   :text "Выберите тех, кто понёс расход:"
   :options (build-accounts-options accounts)})

(defn get-group-expense-msg
  [input account expense-item]
  {:type :text
   :text (str/join " " [input account expense-item])})


;; AUXILIARY FUNCTIONS

(defn setup-new-group-chat!
  [chat-id]
  (if-not (contains? @*bot-data chat-id) ;; petty RC
    (swap! *bot-data assoc chat-id {:state :initial})))

(defn get-real-chat-id
  "Built-in insurance in case of 'supergroup' chat."
  [chat-id]
  (let [value (get-in @*bot-data [chat-id])]
    (if (int? value) (get-real-chat-id value) chat-id)))

(defn get-chat-data
  [chat-id]
  (let [real-chat-id (get-real-chat-id chat-id)]
    (get-in @*bot-data [real-chat-id])))

(defn set-chat-data!
  [chat-id [key & ks] value]
  (let [real-chat-id (get-real-chat-id chat-id)
        full-path (concat [real-chat-id key] ks)]
    (if (nil? value)
      (swap! *bot-data update-in (butlast full-path) dissoc (last full-path))
      (swap! *bot-data assoc-in full-path value))))

(defn get-bot-msg-id
  [chat-id msg-key]
  (get-in (get-chat-data chat-id) [:bot-messages msg-key]))

(defn set-bot-msg-id!
  [chat-id msg-key msg-id]
  (set-chat-data! chat-id [:bot-messages msg-key] msg-id))

;; TODO: Remove duplicates fn (see 'get-group-chat-state').
(defn get-private-chat-state
  [user-id]
  (get (get-chat-data user-id) :state))

(defn drop-private-chat-state!
  [user-id]
  (let [chat-data (get-chat-data user-id)]
    (swap! *bot-data assoc user-id (-> (select-keys chat-data [:groups])
                                       (assoc :state :input)))))

;; TODO: Get rid of Race Condition when this fn is called.
(defn get-group-chat-state
  [chat-id]
  (get (get-chat-data chat-id) :state))

;; TODO: Re-write with State Machine.
(defn change-group-chat-state!
  [chat-id new-state]
  (let [curr-state (get-group-chat-state chat-id)
        possible-new-states (case curr-state
                              :initial #{:ready}
                              :ready #{:initial})]
    (if (contains? possible-new-states new-state)
      (set-chat-data! chat-id [:state] new-state))))

(defn- build-personal-account
  [id name created msg-id user-id]
  {:id id
   :type :personal
   :name name
   :created created
   :msg-id msg-id
   :user-id user-id})

;; TODO: Add it only for chats with 2+ users. Affects account selection.
(defn- build-general-account
  [id created members]
  {:id id
   :type :general
   :name "общие" ;; TODO: Proper l10n.
   :created created
   :members members})

(defn create-personal-account!
  [chat-id chat-title user-id acc-name created-dt first-msg-id]
  (if (nil? (get-in (get-chat-data chat-id) [:user-account-mapping user-id])) ;; petty RC
    (swap! *bot-data
           (fn [bot-data]
             (let [pers-acc-count (count (get-in bot-data [chat-id :accounts :personal]))
                   new-pers-acc-id (inc pers-acc-count)
                   new-pers-acc (build-personal-account
                                  new-pers-acc-id acc-name created-dt first-msg-id user-id)
                   real-chat-id (get-real-chat-id chat-id)

                   upd-with-personal-acc-fn
                   (fn [bot-data]
                     (-> bot-data
                         (assoc-in [real-chat-id :accounts :personal new-pers-acc-id] new-pers-acc)
                         (assoc-in [real-chat-id :user-account-mapping user-id] new-pers-acc-id)))

                   upd-general-acc-fn
                   (fn [bot-data]
                     (if (zero? pers-acc-count)
                       (assoc-in bot-data [real-chat-id :accounts :general 0]
                                 (build-general-account 0 created-dt [new-pers-acc-id]))
                       ;; TODO: A bit more complicated. The 'general' account can have multiple versions.
                       (update-in bot-data [chat-id :accounts :general 0 :members] conj new-pers-acc-id)))

                   upd-private-chat-groups-fn
                   (fn [bot-data]
                     (let [upd-fn (fn [old-val & new-vals]
                                    (if (nil? old-val)
                                      (vec new-vals)
                                      (into old-val new-vals)))
                           new-group {:id chat-id
                                      :title chat-title}]
                       (-> bot-data
                           (update-in [user-id :groups] upd-fn new-group)
                           (assoc-in [user-id :state] :initial))))]
               (-> bot-data
                   upd-general-acc-fn
                   upd-with-personal-acc-fn
                   upd-private-chat-groups-fn))))))

(defn get-personal-accounts-uncreated-count
  [chat-id chat-members-count]
  (let [chat-data (get-chat-data chat-id)
        pers-acc-num (->> (get-in chat-data [:accounts :personal])
                          (filter (fn [[k _]] (some? k)))
                          count)]
    (- chat-members-count pers-acc-num 1)))

(defn get-group-expense-items
  [chat-id]
  (let [chat-data (get-chat-data chat-id)]
    ;; TODO: Sort them according popularity.
    (get-in chat-data [:expenses :items])))

(defn get-group-accounts
  [chat-id user-id]
  (let [chat-data (get-chat-data chat-id)
        general-acc (->> (get-in chat-data [:accounts :general])
                         (apply max-key key)
                         second)
        group-accs (->> (get-in chat-data [:accounts :group])
                        (map val))
        pers-acc-id (get-in chat-data [:user-account-mapping user-id])
        pers-accs (as-> (get-in (get-chat-data chat-id) [:accounts :personal]) $
                        (dissoc $ pers-acc-id)
                        (map val $))]
    ;; TODO: Sort them according popularity.
    (into [general-acc] (concat group-accs pers-accs))))


;; STATE TRANSITIONS

;; TODO: Re-write it as plain data with 'state-mutator', 'msg-generator', etc.
(defn process-state-transition
  [event {:keys [chat-id user-id] :as opts}]
  (let [chat-data (get-chat-data chat-id)]
    (case (:action event)
      [:ui-transition :input]
      (let [prev-state (get-private-chat-state user-id)]
        (drop-private-chat-state! user-id)
        (when (= prev-state :initial)
          {:message (get-private-introduction-msg (:first-name opts))}))

      [:ui-transition :group-selection]
      (do
        ;; TODO: Re-write with a dedicated State Machine.
        (set-chat-data! chat-id [:state] :select-group)
        (let [groups (:groups chat-data)]
          {:message (get-group-selection-msg groups)}))

      [:ui-transition :expense-item-selection]
      (do
        (set-chat-data! chat-id [:state] :select-expense-item)
        (let [group-chat-id (:group chat-data)
              expense-items (get-group-expense-items group-chat-id)]
          {:message (if (seq expense-items)
                      (get-expense-item-selection-msg expense-items)
                      (get-expense-manual-description-msg
                        (:first-name opts) user-id))}))

      [:ui-transition :accounts-selection]
      (do
        (set-chat-data! chat-id [:state] :select-account)
        (let [group-chat-id (:group chat-data)
              accounts (get-group-accounts group-chat-id user-id)]
          {:message (get-account-selection-msg accounts)})))))


;; Bot API

(m-hlr/defhandler
  handler

  ;; BOT COMMANDS

  ; Each bot has to handle '/start' and '/help' commands.
  (m-hlr/command-fn
    "start"
    (fn [{{user-id :id first-name :first_name :as _user} :from
          {chat-id :id type :type :as chat} :chat :as _message}]
      (log/debug "Conversation started in chat:" chat)
      (when (and (= type "private")
                 (= :initial (get-private-chat-state user-id)))
        (let [event {:action [:ui-transition :input]}
              result (process-state-transition event {:chat-id chat-id :user-id user-id
                                                      :first-name first-name})]
          (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
        op-succeed)))

  (m-hlr/command-fn
    "help"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Help requested in chat:" chat)
      ;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
      (tg-client/respond! {:type :text
                           :chat-id chat-id
                           :text "Help is on the way!"})
      op-succeed))

  (m-hlr/command-fn
    "calc"
    (fn [{{user-id :id :as _user} :from
          {chat-id :id type :type :as chat} :chat :as _message}]
      (log/debug "Calculator opened in chat:" chat)
      (when (and (= type "private")
                 (= :input (get-private-chat-state user-id)))
        (let [event {:action [:ui-transition :upd-user-input]}
              result (process-state-transition event {:chat-id chat-id :user-id user-id})]
          (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
        op-succeed)))

  ;; TODO: Implement the commands handling.

  ;; INLINE QUERIES

  (m-hlr/inline-fn
    (fn [inline-query]
      (log/debug "Inline query:" inline-query)))

  (m-hlr/inline-fn
    (fn [{inline-query-id :id _user :from query-str :query _offset :offset :as _inline-query}]
      (ignore "inline query id=%s, query=%s" inline-query-id query-str)))

  ;; CALLBACK QUERIES

  (m-hlr/callback-fn
    (fn [callback-query]
      (log/debug "Callback query:" callback-query)))

  ;; TODO: Implement chat-wide settings (accounts, expense items, data store).
  ;; #{cd-accounts cd-expense-items cd-data-store} ...

  (m-hlr/callback-fn
    (fn [{{user-id :id first-name :first_name :as _user} :from
          {{chat-id :id type :type} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (= type "private")
                 (= :select-group (get-private-chat-state user-id))
                 (str/starts-with? callback-btn-data cd-group-id-prefix))
        (let [group-id-str (str/replace-first callback-btn-data cd-group-id-prefix "")]
          (set-chat-data! chat-id [:group] (nums/parse-int group-id-str))
          ;; TODO: This pattern '(let [callback ... result ...] (...)' is repeated quite often.
          (let [event {:action [:ui-transition :expense-item-selection]}
                result (process-state-transition event {:chat-id chat-id :user-id user-id
                                                        :first-name first-name})]
            (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id type :type} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (= type "private")
                 (= :select-expense-item (get-private-chat-state user-id))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (set-chat-data! chat-id [:expense-item] expense-item)
          (let [event {:action [:ui-transition :accounts-selection]}
                result (process-state-transition event {:chat-id chat-id :user-id user-id})]
            (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id type :type} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (= type "private")
                 (= :select-account (get-private-chat-state user-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [account (str/replace-first callback-btn-data cd-account-prefix "")]
          (let [chat-data (get-chat-data chat-id)
                group-id (:group chat-data)
                group-chat-data (get-chat-data group-id)
                account-path-str (str/split account #"-")
                account-path (conj [:accounts]
                                   (keyword (nth account-path-str 0))
                                   (.intValue (biginteger (nth account-path-str 1))))
                account-name (:name (get-in group-chat-data account-path))
                msg (get-group-expense-msg (:input chat-data)
                                           account-name
                                           (:expense-item chat-data))]
            (tg-client/respond! (assoc msg :chat-id group-id)))
          (let [event {:action [:ui-transition :input]}]
            (process-state-transition event {:chat-id chat-id :user-id user-id}))
          op-succeed))))

  ;; TODO: Implement the callback queries handling.

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-instance :chat_instance callback-btn-data :data :as _callback-query}]
      (ignore "callback query id=%s, data=%s" callback-query-id callback-btn-data)))

  ;; CHAT MEMBER STATUS UPDATES

  (tg-client/bot-chat-member-status-fn
    (fn [{{chat-id :id _type :type _title :title _username :username :as chat} :chat
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          date :date
          {_old-user :user old-status :status :as _old-chat-member} :old_chat_member
          {_new-user :user new-status :status :as new-chat-member} :new_chat_member
          :as _my_chat_member}]
      (log/debug "Bot chat member status updated in:" chat)
      (if (and (some? new-chat-member) (= "member" new-status) (= "left" old-status))
        (do
          (setup-new-group-chat! chat-id)
          ;; TODO: This pattern '(let [tg-response ...] (->> tg-response ...)' is repeated quite often.
          (let [tg-response (tg-client/respond! (assoc introduction-msg :chat-id chat-id))]
            (->> tg-response :result :message_id (set-bot-msg-id! chat-id :intro-msg-id)))
          (let [tg-response (tg-client/respond! (assoc personal-account-name-msg :chat-id chat-id))]
            (->> tg-response :result :message_id (set-bot-msg-id! chat-id :name-request-msg-id)))
          op-succeed)
        (ignore "bot chat member status update dated %s in chat=%s" date chat-id))))

  (tg-client/chat-member-status-fn
    (fn [{{chat-id :id _type :type _title :title _username :username :as chat} :chat
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          date :date
          {_old-user :user _old-status :status :as _old-chat-member} :old_chat_member
          {_new-user :user _new-status :status :as _new-chat-member} :new_chat_member
          :as _chat_member}]
      (log/debug "Chat member status updated in:" chat)
      (ignore "chat member status update dated %s in chat=%s" date chat-id)))

  ;; PLAIN MESSAGES

  (m-hlr/message-fn
    (fn [{msg-id :message_id group-chat-created :group_chat_created :as _message}]
      (when (some? group-chat-created)
        (ignore "message id=%s" msg-id))))

  (m-hlr/message-fn
    (fn [{msg-id :message_id date :date text :text
          {user-id :id} :from
          {chat-id :id chat-title :title} :chat
          {original-msg-id :message_id :as original-msg} :reply_to_message ;; for replies
          :as _message}]
      ;; TODO: Re-think this condition.
      (when (and (some? original-msg)
                 (= original-msg-id (get-bot-msg-id chat-id :name-request-msg-id)))
        (create-personal-account! chat-id chat-title user-id text date msg-id)
        (let [chat-members-count (tg-client/get-chat-members-count chat-id)
              uncreated-count (get-personal-accounts-uncreated-count chat-id chat-members-count)]
          (if (zero? uncreated-count)
            (do
              (change-group-chat-state! chat-id :ready)
              (tg-client/respond! (assoc bot-readiness-msg :chat-id chat-id)))
            (tg-client/respond! (assoc (get-personal-accounts-left-msg uncreated-count) :chat-id chat-id))))
        op-succeed)))

  ;; TODO: Implement scenario for migration from a 'group' chat to a 'supergroup' chat (catch ':migrate_to_chat_id').

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id first-name :first_name} :from
          {chat-id :id type :type} :chat
          :as _message}]
      (when (and (= type "private")
                 (= :input (get-private-chat-state user-id)))
        (let [input (nums/parse-number text)]
          (when (number? input)
            (log/debug "User input:" input)
            (set-chat-data! chat-id [:input] input)
            (let [groups (:groups (get-chat-data chat-id))
                  result (if (> (count groups) 1)
                           (process-state-transition {:action [:ui-transition :group-selection]}
                                                     {:chat-id chat-id :user-id user-id})
                           (do
                             (set-chat-data! chat-id [:group] (:id (first groups)))
                             (process-state-transition {:action [:ui-transition :expense-item-selection]}
                                                       {:chat-id chat-id :user-id user-id
                                                        :first-name first-name})))]
              (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
            op-succeed)))))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id} :from
          {chat-id :id type :type} :chat
          :as _message}]
      (when (and (= type "private")
                 (= :select-expense-item (get-private-chat-state user-id)))
        (log/debug "Expense description:" text)
        (set-chat-data! chat-id [:expense-item] text)
        (let [event {:action [:ui-transition :accounts-selection]}
              result (process-state-transition event {:chat-id chat-id :user-id user-id})]
          (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
        op-succeed)))

  ;; TODO: Implement the messages handling.

  ; A "match-all catch-through" case.
  (m-hlr/message-fn
    (fn [{msg-id :message_id _date :date _text :text
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          {_chat-id :id _type :type _title :title _username :username :as chat} :chat
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

(defn init!
  []
  (let [bot-user (get (tg-client/get-me) :result)]
    (log/debug "Identified myself:" bot-user)
    (reset! *bot-user bot-user)))
