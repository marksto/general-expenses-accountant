(ns general-expenses-accountant.html
  "Layouts for server-side rendered HTML pages"
  (:require [hiccup.page :as h-page]))

(defn page
  "Renders HTML page with provided content"
  [content]
  (h-page/html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "General Expenses Accountant"]
     (h-page/include-css "//cdnjs.cloudflare.com/ajax/libs/semantic-ui/2.4.1/semantic.min.css")
     (h-page/include-css "/style.css")]
    [:body content]))

(defn landing
  [bot-username]
  (page
    [:div#landing-outer.ui.container
     [:div.ui.vertical.masthead.center.aligned.segment
      [:div.ui.test.container
       [:h1.header "Welcome to General Expenses Accountant!"]
       ;; TODO: Add instruction on how to use this bot (create chat, add it there, set Google Sheets ID and API token).
       [:h3 "Telegram bot that helps you to account for general expenses!"]
       [:a.ui.huge.button
        {:href (str "tg://resolve?domain=" bot-username)}
        "Start Conversation"
        [:i.icon.right.arrow]]]]]))
