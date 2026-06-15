# Product API Contract

## 0. Release Boundary

MVP contracts cover identity/accounts, seller/store profiles, listings/media,
search/storefront, basic text chat, and basic business/listing moderation.

V2 contracts cover cart, inventory, checkout/payment, orders/shipping, and
notifications.

V3 contracts cover individual trade completion/reputation, reviews, advanced
admin/trust operations, and AI.

During Phase 1 setup, no feature endpoint in this document should be
implemented.

## 1. Conventions

Base path:

```text
/api/v1
```

Content type:

```text
application/json
```

Authentication:

```text
Authorization: Bearer <access-token>
```

Required headers where applicable:

```text
X-Correlation-Id: optional client value; generated when absent
Idempotency-Key: required for payment, reservation, checkout, order, and retryable commands
If-Match: aggregate version for protected updates
```

Response IDs are opaque strings. Clients must not infer type or sequence.

Money:

```json
{"amount": "19.99", "currency": "USD"}
```

Timestamps use ISO 8601 UTC.

The standard money, pagination, error, correlation, authenticated-principal,
and event-envelope types should be supplied by the corresponding shared
backend/frontend libraries. Shared types define transport conventions only;
they do not expose service-owned domain entities.

## 2. Standard Envelopes

Single resource:

```json
{"data": {}}
```

Collection:

```json
{
  "data": [],
  "page": {"nextCursor": "opaque-or-null", "hasMore": false}
}
```

Error:

```json
{
  "error": {
    "code": "LISTING_NOT_AVAILABLE",
    "message": "The listing is no longer available.",
    "fieldErrors": [{"field": "quantity", "code": "OUT_OF_RANGE"}],
    "correlationId": "id"
  }
}
```

Expected status codes:

- `200` read/update success
- `201` create success
- `202` asynchronous operation accepted
- `204` delete/no body
- `400` malformed or invalid request
- `401` unauthenticated
- `403` unauthorized for resource
- `404` resource not found or intentionally hidden
- `409` state/version/idempotency conflict
- `422` valid syntax but business rule failure
- `429` rate limited
- `503` temporary dependency failure

## 3. Identity and Profile

### `POST /auth/register` (`IAM-01`)

Request:

```json
{"email": "user@example.com", "password": "secret", "displayName": "Alex"}
```

Response `201`: user ID, status, verification required.

### `POST /auth/verify-email` (`IAM-01`)

Request: `{"token": "single-use-token"}`.

### `POST /auth/login` (`IAM-02`)

Request: email and password.

Response: access token metadata and safe user summary. Refresh token delivery
must follow the selected auth design, preferably secure HTTP-only cookie.

### `POST /auth/refresh`, `POST /auth/logout` (`IAM-02`)

Refresh rotates the session. Logout is idempotent.

### `POST /auth/password-recovery`, `POST /auth/password-reset` (`IAM-03`)

Recovery always returns `202`.

### `GET /users/me`, `PATCH /users/me` (`IAM-04`)

Patch supports display name, phone, and avatar only.

### Address APIs (`IAM-05`)

```text
GET    /users/me/addresses
POST   /users/me/addresses
PATCH  /users/me/addresses/{addressId}
DELETE /users/me/addresses/{addressId}
POST   /users/me/addresses/{addressId}/default
```

## 4. Individual Seller

### `POST /individual-seller/activation` (`IND-01`)

Request:

```json
{
  "publicCity": "Irvine",
  "publicRegion": "CA",
  "termsVersion": "2026-01"
}
```

Response `201`: active individual seller profile.

### `GET /individual-seller/me`

Returns profile and reputation summary.

## 5. Business and Store

### Business application APIs

```text
POST  /business-applications                    BUS-01
GET   /business-applications/{id}
PATCH /business-applications/{id}               BUS-01
POST  /business-applications/{id}/submit        BUS-02
POST  /webhooks/business-verification           BUS-03
```

Submit requires expected application version.

### Admin decision (`BUS-04`)

```text
POST /admin/business-applications/{id}/decision
```

Request:

```json
{"decision": "APPROVE|REJECT|REQUEST_INFORMATION", "reason": "text"}
```

### Store APIs (`BUS-05`, `BUS-06`)

```text
GET   /businesses/{businessId}/store
PATCH /businesses/{businessId}/store
GET   /businesses/{businessId}/policies
POST  /businesses/{businessId}/policies
GET   /stores/{slug}
```

### Staff APIs (`BUS-07`)

```text
GET    /businesses/{businessId}/members
POST   /businesses/{businessId}/invitations
POST   /business-invitations/{token}/accept
PATCH  /businesses/{businessId}/members/{userId}
DELETE /businesses/{businessId}/members/{userId}
```

## 6. Categories, Media, and Listings

### Categories (`LST-01`)

```text
GET /categories
GET /categories/{categoryId}
GET /categories/{categoryId}/attributes
```

### Media (`LST-02`, `LST-03`)

```text
POST /media/upload-requests
POST /media/{mediaId}/confirm
GET  /media/{mediaId}/status
```

Upload request contains file name, content type, size, checksum, and owner
context. Response contains signed URL and required headers.

### Listing create (`LST-04`, `LST-05`)

```text
POST /listings/individual
POST /businesses/{businessId}/listings
```

Individual request:

```json
{
  "categoryId": "id",
  "title": "Used bicycle",
  "description": "Description",
  "condition": "GOOD",
  "conditionNotes": "Small scratch",
  "price": {"amount": "250.00", "currency": "USD"},
  "negotiable": true,
  "location": {"city": "Irvine", "region": "CA"},
  "paymentPreferences": ["CASH_ON_MEETING"],
  "deliveryPreferences": ["PUBLIC_MEETING"],
  "attributes": {}
}
```

Business request adds SKU, quantity, shipping policy version, and return policy
version; it omits negotiation and meeting fields.

### Listing management (`LST-06` through `LST-10`)

```text
GET    /listings/{listingId}
PATCH  /listings/{listingId}
PUT    /listings/{listingId}/images
POST   /listings/{listingId}/submit
POST   /listings/{listingId}/pause
POST   /listings/{listingId}/relist
POST   /listings/{listingId}/close
GET    /users/me/listings
GET    /businesses/{businessId}/listings
```

Patch and state commands require `If-Match`.

### Listing moderation (`LST-09`)

```text
GET  /admin/moderation/listings
POST /admin/moderation/listings/{caseId}/claim
POST /admin/moderation/listings/{caseId}/decision
```

### Search (`SRC-02`, `SRC-03`)

```text
GET /search/listings?q=&categoryId=&sellerType=&condition=&minPrice=&maxPrice=&city=&region=&cursor=&limit=
GET /stores/{slug}/listings?cursor=&limit=
```

Maximum `limit` is server-controlled. Search result includes seller type and
checkout/off-platform disclosure.

## 7. Chat

### Conversation APIs (`CHT-01`, `CHT-02`)

```text
POST /listings/{listingId}/conversations
GET  /conversations
GET  /conversations/{conversationId}
GET  /conversations/{conversationId}/messages?cursor=&limit=
POST /conversations/{conversationId}/messages
POST /conversations/{conversationId}/read
```

Message request:

```json
{"type": "TEXT", "body": "Is this still available?"}
```

Realtime endpoint may deliver message events, but HTTP remains the authoritative
command and history interface.

### Safety (`CHT-03`)

```text
POST   /users/{userId}/block
DELETE /users/{userId}/block
POST   /reports
```

Report request identifies subject type/ID, reason, and permitted evidence IDs.

## 8. Individual Trades (V3)

Buyer and seller negotiate only through free-text chat. There are no structured
offer, counteroffer, or offer-acceptance endpoints in MVP.

### Trades (`TRD-01` through `TRD-03`)

```text
POST /conversations/{conversationId}/trade
GET  /trades
GET  /trades/{tradeId}
POST /trades/{tradeId}/cancel
POST /trades/{tradeId}/completion-requests
POST /trades/{tradeId}/buyer-confirmation
POST /trades/{tradeId}/report
```

#### Seller creates trade from conversation

`POST /conversations/{conversationId}/trade`

Authorization: authenticated listing owner.

Request:

```json
{"dealNote": "Optional private note"}
```

The request does not accept buyer ID, email, phone, price, or address. The
service derives the buyer and listing from the conversation. It creates one
trade and reserves the listing atomically. A concurrent request for another
conversation returns `409 LISTING_ALREADY_RESERVED`.

Every response contains:

```json
{
  "paymentHandledByPlatform": false,
  "safetyNotice": "Payment and delivery are arranged directly by participants."
}
```

No endpoint records bank/card credentials or claims external payment success.

#### Seller initiates completion

`POST /trades/{tradeId}/completion-requests`

Authorization: authenticated seller recorded on the trade.

Request:

```json
{"channel": "EMAIL"}
```

The request does not accept buyer ID, email, phone, or address. The service
derives the buyer from the trade and verifies that the selected channel is
available and verified.

Response:

```json
{
  "data": {
    "tradeId": "id",
    "sellerConfirmedAt": "timestamp",
    "buyerConfirmationStatus": "PENDING",
    "deliveryChannel": "EMAIL",
    "maskedDestination": "j***@mail.com",
    "expiresAt": "timestamp"
  }
}
```

Repeated requests are idempotent within a short window and rate-limited.
Creating a new challenge invalidates prior unused challenges.

#### Buyer confirms completion

`POST /trades/{tradeId}/buyer-confirmation`

Authorization: authenticated buyer recorded on the trade.

Request:

```json
{"challenge": "single-use-link-token-or-code"}
```

The service validates the challenge hash, expiry, current request, authenticated
buyer, and trade state. Success transitions the trade to `COMPLETED`, marks the
listing `SOLD`, and increments public seller completed-sales count exactly once.

Example completed response:

```json
{
  "data": {
    "tradeId": "id",
    "status": "COMPLETED",
    "completedAt": "timestamp",
    "seller": {
      "userId": "id",
      "displayName": "Seller name",
      "completedSalesCount": 12
    }
  }
}
```

The confirmation link may open the marketplace confirmation page, but the
buyer must sign in before the API accepts it.

## 9. Cart and Inventory (V2)

### Cart (`CRT-01` through `CRT-04`)

```text
GET    /cart
POST   /cart/items
PATCH  /cart/items/{listingId}
DELETE /cart/items/{listingId}
DELETE /cart
POST   /cart/validate
```

Add request: `{"listingId": "id", "quantity": 1}`.

Cart validation response returns current item status, authoritative price,
availability, and warnings.

### Business inventory (`INV-01`)

```text
GET  /businesses/{businessId}/inventory?cursor=&limit=
GET  /businesses/{businessId}/inventory/{listingId}
POST /businesses/{businessId}/inventory/{listingId}/adjustments
GET  /businesses/{businessId}/inventory/{listingId}/movements
```

Adjustment request contains signed quantity delta, reason, and expected
version.

### Internal reservation APIs (`INV-02` through `INV-04`)

```text
POST /internal/inventory/reservations
POST /internal/inventory/reservations/{id}/commit
POST /internal/inventory/reservations/{id}/release
GET  /internal/inventory/reservations/{id}
```

Service authentication and `Idempotency-Key` are mandatory.

## 10. Checkout, Payment, and Orders (V2)

### Checkout (`CHK-01`, `CHK-02`)

```text
POST /checkouts
GET  /checkouts/{checkoutId}
POST /checkouts/{checkoutId}/cancel
```

Create request references cart and address ID. Response contains authoritative
snapshots, totals, expiry, and reservation state.

### Payment (`PAY-01`, `PAY-02`)

```text
POST /checkouts/{checkoutId}/payment-intent
POST /webhooks/payments
GET  /payments/{paymentId}
```

Payment-intent command requires `Idempotency-Key`. Response exposes only the
provider client data intended for the browser.

### Buyer orders (`ORD-02`, `ORD-04`)

```text
GET  /orders?cursor=&limit=
GET  /orders/{orderId}
POST /orders/{orderId}/cancellation-requests
```

### Business orders (`ORD-03`)

```text
GET /businesses/{businessId}/orders?status=&cursor=&limit=
GET /businesses/{businessId}/orders/{businessOrderId}
```

### Admin payment/order operations (`PAY-03`, `ADM-06`)

```text
GET  /admin/operations/payment-order-mismatches
POST /admin/operations/payment-order-mismatches/{id}/retry
```

Retry requires `FINANCE_ADMIN` or a narrower configured permission.

## 11. Fulfillment and Shipping (V2)

```text
POST /businesses/{businessId}/orders/{businessOrderId}/accept
POST /businesses/{businessId}/orders/{businessOrderId}/shipments
POST /businesses/{businessId}/shipments/{shipmentId}/mark-shipped
GET  /orders/{orderId}/shipments
POST /webhooks/shipping
```

Shipment create request includes carrier, tracking number, and item quantities.
State-changing commands require idempotency.

## 12. Reviews (V3)

```text
POST  /order-items/{orderItemId}/reviews
POST  /trades/{tradeId}/reviews
GET   /listings/{listingId}/reviews?cursor=&limit=
GET   /stores/{storeId}/reviews?cursor=&limit=
GET   /users/{userId}/individual-reputation
PATCH /reviews/{reviewId}
POST  /reviews/{reviewId}/report
```

The server derives reviewer and eligibility from authentication and the
referenced transaction.

Individual reputation response includes:

```json
{
  "data": {
    "userId": "id",
    "completedSalesCount": 12,
    "ratingCount": 10,
    "ratingAverage": "4.80"
  }
}
```

## 13. Notifications (V2)

```text
GET   /notifications?cursor=&limit=
POST  /notifications/{notificationId}/read
POST  /notifications/read-all
GET   /notification-preferences
PATCH /notification-preferences
```

## 14. Administration and Support

MVP includes only business application and listing moderation endpoints.
User/business suspensions, reports, support cases, and operations queues are
V3.

```text
GET  /admin/users
GET  /admin/users/{userId}
POST /admin/users/{userId}/suspensions
POST /admin/users/{userId}/restore

GET  /admin/businesses
GET  /admin/businesses/{businessId}
POST /admin/businesses/{businessId}/suspensions
POST /admin/businesses/{businessId}/restore

GET  /admin/reports
POST /admin/reports/{reportId}/claim
POST /admin/reports/{reportId}/resolve

GET  /admin/support-cases
POST /admin/support-cases/{caseId}/notes
POST /admin/support-cases/{caseId}/responses

GET  /admin/audit-logs
```

Admin list endpoints require bounded filters and cursor pagination.

## 15. Agent APIs and Tools (V3)

User-facing agent endpoint:

```text
POST /agent/sessions
POST /agent/sessions/{sessionId}/messages
GET  /agent/sessions/{sessionId}
```

MVP tool allowlist:

```text
searchListings
getListing
compareListings
getMyOrder
draftTradeMessage
draftListingContent
summarizeAssignedSupportCase
```

Tool rules:

- Each tool executes with the requesting actor's permissions.
- `getMyOrder` cannot accept an arbitrary user ID.
- Support summary requires case assignment or explicit access.
- Draft tools return proposals and never persist automatically.
- Every tool call records session, actor, arguments hash, result status,
  latency, token usage, and correlation ID.

## 16. Event Contract

Envelope:

```json
{
  "eventId": "id",
  "eventType": "listing.activated",
  "eventVersion": 1,
  "occurredAt": "2026-06-13T12:00:00Z",
  "producer": "marketplace-service",
  "aggregateType": "listing",
  "aggregateId": "id",
  "correlationId": "id",
  "payload": {}
}
```

Initial events:

```text
business.application.submitted
business.approved
business.rejected
listing.submitted
listing.activated
listing.updated
listing.deactivated
message.created
trade.created
trade.cancelled
trade.completed
inventory.reservation.expired
payment.succeeded
payment.failed
order.confirmed
order.cancelled
shipment.shipped
shipment.delivered
review.published
review.removed
```

Compatibility:

- Existing fields are not reinterpreted.
- Additive optional fields may remain in the same event version.
- Removing, renaming, or changing meaning requires a new version.
- Consumers ignore unknown fields and deduplicate by event ID.
