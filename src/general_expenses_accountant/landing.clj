(ns general-expenses-accountant.landing
  "Layouts for server-side rendered pages"
  (:require [hiccup.core :refer [html]]
            [hiccup.page :refer [include-css]]))

(defn page
  "Renders HTML page with provided content"
  [content]
  (html
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "General Expenses Accountant"]
      (include-css "//cdnjs.cloudflare.com/ajax/libs/semantic-ui/2.4.1/semantic.min.css")
      (include-css "/style.css")]
     [:body content]]))

(def home
  (page
    [:div#landing-outer.ui.container
     [:div.ui.vertical.masthead.center.aligned.segment
      [:div.ui.test.container
       [:h1.header "Welcome to General Expenses Accountant!"]
       ;; TODO: Add instruction on how to use this bot (create chat, add it there, set Google Sheets ID and API token).
       [:h3 "Telegram bot that helps you to account for general expenses!"]
       [:a.ui.huge.button
        {:href "tg://resolve?domain=gen_exp_acc_bot"}
        "Start Conversation"
        [:i.icon.right.arrow]]]]]))
