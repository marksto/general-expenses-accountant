(ns general-expenses-accountant.config
  (:require [clojure.java.io :as io]
            [omniconf.core :as cfg]
            [taoensso.timbre :as log]))

(def ^:private dev-env "DEV")

(cfg/define {:bot-api-token {:description "Telegram Bot API token"
                             :verifier #(= (count %2) 46)
                             :required true
                             :type :string}
             :bot-url {:description "The bot URL (for a webhook)"
                       :required false
                       :type :string}
             :environment {:description "Either 'DEV' or 'PROD'"
                           :one-of #{dev-env "PROD"}
                           :required false
                           :type :string}
             :heroku-app-name {:description "Heroku app name"
                               :required false
                               :type :string}
             :port {:type :number
                    :default 8080}})

(defn get-prop
  [key]
  (cfg/get key)) ;; internally memoized

(defn in-dev?
  []
  (= (get-prop :environment) dev-env))

(defn load-and-validate!
  ([]
   (load-and-validate! nil))
  ([file]
   (cfg/populate-from-env)
   (when (and (some? file) (in-dev?))
     (if (.exists (io/as-file file))
       (cfg/populate-from-file file)
       (log/warn "Can't find local dev configuration file" file)))
   (cfg/verify
     :quit-on-error true
     :silent (not (in-dev?)))))
