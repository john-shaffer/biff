(ns example.client.app.db
  (:require
    [example.logic :as logic]
    [trident.util :as u]
    [rum.core]))

(defonce db (atom {}))

; same as (do (rum.core/cursor-in db [:sub-data]) ...)
(u/defcursors db
  sub-data [:sub-data])

; same as (do
;           (rum.core/derived-atom [sub-data] :example.client.app.db/data
;             (fn [sub-data]
;               (apply merge-with merge (vals sub-data))))
;           ...)
(u/defderivations [sub-data] example.client.app.db
  data (apply merge-with merge (vals sub-data))

  uid (get-in data [:uid nil :uid])
  id->users (:users data)
  self (get id->users uid)
  email (:user/email self)
  signed-in (and (some? uid) (not= :signed-out uid))

  id->public-users (:public-users data)
  public-self (get id->public-users uid)
  display-name (:user.public/display-name public-self)

  game (->> data
         :games
         vals
         (filter (fn [x]
                   (some #(when (= uid (:db/id %)) %)
                     (:biff-example.game/users (first x)))))
         ffirst)
  game-id (:biff-example.game/id game)

  participants (:users game)
  x (:x game)
  o (:o game)
  board (:board game)

  current-player (get game (logic/current-player game))
  winner (get game (logic/winner game))
  game-over (logic/game-over? game)
  draw (and game-over (not winner))

  biff-subs [; :uid is a special non-Crux query. Biff will respond
             ; with the currently authenticated user's ID.
             :uid
             (when signed-in
               [{:table :users
                 :id uid}
                {:table :public-users
                 :id uid}
                {:table :games
                 :where [[:biff-example.game/users uid]]}])
             (for [u (:users game)]
               {:table :public-users
                :id {:user.public/id u}})]
  subscriptions (->> biff-subs
                  flatten
                  (filter some?)
                  (map #(vector :biff/sub %))
                  set))
