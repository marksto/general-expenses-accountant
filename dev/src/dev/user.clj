(ns dev.user
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]

            [general-expenses-accountant.config :as config]))

(println (str "Working in " (System/getProperty "user.dir") "\n"))

(config/load-and-validate! [] "dev/config.edn")


;; 1. Initializing the DB

(def db-schema
  (clojure.core/slurp "dev/resources/db/init-db-schema.sql"))

(defn db-does-not-exist?
  [db db-name]
  (let [query-str (str "SELECT 1 FROM pg_database WHERE datname='" db-name "'")]
    (empty? (sql/query db [query-str]))))

(defn create-db-schema
  [db db-name]
  (as-> db-schema $
        (str/replace $ #"user_name" (config/get-prop :db-user))
        (str/replace $ #"password" (config/get-prop :db-password))
        (str/replace $ #"database_name" db-name)
        (try
          (sql/execute! db [$] {:transaction? false})
          (println "Successfully created the DB\n")
          (catch Exception e
            (println (str "Failed to create the DB schema. Exception:\n" e))
            (System/exit 1)))))

(let [db-url (config/get-prop :database-url)
      db-name (last (str/split db-url #"/"))
      db (str/replace db-url db-name "")]
  (if (db-does-not-exist? db db-name)
    (do
      (println (str "Creating the DB '" db-name "'..."))
      (create-db-schema db db-name))
    (println (str "The DB '" db-name "' already exists\n"))))


;; 2. Starting the app

(load "../../src/general_expenses_accountant/main")
(def server (general-expenses-accountant.main/-main))

(if (:started (bean server))
  (println (str "Successfully started the " server "\n"))
  (do
    (println (str "Failed to start the web server"))
    (System/exit 2)))


;; 3. Running the DB migrations

(load "../../src/general_expenses_accountant/db")
(general-expenses-accountant.db/migrate-db)


;; 4. Preparing for REPL-driven development

(in-ns 'general-expenses-accountant.core)

(defn restart-long-polling
  []
  (tg-client/stop-long-polling!)
  (tg-client/setup-long-polling!
    (config/get-prop :bot-api-token) bot-api))

(comment
  ;; in case it had stopped
  (restart-long-polling)

  (do ;; to talk to the DB about 'chats'
    (load "../../src/general_expenses_accountant/domain/chat")
    (in-ns 'general-expenses-accountant.domain.chat)))
