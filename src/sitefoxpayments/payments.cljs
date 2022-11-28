(ns sitefoxpayments.payments
  "A simple subscription/payments module wrapping the Stripe API.
  Give logged in Sitefox/Passport users a way to pay for subscriptions.
  Subscriptions can be Stripe recurring subscriptions (monthly, annual) or one-time payments (lifetime, 24hr, etc.)."
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["stripe$default" :as Stripe]
    ["slugify$default" :as slugify]
    [sitefox.util :refer [env-required env]]
    [sitefox.web :refer [build-absolute-uri name-route get-named-route]]
    [sitefox.html :refer [direct-to-template]]
    [sitefox.db :refer [kv]]
    [sitefox.ui :refer [log]]
    [sitefox.auth :refer [save-user]]))

(def stripe (Stripe (env-required "STRIPE_SK")))

(def stripe-portal-config-id (env "STRIPE_PORTAL_CONFIG_ID"))

(defn make-price-name
  "Generates a key/name for this price based on the nickname, falling back to the ID."
  [price]
  (slugify (or (j/get price :nickname) (j/get price :id)) #js {:lower true}))

(defn get-price-info
  "Retrieve price data from the Stripe API e.g. for caching locally or displaying for the user.
  `price-ids` should be a list of Stripe price IDs.
  Returns a map keyed on price nicknames or price IDs if nicknames are missing. Values are the price data."
  [price-ids]
  (log "Refreshing price info from the Stripe API.")
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

(defn get-price-by-id
  "Retrieve a price from the prices datastructure using its id instead of its name."
  [prices price-id]
  (first
    (.filter (js/Object.values prices)
             #(= (j/get % :id) price-id))))

(defn create-customer
  "Create a new Stripe customer using their API and store it in the user's data."
  [user]
  (p/let [user-email (j/get-in user [:auth :email])
          customer (j/call-in stripe [:customers :create] (clj->js {:metadata {:user-id (j/get user :id)}
                                                                    :email user-email}))
          customer-id (j/get customer :id)
          user (j/assoc-in! user [:stripe :customer-id] customer-id)]
    (log "Created new Stripe customer:" customer-id)
    (save-user user)
    customer-id))

(defn get-customer-id
  "Get the Stripe customer ID from the user."
  [user & [verify-customer-exists]]
  (p/let [customer-id (j/get-in user [:stripe :customer-id])]
    (if (and verify-customer-exists (seq customer-id))
      (p/let [customer-check (p/catch
                               (j/call-in stripe [:customers :retrieve] customer-id)
                               (fn [_err]
                                 (log "Customer not found, setting to nil.")
                                 nil))]
        (when (and customer-check (not (j/get customer-check :deleted))) customer-id))
      customer-id)))

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
      (p/let [customer-id (get-customer-id user true)
              customer-id (or customer-id (create-customer user))
              price-id (j/get price :id)
              price-type (j/get price :type)
              price-nickname (j/get price :nickname)
              price-mode (get {"one_time" "payment"
                               "recurring" "subscription"}
                              price-type)
              metadata-key (get {"one_time" :payment_intent_data
                                 "recurring" :subscription_data}
                                price-type)
              metadata (merge metadata
                              {:user-id user-id
                               :price-id price-id
                               :price-description price-nickname
                               :price-name (make-price-name price)
                               :type price-mode})
              packet {:customer customer-id
                      :billing_address_collection "auto"
                      :line_items [{:price price-id :quantity 1}]
                      :allow_promotion_codes true
                      :metadata metadata
                      :mode price-mode
                      :success_url (str (build-absolute-uri req (or success-url (get-named-route req "account:subscription"))) "?refresh")
                      :cancel_url (build-absolute-uri req (or cancel-url "/"))}
              packet (assoc packet
                            metadata-key
                            {:metadata metadata})
              session (j/call-in stripe [:checkout :sessions :create] (clj->js packet))]
        ;(log "user" user)
        (.redirect res 303 (aget session "url")))
      (.redirect res 303 (build-absolute-uri req (or cancel-url "/"))))))

(defn make-initiate-payment-route
  "Internal wrapper function to set up Stripe payment routes."
  [price-ids {:keys [success-url cancel-url metadata]}]
  (fn [req res]
    (p/let [prices (cached-prices price-ids)
            price-name (j/get-in req [:params :price])
            price (j/get prices price-name)]
      (if price
        (initiate-payment req res price success-url cancel-url metadata)
        (.redirect res 303 (build-absolute-uri req (or cancel-url "/")))))))

(defn get-valid-subscriptions
  "Returns any active subscription the currently logged in user has.
  `prices` are regular Stripe subscriptions."
  [customer-id prices]
  ;(log "customer-id" customer-id)
  (p/let [price-ids (map #(j/get % :id) (js/Object.values prices))
          subscriptions-data (j/call-in stripe [:subscriptions :list] #js {:customer customer-id})
          subscriptions (.filter (or (j/get subscriptions-data :data) #js [])
                                 (fn [sub] (contains? (set price-ids) (j/get-in sub [:plan :id]))))
          subscriptions (.map subscriptions
                              (fn [sub]
                                (let [price-id (j/get-in sub [:plan :id])
                                      price (get-price-by-id prices price-id)]
                                  (j/assoc! sub :price price))))]
    subscriptions))

(defn get-customer-payments
  "Returns all of the non-subscription payments a customer has made."
  [customer-id prices]
  (p/let [price-ids (map #(j/get % :id) (js/Object.values prices))
          payment-intents-data (j/call-in stripe [:paymentIntents :list] #js {:customer customer-id
                                                                              :limit 100})
          ; TODO: use (j/call :autoPagingEach (fn [payment] ...collect ))
          payments (.filter (or (j/get payment-intents-data :data) #js [])
                            (fn [payment-intent]
                              (and (contains? (set price-ids) (j/get-in payment-intent [:metadata :price-id]))
                                   (= (j/get-in payment-intent [:metadata :type]) "payment"))))
          payments (.map payments
                         (fn [payment-intent]
                           (let [price-id (j/get-in payment-intent [:metadata :price-id])
                                 price (get-price-by-id prices price-id)]
                             (j/assoc! payment-intent :price price))))]
    payments))

(defn is-refunded
  [payment]
  (> (count (.filter (j/get-in payment [:charges :data]) #(j/get % :refunded))) 0))

(defn is-active-payment
  [payment now]
  (let [validity (j/get-in payment [:price :metadata :validity])
        validity-ms (* validity 60 1000)
        now-ms (-> now .getTime)
        paid-ms (* (j/get-in payment [:created]) 1000)]
    (and
      (> (+ paid-ms validity-ms) now-ms)
      (not (is-refunded payment)))))

(defn is-lifetime-payment
  [payment]
  (and
    (j/get-in payment [:price :metadata :lifetime])
    (not (is-refunded payment))))

(defn is-paused [sub]
  (boolean
    (j/get-in sub [:pause_collection])))

(defn get-active-plan
  "Returns any valid subscription or payment from this user's payment list.
  For payment based prices you must create a metadata key in the
  Stripe prices UI called 'validity' with the value specifying the number of minutes:

  ```
  \"metadata\": {\"validity\": \"1440\"},
  ```

  You can also create a metadata key specifying a lifetime plan:

  ```
  \"metadata\": {\"lifetime\": \"true\"},
  ```
  "
  [all-payments]
  (when all-payments
    (let [payments (j/get all-payments :payments)
          subscriptions (j/get all-payments :subscriptions)
          now (js/Date.)
          active-time-based-plans (.filter payments #(is-active-payment % now))
          active-lifetime-plans (.filter payments #(is-lifetime-payment %))
          active-subscriptions (.sort subscriptions (fn [a b] (- (if (is-paused a) 1 0) (if (is-paused b) 1 0))))]
      (or (first active-lifetime-plans) (first active-time-based-plans) (first active-subscriptions)))))

(defn get-all-payments
  "Retreive all payments the user has made from Stripe, including subscriptions and one-time payments."
  [customer-id prices]
  (when customer-id
    (log "Refreshing customer payments list:" customer-id)
    (p/catch
      (p/let [valid-subscriptions (when customer-id (get-valid-subscriptions customer-id prices))
              valid-payments (when customer-id (get-customer-payments customer-id prices))]
        #js {:subscriptions valid-subscriptions
             :payments valid-payments})
      (fn [err]
        (log "Error refreshing payments")
        (log (.toString err))
        nil))))

(defn get-cached-payments
  "Retrieve a customer's payments from Stripe
  or from the cache, and cache it if not yet cached."
  [customer-id prices payments-cache-time force-refresh]
  (p/let [cache (kv "cache")
          k (str "payments:" customer-id)
          payments (.get cache k)
          payments (or (and (not force-refresh) payments)
                       (get-all-payments customer-id prices))]
    (.set cache k payments payments-cache-time)
    payments))

(defn send-to-customer-portal
  "Redirects the user to the Stripe customer portal where they can manage their
  subscription status according to the parameters you have set in the Stripe UI.
  `return-url` is a relative or absolute URL where the user should return to if they cancel/exit.
  The request must be for a currently authenticated user for this to work (e.g. req.user has an id)."
  [req res & [return-url]]
  (p/let [user (j/get req :user)
          customer-id (get-customer-id user true)
          return-url (or return-url "/account")
          return-url (str (if (= (first return-url) "/")
                            (build-absolute-uri req return-url)
                            return-url)
                          "?refresh")
          config {:customer customer-id
                  :return_url return-url}
          config (if stripe-portal-config-id (assoc config :configuration stripe-portal-config-id) config)
          session (when customer-id
                    (j/call-in stripe
                               [:billingPortal :sessions :create]
                               (clj->js config)))]
    (.redirect res (if session
                     (j/get session :url)
                     return-url))))

(defn make-send-to-portal-route
  "Internal wrapper function to send the user to the Stripe portal."
  [{:keys [return-url]}]
  (fn [req res]
    (send-to-customer-portal req res return-url)))

(defn make-middleware:user-subscription
  "Express/Sitefox middleware for fetching the user's subscription."
  [price-ids {:keys [subscription-cache-time]}]
  (fn [req res done]
    (p/let [user (j/get req :user)
            customer-id (get-customer-id user)
            prices (when customer-id (cached-prices price-ids))
            force-refresh-subscription (= (j/get-in req [:query :refresh]) "")
            payments (get-cached-payments customer-id prices (or subscription-cache-time (* 1000 60 60)) force-refresh-subscription)]
      ;(log "user" user)
      (j/assoc-in! req [:stripe :payments] payments)
      ;(j/assoc-in! req [:stripe :subscription] (current-subscription payments))
      (if force-refresh-subscription
        (.redirect res (j/get req :path))
        (done)))))

(defn get-plan-name
  "Get the name of the plan whether it's a subscription or one-time payment."
  [plan]
  (or (j/get-in plan [:plan :nickname])
      (j/get-in plan [:price :nickname])))

(defn payment-link [req price-id]
  (-> (get-named-route req "account:start")
      (.replace ":price" price-id)))

; *** default routes *** ;

(defn component:account
  "A Reagent component for showing the user their subscription status.
  You can use this as a template for building your own customised view.
  Add your own account view to the app like this:

  ```
  (j/call app :get (name-route app \"/account\" \"account:subscription\")
  (fn [req res] (direct-to-template res template selector [component:account req])))
  ```"
  [req prices]
  (let [payments (j/get-in req [:stripe :payments])
        plan (get-active-plan payments)]
    [:section.account
     [:div

      [:h2 "Your plan"]
      (if plan
        [:<>
         [:p "Thank you for your subscription."]
         [:p "Your current plan is " [:strong (get-plan-name plan)] "."]
         (when (is-paused plan)
           [:p [:strong "Your plan is currently paused."]])
         [:a.button {:href (build-absolute-uri req (get-named-route req "account:portal"))} "Manage subscription"]]
        [:div
         [:p "You have no active subscription."]
         [:p "Choose a subscription:"]
         [:ul
          (for [[price-id price] (js->clj prices)]
            (let [dollars (-> (get price "unit_amount") (/ 100))
                  nickname (get price "nickname")]
              [:li {:key price-id}
               [:a {:href (payment-link req price-id)} "start " nickname]
               " $"
               dollars]))]])

      (let [subscriptions (j/get payments :subscriptions)]
        (when (seq subscriptions)
          [:<>
           [:h2 "Subscriptions"]
           [:ul
            (for [sub subscriptions]
              [:li {:key (j/get sub :id)}
               (j/get-in sub [:plan :nickname])
               (when (is-paused sub) " (paused)")])]]))

      (let [one-time-payments (j/get payments :payments)]
        (when (seq one-time-payments)
          [:<>
           [:h2 "Payments"]
           [:ul
            (for [pyt one-time-payments]
              [:li {:key (j/get pyt :id)}
               (j/get-in pyt [:price :nickname])
               (for [url (.map (or (j/get-in pyt [:charges :data]) #js[]) #(j/get % :receipt_url))]
                 [:a {:href url :target "_BLANK" :key url} " receipt "])
               [:a {:href (str "https://dashboard.stripe.com/test/payments/" (j/get pyt :id))} "link"]
               (when (is-refunded pyt)
                 " (refunded)")
               (when (or (is-active-payment pyt (js/Date.))
                         (is-lifetime-payment pyt)) " (active)")])]]))

      [:p [:a {:href (build-absolute-uri req (get-named-route req "auth:sign-out"))} "Sign out"]]]]))

(defn setup
  "Set up the routes for redirecting to subscriptions etc.
  * `price-ids` is a list of price IDs that your users might be subscribed under (including grandfathered prices).
  * `template` is the HTML string template fragment to render UIs into.
  * `selector` is the query selector for where to mount UIs in the template.
  * `options` can include `:success-url`, `:cancel-url`, `:metadata` to pass to Stripe,
  `:subscription-cache-time` in ms to cache a user's subscription and not hit the Stripe API on every request,
  and `:skip-account-view` to not mount the account view so that a user can mount their own.

  Note: if you use your own account view you must specify `:success-url` in the options."
  [app price-ids template selector & [options]]
  ;(j/call app :use (fn [req _res done] (j/update-in! req [:user] (fn [user] (js-delete user "stripe") user)) (p/do! (save-user (j/get req :user)) (done))))
  (j/call app :use (make-middleware:user-subscription price-ids options))
  (j/call app :get (name-route app "/account/start/:price" "account:start") (make-initiate-payment-route price-ids options))
  (j/call app :get (name-route app "/account/portal" "account:portal") (make-send-to-portal-route options))
  (when (not (:skip-account-view options))
    (j/call app :get (name-route app "/account" "account:subscription") (fn [req res]
                                                                          (p/let [prices (cached-prices price-ids)]
                                                                            (direct-to-template res template selector [component:account req prices])))))
  app)
