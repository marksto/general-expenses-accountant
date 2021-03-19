(ns general-expenses-accountant.handler
  (:require
    [compojure
     [core :refer [defroutes POST]]
     [route :as route]]
    [fipp.edn :refer [pprint]]
    [ring.middleware
     [defaults :refer [api-defaults wrap-defaults]]
     [json :refer [wrap-json-body]]
     [reload :refer [wrap-reload]]]

    [general-expenses-accountant.config :as config]
    [general-expenses-accountant.core :refer [bot-api]]))

(defroutes app-routes
  (POST "/api" {body :body}
    (bot-api body))
  ;(POST "/debug" {body :body}
  ;  (pprint body))
  (route/not-found
    (config/get-prop :not-found-message)))

(def app
  (-> (wrap-defaults app-routes api-defaults)
    (wrap-json-body {:keywords? true})))
