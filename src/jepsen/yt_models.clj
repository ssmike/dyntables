(ns jepsen.yt-models
  (:require [clojure.set :as set]
            [clojure.tools.logging :refer [info warn error debug]]
            [jepsen [util :as util]
                    [generator :as gen]
                    [store :as store]
                    [checker :as checker]]
            [knossos.model :as model])
  (:import (knossos.model Model)))

(def inconsistent model/inconsistent)

(def shards
  [[], [20], [30], [40], [50]])

(def key-map
  {0 2, 1 21, 2 31, 3 41, 4 51})

(def max-cell-val 3)

(def cells-count (count shards))

(defn gen-cell-val [] (rand-int max-cell-val))

(defn gen-key
  ([] (key-map (rand-int cells-count)))
  ([k] (->> (range cells-count) shuffle (take k) key-map)))

(defrecord DynGenerator [writing-processes request-counter]
  gen/Generator
  (op [this test process]
    (merge {:type :invoke}
           (let [id (swap! request-counter inc)
                 [k1 k2] (gen-key 2)]
             (if (contains? @writing-processes process)
               (do
                 (swap! writing-processes disj process)
                 {:rpc-id id
                  :f :commit
                  :value {k1 (gen-cell-val) k2 (gen-cell-val)}})
               (do
                 (swap! writing-processes conj process)
                 {:rpc-id id
                  :f :start-tx
                  :value {k1 nil k2 nil}}))))))

(defn dyntables-gen [] (DynGenerator. (atom #{})
                                      (atom 0)))

(defrecord LockedDict [dict locks]
  java.lang.Object
  (toString [_]
    (str "internal dict " dict " locks " locks))
  Model
  (step [m op]
    (let [kvs (:value op)
          op-locks (or (:locks op) #{})]
      (case (:f op)
        :start-tx
          (cond
            (not (empty (set/intersection op-locks locks)))
              (inconsistent (str "can't lock " op-locks))
            (not= (into dict kvs) dict)
              (inconsistent (str "can't read " (vec kvs)))
            true
              (let [new-locks (set/union locks op-locks)
                    new-dict (into dict kvs)]
                (LockedDict. new-dict new-locks)))
        :commit
          (if (set/subset? op-locks locks)
            (LockedDict. (into dict kvs)
                         (set/difference locks op-locks))
            (inconsistent (str "writing to unlocked " op-locks)))))))

(def empty-locked-dict (LockedDict. (into {} (for [i (range cells-count)]
                                               [i 1]))
                                    #{}))
