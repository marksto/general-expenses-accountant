(ns general-expenses-accountant.domain.chat
  (:require [toucan
             [db :as db]
             [models :as models :refer [IModel]]]))

(models/defmodel Chat :chat
  IModel
  (types [_]
    {:type :enum
     :data :jsonb})
  (properties [_]
    {:timestamped? true}))

;; TODO: Add chat-related DB functions.
