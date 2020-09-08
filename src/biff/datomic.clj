(ns biff.datomic
  (:require
    [biff.protocols :as proto]
    [clojure.core.async :refer [go <!!]]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.walk :as walk]
    [clojure.set :as set]
    [datomic.client.api :as d]
    [expound.alpha :refer [expound]]
    [taoensso.timbre :refer [log spy]]
    [trident.util :as u]))

(defn dissoc-clean [m k1 k2]
  (let [m (update m k1 dissoc k2)]
    (cond-> m
      (empty? (get m k1)) (dissoc k1))))

(defn start-node [client-args db-name]
  (let [client (d/client client-args)]
    (d/create-database client {:db-name db-name})
    (d/connect client {:db-name db-name})))

(defn wrap-sub [handler]
  handler)

(defn authorize-tx [{:keys [tx current-time] :as env
                     :or {current-time (java.util.Date.)}}]
  tx)

(defn wrap-tx [handler]
  handler)

(defn submit-tx [{:biff/keys [node db rules] :as sys} tx]
  (let [db (or db (d/db node))
        tx (authorize-tx {:tx tx
                          :biff/db db
                          :biff/rules rules
                          :admin true})]
    (when (u/anomaly? tx)
      (throw (ex-info "Invalid transaction." tx)))
    (d/transact node {:tx-data tx})))

(defn start-tx-listener [{:keys [biff/node biff.sente/connected-uids] :as sys}]
  sys)
