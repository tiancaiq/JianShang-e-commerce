# V2-COM-00 Commerce Domain Plan

Status: complete as a documentation-only planning slice.

Release: V2.

Requirements:

- `CRT-01` through `CRT-04`
- `INV-01` through `INV-04`
- `CHK-01` and `CHK-02`
- `PAY-01` through `PAY-03`
- `ORD-01` through `ORD-04`
- `SHP-01` through `SHP-04`
- `IAM-05`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`

## 1. Outcome

V2 adds an Amazon-style purchase path for active business store items:

1. A buyer adds business items to one active cart.
2. Checkout revalidates listings, prices, inventory, currency, and address.
3. Inventory is reserved for a bounded checkout window.
4. A payment provider collects payment without exposing raw card data to the
   platform.
5. Verified payment success creates one buyer order containing one fulfillment
   group per business.
6. Business staff fulfill only their own groups and record shipments.

This flow is separate from individual marketplace trades. Individual listings
never enter the cart, inventory, checkout, payment, order, or shipment state
machines.

Frontend placement:

- `/stores` remains an item-first business catalog and gains availability and
  add-to-cart actions when the cart slice is enabled.
- Buyer cart, checkout, and order history use the public marketplace/account
  experience.
- Business inventory, fulfillment groups, and shipment work use the business
  seller portal.
- Individual marketplace listings keep their message/trade calls to action and
  never display business checkout controls.

V2-COM-00 changes documentation only. It does not activate the archived V2
services, add migrations, or expose unfinished commerce UI.

## 2. Entry Gate

Commerce implementation starts only after the MVP browser, seller, listing,
search, chat, and moderation paths have a green verification baseline as
required by the current roadmap.

The first implementation slice must also confirm:

- Docker-backed MySQL and Redis integration tests can run.
- Active business items have stable listing IDs, business IDs, SKUs, prices,
  currency, and publication state.
- Gateway service authentication can protect internal service commands.
- The business owner can be resolved as an authorized member of the referenced
  business.

Existing `inventory-service`, `order-service`, and `payment-service` code is
archived tutorial material. Each service must be audited and rebuilt against
this plan one slice at a time rather than added to the active Maven build
unchanged.

## 3. Domain Boundaries

### 3.1 Product service

Owns business listing identity, seller/business identity, title, SKU,
condition, active publication state, current unit price, and currency.

It does not own authoritative stock, reservations, carts, checkout totals,
payments, or orders. The existing listing `quantity` remains display-only
catalog data until an inventory item is explicitly initialized.

### 3.2 Inventory service

Owns:

- `inventory_items`
- `inventory_movements`
- `inventory_reservations`
- Available-to-sell calculation
- Atomic reserve, release, expiry, and commit behavior
- Inventory adjustment authorization and optimistic concurrency

It never trusts a quantity, business ID, or listing eligibility claim supplied
only by a browser. Listing and business linkage is validated through an
internal product contract when inventory is initialized.

### 3.3 Order service

Acts as the commerce orchestrator and owns:

- The Redis cart namespace
- Cart validation results
- Checkout sessions and immutable checkout item snapshots
- Authoritative checkout totals
- Buyer order headers
- Per-business fulfillment groups
- Immutable order item, address, and policy snapshots
- Cancellation state
- Shipments and shipment timelines
- Payment/order mismatch workflow state

The order service never updates inventory or payment tables. It uses
idempotent internal commands and durable events.

### 3.4 Payment service

Owns:

- Provider payment intents and attempts
- Verified and deduplicated provider webhook events
- Payment state
- Refund state
- Fee and payout projections
- Payment reconciliation input

The payment provider is authoritative about whether money moved. Browser
redirects are never payment confirmation.

### 3.5 Auth service

Owns buyer address-book records and business membership/permission facts.
Checkout resolves an address by authenticated buyer and copies an immutable
snapshot into the order schema. Commerce services do not update the address
book.

### 3.6 Notification service

Consumes committed commerce events. Notification delivery is never part of an
inventory, payment, or order transaction and cannot determine transaction
success.

## 4. Ownership Rules

| Data or decision | Authority |
|---|---|
| Listing publication, title, SKU, price, currency | Product service |
| Business membership and buyer address book | Auth service |
| On-hand, reserved, and available quantity | Inventory service |
| Cart contents | Order service Redis namespace |
| Checkout snapshots and totals | Order service |
| Provider payment status | Payment service |
| Buyer order and business fulfillment status | Order service |
| Shipment records and timeline | Order service |
| Public searchable availability | Derived projection only |

There are no cross-service foreign keys or database writes. External IDs are
stored as references and revalidated at business-flow boundaries.

## 5. Core Invariants

1. Only active, business-owned store items can enter a cart.
2. Individual listings cannot enter any commerce command.
3. Cart prices are advisory; checkout prices and totals are server-derived.
4. A checkout contains one currency. Mixed-currency carts are rejected before
   reservation.
5. `available = on_hand - reserved` and is never negative.
6. Every stock adjustment creates one append-only movement.
7. Reserve, release, commit, checkout creation, payment intent creation, order
   confirmation, cancellation, refund, and shipping commands are idempotent.
8. Reusing an idempotency key with a different request hash returns a conflict.
9. One checkout can create at most one buyer order.
10. One order has at most one business fulfillment group per business.
11. A business actor can see or change only its own inventory and fulfillment
    groups.
12. Buyer address, item, price, totals, and applicable policy snapshots are
    immutable after order confirmation.
13. Payment success without an order is recoverable and visible to operations.
14. Notification failure cannot roll back a paid order.
15. Redis and OpenSearch are never authoritative for inventory, payment, or
    order state.

## 6. State Models

### 6.1 Cart

The cart is a replaceable Redis document with a version and expiry. It has no
durable purchase meaning. Missing or expired carts are treated as empty.

Cart validation reports per-item status such as:

- `READY`
- `PRICE_CHANGED`
- `QUANTITY_REDUCED`
- `OUT_OF_STOCK`
- `LISTING_UNAVAILABLE`
- `CURRENCY_CONFLICT`

Validation does not reserve stock.

### 6.2 Inventory reservation

```text
ACTIVE -> COMMITTED
ACTIVE -> RELEASED
ACTIVE -> EXPIRED
```

Terminal transitions are single-application and retry-safe. Expiry uses an
indexed worker with row locking or optimistic compare-and-set behavior.

### 6.3 Checkout

```text
RESERVING -> PENDING_PAYMENT -> PAYMENT_PROCESSING -> COMPLETED
RESERVING -> FAILED
PENDING_PAYMENT -> CANCELLED
PENDING_PAYMENT -> EXPIRED
PAYMENT_PROCESSING -> PAYMENT_REVIEW
PAYMENT_REVIEW -> COMPLETED
PAYMENT_REVIEW -> REFUND_REQUIRED
```

The checkout expiry and inventory reservation expiry use the same deadline.
`PAYMENT_REVIEW` covers verified provider payment that cannot yet become an
order.

### 6.4 Payment

```text
CREATED -> REQUIRES_ACTION -> PROCESSING -> SUCCEEDED
CREATED | REQUIRES_ACTION | PROCESSING -> FAILED
SUCCEEDED -> PARTIALLY_REFUNDED -> REFUNDED
SUCCEEDED -> REFUNDED
```

Provider events can arrive late or out of order. The payment service maps them
to legal monotonic transitions and retains the immutable provider event.

### 6.5 Buyer order

```text
CONFIRMED -> FULFILLING -> PARTIALLY_SHIPPED -> SHIPPED -> DELIVERED
CONFIRMED | FULFILLING -> CANCELLATION_REQUESTED -> CANCELLED
```

Until fulfillment transitions are implemented, buyer reads expose the stored
`orders.status`. Aggregate derivation from business fulfillment groups begins
only with a future fulfillment slice that defines the complete mapping.
Cancellation never implies a refund or stock release unless the corresponding
idempotent commands have succeeded.

### 6.6 Business fulfillment group

```text
PENDING_ACCEPTANCE -> ACCEPTED -> PARTIALLY_SHIPPED -> SHIPPED -> DELIVERED
PENDING_ACCEPTANCE | ACCEPTED -> CANCELLATION_PENDING -> CANCELLED
```

One shipment can cover some or all remaining quantities. Shipment quantities
cannot exceed unshipped order item quantities.

## 7. Purchase Orchestration

### 7.1 Add and validate cart

1. The authenticated buyer adds a listing ID and positive quantity.
2. Order service loads the current commerce-eligible listing view from product
   service and availability from inventory service.
3. Order service writes only the listing ID, quantity, observed price,
   currency, and timestamp to Redis.
4. Validation repeats source reads and returns changes without mutating
   authoritative inventory.

### 7.2 Create checkout

1. Order service creates an idempotent checkout record in `RESERVING`.
2. It resolves the buyer-owned address and current product facts.
3. It calculates authoritative item, shipping, tax, discount, and total
   amounts through configured server-side adapters.
4. It writes immutable checkout item and address input snapshots.
5. It sends one idempotent reservation command to inventory service.
6. On success, checkout becomes `PENDING_PAYMENT`; on failure it becomes
   `FAILED` with actionable item errors.
7. A response is returned only after reservation outcome is durable.

If the order service fails after inventory reservation, retry uses the same
checkout and reservation idempotency keys. The expiry worker eventually
releases an abandoned active reservation.

### 7.3 Pay and confirm order

1. Order service requests one provider intent from payment service using the
   checkout amount, currency, expiry, and an idempotency key.
2. The buyer completes the provider-hosted flow.
3. Payment service verifies and deduplicates the webhook, updates payment
   state, and publishes `payment.succeeded` through its outbox.
4. Order service deduplicates the event and asks inventory service to commit
   the reservation.
5. After a successful or already-completed commit, order service creates the
   buyer order, business fulfillment groups, immutable snapshots, history,
   and `order.confirmed` outbox event in one local transaction.
6. Replayed events return the existing order.

If the reservation expired before verified payment:

1. Order service attempts one atomic recovery reservation with a deterministic
   recovery idempotency key.
2. If stock is available, it commits that reservation and confirms the order.
3. If stock is unavailable, checkout enters `REFUND_REQUIRED`, no confirmed
   order is exposed, and reconciliation creates an operations item for refund
   or approved manual recovery.

This path never drives available stock below zero.

### 7.4 Expiry and reconciliation

- Inventory service expires active reservations once.
- Order service expires unpaid checkout sessions and requests reservation
  release.
- Payment service polls or otherwise reconciles provider intents that lack a
  final verified event.
- Order service scans succeeded payments without an order and replays the
  confirmation command.
- Unresolved payment/order or payment/inventory mismatches appear in an admin
  operations queue with correlation IDs and safe diagnostic context.

## 8. Authorization

Buyer routes derive the buyer ID from the access token. They never accept a
replacement buyer ID.

Business routes require matching `businessId` membership plus a permission:

| Capability | Planned permission |
|---|---|
| View inventory | `INVENTORY_VIEW` |
| Adjust inventory | `INVENTORY_MANAGE` |
| View fulfillment groups | `ORDER_VIEW` |
| Accept and ship orders | `ORDER_FULFILL` |
| View fee/refund projections | `ORDER_FINANCE_VIEW` |

Until V3 staff management exists, the approved business owner receives these
permissions through the existing owner membership. The backend still checks
business scope on every route.

Internal inventory and payment commands require service authentication,
correlation propagation, and `Idempotency-Key`.

## 9. API And Event Direction

The route families already sketched in `docs/mvp/api-contract.md` remain the
baseline. Implementation slices must add exact request/response schemas,
standard errors, cursor behavior, and OpenAPI-generated client types.

Inventory adjustments use `If-Match` for the expected inventory version.
State-changing money, reservation, checkout, order, cancellation, refund, and
shipping commands require `Idempotency-Key`.

Initial durable events:

- `inventory.adjusted`
- `inventory.reservation.created`
- `inventory.reservation.released`
- `inventory.reservation.expired`
- `inventory.reservation.committed`
- `checkout.created`
- `checkout.expired`
- `payment.succeeded`
- `payment.failed`
- `payment.refunded`
- `order.confirmed`
- `order.cancelled`
- `shipment.created`
- `shipment.shipped`
- `shipment.delivered`

Each event has an event ID, version, occurred-at timestamp, correlation ID,
causation ID, actor-safe context, and owning aggregate ID. Producers use a
transactional outbox; consumers record processed event IDs.

`V2-PAY-01D` implements the payment-service dispatcher contract for version-1
`payment.succeeded` and `payment.failed` rows. It claims due events in stable
creation order under bounded leases, partitions by payment-intent ID, marks
publication only after transport acknowledgement, and retries with bounded
backoff and safe terminal metadata. Delivery is at-least-once; consumers
deduplicate by event ID. Dispatcher and worker remain default-off, with only a
transport interface and deterministic in-memory test transport; no Kafka
adapter or broker runtime is activated.

## 10. Storage And Concurrency

- Inventory, reservations, movements, and idempotency records use the
  inventory-service MySQL schema.
- Checkout, order, fulfillment, shipment, history, outbox, and idempotency
  records use the order-service MySQL schema.
- Payment, attempt, event, refund, outbox, and idempotency records use the
  payment-service MySQL schema.
- Cart documents use Redis and can be recreated or lost without corrupting a
  purchase.
- No service relies on Hibernate schema auto-update.

Reservation uses a conditional update or locked row transaction that verifies
available quantity. Concurrent reservation tests must prove zero oversell.
Seller inventory adjustments use optimistic version checks. Expiry, release,
commit, and event consumption are retry-safe.

## 11. Inventory Bootstrap

The existing business listing `quantity` is not silently migrated into
authoritative stock.

V2-INV-01 introduces an explicit inventory initialization action for each
eligible active or paused business listing. The UI may show the catalog
quantity as a suggested starting value, but the business owner must confirm
it. Cart and checkout are enabled only after the listing is active and an
inventory item exists with positive available stock.

This avoids presenting old display-only values as audited inventory.

## 12. Observability And Failure Handling

Structured logs and traces must connect:

- cart validation
- checkout creation
- reservation commands
- payment intent and verified provider event
- order confirmation
- reservation recovery
- cancellation/refund
- shipment changes

Metrics and alerts include:

- reservation conflict and expiry counts
- oversell invariant violations
- checkout failure reasons
- payment success without order age
- committed reservation without order age
- provider webhook signature failures and duplicates
- outbox backlog and consumer retry age
- refund-required queue age

Logs redact address details, provider client secrets, webhook secrets, payment
instrument data, and unnecessary buyer PII.

## 13. Delivery Slices

Implement one slice at a time in this order:

| Slice | Requirements | Outcome |
|---|---|---|
| V2-COM-00 | Domain planning | Ownership, invariants, flows, and sequence |
| V2-INV-01 | `INV-01` | Inventory initialization, adjustment ledger, seller inventory UI; plan complete |
| V2-CART-01 | `CRT-01` through `CRT-03` | Redis cart APIs and buyer cart UI; complete and browser verified |
| V2-CART-02 | `CRT-04` | Product, seller/store, price, currency, and inventory revalidation with actionable warnings; complete and browser verified |
| V2-INV-02 | `INV-02` through `INV-04` | Reserve, expire, release, commit, and concurrency tests; complete with live lifecycle verification |
| V2-IAM-01 | `IAM-05 Manage addresses` | Buyer address book and internal checkout resolver; complete and browser verified |
| V2-CHK-01 | `CHK-01`, `CHK-02` | Checkout snapshots, totals, expiry, and reservation orchestration; implementation-ready plan complete, product decisions pending approval |
| V2-PAY-01 | `PAY-01`, `PAY-02` | Default-off provider-neutral intent, deterministic fake adapter, verified HMAC webhook, immutable payment events, order-owned checkout adapter, and bounded outbox dispatcher contract; PAY-01A/B/C/D and PAY/ORD boundary cleanup complete, transport and activation deferred |
| V2-ORD-01 | `ORD-01`, `PAY-03` | Payment-succeeded confirmation foundation and PAY/ORD boundary cleanup complete: default-off strict v1 handler, durable dedup/lease, inventory commit, immutable multi-business order and `order.confirmed`; live transport and recovery/reconciliation deferred |
| V2-ORD-02 | `ORD-02`, `ORD-03` | ORD-02A buyer reads, ORD-02B business queue/detail API, and ORD-02C default-off business portal UI/gateway boundary complete; mandatory ORD-02 cleanup is next |
| V2-ORD-03 | `ORD-04` | Cancellation, refund, and inventory compensation |
| V2-SHP-01 | `SHP-01` through `SHP-04` | Fulfillment and shipment lifecycle |
| V2-NOT-01 | V2 notifications | Event-driven buyer and business notifications |

Every implementation slice includes forward-only Flyway migrations when
needed, backend authorization, OpenAPI/client changes, the smallest complete
frontend workflow, unit and integration coverage, tenant-isolation tests, and
browser verification. Incomplete paths remain feature-flagged so buyers and
businesses cannot enter a checkout or fulfillment dead end.

### 13.1 Approved ORD-02 Continuation

Reference:
`docs/v2/commerce/v2-ord-02b-business-fulfillment-queue-plan.md`.

The current business lane is `3/3` after completed `V2-ORD-02A/B/C`:

1. `V2-ORD-02B` added default-disabled Auth/Order backend support for
   `ORDER_VIEW`-scoped business fulfillment queue/detail reads and
   `ORDER_FINANCE_VIEW`-filtered finance projections and advanced the lane to
   `2/3`.
2. `V2-ORD-02C` added the management-style business portal queue/detail UI
   over only the green 02B contract, with independent default-off Angular and
   gateway gates. It advanced the lane to `3/3`.
3. Mandatory business-only cleanup runs before any acceptance or shipment
   command and resets the lane to `0/3`.
4. `V2-SHP-01A/B/C` then implement group acceptance, partial shipments, and
   shipped/buyer aggregate visibility as separate slices.

Reference:
`docs/v2/commerce/v2-ord-02c-business-fulfillment-ui.md`.

Business groups remain the unit of seller authorization and fulfillment.
Buyer orders remain the buyer-facing aggregate. ORD-02 adds no fulfillment
mutation, payment activation, refund, inventory compensation, or aggregate
status derivation.

## 14. Contract Decisions Required Before Implementation

The following approved documents intentionally leave provider or policy
choices unresolved:

1. V2-PAY-01 uses the control-center-approved provisional local-development
   baseline: Stripe-Connect-shaped separate charges/transfers, US/USD/cards,
   platform merchant-of-record assumption, hosted onboarding, and automatic
   capture. The implementation remains fake-provider-only and default-off;
   production provider, legal, transfer, and payout approval is still required
   before activation.
2. Tax and shipping calculation adapters must be selected before V2-CHK-01.
   Discounts remain zero until a separate discount requirement exists.
3. Shipping can begin with manual carrier/tracking entry or a provider adapter;
   choose before V2-SHP-01.
4. `BUS-06` seller-defined policy versioning is V3, while V2 order snapshots
   require an applicable policy. The recommended V2 contract is a versioned
   platform-default shipping/cancellation/return policy snapshot; seller
   customization remains V3. This recommendation requires explicit approval
   before V2-CHK-01 schema work.
5. Checkout and reservation lifetime must be configuration, not a hard-coded
   domain constant. The initial duration is selected during V2-CHK-01.

## 15. V2-COM-00 Completion Criteria

- Commerce and individual trade boundaries are explicit.
- Inventory, order, payment, auth, product, and notification ownership is
  explicit.
- Cart, reservation, checkout, payment, order, and fulfillment states are
  defined.
- Failure recovery preserves zero oversell and exactly-once business effects.
- Authorization and tenant boundaries are defined.
- The implementation sequence is dependency-ordered.
- Unresolved provider and V3-policy dependencies are visible and do not become
  accidental implementation assumptions.
