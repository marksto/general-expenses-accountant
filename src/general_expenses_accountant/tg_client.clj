(ns general-expenses-accountant.tg-client
  "The Morse extension that provides:
   1) a conventional way to set up the updates
   2) missing Telegram Bot API methods
   3) missing update handlers

   NB: As Telegram Bot API documentation states,
       there are two ways to receive updates:
       - via WEBHOOK (should be set in PROD env)
       - via LONG-POLLING"
  (:require [clojure.core.async :refer [go-loop <! <!! timeout]]
            [clojure.core.async.impl.protocols :refer [closed?]]
            [clojure.tools.macro :as macro]
            [clojure.string :as str]

            [clj-http.client :as http] ;; comes with Morse
            [morse
             [api :as m-api]
             [handlers :as m-hlr]
             [polling :as m-poll]]
            [taoensso.timbre :as log]))

;; TODO: List of possible Morse improvements.
;;       1. Elude implicit and unnecessary library dependencies:
;;          - 'cheshire' — make it optional (depending on CP) + add ability to plug any JSON parser
;;          - 'clj-http' — the same as for 'cheshire', but with extra care for existing tests
;;          - 'clojure.tools.macro' — only a single fn is used => inline it in 'utils'
;;          - 'clj-stacktrace' — is it even used? no references within 'src'
;;       2. Improve the error logging:
;;          - surround all outgoing HTTP requests w/ 'try-catch' and log the exception (debug level)
;;          - on '::api/error' log the error itself (the response body ':description'), not just text
;;       3. Add explicit type hints in order to avoid unnecessary reflective calls:
;;          - Reflection warning, morse/api.clj:137:28 - reference to field getName can't be resolved.
;;          - Reflection warning, morse/api.clj:137:28 - call to method endsWith can't be resolved (target class is unknown).

(def ^:private base-url m-api/base-url)

(defn- do-get
  [url req]
  (try
    (http/get url req)
    (catch Exception e
      (log/debug e "HTTP GET request failed"))))

(defn- do-post
  [url req]
  (try
    (http/post url req)
    (catch Exception e
      (log/debug e "HTTP POST request failed"))))

;; UPDATES SETUP

;; - WEBHOOK

; Bot API methods ;; TODO: Move to Morse.

(defn set-webhook
  "Specifies a URL (or a fixed IP address) to receive incoming updates
   via an outgoing webhook. Returns 'True' on success."
  ([token webhook-url]
   (set-webhook token webhook-url {}))
  ([token webhook-url options]
   (let [url (str base-url token "/setWebhook")
         body (into {:url webhook-url} options)
         resp (do-post url {:content-type :json
                            :form-params body
                            :as :json})]
     (get resp :body))))

(defn get-webhook-info
  "Use this method to get current webhook status. On success, returns
   a 'WebhookInfo' object. If the bot uses long-polling ('getUpdates')
   to receive incoming updates, the 'url' field of the returned object
   will be empty."
  [token]
  (let [url (str base-url token "/getWebhookInfo")
        resp (do-get url {:as :json})]
    (get-in resp [:body :result])))

; project-specific

(defn- construct-webhook-url
  "Constructs webhook URL according to the Telegram recommendation."
  [bot-url api-path token]
  (str bot-url api-path "/" token))

(defn setup-webhook!
  "We provide a public HTTP endpoint, through which Telegram will
  provide the latest unprocessed messages from users; this way is
  usual for a remotely deployed web application, but can be quite
  tricky for a local development."
  [{:keys [token] :as _config} bot-url api-path]
  (let [webhook-url (construct-webhook-url bot-url api-path token)]
    (log/info "Bot URL:" bot-url)
    (set-webhook token webhook-url)))

;; - LONG-POLLING

; configuration

(def ^:private default-config
  {:await-async-time 1000
   :check-period 20000
   :restart-attempts 5
   :restart-period 10000
   :get-updates-opts {}})

(defn- complete
  [config]
  (merge default-config config))

; (re)store webhook

(defonce ^:private *webhook-info (atom nil))

(defn- save-the-current-webhook-info
  "Gets the current webhook info and, if there's a preconfigured webhook,
   saves one to be able, if possible, to restore it later."
  [token]
  (let [webhook-info (get-webhook-info token)]
    (when-not (empty? (:url webhook-info))
      (log/debug "Saving the webhook info:" webhook-info)
      (reset! *webhook-info webhook-info))))

(defn- try-restore-the-prior-webhook
  "Attempts to restore the prior webhook w/ the pre-saved webhook info.

   NB: The only case of automatic recovery that is not feasible is using
       a self-signed certificate (this data is not provided by Telegram).
       Thus, it requires you to manually reset the webhook in such cases."
  [token]
  (let [webhook-info @*webhook-info]
    (when (and (some? webhook-info)
               (not (true? (:has_custom_certificate webhook-info))))
      (log/debug "Restoring the webhook from the info:" webhook-info)
      (set-webhook token (:url webhook-info) webhook-info))))

; setup -> teardown

(defonce ^:private *updates-channel (atom nil))

(defn- start-long-polling!
  [{:keys [token get-updates-opts] :as _config} upd-handler]
  (reset! *updates-channel
          (try
            (log/info "Starting Telegram polling...") ;; TODO: Move this line inside the 'm-poll/start' fn.
            (m-poll/start token upd-handler get-updates-opts)
            (catch Exception e
              (log/warn e "Exception during the long-polling start process")
              ;; NB: We pass 'nil' intentionally, see the 'not-polling?' fn.
              nil))))

(defn- stop-long-polling!
  [{:keys [await-async-time] :as _config}]
  (m-poll/stop @*updates-channel)
  ;; NB: Waits a bit just for async operation to catch up.
  (<!! (timeout await-async-time)))

(defn- restart-long-polling!
  [config upd-handler]
  (stop-long-polling! config)
  (start-long-polling! config upd-handler))

(defn- not-polling?
  []
  (let [upd-chan @*updates-channel]
    (or (nil? upd-chan)
        (closed? upd-chan))))

(defn- run-status-checker!
  "An async task that aims to restart the dead long-polling."
  [{:keys [await-async-time ;; the initial async op timeout
           check-period restart-attempts restart-period]
    :as config} upd-handler]
  (go-loop [wait-ms await-async-time
            attempt restart-attempts]
    (log/trace "Waiting for" (/ wait-ms 1000) "sec...")
    (<! (timeout wait-ms))
    (if (not-polling?)
      (do
        (log/trace "Checking the status... NOT POLLING!")
        (if (zero? attempt)
          (do
            (log/fatal "The long-polling cannot be continued")
            (System/exit 4))
          (do
            (log/trace (format "Trying to restart #%d of %d"
                               (- (inc restart-attempts) attempt)
                               restart-attempts))
            (restart-long-polling! config upd-handler)
            (recur restart-period (dec attempt)))))
      (do
        (log/trace "Checking the status... polling [OK]")
        ;; NB: Reset the parameters to their initial values.
        (recur check-period restart-attempts)))))

(defn setup-long-polling!
  "We perform long-polling of server updates ourselves, which
  means a continuous calling of the 'getUpdates' Telegram Bot
  API HTTP endpoint and waiting to receive user messages sent
  to our bot over this time, then making the same call again;
  this way is fine for a local debugging/testing purposes."
  [{:keys [token] :as config} upd-handler]
  (let [comp-config (complete config)]
    ;; NB: The long-polling won't work if a webhook is set up.
    ;;     First, get the current webhook info and, if there's
    ;;     any, save one to be able to restore it later. If no
    ;;     webhook was preconfigured, do nothing.
    (when (save-the-current-webhook-info token)
      (set-webhook token ""))
    (start-long-polling! comp-config upd-handler)
    (run-status-checker! comp-config upd-handler)))

(defn teardown-long-polling!
  [{:keys [token] :as config}]
  (let [comp-config (complete config)]
    (stop-long-polling! comp-config)
    (try-restore-the-prior-webhook token)))


;; BOT API METHODS ;; TODO: Move to Morse.

;; TODO: Morse improvement. Check the resp's ':ok' to be 'true' before
;;       getting its 'body'. The approach for the 'getUpdates' and the
;;       other Bot API methods here may be different. From the Bot API
;;       documentation:

;; The response contains a JSON object, which always has a Boolean field 'ok'
;; and may have an optional String field 'description' with a human-readable
;; description of the result.
;;
;; - If 'ok' equals true, the request was successful
;;   and the result of the query can be found in the 'result' field.
;;
;; - In case of an unsuccessful request, 'ok' equals false
;;   and the error is explained in the 'description'.
;;   TODO: This information should be passed on (instead of plain '::error')
;;         in the 'm-api/get-updates-async' fn.
;;   TODO: All other methods should normally account for this checking ':ok'
;;         before returning the ':result'.
;;
;; - An Integer 'error_code' field is also returned,
;;   but its contents are subject to change in the future.
;;
;; - Some errors may also have an optional field 'parameters' of the type
;;   'ResponseParameters', which can help to automatically handle the error.
;;   TODO: Introduce the auto-retry strategies for some/all of these cases.

; ResponseParameters
;
; Contains information about why a request was unsuccessful.
;
; Field             	Type    	Description
; migrate_to_chat_id 	Integer 	Optional. The group has been migrated to
;                   	        	a supergroup with the specified identifier.
; retry_after       	Integer 	Optional. In case of exceeding flood control,
;                   	        	the number of seconds left to wait before
;                   	        	the request can be repeated.

(defn get-me
  "Returns basic information about the bot in form of a 'User' object."
  [token]
  (let [url (str base-url token "/getMe")
        resp (do-get url {:as :json})]
    (get-in resp [:body :result])))

(defn get-chat-member-count
  "Gets the number of members in a chat. Returns 'Int' on success."
  [token chat-id]
  (let [url (str base-url token "/getChatMemberCount")
        query {:chat_id chat-id}
        resp (do-get url {:content-type :json
                          :query-params query
                          :as :json})]
    (get-in resp [:body :result])))

;; NB: Morse does not support all available optional parameters, e.g. 'url'
;;     and 'cache_time', in callback query answers. Thus, this fn interface
;;     was generalized.
(defn answer-callback-query
  "Sends an answer to a callback query. On success, 'True' is returned.
   NB: After the user presses a callback button, Telegram clients will display
       a progress bar until you call 'answer-callback-query'. It is, therefore,
       necessary to react by calling 'answer-callback-query', even if there is
       no need to notify the user."
  ([token callback-query-id]
   ;; NB: The 'text' is not specified => nothing will be shown to the user.
   (answer-callback-query token callback-query-id {}))
  ([token callback-query-id {:keys [text] :as options}] ;; TODO: Question Otann about this approach.
   (let [url (str base-url token "/answerCallbackQuery")
         body (into {:callback_query_id callback-query-id} options)
         resp (do-post url {:content-type :json
                            :form-params body
                            :as :json})]
     (get resp :body))))


;; UPDATE HANDLERS ;; TODO: Move to Morse.

(defn create-complex-handler
  "Creates an update handler fn by combining several 'handlers' into one, and,
   as well, applying the 'ctx-extractor' to their common argument (an incoming
   update) and merging its return value w/ the original value of the argument
   before calling 'handlers'."
  [ctx-extractor & handlers]
  (fn [upd]
    (let [upd-type (first (second (vec upd)))
          ctx (ctx-extractor upd upd-type)
          upd (update upd upd-type merge ctx)]
      (apply m-hlr/handling upd handlers))))

(defmacro defhandler
  "A version of the Morse's 'defhandler' macro that uses the 'ctx-extractor' fn
   to form a context for an incoming update before handling it.

   This helps to extract the common business logic that forms an update context,
   execute it only once for an incoming update, and then use the context across
   all 'handlers'."
  [name ctx-extractor & handlers]
  (let [[name handlers] (macro/name-with-attributes name handlers)]
    `(def ~name (create-complex-handler ~ctx-extractor ~@handlers))))


(defn get-commands
  "Retrieves a list of bot commands from the update's message text, if any."
  [update]
  (letfn [(filter-cmds [entities]
            (filter #(= "bot_command" (:type %)) entities))
          (map-to-name [bot-cmds]
            (let [get-name (fn [txt cmd]
                             (-> txt
                                 (subs (inc (:offset cmd)) ;; drop '/'
                                       (+ (:offset cmd) (:length cmd)))
                                 (str/split #"@")
                                 (first)))
                  txt (-> update :message :text)]
              (map (partial get-name txt) bot-cmds)))]
    (some-> update
            :message
            :entities
            filter-cmds
            map-to-name)))

(defn command-fn
  "Creates an update handler for the bot command with the specified 'name',
   using the passed 'handler-fn' w/ the update's 'message' as an argument."
  [name handler-fn]
  (fn [update]
    (when (some #{name} (get-commands update))
      (handler-fn (:message update)))))

(defmacro command
  "Creates an update handler for the bot command with the specified 'name',
   using the message handler fn in a form of (fn ['bindings'] 'body')."
  [name bindings & body]
  `(command-fn ~name (fn [~bindings] ~@body)))


(defn bot-chat-member-status-fn
  "Creates an update handler for the bot's own 'ChatMemberUpdated' events,
   using the passed 'handler-fn' w/ the update's 'my_chat_member' as an
   argument."
  [handler-fn]
  (m-hlr/update-fn [:my_chat_member] handler-fn))

(defmacro bot-chat-member-status
  "Creates an update handler for the bot's own 'ChatMemberUpdated' events,
   using the event handler fn in a form of (fn ['bindings'] 'body')."
  [bindings & body]
  `(bot-chat-member-status-fn (fn [~bindings] ~@body)))

(defn chat-member-status-fn
  "Creates an update handler for the non-bot's 'ChatMemberUpdated' events,
   using the passed 'handler-fn' w/ the update's 'chat_member' as an
   argument."
  [handler-fn]
  (m-hlr/update-fn [:chat_member] handler-fn))

(defmacro chat-member-status
  "Creates an update handler for the non-bot's 'ChatMemberUpdated' events,
   using the event handler fn in a form of (fn ['bindings'] 'body')."
  [bindings & body]
  `(chat-member-status-fn (fn [~bindings] ~@body)))
