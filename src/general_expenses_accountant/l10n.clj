(ns general-expenses-accountant.l10n
  (:require [tongue.core :as tongue]))

(def dicts
  {:en {:init-fine "Initialized successfully..."
        :finishing "The app is going to sleep..."
        :processed "Bot API request processed"
        :not-found "No such API endpoint"}
   :tongue/fallback :en})

(def tr ;; [locale key & args] => string
  (tongue/build-translate dicts))
