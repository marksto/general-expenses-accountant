(ns general-expenses-accountant.db
  (:require [clojure.string :as str]
            [clojure.java.jdbc :refer [IResultSetReadColumn ISQLValue]]

            [honeysql.core :as sql]
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
  (:import [clojure.lang Keyword]
           [java.sql Timestamp ResultSetMetaData]
           [org.postgresql.util PGobject]))

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

;; - ENUMS

(def ^:private enum-types
  "A set of all PostgreSQL ENUM types in DB schema migrations.
   Used to convert enum values back into Clojure keywords."
  ;; TODO: Add other ENUMs here manually OR by extraction.
  #{"chat_type"})

(defn- ^PGobject kwd->pg-enum
  [kwd]
  {:pre [(some? (namespace kwd))]}
  (let [type (-> (namespace kwd)
                 (str/replace "-" "_"))
        value (name kwd)]
    (doto (PGobject.)
      (.setType type)
      (.setValue value))))

;; NB: This way of using custom datatype is ignored by HoneySQL,
;;     but for `clojure.java.jdbc` this is an idiomatic approach
;;     to its implementation, thus we leave it for completeness.
(extend-protocol ISQLValue
  Keyword
  (sql-value [kwd]
    (let [pg-obj (kwd->pg-enum kwd)
          type (.getType pg-obj)]
      (if (contains? enum-types type)
        pg-obj
        kwd))))

(defn- pg-enum->kwd
  ([^PGobject pg-obj]
   (prn pg-obj)
   (pg-enum->kwd (.getType pg-obj)
                 (.getValue pg-obj)))
  ([type val]
   {:pre [(not (str/blank? type))
          (not (str/blank? val))]}
   (keyword (str/replace type "_" "-") val)))

(extend-protocol IResultSetReadColumn
  String
  (result-set-read-column [val ^ResultSetMetaData rsmeta idx]
    (let [type (.getColumnTypeName rsmeta idx)]
      (if (contains? enum-types type)
        (pg-enum->kwd type val)
        val)))

  PGobject
  (result-set-read-column [val _ _]
    (if (contains? enum-types (.getType val))
      (pg-enum->kwd val)
      val)))

;; NB: This one is different from the plain ':keyword' by the fact
;;     that an additional DB ENUM type values check is carried out.
(models/add-type!
  :enum
  :in kwd->pg-enum
  :out identity)

;; - JSONB

(def ^:private default-json-object-mapper
  (json/object-mapper {:decode-key-fn true
                       :bigdecimals true}))

(defn- ->json
  [obj]
  (json/write-value-as-string obj default-json-object-mapper))
(defn- ->jsonb
  [obj]
  (sql/call :cast (->json obj) :jsonb))

(defn- <-json
  [^PGobject pg-obj]
  (json/read-value (.getValue pg-obj) default-json-object-mapper))

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
