(ns general-expenses-accountant.tg-client
  "The Morse extension that provides:
   1) a conventional way to set up the updates
   2) some Telegram Bot API operations
   3) missing update handlers

   NB: As Telegram Bot API documentation states,
       there are two ways to receive updates:
       - via WEBHOOK (should be set in PROD env)
       - via LONG-POLLING"
  (:require [clojure.core.async.impl.protocols :refer [closed?]]
            [clj-http.client :as http] ;; comes with Morse
            [morse
             [api :as m-api]
             [handlers :as m-hlr]
             [polling :as m-poll]]
            [taoensso.timbre :as log]))

;; Updates Setup

;; WEBHOOK

(defn- construct-webhook-url
  "Constructs webhook URL according to the Telegram Bot API recommendation."
  [bot-url api-path token]
  (str bot-url api-path "/" token))

(defn setup-webhook!
  "We provide a public HTTP endpoint, through which Telegram will
  provide the latest unprocessed messages from users; this way is
  usual for a remotely deployed web application, but can be quite
  tricky for a local development."
  [token bot-url api-path]
  (let [webhook-url (construct-webhook-url bot-url api-path token)]
    (log/info "Bot URL:" bot-url)
    (m-api/set-webhook token webhook-url)))

;; LONG-POLLING

(defonce ^:private *updates-channel (atom nil))

(defn- start-long-polling!
  [token upd-handler]
  (reset! *updates-channel
          (try
            (m-poll/start token upd-handler)
            (catch Exception _
              ;; will be processed later
              nil))))

(defn- not-polling?
  []
  (let [upd-chan @*updates-channel]
    (or (nil? upd-chan)
        (closed? upd-chan))))

(defn setup-long-polling!
  "We perform long-polling of server updates ourselves, which
  means a continuous calling of the 'getUpdates' Telegram Bot
  API HTTP endpoint and waiting to receive user messages sent
  to our bot over this time, then making the same call again;
  this way is fine for a local debugging/testing purposes."
  [token upd-handler]
  (m-api/set-webhook token "") ;; polling won't work if a webhook is set up

  (start-long-polling! token upd-handler)

  ;; TODO: Make this an async task that aims to "re-spawn" the long-polling.
  (Thread/sleep 1000) ;; await a bit...
  (when (not-polling?)
    (log/fatal "Fatal error during the long-polling setup")
    (System/exit 1)))

(defn stop-long-polling!
  []
  (m-poll/stop @*updates-channel))


;; Operations

(def ^:private base-url m-api/base-url)

(defn get-me
  [token]
  (let [url (str base-url token "/getMe")
        resp (http/get url {:as :json})]
    (get resp :body)))

(defn get-chat-members-count
  [token chat-id]
  (let [url (str base-url token "/getChatMembersCount")
        query {:chat_id chat-id}
        resp (http/get url {:content-type :json
                            :as :json
                            :query-params query})]
    (get-in resp [:body :result])))

;; NB: Morse does not support optional parameters (e.g. 'url') in 'answer-callback'.
(defn answer-callback-query
  "Sends an answer to an callback query"
  ([token callback-query-id]
   ;; NB: Notification 'text' is not specified => nothing will be shown to the user.
   (answer-callback-query token callback-query-id {}))
  ([token callback-query-id options]
   (let [url (str base-url token "/answerCallbackQuery")
         body (into {:callback_query_id callback-query-id} options)
         resp (http/post url {:content-type :json
                              :as :json
                              :form-params body})]
     (-> resp :body))))


;; Handlers

;; TODO: Morse improvement. Implement a Compojure 'let-routes'-like 'let-handler' macro.

(defn bot-chat-member-status-fn [handler-fn]
  (m-hlr/update-fn [:my_chat_member] handler-fn))

(defmacro bot-chat-member-status
  [bindings & body]
  `(bot-chat-member-status-fn (fn [~bindings] ~@body)))

(defn chat-member-status-fn [handler-fn]
  (m-hlr/update-fn [:chat_member] handler-fn))

(defmacro chat-member-status
  [bindings & body]
  `(chat-member-status-fn (fn [~bindings] ~@body)))
