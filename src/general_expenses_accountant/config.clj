(ns general-expenses-accountant.config
  (:require [clojure.java.io :as io]
            [mount.core :as mount :refer [defstate]]
            [omniconf.core :as cfg]
            [taoensso.timbre :as log]))

(def ^:private dev-env "DEV")

(cfg/define {:env-type {:description "Environment type (to be set in CMD args or ENV)"
                        :one-of #{dev-env "PROD"}
                        :required true
                        :type :string}

             :database-url {:description "The Heroku's standard 'DATABASE_URL' var"
                            :type :string}
             :db-user {:description "The database user name for bot"
                       :type :string}
             :db-password {:description "The database user password"
                           :type :string}
             :max-db-conn {:description "Max # of simultaneous DB connections"
                           :default 10
                           :type :number}

             :bot-api-token {:description "Telegram Bot API token"
                             :verifier #(= (count %2) 46)
                             :required true
                             :type :string}
             :bot-url {:description "The bot URL (for a webhook)"
                       :type :string}
             :bot-username {:description "The bot Telegram username"
                            :type :string}

             :heroku-app-name {:description "Heroku app name"
                               :type :string}
             :port {:type :number
                    :default 8080}})

(defn get-prop
  [key]
  (cfg/get key)) ;; internally memoized

(defn in-dev?
  []
  (= (get-prop :env-type) dev-env))

(defn load-and-validate!
  ([]
   (load-and-validate! []))
  ([args]
   (load-and-validate! args "dev/config.edn"))
  ([args file]
   (cfg/populate-from-cmd args)
   (cfg/populate-from-env)

   ;; here, the ':env-type' have to be determined already
   (when (and (some? file) (in-dev?))
     (if (.exists (io/as-file file))
       (cfg/populate-from-file file)
       (log/warn "Can't find local dev configuration file" file)))

   (cfg/verify :quit-on-error true
               :silent (not (in-dev?)))))

(defstate configurator
  :start (load-and-validate! (mount/args)))
