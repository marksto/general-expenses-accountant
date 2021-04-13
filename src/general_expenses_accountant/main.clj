(ns general-expenses-accountant.main
  "Responsible for starting Application from the Command Line"
  (:gen-class) ;; generates Java class which acts as an entry point for the JAR
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.reload :refer [wrap-reload]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.core :refer [bot-api]]
            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.db :as db]
            [general-expenses-accountant.l10n :as l10n]
            [general-expenses-accountant.tg-client :as tg-client]
            [general-expenses-accountant.web :as web :refer [api-path]]))

; logger configuration
;; TODO: Add DB queries logging in DEV environment.
(log/set-level! [["general-expenses-accountant.*" :debug]
                 ["*" :info]])

(defn- get-bot-url
  []
  (or (config/get-prop :bot-url)
      (str (config/get-prop :heroku-app-name) ".herokuapp.com")))

(defn initialize
  "Extracted to be used also as a 'lein-ring' init target,
   for a case when the app's JAR is not executed directly."
  [& args]
  (config/load-and-validate! args "dev/config.edn")
  (db/init!)
  (let [token (config/get-prop :bot-api-token)]
    (if (config/in-dev?)
      (tg-client/setup-long-polling! token bot-api)
      (tg-client/setup-webhook! token (get-bot-url) api-path)))
  (general-expenses-accountant.core/init!)
  (log/info (l10n/tr :en :init-fine)))

(defn- prepare-handler-for-jetty
  "Prepares an app's web handler for use in DEV env (only),
   passing it as a var wrapped for reloading together with
   all modified namespaces on each request.
   This will prevent you from having to reload the modified
   namespaces manually or to reload your entire system when
   only the handler function changes.
   A 'lein-ring' plugin doesn't require such preparations.
   Additionally, the app is wrapped in an 'nREPL-over-HTTP'
   handler to allow interactive development in non-DEV env."
  []
  (if (config/in-dev?)
    (wrap-reload #'web/app)
    (web/wrap-repl web/app)))

(defn finalize
  []
  (log/info (l10n/tr :en :finishing))
  (when (config/in-dev?)
    (tg-client/stop-long-polling!)))

(defn -main [& args]
  (apply initialize args)
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. ^Runnable finalize)))
  (run-jetty (prepare-handler-for-jetty)
             {:port (config/get-prop :port)
              :join? false}))
