(ns general-expenses-accountant.tg-client
  (:require [clojure.core.async.impl.protocols :refer [closed?]]
            [clj-http.client :as http] ;; comes with Morse
            [morse
             [api :as m-api]
             [polling :as m-poll]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]))

;; State

(defonce ^:private *updates-channel (atom nil))


;; Start / Stop

(defn- start-long-polling!
  [token upd-handler]
  (reset! *updates-channel
          (try
            (m-poll/start token upd-handler)
            (catch Exception _
              ;; will be processed later
              nil))))

(defn- not-polling?
  []
  (let [upd-chan @*updates-channel]
    (or (nil? upd-chan)
        (closed? upd-chan))))

(defn- stop-long-polling!
  []
  (m-poll/stop @*updates-channel))

(defn- construct-webhook-url
  [bot-url api-path token]
  ;; Telegram Bot API recommendation
  (str bot-url api-path "/" token))

(defn set-up-tg-updates!
  "As Telegram Bot API docs state, there are two ways of getting updates:
   - WEBHOOK — we provide a public HTTP endpoint, through which Telegram will
               provide the latest unprocessed messages from users; this way is
               usual for a remotely deployed web application, but can be quite
               tricky for a local development.
   - LONG-POLLING — we perform long-polling of Telegram server ourselves, which
                    means a continuous calling of the 'getUpdates' Telegram Bot
                    API HTTP endpoint and waiting to receive user messages sent
                    to our bot over this time, then making the same call again;
                    this way is fine for a local debugging/testing purposes."
  [api-path upd-handler]
  (let [token (config/get-prop :bot-api-token)]
    (if (config/in-dev?)
      (do
        (m-api/set-webhook token "") ;; polling won't work if a webhook is set up
        (start-long-polling! token upd-handler)
        ;; TODO: Make this an async task that aims to "re-spawn" the long-polling.
        (Thread/sleep 1000) ;; await a bit...
        (when (not-polling?)
          (log/fatal "Fatal error during the long-polling setup")
          (System/exit 1)))
      (let [bot-url (or (config/get-prop :bot-url)
                        (str (config/get-prop :heroku-app-name) ".herokuapp.com"))
            webhook-url (construct-webhook-url bot-url api-path token)]
        (log/info "Bot URL:" bot-url)
        (m-api/set-webhook token webhook-url)))))

(defn tear-down-tg-updates!
  []
  (if (config/in-dev?)
    (stop-long-polling!)))


;; Operations

(def base-url "https://api.telegram.org/bot")

(defn get-me
  []
  (let [token (config/get-prop :bot-api-token)
        url (str base-url token "/getMe")
        resp (http/get url {:as :json})]
    (get resp :body)))

(defn get-chat-members-count
  [chat-id]
  (let [token (config/get-prop :bot-api-token)
        url (str base-url token "/getChatMembersCount")
        query {:chat_id chat-id}
        resp (http/get url {:content-type :json
                            :as :json
                            :query-params query})]
    (get-in resp [:body :result])))

;; NB: Morse does not support optional parameters (e.g. 'url') in 'answer-callback'.
(defn answer-callback-query
  "Sends an answer to an callback query"
  ([token callback-query-id]
   ;; NB: Notification 'text' is not specified => nothing will be shown to the user.
   (answer-callback-query token callback-query-id {}))
  ([token callback-query-id options]
   (let [url (str base-url token "/answerCallbackQuery")
         body (into {:callback_query_id callback-query-id} options)
         resp (http/post url {:content-type :json
                              :as :json
                              :form-params body})]
     (-> resp :body))))

;; NB: Properly wrapped in try-catch and logged to highlight the exact HTTP client error.
(defn respond!
  [{:keys [type chat-id text inline-query-id results callback-query-id show-alert options]
    :as response}]
  (try
    (let [token (config/get-prop :bot-api-token)
          tg-response
          (case type
            :text (m-api/send-text token chat-id options text)
            :inline (m-api/answer-inline token inline-query-id options results)
            ;; TODO: Switch to the 'answer-callback-query' above. Test cautiously.
            :callback (m-api/answer-callback token callback-query-id text show-alert))]
      (log/debug "Telegram returned:" tg-response)
      tg-response)
    (catch Exception e
      (log/error e "Failed to respond with:" response))))
