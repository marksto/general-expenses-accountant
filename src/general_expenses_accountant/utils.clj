(ns general-expenses-accountant.utils)

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