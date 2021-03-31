(ns general-expenses-accountant.nums
  (:require [clojure.string :as str]))

(defn parse-int
  [number-string]
  (try
    (Integer/parseInt number-string)
    (catch Exception _
      nil)))

(defn parse-number
  [str]
  (try
    (bigdec (str/replace str "," "."))
    (catch NumberFormatException _
      str)))
