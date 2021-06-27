(ns general-expenses-accountant.utils.coll
  "Project utilities for collections")

(defn ensure-coll
  [val-or-col]
  (if (coll? val-or-col)
    val-or-col
    (list val-or-col)))

(defn ensure-vec
  [val-or-col]
  (if (vector? val-or-col)
    val-or-col
    (vector val-or-col)))

(defn collect
  [val-or-col-1 val-or-col-2]
  ((if (coll? val-or-col-2) into conj)
   (ensure-coll val-or-col-1)
   val-or-col-2))

(defn add-or-remove
  [val coll]
  (into (empty coll)
        (if (some (set coll) [val])
          (disj (set coll) val)
          (conj coll val))))