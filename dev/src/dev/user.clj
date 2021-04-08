(ns dev.user)

(load "../../src/general_expenses_accountant/main")
(def server (general-expenses-accountant.main/-main))

(in-ns 'general-expenses-accountant.core)

(defn restart-long-polling
  []
  (tg-client/stop-long-polling!)
  (tg-client/setup-long-polling!
    (config/get-prop :bot-api-token) bot-api))

(comment
  ;; in case it had stopped
  (restart-long-polling))
