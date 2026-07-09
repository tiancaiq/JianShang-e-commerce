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

Current MVP implementation uses three active MySQL schemas:

- `identity`, owned by `auth-service`, for users, roles, individual seller
  profiles, business applications, businesses, memberships, and business
  verification audit.
- `catalog`, owned by `product-service`, for categories, listings, listing
  media metadata, listing images, listing moderation, and public listing
  search source data.
- `chat`, owned by `chat-service`, for conversations, participants, and text
  messages.

## 2. Identity Schema

### `users`

Application-owned identity user mapped to an immutable Keycloak subject.
Keycloak owns credentials, email verification actions, password recovery, MFA,
login sessions, and OAuth tokens.

| Column | Notes |
|---|---|
| `id` | `CHAR(26)` ULID primary key |
| `keycloak_sub` | Unique immutable Keycloak `sub` |
| `email` | Nullable projection from Keycloak, not the identity key |
| `email_verified` | Projection from Keycloak |
| `display_name` | Nullable app-owned profile value, initially projected from Keycloak |
| `phone` | Nullable app-owned profile value |
| `phone_verified` | App verification state, false until a later verification flow |
| `avatar_url` | Nullable app-owned profile value |
| `status` | `ACTIVE`, `SUSPENDED`, `CLOSED` |
| `version` | Optimistic locking |
| timestamps | UTC |

Indexes:

- Unique `keycloak_sub`
- `email`
- `status`

Do not store passwords, password-reset tokens, Keycloak access tokens,
Keycloak refresh tokens, ID tokens, MFA secrets, or Keycloak login sessions in
application tables.

After IAM-06, profile edits can update only `display_name`, `phone`, and
`avatar_url`. Keycloak remains authoritative for credentials, email, and
`email_verified`; users cannot self-edit roles, account status, internal IDs,
or verification flags.

### `roles`

Seeded role names and descriptions.

IND-01 seeds `BUYER` and `INDIVIDUAL_SELLER`. Business staff roles remain
business-scoped and are not stored here.

### `user_roles`

| Column | Notes |
|---|---|
| `user_id` | User |
| `role_id` | Role |
| `granted_by` | Nullable for system grant |
| `granted_at` | UTC |

Unique: `(user_id, role_id)`.

Business staff roles are stored in business membership tables, not here.

### Credential, session, verification, and recovery data

Owned by Keycloak after ADR-0001. Application services do not create
`refresh_sessions`, password recovery, MFA, or verification-token tables in
IAM-03.

### `addresses`

User-owned address book. Do not reference this table from orders; copy address
data into `order_addresses`.

## 3. Seller and Business Schema

### `individual_seller_profiles`

Created by `IND-01` when an authenticated buyer accepts individual-selling
terms.

| Column | Notes |
|---|---|
| `id` | Profile ID |
| `user_id` | Unique user owner |
| `public_city` | Public location only |
| `public_region` | Public location only |
| `terms_version` | Accepted terms version |
| `status` | `ACTIVE`, `SUSPENDED`, `CLOSED` |
| `completed_sales_count` | Public reputation count, starts at `0` |
| `version` | Optimistic locking |
| `created_at`, `updated_at` | UTC timestamps |

The table must not store exact address, meeting location, payment credentials,
delivery address, or buyer contact data. Activation also grants the local
`INDIVIDUAL_SELLER` role in `user_roles`.

### `business_applications`

Stores applicant, legal name, business type, country, contact data, provider
verification reference, application status, submitted time, and version.

BUS-01 creates only `DRAFT` rows. The authenticated applicant is stored as
`applicant_user_id` and is the proposed business owner. The draft stores legal
name, business type, country, public city/region, contact email, optional
E.164 phone, optional website URL, optional description, status, timestamps,
and version. Approval, business creation, memberships, stores, verification
provider references, and submitted timestamps are deferred to later BUS slices.

BUS-02 moves owned draft rows from `DRAFT` to `PENDING_VERIFICATION` and sets
`submitted_at`. No `businesses`, `business_memberships`, or store rows are
created until a later admin decision slice.

BUS-03 records signed provider callbacks in `business_verification_events`.
Accepted callbacks can move submitted applications to `UNDER_REVIEW` or
`VERIFICATION_FAILED`. Provider event IDs are unique so duplicate callbacks do
not apply duplicate state changes.

BUS-04 stores admin decision metadata on the application:

- `reviewer_user_id`
- `approved_business_id`
- `decision_reason`
- `decided_at`

Approval creates one active `businesses` row and one active `OWNER`
`business_memberships` row for the applicant. Rejection and information
requests do not create a business.

A generated `active_business_account_user_id` column and unique index enforce
one non-rejected business application or approved business account per
applicant. `REJECTED` applications keep their history but do not block the
applicant from starting a corrected new application.

Indexes:

- `(applicant_user_id, status)`
- `(status, submitted_at)` for review queue
- unique `(active_business_account_user_id)` for the active/retry boundary

### `businesses`

Stores approved legal business identity and status.

BUS-04 creates rows with:

- approved application ID
- legal name, business type, and country copied from the application
- status `ACTIVE`
- approving admin user and approval time
- version and timestamps

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

BUS-04 creates the initial applicant membership as role `OWNER`, status
`ACTIVE`. Later staff invitation and permission management belong to BUS-07.

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

BUS-05 creates the table, backfills one active default store for each already
approved business, and creates one default active store in the business
approval transaction for future approvals. Default store name comes from the
approved business legal name; default support contact comes from the approved
business application. Store slugs are unique and lower-case.

### `store_policy_versions`

Stores immutable shipping, cancellation, and return policy versions.

Unique: `(store_id, policy_type, version_number)`.

### `business_verification_events`

Append-only provider and admin verification history.

Important columns:

- provider event ID, unique when present
- application ID
- source `PROVIDER` or `ADMIN`
- event type, outcome, reason
- payload hash for provider callbacks
- actor user ID for admin decisions
- created time

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

LIST-00 creates this table as a schema foundation only. It stores owner IDs but
does not foreign-key to identity/business tables owned by another service.
Later listing command handlers must validate individual seller status or
business membership through service APIs before inserting or changing rows.

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
| `quantity` | Individual fixed to 1; business catalog/display quantity in MVP |
| `public_city`, `public_region` | Individual discovery |
| `status` | Draft/review/active lifecycle, including admin removal |
| `moderation_status` | Moderation state |
| `published_at` | Nullable |
| `version` | Optimistic locking |
| timestamps | UTC |

Constraints:

- Exactly one seller reference matches `seller_type`.
- Individual quantity is seller-entered and must be at least 1.
- Business quantity is seller-entered catalog/display quantity in MVP. It is
  not an authoritative inventory balance and cannot be reserved for checkout
  until V2 inventory tables exist.
- Business SKU unique within business when non-null.

Indexes:

- `(status, seller_type, published_at)`
- `(business_id, status, updated_at)`
- `(individual_seller_user_id, status, updated_at)`
- `(category_id, status, published_at)`

LIST-07 public detail reads only listings where `status=ACTIVE` and
`moderation_status=APPROVED`.

BUS-LIST-00 changes the planned business store item publication path:
approved businesses can publish complete store items to `ACTIVE` without
item-level admin approval, and public `/stores` reads should include those
active business store items. Do not represent self-published business items as
admin-approved unless the schema records the publication source clearly. Add a
forward-safe publication marker if the current status/moderation columns
cannot distinguish business self-publication from admin moderation.

ADM-LIST-04 adds `REMOVED_BY_ADMIN` as a stable listing status. Removed
listings are not public, but their rows, versions, and moderation history stay
authoritative.

### `listing_attributes`

Normalized typed or string representation keyed by listing and attribute
definition. Unique `(listing_id, attribute_definition_id)`.

### `listing_images`

LIST-03 table for ordered draft listing images. Stores listing, confirmed media
object, display order, alt text, moderation status, optimistic version, and
timestamps.

Unique constraints:

- `(listing_id, media_object_id)`
- `(listing_id, display_order)`

### `listing_media_objects`

LIST-02 table for draft listing media metadata. Stores listing owner snapshot,
private object bucket/key, original file name, content type, size, checksum,
upload status, moderation status, optimistic version, and timestamps.

LIST-03 can use this table when attaching confirmed media to ordered listing
images.

LIST-06 allows image/media moderation status to include `CHANGES_REQUESTED`
when an admin asks the seller to revise a submitted listing.

### `listing_visits`

LIST-08 table for account-scoped public listing visits. It stores one row per
authenticated account/listing pair.

Important columns:

- `listing_id`
- `user_id`
- `created_at`

Constraints and indexes:

- Primary key `(listing_id, user_id)`
- `(user_id, created_at)`

### `listing_likes`

LIST-08 table for account-scoped public listing likes. A like row is retained
with an `active` flag so unlike/re-like remains idempotent without destructive
history loss.

Important columns:

- `listing_id`
- `user_id`
- `active`
- `created_at`
- `updated_at`

Constraints and indexes:

- Primary key `(listing_id, user_id)`
- `(user_id, active, updated_at)`
- `(listing_id, active)`

### `listing_engagement_stats`

LIST-08 count projection owned by product-service. MySQL remains authoritative;
OpenSearch may copy these counts later only as a derived read projection.

Important columns:

- `listing_id`
- `visit_count`
- `like_count`
- `updated_at`

Counts are updated transactionally with the command that creates the unique
visit or changes like active state. Counts must never become negative and do
not update the listing aggregate version.

### `listing_moderation_decisions`

LIST-06 append-only decision history for submitted listing review.

Important columns:

- `listing_id`
- `decision`: `APPROVE`, `REJECT`, or `REQUEST_CHANGES`
- `reason`
- `reviewer_user_id`
- `listing_version`
- `created_at`

Rows are immutable audit history for the basic MVP listing review flow.
Moderation case assignment and evidence tables remain deferred to later admin
moderation work.

### `listing_status_history`

Append-only status change with actor, reason, and correlation ID.

## 5. Chat and Individual Trade Schema

CHAT-00 selects a dedicated chat-service schema for MVP conversation and
message data. Individual trade tables remain V3 and may be owned by a later
trade domain once basic chat is validated.

### `conversations`

MVP conversations are tied to one active approved individual listing and use
conversation type `LISTING_BUYER_SELLER`.

Columns include conversation type, subject listing, buyer, seller, status,
last message ID, last message time, version, and timestamps.

Unique for `LISTING_BUYER_SELLER`:
`(subject_listing_id, buyer_user_id, seller_user_id)`.

The same user may appear as buyer in one conversation and seller in another.
Buyer/seller role is conversation-scoped and must not be inferred from global
role lists.

### `conversation_participants`

Participant state including user, role in conversation, last-read message, and
last-read timestamp.

`CHAT-03` uses `last_read_message_id` and `last_read_at` for per-participant
unread state. Mark-read updates only the authenticated participant row.

### `messages`

| Column | Notes |
|---|---|
| `id` | Ordered opaque ID |
| `conversation_id` | Parent |
| `sender_user_id` | Participant |
| `message_type` | MVP accepts user-created `TEXT` |
| `body` | Required for MVP `TEXT`, max 2000 characters |
| `moderation_state` | MVP starts with `VISIBLE` |
| `created_at` | Ordering |

Indexes:

- `(conversation_id, created_at, id)`
- `(sender_user_id, created_at, id)` for abuse investigation

The sender must exist in `conversation_participants` for the same
conversation. Successful message sends update the parent conversation
last-message fields.

### `listing_trade_completions`

CHAT-05 stores minimal conversation-gated individual completion state in
chat-service. This is a scoped expansion from the earlier V3 trade-completion
placement and does not add reputation, reviews, payment, shipping, order, or
buyer contact sharing.

Important columns:

- `id`
- `listing_id`
- `conversation_id`
- `seller_user_id`
- `buyer_user_id`
- `quantity_sold`: sold quantity recorded by the seller; defaults to `1`
- `status`: `SELLER_MARKED_DONE`, `BUYER_CONFIRMED`, `CANCELLED`
- seller marked done, buyer confirmed, and cancelled timestamps
- version and timestamps

Rules:

- `conversation_id` is unique, making seller mark-done idempotent per
  conversation.
- Buyer and seller IDs are copied from the fixed conversation participants,
  not accepted from UI input.
- `quantity_sold` is validated by chat-service against the product listing
  quantity before seller mark-done.
- Buyer confirmation closes the product listing through product-service APIs;
  chat-service never writes product-service tables.

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
- ADM-LIST-04 listing decisions include `ADMIN_EDIT` and `ADMIN_REMOVE` history
  rows for active listing maintenance.
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

MVP case workflow table for listing review. The initial supported case type is
`LISTING_REVIEW`; reports, support cases, suspensions, and dispute tooling are
later admin slices.

Stores:

- subject listing ID
- seller snapshot: seller type plus individual seller user ID or business ID
- submitting user ID
- status: `OPEN`, `CLAIMED`, `RESOLVED`
- priority: `LOW`, `NORMAL`, `HIGH`
- assigned admin user ID
- version, created time, updated time, and resolved time
- generated active listing-review key used to prevent duplicate open/claimed
  cases for the same listing

Listing state remains authoritative for publication and moderation outcome.

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
