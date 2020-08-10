(ns biff.datomic
  (:require
    [clojure.core.async :refer [go <!!]]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.walk :as walk]
    [clojure.set :as set]
    [datomic.client.api :as d]
    [expound.alpha :refer [expound]]
    [taoensso.timbre :refer [log spy]]
    [trident.util :as u]))
(defn p [& args] (apply prn args) (last args))
(defn start-db-conn [client-args db-name]
  (let [client (d/client client-args)]
    (d/create-database client {:db-name db-name})
    (d/connect client {:db-name db-name})))

(u/sdefs
  ::ident (s/cat :table keyword? :id (s/? any?))
  ::tx (s/coll-of (s/tuple ::ident (s/nilable map?))))

(defn authorize-write [{:keys [biff/rules admin] :as env}
                       {:keys [table op] :as doc-tx-data}]

  (prn "TODO: biff.datomic/authorize-write")
  doc-tx-data
  #_(if admin
    doc-tx-data
    (u/letdelay [auth-fn (get-in rules [table op])
                 result (auth-fn (merge env doc-tx-data))
                 anom-fn #(merge (u/anom :forbidden %)
                            (select-keys doc-tx-data [:table :id]))]
      (cond
        (nil? auth-fn) (anom-fn "No auth function.")
        (not result) (anom-fn "Document rejected by auth fn.")
        :default (merge doc-tx-data (when (map? result) result))))))

(defn authorize-tx [{:keys [tx current-time] :as env
                     :or {current-time (java.util.Date.)}}]
  (prn "TODO: biff.datomic/authorize-tx")
  tx
  #_(if-not (s/valid? ::tx tx)
    (u/anom :incorrect "Invalid transaction shape."
      :tx tx)
    (u/letdelay [tx* (->> tx
                       (walk/postwalk
                         #(case %
                            :db/current-time current-time
                            %))
                       (map #(prep-doc env %)))
                 tx (into {} tx*)
                 env (assoc env :tx tx :current-time current-time)
                 auth-result (mapv #(authorize-write env (second %)) tx)
                 crux-tx (u/forv [{:keys [op cas old-doc doc id]} auth-result]
                           (cond
                             cas            [:crux.tx/cas old-doc doc]
                             (= op :delete) [:crux.tx/delete id]
                             :default       [:crux.tx/put doc]))]
      (or
        (first (filter u/anomaly? tx*))
        (first (filter u/anomaly? auth-result))
        crux-tx))))

(defn attr-clause? [clause]
  (not (coll? (first clause))))

(defn normalize-query [{:keys [table where args id] :as query}]
  (if (some? id)
    {:id id}
    (u/assoc-some
      {:where where}
      :args (dissoc args 'doc))))

(defn crux== [& args]
  (let [[colls xs] (u/split-by coll? args)
        sets (map set colls)]
    (if (empty? xs)
      (not-empty (apply set/intersection sets))
      (and (apply = xs)
        (every? #(contains? % (first xs)) sets)))))

(defn crux!= [& args]
  (not (apply crux== args)))

(defn resolve-fn [sym]
  (requiring-resolve
    (if (qualified-symbol? sym)
      sym
      (symbol "clojure.core" (name sym)))))

(defn query-contains? [{:keys [id where args]} doc]
  (if (some? id)
    (= id (:db/id doc))
    (let [args (assoc args 'doc (:db/id doc))
          where (walk/postwalk #(get args % %) where)
          [attr-clauses rule-clauses] (u/split-by (comp keyword? first) where)
          [binding-clauses constant-clauses] (u/split-by (comp symbol? second) attr-clauses)
          {:keys [args fail]} (reduce (fn [{:keys [args fail]} [attr sym]]
                                        (let [value (get doc attr)]
                                          {:args (assoc args attr value)
                                           :fail (or fail
                                                   (not (contains? doc attr))
                                                   (and
                                                     (contains? args sym)
                                                     (crux!= (get args sym) value)))}))
                                {:args args}
                                binding-clauses)
          fail (reduce (fn [fail [attr value :as clause]]
                         (or fail
                           (not (contains? doc attr))
                           (and
                             (not= 1 (count clause))
                             (crux!= (get doc attr) value))))
                 fail
                 constant-clauses)
          rule-clauses (walk/postwalk #(get args % %) rule-clauses)]
      (not (reduce (fn [fail [[f & params]]]
                     (or fail
                       (not (apply (resolve-fn (condp = f
                                                 '== `crux==
                                                 '!= `crux!=
                                                 f)) params))))
             fail
             rule-clauses)))))

(defn doc-valid? [{:keys [verbose]
                   [id-spec doc-spec] :specs
                   {:crux.db/keys [id] :as doc} :doc}]
(prn "TODO: doc-valid?")
  (let [doc (apply dissoc doc
              (cond-> [:crux.db/id]
                (map? id) (concat (keys id))))
        id-valid? (s/valid? id-spec id)
        doc-valid? (s/valid? doc-spec doc)]
    (when verbose
      (cond
        (not id-valid?) (expound id-spec id)
        (not doc-valid?) (expound doc-spec doc)))
    (and id-valid? doc-valid?)))

(defn authorize-read [{:keys [table doc query biff/rules] :as env}]
  (prn "TODO: biff.datomic/authorize-read")
  doc
  #_(let [query-type (if (contains? query :id)
                     :get
                     :query)
        auth-fn (get-in rules [table query-type])
        specs (get-in rules [table :spec])
        anom-message (cond
                       (nil? auth-fn) "No auth fn."
                       (nil? specs) "No specs."
                       (not (doc-valid? {:verbose true
                                         :specs specs
                                         :doc doc})) "Doc doesn't meet specs."
                       (not (u/catchall (auth-fn env)))
                       "Doc rejected by auth fn.")]
    (if anom-message
      (u/anom :forbidden anom-message
        :norm-query query
        :table table)
      doc)))

(defn resubscribe*
  [{:keys [biff/db biff/fn-whitelist session/uid] event-id :id :as env} {:keys [table] :as query}]
  (let [fn-whitelist (into #{'= 'not= '< '> '<= '>= '== '!=} fn-whitelist)
        {:keys [where id args] :as norm-query} (normalize-query query)]
    (u/letdelay [fns-authorized (every? #(or (attr-clause? %) (fn-whitelist (ffirst %))) where)
                 crux-query (cond->
                              {:find '[doc]
                               :where (mapv #(cond->> %
                                               (attr-clause? %) (into ['doc]))
                                        where)}
                              args (assoc :args [args]))]
      (cond
        (not= query (assoc norm-query :table table)) (u/anom :incorrect "Invalid query format."
                                                       :query query)
        (not fns-authorized) (u/anom :forbidden "Function call not allowed."
                               :query query)
        :default {:norm-query norm-query
                  :query-info {:table table
                               :event-id event-id
                               :session/uid uid}}))))

; todo dry with resubscribe*
(defn subscribe*
  [{:keys [biff/db biff/fn-whitelist session/uid] event-id :id :as env} {:keys [table] :as query}]
  (let [{:keys [where id args] :as norm-query} (normalize-query query)
        dat-query (cond->
                      {:find '[(pull ?e [*])]
                       :where (mapv #(cond->> %
                                       (attr-clause? %) (into ['?e]))
                                where)}
                    args (assoc :args [args]))]
    #_(prn :table table :query query
      :nq norm-query
      :dq dat-query)
    #_(prn :docs (if (some? id)
                 (some-> (d/pull db '[*] id) vector)
                 (d/q dat-query db))))
  (let [fn-whitelist (into #{'= 'not= '< '> '<= '>= '== '!=} fn-whitelist)
        {:keys [where id args] :as norm-query} (normalize-query query)]
    (u/letdelay [;fns-authorized (every? #(or (attr-clause? %) (fn-whitelist (ffirst %))) where)
                 fns-authorized (do (prn "TODO: biff.datomic/subscribe* authorize") true)
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

(defn resubscribe!
  [{:keys [biff.datomic/subscriptions session/uid client-id id] :as env}
   {:keys [table] :as query}]
  (let [{:keys [norm-query query-info] :as result} (resubscribe* env query)]
    (if-not (u/anomaly? result)
      (swap! subscriptions assoc-in [client-id norm-query] query-info)
      result)))

(defn unsubscribe!
  [{:keys [biff.datomic/subscriptions client-id session/uid]} query]
  (swap! subscriptions update client-id dissoc (normalize-query query)))

(defn get-id->doc [{:keys [db-after bypass-auth query id->change] :as env}]
  (->> id->change
    (u/map-vals
      (fn [change]
        (map
          #(when (or (not (map? %))
                   (query-contains? query %))
             %)
          change)))
    (remove (comp (fn [[a b]] (= a b)) second))
    (u/map-vals (comp #(if (or (nil? %) bypass-auth)
                         %
                         (authorize-read (assoc env :doc % :biff/db db-after)))
                  second))))

; todo fix race conditions for subscribing and receiving updates
(defn changesets* [{:keys [biff.datomic/subscriptions] :as env}]
  ((fn step [{:keys [query->id->doc subscriptions]}]
     (let [[[client-id query->info] & subscriptions] subscriptions
           queries (keys query->info)
           query->id->doc (->> queries
                            (remove query->id->doc)
                            (u/map-to #(get-id->doc (merge
                                                      env
                                                      (query->info %)
                                                      {:client-id client-id
                                                       :query %})))
                            (merge query->id->doc))]
       (concat (for [q queries
                     :let [id->doc (query->id->doc q)
                           anom (->> id->doc
                                  vals
                                  (filter #(or (u/anomaly? %) (nil? %)))
                                  first)
                           {:keys [table event-id]} (query->info q)
                           changeset (delay (u/map-keys #(vector table %) id->doc))]
                     :when (or anom (not-empty id->doc))]
                 (if anom
                   (assoc anom
                     :client-id client-id
                     :event-id event-id
                     :query q)
                   {:client-id client-id
                    :event-id event-id
                    :query (assoc q :table table)
                    :changeset @changeset}))
         (when (not-empty subscriptions)
           (lazy-seq
             (step {:query->id->doc query->id->doc
                    :subscriptions subscriptions}))))))
   {:query->id->doc {}
    :subscriptions subscriptions}))

(defn time-before [date]
  (-> date
    .toInstant
    (.minusMillis 1)
    java.util.Date/from))

(defn time-before-txes [txes]
  (-> txes
    first
    :crux.tx/tx-time
    time-before))

(defn get-id->change [{:keys [txes db-before db-after]}]
  (->> txes
    (mapcat (fn [tx]
              (distinct (map #(.-e %) (:data tx)))))
    distinct
    (map #(vector % (d/pull db-after '[*] %)))
    (u/map-from first)))

(defn changesets [{:keys [client-id] :as env}]
  (-> env
    (assoc :id->change (get-id->change env))
    (update :biff.datomic/subscriptions (fn [xs] (sort-by #(not= client-id (first %)) xs)))
    changesets*))

(defn trigger-data [{:biff/keys [rules triggers node]
                     :keys [id->change txes] :as env}]
  (/ 1 0)
  (for [{:keys [crux.tx/tx-time crux.tx.event/tx-events] :as tx} txes
        :let [db (/ 1 0) #_(crux/db node tx-time)
              db-before (/ 1 0 ) #_(crux/db node (time-before tx-time))]
        [tx-op doc-id] tx-events
        :when (#{:crux.tx/put :crux.tx/delete} tx-op)
        :let [doc (/ 1 0) #_(crux/entity db doc-id)
              doc-before (/ 1 0) #_(crux/entity db-before doc-id)
              doc-op (cond
                       (nil? doc) :delete
                       (nil? doc-before) :create
                       :default :update)]
        [table op->fn] triggers
        [trigger-op f] op->fn
        :let [specs (get-in rules [table :spec])]
        :when (and (= trigger-op doc-op)
                (some #(doc-valid? {:specs specs
                                    :doc %}) [doc doc-before]))]
    (assoc env
      :table table
      :op trigger-op
      :doc doc
      :doc-before doc-before
      :db db
      :db-before db-before)))

(defn run-triggers [env]
  (doseq [{:keys [table op triggers doc] :as env} (trigger-data env)]
    (try
      ((get-in triggers [table op]) env)
      (catch Exception e
        (.printStackTrace e)
        (log :error e "Couldn't run trigger")))))

(defn listener [f]
  (let [g (fn []
            (Thread/sleep 1000)
            (try
              (f)
              (catch Exception e
                (clojure.pprint/pprint e)))
            (recur))]
    (doto (Thread. g)
      (.setDaemon true)
      .start)))

(defn notify-tx [{:biff/keys [triggers send-event db-conn]
                  :keys [biff.datomic/subscriptions last-tx-id] :as env}]
  (let [last-t (inc @last-tx-id)]
    (when-let [txes (not-empty (d/tx-range db-conn {:start (inc last-t)
                                                    :end (+ 21 last-t)}))]
      (let [tx-id (:t (last txes))
            db (d/db db-conn)
            {:keys [id->change] :as env} (-> env
                                           (update :biff.datomic/subscriptions deref)
                                           (assoc
                                             :txes txes
                                             :db-before (d/as-of db last-t)
                                             :db-after (d/as-of db tx-id))
                                           (#(assoc % :id->change (get-id->change %))))
            changesets (changesets env)]
        (future (u/fix-stdout (run-triggers env)))
        (doseq [{:keys [client-id query changeset event-id] :as result} changesets]
          (if (u/anomaly? result)
            (do
              (u/pprint result)
              (swap! subscriptions
                #(let [subscriptions (update % client-id dissoc query)]
                   (cond-> subscriptions
                     (empty? (get subscriptions client-id)) (dissoc client-id))))
              (send-event client-id [:biff/error (u/anom :forbidden "Query not allowed."
                                                   :query query)]))
            (send-event client-id [event-id {:query query
                                             :changeset changeset}])))
        (reset! last-tx-id tx-id)
        true))))

(defn wrap-tx [handler]
  (fn [{:keys [id biff/db-conn] :as env}]
    (prn :id id :?data (:?data env))
    (if (not= id :biff/tx)
      (handler env)
      (let [tx (authorize-tx (set/rename-keys env {:?data :tx}))]
        (prn :tx tx)
        (if (u/anomaly? tx)
          tx
          (d/transact db-conn {:tx-data tx}))))))

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
                     (= action :unsubscribe) (unsubscribe! env query)
                     (= action :resubscribe) (resubscribe! env query)
                     :default (u/anom :incorrect "Invalid action." :action action))]
        (when (u/anomaly? result)
          result)))))

(defn submit-admin-tx [{:biff/keys [db-conn db rules] :as sys} tx]
  (let [db (or db (d/db db-conn))
        tx (authorize-tx {:tx tx
                          :biff/db db
                          :biff/rules rules
                          :admin true})
        anom (u/anomaly? tx)]
    (when anom
      (u/pprint anom)
      (u/pprint tx))
    (if anom
      tx
      (d/transact db-conn {:tx-data tx}))))


; todo move this to a test file or delete it

(comment

  (u/pprint @(:findka.biff.datomic/subscriptions @biff.core/system))


  (crux/entity (crux/db (:findka.biff/node @biff.core/system))
    {:user/id #uuid "730fccdc-d7a8-482a-9ce7-5517d2607c90"})

  (defn infer-keys [params]
    (letfn [(keys-for [sym exclude]
              (let [ret
                    (->> (get-in params [sym :fns])
                      (mapcat #(apply keys-for %))
                      (concat (get-in params [sym :keys]))
                      (remove (set exclude))
                      set)]
                ;(u/pprint [:keys-for sym exclude ret])
                ret))]
      (u/map-to (comp vec #(keys-for % nil)) (keys params))))


  ; todo infer parts of this
  (def params
    '{prep-doc {:keys [:db :rules]}
      doc-tx-data {:keys [:table :id :generated-id :old-doc :doc :op]}
      read-auth-fn {:keys [:db :doc :auth-uid]}
      write-auth-fn {:keys [:db :auth-uid :tx :current-time]
                     :fns {doc-tx-data nil}}
      authorize-write {:keys [:rules]
                       :fns {write-auth-fn [:table :id :generated-id :old-doc :doc :op]}}
      authorize-tx {:keys [:tx]
                    :fns {prep-doc nil
                          authorize-write [:current-time]}}
      authorize-read {:keys [:table :uid :db :doc :query :rules]}
      subscribe* {:keys [:db :uid :event-id]
                       :fns {authorize-read [:table :doc :query]}}
      subscribe! {:keys [:api-send :subscriptions :client-id]
                       :fns {subscribe* nil}}
      unsubscribe! {:keys [:subscriptions :client-id :uid]}
      get-id->doc {:keys [:db-after :query :id->change]
                   :fns {authorize-read [:doc :db]}}
      changesets* {:keys [:subscriptions]
                   :fns {get-id->doc [:query :table :uid :event-id]} }
      get-id->change {:keys [:txes :db-before :db-after]}
      changesets {:fns {get-id->change nil
                        changesets* [:id->change]}}
      notify-tx {:keys [:api-send :node :client-id :last-tx-id :tx :subscriptions]
                 :fns {changesets [:txes :db-before :db-after]}}})

  (infer-keys params)
  {prep-doc [:db :rules],
   get-id->doc [:table :db-after :uid :rules :id->change :query],
   authorize-tx [:db :rules :tx :auth-uid],
   unsubscribe! [:client-id :uid :subscriptions],
   changesets* [:db-after :rules :subscriptions :id->change],
   changesets [:db-after :txes :rules :db-before :subscriptions],
   authorize-write [:current-time :db :rules :tx :auth-uid],
   doc-tx-data [:table :generated-id :op :id :old-doc :doc],
   write-auth-fn [:current-time :table :db :generated-id :op :tx :id :old-doc :doc :auth-uid],
   notify-tx [:client-id :last-tx-id :api-send :node :rules :subscriptions :tx],
   authorize-read [:table :db :uid :rules :query :doc],
   read-auth-fn [:db :doc :auth-uid],
   subscribe* [:event-id :db :uid :rules],
   get-id->change [:db-after :txes :db-before],
   subscribe! [:event-id :client-id :db :uid :api-send :rules :subscriptions]}


  (defn tmp-node []
    (start-node {:storage-dir (u/tmp-dir)
                 :persist false}))

  (defmacro with-db [id->doc & forms]
    `(with-open [node# (tmp-node)]
       (->>
         (for [[id# doc#] ~id->doc]
           [:crux.tx/put
            (assoc doc# :crux.db/id id#)])
         (crux/submit-tx node#)
         (crux/await-tx node#))
       (let [~'db (crux/db node#)]
         ~@forms)))

  (query-contains?
    {:id :foo}
    {:crux.db/id :foo})

  (query-contains?
    {:where [:foo 3]}
    {:foo 3})

  (query-contains?
    {:where [:foo #{3}]}
    {:foo [3]})

  (with-db {:foo {:bar 3}}
    (crux/entity db :foo))

  (u/anom :incorrect)
  (u/anom :incorrect "hello")
  (u/anomaly? (u/anom :incorrect "hello"))

  (defmacro test-prep-doc [db & forms]
    `(u/pprint
       (with-db ~db
         (prep-doc ~@forms))))

  (test-prep-doc {}
    {:db db
     :rules {:some-table {:spec [uuid? #{{:foo 3}}]}}}
    [[:some-table #uuid "171a3404-cc31-498b-848e-7dabc04a1daa"] {:foo 3}])


  (test-prep-doc {}
    {:db db
     :rules {:some-table {:spec [uuid? #{{:foo 3}}]}}}
    [[:some-table] {:foo 3}])

  (test-prep-doc {:doc-id {:foo 1}}
    {:db db
     :rules {:some-table {:spec [uuid? #{{:foo 3}}]}}}
    [[:some-table :foo] {:foo 3}])

  (test-prep-doc {:doc-id {:foo 1}}
    {:db db
     :rules {:some-table {:spec [keyword? #{{:foo 2}}]}}}
    [[:some-table :foo] {:foo 3}])

  (test-prep-doc {{:a 1 :b 2} {:foo 1 :bar "hey"}}
    {:db db
     :rules {:some-table {:spec [map? #{{:foo 2}}]}}}
    [[:some-table {:a 1 :b 2}] {:foo 2}])

  (test-prep-doc {{:a 1 :b 2} {:foo 1 :bar "hey" :a 1 :b 2}}
    {:db db
     :rules {:some-table {:spec [map? #{{:foo 2 :bar "hey"}}]}}}
    [[:some-table {:a 1 :b 2}] {:foo 2
                                :db/update true}])

  (authorize-write
    {:rules {:some-table {:create (constantly true)}}}
    {:table :some-table
     :op :create})

  (authorize-write
    {:rules {:some-table {:create (constantly false)}}}
    {:table :some-table
     :op :create})

  (authorize-write
    {}
    {:table :some-table
     :op :create})

  (with-db {{:a 1 :b 2} {:foo 1}}
    (authorize-tx
      {:db db
       :tx {[:some-table {:a 1 :b 2}] {:foo 2}}
       :rules {:some-table {:spec [map? (s/keys :req-un [::foo])]
                            :update (constantly true)}}}))

  (with-db {{:a 1 :b 2} {:foo 1}}
    (authorize-tx
      {:db db
       :tx {[:some-table {:a 1 :b 2}] {:foo 2}}
       :rules {:some-table {:spec [map? (s/keys :req-un [::foo])]
                            :update (constantly false)}}}))

(with-db {{:a 1 :b 2} {:foo 1}}
  (authorize-tx
    {:db db
     :tx {[:some-table {:a 1 :b 2}] {:foo 2}}
     :rules {:some-table {:spec [map? (s/keys :req-un [::foo ::bar])]
                          :update (constantly true)}}}))

(with-db {{:a 1 :b 2} {:foo 1}}
  (authorize-tx
    {:db db
     :tx "hi"
     :rules {:some-table {:spec [map? (s/keys :req-un [::foo])]
                          :update (constantly true)}}}))

(with-db {{:a 1 :b 2} {:foo 1}}
  (authorize-tx
    {:current-time "hello"
     :db db
     :tx {[:some-table {:a 1 :b 2}] {:foo :db/current-time}}
     :rules {:some-table {:spec [map? (s/keys :req-un [::foo])]
                          :update (constantly true)}}}))

(authorize-read
  {:table :some-table
   :query {:id nil}
   :doc {:crux.db/id {:a 1 :b 2}
         :foo "hey"}
   :rules {:some-table {:spec [map? (u/only-keys :req-un [::foo])]
                        :get (constantly true)}}})

(authorize-read
  {:table :some-table
   :query {:query nil}
   :doc {:crux.db/id {:a 1 :b 2}
         :foo "hey"}
   :rules {:some-table {:spec [map? (u/only-keys :req-un [::foo])]
                        :get (constantly true)}}})

(authorize-read
  {:table :some-table
   :query {:id nil}
   :doc {:crux.db/id {:a 1 :b 2}
         :foo "hey"}
   :rules {:some-table {:spec [map? (u/only-keys :req-un [::foo ::bar])]
                        :get (constantly true)}}})

(authorize-read
  {:table :some-table
   :query {:id nil}
   :doc {:crux.db/id {:a 1 :b 2}
         :foo "hey"
         :bar "hello"}
   :rules {:some-table {:spec [map? (u/only-keys :req-un [::foo])]
                        :get (constantly true)}}})


;(with-redefs [authorize-read :doc]
;  (with-db {}
;    (subscribe*
;      {:db db
;       :doc {:crux.db/id {:a 1 :b 2}
;             :foo "hey"}
;       :rules {:some-table {:spec [map? (u/only-keys :req-un [::foo])]
;                            :get (constantly true)}}}
;      {:table :some-table
;       :id nil
;       :where nil
;       :args nil})))

(with-redefs [authorize-read :doc]
  (with-db {:foo {:a 1}}
    (subscribe*
      {:db db
       :uid "some-uid"}
      '{:table :some-table
        :id :foo
        :where nil
        :args nil})))

(with-redefs [authorize-read :doc]
  (with-db {:foo {:a 1}}
    (subscribe*
      {:db db
       :uid "some-uid"}
      '{:table :some-table
        :id :foo})))

(with-redefs [authorize-read :doc]
  (with-db {:foo {:a 1}}
    (subscribe*
      {:db db
       :uid "some-uid"}
      '{:table :some-table
        :where [[(println "you got pwned")]]})))

(u/pprint
  (with-redefs [authorize-read :doc]
    (with-db {:foo {:a 1}
              :bar {:a 1}}
      (subscribe*
        {:db db
         :uid "some-uid"}
        '{:table :some-table
          :where [[:a a]
                  [(== a #{1 2 3})]]
          :args {a 1}}))))



(query-contains? {:id :foo}
  {:crux.db/id :bar :a 1})

(query-contains?
  {:id
   {:content-type :music,
    :provider :lastfm,
    :provider-id ["Breaking Benjamin" "The Diary of Jane"]}}
  {:title "The Diary of Jane",
   :author "Breaking Benjamin",
   :url
   "https://www.last.fm/music/Breaking+Benjamin/_/The+Diary+of+Jane",
   :image
   "https://lastfm.freetls.fastly.net/i/u/174s/2e5b0bc8cf774381c37c150f159e58c4.png",
   :crux.db/id
   {:content-type :music,
    :provider :lastfm,
    :provider-id ["Breaking Benjamin" "The Diary of Jane"]},
   :content-type :music,
   :provider :lastfm,
   :provider-id ["Breaking Benjamin" "The Diary of Jane"]})

(u/pprint
  (get-id->doc
    {:bypass-auth true
     :query {:id :foo}
     :id->change {:foo [nil
                        {:crux.db/id :foo
                         :a 1}]
                  :bar [{:crux.db/id :bar
                         :a 1}
                        nil]}}))
(u/pprint
  (get-id->doc
    {:bypass-auth true
     :query {:id :foo}
     :id->change {:foo [nil
                        {:crux.db/id :foo
                         :a 1}]
                  :bar [{:crux.db/id :bar
                         :a 1}
                        nil]}}))

(u/pprint
  (with-redefs [authorize-read (constantly (u/anom :forbidden))]
    (changesets* {:id->change {:foo [nil
                                     {:crux.db/id :foo
                                      :a 1}]
                               :bar [{:crux.db/id :bar
                                      :a 1}
                                     nil]}
                  :subscriptions
                  {"bob" {{:id :foo} {:uid "bob-uid"
                                      :bypass-auth true
                                      :table :some-table}}
                   "alice" {{:where [[:a 1]]} {:uid "alice-uid"
                                               :bypass-auth true
                                               :table :some-table}
                            {:id :bar} {:uid "alice-uid"
                                        :table :some-other-table}}}})))


(prn (query-contains? '{:where [[:provider] [(uuid? doc)]]}
       {:provider-id 78490,
        :provider :thetvdb,
        :content-type :tv-show,
        :event-type :pick,
        :timestamp #inst "2020-04-18T04:47:27.096-00:00",
        :uid #uuid "676589b8-5a80-433e-9368-c6712b0b569d",
        :crux.db/id #uuid "c0a0331c-9d32-4732-aeaf-2e09507df882"}))

(prn (query-contains? '{:where [[:provider] [(map? doc)]]}
       {:provider-id 78490,
        :provider :thetvdb,
        :content-type :tv-show,
        :event-type :pick,
        :timestamp #inst "2020-04-18T04:47:27.096-00:00",
        :uid #uuid "676589b8-5a80-433e-9368-c6712b0b569d",
        :crux.db/id #uuid "c0a0331c-9d32-4732-aeaf-2e09507df882"}))


)
