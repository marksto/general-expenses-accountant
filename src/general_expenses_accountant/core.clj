(ns general-expenses-accountant.core
  (:require [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]))

;; Helpers

(defn- get-bot-api-token
  []
  (config/get-prop :bot-api-token))

;; Business Logic

(defn respond!
  [{:keys [type chat-id text] :as with-what}]
  (try
    (let [token (get-bot-api-token)]
      ;; TODO: Implement other types of responses.
      (case type
        :text (m-api/send-text token chat-id text)))
    (catch Exception e
      (log/error "Failed to respond to chat" chat-id "with:" with-what)
      (println e))))

;; API

(m-hlr/defhandler
  bot-api
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

  ;; TODO: Re-map an old API call delegation logic here.

  ; A "match-all catch-through" case:
  (m-hlr/message-fn
    (fn [{{chat-id :id} :chat :as message}]
      (log/debug "Received message:" message)
      (respond! {:type :text
                 :chat-id chat-id
                 :text "I don't do a whole lot... yet."}))))
