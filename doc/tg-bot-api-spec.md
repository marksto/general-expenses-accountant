
## /getMe
  
A simple method for testing your bot's auth token. Requires no parameters. 
Returns basic information about the bot in form of a `User` object.

## /getChatMembersCount

Use this method to get the number of members in a chat. Returns `Int` on success.

```
Parameter 	Type              	Required 	Description
chat_id   	Integer or String 	Yes      	Unique identifier for the target chat or username of the target 
          	                  	         	supergroup or channel (in the format @channelusername)
```

## /sendMessage

Use this method to send text messages. On success, the sent `Message` is returned.
(Links `tg://user?id=<user_id>` can be used to mention a user by their ID without using a username.)

```
Parameter                 	Type                	Required	Description
chat_id                   	Integer or String   	Yes     	Unique identifier for the target chat or username of
                          	                    	        	the target channel (in the format @channelusername)
text                      	String              	Yes     	Text of the message to be sent,
                          	                    	        	1-4096 characters after entities parsing
parse_mode                	String              	Optional	Mode for parsing entities in the message text.
                          	                    	        	See formatting options for more details.
entities                  	Array of            	Optional	List of special entities that appear in message text,
                          	MessageEntity       	        	which can be specified instead of parse_mode
disable_web_page_preview  	Boolean             	Optional	Disables link previews for links in this message
disable_notification      	Boolean             	Optional	Sends the message silently. Users will receive
                          	                    	        	a notification with no sound.
reply_to_message_id       	Integer             	Optional	If the message is a reply, ID of the original message
allow_sending_without_reply	Boolean             	Optional	Pass True, if the message should be sent even if
                          	                    	        	the specified replied-to message is not found
reply_markup              	InlineKeyboardMarkup or	Optional	Additional interface options.
                          	ReplyKeyboardMarkup  or	         	A JSON-serialized object for an inline keyboard,
                          	ReplyKeyboardRemove  or	         	custom reply keyboard, instructions to remove
                          	ForceReply             	         	reply keyboard or to force a reply from the user.
```

### ReplyKeyboardMarkup

This object represents a custom keyboard with reply options (see Introduction to bots for details and examples).

```
Field             	Type            	Description
keyboard          	Array of Array of	Array of button rows, each represented by an Array of KeyboardButton objects
                  	KeyboardButton   	
resize_keyboard   	Boolean         	Optional. Requests clients to resize the keyboard vertically for optimal fit
                  	                	(e.g., make the keyboard smaller if there are just two rows of buttons).
                  	                	Defaults to false, in which case the custom keyboard is always of the same
                  	                	height as the app's standard keyboard.
one_time_keyboard 	Boolean         	Optional. Requests clients to hide the keyboard as soon as it's been used.
                  	                	The keyboard will still be available, but clients will automatically display
                  	                	the usual letter-keyboard in the chat – the user can press a special button
                  	                	in the input field to see the custom keyboard again. Defaults to false.
selective         	Boolean         	Optional. Use this parameter to show the keyboard to specific users only.
                  	                	Targets:
                  	                	1) users that are @mentioned in the text of the Message object
                  	                	2) if the bot's message is a reply (has reply_to_message_id),
                  	                	   sender of the original message.
```

#### KeyboardButton

This object represents one button of the reply keyboard. For simple text buttons String can be used instead of
this object to specify text of the button. Optional fields request_contact, request_location, and request_poll
are mutually exclusive.

```
Field           	Type                  	Description
text            	String                	Text of the button. If none of the optional fields are used,
                	                      	it will be sent as a message when the button is pressed
request_contact 	Boolean               	Optional. If True, the user's phone number will be sent as a contact
                	                      	when the button is pressed. Available in private chats only
request_location	Boolean               	Optional. If True, the user's current location will be sent
                	                      	when the button is pressed. Available in private chats only
request_poll    	KeyboardButtonPollType	Optional. If specified, the user will be asked to create a poll to be sent
                	                      	when the button is pressed. Available in private chats only
```

### ReplyKeyboardRemove

Upon receiving a message with this object, Telegram clients will remove the current custom keyboard and display
the default letter-keyboard. By default, custom keyboards are displayed until a new keyboard is sent by a bot.
An exception is made for one-time keyboards that are hidden immediately after the user presses a button
(see ReplyKeyboardMarkup).

```
Field           	Type    	Description
remove_keyboard 	True    	Requests clients to remove the custom keyboard (user will not be able to summon
                	        	this keyboardif you want to hide the keyboard from sight but keep it accessible,
                	        	use one_time_keyboard in ReplyKeyboardMarkup)
selective       	Boolean 	Optional. Use this parameter to remove the keyboard for specific users only.
                	        	Targets:
                	        	1) users that are @mentioned in the text of the Message object
                	        	2) if the bot's message is a reply (has reply_to_message_id),
                	        	   sender of the original message.
```

### InlineKeyboardMarkup

This object represents an inline keyboard that appears right next to the message it belongs to.

```
Field           	Type                	Description
inline_keyboard 	Array of Array of   	Array of button rows, each represented by an Array of
                	InlineKeyboardButton	InlineKeyboardButton objects
```

#### InlineKeyboardButton

This object represents one button of an inline keyboard. You must use exactly one of the optional fields.

```
Field                           	Type        	Description
text                            	String      	Label text on the button
url                             	String      	Optional. HTTP or tg:// url to be opened when button is pressed
login_url                       	LoginUrl    	Optional. An HTTP URL used to automatically authorize the user.
                                	            	Can be used as a replacement for the Telegram Login Widget.
callback_data                   	String      	Optional. Data to be sent in a callback query to the bot
                                	            	when button is pressed, 1-64 bytes
switch_inline_query             	String      	Optional. If set, pressing the button will prompt the user to select
                                	            	one of their chats, open that chat and insert the bot's username and
                                	            	the specified inline query in the input field. Can be empty, in which
                                	            	case just the bot's username will be inserted.
                                	            	NOTE: This offers an easy way for users to start using your bot
                                	            	in inline mode when they are currently in a private chat with it.
                                	            	Especially useful when combined with 'switch_pm…' actions –
                                	            	in this case the user will be automatically returned to the chat
                                	            	they switched from, skipping the chat selection screen.
switch_inline_query_current_chat	String      	Optional. If set, pressing the button will insert the bot's username
                                	            	and the specified inline query in the current chat's input field.
                                	            	Can be empty, in which case only the bot's username will be inserted.
                                	            	This offers a quick way for the user to open your bot in inline mode
                                	            	in the same chat – good for selecting something from multiple options.
callback_game                   	CallbackGame	Optional. Description of the game that will be launched when the user
                                	            	presses the button.
                                	            	NOTE: This type of button must be the 1st button in the first row.
pay                             	Boolean     	Optional. Specify True, to send a Pay button.
                                	            	NOTE: This type of button must be the 1st button in the first row.
```

### ForceReply

Upon receiving a message with this object, Telegram clients will display a reply interface to the user
(act as if the user has selected the bot's message and tapped 'Reply'). This can be extremely useful
if you want to create user-friendly step-by-step interfaces without having to sacrifice privacy mode.

```
Field         	Type    	Description
force_reply   	True    	Shows reply interface to the user, as if they manually selected
              	        	the bot's message and tapped 'Reply'
selective     	Boolean 	Optional. Use this parameter to force reply from specific users only.
              	        	Targets:
              	        	1) users that are @mentioned in the text of the Message object
              	        	2) if the bot's message is a reply (has reply_to_message_id),
              	        	   sender of the original message.
```

## /sendChatAction

Use this method when you need to tell the user that something is happening on the bot's side. The status is set 
for 5 seconds or less (when a message arrives from your bot, Telegram clients clear its typing status). Returns 
`True` on success.

    Ex.: The ImageBot needs some time to process a request and upload the image. Instead of sending a text message 
         along the lines of “Retrieving image, please wait…”, the bot may use sendChatAction with action = upload_photo. 
         The user will see a “sending photo” status for the bot.

We only recommend using this method when a response from the bot will take a noticeable amount of time to arrive.

```
Parameter	Type            	Required	Description
chat_id 	Integer or String	Yes     	Unique identifier for the target chat or username of the target channel 
        	                	        	(in the format @channelusername)
action  	String          	Yes     	Type of action to broadcast. Choose one, depending on what the user is 
        	                	        	about to receive: 
        	                	        	- `typing` for text messages, 
        	                	        	- `upload_photo` for photos, 
        	                	        	- `record_video` or `upload_video` for videos, 
        	                	        	- `record_voice` or `upload_voice` for voice notes, 
        	                	        	- `upload_document` for general files, 
        	                	        	- `find_location` for location data, 
        	                	        	- `record_video_note` or `upload_video_note` for video notes.
```

## /setMyCommands

;; TODO: Describe.
