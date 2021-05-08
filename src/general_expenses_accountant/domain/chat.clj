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
   :bot-messages {:to-user db/restore-numeric-keys}
   :input db/restore-numeric-keys
   :accounts {:acc-type/general [db/restore-numeric-keys
                                 {:* {:type keyword
                                      :members set}}]
              :acc-type/personal [db/restore-numeric-keys
                                  {:* {:type keyword}}]
              :acc-type/group [db/restore-numeric-keys
                               {:* {:type keyword
                                    :members set}}]}
   :expenses {:popularity db/restore-string-keys}
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

(defn select-all
  []
  (t-db/select 'Chat))

(defn create!
  [new-chat]
  {:pre [(contains? new-chat :id)]}
  (t-db/insert! 'Chat new-chat))

(defn update!
  [chat-to-upd]
  {:pre [(contains? chat-to-upd :id)]}
  (t-db/update! 'Chat (:id chat-to-upd) chat-to-upd))
