# V2-NOT-01D Event-Driven Commerce In-App Notifications

Status: implemented and verified in the bounded local commerce runtime.

## Boundary

This slice extends the existing Notification Service instead of creating a
parallel notification path. It projects existing Order-owned transactional
outbox events into persistent buyer and business-scoped in-app notifications:

| Source event | Buyer notification | Seller notification |
|---|---|---|
| `order.confirmed` | `BUYER_ORDER_CONFIRMED` | `SELLER_NEW_ORDER` per business group |
| `business_order.accepted` | `BUYER_ORDER_ACCEPTED` | - |
| `business_order.processing_started` | `BUYER_ORDER_PROCESSING` | - |
| `business_order.shipped` | `BUYER_ORDER_SHIPPED` | - |
| `business_order.delivered_demo` | `BUYER_ORDER_DELIVERED` | - |
| `order.cancellation_completed` | `BUYER_ORDER_CANCELLED` and `BUYER_REFUND_COMPLETED` | `SELLER_ORDER_CANCELLED` per business group |

The cancellation-completed event is already emitted only after the bounded
inventory and demo-refund compensations succeed. This slice does not change
Order, Payment, Inventory, cancellation, or fulfillment state machines.

## Delivery and reliability

Order Service owns a retryable outbox delivery cursor on its existing
`order_outbox_events` rows. A default-off scheduled adapter enriches supported
events from Order-owned buyer/business-group data and posts a minimal internal
event to Notification Service. The commerce transaction commits before this
delivery occurs; an unavailable Notification Service schedules another
attempt and cannot roll back an order transition.

Notification Service atomically records the source outcome and recipient
projections. Durable uniqueness on
`(recipient_scope_type, recipient_scope_id, source_event_id, type)` makes
replay and concurrent duplicate delivery user-visible exactly once. The same
source may independently create buyer and multiple seller projections. A
same-ID/different-payload replay conflicts instead of silently mutating data.

The internal HTTP adapter is the approved bounded local-demo transport. Kafka,
WebSockets, email, SMS, push, preferences, provider delivery records, purge,
and marketing notifications remain disabled.

## Read and authorization contract

Buyer routes resolve the active application user from the bearer token and
query only `USER` scope. Seller routes resolve current business membership on
every call and require `ORDER_VIEW` or `ORDER_FULFILL`; they query only the
requested `BUSINESS` scope. Missing, cross-user, and cross-business
notification IDs return safe not-found behavior. Notification deep links use
the existing buyer or seller order routes and therefore do not bypass Order
Service authorization.

```text
GET  /api/v1/notifications
GET  /api/v1/notifications/unread-count
POST /api/v1/notifications/{notificationId}/read
POST /api/v1/notifications/read-all

GET  /api/v1/businesses/{businessId}/notifications
GET  /api/v1/businesses/{businessId}/notifications/unread-count
POST /api/v1/businesses/{businessId}/notifications/{notificationId}/read
POST /api/v1/businesses/{businessId}/notifications/read-all
```

Responses contain only notification identity/type, allowlisted message key and
bounded presentation arguments, safe order-detail route, read state, and
timestamps. They never expose addresses, provider details, source envelopes,
service tokens, membership metadata, or authorization decisions.

## UI and runtime

The buyer marketplace header and seller portal header poll their respective
server-authoritative unread-count endpoint every 15 seconds while authenticated.
Each notification center renders newest first with read/unread distinction,
timestamp, bounded copy, safe deep link, mark-one, mark-all, loading, empty,
and error states. Refresh reloads persisted state. The commerce Compose overlay
enables only the internal ingest, read APIs, gateway routes, frontend surfaces,
and Notification-owned Flyway schema. Runtime preflight asserts those flags and
service URLs so the base Compose file cannot silently disable the slice.

## Verification

Required gates are persistence/deduplication and recipient-isolation MySQL
tests; Order outbox mapping/retry tests; buyer/business API authorization tests;
gateway relay/CSRF/default-off tests; focused Angular parser, center, layout,
badge and route tests; commerce build/regressions; Compose preflight; and fresh
browser purchase, fulfillment, cancellation, isolation, refresh, deep-link,
responsive-layout, and console checks.

On 2026-08-10 the bounded runtime verification completed with fresh buyer
orders covering confirmation, seller acceptance, processing, shipment, demo
delivery, whole-order cancellation, demo refund, and the corresponding seller
notifications. Persisted read and mark-all transitions, buyer and business
isolation, authorized deep links, an actual outbox replay, the 390 x 844 mobile
layout, cart badge accuracy, and a clean browser console were also verified.
