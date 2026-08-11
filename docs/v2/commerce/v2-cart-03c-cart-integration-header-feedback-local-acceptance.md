# V2-CART-03C Cart Integration, Header Feedback And Local Acceptance

Status: cleanup and local runtime/browser acceptance green; cart is enabled in
the production frontend build while checkout remains disabled.

Release: V2.

Requirements: `CRT-01`, `CRT-02`, `CRT-03`, and `CRT-04` source acceptance.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-cart-03a-cart-only-capability-boundary-business-entry.md`
- `docs/v2/commerce/v2-cart-03b-amazon-style-cart-page-validation-repair-ux.md`

## Goal

Finish the cart-only shopping path at the source level by proving the header,
business listing entry, cart page, route gates, and capability-disabled builds behave
as one bounded cart capability without exposing checkout, payment, order, or
shipping paths.

## Boundary

Included:

- Authenticated marketplace navbar cart link with total-quantity badge when
  the frontend cart capability is enabled.
- Cart badge state loads only after an authenticated session and resets when
  the session is absent or marketplace logout starts.
- Business listing Add to cart success and error messages are accessible
  status/alert feedback and continue to use only listing ID and quantity.
- `/cart` keeps validation advisory: price, stock, store, and currency repairs
  require explicit buyer actions.
- Cart-only routes/builds keep checkout disabled even when validation reports
  `checkoutReady=true`.

Excluded:

- Polling, websocket updates, speculative count endpoints, checkout creation,
  payment, order creation, shipping, Product/Order/Inventory domain changes,
  and listing-card cart controls.

## Acceptance Evidence

Source tests prove:

- Capability-disabled configurations have no visible cart link and no cart
  service calls.
- Cart-enabled authenticated layout shows `/cart` with an accessible count and
  loads the current cart once.
- Signed-out cart-enabled layout resets local badge state and does not call the
  cart API.
- Marketplace logout clears local cart state once when the capability is
  enabled.
- Business detail Add to cart uses the marketplace login return flow for
  guests, updates shared cart state for authenticated buyers, and does not
  replay uncertain failures.
- The service-level journey covers add, reload, quantity update, bodyless
  validation, explicit price repair, remove, and clear.
- Individual listings remain cart-free.
- Cart-only mode never renders a checkout route.

## Runtime Acceptance

Local browser/runtime acceptance covers business Add to cart, the authenticated
badge, persisted quantity updates, validation, explicit availability limits,
the checkout-disabled boundary, and two independently owned stores grouped by
authoritative public store provenance through refresh and sign-out/sign-in.

## Cleanup Result

`V2-CART-CLEAN-P0-01` tightened the local cart source boundary without adding
new commerce behavior:

- The marketplace layout now reads the shared `CART_ENABLED` capability token
  used by the listing detail purchase box and cart route gate.
- Cart service loading state now coalesces overlapping cart requests so badge
  and page controls do not appear idle until every active cart request has
  completed.
- Focused cart tests cover the overlapping-request loading regression.

Still deferred:

- Clean-host Redis/Testcontainers cart persistence and Product MySQL
  commerce-context execution remain CI release gates.
