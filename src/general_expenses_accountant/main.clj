(ns general-expenses-accountant.main
  "Responsible for starting Application from the Command Line"
  (:gen-class) ;; generates Java class which acts as an entry point for the JAR
  (:require
    [morse.api :as morse]
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.reload :refer [wrap-reload]]
    [taoensso.timbre :as log]

    [general-expenses-accountant.config :as config]
    [general-expenses-accountant.handler :refer [api-path app]]
    [general-expenses-accountant.l10n :as l10n]))

(defn init
  []
  (config/load-and-validate)
  (if (not (config/in-dev?))
    (let [token (config/get-prop :bot-api-token)
          bot-url (or (config/get-prop :bot-url)
                      (str (config/get-prop :heroku-app-name) ".herokuapp.com"))]
      (morse/set-webhook token (str bot-url api-path)))))

(defn -main [& args]
  (init)
  (let [handler (if (config/in-dev?)
                  (wrap-reload #'app)
                  app)]
    (log/info (l10n/tr :en :starting))
    (run-jetty handler {:port (config/get-prop :port)
                        :join? false})))
