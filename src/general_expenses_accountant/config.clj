(ns general-expenses-accountant.config
  (:require [omniconf.core :as cfg]))

(def ^:private dev-env "DEV")

(cfg/define {:active-profile {:description "Either 'DEV' or 'PROD'"
                              :one-of #{dev-env "PROD"}
                              :type :string}
             :bot-api-token {:description "Telegram Bot API token"
                             :verifier #(= (count %2) 46)
                             :type :string}
             :port {:type :number
                    :default 8080}})

(defn get-prop
  [key]
  (cfg/get key))

(defn in-dev?
  []
  (= (get-prop :active-profile) dev-env))

(defn load-and-validate
  []
  (cfg/populate-from-env)
  (cfg/verify
    :quit-on-error true
    :silent (not (in-dev?))))
