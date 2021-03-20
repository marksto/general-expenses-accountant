(ns general-expenses-accountant.core
  (:require
    [cheshire.core :as json]
    [morse.api :as morse]
    [taoensso.timbre :as log]

    [general-expenses-accountant.config :as config]))

;; Business Logic

(defn stub-answer
  []
  "Yo!")

(defn- handle-private-message
  [message]
  (let [chat-id (-> message :chat :id)]
    ;; TODO: Make this call asynchronous.
    (morse/send-text
      (config/get-prop :bot-api-token)
      chat-id
      (stub-answer))))

(defn- handle-channel-post
  [post]
  (let [chat-id (-> post :chat :id)]
    ;; TODO: Make this call asynchronous.
    (morse/send-text
      (config/get-prop :bot-api-token)
      chat-id
      (stub-answer))))


;; API

(defn bot-api
  [{message :message
    post :channel_post
    :as full-msg-body}]
  (log/debug (json/generate-string full-msg-body))
  (let [tg-resp-body
        (cond
          (not (nil? message))
          (handle-private-message message)

          (not (nil? post))
          (handle-channel-post post))]
    (log/debug tg-resp-body)
    tg-resp-body))
