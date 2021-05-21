(ns general-expenses-accountant.web-server
  (:require [mount.core :refer [defstate]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.reload :refer [wrap-reload]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]
            [general-expenses-accountant.web-handler :as web])
  (:import [org.eclipse.jetty.server Server]))

(defn- prepare-handler-for-jetty
  "Prepares an app's web handler for use in DEV env (only),
   passing it as a var wrapped for reloading together with
   all modified namespaces on each request.
   This will prevent you from having to reload the modified
   namespaces manually or to reload your entire system when
   only the handler function changes.
   A 'lein-ring' plugin doesn't require such preparations.
   Additionally, the app is wrapped in an 'nREPL-over-HTTP'
   handler to allow interactive development in non-DEV env."
  []
  (if (config/in-dev?)
    (wrap-reload #'web/handler)
    (web/wrap-repl web/handler)))

(defn- check-started
  [server]
  (if (:started (bean server))
    (do
      (log/info (str "Successfully started the " server))
      server)
    (do
      (log/info "Failed to start the web server")
      (System/exit 2))))

(defstate server
  :start (-> (prepare-handler-for-jetty)
             (run-jetty {:port (config/get-prop :port)
                         :join? false})
             (check-started))
  :stop (.stop ^Server server))
