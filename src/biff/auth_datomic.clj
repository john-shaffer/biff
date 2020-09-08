(ns biff.auth-datomic
  (:require
    [datomic.client.api :as d]
    [ring.middleware.anti-forgery :as anti-forgery]
    [biff.datomic :as bdat]
    [byte-streams :as bs]
    [crypto.random :as random]
    [trident.util :as u]
    [trident.jwt :as tjwt]
    [clojure.string :as str]
    [cemerick.url :as url]
    [byte-transforms :as bt]))

(defn get-key [{:keys [biff/db k] :as env}]
  (or (ffirst
        (d/q '[:find ?value
               :in $ ?key
               :where
               [?e :db/ident ?key]
               [?e :biff.auth.key/value ?value]]
          db k))
    (doto (bs/to-string (bt/encode (random/bytes 16) :base64))
      (#(bdat/submit-tx
          env
          [{:db/ident k :biff.auth.key/value %}])))))

(defn jwt-key [env]
  (get-key (assoc env :k :jwt-key)))

(defn signin-token [jwt-key claims]
  (tjwt/encode
    (merge
      claims
      {:iss "biff"
       :iat (u/now)
       :exp (u/add-seconds (u/now) (* 60 30))})
    {:secret jwt-key
     :alg :HS256}))

(defn signin-link [{:keys [claims url] :as env}]
  (let [jwt (signin-token (jwt-key env) (update claims :email str/trim))]
    (-> url
      url/url
      (assoc :query {:token jwt})
      str)))

(defn email= [s1 s2]
  (.equalsIgnoreCase s1 s2))

(defn send-signin-link [{:keys [params params/email biff/base-url template location]
                         :biff.auth/keys [send-email]
                         :as env}]
  (let [link (signin-link (assoc env
                            :claims params
                            :url (str base-url "/api/signin")))]
    (send-email (merge env
                  {:to email
                   :template template
                   :data {:biff.auth/link link}})))
  {:status 302
   :headers/Location location})

(defn signin [{:keys [params/token session biff/db biff/node]
               :biff.auth/keys [on-signin on-signin-fail]
               :as env}]
  (if-some [{:keys [email] :as claims}
            (-> token
              (tjwt/decode {:secret (jwt-key env)
                            :alg :HS256})
              u/catchall)]
    (let [tx (d/transact node
               {:tx-data
                [{:user/email email
                  :user/last-signed-in (u/now)}]})
          uid (-> tx :tx-data second .-e)]
      (prn "TODO: biff.auth/signin handle claims")
      {:status 302
       :headers/Location on-signin
       :cookies/csrf {:path "/"
                      :max-age (* 60 60 24 90)
                      :value (force anti-forgery/*anti-forgery-token*)}
       :session (assoc session :uid uid)})
    {:status 302
     :headers/Location on-signin-fail}))

(defn signout [{:keys [biff.auth/on-signout]}]
  {:status 302
   :headers/Location on-signout
   :cookies/ring-session {:value "" :max-age 0}
   :cookies/csrf {:value "" :max-age 0}
   :session nil})

(defn signed-in? [req]
  {:status (if (-> req :session/uid some?)
             200
             403)})

(defn route [{:biff.auth/keys [on-signup on-signin-request] :as sys}]
  ["/api"
   ["/signup" {:post #(send-signin-link (assoc %
                                          :template :biff.auth/signup
                                          :location (or on-signup on-signin-request)))
               :name ::signup}]
   ["/signin-request" {:post #(send-signin-link (assoc %
                                                  :template :biff.auth/signin
                                                  :location on-signin-request))
                       :name ::signin-request}]
   ["/signin" {:get signin
               :name ::signin
               :middleware [anti-forgery/wrap-anti-forgery]}]
   ["/signout" {:get signout
                :name ::signout}]
   ["/signed-in" {:get signed-in?
                  :name ::signed-in}]])
