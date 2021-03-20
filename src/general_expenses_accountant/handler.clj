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

(def api-path "/api")

(defroutes app-routes
           (GET "/" []
             landing/home)
           (POST api-path {body :body}
             (bot-api body)
             {:status 200
              :headers {"Content-Type" "text/plain"}
              :body (l10n/tr :en :processed)})
           (POST "/debug" {body :body}
             (pprint body)
             {:status 200})
           (route/not-found
             (l10n/tr :en :not-found)))

(def app
  (-> (wrap-defaults app-routes api-defaults)
      (wrap-json-body {:keywords? true})))
