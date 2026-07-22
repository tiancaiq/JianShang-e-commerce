# V2-ORD-02B Business Fulfillment Queue And Detail Plan

Status: complete and verified on 2026-07-20.

Release: V2.

Requirements: `ORD-03 View business orders`.

## 1. Purpose

Complete the seller-facing half of `V2-ORD-02` without starting fulfillment
mutations. Authorized business staff need a queue and detail view for only the
fulfillment groups owned by their business.

The buyer-facing half, `V2-ORD-02A`, is already complete. It exposes
default-disabled buyer order history and detail reads over immutable Order
Service snapshots. `V2-ORD-02B` reuses those snapshots but applies a separate
business-scoped authorization and response boundary.

This plan follows the marketplace aggregate pattern:

- one buyer order remains the buyer-facing aggregate;
- one `business_order` remains the seller-visible fulfillment group;
- a business actor never reads or changes another business's group;
- later acceptance, shipment, cancellation, and finance commands operate on
  the business group rather than the whole buyer order; and
- the buyer aggregate status is not derived from group state until a later
  fulfillment slice defines the complete mapping.

## 2. Current Progress And Entry State

The following prerequisites are complete:

- `V2-CHK-01` immutable checkout item, address, totals, and policy snapshots;
- `V2-PAY-01A` through `V2-PAY-01D` default-off payment intent, verified fake
  webhook, checkout adapter, and outbox dispatcher contracts;
- `V2-ORD-01A` payment-succeeded confirmation, one buyer order per checkout,
  one business group per business, immutable order snapshots, history, and
  `order.confirmed` outbox;
- `V2-PAY-ORD-CLEAN-P0-01` payment/order boundary cleanup;
- `V2-ORD-02A` default-off buyer order list/detail APIs; and
- the V2 authorization plan naming `ORDER_VIEW`, `ORDER_FULFILL`, and
  `ORDER_FINANCE_VIEW`.

The business lane is now at `2/3`: `V2-ORD-02A` was the first slice after the
last business cleanup and completed `V2-ORD-02B` is the second.

## 3. Slice Boundaries

### In scope

- Auth Service membership response support for the already planned
  `ORDER_VIEW` and `ORDER_FINANCE_VIEW` permissions;
- Order Service business-group list and detail reads;
- stable cursor pagination and status filtering;
- business and permission isolation;
- buyer shipping-address disclosure limited to a paid group the business is
  authorized to fulfill;
- permission-filtered fee or finance projections that already exist;
- standard errors, correlation handling, safe logs, and low-cardinality
  metrics;
- focused Auth/Order tests and real-schema integration tests; and
- exact ORD-02/API/database/roadmap documentation updates.

### Out of scope

- accepting, rejecting, cancelling, or shipping an order;
- creating or updating a shipment;
- refund, payout, transfer, commission settlement, or money movement;
- exposing provider, payment-intent, event, lease, payload-hash, outbox, or
  reconciliation internals;
- buyer order API changes;
- aggregate order-status derivation;
- gateway, Angular, browser, or runtime activation;
- live Kafka, payment provider, shipping provider, or external network work;
- business staff invitation/management; and
- individual-listing trade behavior.

## 4. Authorization Contract

Every request derives the actor from the authenticated session and checks:

1. the feature is enabled;
2. the actor has an active membership in the path `businessId`;
3. the business itself is active;
4. the membership carries `ORDER_VIEW`; and
5. the selected `business_order.business_id` equals the path business.

`ORDER_FINANCE_VIEW` independently controls fee/refund projection fields.
Until V3 staff management exists, `OWNER` receives `ORDER_VIEW` and
`ORDER_FINANCE_VIEW`; `MANAGER` receives `ORDER_VIEW` only. Neither role gains
`ORDER_FULFILL` in this read-only slice. Auth Service owns that membership
fact; Order Service still enforces business scope on every SQL query.

Missing membership, missing permission, a missing group, and a group belonging
to another business all return the same `404 BUSINESS_ORDER_NOT_FOUND`
contract and must not reveal cross-business existence.

Browser-supplied actor, role, permission, buyer, store, or replacement
business identifiers are never authoritative.

## 5. Planned API

The existing route family remains:

```text
GET /api/v1/businesses/{businessId}/orders?status=&cursor=&limit=
GET /api/v1/businesses/{businessId}/orders/{businessOrderId}
```

Both routes default disabled through `order.business-views.enabled=false`.
The gate is evaluated before validation, membership, or repository access.
Disabled requests make zero Auth/Order downstream reads and return
`404 BUSINESS_ORDERS_NOT_AVAILABLE`.

### 5.1 Queue

The queue:

- accepts an optional exact stored group status;
- accepts `limit=1..50`, default `20`;
- accepts an opaque, versioned cursor of at most 512 characters;
- orders by `(created_at DESC, id DESC)`;
- binds both values in the cursor so newer inserts do not shift traversal; and
- rejects malformed status, cursor, or limit before executing the query.

Initial status filters are the stored business-group states:

```text
PENDING_ACCEPTANCE
ACCEPTED
PARTIALLY_SHIPPED
SHIPPED
DELIVERED
CANCELLATION_PENDING
CANCELLED
```

The queue returns only fields already persisted and needed to choose work:

- business order ID and seller-visible number;
- owning business/store IDs;
- stored group status and cancellation status;
- buyer order ID and buyer order number;
- item count and total quantity;
- subtotal/total and currency;
- confirmed/created/updated timestamps; and
- finance projection only when present and permitted.

It does not return item details, buyer contact information, full address,
provider data, or another business's group.

### 5.2 Detail

The detail returns the same header plus:

- immutable item snapshots for this group only;
- unit price, quantity, line total, currency, and policy version;
- the minimum immutable shipping-address fields needed for fulfillment;
- stored payment status as a bounded fact such as `SUCCEEDED`;
- current shipment summaries only after `V2-SHP-01` creates authoritative
  shipment persistence; and
- permitted finance projections only with `ORDER_FINANCE_VIEW`.

The detail never returns billing address unless a later approved fulfillment
or legal requirement needs it. It never returns payment provider identifiers,
buyer identity-provider data, buyer email, moderation information, internal
history rows, event payloads, storage metadata, or data from sibling business
groups.

No aggregate version is invented. These routes remain read-only until later
state-changing slices add explicit group versions and `If-Match` contracts.

## 6. Persistence And Query Direction

Order Service reads its own:

- `orders`;
- `business_orders`;
- `order_items`; and
- the immutable shipping `order_addresses` row.

The primary queue predicate is business ID plus optional fulfillment status,
ordered by creation time and ID. MySQL 8.4 `EXPLAIN` demonstrated that the V3
index serves the exact-status queue but the unfiltered queue otherwise uses a
filesort. Forward-only Order Service V4 therefore adds:

```text
idx_business_order_all_queue (business_id, created_at, id)
```

The repository selects V3 `idx_business_order_queue` for filtered reads and V4
for unfiltered reads. Order V1 through V3 remain unchanged. Order Service does
not query Auth, Product, Payment, or Inventory databases.

## 7. Required Verification

### Authorization and privacy

- owner/manager with `ORDER_VIEW` can list and read their group;
- inactive membership, inactive business, and missing permission are denied;
- a member of business A cannot infer or read business B's group;
- changing path business ID cannot widen access;
- finance fields require `ORDER_FINANCE_VIEW`; and
- disabled routes make zero membership/repository calls.

### Query behavior

- empty queue;
- exact status filtering;
- first, middle, and final pages;
- malformed, overlong, and unsupported-version cursors;
- invalid limit and invalid status;
- stable traversal while a newer group is inserted;
- deterministic ordering for equal timestamps; and
- one query never returns sibling groups from another business.

### Snapshot behavior

- multi-business buyer order produces one isolated detail per business;
- each detail contains only its own items and totals;
- address is the immutable order snapshot, not the mutable address book;
- policy and price snapshots remain unchanged after source edits; and
- hidden internal/provider/event fields cannot serialize.

### Build and operations

- focused Auth and Order tests;
- real MySQL migration/query integration tests;
- full Order Service test suite and package;
- Auth Service focused membership tests and package when its permission mapping
  changes;
- migration hash verification;
- credential, unsafe-log, and hidden-field scans; and
- cleanup of only disposable test resources.

## 8. Completion And Successor

`V2-ORD-02B` completed with the API and authorization tests green and every
commerce/runtime flag still false. It advances the business lane from `1/3`
to `2/3`.

`V2-ORD-02C Business Fulfillment Queue And Detail UI` completed on
2026-07-20. It exposes only the green 02B read contract in the
management-style business portal behind independent default-off Angular and
gateway gates. It adds no fulfillment mutations. See
`docs/v2/commerce/v2-ord-02c-business-fulfillment-ui.md`.

`V2-ORD-02C` advanced the business lane to `3/3`.
`V2-ORD-CLEAN-P0-01` completed on 2026-07-20 and reset it to `0/3` after
re-auditing read isolation, finance omission, cursor bounds, dependency-error
mapping, default-off UI/gateway behavior, and the V3/V4 query indexes. The lane
is paused before `V2-SHP-01A`.

## 9. Deferred Fulfillment Sequence

After the ORD-02 cleanup:

1. `V2-SHP-01A` accepts a paid `PENDING_ACCEPTANCE` business group through an
   idempotent, versioned, audited command.
2. `V2-SHP-01B` creates partial shipment records with bounded carrier,
   tracking, and item quantities.
3. `V2-SHP-01C` marks shipments shipped, exposes buyer shipment snapshots, and
   introduces the approved aggregate-status derivation.
4. Mandatory shipping cleanup runs at the three-slice checkpoint.
5. `V2-ORD-03A/B/C` adds cancellation eligibility, refund orchestration, and
   inventory compensation as separate idempotent states.
6. `V2-PAY-02A` adds payment/order reconciliation and the admin operations
   queue before any real provider activation.
7. `V2-NOT-01` consumes stable order/shipment events; notification failure
   never rolls back a paid order.

## 10. Planning References

The plan adopts established marketplace workflow patterns without copying
their technology stacks:

- Mercur completes a multi-seller cart as one order set with separate seller
  orders, seller-specific shipping validation, payment authorization,
  inventory reservation, links, and events:
  <https://github.com/mercurjs/mercur/blob/ac6f08d0d0336566e92277b656098a6130ef3afc/apps/docs/v1/docs/product/workflows/core/cart-workflows.mdx>.
- Vendure assigns order lines to a seller before splitting an aggregate order
  into seller orders and synchronizing aggregate/seller state:
  <https://docs.vendure.io/current/core/how-to/multi-vendor-marketplaces>.
- Saleor keeps checkout completion behind address/payment validation and
  separates asynchronous order/payment events:
  <https://github.com/saleor/saleor/blob/5656e26e190fd42a022145591e01edec264ae398/saleor/graphql/checkout/mutations/checkout_complete.py>.
- Sharetribe expresses marketplace actions, actors, timeouts, compensation,
  payout, dispute, and review behavior as explicit transitions:
  <https://github.com/sharetribe/example-processes/blob/master/default-purchase/process.edn>.

These references support the aggregate/group and transition-first direction.
They do not change this repository's Java 21, Spring Boot, MySQL, service
ownership, or individual-trade invariants.
