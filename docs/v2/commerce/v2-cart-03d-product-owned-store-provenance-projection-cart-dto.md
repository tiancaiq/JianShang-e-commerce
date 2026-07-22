# V2-CART-03D Product-Owned Store Provenance Projection And Cart DTO

Status: implemented locally; runtime/browser acceptance remains deferred until
the cart publication gate.

Release: V2.

Requirements: `CRT-01`, `CRT-02`, `CRT-03`, and `CRT-04` cart provenance
stabilization.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-cart-03a-cart-only-capability-boundary-business-entry.md`
- `docs/v2/commerce/v2-cart-03b-amazon-style-cart-page-validation-repair-ux.md`
- `docs/v2/commerce/v2-cart-03c-cart-integration-header-feedback-local-acceptance.md`

## Goal

Carry authoritative, buyer-safe public store provenance from Product's
internal commerce projection into Order cart read and validation DTOs without
changing Redis cart storage or enabling checkout.

## Boundary

Included:

- Product Service enriches its internal store-item commerce context from
  Auth-owned public business/store labels.
- The projection carries internal `businessId` and `storeId` plus only
  buyer-visible `storeName`, `storeSlug`, `businessVerified`, `publicCity`,
  and `publicRegion`.
- Order Service relays those optional fields on `GET /api/v1/cart` and
  `POST /api/v1/cart/validate`.
- Existing validation remains authoritative for business-only listing
  eligibility, active seller/store lifecycle, price, currency, and inventory.

Excluded:

- Redis schema expansion, Flyway migrations, gateway changes, UI redesign,
  checkout, payment, order creation, shipping, tracking, recommendations,
  coupons, save-for-later, AI, notifications, ORD-03, and SHP behavior.
- Legal business names, support contacts, staff/member identity, exact
  addresses, moderation/review/application data, storage details, internal
  statuses, and payment/order fields.

## Rules

- Product owns the bounded internal cart-eligibility projection because it
  already owns listing eligibility and public business-listing enrichment.
- Auth remains authoritative for business/store lifecycle and public store
  profile facts.
- Order never queries Auth for labels and never infers store identity. It
  consumes Product's projection and still uses the existing seller/store
  eligibility check for lifecycle validation.
- Multi-business carts remain supported. Store-only means only `BUSINESS`
  listings can enter cart; `INDIVIDUAL` listings remain trade/chat-only.
- Cart read and validation compose live display fields. Redis still stores
  only listing ID, quantity, observed price, currency, and added time.
- Missing optional store fields keep the neutral frontend fallback
  `Business store item`; the browser never invents store identity.
- Inactive, suspended, missing, cross-type, or mismatched listing/store state
  fails closed through the existing `LISTING_UNAVAILABLE` or
  `SELLER_UNAVAILABLE` cart semantics.
- Product/Auth outages remain retryable dependency failures and do not mutate
  the cart.
- Repairs stay explicit. The platform never auto-accepts price changes,
  quantity reductions, removals, or store substitutions.

## Verification

Focused source checks cover:

- Product internal commerce context returns store name, slug, verification,
  and public location while omitting legal name and private store fields.
- Product treats Auth label outages as retryable dependency failures.
- Order cart reads and validation DTOs relay Product-provided store
  provenance without changing Redis contents.
- Existing business-only, price, inventory, validation, idempotency/version,
  and default-off behavior remains unchanged.

Deferred gates:

- Redis/Testcontainers persistence, Product MySQL commerce-context CI,
  default-off gateway runtime, and browser acceptance remain publication
  gates because this slice is source-only and does not start Docker or shared
  local services.
