(ns general-expenses-accountant.main
  "Responsible for starting Application from the Command Line"
  (:gen-class) ;; generates Java class which acts as an entry point for the JAR
  (:require [mount.core :as mount :refer [defstate]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.core :refer [bot-api]]
            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.l10n :as l10n]
            [general-expenses-accountant.tg-client :as tg-client]
            [general-expenses-accountant.web-handler :refer [api-path]]
            [general-expenses-accountant.web-server]))

; logger configuration
;; TODO: Add DB queries logging in DEV environment.
(log/set-level! [["general-expenses-accountant.*" :debug]
                 ["mount.core" :debug]
                 ["*" :info]])

(defn- get-bot-url
  []
  (or (config/get-prop :bot-url)
      (str (config/get-prop :heroku-app-name) ".herokuapp.com")))

(defn initialize!
  []
  (let [token (config/get-prop :bot-api-token)]
    (if (config/in-dev?)
      (tg-client/setup-long-polling! token bot-api)
      (tg-client/setup-webhook! token (get-bot-url) api-path)))
  (log/info (l10n/tr :en :init-fine)))

(defn finalize!
  []
  (log/info (l10n/tr :en :finishing))
  (when (config/in-dev?)
    (tg-client/stop-long-polling!))
  (log/info (l10n/tr :en :exit-fine)))

(defstate app
  :start (initialize!)
  :stop (finalize!))

(defn -main [& args]
  (mount/start-with-args args)
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. ^Runnable mount/stop))))
