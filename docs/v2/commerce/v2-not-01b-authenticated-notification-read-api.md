# V2-NOT-01B Authenticated Notification Read API

Status: source-complete; disposable MySQL verification required for green.

## Boundary

This slice exposes only default-off buyer notification reads:

- `GET /api/v1/notifications?cursor=&limit=`;
- `POST /api/v1/notifications/{notificationId}/read`;
- `POST /api/v1/notifications/read-all`.

There is no gateway route, frontend, runtime activation, transport, email,
preference, business/admin notification, or purge behavior.

## Identity And Authorization

`notifications.read-api.enabled=false` fails with a stable hidden 404 before
any other work. When enabled, Notification Service validates a bounded bearer
header and relays only that original header and the bounded correlation ID to
Auth Service `GET /api/v1/users/me`. The strict response projection is internal
user ID plus status. Only canonical IDs with `ACTIVE` status proceed.

Missing, malformed, or rejected authentication returns 401. `SUSPENDED` and
`CLOSED` return 403. Auth transport, timeout, 5xx, unexpected status, or
malformed response returns 503 without repository work. Client actor/user
headers and values are ignored.

## Read Contract

List is recipient-scoped in SQL and ordered by `(created_at DESC,id DESC)`.
The opaque version-1 cursor contains both sort values. Limit defaults to 20 and
is bounded from 1 through 50. The projection contains only notification ID,
stable type/message key, validated `{orderId}` presentation arguments,
allowlisted route, read/readAt, and createdAt. Stored projection corruption
fails closed rather than returning raw JSON or internal fields.

Mark-one uses recipient ownership in SQL. Missing and cross-recipient rows are
identical 404 responses. The first read timestamp is preserved on replay and
concurrent retries. Mark-all updates only the resolved recipient's unread rows
and returns no count. Both commands accept no body and return 204.

## Persistence

The existing Notification Flyway V1 indexes support these queries and commands;
NOT-01B adds no migration and does not modify V1. Source event, consumer, hash,
recipient, raw JSON, payment, provider, and address fields never cross the API.

## Verification

Offline coverage includes feature-off zero-work behavior, bearer and Auth
mapping, correlation relay, spoofed-header rejection, cursor/limit/ID/body
validation, stable projection, corrupt arguments, read replay, read-all, and
cross-user hiding. The compiled disposable MySQL suite covers tied pagination,
concurrent inserts, ownership, first-timestamp concurrency, read-all isolation,
and rollback. The business lane remains `0/3` until that MySQL suite and the
NOT-01A MySQL suite execute green on an available runner.
