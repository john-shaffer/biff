---
title: Biff

language_tabs: # must be one of https://git.io/vQNgJ
  - Clojure

toc_footers:

includes:

search: true
---

# Introduction

Biff is designed to make web development with Clojure fast and easy, especially
for early stage startups and hobby projects. Over time I'd like to make it
suitable for apps that need scale as well. I use it in production for <a
href="https://findka.com" target="_blank">Findka</a>, my startup. It is
currently alpha quality and may have breaking changes.

I started writing Biff after 18 months
of experimenting with various web technologies like Firebase, Datomic and
several Clojure web frameworks/libraries. It includes:

- **Installation and deployment** on DigitalOcean (or other VPS).
- **Crux** (an immutable document database with Datalog queries).
- **Subscriptions**. Specify what data the frontend needs declaratively, and
  Biff will keep it up-to-date.
- **Read/write authorization rules**. No need to set up a bunch of endpoints
  for CRUD operations. Queries and transactions can be submitted from the
  frontend as long as they pass the rules you define.
- **Database triggers**. Run code when documents of certain types are created,
  updated or deleted.
- **Authentication**. Email link for now; password and SSO coming later.
- **Websocket communication**.
- Serving **static resources**.
- **Multitenancy**. Run multiple apps from the same process.
- Less than **2K lines of code**.

Also: instead of trying to do everything for everyone, Biff is designed to be
easy to take apart (without forking). This should help mitigate the main
drawback of frameworks, which is that it's often less work in the long run to
just stitch the libraries together yourself.

## Resources

 - Join `#biff` on <a href="http://clojurians.net" target="_blank">Clojurians Slack</a> for
discussion. Feel free to reach out for help, bug reports or anything else.
 - See the issues and source on <a href="https://github.com/jacobobryant/biff"
target="_blank">Github</a>
 - Watch <a
href="https://www.youtube.com/watch?v=oYwhrq8hDFo" target="_blank">a
presentation</a> I gave at the Clojure Mid-Cities meetup.
 - See the <a href="#faq">FAQ</a> section for comparison to other frameworks.

## Contributing

For hacking on Biff, change the example project's dependency to `:local/root
".."`. PRs welcome, but I recommend contacting me on `#biff` first.

# Getting started

The fastest way to get started with Biff is by cloning the Github repo and running the
official example project (an implementation of Tic Tac Toe):

1. Install dependencies: <a href="https://clojure.org/guides/getting_started" target="_blank">Clojure</a>,
   <a href="https://www.npmjs.com/get-npm" target="_blank">NPM</a>.
2. `git clone https://github.com/jacobobryant/biff`
3. `cd biff/example`
4. `./task setup`
5. `./task dev`
6. Go to `localhost:9630` and start the `app` build.
7. Go to `localhost:8080`

**Note:** if you're on Windows/don't have Bash, you'll need to run the
commands inside `task` individually (translated to whatever shell you're
using). Also, you may need to install
<a href="https://www.microsoft.com/en-us/download/details.aspx?id=48145" target="_blank">this package</a>
(see <a href="https://github.com/facebook/rocksdb/issues/2531" target="_blank">facebook/rocksdb#2531</a>).

You can tinker with this app and use it as a template for your own projects. See
[Production](#production) when you want to deploy.

# Build system

The example project uses tools.deps, Shadow CLJS, and Overmind.
`task` is the main entrypoint:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/task" target="_blank">
task</a></div>
```bash
#!/bin/bash
set -e

setup () {
  which clj npm > /dev/null # Assert dependencies
  npm install # Or `npm init; npm install --save-dev shadow-cljs; npm install --save react react-dom`
}

dev () {
  clj -Sresolve-tags
  BIFF_ENV=dev clj -A:cljs "$@" -m biff.core
}

...

"$@"
```

You can easily add new build tasks by creating new functions in `task`. Also, I
recommend putting `alias t='./task'` in your `.bashrc`.

`./task dev` will fetch the latest Biff version for you (via `clj -Sresolve-tags`). To update Biff,
remove the `:sha "..."` key from `deps.edn` and run `./task dev` again.

# Backend entrypoint

When you run `clj -m biff.core`, Biff searches the classpath for plugins and then starts
them in a certain order. To define a plugin, you must set `^:biff` metadata on a namespace
and then set a `components` var to a list of plugin objects in that namespace:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/core.clj" target="_blank">
src/example/core.clj</a></div>
```clojure
(ns ^:biff example.core
  (:require
    [biff.system]
    [clojure.pprint :refer [pprint]]
    [example.handlers]
    [example.routes]
    [example.rules]
    [example.static]
    [example.triggers]))

(defn send-email [opts]
  (pprint [:send-email opts]))

(defn start-example [sys]
  (-> sys
    (merge #:example.biff.auth{:send-email send-email
                               :on-signup "/signin/sent/"
                               :on-signin-request "/signin/sent/"
                               :on-signin-fail "/signin/fail/"
                               :on-signin "/app/"
                               :on-signout "/"})
    (merge #:example.biff{:routes example.routes/routes
                          :static-pages example.static/pages
                          :event-handler #(example.handlers/api % (:?data %))
                          :rules example.rules/rules
                          :triggers example.triggers/triggers})
    (biff.system/start-biff 'example)))

(def components
  [{:name :example/core
    :requires [:biff/init]
    :required-by [:biff/web-server]
    :start start-example}])
```

`:biff/init` and `:biff/web-server` are plugins defined in the `biff.core`
namespace. The `:requires` and `:required-by` values are used to start plugins
in the right order. You can think of plugins kind of like Ring middleware. They
receive a system map and pass along a modified version of it.

`biff.system/start-biff` starts a Biff app. That includes initializing:

 - a Crux node
 - a Sente websocket listener and event router
 - some default event handlers that handle queries, transactions and subscriptions from the frontend
 - a Crux transaction listener, so clients can be notified of subscription updates
 - some Reitit HTTP routes for authentication
 - any static resources you've included with your app

If you connect to nrepl port 7888, you can access the system with
`@biff.core/system`. Biff provides some helper functions for repl-driven
development:

```clojure
(biff.core/stop)
(biff.core/start)
(biff.core/refresh) ; stops, reloads namespaces from filesystem, starts.
```

# Configuration

App-specific configuration can go in your plugin, as shown above. For example, we set
`:example.biff.auth/on-signin` so that clients will be redirected to `/app/` after they
sign in successfully.

Environment-specific configuration and secrets can go in `config.edn`. They will be read in
by the `:biff/init` plugin.

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/config.edn" target="_blank">
config.edn</a></div>
```clojure
{:prod {; If you set jdbc credentials, remove this file from source control!
        :biff.crux.jdbc/dbname "..."
        :biff.crux.jdbc/user "..."
        :biff.crux.jdbc/password "..."
        :biff.crux.jdbc/host "..."
        :biff.crux.jdbc/port ...
        :example.biff/host "example.com"
        :example.mailgun/api-key "..."}
 :dev {:inherit [:prod]
       :biff/dev true
       :example.biff/host "localhost"}}
```

<aside class="warning">Keep this file out of source control if it contains any secrets.</aside>

The system map is mostly flat, with namespaced keys. For example, Biff
configuration for the example app is stored under the `:example.biff` namespace.
You could run multiple Biff apps in the same process by using a different
namespace, e.g. `(biff.system/start-biff sys 'another-app)`. Keys under
the `:biff` namespace (e.g. `:biff/dev` from `config.edn` above) will become
defaults for all Biff apps.

Similarly, the return values from `biff.system/start-biff` will be namespaced. For example,
you can get the Crux node in our example app with `(:example.biff/node @biff.core/system)`.

When the `:biff/init` plugin reads in your `config.edn` file, it will merge the
nested maps according to the current environment and the value of `:inherit`.
The default environment is `:prod`. This can be overridden by setting the
`BIFF_ENV` environment variable:

```shell
BIFF_ENV=dev clj -m biff.core
```

Here is a complete list of configuration options and their default values. See the following sections
for a deeper explanation.

Note: `:foo/*` is used to denote all keywords prefixed by `:foo/` or `:foo.`.

```clojure
; === Config for the :biff/init plugin ===

:biff.init/start-nrepl true
:biff.init/nrepl-port 7888
:biff.init/start-shadow false
:biff.init/instrument false ; Calls orchestra.spec.test/instrument if true.
:biff.init/timbre true
:timbre/* ...               ; If :biff.init/timbre is true, these keys are passed to
                            ; taoensso.timbre/merge-config! (without the timbre prefix).
:biff/dev false ; When true, sets the following config options (overriding any specified values):
                ; {:biff.init/start-shadow true
                ;  :biff.init/start-nrepl false} ; shadow-cljs has its own nrepl server.

; === Config for biff.system/start-biff ===
; Note: app-ns is the second parameter in biff.system/start-biff

; REQUIRED
:biff/host nil          ; The hostname this app will be served on, e.g. "example.com" for prod
                        ; or "localhost" for dev.

; RECOMMENDED
:biff/static-pages nil  ; A map from paths to Rum data structures, e.g.
                        ; {"/hello/" [:html [:body [:p {:style {:color "red"}} "hello"]]]}
:biff/rules nil         ; An authorization rules data structure.
:biff/fn-whitelist nil  ; Collection of fully-qualified function symbols to allow in
                        ; Crux queries sent from the frontend. Functions in clojure.core
                        ; need not be qualified. For example: '[map? example.core/frobulate]
:biff/triggers nil      ; A database triggers data structure.
:biff/routes nil        ; A vector of Reitit routes.
:biff/event-handler nil ; A Sente event handler function.

:biff.auth/send-email nil ; A function.
:biff.auth/on-signup nil  ; Redirect route, e.g. "/signup/success/".
:biff.auth/on-signin-request nil
:biff.auth/on-signin-fail nil
:biff.auth/on-signin nil
:biff.auth/on-signout nil

; Ignored if :biff.crux/topology isn't :jdbc.
:biff.crux.jdbc/dbname nil
:biff.crux.jdbc/user nil
:biff.crux.jdbc/password nil
:biff.crux.jdbc/host nil
:biff.crux.jdbc/port nil

:biff.handler/spa-path nil ; If set, takes precedence over not-found-path (and sets http
                           ; status to 200).

:biff/dev false ; When true, sets the following config options (overriding any specified values):
                ; {:biff/using-proxy false
                ;  :biff.crux/topology :standalone
                ;  :biff.handler/secure-defaults false
                ;  :biff.static/root-dev "www-dev"}

; OPTIONAL
:biff.crux/topology :jdbc ; One of #{:jdbc :standalone}
:biff.crux/storage-dir "data/{{app-ns}}/crux-db" ; Directory to store Crux files.
:biff.crux.jdbc/* ...     ; Passed to crux.api/start-node (without the biff prefix) if
                          ; :biff.crux/topology is :jdbc. In this case, you must set
                          ; :biff.crux.jdbc/{user,password,host,port}.
:biff.crux.jdbc/dbtype "postgresql"

:biff.static/root "www/{{value of :biff/host}}" ; Directory from which to serve static files.
:biff.static/root-dev nil                       ; An additional static file directory.
:biff.static/resource-root "www/{{app-ns}}"     ; Resource directory where static files are stored.

:biff.handler/not-found-path "{{value of :biff.static/root}}/404.html"
:biff.handler/secure-defaults true ; Whether to use ring.middleware.defaults/secure-site-defaults
                                   ; or just site-defaults.

:biff/using-proxy (not= {{value of :biff/host}} "localhost") ; Used for setting :biff/base-url.


; === Config for the :biff/web-server plugin ===

:biff.web/host->handler ... ; Set by biff.system/start-biff. A map used to dispatch Ring
                            ; requests. For example:
                            ; {"localhost" (constantly {:status 200 ...})
                            ;  "example.com" (constantly {:status 200 ...})}
:biff.web/host "localhost"  ; Host for the web server to listen on. localhost is used in prod
                            ; because requests are proxied through Nginx.
:biff.web/port 8080         ; Port for the web server to listen on. Also used in
                            ; biff.system/start-biff.
:biff/dev false ; When true, sets the following config options (overriding any specified values):
                ; {:biff.web/host "0.0.0.0"}
```

The following keys are added to the system map by `biff.system/start-biff`:

 - `:biff/base-url`: e.g. `"https://example.com"` or `"http://localhost:8080"`
 - `:biff/node`: the Crux node.
 - `:biff/send-event`: the value of `:send-fn` returned by `taoensso.sente/make-channel-socket!`.
 - `:biff.sente/connected-uids`: Ditto but for `:connected-uids`.
 - `:biff.crux/subscriptions`: An atom used to keep track of which clients have subscribed
   to which queries.

`biff.system/start-biff` merges the system map into incoming Ring requests and
Sente events. It also adds `:biff/db` (a Crux DB value) on each new
request/event. Note that the keys will be prefixed just before `start-biff`
returns&mdash;so within a request/event handler, you'd use `:biff/node` to get
the Crux node, but within a subsequent Biff plugin you'd use e.g.
`:example.biff/node`.

# Static resources

Relevant config:

```clojure
:biff/static-pages nil  ; A map from paths to Rum data structures, e.g.
                        ; {"/hello/" [:html [:body [:p "hello"]]]}

:biff.static/root "www/{{value of :biff/host}}" ; Directory from which to serve static files.
:biff.static/root-dev nil                       ; An additional static file directory.
:biff.static/resource-root "www/{{app-ns}}"     ; Resource directory where static files are stored.

:biff/dev false ; When true, sets the following config options (overriding any specified values):
                ; {:biff.static/root-dev "www-dev"
                ;  ...}
```

Biff will copy your static resources to `www/yourwebsite.com/` (i.e. the value
of `:biff.static/root`). In production, `www/` is a symlink to `/var/www/` and
is served directly by Nginx (so static files will be served even if your JVM
process goes down, e.g. during deployment). In development, the JVM process
will serve files from that directory.

Biff gets static resources from two places. First, it will render HTML
files from the value of `:biff/static-pages` on startup.

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/static.clj" target="_blank">
src/example/static.clj</a></div>
```clojure
(ns example.static
  (:require
    [rum.core :as rum]))

(def signin-form
  (rum/fragment
    [:p "Email address:"]
    [:form {:action "/api/signin-request" :method "post"}
     [:input {:name "email" :type "email" :placeholder "Email"}]
     [:button {:type "submit"} "Sign up/Sign in"]]))

(def home
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:script {:src "/js/ensure-signed-out.js"}]]
   [:body
    signin-form]])

(def signin-sent
  [:html [:head [:meta {:charset "utf-8"}]]
   [:body
    [:p "Sign-in link sent, please check your inbox."]
    [:p "(Just kidding: click on the sign-in link that was printed to the console.)"]]])

(def signin-fail
  [:html [:head [:meta {:charset "utf-8"}]]
   [:body
    [:p "Invalid sign-in token."]
    signin-form]])

(def app
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:script {:src "/js/ensure-signed-in.js"}]]
   [:body
    [:#app "Loading..."]
    [:script {:src "/cljs/app/main.js"}]]])

(def not-found
  [:html [:head [:meta {:charset "utf-8"}]]
   [:body
    [:p "Not found."]]])

(def pages
  {"/" home
   "/signin/sent/" signin-sent
   "/signin/fail/" signin-fail
   "/app/" app
   "/404.html" not-found})
```

Second, Biff will look for files in the resource directory specified
by `:biff.static/resource-root`.

```bash
biff/example $ tree resources/
resources/
└── www
    └── example
        └── js
            ├── ensure-signed-in.js
            └── ensure-signed-out.js
```

I currently commit generated resources (except for HTML files, but including
CLJS compilation output) to the git repo. If you prefer, you can easily add
initialization code to your app that instead generates the resources during
deployment (or downloads them from a CI server).

I'd like to add a CDN integration eventually.

# Authentication

Relevant config:

```clojure
:biff.auth/send-email nil ; A function.
:biff.auth/on-signup nil  ; Redirect route, e.g. "/signup/success/".
:biff.auth/on-signin-request nil
:biff.auth/on-signin-fail nil
:biff.auth/on-signin nil
:biff.auth/on-signout nil
```

Biff currently provides email link authentication (i.e. the user clicks a link
in an email to sign in). Password and SSO authentication are on the roadmap.

Biff provides a set of HTTP endpoints for authentication:

## Sign up

Sends the user an email with a sign-in link. The token included in the link
will expire after 30 minutes. Redirects to the value of `:biff.auth/on-signup`.

### HTTP Request

`POST /api/signup`

### Form Parameters

Parameter | Description
----------|------------
email | The user's email address.

### Example Usage

```clojure
[:p "Email address:"]
[:form {:action "/api/signup" :method "post"}
 [:input {:name "email" :type "email" :placeholder "Email"}]
 [:button {:type "submit"} "Sign up"]]
```

The `:biff.auth/send-email` function will be called with the following options:

```clojure
(send-email (merge
              ring-request
              {:to "alice@example.com"
               :template :biff.auth/signup
               :data {:biff.auth/link "https://example.com/api/signin?token=..."}}))
```

You will have to provide a `send-email` function which generates an email from
the template data and sends it. (The example app just prints the template data
to the console). If you use Mailgun, you can do something like this:

```clojure
(def templates
  {:biff.auth/signup
   (fn [{:keys [biff.auth/link to]}]
     {:subject "Thanks for signing up"
      :html (rum.core/render-static-markup
              [:div
               [:p "We received a request to sign up with Findka using this email address."]
               [:p [:a {:href link} "Click here to sign up."]]
               [:p "If you did not request this link, you can ignore this email."]])})
   :biff.auth/signin ...})

(defn send-email* [api-key opts]
  (http/post (str "https://api.mailgun.net/v3/mail.example.com/messages")
    {:basic-auth ["api" api-key]
     :form-params (assoc opts :from "Example <contact@mail.example.com>")}))

(defn send-email [{:keys [to text template data api-key] :as opts}]
  (if (some? template)
    (if-some [template-fn (get templates template)]
      (send-email* api-key
        (assoc (template-fn (assoc data :to to)) :to to))
      (println "Email template not found:" template))
    (send-email* api-key (select-keys opts [:to :subject :text :html]))))

(defn start-example [{:keys [example.mailgun/api-key] :as sys}]
  (-> sys
    (merge
      {:example.biff.auth/send-email #(send-email (assoc % :api-key api-key))
       ...})
    (biff.system/start-biff 'example.biff)))
```

### Dealing with bots

The above example is susceptible to abuse from bots. An account isn't created
until the user clicks the confirmation link, but it's better not to send emails
to bots/spam victims in the first place. You'll need to use your own method for
deciding if signups come from bots. The map passed to `send-email` includes the
Ring request specifically so you can check the form parameters and make that
decision.

If you render the login form with JS, you may not need to deal with this for a
while. If you render it statically (like in the example app), you'll have to
deal with it sooner.

## Request sign-in

Sends the user an email with a sign-in link. This is the same as [Sign up](#sign-up),
except:

 - The endpoint is `/api/signin-request`
 - The template key will be set to `:biff.auth/signin`
 - The user will be redirected to the value of `:biff.auth/on-signin-request`

## Sign in

Verifies the given JWT and adds a `:uid` key to the user's session (expires in
90 days). Also sets a `:csrf` cookie, the value of which
must be included in the `X-CSRF-Token` header for any HTTP requests that
require authentication.

If this is the first sign in, creates a user document in Crux with the email
and a random UUID.

Redirects to the value of `:biff.auth/on-signin` (or
`:biff.auth/on-signin-fail` if the token is incorrect or expired).


### HTTP Request

`GET /api/signin`

### URL Parameters

Parameter | Description
----------|------------
token | A JWT

### Example Usage

This endpoint is used by the link generated by [Sign up](#sign-up) and [Request
sign-in](#request-sign-in), so you typically won't need to generate a link for
this endpoint yourself. However, if you'd like to use a longer expiration date for the
token or authenticate at a custom endpoint, you can do it like so:

```clojure
(trident.jwt/url {:url (str (:biff/base-url sys) "/api/unsubscribe")
                  :claims {:email "alice@example.com"
                           :uid "abc123"}
                  :jwt-secret (biff.auth/jwt-key sys)
                  :iss "example"
                  :expires-in (* 60 60 24 7 4)})

; Or for just the token:
(trident.jwt/mint {:secret (biff.auth/jwt-key sys)
                   :iss "example"
                   :expires-in (* 60 60 24 7 4)}
                  {:email "alice@example.com"
                   :uid "abc123"})
```

After a user is signed in, you can authenticate them on the backend from an event/request
handler like so:

```clojure
(defn handler [{:keys [session/uid biff/db]}]
  (if (some? uid)
    (prn (crux.api/entity db {:user/id uid}))
    ; => {:crux.db/id {:user/id #uuid "..."}
    ;     :user/id #uuid "..." ; duplicated for query convenience
    ;     :user/email "alice@example.com"}
    (println "User not authenticated.")))
```

## Sign out

Clears the user's session and `:csrf` cookie. Redirects to the value of
`:biff.auth/on-signout`.

See <a href="https://github.com/jacobobryant/biff/issues/26" target="_blank">#26</a>.

### HTTP Request

`GET /api/signout`

### Example Usage

```clojure
[:a {:href "/api/signout"} "sign out"]
```

## Check if signed in

Returns status 200 if the user is authenticated, else 403.

See <a href="https://github.com/jacobobryant/biff/issues/26" target="_blank">#26</a>.

### HTTP Request

`GET /api/signed-in`

### Example Usage

Include this on your landing page:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/resources/www/example/js/ensure-signed-out.js" target="_blank">
resources/www/example/js/ensure-signed-out.js</a></div>
```javascript
fetch("/api/signed-in").then(response => {
  if (response.status == 200) {
    document.location = "/app";
  }
});
```

Include this on your app page:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/resources/www/example/js/ensure-signed-in.js" target="_blank">
resources/www/example/js/ensure-signed-in.js</a></div>
```javascript
fetch("/api/signed-in").then(response => {
  if (response.status != 200) {
    document.location = "/";
  }
});
```

# Client/server communication

Relevant config:

```clojure
:biff/routes nil           ; A vector of Reitit routes.
:biff/event-handler nil    ; A Sente event handler function.
:biff.handler/not-found-path "{{value of :biff.static/root}}/404.html"
:biff.handler/spa-path nil ; If set, takes precedence over not-found-path (and sets http
                           ; status to 200).
:biff.handler/secure-defaults true ; Whether to use ring.middleware.defaults/secure-site-defaults
                                   ; or just site-defaults.
:biff/dev false            ; When true, sets the following config options (overriding any
                           ; specified values):
                           ; {:biff.handler/secure-defaults false}
```

## HTTP

The value of `:biff/routes` will be wrapped with some default middleware which, among other things:

 - Applies a modified version of `ring.middleware.defaults/secure-site-defaults` (or `site-defaults`).
 - Merges the system map into the request (so you can access configuration and other things).
 - Sets `:biff/db` to a current Crux db value.
 - Flattens the `:session` and `:params` maps (so you can do e.g. `(:session/uid request)` instead
   of `(:uid (:session request))`).
 - Sets default values of `{:body "" :status 200}` for responses.
 - Nests any `:headers/*` or `:cookies/*` keys (so `:headers/Content-Type "text/plain"` expands
   to `:headers {"Content-Type" "text/plain"}`).

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/routes.clj" target="_blank">
src/example/routes.clj</a></div>
```clojure
(ns example.routes
  (:require
    [trident.util :as u]
    ...))

(defn echo [req]
  {:headers/Content-Type "application/edn"
   :body (prn-str
           (merge
             (select-keys req [:params :body-params])
             (u/select-ns req 'params)))})

(def routes
  [["/echo" {:get echo
             :post echo
             :name ::echo}]
   ...])
```

```shell
$ curl -XPOST localhost:8080/echo?foo=1 -F bar=2
{:params {:foo "1", :bar "2"}, :params/bar "2", :params/foo "1"}
$ curl -XPOST localhost:8080/echo -d '{:foo 1}' -H "Content-Type: application/edn"
{:params {}, :body-params {:foo 1}}
```

For endpoints that require authentication, you must wrap anti-forgery middleware. Also,
be sure not to make any `GET` endpoints that require authentication as these bypass anti-forgery
checks.

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/routes.clj" target="_blank">
src/example/routes.clj</a></div>
```clojure
(ns example.routes
  (:require
    [biff.http :as bhttp]
    [crux.api :as crux]
    [ring.middleware.anti-forgery :as anti-forgery]))

...

(defn whoami [{:keys [session/uid biff/db]}]
  (if (some? uid)
    {:body (:user/email (crux/entity db {:user/id uid}))
     :headers/Content-Type "text/plain"}
    {:status 401
     :headers/Content-Type "text/plain"
     :body "Not authorized."}))

(defn whoami2 [{:keys [session/uid biff/db]}]
  {:body (:user/email (crux/entity db {:user/id uid}))
   :headers/Content-Type "text/plain"})

(def routes
  [...
   ["/whoami" {:post whoami
               :middleware [anti-forgery/wrap-anti-forgery]
               :name ::whoami}]
   ; Same as whoami
   ["/whoami2" {:post whoami2
                :middleware [bhttp/wrap-authorize]
                :name ::whoami2}]])
```

When calling these endpoints, you must include the value of the `csrf` cookie in the
`X-CSRF-Token` header:

```clojure
(cljs-http.client/post "/whoami" {:headers {"X-CSRF-Token" (biff.client/csrf)}})
; => {:status 200, :body "alice@example.com", ...}
```

However, communicating over websockets is usually more convenient, in which
case this is unnecessary.

## Web sockets

Web sockets are the preferred method of communication. First, set `:biff/event-handler`:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/core.clj" target="_blank">
src/example/core.clj</a></div>
```clojure
(defn start-example [sys]
  (-> sys
    ...
    (merge #:example.biff{:event-handler #(example.handlers/api % (:?data %))
                          ...})
    (biff.system/start-biff 'example)))
```

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/handlers.clj" target="_blank">
src/example/handlers.clj</a></div>
```clojure
(ns example.handlers
  ...)

(defmulti api :id)

(defmethod api :default
  [{:keys [id]} _]
  (trident.util/anom :not-found (str "No method for " id)))

(defmethod api :example/move
  [{:keys [biff/db session/uid] :as sys} {:keys [game-id location]}]
  ...)

(defmethod api :example/echo
  [{:keys [client-id biff/send-event]} arg]
  (send-event client-id [:example/prn ":example/echo called"])
  arg)
```

Biff provides a helper function for initializing the web socket connection on the frontend:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/client/app.cljs" target="_blank">
src/example/client/app.cljs</a></div>
```clojure
(defn ^:export init []
  (reset! example.client.app.system/system
    (biff.client/init-sub {:handler example.client.app.mutations/api
                           ...}))
  ...)
```

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/client/app/mutations.cljs" target="_blank">
src/example/client/app/mutations.cljs</a></div>
```clojure
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
  (apply (:api-send @example.client.app.system/system) args))

(comment
  (go
    (<! (api-send [:example/echo {:foo "bar"}]))
    ; => {:foo "bar"}
    ; => ":example/echo called"
    ))
```

# Transactions

You can send arbitrary transactions from the frontend. They will be submitted
only if they pass certain authorization rules which you define (see
[Rules](#rules)). Transactions look like this:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/client/app/mutations.cljs" target="_blank">
src/example/client/app/mutations.cljs</a></div>

```clojure
(defn set-display-name [display-name]
  (api-send
    [:biff/tx
     {[:public-users {:user.public/id @db/uid}]
      {:db/merge true
       :display-name (or (not-empty display-name) :db/remove)}}]))

(defn set-game-id [game-id]
  (when (not= game-id @db/game-id)
    (api-send
      [:biff/tx
       (cond-> {}
         (not-empty @db/game-id)
         (assoc [:games {:game/id @db/game-id}]
           {:db/update true
            :users [:db/disj @db/uid]})

         (not-empty game-id)
         (assoc [:games {:game/id game-id}]
           {:db/merge true
            :users [:db/union @db/uid]}))])))
```

The transaction is a map from idents to documents. The first element of an
ident is a table, such as `:games`. Tables are defined by your rules, and they
specify which rules a document write must pass in order to be allowed.

The second element, if present, is a document ID. If omitted, it means we're
creating a new document and we want the server to set the ID to a random UUID:

```clojure
{[:messages] {:text "hello"}}
```

If you want to create multiple documents in the same table with random IDs, use
a nested vector instead of a map.

```clojure
[[[:messages] {:text "a"}]
 [[:messages] {:text "b"}]]
```

`:db/current-time` is replaced by the server with the current time.

```clojure
{[:events] {:timestamp :db/current-time
            ...}}
```

If `:db/update` is true, the given document will be merged with an existing
document, failing if the document doesn't exist. There's also `:db/merge` which
simply creates the document if it doesn't exist (i.e. upsert).

```clojure
{[:chatrooms {:chatroom/id #uuid "some-uuid"}]
 {:db/update true
  :title "Existing chatroom"}

 [:chatrooms {:chatroom/id #uuid "another-uuid"}]
 {:db/merge true
  :title "New or existing chatroom"}}
```

You can `dissoc` document keys by setting them to `:db/remove`. You can
delete whole documents by setting them to `nil`.

```clojure
{[:users {:user/id #uuid "my-id"}]
 {:db/update true
  :display-name :db/remove}

 [:orders {:order/id #uuid "some-order-id"}]
 nil}
```

You can add or remove an element to/from a set by using `:db/union` and
`:db/disj`, respectively:

```clojure
{[:games {:game/id #uuid "old-game-uuid"}]
 {:db/update true
  :users [:db/disj "my-uid"]}

 [:games {:game/id #uuid "new-game-uuid"}]
 {:db/update true
  :users [:db/union "my-uid"]}}
```

Using maps as document IDs lets you specify composite IDs. In addition, all
keys in in the document ID will be duplicated in the document itself. This
allows you to use document ID keys in your queries.

```clojure
{[:user-item {:user #uuid "some-user-id"
              :item #uuid "some-item-id"}]
 {:rating :like}}

; Expands to:
[:crux.tx/put
 {:crux.db/id {:user #uuid "some-user-id"
               :item #uuid "some-item-id"}
  :user #uuid "some-user-id"
  :item #uuid "some-item-id"
  :rating :like}]
```

# Subscriptions

Biff allows you to subscribe to Crux queries from the frontend with one major
caveat: cross-entity joins are not allowed. Basically, this means all the where
clauses in the query have to be for the same entity.

```clojure
; OK
'{:find [doc]
  :where [[doc :foo 1]
          [doc :bar "hey"]]}

; Not OK
'{:find [doc]
  :where [[user :name "Tilly"]
          [doc :user user]]}
```

So to be clear, Biff's subscribable "queries" are not datalog at all. They're
just predicates that can take advantage of Crux's indices. Biff makes this
restriction so that it can provide query updates to clients efficiently without
having to solve a hard research problem first. However, it turns out that we can
go quite far even with this restriction.

On the frontend, use `biff.client/init-sub` to initialize a websocket connection
that handles query subscriptions for you:

```clojure
(def default-subscriptions
  #{[:biff/sub '{:table :users
                 :where [[:name "Ben"]
                         [:age age]
                         [(<= 18 age)]
                         [(yourapp.core/likes-cheese? doc)]]}]})

(def subscriptions (atom default-subscriptions))
(def sub-data (atom {}))

(biff.client/init-sub
  {:subscriptions subscriptions
   :sub-data sub-data})
```


If you want to subscribe to a query, `swap!` it into `subscriptions`. If you
want to unsubscribe, `swap!` it out. Biff will populate `sub-data` with the
results of your queries and remove old data when you unsubscribe. You can then
use the contents of that atom to drive your UI. The contents of `sub-data` is a
map of the form `subscription->table->id->doc`, for example:

```clojure
{[:biff/sub '{:table :users
              :where ...}]
 {:users
  {{:user/id #uuid "some-uuid"} {:name "Sven"
                                 :age 250
                                 ...}}}}
```

Note the subscription format again:

```clojure
[:biff/sub '{:table :users
             :where [[:name "Ben"]
                     [:age age]
                     [(<= 18 age)]
                     [(yourapp.core/likes-cheese? doc)]]}]
```

The first element is a Sente event ID. The query map (the second element) omits
the entity variable in the where clauses since it has to be the same for each
clause anyway. But it will be bound to `doc` in case you want to use it in e.g.
a predicate function. `:find` is similarly omitted.

The `:table` value is connected to authorization rules which you define on the
backend (see [Rules](#rules)). When a client subscribes to this query, it will
be rejected unless you define rules for that table which allow the query. You
also have to whitelist any predicate function calls (like
`yourapp.core/likes-cheese?`), though the comparison operators (like `<=`) are
whitelisted for you.

I haven't yet added support for `or`, `not`, etc. clauses in subscriptions. See
<a href="https://github.com/jacobobryant/biff/issues/9" target="_blank">#9</a>.

You can also subscribe to individual documents:

```clojure
[:biff/sub '{:table :users
             :id {:user/id #uuid "some-uuid"}}]
```

All this is most powerful when you make the `subscriptions` atom a derivation of
`sub-data`:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/client/app.cljs" target="_blank">
src/example/client/app.cljs</a></div>
```clojure
(ns example.client.app
  (:require
    [biff.client :as bc]
    [example.client.app.db :as db]
    [example.client.app.mutations :as m]
    [example.client.app.system :as s]
    ...))

...

(defn ^:export init []
  (reset! s/system
    (bc/init-sub {:handler m/api
                  :sub-data db/sub-data
                  :subscriptions db/subscriptions}))
  ...)
```

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/client/app/db.cljs" target="_blank">
src/example/client/app/db.cljs</a></div>
```clojure
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
  signed-in (and (some? uid) (not= :signed-out uid))
  user-ref {:user/id uid}
  game (->> data
         :games
         vals
         (filter #(contains? (:users %) uid))
         first)

  ...

  biff-subs [; :uid is a special non-Crux query. Biff will respond
             ; with the currently authenticated user's ID.
             :uid
             (when signed-in
               [{:table :users
                 :id user-ref}
                {:table :public-users
                 :id {:user.public/id uid}}
                {:table :games
                 :where [[:users uid]]}])
             (for [u (:users game)]
               {:table :public-users
                :id {:user.public/id u}})]
  subscriptions (->> biff-subs
                  flatten
                  (filter some?)
                  (map #(vector :biff/sub %))
                  set))
```

When a user signs into the example app, the following will happen:

1. Client subscribes to `:uid` (i.e. `subscriptions` contains `#{[:biff/sub
:uid]}`).
2. `sub-data` is populated with the user's ID.
3. `signed-in` changes to `true` and `biff-subs` gets updated. The client is now
   subscribed to various information about the current user, including the current
   game (if they've joined one).
4. `sub-data` is populated with more data. The UI will display the user's
   email address, display name and current game. The client will subscribe to data
   about the other players (their display names).
5. The other players' display names will be loaded into `sub-data` and the UI will
   update again.

This is what I meant when I said that we can go pretty far without cross-entity
joins: using this method, we can declaratively load all the relevant data and
perform joins on the client. This should be sufficient for many situations.

However, it won't work if you need an aggregation of a set of documents that's
too large to send to the client (not to mention each client), or if the client
isn't allowed to see the individual documents. To handle that, I've been working on
a <a href="https://materialize.io" target="_blank">Materialize</a> integration.

# Rules

Relevant config:

```clojure
:biff/rules nil         ; An authorization rules data structure.
:biff/fn-whitelist nil  ; Collection of fully-qualified function symbols to allow in
                        ; Crux queries sent from the frontend. Functions in clojure.core
                        ; need not be qualified. For example: '[map? example.core/frobulate]
```

Your app's rules define what transactions and subscriptions will be accepted
from the frontend (see [Transactions](#transactions) and
[Subscriptions](#subscriptions)).

The value of `:biff/rules` is a map of `table->rules`, for example:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/rules.clj" target="_blank">src/example/rules.clj</a></div>
```clojure
(ns example.rules
  (:require
    [trident.util :as u]
    [clojure.spec.alpha :as s]))

; Same as (do (s/def ...) ...)
(u/sdefs
  :user/id uuid?
  ; like s/keys, but only allows specified keys.
  ::user-ref (u/only-keys :req [:user/id])
  ::user (u/only-keys :req [:user/email])
  ...)

(def rules
  {:users {:spec [::user-ref ::user]
           :get (fn [{:keys [session/uid] {:keys [user/id]} :doc}]
                  (= uid id))}
   ...})
```

### Tables

The table is used in transactions and subscriptions to specify which rules should be
used. The rules above authorize us to subscribe to this:

```clojure
[:biff/sub {:table :users
            :id {:user/id #uuid "some-uuid"}}]
```

And for transactions:

```clojure
{:games {:spec [::game-ref ::game]
         :create (fn [env] ...)}}
; Authorizes:
[:biff/tx {[:games {:game/id "ABCD"}]
           {:users #{#uuid "some-uuid"}}}]
```

### Specs

For each document in the query result or transaction, authorization has two
steps. First, the document ID and the document are checked with `s/valid?`
against the two elements in `:specs`, respectively. For example, the specs for
the `:users` table above would authorize a read or write operation on the
following document:

```clojure
{:crux.db/id {:user/id #uuid "some-uuid"}
 :user/id #uuid "some-uuid"
 :user/email "email@example.com"}
```

Note that during this check, the document will not include the ID or any keys
in the ID (for map IDs). (Also recall that map ID keys are automatically
duplicated in the document when using Biff transactions).

For write operations, the document must pass the spec before and/or after the
transaction, depending on whether the document is being created, updated or
deleted.

### Operations

If the specs pass, then the document must pass a predicate specified by the
operation. There are five operations: `:create`, `:update`, `:delete`,
`:query`, `:get`.

```clojure
{:messages {:specs ...
            :create (fn [env] ...)
            :get (fn [env] ...)}}
```

You can use the same predicate for multiple operations like so:

```clojure
{:messages {:specs ...
            [:create :update] (fn [env] ...)}}
```

There are also several aliases:

Alias | Expands to
------|-----------
`:read` | `[:query :get]`
`:write` | `[:create :update :delete]`
`:rw` | `[:query :get :create :update :delete]`

For example:

```clojure
{:messages {:specs ...
            :write (fn [env] ...)}}
```

`:get` refers to subscriptions for individual documents while `:query` is for
multiple documents:

```clojure
; get
[:biff/sub {:table :users
            :id {:user/id #uuid "some-uuid"}}]
; query
[:biff/sub {:table :games
            :where [[:users #uuid "some-uuid"]]}]
```

### Predicates

Predicates receive the system map merged with some additional keys, depending
on the operation:

Key | Operations | Description
----|------------|------------
`:session/uid` | `:rw` | The ID of the user who submitted the query/transaction. `nil` if they're unauthenticated.
`:biff/db` | `:rw` | The Crux DB value before this operation occurred.
`:doc` | `:rw` | The document being operated on.
`:old-doc` | `:write` | The previous value of the document being operated on.
`:current-time` | `:write` | The inst used to replace any occurrences of `:db/current-time` (see [Transactions](#transactions)).
`:generated-id` | `:create` | `true` iff a random UUID was generated for this document's ID.

Some examples:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/rules.clj" target="_blank">src/example/rules.clj</a></div>
```clojure
(def rules
  {:public-users {:spec [::user-public-ref ::user-public]
                  ; Returns false iff :session/uid is nil.
                  :get biff.rules/authenticated?
                  :write (fn [{:keys [session/uid] {:keys [user.public/id]} :doc}]
                           (= uid id))}
   :users {:spec [::user-ref ::user]
           :get (fn [{:keys [session/uid] {:keys [user/id]} :doc}]
                  (= uid id))}
   :games {:spec [::game-ref ::game]
           :query (fn [{:keys [session/uid] {:keys [users]} :doc}]
                    (contains? users uid))
           [:create :update] (fn [{:keys [session/uid doc old-doc]
                                   {:keys [users]} :doc}]
                               (and
                                 (some #(contains? (:users %) uid) [doc old-doc])
                                 ; Checks that no keys other than :users have changed
                                 ; (supports varargs).
                                 (biff.rules/only-changed-keys? doc old-doc :users)
                                 ; Checks that the value of :users (a set) hasn't changed except
                                 ; for the addition/removal of uid (supports varargs).
                                 (biff.rules/only-changed-elements? doc old-doc :users uid)))}})
```

```clojure
{:events {:spec [uuid? ::event]
          :create (fn [{:keys [session/uid current-time generated-id]
                        {:keys [timestamp user]} :doc}]
                    (and
                      (= uid (:user/id user))
                      ; Make sure that :timestamp was set by the server, not the client.
                      (= current-time timestamp)
                      ; Make sure that the ID was set by the server, not the client.
                      generated-id))}}
```

# Triggers

Relevant config:

```clojure
:biff/triggers nil ; A database triggers data structure.
```

Triggers let you run code in response to document writes. You must define a map of
`table->operation->fn`, for example:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/src/example/triggers.clj" target="_blank">src/example/triggers.clj</a></div>
```clojure
(defn assign-players [{:keys [biff/node doc]
                       {:keys [users x o]} :doc :as env}]
  ; When a user joins or leaves a game, re-assign users to X and O as needed.
  ; Delete the game document if everyone has left.
  (let [new-doc ... ; Same as doc but maybe with different :x and :o values
        op (cond
             (empty? users) [:crux.tx/delete (:crux.db/id doc)]
             (not= doc new-doc) [:crux.tx/put new-doc])]
    (when op
      (crux/submit-tx node
        [[:crux.tx/match (some :crux.db/id [doc new-doc]) doc]
         op]))))

(def triggers
  {:games {[:create :update] assign-players}})
```

See [Tables](#tables) and [Operations](#operations). The function will receive the system
map merged with the following keys:

Key | Description
----|------------
`:doc` | The document that was written.
`:doc-before` | The document's value before being written.
`:db` | The Crux DB value after this operation occurred.
`:db-before` | The Crux DB value before this operation occurred.
`:op` | One of `#{:create :update :delete}`.

# Production

## Installation

First, create an Ubuntu droplet on DigitalOcean. Make sure you've added your
public key to `/root/.ssh/authorized_keys`. If you've added your public key to
DigitalOcean already, this may be handled automatically.

The following script includes setting up Let's Encrypt. Before running it,
you'll need to point at the droplet any domain(s) you want to serve from Biff.
The script will ask for a list of the domains and will generate a certificate
for them.

Log in as root and run this:

```bash
git clone https://github.com/jacobobryant/biff
./biff/prod/install.sh
reboot
```

`install.sh` will:

1. Install dependencies
2. Create a non-root user
3. Install Biff as a systemd service (i.e. autostart on boot)
4. Setup Nginx
5. Install certificates
6. Setup firewall

If you ever want to update the list of domains served by Biff, just run
something like the following:

```bash
certbot --nginx -d 'findka.com,jacobobryant.com'
```

I've only tested the install script on DigitalOcean, but it should work on
other providers with little to no tweaking.

## Deployment

By default, Biff uses Postgres for storage in production. Managed
Postgres is easy to set up in DigitalOcean. After doing that, you'll just need
to set the `:biff.crux.jdbc/*` parameters in `config.edn`. Alternatively, you
can set `:biff.crux/topology :standalone` to use filesystem storage in
production (good for experimenting, but not recommended for non-hobby apps). **Also**,
you must set the hostname key (`:example.biff/host` in the example app).

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/config.edn" target="_blank">
config.edn</a></div>
```clojure
{:prod {; If you set jdbc credentials, remove this file from source control!
        :biff.crux.jdbc/dbname "..."
        :biff.crux.jdbc/user "..."
        :biff.crux.jdbc/password "..."
        :biff.crux.jdbc/host "..."
        :biff.crux.jdbc/port ...
        ; Uncomment to use filesystem storage in production instead of jdbc:
        ; :biff.crux/topology :standalone
        :example.biff/host "example.com" ; change this
        ...}
 ...}
```

### Steps for deploying

1. Copy `config.edn` from your local machine to `/home/biff/prod/config.edn` on the server
  (e.g. `scp config.edn root@example.com:/home/biff/prod/`).
2. Commit any static resources you need to your project's repo (or write your
   own code to download them from a CI server). For example, run `./task
   compile-cljs` in the example app:

<div class="file-heading"><a href="https://github.com/jacobobryant/biff/blob/master/example/task" target="_blank">task</a></div>
```bash
APP_NS="example"
CLJS_APPS="app"

compile-cljs () {
  npx shadow-cljs release $CLJS_APPS
  for app in $CLJS_APPS; do
    mkdir -p resources/www/$APP_NS/cljs/$app
    cp {www-dev,resources/www/$APP_NS}/cljs/$app/main.js
  done
}
```

<ol start="3">

  <li><strong>First deploy:</strong> Set <code>:git/url</code> for your project
  in <code>/home/biff/prod/deps.edn</code> on the server (e.g.
  <code>"https://github.com/example/example"</code>). Then run
  <code>reboot</code>. The latest commit hash will be fetched from your repo
  and added to the file on startup.<br>

  <strong>Future deploys:</strong> Delete <code>:sha "..."</code> from
  <code>/home/biff/prod/deps.edn</code> so that the latest commit hash will be
  fetched again on startup. Alternatively, you can specify the
  <code>:sha</code> value explicitly (e.g. for rollbacks).</code>

  <li>Watch the logs: <code>journalctl -u biff -f</code>. Your app should be
  live after you see <code>System started</code>.</li>
</ol>

If you want to deploy your app from a private repo, you'll need to <a
href="https://developer.github.com/v3/guides/managing-deploy-keys/#deploy-keys"
target="_blank"> add a deploy key</a>. Biff will <a
href="https://github.com/jacobobryant/biff/blob/master/prod/task"
target="_blank"> add it to the keychain</a> for you on app startup.

# FAQ

## Comparison to Firebase

Basically, if you like Firebase and you like Clojure backend dev, you might
enjoy using Biff for your next side project. Same if you like the idea of
Firebase but in practice you have issues with it. If you want something mature
or you like having a Node/ClojureScript backend, Firebase is a great choice. <a
href="https://github.com/jacobobryant/mystery-cows" target="_blank">Here's a non-trivial
example</a> of using Firebase with ClojureScript.

Some shared features:

 - Natural modeling of graph data
 - Basic query subscriptions (no joins)
 - Client-side transactions
 - Authorization rules
 - Triggers
 - Authentication built-in

Some differences:

 - Biff has a long-running JVM/Clojure backend instead of an ephemeral
   Node/ClojureScript backend => better library ecosystem IMO and lower response
   times/no cold start.
 - Firebase has way more features and is vastly more mature.
 - Biff is open-source + self-hosted => you have total control. If there's anything you don't like, you can fix it.
 - <a href="https://opencrux.com/" target="_blank">Crux</a> (the database Biff uses) is immutable and has Datalog queries.
 - Authorization rules in Firebase are IMO error-prone and hard to debug.
 - Firebase supports password and SSO authentication.

## Comparison to Fulcro

Similarities:

 - Both contain some code for moving data between frontend and backend, hence
   they can both be described as "full-stack frameworks."

Differences:

 - Fulcro is primarily a front-end framework while Biff is primarily backend.
 - Biff prioritizes the low end of the "market" (early-stage startups and hobby
   projects, as mentioned).
 - Biff is much smaller and younger.
 - Biff's scope includes devops.


## Why Crux and not Datomic?

I used Datomic pretty heavily in my own projects for about a year prior to
switching to Firestore and then Crux. My opinion on Datomic vs. Crux is that
Datomic is more powerful and can probably scale better, but Crux is  easier to
get started with and has a lot less operational overhead for small projects (in
terms of developer time). I've had many headaches from my time using Datomic
(<a href="https://jacobobryant.com/post/2019/aws-battles-ep-1/" target="_blank">and AWS</a>,
which Datomic Cloud is coupled to). On the other hand, using Crux has
been smooth&mdash;and you can use DigitalOcean instead of AWS (yay). Since
Biff prioritizes the solo-developer / early-stage / rapid-prototyping use-case,
I think Crux is a much better fit. Whereas if I was in a situation with many
developers/delivering an application that I knew would have scale once
released, Datomic Cloud Ions I think would be great.

Off the top of my head, a few more reasons:

 - I like that Crux doesn't enforce schema, which made it easy for Biff to use
   it's own schema (i.e. rules). I also think it's better for rapid-prototyping,
   when you're still figuring out the schema and it changes often.

 - Although Crux is less featureful, it's good enough for me. It's immutable
   and has datalog queries. In some cases, it makes Crux easier to use which
   could be considered a benefit for some situations. e.g. transactions in Crux
   are much less complex than in Datomic.

 - Crux is open-source. I'm a pragmatist and I don't mind using a closed source
   DB like Datomic in an app. But for Biff, a web framework intended for other
   people to build their apps on too, I'd rather not have a hard dependency on
   something closed-source. It'd suck if a feature broke in Datomic that was
   critical for Biff but low-priority for Cognitect. (I have a small budgeting
   app on Datomic that was down for several months because of that).

 - For hobby projects, you can run Crux on DigitalOcean with filesystem
   persistence for $5/month, whereas Datomic Cloud starts at $30/month. Doesn't
   matter for a startup of course, but I wouldn't want to be shelling out
   $30/month forever just to keep that budgeting app running.
