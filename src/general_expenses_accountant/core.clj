(ns general-expenses-accountant.core
  "Bot API and business logic (core functionality)"
  (:require [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.nums :as nums]
            [general-expenses-accountant.tg-bot-api :as tg-api]
            [general-expenses-accountant.tg-client :as tg-client]
            [clojure.string :as str]))

;; STATE

;; TODO: Move to a dedicated 'db' ns.
(defonce ^:private *bot-data (atom {}))

#_(def sample-data
    ;; private chat-id -> user-specific
    {280000000 {:groups #{-560000000 -1001000000000}

                ;; for data input
                :state :input
                :amount 100
                :group -560000000
                :expense-item "food"
                ;; or
                :expense-desc "other"}}

    ;; group chat-id -> group-level settings
    {-560000000 {:state :initial
                 :title "Family expenses"
                 :members-count 3

                 ;; messages to delete/check replies to
                 :bot-messages {:intro-msg-id 1
                                :name-request-msg-id 2}

                 ;; configured by users
                 :accounts {:general {0 {:id 0
                                         :type :general
                                         :name "Common"
                                         :created 426300760
                                         :revoked 426320300
                                         :members #{1 2}}
                                      5 {:id 5
                                         :type :general
                                         :name "Common"
                                         :created 426320300
                                         :revoked 432500000
                                         :members #{1 2 3}}
                                      6 {:id 6
                                         :type :general
                                         :name "Common"
                                         :created 432500000
                                         :members #{1 2}}}
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
                                       :members #{1 2}}}
                            :last-id 6}
                 ;; TODO: Re-implement ':items' with another data structure.
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

(def ^:private min-members-for-general-account
  "The number of users in a group chat (including the bot)
   required to create a general account."
  3)

;; TODO: Implement transactions log. Each group chat is to keep a list of all inbound expenses (as data, with IDs).


;; MESSAGE TEMPLATES & CALLBACK DATA

(def ^:private cd-accounts "<accounts>")
(def ^:private cd-expense-items "<expense_items>")
(def ^:private cd-data-store "<data_store>")
(def ^:private cd-expense-item-prefix "ei::")
(def ^:private cd-group-chat-prefix "gc::")
(def ^:private cd-account-prefix "ac::")

;; TODO: Proper localization (with fn).
(def ^:private default-general-acc-name "общие")

;; TODO: Make messages texts localizable:
;;       - take the ':language_code' of the chat initiator (no personal settings)
;;       - externalize texts, keep only their keys (to get them via 'l10n')
(def ^:private introduction-msg
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

(def ^:private personal-account-name-msg
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

(defn- get-private-introduction-msg
  [first-name]
  {:type :text
   :text (str "Привет, " first-name "! Чтобы добавить новый расход просто напиши мне сумму.")})

(defn- get-added-to-new-group-msg
  [chat-title]
  {:type :text
   :text (str "Вас добавили в группу \"" chat-title "\".")})

(defn- build-select-items-options
  [items name-extr-fn key-extr-fn val-extr-fn]
  (let [select-items (for [item items]
                       [(tg-api/build-inline-kbd-btn (name-extr-fn item)
                                                     (key-extr-fn item)
                                                     (val-extr-fn item))])]
    (tg-api/build-message-options
      {:reply-markup (tg-api/build-reply-markup :inline-keyboard (vec select-items))})))

(defn- group-refs->options
  [group-refs]
  (build-select-items-options group-refs
                              :title
                              (constantly :callback_data)
                              #(str cd-group-chat-prefix (:id %))))

(defn- get-group-selection-msg
  [group-refs]
  (assert (seq group-refs))
  {:type :text
   :text "Выберите, к какой группе отнести расход:"
   :options (group-refs->options group-refs)})

(defn- expense-items->options
  [expense-items]
  (build-select-items-options expense-items
                              :desc
                              (constantly :callback_data)
                              #(str cd-expense-item-prefix (:code %))))

(defn- get-expense-item-selection-msg
  [expense-items]
  (assert (seq expense-items))
  {:type :text
   :text "Выберите статью расходов:"
   :options (expense-items->options expense-items)})

(defn- get-expense-manual-description-msg
  [user-name user-id]
  {:type :text
   :text (str "[" user-name "](tg://user?id=" user-id "), опишите расход в двух словах:")
   :options (tg-api/build-message-options
              {:parse-mode "MarkdownV2"
               :reply-markup (tg-api/build-reply-markup :force-reply {:selective true})})})

(defn- accounts->options
  [accounts]
  (build-select-items-options accounts
                              :name
                              (constantly :callback_data)
                              #(str cd-account-prefix (name (:type %)) "-" (:id %))))

(defn- get-account-selection-msg
  [accounts]
  (assert (seq accounts))
  {:type :text
   :text "Выберите тех, кто понёс расход:"
   :options (accounts->options accounts)})

(defn- get-group-expense-msg
  [payer-acc-name amount debtor-acc-name expense-details]
  {:type :text
   :text (str "*" payer-acc-name "*\n"
              (str/join " " [(str (format "%.2f" amount) "₽")
                             "/" debtor-acc-name
                             "/" expense-details]))
   :options (tg-api/build-message-options
              {:parse-mode "MarkdownV2"})})

(def ^:private expense-added-successfully-msg
  {:type :text
   :text "Запись успешно внесена в ваш гроссбух."})


;; AUXILIARY FUNCTIONS

(defn- setup-new-chat!
  [chat-id init-chat-data]
  (if-not (contains? @*bot-data chat-id) ;; petty RC
    (swap! *bot-data assoc chat-id init-chat-data)))

(defn- setup-new-group-chat!
  [chat-id chat-title chat-members-count]
  (setup-new-chat! chat-id {:state :initial
                            :title chat-title
                            :members-count chat-members-count
                            :accounts {:last-id -1}}))

(defn- setup-new-private-chat!
  [chat-id group-chat-id]
  (setup-new-chat! chat-id {:state :initial
                            :groups #{group-chat-id}}))

(defn- get-real-chat-id
  "Built-in insurance in case of 'supergroup' chat."
  [chat-id]
  (let [value (get-in @*bot-data [chat-id])]
    (if (int? value) (get-real-chat-id value) chat-id)))

(defn- get-chat-data
  [chat-id]
  (let [real-chat-id (get-real-chat-id chat-id)]
    (get-in @*bot-data [real-chat-id])))

(defn- set-chat-data!
  ([chat-id value]
   (if (nil? value)
     (swap! *bot-data dissoc chat-id)
     (swap! *bot-data assoc chat-id value)))
  ([chat-id [key & ks] value]
   (let [real-chat-id (get-real-chat-id chat-id)
         full-path (concat [real-chat-id key] ks)]
     (if (nil? value)
       (swap! *bot-data update-in (butlast full-path) dissoc (last full-path))
       (swap! *bot-data assoc-in full-path value)))))

(defn- get-chat-state
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

(defn- ->personal-account
  [id name created msg-id user-id]
  {:id id
   :type :personal
   :name name
   :created created
   :msg-id msg-id
   :user-id user-id})

(defn- ->general-account
  [id name created members]
  {:id id
   :type :general
   :name name
   :created created
   :members (set members)})

(defn- get-current-general-account
  [chat-data]
  (let [gen-accs (get-in chat-data [:accounts :general])]
    (if (some? gen-accs)
      (->> (get-in chat-data [:accounts :general])
           (apply max-key key)
           second))))

(defn- get-accounts-next-id
  [chat-id]
  (-> (get-chat-data chat-id) :accounts :last-id inc))

(defn- get-personal-account-ids
  [chat-data]
  (->> (get-in chat-data [:accounts :personal])
       (map (comp :id val))))

(defn- create-general-account!
  [chat-id created-dt]
  (swap! *bot-data
         (fn [bot-data]
           (let [real-chat-id (get-real-chat-id chat-id)
                 chat-data (get bot-data real-chat-id)
                 existing-gen-acc (get-current-general-account chat-data)
                 curr-gen-acc-id (:id existing-gen-acc)
                 next-id (get-accounts-next-id real-chat-id)
                 acc-name (if (nil? existing-gen-acc)
                            default-general-acc-name
                            (:name existing-gen-acc))
                 members (if (nil? existing-gen-acc)
                           (get-personal-account-ids chat-data)
                           (:members existing-gen-acc))
                 general-acc (->general-account next-id acc-name created-dt members)]
             (as-> bot-data $
                   (assoc-in $ [real-chat-id :accounts :last-id] next-id)
                   (if-not (nil? existing-gen-acc)
                     (assoc-in $ [real-chat-id :accounts :general curr-gen-acc-id :revoked] created-dt)
                     $)
                   (assoc-in $ [real-chat-id :accounts :general next-id] general-acc))))))

;; TODO: There have to be another version that removes an old member.
(defn- add-general-account-member
  [chat-data new-pers-acc-id]
  (let [curr-gen-acc-id (:id (get-current-general-account chat-data))]
    (update-in chat-data [:accounts :general curr-gen-acc-id :members] conj new-pers-acc-id)))

(defn- get-personal-account-id
  [chat-data user-id]
  (get-in chat-data [:user-account-mapping user-id]))

(defn- set-personal-account-id
  [chat-data user-id pers-acc-id]
  (assoc-in chat-data [:user-account-mapping user-id] pers-acc-id))

(defn- create-personal-account!
  [chat-id user-id acc-name created-dt first-msg-id]
  (if (nil? (get-personal-account-id (get-chat-data chat-id) user-id)) ;; petty RC
    (swap! *bot-data
           (fn [bot-data]
             (let [real-chat-id (get-real-chat-id chat-id)
                   next-id (get-accounts-next-id real-chat-id)

                   upd-accounts-next-id-fn
                   (fn [bot-data]
                     (assoc-in bot-data [real-chat-id :accounts :last-id] next-id))

                   upd-with-personal-acc-fn
                   (fn [bot-data]
                     (let [chat-data (get bot-data real-chat-id)
                           new-pers-acc (->personal-account
                                          next-id acc-name created-dt first-msg-id user-id)]
                       (assoc-in bot-data [real-chat-id]
                                 (-> chat-data
                                     (assoc-in [:accounts :personal next-id] new-pers-acc)
                                     (set-personal-account-id user-id next-id)))))

                   upd-general-acc-members-fn
                   (fn [bot-data]
                     (let [chat-data (get bot-data real-chat-id)]
                       (if-not (empty? (-> chat-data :accounts :general))
                         (assoc bot-data
                           real-chat-id (add-general-account-member chat-data next-id))
                         bot-data)))]
               (-> bot-data
                   upd-accounts-next-id-fn
                   upd-with-personal-acc-fn
                   upd-general-acc-members-fn))))))

(defn- update-personal-account!
  [chat-id user-id acc-name]
  (let [pers-acc-id (get-personal-account-id (get-chat-data chat-id) user-id)]
    (if (some? pers-acc-id) ;; petty RC
      (swap! *bot-data
             (fn [bot-data]
               (let [real-chat-id (get-real-chat-id chat-id)

                     upd-acc-name-fn
                     (fn [bot-data]
                       (assoc-in bot-data
                                 [real-chat-id :accounts :personal pers-acc-id :name]
                                 acc-name))]
                 (-> bot-data
                     upd-acc-name-fn)))))))

(defn- get-missing-personal-accounts
  "The number of missing personal accounts
   (which need to be created for the group to be ready)."
  [chat-id chat-members-count]
  (let [chat-data (get-chat-data chat-id)
        pers-acc-num (count (get-personal-account-ids chat-data))]
    (- chat-members-count pers-acc-num 1)))

(defn- get-group-expense-items
  [chat-id]
  (let [chat-data (get-chat-data chat-id)]
    ;; TODO: Sort them according popularity.
    (get-in chat-data [:expenses :items])))

(defn- get-group-accounts
  [chat-id user-id]
  (let [chat-data (get-chat-data chat-id)]
    (if (empty? (-> chat-data :accounts :general))
      (let [pers-accs (as-> (get-in chat-data [:accounts :personal]) $
                            (map val $))]
        (assert (= (count pers-accs) 1))
        pers-accs)
      (let [general-acc (get-current-general-account chat-data)
            group-accs (->> (get-in chat-data [:accounts :group])
                            (map val))
            user-pers-acc-id (get-personal-account-id chat-data user-id)
            pers-accs (as-> (get-in chat-data [:accounts :personal]) $
                            (dissoc $ user-pers-acc-id)
                            (map val $))]
        ;; TODO: Sort them according popularity.
        (into [general-acc] (concat group-accs pers-accs))))))

(defn- get-account-name
  [group-chat-data acc-type acc-id]
  (:name (get-in group-chat-data [:accounts acc-type acc-id])))

(defn- data->account-name
  [callback-btn-data group-chat-data]
  (let [account (str/replace-first callback-btn-data cd-account-prefix "")
        account-path (str/split account #"-")]
    (get-account-name group-chat-data
                      (keyword (nth account-path 0))
                      (.intValue (biginteger (nth account-path 1))))))

(defn- get-bot-msg-id
  [chat-id msg-key]
  (get-in (get-chat-data chat-id) [:bot-messages msg-key]))

(defn- set-bot-msg-id!
  [chat-id msg-key msg-id]
  (set-chat-data! chat-id [:bot-messages msg-key] msg-id))

(defn- is-reply-to-bot?
  [chat-id bot-msg-key message]
  (tg-api/is-reply-to? (get-bot-msg-id chat-id bot-msg-key) message))

(defn- update-private-chat-groups!
  ([chat-id new-group-chat-id]
   (let [real-chat-id (get-real-chat-id chat-id)] ;; petty RC
     (swap! *bot-data update-in [real-chat-id :groups] conj new-group-chat-id)))
  ([chat-id old-group-chat-id new-group-chat-id]
   (let [real-chat-id (get-real-chat-id chat-id)] ;; petty RC
     (swap! *bot-data update-in [real-chat-id :groups]
            (comp set (partial replace {old-group-chat-id new-group-chat-id}))))))

(defn- ->group-ref
  [group-chat-id]
  {:id group-chat-id
   :title (-> group-chat-id get-chat-data :title)})


;; STATES & STATE TRANSITIONS

;; TODO: Re-write with State Machines.
;; TODO: Switch to Event-Driven.

(def ^:private group-chat-states
  {:initial #{:waiting}
   :waiting #{:waiting :ready}
   :ready #{:waiting}})

(def ^:private private-chat-states
  {:initial #{:input}
   :input {:to #{:select-group :detail-expense :input}
           :init-fn (fn [chat-data]
                      (select-keys chat-data [:groups]))}
   :select-group #{:detail-expense :input}
   :detail-expense #{:select-account :input}
   :select-account #{:input}})

(defn- change-chat-state!*
  [chat-type chat-id new-state]
  (let [chat-states (case chat-type
                      :group group-chat-states
                      :private private-chat-states)]
    (change-chat-state! chat-states chat-id new-state)))

(def ^:private state-transitions
  {:group {:waiting-for-user {:to-state :waiting
                              :message-fn get-personal-accounts-left-msg
                              :message-params [:uncreated-count]}
           :ready {:to-state :ready
                   :message-fn get-bot-readiness-msg
                   :message-params [:bot-username]}}

   :private {:amount-input {:to-state :input
                            :message-fn get-private-introduction-msg
                            :message-params [:first-name]}
             :group-selection {:to-state :select-group
                               :message-fn get-group-selection-msg
                               :message-params [:group-refs]}
             :expense-item-selection {:to-state :detail-expense
                                      :message-fn get-expense-item-selection-msg
                                      :message-params [:expense-items]}
             :manual-expense-description {:to-state :detail-expense
                                          :message-fn get-expense-manual-description-msg
                                          :message-params [:first-name :user-id]}
             :accounts-selection {:to-state :select-account
                                  :message-fn get-account-selection-msg
                                  :message-params [:accounts]}
             :successful-input {:to-state :input
                                :message expense-added-successfully-msg}}})

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
    (if (:ok tg-response)
      (tg-response-handler-fn (:result tg-response)))))

(defn- proceed-and-respond-attentively!
  "Continues the course of transitions between states, sends a message in
   response to a user (or a group), and awaits for a feedback (Telegram's
   response) which is then processed by the provided handler function."
  [chat-id event tg-response-handler-fn]
  (let [result (handle-state-transition chat-id event)]
    (respond-attentively! (assoc (:message result) :chat-id chat-id)
                          tg-response-handler-fn)))



(defn- proceed-with-expense-details!
  [chat-id group-chat-id first-name user-id]
  (let [expense-items (get-group-expense-items group-chat-id)
        event (if (seq expense-items)
                {:transition [:private :expense-item-selection]
                 :params {:expense-items expense-items}}
                {:transition [:private :manual-expense-description]
                 :params {:first-name first-name :user-id user-id}})]
    (proceed-and-respond! chat-id event)))


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


;; BOT API

(m-hlr/defhandler
  handler

  ;; BOT COMMANDS

  ; Each bot has to handle '/start' and '/help' commands.
  (m-hlr/command-fn
    "start"
    (fn [{{first-name :first_name :as _user} :from
          {chat-id :id :as chat} :chat :as _message}]
      (log/debug "Conversation started in chat:" chat)
      (when (tg-api/is-private? chat)
        (proceed-and-respond! chat-id {:transition [:private :amount-input]
                                       :params {:first-name first-name}})
        op-succeed)))

  (m-hlr/command-fn
    "help"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Help requested in chat:" chat)
      ;; TODO: Implement proper '/help' message (w/ the list of commands, etc.).
      (respond! {:type :text
                 :chat-id chat-id
                 :text "Help is on the way!"})
      op-succeed))

  (m-hlr/command-fn
    "calc"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Calculator opened in chat:" chat)
      (when (and (tg-api/is-private? chat)
                 (= :input (get-chat-state chat-id)))
        (proceed-and-respond! chat-id {:transition [:private :upd-user-input]})
        op-succeed)))

  ;; TODO: Implement the commands handling (including '/cancel' for private chat).

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
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :select-group (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-group-chat-prefix))
        (let [group-chat-id-str (str/replace-first callback-btn-data cd-group-chat-prefix "")
              group-chat-id (nums/parse-int group-chat-id-str)]
          (set-chat-data! chat-id [:group] group-chat-id)
          (proceed-with-expense-details! chat-id group-chat-id first-name user-id))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :detail-expense (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-expense-item-prefix))
        (let [expense-item (str/replace-first callback-btn-data cd-expense-item-prefix "")]
          (set-chat-data! chat-id [:expense-item] expense-item)
          (let [group-chat-id (:group (get-chat-data chat-id))
                accounts (get-group-accounts group-chat-id user-id)]
            (proceed-and-respond! chat-id {:transition [:private :accounts-selection]
                                           :params {:accounts accounts}})))
        op-succeed)))

  (m-hlr/callback-fn
    (fn [{{user-id :id :as _user} :from
          {{chat-id :id :as chat} :chat :as _msg} :message
          callback-btn-data :data :as _callback-query}]
      (when (and (tg-api/is-private? chat)
                 (= :select-account (get-chat-state chat-id))
                 (str/starts-with? callback-btn-data cd-account-prefix))
        (let [chat-data (get-chat-data chat-id)
              group-chat-id (:group chat-data)
              group-chat-data (get-chat-data group-chat-id)
              payer-acc-id (get-personal-account-id group-chat-data user-id)
              payer-acc-name (get-account-name group-chat-data
                                               :personal payer-acc-id)
              debtor-acc-name (data->account-name callback-btn-data
                                                  group-chat-data)
              expense-details (or (:expense-item chat-data)
                                  (:expense-desc chat-data))
              group-notification-msg (get-group-expense-msg payer-acc-name
                                                            (:amount chat-data)
                                                            debtor-acc-name
                                                            expense-details)]
          (respond! (assoc group-notification-msg :chat-id group-chat-id)))

        (proceed-and-respond! chat-id {:transition [:private :successful-input]})
        op-succeed)))

  ;; TODO: Implement the callback queries handling.

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-instance :chat_instance callback-btn-data :data :as _callback-query}]
      (ignore "callback query id=%s, data=%s" callback-query-id callback-btn-data)))

  ;; CHAT MEMBER STATUS UPDATES

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
                chat-members-count (tg-client/get-chat-members-count token chat-id)]
            (setup-new-group-chat! chat-id chat-title chat-members-count)
            (when (>= chat-members-count min-members-for-general-account)
              (create-general-account! chat-id date)))

          (respond-attentively! (assoc introduction-msg :chat-id chat-id)
                                #(->> % :message_id (set-bot-msg-id! chat-id :intro-msg-id)))

          (respond-attentively! (assoc personal-account-name-msg :chat-id chat-id)
                                #(->> % :message_id (set-bot-msg-id! chat-id :name-request-msg-id)))

          (handle-state-transition chat-id {:transition [:group :waiting-for-user]})
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

  ;; PLAIN MESSAGES

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
        (let [group-chat-id (get-real-chat-id chat-id)]
          (if-not (setup-new-private-chat! user-id group-chat-id)
            (update-private-chat-groups! user-id group-chat-id)))

        (if (create-personal-account! chat-id user-id text date msg-id)
          (if (not= :initial (get-chat-state user-id))
            (respond! (assoc (get-added-to-new-group-msg chat-title) :chat-id user-id)))
          (update-personal-account! chat-id user-id text))

        (let [chat-members-count (:members-count (get-chat-data chat-id))
              uncreated-count (get-missing-personal-accounts chat-id chat-members-count)
              event (if (zero? uncreated-count)
                      {:transition [:group :ready]
                       :params {:bot-username (get @*bot-user :username)}}
                      {:transition [:group :waiting-for-user]
                       :params {:uncreated-count uncreated-count}})]
          (proceed-and-respond! chat-id event))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id :as chat} :chat
          new-chat-title :new_chat_title
          :as _message}]
      (when (and (tg-api/is-group? chat)
                 (some? new-chat-title))
        (log/debugf "Chat %s title was changed to '%s'" chat-id new-chat-title)
        (set-chat-data! chat-id [:title] new-chat-title)
        op-succeed)))

  (m-hlr/message-fn
    (fn [{{chat-id :id :as chat} :chat
          migrate-to-chat-id :migrate_to_chat_id
          :as _message}]
      (when (and (tg-api/is-group? chat)
                 (some? migrate-to-chat-id))
        (log/debugf "Group %s has been migrated to a supergroup %s" chat-id migrate-to-chat-id)
        (set-chat-data! migrate-to-chat-id chat-id)
        (let [group-users (-> (get-chat-data chat-id)
                              (get :user-account-mapping)
                              keys)]
          (doseq [user-id group-users]
            (update-private-chat-groups! user-id chat-id migrate-to-chat-id)))
        op-succeed)))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id first-name :first_name :as _user} :from
          {chat-id :id :as chat} :chat
          :as _message}]
      (when (and (tg-api/is-private? chat)
                 (= :input (get-chat-state chat-id)))
        (let [input (nums/parse-number text)]
          (when (number? input)
            (log/debug "User input:" input)
            (set-chat-data! chat-id [:amount] input)
            (let [groups (:groups (get-chat-data chat-id))]
              (if (> (count groups) 1)
                (let [group-refs (map ->group-ref groups)]
                  (proceed-and-respond! chat-id {:transition [:private :group-selection]
                                                 :params {:group-refs group-refs}}))
                (let [group-chat-id (first groups)]
                  (log/debug "Group chat auto-selected:" group-chat-id)
                  (set-chat-data! chat-id [:group] group-chat-id)
                  (proceed-with-expense-details! chat-id group-chat-id first-name user-id))))
            op-succeed)))))

  (m-hlr/message-fn
    (fn [{text :text
          {user-id :id :as _user} :from
          {chat-id :id :as chat} :chat
          :as _message}]
      (when (and (tg-api/is-private? chat)
                 (= :detail-expense (get-chat-state chat-id)))
        (log/debug "Expense description:" text)
        (set-chat-data! chat-id [:expense-desc] text)
        (let [group-chat-id (:group (get-chat-data chat-id))
              accounts (get-group-accounts group-chat-id user-id)]
          (proceed-and-respond! chat-id {:transition [:private :accounts-selection]
                                         :params {:accounts accounts}}))
        op-succeed)))

  ;; TODO: Implement the messages handling.

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

(defn init!
  []
  (let [token (config/get-prop :bot-api-token)
        bot-user (get (tg-client/get-me token) :result)]
    (log/debug "Identified myself:" bot-user)
    (reset! *bot-user bot-user)))
