(ns jepsen.faunadb.bank
  "Simulates transfers between bank accounts"
  (:refer-clojure :exclude [test])
  (:import com.faunadb.client.errors.UnavailableException)
  (:import com.faunadb.client.types.Codec)
  (:import com.faunadb.client.types.Field)
  (:import com.faunadb.client.types.Result)
  (:import com.faunadb.client.types.Value)
  (:import java.io.IOException)
  (:import java.util.concurrent.ExecutionException)
  (:require [jepsen [client :as client]
                    [checker :as checker]
                    [core :as jepsen]
                    [fauna :as fauna]
                    [util :as util]
                    [generator :as gen]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.tests.bank :as bank]
            [jepsen.faunadb [client :as f]
                            [query :as q]]
            [dom-top.core :as dt]
            [clojure.core.reducers :as r]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as cstr]
            [clojure.tools.logging :refer :all]
            [knossos.op :as op]))

(def accounts-name "accounts")
(def accounts (q/class accounts-name))

(def idx-name "all_accounts")
(def idx (q/index idx-name))

(defn do-index-read
  [conn]
  ; TODO: figure out how to iterate over queries containing pagination
  (->> (f/query conn
                (q/map
                  (q/paginate (q/match idx))
                  (q/fn [r]
                    [r (q/select ["data" "balance"] (q/get r))])))
       :data
       (map (fn [[ref balance]]
                [(Long/parseLong (:id ref)) balance]))
       (into {})))

(defmacro wrapped-query
  [op & exprs]
  `(try
    ~@exprs
    (catch UnavailableException e#
      (assoc ~op :type :fail, :error [:unavailable (.getMessage e#)]))

    (catch java.util.concurrent.TimeoutException e#
      (assoc ~op :type :info, :error [:timeout (.getMessage e#)]))

    (catch IOException e#
      (assoc ~op :type :info, :error [:io (.getMessage e#)]))

    (catch com.faunadb.client.errors.BadRequestException e#
      (if (= (.getMessage e#) "transaction aborted: balance would go negative")
        (assoc ~op :type :fail, :error :negative)
        (throw e#)))))

(defrecord BankClient [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (f/client node)))

  (setup! [this test]
    (dt/with-retry [tries 5]
      (f/query conn (q/when (q/not (q/exists? accounts))
                      (q/create-class {:name accounts-name})))
      (let [acct (q/ref accounts (first (:accounts test)))]
        (f/query conn (q/when (q/not (q/exists? acct))
                        (q/create acct
                                  {:data {:balance (:total-amount test)}}))))
      (catch com.faunadb.client.errors.UnavailableException e
        (if (< 1 tries)
          (do (info "Waiting for cluster ready")
              (Thread/sleep 1000)
              (retry (dec tries)))
          (throw e)))))

  (invoke! [this test op]
    (case (:f op)
      :read
      (wrapped-query
        op
        (->> (f/query conn
                      (f/maybe-at test conn
                                  {:data (mapv
                                           (fn [i]
                                             (let [acct (q/ref accounts i)]
                                               (q/when (q/exists? acct)
                                                 [i (q/select ["data" "balance"]
                                                              (q/get acct))])))
                                           (:accounts test))}))
             :data
             (remove nil?)
             (map vec)
             (into {})
             (assoc op :type :ok, :value)))

      :transfer
      (wrapped-query op
        (let [{:keys [from to amount]} (:value op)]
          (f/query
            conn
            (q/do
              (q/let [a (q/- (q/if (q/exists? (q/ref accounts from))
                               (q/select ["data" "balance"]
                                         (q/get (q/ref accounts from)))
                               0)
                             amount)]
                (q/cond
                  (q/< a 0) (q/abort "balance would go negative")
                  (q/= a 0) (q/delete (q/ref accounts from))
                  (q/update
                    (q/ref accounts from)
                    {:data {:balance a}})))
              (q/if (q/exists? (q/ref accounts to))
                (q/let [b (q/+ (q/select ["data" "balance"]
                                         (q/get (q/ref accounts to)))
                               amount)]
                  (q/update (q/ref accounts to)
                            {:data {:balance b}}))
                (q/create (q/ref accounts to)
                          {:data {:balance amount}}))))
          (assoc op :type :ok)))))

  (teardown! [this test])

  (close! [this test]
    (.close conn)))

; Like BankClient, but performs reads using an index instead.
(defrecord IndexClient [bank-client conn]
  client/Client
  (open! [this test node]
    (let [b (client/open! bank-client test node)]
      (assoc this :bank-client b :conn (:conn b))))

  (setup! [this test]
    (client/setup! bank-client test)
    (f/query conn
             (q/when (q/not (q/exists? idx))
               (q/create-index {:name idx-name
                                :source accounts
                                :values [{:field ["ref"]}
                                         {:field ["data" "balance"]}]}))))

  (invoke! [this test op]
    (if (= :read (:f op))
      (wrapped-query op
        (->> (f/query-all conn (q/match idx))
             (map (fn [[ref balance]] [(Long/parseLong (:id ref)) balance]))
             (into (sorted-map))
             (assoc op :type :ok, :value)))

      (client/invoke! bank-client test op)))


  (teardown! [this test]
    (client/teardown! bank-client test))

  (close! [this test]
    (client/close! bank-client test)))

; TODO: index reads variant
; We're not creating this index in the individual client because I want to avoid
; the possibility that index updates are introducing an unnecessary
; synchronization point.
        ;(f/query
        ;  conn
        ;  (q/create-index {:name "all_accounts"
        ;                   :source accounts}))

(defn bank-test-base
  [opts]
  (let [workload (bank/test)]
    (fauna/basic-test
      (merge
        (dissoc workload :generator)
        {:client {:client (:client opts)
                  :during (->> (:generator workload)
                               (gen/delay 1/10)
                               (gen/clients))
                  :final  (gen/clients nil)}
         :checker (checker/compose
                    {:perf     (checker/perf)
                     :timeline (timeline/html)
                     :details  (:checker workload)})}
        (dissoc opts :client)))))

(defn test
  [opts]
  (bank-test-base
    (merge {:name   "bank"
            :client (BankClient. nil)}
           opts)))

(defn index-test
  "A variant of the test which uses an index instead of directly fetching
  individual accounts."
  [opts]
  (bank-test-base
    (merge {:name   "bank index"
            :client (IndexClient. (BankClient. nil) nil)}
           opts)))
