
# Deep Links

Most of the links can be found here:
https://github.com/DrKLO/Telegram/blob/master/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java

---

tg:resolve

tg://resolve

    domain (username)
    start (botUser)
    startgroup (botChat)
    game (game)
    post (messageId)
    thread (messageId)
    comment (messageId)

if domain == `telegrampassport`:

    bot_id
    scope
    public_key
    callback_url
    nonce (auth)
    payload (auth)
    scope (auth)

---

tg:join

tg://join

    inivite (group)

---

tg:addstickers

tg://addstickers

    set (sticker)

---

tg:msg

tg:share

tg://msg

tg://share

tg://msg_url

    url (message)
    text (message)

---

tg:confirmphone

tg://confirmphone

    phone
    hash

---

tg:passport
tg:secureid

tg://passport

    scope
    nonce
    payload
    bot_id
    public_key
    callback_url

---

tg:proxy

tg://proxy

tg:socks

tg://socks

    server (address)
    port (port)
    user (user)
    pass (password)
    secret (secret)

---

tg://need_update_for_some_feature

tg://some_unsupported_feature

---

tg://user

    id

(Bot API only)

---

tg://share_game_score

tg://gshare (?)

    hash

---

tg:// filename (?)

    filename = dc_id + _ + document_id (?)
    filename = volume_id + _ + local_id + . + jpg (?)
    filename = md5(url) + . + extension (?)
    filename = "" (?)
    filename = dc_id + _ + document_id + _ + document_version + extension (?)

    id (document id)
    hash (access hash)
    dc (dc id)
    size (size)
    mime (mime type)
    name (document file name)

---

tg:bg

tg://bg

    slug (wallpaper)
    mode (blur+motion)
    color
    bg_color
    rotation
    intensity

---

tg://search_hashtag

    hashtag

(used internally by Telegram Web/Telegram React, you can use it by editing a href)

---

tg://bot_command

    command
    bot

(used internally by Telegram Web/Telegram React, you can use it by editing a href)

---

tg://unsafe_url

    url

(used internally by Telegram Web, you can use it by editing a href)

---

tg:setlanguage

tg://setlanguage

    lang

---

tg://statsrefresh

(something related to getStatsURL, probably not implemented yet)

---

tg:openmessage

tg://openmessage

    user_id
    chat_id
    message_id

(used internally by Android Stock (and fork), do not use, use tg://privatepost)

---

tg:privatepost

tg://privatepost

    channel (channelId)
    post (messageId)
    thread (messageId)
    comment (messageId)

---

tg:addtheme

tg://addtheme

    slug

---

tg:login

tg://login

    token
    code

---

tg:settings

tg://settings

    themes
    devices
    folders
    language
    change_number

---

tg:calllog

tg://calllog

---

tg:call

tg://call

    format
    name
    phone

---

tg:scanqr

tg://scanqr

---

tg:addcontact

tg://addcontact

    name
    phone

---

tg:search

tg://search

    query

---

https://t.me/@id1234

(it works only on iOS)

---

telegram.me

t.me

telegram.dog

telesco.pe


    joinchat/
    + (new invite link t.me/+HASH)
    
    addstickers/
    
    addtheme/
    
    iv/
      url
      rhash
    
    login/
    
    msg/
    share/
      url
      text
    (Only android)
    
    confirmphone
      phone
      hash
    
    start
    
    startgroup
    
    game
    
    socks
    proxy
      server (address)
      port (port)
      user (user)
      pass (password)
      secret (secret)
    
    setlanguage/
      (12char max)
    
    bg
      slug
      mode
      intensity
      bg_color
      rotation
    
    c/
     (/chatid/messageid/ t.me/tgbeta/3539)
      threadId
      comment
    
    s/
     (channel username/messageid)
     q (search query)
    
    ?comment=
    ?voicechat=
