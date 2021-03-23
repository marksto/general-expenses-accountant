(ns general-expenses-accountant.core
  (:require [morse
             [api :as m-api]
             [handlers :as m-hlr]]
            [taoensso.timbre :as log]

            [general-expenses-accountant.config :as config]))

;; Helpers

(defn- token
  []
  (config/get-prop :bot-api-token))

;; Business Logic

(defn- stub-answer
  []
  "Yo!")

(defn- handle-private-message
  [message]
  (let [chat-id (-> message :chat :id)]
    ;; TODO: Make this call asynchronous?
    (m-api/send-text
      (token)
      chat-id
      (stub-answer))))

(defn- handle-channel-post
  [post]
  (let [chat-id (-> post :chat :id)]
    ;; TODO: Make this call asynchronous?
    (m-api/send-text
      (token)
      chat-id
      (stub-answer))))


;; API

;(defn bot-api
;  [{message :message
;    post :channel_post
;    :as full-msg-body}]
;  (log/debug (json/generate-string full-msg-body))
;  (let [tg-resp-body
;        (cond
;          (not (nil? message))
;          (handle-private-message message)
;
;          (not (nil? post))
;          (handle-channel-post post))]
;    (log/debug tg-resp-body)
;    tg-resp-body))

(m-hlr/defhandler
  bot-api
  ; Each bot has to handle /start and /help commands.
  (m-hlr/command-fn
    "start"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Joined new chat:" chat)
      (m-api/send-text token chat-id "Welcome!")))

  (m-hlr/command-fn
    "help"
    (fn [{{chat-id :id :as chat} :chat :as _message}]
      (log/debug "Help requested in chat:" chat)
      (m-api/send-text token chat-id "Help is on the way!")))

  ;; TODO: Re-map an old API call delegation logic here.

  ; A "match-all catch-through" case:
  (m-hlr/message-fn
    (fn [{{chat-id :id} :chat :as message}]
      (log/debug "Received message:" message)
      (m-api/send-text token chat-id "I don't do a whole lot... yet."))))
