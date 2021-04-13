(ns general-expenses-accountant.db
  (:require [honeysql.core :as sql]
            [jsonista.core :as json]
            [ragtime
             [jdbc :as rt-jdbc]
             [repl :as rt-repl]]
            [taoensso
             [encore :as encore]
             [timbre :as log]]
            [toucan
             [db :as db]
             [models :as models]]

            [general-expenses-accountant.config :as config])
  (:import [java.sql Timestamp]))

;; SETTING-UP

(defn- db-spec
  []
  (let [db-url (config/get-prop :database-url)
        jdbc-url-parts (re-find (re-matcher #"(jdbc:)?([^:]+):(.+)" db-url))
        [subprotocol subname] (encore/get-subvector jdbc-url-parts -2 2)]
    {:classname "org.postgresql.Driver"
     :subprotocol subprotocol
     :subname subname
     :user (config/get-prop :db-user)
     :password (config/get-prop :db-password)

     ;; PostgreSQL-specific
     :reWriteBatchedInserts true}))

;; TODO: Pool DB connections with HikariCP.
(defn init!
  []
  (db/set-default-db-connection! (db-spec))
  (db/set-default-automatically-convert-dashes-and-underscores! true))


(models/set-root-namespace! 'general-expenses-accountant.domain)


;; MIGRATIONS

(defn- load-ragtime-config
  []
  {:datastore (rt-jdbc/sql-database (db-spec) {:migrations-table "migrations"})
   :migrations (rt-jdbc/load-resources "db/migrations")})


(defn migrate-db
  "Uses 'ragtime' to apply DB migration scripts."
  []
  (try
    (log/info "Applying DB migrations...")
    (let [output (-> (load-ragtime-config)
                     rt-repl/migrate
                     with-out-str)]
      (log/info {:event ::migration-succeed
                 :ragtime-output output}))
    (catch Exception e
      (log/error e {:event ::migration-failed})
      (System/exit 1))))

(defn rollback-db
  "Uses 'ragtime' to apply DB migration rollback scripts."
  []
  (try
    (log/info "Rolling back DB migrations...")
    (let [output (-> (load-ragtime-config)
                     rt-repl/rollback
                     with-out-str)]
      (log/info {:event ::migration-rollback-succeed
                 :ragtime-output output}))
    (catch Exception e
      (log/error e {:event ::migration-rollback-failed})
      (System/exit 1))))

(defn reinit-db
  "Clears DB and recreates it using 'ragtime'."
  []
  (rollback-db)
  (migrate-db))


(defn lein-migrate-db
  "Called by the `lein migrate-db` via alias."
  []
  (migrate-db))

(defn lein-rollback-db
  "Called by the `lein rollback-db` via alias."
  []
  (rollback-db))

(defn lein-reinit-db
  "Called by the `lein reinit-db` via alias."
  []
  (reinit-db))


;; CUSTOM TYPES

(def ^:private default-json-object-mapper
  (json/object-mapper {:decode-key-fn true
                       :bigdecimals true}))
(def ^:private ->json
  #(json/write-value-as-string % default-json-object-mapper))
(def ^:private <-json
  #(json/read-value % default-json-object-mapper))

(defn ->jsonb [obj]
  (sql/call :cast (->json obj) :jsonb))

(models/add-type!
  :jsonb
  :in ->jsonb
  :out <-json)


;; CUSTOM PROPS

(defn- sql-now
  []
  (Timestamp. (System/currentTimeMillis)))

(models/add-property!
  :timestamped?
  :insert (fn [obj _]
            (let [now (sql-now)]
              (assoc obj :created-at now, :updated-at now)))
  :update (fn [obj _]
            (assoc obj :updated-at (sql-now))))
