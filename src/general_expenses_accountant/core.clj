(ns general-expenses-accountant.core
  (:require [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]))

;; State

;; TODO: Move to a dedicated 'db' ns.
(defonce ^:private bot-data (atom []))


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
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Unprocessed message in chat:" chat)
      (respond! {:type :text
                 :chat-id chat-id
                 :text "I didn't understand you."}))))

(defn bot-api
  [update]
  (log/debug "Received update:" update)
  (handler update))
