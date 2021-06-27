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
  (let [tg-client-cfg {:token (config/get-prop :bot-api-token)}]
    (if (config/in-dev?)
      (tg-client/setup-long-polling! tg-client-cfg bot-api)
      (tg-client/setup-webhook! tg-client-cfg (get-bot-url) api-path)))
  (log/info (l10n/tr :en :init-fine)))

(defn finalize!
  []
  (log/info (l10n/tr :en :finishing))
  (let [tg-client-cfg {:token (config/get-prop :bot-api-token)}]
    (when (config/in-dev?)
      (tg-client/teardown-long-polling! tg-client-cfg)))
  (log/info (l10n/tr :en :exit-fine)))

(defstate ^:private app
  :start (initialize!)
  :stop (finalize!))

(defn -main [& args]
  (mount/start-with-args args)
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. ^Runnable mount/stop))))
