(ns general-expenses-accountant.md-v2
  "A minor part of the MarkdownV2 functionality that is absolutely necessary."
  (:require [clojure.string :as str]

            [taoensso.encore :as encore]

            [general-expenses-accountant.utils.regexp :as u-re]))

(def ^:private inline-url-re
  #"\[(?<text>[^]]+)\]\((?<href>[^\s]+)\)")

(defn- escape-text
  "Escapes plain text (no pre, code or inline strings)."
  [text-str]
  (str/replace text-str #"[_*\[\]()~`>#+\-=|{}.!]" #(str "\\" %)))

;; TODO: Implement this f11y later, if necessary.
(defn- escape-code
  "Escapes the inline code strings and pre-formatted code blocks."
  [code-str]
  (str/replace code-str #"[`\\]" #(str "\\" %)))

(defn- escape-inline-url
  "Escapes the \"[inline URL](http://www.example.com/)\" strings."
  [inline-url-str]
  (let [{:keys [text href]} (u-re/re-match-get-groups
                              inline-url-re
                              inline-url-str
                              [:text :href])
        escaped-url (str/replace href #"[)\\]" #(str "\\" %))]
    (str "[" (escape-text text) "]" "(" escaped-url ")")))

(defn escape
  [text-str]
  (if (str/blank? text-str)
    text-str
    (let [text-fragments (str/split text-str inline-url-re)
          inline-urls (mapv first (u-re/re-find-all inline-url-re text-str))]
      (str/join (encore/interleave-all (map escape-text text-fragments)
                                       (map escape-inline-url inline-urls))))))

(defn format-bold
  ([text-str]
   (format-bold text-str true))
  ([text-str escape?]
   (str "*" (if escape? (escape text-str) text-str) "*")))

(defn format-italic
  ([text-str]
   (format-italic text-str true))
  ([text-str escape?]
   (str "_" (if escape? (escape text-str) text-str) "_")))

(defn format-underline
  ([text-str]
   (format-underline text-str true))
  ([text-str escape?]
   (str "__" (if escape? (escape text-str) text-str) "__")))

(defn format-strikethrough
  ([text-str]
   (format-strikethrough text-str true))
  ([text-str escape?]
   (str "~" (if escape? (escape text-str) text-str) "~")))
