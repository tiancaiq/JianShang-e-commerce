# V2-CART-03B Amazon-Style Cart Page And Validation Repair UX

Status: implemented locally; runtime/browser publication remains deferred until
the matching frontend release gates are run.

Release: V2.

Requirements: `CRT-01`, `CRT-02`, `CRT-03`, and `CRT-04` cart UX
stabilization.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-cart-03a-cart-only-capability-boundary-business-entry.md`

## Goal

Make `/cart` feel like a store-item purchase review page while preserving the
CART-02 validation contract: validation is advisory, repairs are explicit, and
the cart never mutates automatically.

## Boundary

Included:

- Redesign `/cart` as image-forward business item packing cards plus a
  receipt-style subtotal rail.
- Show saved subtotal separately from current validated subtotal.
- Show per-line current price, availability, store/currency readiness, and
  repair actions.
- Keep checkout hidden unless the independent buyer checkout capability is
  enabled and validation reports `checkoutReady=true`.
- Keep `Clear cart` and `Continue shopping`.
- Add optional frontend model fields for store identity so the page can render
  actual store provenance when the cart API supplies it.

Excluded:

- Backend cart DTO expansion, checkout behavior, inventory reservation,
  payment, orders, shipping, recommendations, coupons, save-for-later, and
  listing-card cart controls.

## UX Rules

- Item rows are compact white packing cards with plum text, honey/gold action
  accents, and existing marketplace fonts/tokens.
- Quantity controls stay bounded to `1..999` and use at least 44px touch
  targets.
- Mutation and validation outcomes use live regions and focus targets.
- Mobile layouts place the receipt summary under the page header and before
  item cards; it never overlays content.
- Price changes, reduced stock, unavailable listing/store, and currency
  conflicts require explicit buyer action.
- Failed or uncertain mutations are not replayed automatically.

## Store Provenance Note

Current CART-01/02 backend responses do not guarantee public store name, slug,
verification, or location in cart lines. This slice adds optional frontend
model fields and renders them when present. Until the cart API grows a bounded
buyer-facing store provenance contract, the fallback line is
`Business store item` rather than invented browser-side identity.

## Verification

Required checks:

- Component tests for packing-card rendering, optional store identity, image
  alt text, saved/current subtotal, repair actions, validation outage retry,
  empty cart, cart-only checkout copy, and quantity bounds.
- Cart service tests for bodyless validation, matching cart-version storage,
  stale validation discard, count refresh, and mutation methods.
- Route tests proving `/cart` remains false-default and opt-in only.
- Production and `demo-cart` Angular builds.
- Static scans for old combined cart/checkout flags, secret leakage,
  default-off behavior, and whitespace.
