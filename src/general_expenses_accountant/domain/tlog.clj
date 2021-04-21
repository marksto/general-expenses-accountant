(ns general-expenses-accountant.domain.tlog
  (:require [toucan
             [db :as t-db]
             [models :as t-m :refer [IModel]]]))

(t-m/defmodel TLog :tlog
  IModel
  (properties [_]
    {:timestamped? :created-at}))

(defn select-by-chat-id
  [chat-id]
  (t-db/select 'TLog :chat-id chat-id))

(defn create!
  [new-transaction]
  {:pre [(contains? new-transaction :chat-id)]}
  (t-db/insert! 'TLog new-transaction))
