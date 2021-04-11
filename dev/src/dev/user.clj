(ns dev.user
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]))

(println (str "Working in " (System/getProperty "user.dir") "\n"))


;; 1. Initializing the DB

(def database-url
  (System/getenv "DATABASE_URL"))
(def user-name
  (System/getenv "DATABASE_USER"))
(def db-schema
  (clojure.core/slurp "dev/resources/db/init-db-schema.sql"))

(defn db-does-not-exist?
  [db-name]
  (let [query-str (str "SELECT 1 FROM pg_database WHERE datname='" db-name "'")]
    (empty? (sql/query database-url [query-str]))))

(defn create-db-schema
  [db-name]
  (as-> db-schema $
        (str/replace $ #"user_name" user-name)
        (str/replace $ #"database_name" db-name)
        (try
          (sql/execute! database-url [$] {:transaction? false})
          (println "Successfully created the DB\n")
          (catch Exception e
            (println (str "Failed to create the DB schema. Exception:\n" e))
            (System/exit 1)))))

(let [db-name (last (str/split database-url #"/"))]
  (if (db-does-not-exist? db-name)
    (do
      (println (str "Creating the DB '" db-name "'..."))
      (create-db-schema db-name))
    (println (str "The DB '" db-name "' already exists\n"))))


;; 2. Starting the app

(load "../../src/general_expenses_accountant/main")
(def server (general-expenses-accountant.main/-main))

(if (:started (bean server))
  (println (str "Successfully started the " server "\n"))
  (do
    (println (str "Failed to start the web server"))
    (System/exit 2)))


;; 3. Preparing for REPL-driven development

(in-ns 'general-expenses-accountant.core)

(defn restart-long-polling
  []
  (tg-client/stop-long-polling!)
  (tg-client/setup-long-polling!
    (config/get-prop :bot-api-token) bot-api))

(comment
  ;; in case it had stopped
  (restart-long-polling))
