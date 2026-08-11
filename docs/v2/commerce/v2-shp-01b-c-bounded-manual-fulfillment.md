# V2-SHP-01B/C Bounded Manual Fulfillment

Status: implemented and locally browser-verified on 2026-08-04; responsive and duplicate-action checks completed on 2026-08-10.

Depends on `V2-SHP-01A`, `V2-ORD-01B`, and `V2-ORD-02B/C`.

## Scope

This local-demo slice reuses the SQL-owned business fulfillment group and adds:

```text
PENDING_ACCEPTANCE -> ACCEPTED -> PROCESSING -> SHIPPED -> DELIVERED
```

`DELIVERED` is an explicit local-demo simulation, not carrier confirmation.
Each business group progresses independently. Parent order payment, inventory,
and sibling groups are unchanged.

The slice permits exactly one shipment per business group with manual carrier,
service, tracking number, and shipped time. It adds no item allocation, second
shipment, label, rate, carrier API, webhook, notification, cancellation,
refund, return, payout, review, or real-payment behavior.

## Commands

All commands require authentication, active membership in the path business,
`ORDER_FULFILL`, `If-Match`, and `Idempotency-Key`:

```text
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/accept
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/processing
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/shipments
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/delivery-demo
```

Only owners currently receive `ORDER_FULFILL`. Missing permission, membership,
group, and cross-business scope are hidden as `404 BUSINESS_ORDER_NOT_FOUND`.
Stale versions return `409 BUSINESS_ORDER_VERSION_CONFLICT`; invalid state
returns `409 BUSINESS_ORDER_STATE_CONFLICT`.

Shipment creation accepts exactly:

```json
{
  "carrierDisplayName": "Demo Carrier",
  "serviceDisplayName": "Ground",
  "trackingNumber": "DEMO-01K...",
  "shippedAt": "2026-08-03T15:00:00Z"
}
```

The seller UI labels the record `Local demo manual shipment`.

## Persistence and concurrency

Forward-only Order V8 expands the existing business-group check and immutable
history constraint. It creates:

- `business_order_fulfillment_commands`, with P7D retention and a unique
  actor/business/operation/key tuple;
- `shipments`, with unique `business_order_id` and business/carrier/tracking;
- `shipment_status_history`, with unique shipment version.

The group transition, history, shipment mutation when applicable, shipment
history, transactional outbox event, and completed command result share one
transaction. InnoDB concurrency victims are retried in a fresh transaction up
to three times. Same-key requests replay; distinct stale-version requests fail
with a bounded conflict. Expiry purging runs outside the mutation transaction
so it cannot widen the command's range locks.

## Read projections

Buyer and seller detail add group version, safe status timeline, and the single
shipment snapshot. Buyer detail can show sibling groups in different states.
Seller reads remain SQL-scoped to the path business and do not expose sibling
groups, membership internals, commands, history actor IDs, or outbox rows.

## Runtime and local identities

Backend and gateway properties remain false by default. The composed
`docker-compose.cart-runtime.yml` explicitly enables only acceptance,
processing, manual shipment, demo delivery, and the exact gateway command
paths. The `demo-checkout` Angular configuration pins the corresponding UI.

`tools/ensure-local-fulfillment-sellers.ps1` requires passwords through local
environment variables and provisions/resets two Keycloak users without
printing or storing those passwords:

```powershell
$env:KEYCLOAK_ADMIN_PASSWORD = '<local admin password>'
$env:LOCAL_DEMO_SHEN_PASSWORD = '<local Shen password>'
$env:LOCAL_DEMO_HARBOR_PASSWORD = '<local Harbor password>'
$env:COMMERCE_INTERNAL_SERVICE_TOKEN = '<local internal fixture token>'
.\tools\ensure-local-fulfillment-sellers.ps1
```

Browser usernames are `shen.ban2@mycnmipss.org` for Shen and
`harbor.seller@msb.local` for Harbor. The Auth local fixture assigns Harbor to
its own deterministic owner, preserves Shen's existing owner, and links both
to the Keycloak subjects persisted by the current local realm.
The fixture endpoint remains protected by the local-demo property and internal
service credential.

`shen.ban@mycnmipss.org` is a separate legacy local account that owns a
different business named `32`; it is not the Shen fulfillment-fixture login.

Always retain both Compose files when recreating this runtime. In particular,
a frontend-only rebuild must not reconcile its dependencies from the base demo
file, because the base gateway deliberately keeps V2 commerce disabled:

```powershell
# Full bounded commerce runtime reconciliation
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d

# Frontend-only rebuild while the bounded runtime is already running
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --no-deps --build frontend
```

After a full reconciliation, the gateway must report
`GATEWAY_FEATURE_CHECKOUT=true`, `GATEWAY_FEATURE_BUSINESS_ORDERS=true`, and
`ORDER_SERVICE_URL=http://order-service:8081`. Building `demo-checkout` UI
against the base gateway creates a split runtime: the navigation appears, but
buyer and seller order reads remain disabled.

## Verification

Automated coverage includes acceptance deadlock replay, stale conflicts,
processing state checks, one-shipment uniqueness, same-key concurrent shipment
creation, atomic group/shipment histories and outbox cardinality, independent
group state, Auth permission projection, bounded gateway routing/header
filtering, and buyer/seller Angular projections and actions.

The 2026-08-10 runtime recheck also covered split-composition recovery. It first
reproduced a `demo-checkout` frontend paired with the base gateway, where the
gateway had commerce flags disabled and targeted `order-service-disabled`.
Reapplying both Compose files restored the Order Service target and all bounded
commerce flags. Through the browser-facing gateway, the Shen fixture identity
then received successful buyer-history and seller-queue responses; the seller
queue contained five Shen-only fulfillment groups and buyer history contained
two confirmed orders. Focused gateway tests additionally verify that a
deliberately disabled buyer-order route returns a safe not-found response
instead of the generic unexpected-error envelope.

Fulfillment confirmations now use an accessible in-app dialog instead of
native `window.confirm`. The dialog names the exact current and target states,
keeps cancellation mutation-free, restores the prior status after cancellation,
and supports explicit cancel and Escape dismissal. Browser verification opened
the `Accepted -> Processing` dialog for Shen group
`01KZ3X4PH25T0Y5F2KJ0SKJXEK`, confirmed that no JavaScript dialog existed, and
dismissed it without changing version 1. The seller queue also recognizes a
404 while its build capability is enabled as a likely split runtime and shows
a visible `Commerce runtime check failed` diagnostic with the overlay recovery
direction. Tenant-scoped order-detail 404s retain the safe not-found behavior.

The rebuilt local runtime journey created confirmed order
`01KZ45SP4R9PWN3M75CM6MDN56` for one Shen item and one Harbor item. Shen group
`01KZ45SP4T1C9ZBFDZM8QQZVSC` progressed through versions 0-4. Harbor group
`01KZ45SP4WD8BNPAENBM4PFA2P` also progressed through
versions 0-4 and completed the optional `DELIVERED` simulation. Buyer detail
showed both independent timelines and safe shipment fields. Each seller queue
showed only its own business group; cross-business detail was unavailable. The
primary buyer could not access a seller mutation surface, and a secondary buyer
received order-not-found for the buyer detail.

The database audit found exactly one acceptance command per group, one
processing and one shipment command per group, one delivery command for Harbor,
one shipment per group, one history row per transition, and one outbox event per
transition. A second tab holding Shen version 3 received the safe version-conflict
message after the first tab recorded delivery at version 4. The browser console
contained no warnings or errors, the purchased cart badge was zero, and the
supported Harbor fixture restored available stock to 12. Shen available stock
is 4 after the verified purchase; it remains reduced because this slice does
not add or use an unsupported cross-service inventory reset.

The buyer and seller detail views were additionally measured at a 390 x 844
viewport. Both matched the 384-pixel document content width with no horizontally
overflowing descendants. This check exposed and fixed an intrinsic-width issue
in the seller detail caused by long order and tracking identifiers.

Duplicate browser actions were exercised against existing confirmed Shen group
`01KZ42G804MJC5VE8S7FSSKYP9` without creating another purchase. Two tabs raced
acceptance from version 0 and shipment creation from version 2. In each race,
one tab succeeded and the other displayed the safe version-conflict message.
The database audit found one acceptance command, one processing command, one
shipment command, one shipment, one history row per transition, and one outbox
event per transition. The group remains `SHIPPED` at version 3 as local evidence.
