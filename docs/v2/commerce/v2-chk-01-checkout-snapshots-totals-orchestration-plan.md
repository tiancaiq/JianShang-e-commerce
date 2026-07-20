# V2-CHK-01 Checkout Snapshots, Totals, And Orchestration Plan

Status: implemented and automated verification complete on 2026-07-19 with
the four local-demo-only defaults recorded in section 3. The coordinated live
demo rebuild and browser walkthrough remain pending so this slice does not
invalidate concurrent browser stabilization work.

Release: V2.

Requirements:

- `CHK-01 Create checkout session`
- `CHK-02 Calculate totals`

Delivery naming:

- The approved roadmap packages both requirement IDs into delivery slice
  `V2-CHK-01`.
- There is no separate implementation slice named `V2-CHK-02` in the approved
  slice order.

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Depends on:

- `V2-CART-02`
- `V2-INV-02`
- `V2-IAM-01`

Required by:

- `V2-PAY-01`
- `V2-ORD-01`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- Every existing plan under `docs/v2/commerce/`

## 1. Goal

Create one durable, buyer-owned checkout session from one exact cart version.
The order service must:

1. Revalidate the cart from product, business/store, and inventory sources.
2. Resolve one buyer-owned address without accepting buyer identity or raw
   address data from the browser.
3. Copy immutable item, price, address, calculation, and policy snapshots.
4. Calculate authoritative subtotal, shipping, tax, discount, and total.
5. Reserve every item through the existing all-or-nothing inventory contract.
6. Use the same immutable deadline for checkout and reservation expiry.
7. Recover safely when the reservation result is ambiguous.
8. Let the buyer read or cancel only their own unpaid checkout.

This slice does not create a payment intent or order. Individual marketplace
listings and trades remain completely outside this state machine.

## 2. Slice Boundary

Included:

- Order-service MySQL/Flyway activation alongside its existing Redis cart.
- Durable checkout, item, address, policy, quote, history, idempotency, and
  outbox records.
- Authenticated create, read, and unpaid-cancel checkout APIs.
- Exact-cart-version validation and one active checkout per buyer.
- Immutable server-derived snapshots and totals.
- Synchronous inventory reservation with durable retry/recovery state.
- Checkout expiry plus idempotent reservation release reconciliation.
- A service-authenticated subject-to-buyer/address resolver in auth service.
- The product context fields needed for snapshots: SKU, condition, and catalog
  version.
- Tax, shipping, and policy adapter boundaries.
- A feature-gated buyer checkout review/create/read/cancel UI for local
  verification.
- Structured logs, metrics, traces, and focused tests.

Not included:

- Payment intent creation, provider SDKs, hosted payment UI, or webhooks.
- Order confirmation, reservation commit, payment recovery, or refunds.
- Seller order views, fulfillment, shipments, or notifications.
- Promotions, coupons, gift cards, loyalty, or nonzero discounts.
- Seller-authored policy management.
- Tax filing, tax nexus management, category tax codes, customs, duties, or
  international marketplace rollout.
- Weight, dimensions, warehouses, carrier labels, delivery promises, or live
  shipping rates.
- Cart clearing or purchased-line removal.
- Any checkout action for individual listings or trades.

The buyer-facing checkout entry remains disabled in production until
V2-PAY-01 provides a complete next action. Local browser verification may
enable it through explicit configuration and must always retain Cancel.

## 3. Product Approval Gates

The control center approved all four decisions on 2026-07-19 for this bounded
local-demo slice. The approval does not authorize production use of either
calculation adapter or a production checkout rollout.

| Decision | Recommended smallest local-demo default | Rationale and limit |
|---|---|---|
| Tax adapter and default | `ZERO_LOCAL_DEMO_V1`; return `0.0000` tax for every line | Deterministic and does not pretend the repository has tax nexus or product tax-classification data. It must be rejected by production configuration. A real tax adapter requires a separately approved nexus/tax-code source contract. |
| Shipping calculation adapter and default | `FREE_LOCAL_DEMO_V1`; return one zero-cost shipping allocation per business group | The catalog has no origin, weight, dimensions, package, or carrier-service data. Zero shipping avoids fabricating a rate. It must be labeled local-demo behavior and rejected by production configuration. |
| Platform-default policy version | Immutable `LOCAL_DEMO_V1` shipping/cancellation/return policy owned by order service and snapshotted once per checkout business | V2 requires a policy snapshot while seller policy authoring is V3. The approved exact local-demo text is recorded below. |
| Checkout/reservation lifetime | Configurable `PT15M` local default | Fifteen minutes gives a bounded review/payment window without holding stock for the inventory service's full 24-hour maximum. The same absolute `expiresAt` is sent to inventory. Production may configure another positive duration within the inventory maximum. |

Mandatory safeguards for the recommended demo adapters:

- `checkout.calculation-mode=LOCAL_DEMO` must be explicit.
- Startup fails when a production profile selects either local-demo adapter.
- Responses expose adapter/method version metadata so zero amounts are not
  mistaken for a provider quote.
- The UI uses neutral labels such as `Local demo shipping` and `Local demo
  tax`; it never claims tax exemption, a carrier quote, or free production
  delivery.
- Discounts remain exactly zero until a separate approved requirement exists.

Approved immutable `LOCAL_DEMO_V1` policy text:

- Shipping: `Local demo checkout does not include a carrier or delivery-date
  promise.`
- Cancellation: `An unpaid checkout may be cancelled before expiry.
  Paid-order cancellation is not available in this local demo.`
- Returns: `Automated returns are unavailable in this local demo and require
  a later approved workflow.`

Approved runtime values:

- `checkout.calculation-mode=LOCAL_DEMO`
- `checkout.tax-adapter=ZERO_LOCAL_DEMO_V1`
- `checkout.shipping-adapter=FREE_LOCAL_DEMO_V1`
- `checkout.policy-version=LOCAL_DEMO_V1`
- `checkout.session-lifetime=PT15M`

## 4. Current Repository Reality

The prerequisites already provide:

- One authenticated Redis cart per Keycloak subject.
- Point-in-time cart validation against current product, seller/store, price,
  currency, and inventory facts.
- One checkout-scoped, multi-line inventory reservation with reserve, read,
  release, expiry, commit, idempotency, and all-or-nothing locking.
- A private buyer address book and an internal resolver keyed by auth-service
  buyer ULID plus address ULID.
- Gateway token relay for cart and address browser routes.
- A cart page that can show `Ready for checkout` but has no checkout action.

The checkout gaps are:

- Order service has Redis dependencies but no active MySQL, Flyway, actuator,
  checkout repository, or checkout migration.
- Legacy `V1__init.sql` creates tutorial `t_orders`; it must remain immutable
  and quarantined.
- Cart validation builds a response directly and does not expose a reusable
  immutable checkout candidate.
- The order-service product client drops current SKU and catalog version, and
  the product commerce response does not yet include condition.
- Authentication yields a Keycloak subject while the address resolver expects
  the auth-service user ULID.
- No inventory reservation client exists in order service.
- No tax, shipping, policy, checkout, expiry, or release-reconciliation
  behavior exists.

Existing user changes in these services must be preserved. The deleted
tutorial order controller/entity path must not be restored.

## 5. Ownership And Authorization

- Order service owns checkout IDs, status, snapshots, totals, orchestration,
  checkout history, idempotency results, and checkout outbox events.
- Redis remains authoritative only for the mutable cart before checkout.
- Product service remains authoritative for current listing, business/store,
  title, SKU, condition, catalog version, price, currency, and publication.
- Auth service remains authoritative for Keycloak-subject mapping, active
  buyer identity, and mutable address-book ownership.
- Inventory service remains authoritative for stock and reservation state.
- Tax and shipping adapters are calculation dependencies; their normalized
  outputs become immutable order-service snapshots.
- No service reads or writes another service's database.

Browser authorization rules:

- The JWT subject selects the buyer and cart.
- The request accepts no buyer ID, user ID, business ID, store ID, raw address,
  item list, price, currency, total, reservation ID, or policy content.
- Checkout reads and cancellation query by both checkout ID and resolved buyer
  ID; guessed cross-user IDs return `404 CHECKOUT_NOT_FOUND`.
- A buyer can have at most one active checkout. This prevents several active
  reservations from being created from repeated or modified cart snapshots.

## 6. State Model And Invariants

States implemented by this slice:

```text
RESERVING -> PENDING_PAYMENT
RESERVING -> FAILED
PENDING_PAYMENT -> CANCELLED
PENDING_PAYMENT -> EXPIRED
```

Later payment states are added by forward migrations in V2-PAY-01 rather than
activated early.

Reservation release state:

```text
NOT_REQUIRED -> PENDING -> COMPLETE
```

Core invariants:

1. One buyer has at most one checkout in `RESERVING` or `PENDING_PAYMENT`.
2. A checkout snapshots one nonempty cart version containing 1 through 50
   distinct active business listing lines.
3. Every checkout item uses the current product price and currency, never the
   cart's observed amount or a browser amount.
4. Every line has the checkout currency; mixed currency fails before
   persistence or reservation.
5. Item, address, quote, policy, and total snapshots are immutable after
   checkout insertion.
6. Only status, reservation reconciliation fields, version, and timestamps
   change after insertion.
7. Checkout and reservation have the exact same absolute UTC deadline.
8. `total = subtotal + shipping + tax - discount` and every total equals the
   exact sum of its line allocations.
9. Amounts use `DECIMAL(19,4)`, `BigDecimal`, and stable uppercase ISO currency
   codes. Floating point is forbidden.
10. A checkout response is successful only after the reservation result is
    durable in both owning services.
11. An ambiguous reservation response never produces false failure or a
    second reservation.
12. Cart changes, address edits, price changes, or policy publication after
    checkout creation do not mutate the checkout snapshots.
13. No checkout can create a payment or order in this slice.

## 7. Buyer API Contract

All routes use `/api/v1`, require the authenticated BFF session, use the
standard envelope/error contract, and propagate correlation IDs.

```text
POST /api/v1/checkouts
GET  /api/v1/checkouts/{checkoutId}
POST /api/v1/checkouts/{checkoutId}/cancel
```

Both POST commands require `Idempotency-Key`. GET does not.

### 7.1 Create checkout

Strict request:

```json
{
  "cartVersion": 4,
  "addressId": "01..."
}
```

Rules:

- `cartVersion` must be the exact nonnegative Redis version displayed to the
  buyer.
- `addressId` must be a canonical address ULID owned by the authenticated
  active buyer.
- Unknown fields are rejected so client totals, currency, buyer identity, raw
  address, and item replacement cannot be smuggled into the command.
- Success returns `201` only after inventory reserve succeeded or replayed and
  order service durably stored `PENDING_PAYMENT`.
- The cart is not cleared or mutated.
- A current active checkout conflict includes only the existing checkout ID
  and expiry so the UI can navigate to it.

Response shape:

```json
{
  "id": "01...",
  "status": "PENDING_PAYMENT",
  "cartVersion": 4,
  "currency": "USD",
  "subtotal": 43.9800,
  "shipping": 0.0000,
  "tax": 0.0000,
  "discount": 0.0000,
  "total": 43.9800,
  "expiresAt": "2026-07-19T12:15:00Z",
  "reservation": {
    "id": "01...",
    "status": "ACTIVE",
    "releaseStatus": "NOT_REQUIRED"
  },
  "address": {
    "sourceAddressId": "01...",
    "sourceVersion": 2,
    "label": "Home",
    "recipientName": "Alex Buyer",
    "phone": "+19495550123",
    "line1": "100 Main Street",
    "line2": null,
    "city": "Irvine",
    "region": "CA",
    "postalCode": "92618",
    "countryCode": "US"
  },
  "items": [{
    "listingId": "01...",
    "businessId": "01...",
    "storeId": "01...",
    "catalogVersion": 7,
    "title": "Store item",
    "sku": "SKU-100",
    "condition": "NEW",
    "thumbnailUrl": "/api/v1/public/listing-media/01...",
    "quantity": 2,
    "unitPrice": 21.9900,
    "lineSubtotal": 43.9800,
    "shippingAllocation": 0.0000,
    "taxAllocation": 0.0000,
    "discountAllocation": 0.0000,
    "lineTotal": 43.9800,
    "policyVersion": "LOCAL_DEMO_V1"
  }],
  "shippingQuotes": [{
    "businessId": "01...",
    "storeId": "01...",
    "methodCode": "FREE_LOCAL_DEMO",
    "amount": 0.0000,
    "adapter": "FREE_LOCAL_DEMO_V1"
  }],
  "taxQuote": {
    "amount": 0.0000,
    "adapter": "ZERO_LOCAL_DEMO_V1"
  },
  "policies": [{
    "businessId": "01...",
    "storeId": "01...",
    "source": "PLATFORM_DEFAULT",
    "version": "LOCAL_DEMO_V1",
    "shippingText": "approved text",
    "cancellationText": "approved text",
    "returnText": "approved text"
  }],
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

The response does not expose buyer ID, Keycloak subject, inventory balances,
service tokens, internal quote payloads, or idempotency storage.

### 7.2 Read checkout

- Returns the stored checkout and immutable snapshots only to its buyer.
- Does not reprice, recalculate, re-resolve the address, or read the cart.
- May reconcile a stale displayed reservation state through background work;
  GET itself remains side-effect free.
- `RESERVING`, `FAILED`, `CANCELLED`, and `EXPIRED` responses include safe
  state and issue codes but never internal stack/dependency details.

### 7.3 Cancel checkout

- Has no body.
- `PENDING_PAYMENT` becomes `CANCELLED` in one local transaction, records
  history/outbox, and sets reservation release to `PENDING`.
- Order service calls inventory release with reason `CHECKOUT_CANCELLED` and a
  deterministic idempotency key.
- Successful, already released, or already expired inventory state changes
  release state to `COMPLETE`.
- A transient inventory failure leaves the checkout cancelled with release
  pending; the release worker retries. Cancellation never rolls back.
- Repeating cancellation after `CANCELLED` returns the current checkout.
- `RESERVING`, `FAILED`, or `EXPIRED` returns
  `409 CHECKOUT_NOT_CANCELLABLE` unless the same completed idempotency command
  is being replayed.

## 8. Reusable Cart Assessment

Checkout must repeat CART-02 validation. It cannot trust a previous browser
validation response.

Refactor direction:

- Introduce an internal `CartAssessmentService` that accepts one immutable
  `CartDocument` read and returns a domain result containing every current
  product, seller/store, inventory, issue, and current-price fact.
- The public CART-02 service maps that domain result to the existing response.
- Checkout consumes the same result and does not perform a second set of
  product/auth/inventory reads.
- Checkout first compares the loaded cart version to request `cartVersion`.
- A later cart mutation does not change the already loaded checkout candidate;
  it creates a new Redis version for a later checkout attempt.
- Checkout proceeds only when assessment is nonempty, all lines are ready,
  and exactly one current currency exists.

The product commerce context must include and order service must retain:

- listing, business, and store IDs
- title and thumbnail
- SKU and condition
- current catalog version
- seller type and active publication state
- current price and currency

## 9. Address Resolution Contract

The browser supplies only `addressId`, but the current JWT supplies a Keycloak
subject while the existing internal address route expects an auth-service
buyer ULID.

Add one token-protected auth-service composition route:

```text
POST /api/v1/internal/users/checkout-address-resolution
```

Internal request generated by order service:

```json
{
  "subject": "authenticated-keycloak-subject",
  "addressId": "01..."
}
```

Response uses the existing internal address fields and adds the resolved
auth-service `buyerId` when needed. Rules:

- Requires `X-Internal-Service-Token` and is never gateway routed.
- Maps only an active user with the exact subject.
- Resolves only an address owned by that user.
- Missing subject, inactive user, missing address, or wrong owner returns the
  same privacy-preserving `404 BUYER_ADDRESS_NOT_FOUND`.
- Subject and address PII are not logged.
- The existing buyer-ID-based resolver remains available and unchanged.

Order service stores the returned buyer ULID and copied normalized address.
It never stores a foreign key to auth-service `addresses`.

## 10. Calculation Contracts

The calculation pipeline receives only the validated immutable candidate and
copied address snapshot.

Order:

1. Calculate each line subtotal as current unit price times integer quantity.
2. Group lines by `(businessId, storeId)`.
3. Resolve the effective policy version for each group.
4. Call the configured shipping adapter and validate exact per-line
   allocations.
5. Call the configured tax adapter using item, shipping, address, and policy
   facts and validate exact per-line allocations.
6. Set every discount allocation to zero.
7. Sum line and checkout totals and verify exact equality.

Adapter interfaces are order-service ports, not new microservices:

- `ShippingCalculationAdapter`
- `TaxCalculationAdapter`
- `PlatformPolicyProvider`

Normalized adapter output rules:

- Same checkout currency only.
- One allocation for every checkout line and no unknown line.
- Nonnegative shipping and tax.
- At most four decimal places and no floating-point conversion.
- Allocation sums equal the adapter quote total exactly.
- Stable adapter name/version, method code, optional bounded provider quote
  reference, and quote timestamp.
- Provider request/response payloads are not exposed to the browser or logs.

Any timeout, malformed output, currency mismatch, missing allocation, or
arithmetic overflow fails closed before reservation.

## 11. Create Orchestration And Crash Recovery

Create uses a durable orchestrator, not one distributed transaction.

### 11.1 Before reservation

1. Require authentication, CSRF, `Idempotency-Key`, strict request fields,
   and canonical IDs.
2. Hash `{resolvedBuyerSubject, cartVersion, addressId}` for command
   idempotency. Lookup occurs before active-checkout conflict checks.
3. Read the Redis cart once and require exact version/nonempty bounds.
4. Build the reusable current cart assessment.
5. Resolve the buyer ULID and address snapshot through auth service.
6. Calculate shipping, tax, zero discount, policies, allocations, and totals.
7. Choose `expiresAt = now + configuredLifetime` once.
8. In one order-service transaction, insert:
   - checkout in `RESERVING`
   - immutable items/address/quotes/policies
   - `CREATED -> RESERVING` history
   - in-progress idempotency record

No inventory call occurs when cart, address, policy, or calculation validation
fails.

### 11.2 Reserve

Order service calls:

```text
POST /api/v1/internal/inventory/reservations
```

with:

- `checkoutId` equal to the durable checkout ID
- purpose `CHECKOUT`
- the exact checkout `expiresAt`
- immutable `{listingId, quantity}` lines
- deterministic idempotency key `checkout-reserve:{checkoutId}`

On reserve success or replay, order service reads the returned reservation's
current state when needed and verifies checkout ID, purpose, deadline, and
line identity.

### 11.3 Durable outcomes

- Active usable reservation: atomically set checkout `PENDING_PAYMENT`, store
  reservation ID/state/version, append history and `checkout.created.v1`
  outbox, and complete idempotency with the `201` response.
- Stable insufficient stock: atomically set checkout `FAILED`, append safe
  line issues and `checkout.failed.v1`, and complete idempotency with
  `409 CHECKOUT_INSUFFICIENT_STOCK`.
- Stable invalid reservation contract: set `FAILED`, emit an integrity metric,
  and return a safe checkout orchestration error.
- Timeout, connection loss, or ambiguous `5xx`: leave checkout `RESERVING`
  and idempotency in progress; return
  `503 CHECKOUT_RESERVATION_PENDING`. Retry with the same key resumes the same
  checkout rather than creating another reservation.

The `RESERVING` recovery worker retries the exact deterministic reserve
command before expiry. A crash after inventory committed reserve but before
order-service status update therefore replays the original reservation.

Inventory contract hardening required before checkout activation:

- Identical completed reserve requests must replay even when the stored
  `expiresAt` is now in the past. Structural validation and request hashing
  occur before replay lookup; time-relative new-request validation occurs only
  after no replay exists.
- Add a regression test for retry after deadline.
- After replay, order service verifies current reservation state with GET so
  an already expired replay cannot become `PENDING_PAYMENT`.

If an unresolved `RESERVING` checkout reaches its deadline, recovery performs
one final replay attempt. It then records `EXPIRED` when the reservation is
already expired/released or when no new past-deadline reservation can be
created. Any active reservation found at the boundary is released
idempotently.

## 12. Expiry And Release Reconciliation

Checkout expiry worker:

- Claims a bounded batch of `PENDING_PAYMENT` rows where
  `expires_at <= now` using `FOR UPDATE SKIP LOCKED`.
- Changes each checkout to `EXPIRED`, appends history and
  `checkout.expired.v1`, and sets release state `PENDING` in one transaction.
- Does not call inventory while holding the checkout row transaction.

Release reconciliation worker:

- Claims cancelled/expired checkouts with release state `PENDING`.
- Calls inventory release with deterministic key
  `checkout-release:{checkoutId}` and reason `CHECKOUT_CANCELLED` or
  `SYSTEM_RECOVERY`.
- Treats inventory `RELEASED` or `EXPIRED` as complete.
- Retries transient failures with bounded backoff and safe attempt metadata.
- Never changes checkout back to an active state.

Inventory's own expiry worker remains authoritative for stock restoration at
the deadline. The order worker reconciles checkout state and observes/replays
release; the two workers are intentionally safe in either execution order.

Configuration:

- `checkout.enabled`
- `checkout.session-lifetime`
- `checkout.expiry-worker-enabled`
- `checkout.expiry-interval-ms`
- `checkout.expiry-batch-size`
- `checkout.recovery-interval-ms`
- `checkout.recovery-batch-size`
- `checkout.idempotency-retention`

All durations and batch bounds are validated at startup. Tests inject a clock
and never depend on sleeping.

## 13. Database Plan

Do not rewrite legacy `V1__init.sql`. Add:

```text
order-service/src/main/resources/db/migration/
V2__create_checkout_foundation.sql
```

Use InnoDB, UTC `TIMESTAMP(6)`, `utf8mb4`, ASCII/binary ULIDs and stable codes,
and `DECIMAL(19,4)` money columns.

### 13.1 `checkout_sessions`

Stores:

- checkout ULID and auth-service buyer ULID
- status and optimistic transition version
- cart version and SHA-256 canonical cart snapshot hash
- currency and subtotal/shipping/tax/discount/total
- immutable expiry
- reservation ID, observed reservation status/version
- release status and bounded safe last release error code
- failure code when applicable
- created/updated timestamps
- generated nullable `active_buyer_id` equal to buyer ID only for
  `RESERVING` or `PENDING_PAYMENT`

Constraints/indexes:

- Unique generated `active_buyer_id` enforces one active checkout per buyer.
- Unique reservation ID when nonnull.
- Checks for allowed statuses, nonnegative totals, exact total equation, and
  reservation/release field consistency.
- `(buyer_id, created_at, id)` for buyer ownership reads.
- `(status, expires_at, id)` for expiry.
- `(status, updated_at, id)` for reserving recovery.
- `(release_status, updated_at, id)` for release reconciliation.

### 13.2 `checkout_items`

Immutable rows containing:

- checkout and stable line number
- listing, business, and store IDs
- catalog version, title, SKU, condition, and thumbnail snapshot
- quantity, unit price, currency
- line subtotal, shipping/tax/discount allocations, line total
- policy snapshot reference
- created timestamp

Constraints:

- Unique `(checkout_id, listing_id)` and `(checkout_id, line_number)`.
- Positive quantity and nonnegative amounts.
- Exact line-total equation.
- Checkout-owned foreign keys only.

### 13.3 `checkout_addresses`

One immutable row per checkout containing source address ID/version, label,
recipient name/phone, address lines, city, region, postal code, country code,
and snapshot timestamp. It has no foreign key to auth-service.

### 13.4 `platform_policy_versions`

Immutable order-service policy source:

- local ULID
- unique stable version code
- source `PLATFORM_DEFAULT`
- shipping, cancellation, and return text
- effective-from and optional effective-to timestamps
- created timestamp

No seller/admin mutation API is added. The approved initial version is inserted
by the migration and is never updated or deleted; later policy changes insert
a new version.

### 13.5 `checkout_policy_snapshots`

One immutable row per checkout/business/store containing source policy ID,
source/version code, copied shipping/cancellation/return text, and snapshot
timestamp. Unique `(checkout_id, business_id)`.

### 13.6 `checkout_shipping_quotes` and `checkout_tax_quotes`

Shipping has one immutable normalized quote per checkout/business/store. Tax
has one immutable normalized checkout quote. They store adapter name/version,
method code where applicable, amount, optional bounded provider reference, and
quote timestamp. Raw provider payloads are not stored in this slice.

### 13.7 `checkout_status_history`

Append-only transition audit containing checkout, from/to status, stable reason
code, command ID, correlation/causation IDs, safe details JSON, and timestamp.
Command ID is unique.

### 13.8 `order_idempotency_records`

Stores caller scope, key, request hash, operation, `IN_PROGRESS` or
`COMPLETED`, nullable checkout/result reference, HTTP status/response JSON when
complete, and retention timestamps. Unique `(caller_scope, idempotency_key)`.

### 13.9 `order_outbox_events`

Stores checkout event ID/type/version, aggregate, safe payload,
correlation/causation, publication timestamp, and retry count. This slice
persists outbox rows transactionally; Kafka publication may remain disabled
until the first live consumer slice.

## 14. Idempotency And Concurrency

Create caller scope:

```text
BUYER:CREATE_CHECKOUT:{buyerId}
```

Cancel caller scope:

```text
BUYER:CANCEL_CHECKOUT:{buyerId}:{checkoutId}
```

Rules:

- Same scope/key/hash replays the original status and response.
- Same scope/key with another hash returns `409 IDEMPOTENCY_KEY_REUSED`.
- Create hash contains resolved subject identity, cart version, and address ID;
  item/price snapshots are server outputs, not request hash inputs.
- Cancel hash contains buyer and checkout IDs.
- Idempotency lookup precedes active/state conflict checks.
- Concurrent create with different keys is stopped by the active-buyer unique
  constraint; the loser receives the existing checkout reference.
- Concurrent cancel/expiry uses checkout row locking and one legal terminal
  transition.
- Snapshot rows are insert-only through repository ownership rules.
- Completed responses and stable business failures are retained. Malformed,
  unauthenticated, and transient pre-persistence dependency failures are not.

## 15. Error Contract

| Status | Code | Meaning |
|---|---|---|
| `400` | `IDEMPOTENCY_KEY_REQUIRED` | Required command key is missing/invalid |
| `400` | `CHECKOUT_INVALID_REQUEST` | IDs, version, or forbidden fields are invalid |
| `404` | `CHECKOUT_NOT_FOUND` | Checkout is absent or belongs to another buyer |
| `404` | `CHECKOUT_ADDRESS_NOT_FOUND` | Selected buyer-owned address cannot resolve |
| `409` | `CHECKOUT_CART_EMPTY` | Current cart has no lines |
| `409` | `CHECKOUT_CART_VERSION_CONFLICT` | Browser version differs from the current Redis cart |
| `409` | `CHECKOUT_CART_INVALID` | Current source validation has actionable issues |
| `409` | `CHECKOUT_ALREADY_ACTIVE` | Buyer already has an active checkout |
| `409` | `CHECKOUT_INSUFFICIENT_STOCK` | Atomic reserve failed with safe line details |
| `409` | `CHECKOUT_NOT_CANCELLABLE` | Current state cannot be cancelled by this slice |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Key was reused with another request hash |
| `422` | `CHECKOUT_CALCULATION_INVALID` | Adapter output or total invariants are invalid |
| `503` | `CHECKOUT_DEPENDENCY_UNAVAILABLE` | Cart source, address, policy, tax, or shipping dependency failed before reserve |
| `503` | `CHECKOUT_RESERVATION_PENDING` | Reserve outcome is ambiguous and durable recovery is active |

Cart-invalid details reuse CART-02 issue codes/actions. Address, dependency,
and calculation errors never echo full address values or provider payloads.

## 16. Gateway And Service Wiring

Order service:

- Add JDBC, Flyway MySQL, actuator, MySQL runtime, and MySQL Testcontainers
  dependencies while retaining Redis.
- Add datasource/Flyway and validated checkout properties.
- Add auth-address, inventory-reservation, product-context, shipping, tax, and
  policy adapters.

Gateway:

- Route `/api/v1/checkouts` and `/api/v1/checkouts/**` to order service.
- Relay the buyer token and preserve BFF CSRF behavior.
- Add standard order-service circuit-breaker fallback for all three methods.
- Never route auth or inventory internal commerce endpoints.

Product service:

- Extend the existing internal commerce response with condition.
- Preserve SKU and catalog version already present in the product response.

Auth service:

- Add the internal subject/address composition route from section 9.

Inventory service:

- Preserve existing reservation routes and add only the reserve replay-order
  hardening/test required by section 11.

## 17. Frontend Plan

Add feature flag:

```text
features.buyerCheckout
```

Rules:

- Production default remains false until V2-PAY-01 completes the next buyer
  action.
- Local verification can enable the flag explicitly after the four decisions
  are approved.
- Direct checkout navigation while disabled redirects to `/cart` without
  calling checkout APIs.

Routes:

```text
/checkout
/checkout/{checkoutId}
```

The review page:

1. Requires authentication.
2. Loads the current cart and repeats validation.
3. Loads the bounded address book and selects the default when present.
4. Links to `/account/addresses` when no address exists.
5. Shows current item subtotal; shipping/tax remain `Calculated when checkout
   starts` until POST succeeds.
6. Generates and retains one browser idempotency key for retries of the same
   cart version/address selection.
7. Creates checkout only when validation is ready and an address is selected.

The checkout detail page renders only stored snapshots, expiry, calculation
metadata, policies, and Cancel. It has no payment button, card fields, order
claim, delivery promise, or provider redirect in this slice.

Cart integration:

- Show the checkout command only when CART-02 is ready and the feature flag is
  enabled.
- A stale validation/cart version returns the buyer to repair/revalidate.
- `CHECKOUT_ALREADY_ACTIVE` navigates to the existing checkout.

Required states include loading, no address, validation changed, creating,
reservation pending, pending payment, failed, cancelled, expired, retry, and
service unavailable. Countdown display is informational; server time/state is
authoritative.

## 18. Events And Observability

Transactional outbox events:

- `checkout.created.v1`
- `checkout.failed.v1`
- `checkout.cancelled.v1`
- `checkout.expired.v1`

Safe payloads include checkout ID, buyer opaque ID, cart version/hash,
currency/totals, reservation ID/status, business/listing IDs, expiry, and
correlation metadata. They exclude exact address fields, subject, policy text,
provider payloads, and service tokens.

Structured logs/traces connect:

- cart read and assessment
- buyer/address resolution
- policy/tax/shipping calculation
- checkout persistence
- inventory reserve/replay/read
- cancellation, expiry, and release reconciliation

Metrics:

- checkout create attempts by result
- cart/address/calculation failure counts
- calculation latency by adapter
- reserve success, insufficient stock, ambiguous response, and recovery age
- active checkout count and expiry lag
- cancellation and release-pending age
- idempotency replay/conflict count
- active-buyer conflict count
- outbox backlog age
- invariant violation count

Exact address, phone, subject, policy text, provider secrets, and raw adapter
payloads are redacted from logs, metrics, traces, and errors.

## 19. Test Plan

Domain/unit:

- Exact cart-version and one-currency rules.
- Reusable cart assessment maps the same source facts for cart and checkout.
- Snapshot construction includes SKU, condition, catalog version, and address
  source version.
- Decimal subtotal/allocation/total equations and overflow rejection.
- Missing, duplicate, negative, excessive-scale, wrong-currency, and
  mismatched adapter allocations fail closed.
- Zero discount behavior.
- State transition and release-state matrices.
- Fixed-clock lifetime boundary.
- Request canonicalization and idempotency hashes.

MySQL integration:

- Forward migration succeeds after immutable tutorial V1.
- Tutorial `t_orders` remains unused.
- Snapshot/address/policy/quote rows are immutable.
- One active checkout per buyer under concurrent different keys.
- Same-key concurrent create produces one checkout and reservation command.
- Status, history, idempotency, and outbox writes are atomic.
- Exact money and status constraints reject invalid rows.
- Expiry workers use bounded `SKIP LOCKED` claims.
- Concurrent cancel/expiry produces one terminal transition.

Orchestration/integration:

- Ready single-business and multi-business carts.
- Empty, stale-version, invalid, and mixed-currency carts never reserve.
- Wrong-owner or inactive-buyer address never persists checkout data.
- Source changes between CART-02 display and checkout are revalidated.
- Reserve success, replay, insufficient stock, timeout, and malformed response.
- Crash after reserve but before local status completion recovers one checkout.
- Reserve replay after deadline returns the prior reservation result and GET
  observes its current state.
- Checkout and reservation deadlines are exactly equal.
- Cancel and expiry release once; either inventory/order expiry-worker order is
  harmless.
- Cart/address edits after creation do not change snapshots.
- Cross-user GET/cancel returns not found.

Service/gateway security:

- Authentication and CSRF required for browser writes.
- `Idempotency-Key` required for create/cancel.
- Browser payload rejects buyer IDs, items, prices, totals, currency, address
  bodies, reservation IDs, and unknown fields.
- Internal auth/inventory routes require service token and are not gateway
  routed.
- Gateway routes checkout methods and returns the standard 503 envelope.

Frontend:

- Feature-disabled routes/CTA do not call checkout.
- Ready validation and default/nondefault address selection.
- No-address link and stale-cart repair path.
- One retained idempotency key per logical create retry.
- Stored snapshots/totals/policies render after create and reload.
- Active-checkout conflict navigation.
- Cancel, expired, failed, reservation-pending, and service-error states.
- No payment, order, or individual-trade checkout controls.

Regression:

- CART-01/CART-02 behavior and Redis TTL remain unchanged.
- Inventory reserve/release/expiry tests remain green.
- Address-book CRUD/default behavior remains unchanged.
- Seller inventory and public business catalog remain tenant-safe.
- Individual listing/trade pages never expose checkout.

## 20. Browser Verification

Use a dedicated buyer fixture, initialized business inventory, and fake test
address:

1. Enable local-demo checkout and approved calculation/policy configuration.
2. Sign in and add initialized active business items to the cart.
3. Open `/checkout`; verify cart revalidation and default address selection.
4. Create checkout and verify immutable current prices, address, policy,
   shipping/tax metadata, totals, reservation ID, and matching expiry.
5. Reload `/checkout/{id}` and verify persistence without source re-fetch.
6. Edit the address and product price after creation; verify checkout snapshot
   remains unchanged.
7. Verify seller inventory shows reserved stock during the active checkout.
8. Cancel and verify release completes and seller availability returns.
9. Create another short configured checkout, allow expiry, and verify both
   checkout and reservation become expired without duplicate release.
10. Exercise stale cart version and insufficient stock; verify actionable safe
    errors and no false pending checkout.
11. Verify a second account cannot read or cancel the checkout.
12. Confirm no payment/order action exists and individual listings remain
    outside checkout.
13. Remove the fake address, clear the cart, restore demo inventory/catalog
    changes, and confirm no browser console errors.

## 21. Implementation Order

Do not begin until section 3 is explicitly approved.

1. Record approved adapter modes, policy version/text, and lifetime in this
   document and the authoritative API/database docs.
2. Add order-service MySQL/Flyway/actuator configuration and migration tests.
3. Add the forward-only checkout foundation migration.
4. Add checkout records/repositories, money invariants, clock, and validated
   configuration.
5. Refactor cart validation into the reusable assessment without changing
   CART-02 behavior.
6. Extend product context and add subject/address plus reservation clients.
7. Implement policy, shipping, tax, and zero-discount calculation ports.
8. Implement create persistence, reserve orchestration, idempotency, history,
   outbox, and ambiguous-result recovery.
9. Implement buyer read, cancel, expiry, and release reconciliation.
10. Add gateway routes and exact API/security tests.
11. Add feature-gated Angular checkout review/detail/cancel paths and tests.
12. Run order, cart, inventory, auth, gateway, frontend, concurrency, and
    production-build verification.
13. Complete browser verification, restore fixtures, and mark the slice
    complete.

## 22. Planning Completion Criteria

- CHK-01 and CHK-02 are one bounded delivery slice.
- The trade/order boundary and service ownership are unchanged.
- Browser inputs, server source reads, snapshots, totals, and responses are
  exact.
- Address subject/ULID resolution is closed without trusting a browser buyer
  ID.
- Reservation orchestration is crash-recoverable and idempotent.
- One-active-checkout, expiry, cancellation, and release behavior are defined.
- Persistence, indexes, constraints, events, metrics, tests, and UI gating are
  implementation-ready.
- Tax, shipping, policy, and lifetime defaults are explicitly approved,
  recorded, and rejected by production-profile configuration validation.
- No V2-PAY-01 behavior is designed or implemented beyond the stable
  `PENDING_PAYMENT` handoff.

## 23. Deferred Work

- V2-PAY-01 owns payment provider selection, intent, browser client data,
  verified webhook, and payment state.
- V2-ORD-01 owns reservation commit, payment-success recovery, immutable order
  creation, fulfillment groups, and reconciliation cases.
- V2-ORD-02 owns buyer/business order views.
- V2-ORD-03 owns paid-order cancellation, refunds, and inventory compensation.
- V2-SHP-01 owns fulfillment and actual shipment lifecycle.
- V3 BUS-06 owns seller-defined, versioned shipping/cancellation/return policy.
- Production tax and shipping providers require separately approved catalog,
  nexus, origin, package, carrier, and compliance contracts.
