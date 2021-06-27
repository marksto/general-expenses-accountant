(ns general-expenses-accountant.utils.regexp
  "Project utilities for regular expressions")

(defn re-match-get-groups
  "Matches a string 'str' to pattern 're' once, then uses 'groups' to return
   a mapping from a group name to its value or 'nil', if it was not matched."
  [re str groups]
  (let [matcher (re-matcher re str)
        get-group (fn [^String name]
                    (try
                      (.group matcher name)
                      (catch Exception _
                        nil)))]
    (when (.matches matcher)
      (reduce (fn [acc key]
                (assoc acc key (get-group (name key))))
              {}
              groups))))

(defn re-find-all
  "Returns all found regexp matches, if any, of string 'str' to pattern 're'
   in a form of a lazy sequence. If there's no match, an empty seq returned."
  [re str]
  (let [matcher (re-matcher re str)]
    (take-while some? (repeatedly #(re-find matcher)))))