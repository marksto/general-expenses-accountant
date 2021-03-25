(ns general-expenses-accountant.web
  (:require [clojure.core.async.impl.protocols :refer [closed?]]
            [compojure
             [core :as cmpj :refer [GET POST]]
             [route :as cmpj-route]]
            [fipp.edn :as f-edn]
            [morse
             [api :as m-api]
             [polling :as m-poll]]
            [ring.middleware
             [defaults :refer [api-defaults wrap-defaults]]
             [basic-authentication :refer [wrap-basic-authentication]]
             [json :refer [wrap-json-body]]
             [keyword-params :refer [wrap-keyword-params]]
             [nested-params :refer [wrap-nested-params]]
             [params :refer [wrap-params]]
             [session :refer [wrap-session]]]
            [drawbridge.core :as drawbridge]
            [taoensso.timbre :as log]

            [general-expenses-accountant.core :refer [bot-api]]
            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.html :as html]
            [general-expenses-accountant.l10n :as l10n]))

;; nREPL-over-HTTP

(def repl-path "/repl")

(def repl-handler
  (-> (drawbridge/ring-handler)
      (wrap-keyword-params)
      (wrap-nested-params)
      (wrap-params)
      (wrap-session)))

(defn- authed? [name pass]
  (= [name pass]
     [(System/getenv "REPL_USER") (System/getenv "REPL_PASSWORD")]))

(defn wrap-repl [handler]
  (fn [req]
    (let [handler* (if (= (:uri req) repl-path)
                     (wrap-basic-authentication repl-handler authed?)
                     handler)]
      (handler* req))))


;; App's HTTP API

(def api-path "/api")

(cmpj/defroutes
  app-routes
  (GET "/" []
    html/landing)
  (POST api-path {body :body}
    (log/debug "Request body:" body)
    (bot-api body)
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (l10n/tr :en :processed)})
  (POST "/debug" {body :body}
    (f-edn/pprint body)
    {:status 200})
  (cmpj-route/not-found
    (l10n/tr :en :not-found)))

(def app
  (-> (wrap-defaults app-routes api-defaults)
      (wrap-json-body {:keywords? true})))


;; Telegram-specific set-up

(defonce ^:private *updates-channel (atom nil))

(defn- start-long-polling!
  [token]
  (reset! *updates-channel
          (try
            (m-poll/start token bot-api)
            (catch Exception _
              nil))))

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
        (Thread/sleep 1000) ;; await a bit
        (when (not-polling?)
          (log/fatal "Fatal error during the long-polling setup")
          (System/exit 1)))
      (let [bot-url (or (config/get-prop :bot-url)
                        (str (config/get-prop :heroku-app-name) ".herokuapp.com"))]
        (log/info "Bot URL:" bot-url)
        (m-api/set-webhook token (str bot-url api-path))))))

(defn tear-down-tg-updates!
  []
  (if (config/in-dev?)
    (stop-long-polling!)))
