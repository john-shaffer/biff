(ns example.schema
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [datomic.client.api :as d]))

(def schema-names
  '[biff.auth.key
    biff-example.game
    user
    user.public])

(defn get-schema []
  (mapcat (comp edn/read-string slurp io/resource
            #(str "schema/biff-example/" % ".edn"))
    schema-names))

(defn load-schema [client db-name]
  (d/transact (d/connect client {:db-name db-name})
    {:tx-data (get-schema)}))
