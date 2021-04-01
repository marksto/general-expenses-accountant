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
                 :members-count 3

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
                                       :members [1 2]}}
                            :last-id 5}
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

(defonce ^:private *bot-user (atom nil))

(def min-members-for-general-acc
  "The number of users in a group chat (including the bot)
   required to create a general account."
  3)


;; MESSAGE TEMPLATES & CALLBACK DATA

(def cd-accounts "<accounts>")
(def cd-expense-items "<expense_items>")
(def cd-data-store "<data_store>")
(def cd-expense-item-prefix "ei::")
(def cd-group-chat-prefix "gc::")
(def cd-account-prefix "ac::")

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

(defn get-bot-readiness-msg
  [bot-username]
  {:type :text
   :text "Я готов к ведению учёта. Давайте же начнём!"
   :options (tg-api/build-message-options
              {:reply-markup (tg-api/build-reply-markup
                               :inline-keyboard
                               [[(tg-api/build-inline-kbd-btn "Перейти в чат для ввода расходов"
                                                              :url (str "https://t.me/" bot-username))]])})})

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
                              #(str cd-group-chat-prefix (:id %))))

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
  [chat-id chat-members-count]
  (if-not (contains? @*bot-data chat-id) ;; petty RC
    (swap! *bot-data assoc chat-id {:state :initial
                                    :members-count chat-members-count
                                    :accounts {:last-id -1}})))

(defn setup-new-private-chat!
  [chat-id]
  (swap! *bot-data assoc chat-id {:state :input
                                  :groups []}))

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

(defn get-chat-state
  "Returns the state of the given chat.
   NB: Be aware that calling that function, e.g. during a state change,
       can cause a race condition (RC) and result in an obsolete value."
  [chat-id]
  (get (get-chat-data chat-id) :state))

(defn- change-chat-state!
  [chat-states chat-id new-state]
  (swap! *bot-data
         (fn [bot-data]
           (let [curr-state (get-chat-state chat-id)
                 possible-new-states (or (-> chat-states curr-state :to)
                                         (-> chat-states curr-state))]
             (if (contains? possible-new-states new-state)
               (let [state-init-fn (-> chat-states new-state :init-fn)]
                 (as-> bot-data $
                       (if (some? state-init-fn)
                         (update-in $ [chat-id] state-init-fn)
                         $)
                       (assoc-in $ [chat-id :state] new-state)))
               bot-data)))))

(defn get-accounts-next-id
  [chat-id]
  (-> (get-chat-data chat-id) :accounts :last-id inc))

(defn- build-personal-account
  [id name created msg-id user-id]
  {:id id
   :type :personal
   :name name
   :created created
   :msg-id msg-id
   :user-id user-id})

(defn- build-general-account
  [id created members]
  {:id id
   :type :general
   :name "общие" ;; TODO: Proper l10n.
   :created created
   :members members})

(defn create-general-account!
  [chat-id created-dt]
  (swap! *bot-data
         (fn [bot-data]
           (let [real-chat-id (get-real-chat-id chat-id)
                 next-id (get-accounts-next-id real-chat-id)
                 general-acc (build-general-account next-id created-dt [])]
             (-> bot-data
                 (assoc-in [real-chat-id :accounts :last-id] next-id)
                 (assoc-in [real-chat-id :accounts :general next-id] general-acc))))))

(defn update-general-account-members
  [chat-data new-pers-acc-id]
  ;; TODO: Should have a bit more complicated logic at some point.
  ;;       The 'general' account must have multiple versions that
  ;;       are created when the 'pers-acc-count' changes.
  (let [curr-general-acc-id 0
        existing-pers-accs (->> (get-in chat-data [:accounts :personal])
                                (map (comp :id val)))]
    (assoc-in chat-data
              [:accounts :general curr-general-acc-id :members]
              (conj existing-pers-accs new-pers-acc-id))))

(defn create-personal-account!
  [chat-id chat-title user-id acc-name created-dt first-msg-id]
  (if (nil? (get-in (get-chat-data chat-id) [:user-account-mapping user-id])) ;; petty RC
    (swap! *bot-data
           (fn [bot-data]
             (let [real-chat-id (get-real-chat-id chat-id)
                   next-id (get-accounts-next-id real-chat-id)

                   upd-accounts-next-id-fn
                   (fn [bot-data]
                     (assoc-in bot-data [real-chat-id :accounts :last-id] next-id))

                   upd-with-personal-acc-fn
                   (fn [bot-data]
                     (let [new-pers-acc (build-personal-account
                                          next-id acc-name created-dt first-msg-id user-id)]
                       (-> bot-data
                           (assoc-in [real-chat-id :accounts :personal next-id] new-pers-acc)
                           (assoc-in [real-chat-id :user-account-mapping user-id] next-id))))

                   upd-general-acc-members-fn
                   (fn [bot-data]
                     (let [chat-data (get-chat-data real-chat-id)]
                       (if-not (empty? (-> chat-data :accounts :general))
                         (assoc bot-data
                           real-chat-id (update-general-account-members chat-data next-id))
                         bot-data)))

                   upd-private-chat-groups-fn
                   (fn [bot-data]
                     (let [new-group {;; TODO: Check if it works in case of a 'supergroup'.
                                      :id chat-id
                                      ;; TODO: Should be updated when the group title is changed.
                                      :title chat-title}]
                       (update-in bot-data [user-id :groups] conj new-group)))]
               (-> bot-data
                   upd-accounts-next-id-fn
                   upd-with-personal-acc-fn
                   upd-general-acc-members-fn
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
  (let [chat-data (get-chat-data chat-id)]
    (if (empty? (-> chat-data :accounts :general))
      (let [pers-accs (as-> (get-in chat-data [:accounts :personal]) $
                            (map val $))]
        (assert (= (count pers-accs) 1))
        pers-accs)
      (let [general-acc (->> (get-in chat-data [:accounts :general])
                             (apply max-key key)
                             second)
            group-accs (->> (get-in chat-data [:accounts :group])
                            (map val))
            pers-acc-id (get-in chat-data [:user-account-mapping user-id])
            pers-accs (as-> (get-in chat-data [:accounts :personal]) $
                            (dissoc $ pers-acc-id)
                            (map val $))]
        ;; TODO: Sort them according popularity.
        (into [general-acc] (concat group-accs pers-accs))))))

(defn- to-account-name
  [callback-btn-data chat-data]
  (let [account (str/replace-first callback-btn-data cd-account-prefix "")
        account-path-str (str/split account #"-")
        account-path (conj [:accounts]
                           (keyword (nth account-path-str 0))
                           (.intValue (biginteger (nth account-path-str 1))))]
    (:name (get-in chat-data account-path))))

(defn- is-reply-to?
  [chat-id msg-key {msg-id :message_id :as msg}]
  (and (some? msg)
       (= msg-id (get-bot-msg-id chat-id msg-key))))


;; STATE TRANSITIONS

;; TODO: Re-write with State Machines.

(def ^:private private-chat-states
  {:input {:to #{:select-group :select-expense-item :select-account :input}
           :init-fn (fn [chat-data]
                      (select-keys chat-data [:groups]))}
   :select-group #{:select-expense-item :select-account :input}
   :select-expense-item #{:select-account :input}
   :select-account #{:input}})

(defn change-private-chat-state!
  [chat-id new-state]
  (change-chat-state! private-chat-states chat-id new-state))

(def ^:private group-chat-states
  {:initial #{:waiting :ready}
   :waiting #{:waiting :ready}
   :ready #{:initial :waiting}})

(defn change-group-chat-state!
  [chat-id new-state]
  (change-chat-state! group-chat-states chat-id new-state))

;; TODO: Re-write it as plain data with 'state-mutator', 'msg-generator', etc.
(defn handle-state-transition
  [event {:keys [chat-id] :as opts}]
  (case (:transition event)
    [:group :waiting-for-user]
    (do
      (change-group-chat-state! chat-id :waiting)
      {:message (get-personal-accounts-left-msg (:uncreated-count opts))})

    [:group :ready]
    (do
      (change-group-chat-state! chat-id :ready)
      {:message (get-bot-readiness-msg (:bot-username opts))})


    [:private :input]
    (do
      (change-private-chat-state! chat-id :input)
      {:message (get-private-introduction-msg (:first-name opts))})

    [:private :group-selection]
    (do
      (change-private-chat-state! chat-id :select-group)
      {:message (get-group-selection-msg (:groups opts))})

    [:private :expense-item-selection]
    (do
      (change-private-chat-state! chat-id :select-expense-item)
      (let [expense-items (:expense-items opts)]
        {:message (if (seq expense-items)
                    (get-expense-item-selection-msg expense-items)
                    (get-expense-manual-description-msg
                      (:first-name opts) (:user-id opts)))}))

    [:private :accounts-selection]
    (do
      (change-private-chat-state! chat-id :select-account)
      {:message (get-account-selection-msg (:accounts opts))})))


;; RECIPROCAL ACTIONS

(defn respond-attentively!
  [response tg-response-handler-fn]
  (let [tg-response (tg-client/respond! response)]
    (if (:ok tg-response)
      (tg-response-handler-fn (:result tg-response)))))


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


;; Bot API

(m-hlr/defhandler
  handler

  ;; BOT COMMANDS

  ; Each bot has to handle '/start' and '/help' commands.
  (m-hlr/command-fn
    "start"
    (fn [{{first-name :first_name :as _user} :from
          {chat-id :id type :type :as chat} :chat :as _message}]
      (log/debug "Conversation started in chat:" chat)
      (when (= type "private")
        (let [result (handle-state-transition {:transition [:private :input]}
                                              {:chat-id chat-id
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
    (fn [{{chat-id :id type :type :as chat} :chat :as _message}]
      (log/debug "Calculator opened in chat:" chat)
      (when (and (= type "private")
                 (= :input (get-chat-state chat-id)))
        (let [result (handle-state-transition {:transition [:private :upd-user-input]}
                                              {:chat-id chat-id})]
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
                 (= :select-group (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-group-chat-prefix))
        (let [group-chat-id-str (str/replace-first callback-btn-data cd-group-chat-prefix "")
              group-chat-id (nums/parse-int group-chat-id-str)]
          (set-chat-data! chat-id [:group] group-chat-id)
          ;; TODO: This pattern '(let [result (handle-state-transition ...)] (respond! ...)'
          ;;       is repeated quite often. It should be automation with an intermediary fn.
          (let [expense-items (get-group-expense-items group-chat-id)
                result (handle-state-transition {:transition [:private :expense-item-selection]}
                                                {:chat-id chat-id :user-id user-id
                                                 :expense-items expense-items
                                                 :first-name first-name})]
            (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id type :type} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (= type "private")
                 (= :select-expense-item (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (set-chat-data! chat-id [:expense-item] expense-item)
          (let [group-chat-id (:group (get-chat-data chat-id))
                accounts (get-group-accounts group-chat-id user-id)
                result (handle-state-transition {:transition [:private :accounts-selection]}
                                                {:chat-id chat-id
                                                 :accounts accounts})]
            (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
          op-succeed))))

  (m-hlr/callback-fn
    (fn [{{{chat-id :id type :type} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (= type "private")
                 (= :select-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              group-chat-id (:group chat-data)
              group-chat-data (get-chat-data group-chat-id)
              account-name (to-account-name callback-btn-data group-chat-data)
              group-notification-msg (get-group-expense-msg (:input chat-data)
                                                            account-name
                                                            (:expense-item chat-data))]
          (tg-client/respond! (assoc group-notification-msg :chat-id group-chat-id)))
        ;; TODO: Write some success confirmation message to the private chat.
        (handle-state-transition {:transition [:private :input]}
                                 {:chat-id chat-id})
        op-succeed)))

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
          (let [chat-members-count (tg-client/get-chat-members-count chat-id)]
            (setup-new-group-chat! chat-id chat-members-count)
            (when (>= chat-members-count min-members-for-general-acc)
              (create-general-account! chat-id date)))

          (respond-attentively! (assoc introduction-msg :chat-id chat-id)
                                #(->> % :message_id (set-bot-msg-id! chat-id :intro-msg-id)))

          (respond-attentively! (assoc personal-account-name-msg :chat-id chat-id)
                                #(->> % :message_id (set-bot-msg-id! chat-id :name-request-msg-id)))

          (handle-state-transition {:transition [:group :waiting-for-user]}
                                   {:chat-id chat-id})
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
      ;; TODO: Inc-/decrement the ':members-count' in this group.
      (ignore "chat member status update dated %s in chat=%s" date chat-id)))

  ;; PLAIN MESSAGES

  (m-hlr/message-fn
    (fn [{msg-id :message_id group-chat-created :group_chat_created :as _message}]
      (when (some? group-chat-created)
        (ignore "message id=%s" msg-id))))

  (m-hlr/message-fn
    (fn [{msg-id :message_id date :date text :text
          {user-id :id :as _user} :from
          {chat-id :id type :type chat-title :title} :chat
          original-msg :reply_to_message
          :as _message}]
      (when (and (contains? #{"group" "supergroup"} type)
                 (= :waiting (get-chat-state chat-id))
                 (is-reply-to? chat-id :name-request-msg-id original-msg))
        (setup-new-private-chat! user-id)
        (create-personal-account! chat-id chat-title user-id text date msg-id)

        (let [chat-members-count (:members-count (get-chat-data chat-id))
              uncreated-count (get-personal-accounts-uncreated-count chat-id chat-members-count)
              event (if (zero? uncreated-count)
                      {:transition [:group :ready]}
                       ;:params {:bot-username (get @*bot-user :username)} ;; TODO: Re-write in this way?
                      {:transition [:group :waiting-for-user]})
              result (handle-state-transition event {:chat-id chat-id
                                                     :bot-username (get @*bot-user :username)
                                                     :uncreated-count uncreated-count})]
          (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
        op-succeed)))

  ;; TODO: Implement scenario for migration from a 'group' chat to a 'supergroup' chat (catch ':migrate_to_chat_id').

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id first-name :first_name :as _user} :from
          {chat-id :id type :type} :chat
          :as _message}]
      (when (and (= type "private")
                 (= :input (get-chat-state chat-id)))
        (let [input (nums/parse-number text)]
          (when (number? input)
            (log/debug "User input:" input)
            (set-chat-data! chat-id [:input] input)
            (let [groups (:groups (get-chat-data chat-id))
                  result (if (> (count groups) 1)
                           (handle-state-transition {:transition [:private :group-selection]}
                                                    {:chat-id chat-id :groups groups})
                           (let [group-chat-id (:id (first groups))
                                 expense-items (get-group-expense-items group-chat-id)]
                             (log/debug "Group chat auto-selected:" group-chat-id)
                             (set-chat-data! chat-id [:group] group-chat-id)
                             (handle-state-transition {:transition [:private :expense-item-selection]}
                                                      {:chat-id chat-id :user-id user-id
                                                       :expense-items expense-items
                                                       :first-name first-name})))]
              (tg-client/respond! (assoc (:message result) :chat-id chat-id)))
            op-succeed)))))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id :as _user} :from
          {chat-id :id type :type} :chat
          :as _message}]
      (when (and (= type "private")
                 (= :select-expense-item (get-chat-state chat-id)))
        (log/debug "Expense description:" text)
        (set-chat-data! chat-id [:expense-item] text)
        (let [group-chat-id (:group (get-chat-data chat-id))
              accounts (get-group-accounts group-chat-id user-id)
              result (handle-state-transition {:transition [:private :accounts-selection]}
                                              {:chat-id chat-id :accounts accounts})]
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
