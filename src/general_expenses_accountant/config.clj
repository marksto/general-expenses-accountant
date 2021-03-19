(ns general-expenses-accountant.config
  (:require [omniconf.core :as cfg]))

(cfg/define {:active-profile {:description "Either 'DEV' or 'PROD'"
                              :type :string}
             :bot-api-token {:description "Telegram Bot API token"
                             :type :string}
             :datetime-format {:type :string
                               :default "dd-MM-yyyy (HH:mm:ss)"}
             :starting-message {:type :string
                                :default "Starting the bot..."}
             :not-found-message {:type :string
                                 :default "No such API endpoint"}
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
