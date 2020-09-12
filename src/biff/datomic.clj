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

(defn attr-clause? [clause]
  (not (coll? (first clause))))

(defn normalize-query [{:keys [table where args id] :as query}]
  (if (some? id)
    {:id id}
    (u/assoc-some
      {:where where}
      :args (dissoc args 'doc))))

(defn authorize-read [{:keys [table doc query biff/rules] :as env}]
  (prn "TODO: biff.datomic/authorize-read")
  doc)

(defn subscribe*
  [{:keys [biff/db biff/fn-whitelist session/uid] event-id :id :as env} {:keys [table] :as query}]
  (let [fn-whitelist (into #{'= 'not= '< '> '<= '>= '== '!=} fn-whitelist)
        {:keys [where id args] :as norm-query} (normalize-query query)]
    (u/letdelay [fns-authorized #_(every? #(or (attr-clause? %) (fn-whitelist (ffirst %))) where)
                 #__ (do (prn "TODO: biff.datomic/subscribe* authorize") true)
                 dat-query (cond-> ;TODO: Only pull allowed and needed attrs
                               {:find '[(pull ?e [*])]
                                :where (mapv #(cond->> %
                                                (attr-clause? %) (into ['?e]))
                                         where)}
                              args (assoc :args [args]))
                 docs (if (some? id)
                        (some-> (d/pull db '[*] id) vector)
                        (d/q dat-query db))
                 authorize-anom (->> docs
                                  (map #(->> {:doc %
                                              :table table
                                              :query norm-query}
                                          (merge env)
                                          authorize-read))
                                  (filter u/anomaly?)
                                  first)
                 changeset (u/map-from #(vector table (:db/id %)) docs)]
      (cond
        (not= query (assoc norm-query :table table)) (u/anom :incorrect "Invalid query format."
                                                       :query query)
        (not fns-authorized) (u/anom :forbidden "Function call not allowed."
                               :query query)
        authorize-anom authorize-anom
        :default {:norm-query norm-query
                  :query-info {:table table
                               :event-id event-id
                               :session/uid uid}
                  :sub-data {:query query
                             :changeset changeset}}))))

(defn subscribe!
  [{:keys [biff/send-event biff.datomic/subscriptions client-id id] :as env} query]
  (let [{:keys [norm-query query-info sub-data] :as result} (subscribe* env query)]
    (if-not (u/anomaly? result)
      (do
        (send-event client-id [id sub-data])
        (swap! subscriptions assoc-in [client-id norm-query] query-info))
      result)))

(defn wrap-sub [handler]
  (fn [{:keys [id biff/send-event client-id session/uid] {:keys [query action]} :?data :as env}]
    (if (not= :biff/sub id)
      (handler env)
      (let [result (cond
                     (and (= query :uid)
                       (#{:subscribe :resubscribe} action))
                     (send-event client-id
                       [:biff/sub {:changeset {[:uid nil]
                                               (if (some? uid)
                                                 {:uid uid}
                                                 {:uid client-id
                                                  :tmp true})}
                                   :query query}])

                     (= action :subscribe) (subscribe! env query)
                     ;(= action :unsubscribe) (crux-unsubscribe! env query)
                     ;(= action :resubscribe) (crux-resubscribe! env query)
                     :default (u/anom :incorrect "Invalid action." :action action))]
        (when (u/anomaly? result)
          result)))))

(defn authorize-tx [{:keys [tx current-time] :as env
                     :or {current-time (java.util.Date.)}}]
  tx)

(defn wrap-tx [handler]
  (fn [{:keys [id biff/node] :as env}]
    (if (not= id :biff/tx)
      (handler env)
      (let [tx (authorize-tx (set/rename-keys env {:?data :tx}))]
        (if (u/anomaly? tx)
          tx
          (d/transact node {:tx-data tx}))))))

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
