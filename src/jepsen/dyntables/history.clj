(ns jepsen.dyntables.history
  (:require [clojure.tools.logging :refer [info debug]]
            [clojure.core.match :refer [match]]))

(defn index
  [history]
  (-> (reduce (fn [[index invoke-cache result] op]
                (if (= (:type op) :invoke)
                  [(inc index)
                   (assoc! invoke-cache (:process op) index)
                   (conj! result (assoc op :req-id index))]
                  [index
                    invoke-cache
                    (conj! result (assoc op
                                         (:req-id op)
                                         (invoke-cache (:process op))))]))
              [0 (transient {}) (transient [])]
              history)
      (get 2)
      persistent!))

(defn foldup-locks
  [history]
  (let [last-write (atom (transient {}))]
    (->> (reverse history)
         (map (fn [op]
                (do
                  (let [write-op (@last-write (:process op))]
                    (match [(:f op) (:type op)]
                           [:start-tx _]
                           (if (and write-op
                                    (not= (:type write-op) :fail))
                             (let [_ (assert (:value write-op) (str write-op op))
                                   locked (assoc op :locks (into #{} (keys (:value write-op))))
                                   unlocked (assoc op :blocks true)]
                               (if (= (:type write-op) :ok)
                                 [locked]
                                 [unlocked locked]))
                             [op])
                           [:commit :invoke]
                           [op]
                           [:commit _]
                           (do
                             (swap! last-write assoc! (:process op) op)
                             [op])
                           :else nil)))))
         (filter vector?)
         reverse)))

(defn merge-success
  [invoke-ops ok-ops]
  (if (nil? ok-ops)
    invoke-ops
    (mapv (fn [invoke-op ok-op]
            (assoc invoke-op :value (:value ok-op)))
          invoke-ops
          ok-ops)))

(defn complete-history
  [history]
  (let [cache (atom (transient {}))
        history  (->> (reverse history)
                      (map (fn [op]
                             (let [p (:process (first op))]
                               (case (-> op first :type)
                                 :ok (do
                                       (swap! cache assoc! p op)
                                       op)
                                 :fail (swap! cache assoc! p :fail)
                                 :info (swap! cache dissoc! p)
                                 :invoke (let [saved (@cache p)]
                                           (swap! cache dissoc! p)
                                           (if (not= saved :fail)
                                             (merge-success op saved)))))))
                      (filter vector?)
                      reverse)]
    (assert (empty? (persistent! @cache)))
    history))
