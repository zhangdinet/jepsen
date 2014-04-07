(ns jepsen.generator-test
  (:use clojure.test
        clojure.pprint
        clojure.tools.logging)
  (:require [jepsen.generator :as gen]
            [clojure.set :as set]))

(def a-test {:nodes [:a :b :c :d :e]})

(defn ops
  "All ops from a generator"
  [threads gen]
  (let [ops (atom [])]
    (binding [gen/*threads* threads]
      (->> threads
           (map (fn [p] (future
                          (loop []
                            (when-let [op (gen/op gen a-test p)]
                              (swap! ops conj op)
                              (recur))))))
           doall
           (map deref)
           dorun))
    @ops))

(deftest seq-test
  (is (= (set (ops (:nodes a-test)
              (gen/seq (range 100)))))
      (set (range 100)))

  (pprint (ops [:nemesis :a :b]
            (->> (gen/nemesis
                   (gen/seq
                     (cycle [(gen/sleep 3)
                             (gen/log* "start")
                             {:type :info :f :start}
                             (gen/sleep 3)
                             (gen/log* "stop")
                             {:type :info :f :stop}])))
                 (gen/time-limit 20)))))

(deftest complex-test
  (let [ops (ops (:nodes a-test)
                 (->> (gen/queue)
                      (gen/limit 100)
                      (gen/then (gen/once {:value :a}))
                      (gen/then (gen/once {:value :b}))
                      (gen/then (gen/once {:value :c}))
                      (gen/then (gen/once {:value :d}))))]
    (is (= 104 (count ops)))
    (is (= [:a :b :c :d] (map :value (take-last 4 ops))))

    (is (set/subset? (set (map :value ops))
                     (set (concat (range 0 99) [nil :a :b :c :d]))))))

(deftest log-test
  (let [ops (ops (:nodes a-test)
                 (gen/phases (gen/log "start")
                             (gen/limit (count (:nodes a-test))
                                        {:value :hi})
                             (gen/log "stop")))]
    (is (= ops (repeat (count (:nodes a-test)) {:value :hi})))))

(deftest then-test
  (testing "phases and then"
    (is (= (ops (:nodes a-test)
                (gen/phases
                  (gen/on #{:c :d}
                          (->> (gen/once 1)
                               (gen/then (gen/once 2))))))
           [1 2]))))

(deftest nemesis-phase-test
  (testing "nemesis can take part in synchronization barriers"
    (is (= (ops (cons :nemesis (:nodes a-test))
                (gen/phases (gen/once :a)
                            (gen/once :b)))
           [:a :b])))

  (testing "nemesis is not included when we filter generator processes"
    (is (= (ops (cons :nemesis (:nodes a-test))
                (gen/phases (->> (gen/once :start)
                                 (gen/nemesis (gen/once :start)))
                            (gen/nemesis (gen/once :nem))
                            (gen/on (complement #{:nemesis})
                                    (gen/synchronize
                                      (gen/each :*)))
                            (gen/on #{:c :d}
                                    (->> (gen/once :c)
                                         (gen/then (gen/once :d))))))
           [:start :start :nem
            :* :* :* :* :*
            :c :d]))))