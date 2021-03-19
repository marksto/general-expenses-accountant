(ns general-expenses-accountant.core
  (:require
    [cheshire.core :as json]
    [morse.api :as morse]
    ;[taoensso.timbre :as log]

    [general-expenses-accountant.config :as config]))

(defn- log
  [str]
  (println str))


;; Business Logic

(defn stub-answer
  []
  "Yo!")

(defn- handle-private-message
  [message]
  (let [chat-id (-> message :chat :id)]
    (morse/send-text
      (config/get-prop :bot-api-token)
      chat-id
      (stub-answer))))

(defn- handle-channel-post
  [post]
  (let [chat-id (-> post :chat :id)]
    (morse/send-text
      (config/get-prop :bot-api-token)
      chat-id
      (stub-answer))))


;; API

(defn bot-api
  [{message :message
    post :channel_post
    :as full-msg-body}]
  ;; TODO: Re-write with proper logger.
  (log (json/generate-string full-msg-body))

  (if (not (nil? message))
    (handle-private-message message))
  (if (not (nil? post))
    (handle-channel-post post))

  {:status 200
   :body "Bot API request processed"})
