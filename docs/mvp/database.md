# Product Database Design

## 0. Release Boundary

MVP schemas:

- Identity/accounts
- Individual and business seller profiles
- Basic store
- Categories, listings, and media
- Basic conversations/messages
- Basic business/listing moderation

Deferred schemas:

- V2: cart, inventory, checkout, orders, payments, shipping, notifications
- V3: trades/completion reputation, reviews, advanced moderation/support

Phase 1 setup must define conventions only. It must not create feature tables.

## 1. Database Policy

- MySQL 8 with InnoDB is the transactional default.
- Each deployable service owns its schema and migrations.
- Services access other services through APIs or events, not direct SQL.
- Redis is temporary state; OpenSearch is a derived projection.
- All timestamps are UTC.
- All text uses `utf8mb4`.
- Primary business IDs should be UUID/ULID-compatible `CHAR(26|36)` or
  `BINARY(16)` chosen consistently before the first migration.
- Money uses `DECIMAL(19,4)` and a three-character currency code.
- Status fields use stable strings, not database-native enums, to simplify
  migrations.
- Every mutable aggregate includes `version`, `created_at`, and `updated_at`.

The table lists below define logical ownership. Exact DDL is created later in
small Flyway migrations.

## 2. Identity Schema

### `users`

| Column | Notes |
|---|---|
| `id` | Primary key |
| `email_normalized` | Unique |
| `email_display` | Original display form |
| `password_hash` | Never returned |
| `display_name` | Public name |
| `phone` | Nullable, encrypted or protected |
| `avatar_url` | Nullable |
| `status` | `PENDING_VERIFICATION`, `ACTIVE`, `SUSPENDED`, `CLOSED` |
| `email_verified_at` | Nullable |
| `version` | Optimistic locking |
| timestamps | UTC |

Indexes:

- Unique `email_normalized`
- `status`

### `roles`

Seeded role names and descriptions.

### `user_roles`

| Column | Notes |
|---|---|
| `user_id` | User |
| `role_id` | Role |
| `granted_by` | Nullable for system grant |
| `granted_at` | UTC |

Unique: `(user_id, role_id)`.

Business staff roles are stored in business membership tables, not here.

### `refresh_sessions`

Stores hashed refresh token identifier, user, expiry, rotation chain,
revocation time, device label, and last-used metadata.

Indexes:

- Unique token hash
- `(user_id, revoked_at, expires_at)`

### `verification_tokens`

Stores hashed token, user, purpose, expiry, and consumption time.

### `addresses`

User-owned address book. Do not reference this table from orders; copy address
data into `order_addresses`.

## 3. Seller and Business Schema

### `individual_seller_profiles`

| Column | Notes |
|---|---|
| `user_id` | Primary key and owner |
| `public_city` | Approximate location |
| `public_region` | Approximate location |
| `terms_version` | Accepted version |
| `terms_accepted_at` | UTC |
| `status` | `ACTIVE`, `SUSPENDED`, `CLOSED` |
| timestamps | UTC |

### `business_applications`

Stores applicant, legal name, business type, country, contact data, provider
verification reference, application status, submitted time, and version.

Indexes:

- `(applicant_user_id, status)`
- `(status, submitted_at)` for review queue

### `businesses`

Stores approved legal business identity and status.

### `business_memberships`

| Column | Notes |
|---|---|
| `business_id` | Tenant |
| `user_id` | Member |
| `role` | Business-scoped role |
| `status` | `INVITED`, `ACTIVE`, `REVOKED` |
| `invited_by` | User |
| timestamps | UTC |

Unique: `(business_id, user_id)`.

### `business_invitations`

Stores business, normalized invited email, role, hashed token, expiry, and
acceptance state.

### `stores`

One MVP store per business.

Important columns:

- `business_id` unique
- `slug` unique
- name, description, logo, banner, support contact
- `status`
- version and timestamps

### `store_policy_versions`

Stores immutable shipping, cancellation, and return policy versions.

Unique: `(store_id, policy_type, version_number)`.

### `business_verification_events`

Append-only provider and admin verification history.

## 4. Catalog and Listing Schema

### `categories`

Self-referencing hierarchy with slug, name, status, and display order.

### `category_attribute_definitions`

Defines typed attributes allowed or required for a category:

- key
- label
- data type
- required flag
- allowed values JSON
- validation JSON

JSON here stores configuration, not transactional state.

### `listings`

Shared listing table:

| Column | Notes |
|---|---|
| `id` | Primary key |
| `seller_type` | `INDIVIDUAL`, `BUSINESS` |
| `individual_seller_user_id` | Nullable by seller type |
| `business_id` | Nullable by seller type |
| `store_id` | Nullable by seller type |
| `category_id` | Required |
| `title` | Required |
| `description` | Required |
| `condition_code` | `NEW`, `OPEN_BOX`, `LIKE_NEW`, `GOOD`, `FAIR`, `FOR_PARTS` |
| `condition_notes` | Required for non-new where configured |
| `price_amount` | Asking/fixed price |
| `currency` | ISO code |
| `negotiable` | Individual only |
| `sku` | Business only |
| `quantity` | Individual fixed to 1 |
| `public_city`, `public_region` | Individual discovery |
| `status` | Draft/review/active lifecycle |
| `moderation_status` | Moderation state |
| `published_at` | Nullable |
| `version` | Optimistic locking |
| timestamps | UTC |

Constraints:

- Exactly one seller reference matches `seller_type`.
- Individual quantity equals 1.
- Business SKU unique within business when non-null.

Indexes:

- `(status, seller_type, published_at)`
- `(business_id, status, updated_at)`
- `(individual_seller_user_id, status, updated_at)`
- `(category_id, status, published_at)`

### `listing_attributes`

Normalized typed or string representation keyed by listing and attribute
definition. Unique `(listing_id, attribute_definition_id)`.

### `listing_images`

Stores listing, media object, display order, alt text, and moderation status.

### `media_objects`

Stores owner context, private object key, media type, size, checksum, scan
status, public derivative URL, and lifecycle status.

### `listing_status_history`

Append-only status change with actor, reason, and correlation ID.

## 5. Chat and Individual Trade Schema

### `conversations`

MVP conversations are tied to one individual listing.

Columns include listing, buyer, seller, status, last message time, and
timestamps.

Unique: `(listing_id, buyer_user_id, seller_user_id)`.

### `conversation_participants`

Participant state including last-read message and blocked time.

### `messages`

| Column | Notes |
|---|---|
| `id` | Ordered opaque ID |
| `conversation_id` | Parent |
| `sender_user_id` | Participant |
| `message_type` | `TEXT`, `TRADE_EVENT`, `SYSTEM` |
| `body` | Nullable by type |
| `moderation_state` | Safety state |
| `created_at` | Ordering |

Indexes:

- `(conversation_id, created_at, id)`
- `(sender_user_id, created_at)` for abuse investigation

### `trades`

Release: V3.

| Column | Notes |
|---|---|
| `id` | Primary key |
| `listing_id` | Unique active/completed trade per listing |
| `conversation_id` | Conversation used to select buyer |
| `buyer_user_id` | Participant |
| `seller_user_id` | Participant |
| `deal_note` | Optional seller-entered private note |
| `status` | `AGREED`, `RESERVED`, `COMPLETED`, `CANCELLED`, `REPORTED` |
| `buyer_confirmed_at` | Nullable |
| `seller_confirmed_at` | Nullable |
| `completion_request_id` | Nullable current challenge reference |
| `cancelled_by`, `cancel_reason` | Nullable |
| version and timestamps | UTC |

The table stores no external payment credentials or proof that money moved.
The buyer is derived from `conversation_id` when the seller creates the trade.
Chat text is not parsed into authoritative price, payment, or delivery terms.

### `trade_completion_requests`

Stores:

- trade ID and buyer user ID
- delivery channel: `EMAIL` or `SMS`
- masked destination snapshot for display/audit
- hashed single-use challenge token or code
- expiry, consumed, invalidated, and attempt timestamps
- request count and delivery status

Rules:

- The buyer user ID is copied from the trade, never accepted from seller input.
- Raw challenge values are not stored.
- Raw email/phone destinations are resolved from the identity service at send
  time and are not copied into the trade schema.
- Only one current usable challenge exists per trade.
- Challenges expire and are invalid after successful confirmation.

Indexes:

- Unique active/current request per trade, enforced by transaction/application
  invariant
- Unique challenge hash
- `(status, expires_at)` for cleanup

### `individual_seller_reputation`

Derived public projection:

| Column | Notes |
|---|---|
| `seller_user_id` | Primary key |
| `completed_sales_count` | Non-negative count |
| `rating_count` | Published eligible reviews |
| `rating_average` | Derived average |
| `last_rebuilt_at` | Projection maintenance |
| version and timestamps | UTC |

`completed_sales_count` increments only when a distinct trade first transitions
to `COMPLETED`. The operation must deduplicate by trade ID and support rebuild
from `trades`.

### `individual_seller_reputation_events`

Projection ledger with unique `(seller_user_id, trade_id, event_type)`. This
prevents duplicate increments when completion events are retried.

## 6. Cart, Inventory, Checkout, and Order Schema (V2)

### Redis cart

Key: `cart:v1:{userId}`.

Value:

```json
{
  "version": 3,
  "expiresAt": "timestamp",
  "items": [
    {
      "listingId": "id",
      "quantity": 2,
      "observedPrice": "19.99",
      "currency": "USD",
      "addedAt": "timestamp"
    }
  ]
}
```

Redis cart data is revalidated from source services before checkout.

### `inventory_items`

One row per business listing/SKU:

- business and listing IDs
- SKU
- on-hand quantity
- reserved quantity
- version and timestamps

Constraint: quantities are non-negative and reserved does not exceed on-hand.

### `inventory_movements`

Append-only ledger:

- inventory item
- movement type
- quantity delta
- reference type and ID
- actor
- idempotency key
- created time

Unique idempotency key within inventory service.

### `inventory_reservations`

Stores checkout, item, quantity, status, expiry, idempotency key, committed or
released time.

Indexes:

- Unique idempotency key
- `(status, expires_at)` for expiry worker
- `(inventory_item_id, status)`

### `checkout_sessions`

Stores buyer, status, currency, totals, expiry, address input, and idempotency
key.

### `checkout_items`

Immutable snapshot:

- checkout
- listing and business IDs
- title, SKU, condition
- quantity
- unit price
- shipping, tax, discount allocations
- policy version IDs

### `orders`

Buyer-facing order header:

- checkout reference
- buyer
- order number
- currency and totals
- payment status
- order status
- created/confirmed timestamps

Unique order number and checkout reference.

### `business_orders`

One fulfillment group per business within an order:

- order
- business/store
- seller-visible number
- fulfillment status
- cancellation status
- subtotal and fee projections

Unique `(order_id, business_id)`.

### `order_items`

Immutable listing snapshot tied to `business_order_id`.

### `order_addresses`

Immutable shipping/billing snapshot. Never updated from user address book.

### `order_status_history`

Append-only transition history with actor and reason.

### `shipments`

Business order, carrier, tracking number, status, shipped/delivered timestamps,
and provider reference.

### `shipment_items`

Maps partial shipment quantities to order items.

### `shipment_events`

Append-only carrier status events with provider event ID.

## 7. Payment Schema (V2)

### `payments`

Stores order/checkout reference, provider, provider intent ID, amount, currency,
status, idempotency key, and timestamps.

### `payment_attempts`

Stores each provider interaction result without sensitive card data.

### `payment_events`

Append-only verified provider events.

Unique: `(provider, provider_event_id)`.

### `refunds`

Stores order/payment, amount, reason, status, provider reference, requester,
approver where required, and idempotency key.

### `payout_projections`

V2 read model for expected/paid business amounts and platform fees. Provider
remains authoritative.

## 8. Reviews, Moderation, and Notifications

Release placement:

- MVP: basic moderation cases and decisions for businesses/listings
- V2: notifications
- V3: reviews, reports, suspensions, and support cases

### `reviews`

Stores:

- review type
- reviewer
- subject type and ID
- eligible order item or trade ID
- rating
- title/body
- status
- version and timestamps

Unique eligibility constraints:

- `(reviewer_id, order_item_id, review_type)`
- `(reviewer_id, trade_id, review_type)`

### `rating_aggregates`

Derived count and average by subject/type. Rebuildable from reviews.

### `moderation_cases`

Generic queue item with case type, subject type/ID, status, priority,
assignment, version, and timestamps.

### `moderation_evidence`

References snapshots or permitted evidence. Chat evidence access must be
audited.

### `moderation_decisions`

Append-only action, reason, actor, and created time.

### `reports`

Reporter, subject, category, description, linked case, and status.

### `suspensions`

Subject user/business, scope, reason, start, optional expiry, actor, and
restoration details.

### `support_cases` and `support_case_messages`

Separate internal notes from user-visible responses using an explicit
visibility field.

### `notifications`

User, type, title, safe route, event ID, read time, and created time.

Unique `(user_id, source_event_id, type)`.

### `notification_preferences`

User preference by channel and notification class.

### `notification_deliveries`

Channel attempt, template version, provider reference, status, retry count, and
last error code.

## 9. Reliability and Audit Tables

### `outbox_events`

Every event-producing schema includes:

- event ID
- aggregate type and ID
- event type and version
- payload JSON
- correlation ID
- created time
- published time
- retry count

Index `(published_at, created_at)`.

### `processed_events`

Consumer deduplication by consumer name and event ID.

### `idempotency_records`

Stores caller scope, key, request hash, operation, status, result reference,
and expiry. Same key with a different request hash is rejected.

### `audit_logs`

Append-only:

- actor user/service
- actor roles
- action
- subject type/ID
- business scope
- correlation ID
- safe metadata
- timestamp

Audit logs must not contain passwords, tokens, payment details, or unrestricted
chat message bodies.

## 10. Migration Sequence

Create small migrations in this order:

1. Identity core tables.
2. Role and session tables.
3. Individual seller profile.
4. Business application and verification tables.
5. Business, membership, store, and policies.
6. Categories and attribute definitions.
7. Listings.
8. Media and listing images.
9. Listing status history and outbox.
10. Conversations and messages.
11. Trades, completion requests, and individual seller reputation.
12. Inventory items and movements.
13. Reservations.
14. Checkout sessions and snapshots.
15. Orders and business orders.
16. Payments and payment events.
17. Shipments.
18. Reviews and aggregates.
19. Moderation, reports, and support.
20. Notifications and delivery attempts.
21. Shared idempotency, processed-event, and audit support as owned per schema.

Each migration must be backward compatible with the application version that
precedes it. Destructive cleanup is a separate, later migration.
