(ns webserver
  (:require
    ["fs" :as fs]
    ["fast-glob$default" :as fg]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [nbb.core :refer [*file*]]
    [sitefox.web :as web]
    [sitefox.html :refer [direct-to-template]]
    [sitefox.util :refer [env env-required]]
    [sitefox.ui :refer [log]]
    [sitefox.reloader :refer [nbb-reloader]]
    [sitefox.tracebacks :refer [install-traceback-emailer]]
    ;[sitefox.logging :refer [bind-console-to-file]]
    [sitefox.auth :as auth]
    [sitefoxpayments.payments :refer [cached-prices] :as payments]))

; (bind-console-to-file)

(when (env "ADMIN_EMAIL")
  (install-traceback-emailer (env "ADMIN_EMAIL")))

(def template (fs/readFileSync "index.html"))

(def price-ids (->> (.split (env-required "PRICES") ",")
                    (map keyword)))

(defn component-main [req]
  (let [user (j/get req :user)]
    [:div
     [:h1 "Subscriptions test"]
     (if user
       [:section
        [:p "Welcome, " (j/get-in user [:auth :email])]
        [:p [:a {:href (web/get-named-route req "account:subscription")} "My account"]]
        [:p [:a {:href (web/get-named-route req "auth:sign-out")} "Sign out"]]]
       [:section
        [:p [:a {:href (web/get-named-route req "auth:sign-in")} "Sign in"]]])]))

(defn setup-routes [app price-ids]
  (web/reset-routes app)
  (web/static-folder app "/css" "node_modules/minimal-stylesheet/")
  (auth/setup-auth app)
  (auth/setup-email-based-auth app template "main"
                               :sign-in-redirect "account:subscription"
                               :sign-up-redirect "account:subscription")
  (auth/setup-reset-password app template "main")
  (payments/setup app price-ids template "main" {:subscription-cache-time (* 1000 60)})
  (.get app "/"
        (fn [req res]
          (direct-to-template res template "main" [component-main req]))))

(defonce init
  (p/let [self *file*
          [app host port] (web/start)
          prices (cached-prices price-ids)
          watch (fg #js [self "src/**/*.cljs" "index.html"])]
    (log "loaded prices:" (keys (js->clj prices)))
    (setup-routes app price-ids)
    (nbb-reloader watch #(setup-routes app price-ids))
    (print "Serving at" (str host ":" port))))
