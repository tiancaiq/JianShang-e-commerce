# V2-CART-03E Cart Mutation Idempotency And Version Contract

Status: source implemented locally; executable Redis/Testcontainers evidence is
still required on a clean host.

Release: V2.

Depends on:

- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`
- `docs/v2/commerce/v2-cart-02-cart-validation-plan.md`
- `docs/v2/commerce/v2-cart-03d-product-owned-store-provenance-projection-cart-dto.md`
- `docs/v2/commerce/v2-cart-rel-p0-02b-cart-ci-gate-definition.md`

## Goal

Make buyer cart mutations retry-safe and version-aware before cart demo/runtime
enablement.

## Contract

Every cart mutation requires:

- `If-Match`: plain or quoted nonnegative cart version.
- `Idempotency-Key`: `[A-Za-z0-9._:-]{8,128}`.

Affected routes:

```text
POST   /api/v1/cart/items
PATCH  /api/v1/cart/items/{listingId}
DELETE /api/v1/cart/items/{listingId}
DELETE /api/v1/cart
```

Reads and validation remain unchanged. `POST /api/v1/cart/validate` stays
read-only and does not require an idempotency key.

## Behavior

- Same buyer, same idempotency key, same canonical mutation payload replays the
  original cart response.
- Same buyer, same idempotency key, different canonical payload returns
  `409 CART_IDEMPOTENCY_CONFLICT`.
- A stale `If-Match` returns `409 CART_VERSION_CONFLICT`.
- Redis writes the cart mutation and completed idempotency result atomically.
- Concurrent distinct-key mutations against the same cart version have exactly
  one successful winner; the loser receives a version conflict.
- The frontend sends a fresh mutation key per explicit buyer action and does
  not automatically replay uncertain failed mutations.

## Non-Goals

This slice does not enable checkout, payment, order creation, shipping,
tracking, recommendations, coupons, save-for-later, or individual-listing cart
behavior.

## Verification

Required clean-host gates:

- `RedisCartRepositoryTests#concurrentMutationsAreAtomicVersionedAndIsolated`
- `RedisCartRepositoryTests#sameListingRetryIsDeterministicAndVersioned`
- focused cart controller/service tests
- focused Angular cart service tests
- full affected Order and frontend build gates before runtime acceptance
