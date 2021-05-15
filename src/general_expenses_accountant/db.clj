(ns general-expenses-accountant.db
  (:require [clojure.string :as str]
            [clojure.java.jdbc :refer [IResultSetReadColumn ISQLValue]]

            [hikari-cp.core :as cp]
            [honeysql.core :as sql]
            [jsonista.core :as json]
            [ragtime
             [jdbc :as rt-jdbc]
             [repl :as rt-repl]]
            [taoensso.timbre :as log]
            [toucan
             [db :as db]
             [models :as models]]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.nums :as nums])
  (:import [clojure.lang Keyword]
           [java.sql ResultSetMetaData]
           [java.util Calendar]
           [java.util.concurrent TimeUnit]
           [org.postgresql.util PGobject PGTimestamp]))

;; SETTING-UP

(def ^:private heroku-db-url-re
  #"postgres://(?<username>.+):(?<password>.+)@(?<host>.+):(?<port>[0-9]+)/(?<dbname>.+)")

(def ^:private jdbc-url-re
  #"(jdbc:)?(?<adapter>[^:]+).*://(?<host>.+):(?<port>[0-9]+)/(?<dbname>[^?]+)(\?(user(name)?=(?<username>[^&]+))?(&password=(?<password>[^&]+))?)?")

(defn- parse-db-url*
  [db-url-re db-url]
  (let [matcher (re-matcher db-url-re db-url)
        get-group (fn [^String name] (.group matcher name))]
    (when (.matches matcher)
      {:adapter (get-group "adapter")
       :username (get-group "username")
       :password (get-group "password")
       :host (get-group "host")
       :port (get-group "port")
       :db-name (get-group "dbname")})))

(defn parse-db-url
  "The DATABASE_URL for the Heroku Postgres add-on follows the naming convention:
   postgres://<username>:<password>@<host>:<port>/<dbname>

   However the Postgres JDBC driver uses the following convention:
   jdbc:postgresql://<host>:<port>/<dbname>?user=<username>&password=<password>

   Due to this difference (notice the extra 'ql' at the end of 'jdbc:postgresql')
   we need to hardcode the scheme to 'jdbc:postgresql'.

   Most clients will connect over SSL by default, but on occasion it is necessary
   to set the 'sslmode=require' parameter on a Postgres connection. We should add
   this parameter in code rather than edit the config var directly."
  [db-url]
  {:pre [(some? db-url)]}
  (or (parse-db-url* jdbc-url-re db-url)
      (some-> (parse-db-url* heroku-db-url-re db-url)
              (assoc :adapter "postgresql"))))

(defn- get-datasource-options
  []
  (let [db-info (parse-db-url (config/get-prop :database-url))]
    {:auto-commit true
     :read-only false
     :connection-timeout (.toMillis TimeUnit/SECONDS 30)
     :validation-timeout (.toMillis TimeUnit/SECONDS 5)
     :idle-timeout (.toMillis TimeUnit/MINUTES 10)
     :max-lifetime (.toMillis TimeUnit/MINUTES 30)
     :minimum-idle 1
     :maximum-pool-size (config/get-prop :max-db-conn)
     :pool-name "db-pool"
     :adapter (:adapter db-info)
     :username (or (:username db-info)
                   (config/get-prop :db-user))
     :password (or (:password db-info)
                   (config/get-prop :db-password))
     :database-name (:db-name db-info)
     :server-name (:host db-info)
     :port-number (:port db-info)}))

(defonce datasource
         (delay (cp/make-datasource (get-datasource-options))))

(defn- get-db-spec
  []
  {:datasource @datasource
   ;; PostgreSQL-specific
   :reWriteBatchedInserts true})

(defn init!
  []
  (db/set-default-db-connection! (get-db-spec))
  (db/set-default-automatically-convert-dashes-and-underscores! true))

(defn close!
  []
  (cp/close-datasource @datasource))


(models/set-root-namespace! 'general-expenses-accountant.domain)


;; MIGRATIONS

(defn- load-ragtime-config
  []
  {:datastore (rt-jdbc/sql-database (get-db-spec)
                                    {:migrations-table "migrations"})
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


(defn transform-json
  "Recursively transforms the JSON object (a map) according to a predefined mapping.
   To be called within `post-select`, e.g. with an `update-in` on a JSONB attribute."
  [json-obj mapping-rules]
  (letfn [(reducer [m [k v]]
            (if (= :* k)
              ;; "transform for every key in a map" scenario
              ;; {M, [:* FN]} -> (recur {M,  [k_1 FN]}) = M'
              ;;                  ...
              ;;                 (recur {M', [k_N FN]})
              (reduce (fn [m' k']
                        (reducer m' [k' v]))
                      m (keys m))

              (let [mkv (get m k)]
                (if (some? mkv)
                  (if (map? v)
                    ;; "internal (map attributes) transformations" scenario
                    ;; {M, [:x {k_1 FN_1       (recur {(:x M), [k_1 FN_1]})
                    ;;          ...         ->  ...
                    ;;          k_N FN_N}]}    (recur {(:x M), [k_N FN_N]})
                    (let [kvs (seq v)
                          res (reduce (fn [m' [k' v']]
                                        (reducer m' [k' v']))
                                      mkv kvs)]
                      (assoc m k res))

                    (if (vector? v)
                      ;; "series of transformations" scenario (order matters!)
                      ;; {M, [:x [EXPR_1       (recur {M,  [:x EXPR_1]}) = M'
                      ;;          ...       ->  ...
                      ;;          EXPR_N]]}    (recur {M', [:x EXPR_N]})
                      (reduce (fn [m' v']
                                (reducer m' [k v']))
                              m v)

                      ;; "direct transformation" scenario
                      ;; {M, [:x FN]} -> (FN (:x M)) = M'
                      (assoc m k (v mkv))))
                  m))))]
    (if (map? json-obj)
      (reduce reducer json-obj mapping-rules)
      json-obj)))

(defn- restore-keys
  [map restore-fn]
  (apply hash-map
         (mapcat (fn [[k v]]
                   [(restore-fn k) v])
                 map)))

(defn restore-string-keys
  "Changes the type of keys in the 'map' from Keyword to a String."
  [map]
  (restore-keys map name))

(defn restore-numeric-keys
  "Changes the type of keys in the 'map' from Keyword to an Integer."
  [map]
  (restore-keys map (comp nums/parse-int name)))


;; CUSTOM PROPS

(defn- sql-now
  "Results in a correct `timestamptz` object."
  []
  (PGTimestamp. (System/currentTimeMillis)
                (Calendar/getInstance)))

(def timestamped-options #{:created-at :updated-at :all})

(defn- is-ts-option?
  [ts-option prop-val]
  (or (true? prop-val) (= :all prop-val) (= ts-option prop-val)))

(models/add-property!
  :timestamped?
  :insert (fn [obj prop-val]
            (assert (or (boolean? prop-val)
                        (contains? timestamped-options prop-val)))
            (let [now (sql-now)]
              (cond-> obj
                      (is-ts-option? :created-at prop-val) (assoc :created-at now)
                      (is-ts-option? :updated-at prop-val) (assoc :updated-at now))))
  :update (fn [obj prop-val]
            (assert (or (boolean? prop-val)
                        (contains? timestamped-options prop-val)))
            (when (is-ts-option? :updated-at prop-val)
              (assoc obj :updated-at (sql-now)))))
