(ns general-expenses-accountant.tg-client
  (:require [clojure.core.async.impl.protocols :refer [closed?]]
            [morse
             [api :as m-api]
             [polling :as m-poll]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.core :refer [bot-api get-webhook-path]]
            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.web :refer [api-path]]))

;; State

(defonce ^:private *updates-channel (atom nil))

;; Start / Stop

(defn- start-long-polling!
  [token]
  (reset! *updates-channel
          (try
            (m-poll/start token bot-api)
            (catch Exception _
              nil)))) ;; will be processed later

(defn- not-polling?
  []
  (let [upd-chan @*updates-channel]
    (or (nil? upd-chan)
        (closed? upd-chan))))

(defn- stop-long-polling!
  []
  (m-poll/stop @*updates-channel))

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
  []
  (let [token (config/get-prop :bot-api-token)]
    (if (config/in-dev?)
      (do
        (m-api/set-webhook token "") ;; polling won't work if a webhook is set up
        (start-long-polling! token)
        (Thread/sleep 1000) ;; await a bit...
        (when (not-polling?)
          (log/fatal "Fatal error during the long-polling setup")
          (System/exit 1)))
      (let [bot-url (or (config/get-prop :bot-url)
                        (str (config/get-prop :heroku-app-name) ".herokuapp.com"))
            webhook-url (str bot-url (get-webhook-path api-path))]
        (log/info "Bot URL:" bot-url)
        (m-api/set-webhook token webhook-url)))))

(defn tear-down-tg-updates!
  []
  (if (config/in-dev?)
    (stop-long-polling!)))
