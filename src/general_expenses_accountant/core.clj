(ns general-expenses-accountant.core
  (:require [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]))

;; State

;; TODO: Move to a dedicated 'db' ns.
(defonce ^:private *bot-data (atom {}))

#_(def sample-data
    ;; chat-id -> chat-specific settings
    {-560000000 {:state :initial

                 ;; to reply to (for newcomers intro)
                 :entry-msg-id 1

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
                                          :user-id 1400000000}
                                       2 {:id 2
                                          :type :personal
                                          :name "Bob"
                                          :created 426301230
                                          :user-id 1200000000}
                                       3 {:id 3
                                          :type :personal
                                          :name "Carl"
                                          :created 426320300
                                          :revoked 432500000
                                          :user-id 2000000000}}
                            :group {4 {:id 4
                                       :type :group
                                       :name "Alice & Bob"
                                       :created 426307670
                                       :revoked 432500000
                                       :members [1 2]}}}
                 :expense-items [{:code "food"
                                  :desc "foodstuffs & manufactured goods"}
                                 {:code "out"
                                  :decs "cafes and coffee (eating out)"}
                                 {:code "gas"
                                  :desc "gasoline & car expenses"}]
                 :data-store {:type :google-sheets
                              :url "..."
                              :api-key "..."}

                 ;; precomputed values
                 :user-account-mapping {1400000000 1
                                        1200000000 2
                                        2000000000 3}}})


;; Business Logic

(defn- respond!
  [{:keys [type chat-id text inline-query-id results callback-query-id show-alert options]
    :as response}]
  (try
    (let [token (config/get-prop :bot-api-token)
          tg-response
          (case type
            :text (m-api/send-text token chat-id options text)
            :inline (m-api/answer-inline token inline-query-id options results)
            :callback (m-api/answer-callback token callback-query-id text show-alert))]
      (log/debug "Telegram returned:" tg-response)
      tg-response)
    (catch Exception e
      (log/error "Failed to respond with:" response)
      (println e))))


;; API

(m-hlr/defhandler
  handler
  ; Each bot has to handle /start and /help commands.
  (m-hlr/command-fn
    "start"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Conversation started in chat:" chat)
      (respond! {:type :text
                 :chat-id chat-id
                 :text "Welcome!"})))

  (m-hlr/command-fn
    "help"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Help requested in chat:" chat)
      (respond! {:type :text
                 :chat-id chat-id
                 :text "Help is on the way!"})))

  ;; TODO: Implement the commands handling.

  (m-hlr/inline-fn
    (fn [{inline-query-id :id _user :from query-str :query _offset :offset :as inline-query}]
      (log/debug "Inline query:" inline-query)
      (respond! {:type :inline
                 :inline-query-id inline-query-id
                 :results []
                 :options {:next_offset "" ;; don't support pagination
                           :switch_pm_text "Let's talk privately"
                           :switch_pm_parameter query-str}})))

  (m-hlr/callback-fn
    (fn [{callback-query-id :id _user :from _msg :message _msg-id :inline_message_id
          _chat-id :chat_instance _callback-btn-data :data :as callback-query}]
      (log/debug "Callback query:" callback-query)
      (let [notification-text ""] ;; nothing will be shown to the user
        ;; TODO: Make Morse support 'url' parameter in 'answerCallbackQuery'.
        (respond! {:type :callback
                   :callback-query-id callback-query-id
                   :text notification-text}))))

  ;; TODO: Implement the messages handling.

  ; A "match-all catch-through" case.
  (m-hlr/message-fn
    (fn [{_msg-id :message_id _date :date _text :text
          {_user-id :id _first-name :first_name _last-name :last_name
           _username :username _is-bot :is_bot _lang :language_code :as _user} :from
          {chat-id :id _type :type _title :title _username :username :as chat} :chat
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
      (respond! {:type :text
                 :chat-id chat-id
                 :text "I didn't understand you."}))))

(defn bot-api
  [update]
  (log/debug "Received update:" update)
  (handler update))
