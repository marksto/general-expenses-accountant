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

(defn parse-arithmetic-expression
  "Parses the string with an arithmetic expression and calculates its value.

   IMPLEMENTATION NOTE:
   It's super-naïve and supports only the '+' and '–' operations."
  [str]
  (try
    (let [num-seq (-> str
                      ;; "150+50–100" -> "150 + 50 – 100"
                      (str/replace #"(\d)([+–])(\d)" "$1 $2 $3")
                      ;; "150 + 50 – 100" -> "150 + 50  -100"
                      (str/replace #"– " " -")
                      ;; "150 + 50  -100" -> ["150" "" "" "50" "" "-100"]
                      (str/split #"[+ ]"))]
      (->> num-seq
           (filter not-empty)
           (map parse-number)
           (reduce +')))
    (catch Exception _
      str)))
