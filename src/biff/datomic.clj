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

(defn wrap-tx [handler]
  handler)

(defn start-tx-listener [{:keys [biff/node biff.sente/connected-uids] :as sys}]
  sys)
