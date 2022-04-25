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
    [sitefox.web :refer [build-absolute-uri]]))

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
  (p/let [price-ids (map name price-ids)
          prices (p/all (map #(j/call-in stripe [:prices :retrieve] %) price-ids))
          named-prices (apply merge
                              (map (fn [price]
                                     {(make-price-name price)
                                      price})
                                   prices))]
    (clj->js named-prices)))

(defn initiate-payment
  "Sends the user to the Stripe payment page to make a one-time payment or initiate a subscription.
  In the background this starts a Stripe session, creating a Stripe customer if the currently logged in user doesn't have one yet,
  and stores the Stripe customer id in the user data as `stripe-customer-id`.
  Redirects the browser to the payment/subscription page to complete payment."
  [req price-id])

(defn get-valid-subscription
  "Returns any active subscription the currently logged in user has.
  `price-ids` are regular Stripe subscriptions."
  [req price-ids])

(defn send-to-customer-portal
  "Redirects the user to the Stripe customer portal where they can manage their
  subscription status according to the parameters you have set in the Stripe UI.
  `return-url` is a relative or absolute URL where the user should return to if they cancel/exit."
  [req res & [return-url]]
  (p/let [customer-id (j/get-in req [:user :stripe-customer-id])
          return-url (or return-url "/account")
          return-url (if (= (first return-url) "/")
                       (build-absolute-uri req return-url)
                       return-url)
          session (j/call-in stripe
                             [:billingPortal :sessions :create]
                             (clj->js {:customer customer-id
                                       :return_url return-url}))]
    (.redirect res (aget session "url"))))
