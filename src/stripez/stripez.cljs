(ns stripez
  "A simple subscription/payments module wrapping the Stripe API.
  Give logged in Sitefox/Passport users a way to pay for subscriptions.
  Subscriptions can be Stripe recurring subscriptions (monthly, annual) or one-time payments (lifetime, 24hr, etc.)."
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["stripe$default" :as Stripe]
    ["slugify$default" :as slugify]
    [sitefox.util :refer [env-required]]
    [sitefox.web :refer [build-absolute-uri name-route get-named-route]]
    [sitefox.html :refer [direct-to-template]]
    [sitefox.db :refer [kv]]
    [sitefox.ui :refer [log]]
    [sitefox.auth :refer [save-user]]))

(def stripe (Stripe (env-required "STRIPE_SK")))

(defn make-price-name
  "Generates a key/name for this price based on the nickname, falling back to the ID."
  [price]
  (slugify (or (j/get price :nickname) (j/get price :id)) #js {:lower true}))

(defn get-price-info
  "Retrieve price data from the Stripe API e.g. for caching locally or displaying for the user.
  `price-ids` should be a list of Stripe price IDs.
  Returns a map keyed on price nicknames or price IDs if nicknames are missing. Values are the price data."
  [price-ids]
  (log "Getting price info from the Stripe API.")
  (p/let [price-ids (map name price-ids)
          prices (p/all (map #(j/call-in stripe [:prices :retrieve] %) price-ids))
          named-prices (apply merge
                              (map (fn [price]
                                     {(make-price-name price)
                                      price})
                                   prices))]
    (clj->js named-prices)))

(defn cached-prices
  "Retrieve price data from Stripe using get-price-info,
  or from the cache, and cache it if not yet cached."
  [price-ids]
  (p/let [cache (kv "cache")
          prices (.get cache "prices")
          prices (or prices (get-price-info price-ids))]
    (.set cache "prices" prices (* 1000 60 5))
    prices))

(defn initiate-payment
  "Sends the user to the Stripe payment page to make a one-time payment or initiate a subscription.
  In the background this starts a Stripe session, creating a Stripe customer if the currently logged in user doesn't have one yet,
  and stores the Stripe session reference for later retrieval.
  Redirects the browser to the payment/subscription page to complete payment.
  The request must be for a currently authenticated user for this to work (e.g. req.user has an id)."
  [req res price success-url cancel-url & [metadata]]
  (let [user (j/get req :user)
        user-id (j/get user :id)]
    (if user-id
      (p/let [customer-id (j/get-in user [:stripe :customer-id])
              customer-email (j/get-in user [:auth :email])
              price-id (j/get price :id)
              price-type (j/get price :type)
              price-mode (get {"one_time" "payment"
                               "recurring" "subscription"}
                              price-type)
              metadata-key (get {"one_time" :payment_intent_data
                                 "recurring" :subscription_data}
                                price-type)
              metadata (merge metadata
                              {:user-id user-id
                               :price-id price-id
                               :price-name (make-price-name price)})
              packet {:billing_address_collection "auto"
                      :line_items [{:price price-id :quantity 1}]
                      :metadata metadata
                      :mode price-mode
                      :success_url (build-absolute-uri req (or success-url "/account"))
                      :cancel_url (build-absolute-uri req (or cancel-url "/"))}
              packet (assoc packet
                            metadata-key
                            {:metadata metadata})
              packet (if customer-id (assoc packet :customer customer-id) packet)
              packet (if customer-email (assoc packet :customer_email customer-email) packet)
              session (j/call-in stripe [:checkout :sessions :create]
                                 (clj->js packet))
              user (j/update-in! user [:stripe :checkout-session-ids] #(.concat (or % #js []) #js [(j/get session :id)]))
              _saved (save-user user)]
        (.redirect res 303 (aget session "url")))
      (.redirect res 303 (build-absolute-uri req (or cancel-url "/"))))))

(defn make-initiate-payment-route
  "Internal wrapper function to set up Stripe payment routes."
  [price-ids {:keys [success-url cancel-url metadata]}]
  (fn [req res]
    (p/let [prices (cached-prices price-ids)
            price-id (j/get-in req [:params :price])
            price (j/get prices price-id)]
      (initiate-payment req res price success-url cancel-url metadata))))

(defn get-valid-subscription
  "Returns any active subscription the currently logged in user has.
  `prices` are regular Stripe subscriptions."
  [_customer-id _prices])

(defn get-customer-payments
  "Returns all of the payments a customer has made. Optionally limited to `since` days ago."
  [_customer-id _prices])

(defn get-any-valid-plan
  "Returns any valid subscripton or payment for the current user.
  For payment based prices you must create a metadata key in the
  Stripe prices UI called 'validity' with the value specifying the number of minutes:

  ```
    \"metadata\": {\"validity\": \"1440\"},
  ```
  "
  [customer-id _prices]
  (when customer-id
    nil))

(defn send-to-customer-portal
  "Redirects the user to the Stripe customer portal where they can manage their
  subscription status according to the parameters you have set in the Stripe UI.
  `return-url` is a relative or absolute URL where the user should return to if they cancel/exit.
  The request must be for a currently authenticated user for this to work (e.g. req.user has an id)."
  [req res & [return-url]]
  (p/let [customer-id (j/get-in req [:user :stripe :customer-id])
          return-url (or return-url "/account")
          return-url (if (= (first return-url) "/")
                       (build-absolute-uri req return-url)
                       return-url)
          session (j/call-in stripe
                             [:billingPortal :sessions :create]
                             (clj->js {:customer customer-id
                                       :return_url return-url}))]
    (.redirect res (aget session "url"))))

(defn get-customer-id [req]
  (let [customer-id (j/get-in req [:user :stripe :customer-id])
        session-ids (j/get-in req [:user :stripe :checkout-session-ids])]
    (or customer-id
        (when (seq session-ids)
          (p/let [sessions (p/all (.map session-ids #(j/call-in stripe [:checkout :sessions :retrieve] %)))
                  customer-ids (when (seq sessions) (remove nil? (map #(j/get % :customer) (reverse sessions))))]
            (first customer-ids))))))

(defn make-middleware:user-subscription
  "Express/Sitefox middleware for fetching the user's subscription."
  [price-ids]
  (fn [req _res done]
    (print "user-id" (j/get-in req [:user :id]))
    (print "customer-id" (j/get-in req [:user :stripe :customer-id]))
    (print "sessions" (j/get-in req [:user :stripe :checkout-session-ids]))
    (p/let [user (j/get req :user)
            customer-id (get-customer-id req)
            prices (when customer-id (cached-prices price-ids))
            subscription (get-any-valid-plan customer-id prices)]
      (print "subscription" subscription)
      (print "customer-id" customer-id)
      (j/update-in! user [:stripe] j/assoc!
                    :subscription subscription
                    :customer-id customer-id)
      (save-user user)
      (done))))

(defn is-paused [sub]
  (j/get-in sub [:pause_collection]))

(defn payment-link [req price-id]
  (-> (get-named-route req "account:start") (.replace ":price" price-id)))

; *** default routes *** ;

(defn component:account
  "A Reagent component for showing the user their subscription status."
  [req]
  (let [subscription (j/get-in req [:user :stripe :subscription])]
    [:section.account
     [:div
      [:h2 "Your subscription"]
      (if subscription
        [:<>
         [:p "Hello. " [:strong "Thank you"] " for your subscription."]
         [:p "Your current plan is " [:strong (j/get subscription "name")] "."]
         (when (is-paused subscription)
           [:p [:strong "Your subscription is currently paused."]])
         [:h2 "Update subscription"]
         [:a.button {:href "/account/portal"} "visit the customer portal"]]
        [:p "You have no active subscription."])]]))

(defn setup
  "Set up the routes for redirecting to subscriptions etc."
  [app price-ids template selector & [options]]
  (j/call app :use (make-middleware:user-subscription price-ids))
  (j/call app :get (name-route app "/account/start/:price" "account:start") (make-initiate-payment-route price-ids options))
  (j/call app :get (name-route app "/account" "account") (fn [req res] (direct-to-template res template selector [component:account req]))))
