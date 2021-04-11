(ns general-expenses-accountant.db
  (:require [clojure.string :as str]

            [honeysql.core :as sql]
            [jsonista.core :as json]
            [toucan
             [db :as db]
             [models :as models]])
  (:import [java.sql Timestamp]))

;; SETTING-UP

(db/set-default-automatically-convert-dashes-and-underscores! true)

(models/set-root-namespace! 'general-expenses-accountant.domain)

;; TODO: Pool DB connections with HikariCP.
(defn- init!*
  [db-name user-name]
  (db/set-default-db-connection!
    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname (str "//localhost:5432/" db-name)
     :user user-name

     ;; PostgreSQL-specific
     :reWriteBatchedInserts true}))

(defn init!
  [database-url user-name]
  (let [db-name (last (str/split database-url #"/"))]
    (init!* db-name user-name)))


;; MIGRATIONS

;; TODO: Implement DB schema migrations — with 'ragtime'?


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
