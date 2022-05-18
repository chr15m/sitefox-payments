Add subscriptions and payments to your Sitefox site.

# Installation

`npm i chr15m/sitefoxpayments`

Then add `node_modules/sitefoxpayments/src` to your classpath.

# Set up

To use this module you need to use the Stripe UI to set up a few things.

 * API keys. To use this library you will need API keys from your Stripe account. Set the environment variable `STRIPE_SK` to your Stripe secret key.
 * Products and prices. You'll need to create products and prices for the things you want people to be able to purchase or subscribe to.
 * Customer portal configuration (optional). Set the environment variable `STRIPE_PORTAL_CONFIG_ID` to use a specific portal config if different from the default.

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
