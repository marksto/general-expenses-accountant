(ns general-expenses-accountant.config
  (:require [omniconf.core :as cfg]))

(cfg/define {:active-profile {:description "Either 'DEV' or 'PROD'"
                              :type :string}
             :bot-api-token {:description "Telegram Bot API token"
                             :type :string}
             :port {:type :number
                    :default 8080}})

(defn get-prop
  [key]
  (cfg/get key))

(defn in-dev?
  []
  (= (get-prop :active-profile) "DEV"))

(defn load-and-validate
  []
  (cfg/populate-from-env)
  (cfg/verify :quit-on-error true)
  (if (in-dev?)
    (cfg/report-configuration)))
