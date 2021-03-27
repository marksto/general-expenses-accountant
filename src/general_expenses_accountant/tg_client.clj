(ns general-expenses-accountant.tg-client
  (:require [clojure.core.async.impl.protocols :refer [closed?]]
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

(defn respond!
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
