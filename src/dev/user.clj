(ns dev.user)

(load "../general_expenses_accountant/main")
(general-expenses-accountant.main/-main)

(in-ns 'general-expenses-accountant.core)
(deref *bot-user)

(defn restart-tg-long-polling []
  (tg-client/tear-down-tg-updates!)
  (tg-client/set-up-tg-updates! "/api" bot-api))

(comment
  ;; in case it had stopped
  (restart-tg-long-polling))