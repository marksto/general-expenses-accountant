(ns general-expenses-accountant.handler
  (:require
    [compojure
     [core :refer [defroutes GET POST]]
     [route :as route]]
    [fipp.edn :refer [pprint]]
    [ring.middleware
     [defaults :refer [api-defaults wrap-defaults]]
     [json :refer [wrap-json-body]]
     [reload :refer [wrap-reload]]]

    [general-expenses-accountant.core :refer [bot-api]]
    [general-expenses-accountant.landing :as landing]
    [general-expenses-accountant.l10n :as l10n]))

(defroutes app-routes
  (GET "/" []
    landing/home)
  (POST "/api" {body :body}
    (bot-api body))
  ;(POST "/debug" {body :body}
  ;  (pprint body))
  (route/not-found
    (l10n/tr :en :not-found)))

(def app
  (-> (wrap-defaults app-routes api-defaults)
    (wrap-json-body {:keywords? true})))
