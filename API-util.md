## ( make-price-name price )

Generates a key/name for this price based on the nickname,
  falling back to the ID.

## ( get-price-by-id prices price-id )

Retrieve a price from the prices datastructure
  using its id instead of its name.

## ( get-active-plan all-payments )

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
  

## ( get-plan-name plan )

Get the name of the plan whether it's a subscription or one-time payment.

