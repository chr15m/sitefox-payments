## ( make-price-name   price )

Generates a key/name for this price based on the nickname,
  falling back to the ID.

## ( get-price-info   price-ids )

Retrieve price data from the Stripe API e.g.
  for caching locally or displaying for the user.
  `price-ids` should be a list of Stripe price IDs.
  Returns a map keyed on price nicknames or price IDs
  if nicknames are missing. Values are the price data.

## ( cached-prices   price-ids )

Retrieve price data from Stripe using get-price-info,
  or from the cache, and cache it if not yet cached.

## ( get-price-by-id   prices price-id )

Retrieve a price from the prices datastructure
  using its id instead of its name.

## ( create-customer   user )

Create a new Stripe customer using their
  API and store it in the user's data.

## ( get-customer-id   user & [verify-customer-exists] )

Get the Stripe customer ID from the user.

## ( initiate-payment   req res price success-url cancel-url & [metadata] )

Sends the user to the Stripe payment page to make a
  one-time payment or initiate a subscription. In the
  background this starts a Stripe session, creating a
  Stripe customer if the currently logged in user doesn't
  have one yet, and stores the Stripe session reference
  for later retrieval. Redirects the browser to the
  payment/subscription page to complete payment. The
  request must be for a currently authenticated user for
  this to work (e.g. req.user has an id).

## ( make-initiate-payment-route   price-ids {:keys [success-url cancel-url metadata]} )

Internal wrapper function to set up Stripe payment routes.

## ( get-valid-subscriptions   customer-id prices )

Returns any active subscription the currently logged in user has.
  `prices` are regular Stripe subscriptions.

## ( get-customer-payments   customer-id prices )

Returns all of the non-subscription payments a customer has made.

## ( get-active-plan   all-payments )

Returns any valid subscription or payment from this user's payment list.
  For payment based prices you must create a metadata key in the Stripe
  prices UI called 'validity' with the value specifying the number of minutes:

  ```
  "metadata": {"validity": "1440"},
  ```

  You can also create a metadata key specifying a lifetime plan:

  ```
  "metadata": {"lifetime": "true"},
  ```
  

## ( get-all-payments   customer-id prices )

Retreive all payments the user has made from Stripe,
  including subscriptions and one-time payments.

## ( get-cached-payments   customer-id prices payments-cache-time force-refresh )

Retrieve a customer's payments from Stripe
  or from the cache, and cache it if not yet cached.

## ( send-to-customer-portal   req res & [return-url] )

Redirects the user to the Stripe customer portal where they can manage their
  subscription status according to the parameters you have set in the Stripe UI.
  `return-url` is a relative or absolute URL where the user should return to if
  they cancel/exit. The request must be for a currently authenticated user for
  this to work (e.g. req.user has an id).

## ( make-send-to-portal-route   {:keys [return-url]} )

Internal wrapper function to send the user to the Stripe portal.

## ( make-middleware:user-subscription   price-ids {:keys [subscription-cache-time]} )

Express/Sitefox middleware for fetching the user's subscription.

## ( get-plan-name   plan )

Get the name of the plan whether it's a subscription or one-time payment.

## ( component:account   req prices )

A Reagent component for showing the user their subscription status.
  You can use this as a template for building your own customised view.
  Add your own account view to the app like this:

  ```
  (j/call app :get (name-route app "/account" "account:subscription")
  (fn [req res]
    (direct-to-template res template selector
                        [component:account req])))
  ```

## ( setup   app price-ids template selector & [options] )

Set up the routes for redirecting to subscriptions etc.
  * `price-ids` is a list of price IDs that your users
  might be subscribed under (including grandfathered prices).
  * `template` is the HTML string template fragment to render UIs into.
  * `selector` is the query selector for where to mount UIs in the template.
  * `options` can include `:success-url`, `:cancel-url`, `:metadata`
  to pass to Stripe, `:subscription-cache-time` in ms to cache a user's
  subscription and not hit the Stripe API on every request, and
  `:skip-account-view` to not mount the account view so that a user
  can mount their own.

  Note: if you use your own account view you must specify `:success-url`
  in the options.

