(ns sitefoxpayments.util
  "Utility functions for working with subscription data in node & browser."
  (:require
    [applied-science.js-interop :as j]
    ["slugify$default" :as slugify]))

(defn make-price-name
  "Generates a key/name for this price based on the nickname,
  falling back to the ID."
  [price]
  (slugify (or (j/get price :nickname) (j/get price :id)) #js {:lower true}))

(defn get-price-by-id
  "Retrieve a price from the prices datastructure
  using its id instead of its name."
  [prices price-id]
  (first
    (.filter (js/Object.values prices)
             #(= (j/get % :id) price-id))))

(defn is-refunded
  [payment]
  (> (count (.filter (or (j/get-in payment [:charges :data]) #js [])
                     #(j/get % :refunded))) 0))

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
  For payment based prices you must create a metadata key in the Stripe
  prices UI called 'validity' with the value specifying the number of minutes:

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
    (let [payments (or (j/get all-payments :payments) #js [])
          subscriptions (or (j/get all-payments :subscriptions) #js [])
          now (js/Date.)
          active-time-based-plans (.filter payments #(is-active-payment % now))
          active-lifetime-plans (.filter payments #(is-lifetime-payment %))
          active-subscriptions (.sort
                                 subscriptions
                                 (fn [a b]
                                   (- (if (is-paused a) 1 0)
                                      (if (is-paused b) 1 0))))]
      (or (first active-lifetime-plans)
          (first active-time-based-plans)
          (first active-subscriptions)))))

(defn get-plan-name
  "Get the name of the plan whether it's a subscription or one-time payment."
  [plan]
  (or (j/get-in plan [:plan :nickname])
      (j/get-in plan [:price :nickname])))
