# V2-NOT-01A Order-Confirmed In-App Notification

Status: implemented locally; disposable MySQL verification required for green.

## Scope

This slice creates only durable buyer `ORDER_CONFIRMED` in-app notifications.
It excludes authenticated read APIs, Auth identity resolution, gateway and UI,
email, preferences, Kafka, business/admin notifications, shipping, tracking,
cancellation, refund, inventory, payment mutations, purge, and runtime
activation.

## Order Producer

Order confirmation continues to insert its exact version-1
`order.confirmed` outbox row. In the same local transaction it inserts a
version-2 row whose payload adds only opaque `recipientUserId`. The recipient
is re-read from persisted `orders.buyer_id`; it is never accepted from a
client, payment event, address, or external identity claim.

Version-2 aggregate ID, payload order ID, and future partition key are the same
order ULID. The payload retains bounded checkout, payment-intent, sorted
business, status, and confirmation-time fields from v1 only for event
compatibility. Correlation and payment-event causation remain outbox metadata.
No email, address, name, provider information, title/body, or route is added.

## Notification Consumer

`notifications.order-confirmed.enabled=false` is checked before parsing,
transaction creation, metrics, or repository work. There is no listener or
transport adapter. Tests call the application consumer directly with a strict
transport-neutral envelope.

The consumer:

1. bounds JSON to 16 KiB and validates the exact envelope;
2. requires producer `order-service`, aggregate `ORDER`, and matching
   aggregate/partition identity;
3. canonicalizes and SHA-256 hashes the complete envelope and payload;
4. durably deduplicates `(consumerName,eventId)`;
5. validates exact `order.confirmed` version-2 payload identity and bounds;
6. atomically writes the source result and one notification projection.

Same-ID/same-hash is replay. Same-ID/different-hash is conflict. Unsupported
type/version is a durable terminal rejection. A supported event with a valid
identity but malformed payload is durable poison. Input without trustworthy
identity is nonretryable poison without persistence. Database failure rolls
back and returns retry-required.

## Persistence

Notification Flyway V1 owns:

- `notification_source_events` with durable source hash/outcome metadata;
- `notifications` with opaque recipient, `ORDER_CONFIRMED`,
  `ORDER_CONFIRMED_V1`, bounded `{orderId}`, `/account`, read/version/time
  fields, and source references;
- unique `(recipient_user_id,source_event_id,type)` and cursor/unread indexes.

There are no cross-service foreign keys or stored source bodies. P180D is
local/test retention metadata only. No delete or purge worker exists, and
configuration rejects purge activation.

## Verification

Required coverage:

- unchanged version-1 and authoritative recipient-bearing version-2 Order
  outbox rows;
- strict envelope, payload bounds, privacy, and identity agreement;
- Flyway/MySQL schema, deduplication, replay/hash conflict, concurrent single
  creation, rollback/retry, restart replay, unsupported/poison behavior;
- default-off zero parser/repository/transaction/metric work;
- reactor, package, migration, credential, PII/log, and whitespace checks.

`V2-NOT-01B` may separately approve a general Auth subject-to-active-user-ID
resolver and authenticated read API. It is not part of this slice.
