(ns dev.user
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [mount.core :as mount]))

;; 0. Loading DEV config

(println (str "Working in " (System/getProperty "user.dir") "\n"))

(load "../../src/general_expenses_accountant/config")
(alias 'config 'general-expenses-accountant.config)

(mount/start #'config/loader)


;; 1. Initializing the DB

(load "../../src/general_expenses_accountant/db")
(alias 'db 'general-expenses-accountant.db)

(def db-schema
  (clojure.core/slurp "dev/resources/db/init-db-schema.sql"))

(defn db-does-not-exist?
  [db db-name]
  (let [query-str (str "SELECT 1 FROM pg_database WHERE datname='" db-name "'")]
    (empty? (sql/query db [query-str]))))

(defn create-db-schema
  [db db-info]
  (as-> db-schema $
        (str/replace $ #"user_name" (or (:username db-info)
                                        (config/get-prop :db-user)))
        (str/replace $ #"password" (or (:password db-info)
                                       (config/get-prop :db-password)))
        (str/replace $ #"database_name" (:dbname db-info))
        (try
          (sql/execute! db [$] {:transaction? false})
          (println "Successfully created the DB\n")
          (catch Exception e
            (println (str "Failed to create the DB schema. Exception:\n" e))
            (System/exit 1)))))

(let [db-url (config/get-prop :database-url)
      db-info (db/parse-db-url db-url)
      db-name (:dbname db-info)]
  (if (db-does-not-exist? db-url db-name)
    (do
      (println (str "Creating the DB '" db-name "'..."))
      (create-db-schema db-url db-info))
    (println (str "The DB '" db-name "' already exists\n"))))


;; 2. Starting the app

(load "../../src/general_expenses_accountant/main")
(alias 'main 'general-expenses-accountant.main)

(mount/start-without #'config/loader)

(defn restart-long-polling
  []
  (mount/stop #'main/app)
  (mount/start #'main/app))

(comment
  ;; in case it had stopped
  (restart-long-polling))


;; 3. Preparing for REPL-driven development

(in-ns 'general-expenses-accountant.core)

(comment
  (do ;; to talk to the DB about 'chats'
    (load "../../src/general_expenses_accountant/domain/chat")
    (in-ns 'general-expenses-accountant.domain.chat)))
