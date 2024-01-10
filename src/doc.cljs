(ns doc
  (:require 
    ["fs" :as fs]
    ["process" :refer [argv]]
    [clojure.core :refer [read-string]]
    [clojure.string :refer [join]]))

(defn main [args]
(let [f (-> (fs/readFileSync (or (first args)
                                 "src/sitefoxpayments/payments.cljs")) .toString)
      code (read-string (str "[" f "]"))]
  (doseq [form code]
    (case (keyword (first form))
      :defn (let [fn-name (second form)
                  doc (nth form 2)
                  args (nth form 3)]
              (when (= (type doc) js/String)
                (print "## (" fn-name (join " " args) ")")
                (print)
                (print doc)
                (print)))
      :def (let [n (second form)
                 doc (nth form 2)]
             (when (and (= (type doc) js/String) (> (count form) 3))
               (print "## " n)
               (print)
               (print doc)
               (print)))
      ;:ns (print "NS" form)
      nil))))

(let [args (.slice argv 3)]
  (when (seq args)
    (main args)))
