(ns general-expenses-accountant.web
  (:require [compojure
             [core :as cmpj :refer [GET POST]]
             [route :as cmpj-route]]
            [ring.middleware
             [defaults :refer [api-defaults wrap-defaults]]
             [basic-authentication :refer [wrap-basic-authentication]]
             [json :refer [wrap-json-body]]
             [keyword-params :refer [wrap-keyword-params]]
             [nested-params :refer [wrap-nested-params]]
             [params :refer [wrap-params]]
             [session :refer [wrap-session]]]
            [drawbridge.core :as drawbridge]

            [general-expenses-accountant.core :refer [bot-api get-webhook-path]]
            [general-expenses-accountant.html :as html]
            [general-expenses-accountant.l10n :as l10n]))

;; nREPL-over-HTTP

(def repl-path "/repl")

(def repl-handler
  (-> (drawbridge/ring-handler)
      (wrap-keyword-params)
      (wrap-nested-params)
      (wrap-params)
      (wrap-session)))

(defn- authed? [name pass]
  (= [name pass]
     [(System/getenv "REPL_USER") (System/getenv "REPL_PASSWORD")]))

(defn wrap-repl [handler]
  (fn [req]
    (let [handler* (if (= (:uri req) repl-path)
                     (wrap-basic-authentication repl-handler authed?)
                     handler)]
      (handler* req))))


;; App's HTTP API

(def api-path "/api")

(def webhook-path
  (get-webhook-path api-path))

(cmpj/defroutes
  app-routes
  (GET "/" []
    html/landing)
  (POST webhook-path {body :body}
    (bot-api body)
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (l10n/tr :en :processed)})
  (cmpj-route/not-found
    (l10n/tr :en :not-found)))

(def app
  (-> (wrap-defaults app-routes api-defaults)
      (wrap-json-body {:keywords? true})))
