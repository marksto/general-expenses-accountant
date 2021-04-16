(ns general-expenses-accountant.domain.chat
  (:require [toucan
             [db :as t-db]
             [models :as t-m :refer [IModel]]]

            [general-expenses-accountant.db :as db]))

;; TODO: Re-write w/ transformational schema?
(def ^:private chat-mapping-rules
  {;; common part
   :state keyword

   ;; private chat-specific
   :groups set

   ;; group chat-specific
   :accounts {:general [db/restore-numeric-keys
                        {:* {:type keyword
                             :members set}}]
              :personal [db/restore-numeric-keys
                         {:* {:type keyword}}]
              :group [db/restore-numeric-keys
                      {:* {:type keyword
                           :members set}}]}
   :expenses {:popularity db/restore-string-keys}
   :data-store {:type keyword}
   :user-account-mapping db/restore-numeric-keys})

(t-m/defmodel Chat :chat
  IModel
  (types [_]
    {:type :enum
     :data :jsonb})
  (properties [_]
    {:timestamped? true})
  (post-select [chat]
    (update-in chat [:data] #(db/transform-json % chat-mapping-rules))))

;; TODO: Add chat-related DB functions.
