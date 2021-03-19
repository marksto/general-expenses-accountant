(ns general-expenses-accountant.main
  "Responsible for starting Application from the Command Line"
  (:gen-class) ;; generates Java class which acts as an entry point for the JAR
  (:require
    [ring.adapter.jetty :refer [run-jetty]]
    [ring.middleware.reload :refer [wrap-reload]]
    ;[taoensso.timbre :as log]

    [general-expenses-accountant.config :as config]
    [general-expenses-accountant.handler :refer [app]]))

(defn -main [& args]
  (config/load-and-validate)
  (let [handler (if (config/in-dev?)
                  (wrap-reload #'app)
                  app)]
    ;; TODO: Re-write with proper logger.
    (println (config/get-prop :starting-message))
    (run-jetty handler {:port (config/get-prop :port)
                        :join? false}))) ;; TODO: Figure out, why ':join? false' is left???
