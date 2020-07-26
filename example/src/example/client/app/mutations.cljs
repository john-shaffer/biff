(ns example.client.app.mutations
  (:require
    [clojure.pprint :refer [pprint]]
    [example.client.app.db :as db]
    [example.client.app.system :as s]))

(defmulti api (comp first :?data))
(defmethod api :default
  [{[event-id] :?data} data]
  (println "unhandled event:" event-id))

(defmethod api :biff/error
  [_ anom]
  (pprint anom))

(defmethod api :example/prn
  [_ arg]
  (prn arg))

(defn api-send [& args]
  (apply (:api-send @s/system) args))

(defn set-display-name [display-name]
  (api-send
    [:biff/tx
     [{:db/id @db/uid
       :user.public/display-name (str display-name)}]]))

(defn set-game-id [game-id]
  (let [old-id @db/game-id
        uid @db/uid]
    (when (not= game-id old-id)
      (api-send
        [:biff/tx
         (cond-> []
           (not-empty old-id)
           (conj [:db/retract [:biff-example.game/id old-id]
                  :biff-example.game/users uid])

           (not-empty game-id)
           (conj #:biff-example.game{:id game-id :users [uid]}))]))))

(defn move [location]
  (api-send [:example/move {:game-id @db/game-id :location location}]))

(defn new-game []
  (api-send [:example/new-game {:game-id @db/game-id}]))
