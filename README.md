Add subscriptions and payments to your Sitefox site.

# Installation

`npm i chr15m/sitefox-payments`

Then add `node_modules/sitefox-payments/src` to your classpath.

# Set up

To use this module you need to use the Stripe UI to set up a few things.

 * API keys. To use this library you will need API keys from your Stripe account. Set the environment variable `STRIPE_SK` to your Stripe secret key.
 * Products and prices. You'll need to create products and prices for the things you want people to be able to purchase or subscribe to. See "One-time payment plans" below.
 * Customer portal configuration (optional). Set the environment variable `STRIPE_PORTAL_CONFIG_ID` to use a specific portal config if different from the default.

# Use it

See `server.cljs` for an example nbb server which you can run with `npm run serve`.

Specify the IDs of the prices you want to use from your Stripe account.
Here they are taken from an environment variable called `PRICES` (comma separated).

```clojure
(def price-ids (->> (.split (env-required "PRICES") ",")
                    (map keyword)))
```

Add the routes to your Sitefox site in your `setup-routes` function:

```clojure
(ns myapp
  (:require ["sitefoxpayments" :as payments]))

(payments/setup app price-ids template "main" {:subscription-cache-time (* 1000 60)})
```

This will add the following routes:

 * `/account/start/:price` - initiate a payment using a particular price.
 * `/account/portal` - send the user to the Stripe portal to manage their subscription.
 * `/account` - view of the user's account.

A middleware will also be installed that adds the currently authenticated user's subscription information.
It is added to the Express `req` object under `req.stripe.payments`. See below for `get-active-plan`.

Inside a view create a link which the user can click on to pay for a subscription:

```clojure
[:a {:href (payment-link req price-id)} "subscribe now"]
```

Here's an example showing how to build links to all of your price options:

```clojure
(for [[price-id price] (js->clj prices)]
  (let [dollars (-> (get price "unit_amount") (/ 100))
        nickname (get price "nickname")]
    [:p {:key price-id}
      [:a {:href (payment-link req price-id)} "start " nickname]
      " $"
      dollars]))
```

Get the currently authenticated user's subscribed plan.
You can use this to gate features etc.

```clojure
(let [payments (j/get-in req [:stripe :payments])
      plan (get-active-plan payments)])
```

Give the user a link to manage their subscription through the Stripe portal:

```
[:a.button {:href (build-absolute-uri req "account:portal")} "Manage subscription"]
```

# One-time payment plans

For one-time payment based subscriptions you can create a metadata key in the
Stripe prices UI called 'validity' with the value specifying the number of minutes:

```
"metadata": {"validity": "1440"},
```

You can also create a metadata key specifying a lifetime plan:

```
"metadata": {"lifetime": "true"},
```
