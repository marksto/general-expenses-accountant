(ns general-expenses-accountant.tg-bot-api
  "Additional available Telegram Bot API types & methods, as well as some auxiliary checks"
  (:require [clojure.string :as str]))

;; TODO: Define the most used "AVAILABLE TYPES" as CLJC records w/ type hints.

;; AUXILIARY CHECKS

; User

(def mention-types #{:by-name :by-full-name :by-username})

(defn get-user-mention-text
  "Handy fn for mentioning users in a message text.
   NB: Must be used with the formatting/parse mode."
  ([user]
   ;; NB: This is the default option since the User always have a 'first_name' and an 'id'.
   (get-user-mention-text user :by-name))
  ;; TODO: Have to escape the user names?
  ([{user-id :id first-name :first_name ?last-name :last_name ?username :username :as user}
    mention-type]
   {:pre [(contains? mention-types mention-type)]}
   (case mention-type
     :by-name (str "[" first-name "](tg://user?id=" user-id ")")
     :by-full-name (if (some? ?last-name)
                     (str "[" first-name " " ?last-name "](tg://user?id=" user-id ")")
                     (get-user-mention-text user))
     :by-username (if (some? ?username)
                    (str "@" ?username)
                    (get-user-mention-text user)))))

; Chat

(defn is-private?
  [{type :type :as _chat}]
  (= type "private"))

(defn is-group?
  [{type :type :as _chat}]
  (contains? #{"group" "supergroup"} type))

; Message

(defn is-reply-to?
  [msg-id {{original-msg-id :message_id :as original-msg} :reply_to_message
           :as _message}]
  (and (some? msg-id)
       (some? original-msg)
       (= msg-id original-msg-id)))

; Chat Member

(def active-chat-member-statuses
  #{"member" "administrator" "creator" "restricted"})

(def inactive-chat-member-statuses
  #{"left" "kicked"})

(defn has-joined?
  [{{old-status :status :as old-chat-member} :old_chat_member
    {new-status :status :as new-chat-member} :new_chat_member
    :as _chat-member-updated}]
  (and (some? old-chat-member)
       (some? new-chat-member)
       (contains? inactive-chat-member-statuses old-status)
       (contains? active-chat-member-statuses new-status)))

(defn has-left?
  [{{old-status :status :as old-chat-member} :old_chat_member
    {new-status :status :as new-chat-member} :new_chat_member
    :as _chat-member-updated}]
  (and (some? old-chat-member)
       (some? new-chat-member)
       (contains? active-chat-member-statuses old-status)
       (contains? inactive-chat-member-statuses new-status)))


;; AVAILABLE METHODS

(defn build-immediate-response
  "From the Telegram Bot API docs: \"If you're using webhooks, you can perform
   a request to the Bot API while sending an answer to the webhook ... Specify
   the method to be invoked in the 'method' parameter of the request.\".

   An example of an immediate request that the Telegram API is able to handle:

   {:method \"sendMessage\",
    :chat_id chat-id,
    :reply_to_message_id msg-id,
    :text \"An ordinary reply message text.\"}

   This technique helps to reduce the number of requests to the Bot API server
   and, as a consequence, reduces the response time of the client's UI."
  [method-name request]
  (merge {:method method-name} request))

; /sendMessage
;
; Use this method to send text messages. On success, the sent Message is returned.
; (Links tg://user?id=<user_id> can be used to mention a user by their ID without using a username.)
;
; Parameter                 	Type                  	Required	Description
; chat_id                   	Integer or String     	Yes     	Unique identifier for the target chat or username of
;                           	                      	        	the target channel (in the format @channelusername)
; text                      	String                	Yes     	Text of the message to be sent,
;                           	                      	        	1-4096 characters after entities parsing
; parse_mode                	String                	Optional	Mode for parsing entities in the message text.
;                           	                      	        	See formatting options for more details.
; entities                  	Array of              	Optional	List of special entities that appear in message text,
;                           	MessageEntity         	        	which can be specified instead of parse_mode
; disable_web_page_preview  	Boolean               	Optional	Disables link previews for links in this message
; disable_notification      	Boolean               	Optional	Sends the message silently. Users will receive
;                           	                      	        	a notification with no sound.
; reply_to_message_id       	Integer               	Optional	If the message is a reply, ID of the original message
; allow_sending_without_reply	Boolean               	Optional	Pass True, if the message should be sent even if
;                           	                      	        	the specified replied-to message is not found
; reply_markup              	InlineKeyboardMarkup or	Optional	Additional interface options.
;                           	ReplyKeyboardMarkup  or	         	A JSON-serialized object for an inline keyboard,
;                           	ReplyKeyboardRemove  or	         	custom reply keyboard, instructions to remove
;                           	ForceReply             	         	reply keyboard or to force a reply from the user.
(defn build-message-options
  [{:keys [parse-mode no-link-preview send-silently original-msg-id reply-anyway reply-markup] :as _options}]
  (cond-> {}
          (some? parse-mode) (assoc :parse_mode parse-mode)
          (true? no-link-preview) (assoc :disable_web_page_preview true)
          (true? send-silently) (assoc :disable_notification true)
          (some? original-msg-id) (assoc :reply_to_message_id original-msg-id)
          (true? reply-anyway) (assoc :allow_sending_without_reply true)
          (some? reply-markup) (assoc :reply_markup reply-markup)))

; ReplyKeyboardMarkup
;
; This object represents a custom keyboard with reply options (see Introduction to bots for details and examples).
;
; Field             	Type              	Description
; keyboard          	Array of Array of 	Array of button rows, each represented by an Array of KeyboardButton objects
;                   	KeyboardButton
; resize_keyboard   	Boolean           	Optional. Requests clients to resize the keyboard vertically for optimal fit
;                   	                  	(e.g., make the keyboard smaller if there are just two rows of buttons).
;                   	                  	Defaults to false, in which case the custom keyboard is always of the same
;                   	                  	height as the app's standard keyboard.
; one_time_keyboard 	Boolean           	Optional. Requests clients to hide the keyboard as soon as it's been used.
;                   	                  	The keyboard will still be available, but clients will automatically display
;                   	                  	the usual letter-keyboard in the chat – the user can press a special button
;                   	                  	in the input field to see the custom keyboard again. Defaults to false.
; selective         	Boolean           	Optional. Use this parameter to show the keyboard to specific users only.
;                   	                  	Targets:
;                   	                  	1) users that are @mentioned in the text of the Message object;
;                   	                  	2) if the bot's message is a reply (has reply_to_message_id),
;                   	                  	   sender of the original message.
(def default-reply-keyboard
  {:keyboard []
   :resize_keyboard true})

(defn build-reply-keyboard
  ([]
   default-reply-keyboard)
  ([button-rows]
   (update default-reply-keyboard :keyboard into button-rows))
  ([button-rows {:keys [resize one-time selective] :as _options}]
   (cond-> (build-reply-keyboard button-rows)
           (true? resize) (assoc :resize_keyboard true)
           (true? one-time) (assoc :one_time_keyboard true)
           (true? selective) (assoc :selective true))))

; KeyboardButton
;
; This object represents one button of the reply keyboard.
; For simple text buttons String can be used instead of this object to specify text of the button.

; ReplyKeyboardRemove
;
; Upon receiving a message with this object, Telegram clients will remove the current custom keyboard and display
; the default letter-keyboard. By default, custom keyboards are displayed until a new keyboard is sent by a bot.
; An exception is made for one-time keyboards that are hidden immediately after the user presses a button
; (see ReplyKeyboardMarkup).
;
; Field           	Type    	Description
; remove_keyboard 	True    	Requests clients to remove the custom keyboard (user will not be able to summon
;                 	        	this keyboard; if you want to hide the keyboard from sight but keep it accessible,
;                 	        	use one_time_keyboard in ReplyKeyboardMarkup)
; selective       	Boolean 	Optional. Use this parameter to remove the keyboard for specific users only.
;                 	        	Targets:
;                 	        	1) users that are @mentioned in the text of the Message object;
;                 	        	2) if the bot's message is a reply (has reply_to_message_id),
;                 	        	   sender of the original message.
(def default-remove-keyboard
  {:remove_keyboard true})

(defn build-remove-keyboard
  ([]
   default-remove-keyboard)
  ([{:keys [selective] :as _options}]
   (cond-> default-remove-keyboard
           (true? selective) (assoc :selective true))))

; InlineKeyboardMarkup
;
; This object represents an inline keyboard that appears right next to the message it belongs to.
;
; Field           	Type                	Description
; inline_keyboard 	Array of Array of   	Array of button rows, each represented by an Array of
;                 	InlineKeyboardButton	InlineKeyboardButton objects
(def default-inline-keyboard
  {:inline_keyboard []})

(defn build-inline-keyboard
  ([]
   default-inline-keyboard)
  ([button-rows]
   (update default-inline-keyboard :inline_keyboard into button-rows)))

; InlineKeyboardButton
;
; This object represents one button of an inline keyboard. You must use exactly one of the optional fields.
;
; Field                           	Type        	Description
; text                            	String      	Label text on the button
; url                             	String      	Optional. HTTP or tg:// url to be opened when button is pressed
; login_url                       	LoginUrl    	Optional. An HTTP URL used to automatically authorize the user.
;                                 	            	Can be used as a replacement for the Telegram Login Widget.
; callback_data                   	String      	Optional. Data to be sent in a callback query to the bot
;                                 	            	when button is pressed, 1-64 bytes
; switch_inline_query             	String      	Optional. If set, pressing the button will prompt the user to select
;                                 	            	one of their chats, open that chat and insert the bot's username and
;                                 	            	the specified inline query in the input field. Can be empty, in which
;                                 	            	case just the bot's username will be inserted.
;                                 	            	NOTE: This offers an easy way for users to start using your bot
;                                 	            	in inline mode when they are currently in a private chat with it.
;                                 	            	Especially useful when combined with 'switch_pm…' actions –
;                                 	            	in this case the user will be automatically returned to the chat
;                                 	            	they switched from, skipping the chat selection screen.
; switch_inline_query_current_chat	String      	Optional. If set, pressing the button will insert the bot's username
;                                 	            	and the specified inline query in the current chat's input field.
;                                 	            	Can be empty, in which case only the bot's username will be inserted.
;                                 	            	This offers a quick way for the user to open your bot in inline mode
;                                 	            	in the same chat – good for selecting something from multiple options.
; callback_game                   	CallbackGame	Optional. Description of the game that will be launched when the user
;                                 	            	presses the button.
;                                 	            	NOTE: This type of button must be the 1st button in the first row.
; pay                             	Boolean     	Optional. Specify True, to send a Pay button.
;                                 	            	NOTE: This type of button must be the 1st button in the first row.
(def inline-kbd-btn-types
  #{:url :login_url :callback_data :switch_inline_query :switch_inline_query_current_chat :callback_game :pay})

(defn build-inline-kbd-btn
  [text type-specific-key type-specific-val]
  {:pre [(keyword? type-specific-key)
         (string? type-specific-val)]}
  (let [field (-> type-specific-key
                  name
                  (str/replace "-" "_")
                  keyword)]
    (assert (contains? inline-kbd-btn-types field))
    {:text text
     field type-specific-val}))

; ForceReply
;
; Upon receiving a message with this object, Telegram clients will display a reply interface to the user
; (act as if the user has selected the bot's message and tapped 'Reply'). This can be extremely useful
; if you want to create user-friendly step-by-step interfaces without having to sacrifice privacy mode.
;
; Field         	Type    	Description
; force_reply   	True    	Shows reply interface to the user, as if they manually selected
;               	        	the bot's message and tapped 'Reply'
; selective     	Boolean 	Optional. Use this parameter to force reply from specific users only.
;               	        	Targets:
;               	        	1) users that are @mentioned in the text of the Message object;
;               	        	2) if the bot's message is a reply (has reply_to_message_id),
;               	        	   sender of the original message.
(def default-force-reply
  {:force_reply true})

(defn build-force-reply
  ([]
   default-force-reply)
  ([{:keys [selective] :as _options}]
   (cond-> default-force-reply
           (true? selective) (assoc :selective true))))

; Reply Markups
(def reply-markup-builders-by-type
  {:custom-keyboard build-reply-keyboard
   :remove-keyboard build-remove-keyboard
   :inline-keyboard build-inline-keyboard
   :force-reply build-force-reply})

(defn build-reply-markup
  [type & args]
  (apply (get reply-markup-builders-by-type type) args))


(comment
  (build-message-options
    {:reply-markup (build-reply-markup :inline-keyboard
                                       [[(build-inline-kbd-btn "Callback" :callback_data "<some_data>")]])})
  (build-message-options
    {:reply-markup (build-reply-markup :custom-keyboard [["Add expense"]])})

  (build-message-options {:reply-markup (build-reply-markup :remove-keyboard {:selective true})})

  (build-message-options {:reply-markup (build-reply-markup :force-reply {:selective true})}))


; /sendChatAction
;
; Use this method when you need to tell the user that something is happening on the bot's side. The status is set
; for 5 seconds or less (when a message arrives from your bot, Telegram clients clear its typing status). Returns
; `True` on success.
;
; We only recommend using this method when a response from the bot will take a noticeable amount of time to arrive.
;
; Parameter 	Type            	Required	Description
; chat_id   	Integer or String	Yes     	Unique identifier for the target chat or username of the target channel
;           	                	        	(in the format @channelusername)
; action    	String          	Yes     	Type of action to broadcast. Choose one, depending on what the user is
;           	                	        	about to receive:
;           	                	        	- `typing` for text messages,
;           	                	        	- `upload_photo` for photos,
;           	                	        	- `record_video` or `upload_video` for videos,
;           	                	        	- `record_voice` or `upload_voice` for voice notes,
;           	                	        	- `upload_document` for general files,
;           	                	        	- `find_location` for location data,
;           	                	        	- `record_video_note` or `upload_video_note` for video notes.

;; TODO: Implement a specification.
