# V2-CART-02 Cart Validation Plan

Status: complete and browser verified on 2026-07-18.

Release: V2.

Requirement: `CRT-04`.

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Depends on:

- `V2-INV-01`
- `V2-CART-01`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- `docs/v2/commerce/v2-inv-01-business-inventory-foundation-plan.md`
- `docs/v2/commerce/v2-cart-01-redis-cart-plan.md`

## 1. Goal

Revalidate every item in the authenticated buyer's cart against current
product, seller, store, price, currency, and inventory facts.

The result tells the buyer whether the cart is ready for a later checkout and
gives one explicit repair action for every invalid line. Validation never
reserves stock and does not claim that inventory will remain available.

## 2. Slice Boundary

Included:

- Authenticated `POST /api/v1/cart/validate`.
- Current product, seller/store, price, currency, and inventory reads.
- Per-item validation status, issue codes, and repair actions.
- Authoritative totals only for a fully valid single-currency cart.
- A token-protected auth-service read for current business/store commerce
  eligibility.
- Cart UI validation on entry and after successful cart mutations.
- Retry, price acceptance, quantity reduction, and removal actions.
- Structured validation logs, metrics, and focused tests.

Not included:

- Inventory reservation, release, expiry, or commit.
- Checkout creation or a checkout button.
- Address, tax, shipping, discount, payment, order, or fulfillment behavior.
- Durable validation persistence, cart events, or a Flyway migration.
- Automatic deletion or quantity changes during validation.
- Individual listings or changes to the trade flow.

`V2-INV-02` owns reservation. `V2-CHK-01` must repeat validation before
creating checkout snapshots and cannot trust an earlier CART-02 result.

## 3. Current Repository Reality

V2-CART-01 already provides:

- One isolated Redis cart per authenticated user.
- Stored listing ID, quantity, observed price, currency, and added time.
- Product and inventory checks on add and quantity replacement.
- Current product display composition on cart reads.
- Internal single-item product and inventory reads.

The missing CART-02 behavior is:

- A full-cart validation operation.
- Current seller and store eligibility from auth service.
- Comparison of observed and current prices/currencies.
- Per-item warning and repair states.
- A cart-level single-currency and readiness decision.

Product commerce context already contains `businessId` and `storeId`.
Order-service must retain both in its internal client model for seller
eligibility checks.

## 4. Ownership And Authorization

- Order-service owns validation orchestration and the buyer response.
- The JWT subject selects the cart. No buyer or user ID is accepted.
- Product service owns listing type, business/store linkage, publication
  status, title, media, price, and currency.
- Auth service owns current business and store status.
- Inventory service owns initialized state and available quantity.
- Internal reads require `X-Internal-Service-Token` and are not routed through
  the public gateway.
- Validation is read-only and does not require `Idempotency-Key`.

Auth service adds:

```text
GET /api/v1/internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility
```

Internal response:

```json
{
  "businessId": "01...",
  "storeId": "01...",
  "eligible": true,
  "businessStatus": "ACTIVE",
  "storeStatus": "ACTIVE"
}
```

Eligibility is true only when the referenced store belongs to the referenced
business and both statuses are `ACTIVE`. Missing or mismatched pairs return
not found. Order-service does not relay raw business/store statuses to the
buyer.

## 5. Validation API

Buyer route:

```text
POST /api/v1/cart/validate
```

The request has no body. Client prices, totals, currencies, seller facts, and
availability are therefore never accepted as input.

Response:

```json
{
  "cartVersion": 3,
  "validatedAt": "2026-07-18T12:00:00Z",
  "checkoutReady": false,
  "itemCount": 2,
  "totalQuantity": 3,
  "validatedTotals": [],
  "cartIssues": [],
  "items": [
    {
      "listingId": "01...",
      "title": "Store item",
      "thumbnailUrl": "/api/v1/public/listing-media/01...",
      "requestedQuantity": 2,
      "availableQuantity": 1,
      "observedPrice": 19.99,
      "currentPrice": 21.99,
      "observedCurrency": "USD",
      "currentCurrency": "USD",
      "status": "QUANTITY_REDUCED",
      "issues": [
        {
          "code": "CART_QUANTITY_REDUCED",
          "message": "Only 1 is currently available.",
          "action": "SET_AVAILABLE_QUANTITY"
        },
        {
          "code": "CART_PRICE_CHANGED",
          "message": "The price changed from 19.99 USD to 21.99 USD.",
          "action": "ACCEPT_CURRENT_PRICE"
        }
      ]
    }
  ]
}
```

Rules:

- `cartVersion` is the Redis version read for this validation result.
- Validation does not increment the version or refresh cart expiry.
- `totalQuantity` is the quantity currently stored in the cart.
- `validatedTotals` is populated only when `checkoutReady=true`.
- A ready cart has exactly one authoritative currency total calculated from
  current prices and stored quantities.
- Empty carts return `checkoutReady=false` with cart issue
  `CART_EMPTY`.
- The frontend discards a validation result if its `cartVersion` no longer
  matches the current cart.

## 6. Item Status And Repair Contract

Primary item statuses:

- `READY`
- `PRICE_CHANGED`
- `QUANTITY_REDUCED`
- `OUT_OF_STOCK`
- `LISTING_UNAVAILABLE`
- `SELLER_UNAVAILABLE`
- `CURRENCY_CONFLICT`

An item can have multiple issues. `status` is the highest-priority issue for
compact display; `issues` preserves every actionable problem.

Priority:

```text
LISTING_UNAVAILABLE
SELLER_UNAVAILABLE
OUT_OF_STOCK
QUANTITY_REDUCED
CURRENCY_CONFLICT
PRICE_CHANGED
READY
```

Repair actions:

- `ACCEPT_CURRENT_PRICE`: call the existing add endpoint with the same listing
  and quantity so observed price and currency are replaced after fresh checks.
- `SET_AVAILABLE_QUANTITY`: call the existing quantity replacement endpoint
  with `availableQuantity`.
- `REMOVE_ITEM`: call the existing item deletion endpoint.
- `RETRY_VALIDATION`: repeat validation after a transient failure.

No repair happens automatically. A buyer must explicitly accept a new price,
reduce quantity, select one currency by removing conflicting lines, or remove
an unavailable item.

## 7. Validation Rules

For each stored line, order-service reloads:

1. Product commerce context by listing ID.
2. Business/store eligibility for the returned ownership pair.
3. Inventory availability by listing ID.

Seller eligibility reads are deduplicated per distinct
`(businessId, storeId)` pair during one validation.

Results:

- Missing, non-business, non-`ACTIVE`, invalid-price, or invalid-currency
  product context produces `LISTING_UNAVAILABLE`.
- A missing, suspended, or closed business/store produces
  `SELLER_UNAVAILABLE`.
- Uninitialized inventory or zero available produces `OUT_OF_STOCK`.
- Positive availability below requested quantity produces
  `QUANTITY_REDUCED`.
- Different observed and current price or currency produces
  `PRICE_CHANGED`, unless a higher-priority issue is present.
- More than one current currency among otherwise eligible lines adds
  `CURRENCY_CONFLICT` to those lines and prevents readiness.
- Product business ID and inventory business ID must match. A mismatch fails
  that line closed as `LISTING_UNAVAILABLE` and emits an integrity log and
  metric.
- `checkoutReady=true` only when the cart is nonempty, every line is `READY`,
  and exactly one current currency remains.

Price equality uses decimal numeric comparison, not string or floating-point
comparison. Currency values are normalized stable uppercase codes.

Validation is a point-in-time advisory result. It does not lock listings,
sellers, prices, or inventory.

## 8. Dependency Failure Policy

Validation is fail-closed:

- Product or seller not found is a normal actionable item result.
- Uninitialized inventory is a normal `OUT_OF_STOCK` result.
- Timeout, connection failure, malformed response, or dependency `5xx`
  returns `503 CART_DEPENDENCY_UNAVAILABLE` for the whole validation.
- No partial response may set `checkoutReady=true`.
- The existing cart remains unchanged after any validation failure.

The first implementation may reuse the bounded single-item product and
inventory contracts from CART-01. The cart limit is 50 lines, and seller reads
are deduplicated. Batch internal APIs are deferred until latency evidence
justifies expanding three service contracts.

## 9. Frontend Workflow

The `/cart` page:

1. Loads the cart.
2. Validates a nonempty cart.
3. Shows a compact validating state without hiding cart contents.
4. Displays current price, current availability, and line-specific warnings.
5. Offers only the repair action supported by the existing cart APIs.
6. Revalidates after each successful repair or quantity mutation.
7. Shows a retry action when validation is temporarily unavailable.

The summary displays:

- `Ready for checkout` only for a valid cart.
- `Needs attention` when one or more lines are invalid.
- The authoritative current subtotal only when ready.
- The observed CART-01 subtotal as clearly non-current cart data while
  validation is unavailable or incomplete.

This slice does not expose a checkout action because reservation and checkout
do not exist yet.

## 10. Observability

Structured logs include:

- operation `cart_validate`
- buyer subject
- cart version and item count
- result and issue-code counts
- dependency name on failure
- correlation ID and elapsed time

Metrics include:

- validation attempts by result
- validation latency
- item issue counts by code
- dependency failures by service
- inventory/business ownership mismatch count

Logs do not include access tokens, business legal details, or unnecessary
buyer information.

## 11. Test Plan

Order-service unit tests:

- Empty and all-ready carts.
- Price and currency changes.
- Reduced quantity and out of stock.
- Listing unavailable and seller unavailable.
- Mixed-current-currency conflict.
- Multiple simultaneous issues and priority.
- Decimal price comparison.
- Inventory/business mismatch.
- Dependency failure returns no partial readiness.
- JWT owner isolation and no cart mutation or TTL refresh.

Auth-service tests:

- Internal token is required.
- Active business and active matching store are eligible.
- Suspended/closed business or store is ineligible.
- Missing and cross-business store pairs are not found.

API and gateway tests:

- Authentication is required.
- `POST /api/v1/cart/validate` returns the documented schema.
- No request totals are accepted.
- Order-service outage uses the standard gateway `503` envelope.

Angular tests:

- Validation runs after nonempty load and successful mutation.
- Ready, price-changed, reduced, out-of-stock, seller-unavailable, and
  currency-conflict states render.
- Repair buttons call the correct existing API and revalidate.
- Stale-version results are ignored.
- Dependency failures retain cart contents and expose retry.
- No checkout control is rendered.

Browser verification:

1. Sign in with the approved demo buyer.
2. Add an initialized active business item.
3. Confirm the ready state and authoritative subtotal.
4. Change inventory and verify reduced/out-of-stock repair behavior.
5. Change price and verify explicit price acceptance.
6. Pause the listing or seller/store and verify unavailable behavior.
7. Restore source data and confirm the cart returns to ready.
8. Confirm individual listings still have no cart action.
9. Leave source data restored and the test cart empty.

## 12. Implementation Order

1. Add auth-service internal seller/store eligibility contract and tests.
2. Extend order-service product context with `storeId` and add the auth client.
3. Add validation domain types, orchestration, and unit tests.
4. Expose `POST /api/v1/cart/validate` and gateway/API tests.
5. Add Angular models, service state, cart warning/repair UI, and tests.
6. Run focused backend, Redis, Angular, and production-build verification.
7. Complete the approved-account browser walkthrough and update this document
   to complete.

## 13. Completion Criteria

- `CRT-04` reloads product, seller/store, price, currency, and inventory facts.
- Client totals and identity are never trusted.
- Every invalid line has an actionable issue.
- Validation does not mutate the cart, refresh TTL, or reserve inventory.
- Only a nonempty, all-ready, single-currency cart is marked checkout-ready.
- Source-service failure cannot produce false readiness.
- Buyer isolation, internal service authentication, and seller/store ownership
  are tested.
- The cart UI exposes repair and retry behavior without a dead-end checkout.
- Browser verification passes with source data restored afterward.

## 14. Implementation Result

Completed on 2026-07-18:

- Auth service now exposes the token-protected internal business/store
  commerce-eligibility contract with exact ownership and active-state checks.
- Order service now exposes `POST /api/v1/cart/validate`, reloading product,
  seller/store, price, currency, and inventory facts without mutating Redis.
- Validation returns current totals only for ready, nonempty,
  single-currency carts and fails closed when a dependency is unavailable.
- Marketplace cart UI now validates after load and mutation, renders
  actionable line issues, supports quantity and price repairs, and ignores
  stale cart-version responses.
- Focused backend, Redis, Angular, and production-build verification passed.
- Browser verification covered ready, reduced-quantity, changed-price,
  repair, empty-cart, and individual-listing boundary behavior. The demo
  catalog and inventory rows were restored and the test cart was left empty.
