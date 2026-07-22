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

V2-IAM-01 user-owned address book. Stores recipient name, recipient phone,
optional label, address lines, city, region, postal code, country code,
default state, optimistic version, and UTC timestamps.

Rules:

- At most 20 addresses per user.
- A nonempty address book has exactly one default after each committed
  command.
- A generated nullable owner column plus a unique index enforces at most one
  default per user.
- Create, delete-default, and set-default lock the owning `users` row to
  serialize collection invariants.
- `(user_id, is_default, updated_at, id)` supports the bounded account list.
- `(user_id, created_at, id)` supports deterministic default promotion.
- Address rows cascade on user deletion.
- User-requested address deletion is a hard delete.

Do not reference this table from checkout or orders. Copy normalized address
data into immutable checkout and `order_addresses` snapshots. Editing or
deleting an address-book row never changes an existing checkout or order.

Detailed schema and concurrency rules are defined in
`docs/v2/commerce/v2-iam-01-buyer-address-book-plan.md`.

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

BUS-LIST-04 implements the business store item publication path: approved
businesses can publish complete store items to `ACTIVE` without item-level
admin approval, and public `/stores` reads include active business store items
where `publication_source=BUSINESS_SELF_PUBLISHED`. Self-published business
items are not represented as admin-approved items.

BUS-LIST-06 uses `(business_id, status, updated_at)` for the seller management
query and stable `updated_at, id` cursor ordering. No schema migration was
required for the management list.

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

`CHAT-07` adds a unique immutable `users.public_handle` identity label. Chat
resolves it through auth-service together with display name and avatar; it does
not copy private identity fields into chat-service.

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
- Buyer confirmation also changes the conversation status to `LOCKED`.
  Completed conversations reject new messages while preserving their history.

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

Ownership is defined by V2-COM-00:

- `inventory-service` owns `inventory_items`, `inventory_movements`, and
  `inventory_reservations`.
- `order-service` owns the Redis cart namespace plus checkout, order,
  fulfillment, shipment, history, outbox, and idempotency records.
- `payment-service` owns the payment schema in section 7.

There are no cross-service foreign keys. Product, auth, inventory, order, and
payment identifiers stored outside their owning schema are immutable
references validated through APIs or durable events.

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
      "observedPrice": 19.99,
      "currency": "USD",
      "addedAt": "timestamp"
    }
  ]
}
```

The cart expires after 30 days of inactivity. Mutations atomically increment
`version`, update `expiresAt`, write the document, and refresh Redis TTL.
Reads do not refresh expiry. A missing or expired key is an empty version-zero
cart. Redis cart data is revalidated from source services before checkout.

### `inventory_items`

One row per business store listing. SKU is retained as a catalog snapshot:

- ULID primary key
- business and globally stable listing IDs
- SKU and catalog-version snapshots
- on-hand and reserved quantities
- initialized, created, and updated timestamps
- optimistic version

Constraint: quantities are non-negative and reserved does not exceed on-hand.
Listing ID is unique and is the inventory identity. SKU is a nonunique catalog
snapshot; current SKU uniqueness remains owned by product-service.

Indexes:

- Unique `listing_id`
- `(business_id, updated_at, id)`
- `(business_id, sku_snapshot)`

### `inventory_movements`

Append-only ledger:

- inventory item, business, and listing references
- `INITIALIZE`, `SET`, or `ADJUST` operation
- stable reason code and optional bounded note
- signed quantity delta
- on-hand before/after and reserved snapshot
- actor, command, and correlation IDs
- created time

Command ID is unique. Inventory-service `idempotency_records` deduplicate the
caller/operation/key scope; reuse with a different request hash is rejected.
The item, movement, idempotency result, and outbox event are committed in one
transaction.

### `inventory_reservations` and reservation lines

V2-INV-02 defines one checkout-scoped reservation aggregate containing one
through 50 distinct listing lines. The whole aggregate reserves, releases,
expires, or commits atomically.

The reservation header stores checkout reference, purpose, status, immutable
expiry, transition version, and committed or released timestamps.
`inventory_reservation_items` stores immutable inventory item, business,
listing, and quantity references plus reserve and terminal balance snapshots.
`inventory_reservation_history` is the append-only state-transition audit.

Indexes and constraints:

- Unique `(checkout_id, purpose)`
- `(status, expires_at, id)` for the expiry worker
- Unique reservation/item and reservation/listing line pairs
- `(inventory_item_id, reservation_id)` for inventory history lookup
- Unique transition command ID

Inventory-service idempotency records deduplicate reserve, release, and commit
commands by caller scope and request hash. Their result resource reference is
nullable for retained reservation outcomes that create no aggregate, such as
an insufficient-stock response.

### `checkout_sessions`

Stores buyer, status, currency, totals, expiry, address input, and idempotency
key.

The first immutable platform policy row is `LOCAL_DEMO_V1`, approved only for
the local demo. Checkout rows copy its shipping, cancellation, and return text
into checkout-owned snapshots; they never reference mutable seller policy.

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

`V2-ORD-02B` reads seller queues with SQL-enforced `business_id` scope and
descending `(created_at, id)` cursors. The existing V3
`idx_business_order_queue (business_id, fulfillment_status, created_at, id)`
serves exact-status queues. MySQL 8.4 `EXPLAIN` showed an unfiltered queue
filesort, so forward-only Order Service V4 adds
`idx_business_order_all_queue (business_id, created_at, id)`. V1 through V3
are unchanged.

Order Service Flyway V5 (`V2-SHP-01A`) adds a nonnegative optimistic `version`
and replaces the named fulfillment check with exactly
`PENDING_ACCEPTANCE|ACCEPTED`. Acceptance uses one conditional update by
business ID, business-order ID, expected version, pending state, and
`cancellation_status=NONE`; it never updates the buyer `orders.status`.

### `business_order_acceptance_commands`

V5 stores the durable `ACCEPT_BUSINESS_ORDER` command, actor user ID, business
and business-order IDs, idempotency key, canonical SHA-256 request hash,
in-progress/completed state, original result version/time, and P7D expiry.
Unique `(actor_user_id, business_id, operation, idempotency_key)` serializes
same-key concurrency. `(expires_at, id)` supports bounded lazy retention
purging.

### `business_order_status_history`

V5 adds append-only business-group transition history separate from buyer
`order_status_history`. Each row records the group/business IDs,
`PENDING_ACCEPTANCE -> ACCEPTED`, new group version, `BUSINESS_ACCEPTED`,
internal actor user ID, correlation ID, durable command causation ID, and
creation time. Unique `(business_order_id, business_order_version)` prevents a
second history row for the same transition version.

The group update, history row, one version-1 `business_order.accepted`
`order_outbox_events` row, and completed command result commit atomically.
The event aggregate and partition identity are the business-order ID. Payload
is limited to event identity/version/time, business-order ID, parent order ID,
business ID, `ACCEPTED`, and business-order version.

### `order_items`

Immutable listing snapshot tied to `business_order_id`.

### `order_addresses`

Immutable shipping/billing snapshot. Never updated from user address book.

### `order_status_history`

Append-only transition history with actor and reason.

### Order confirmation foundation (`V2-ORD-01A`)

Order Service Flyway V3 extends checkout status with the approved
`PAYMENT_PROCESSING`, `PAYMENT_REVIEW`, `REFUND_REQUIRED`, and `COMPLETED`
states and keeps the generated one-active-checkout constraint through payment
processing/review.

`checkout_payment_intents` stores the exact Order-owned payment command
binding: payment/checkout IDs, checkout version and snapshot hash, buyer,
ordered business scope, amount/currency, expiry, and creation time. It has one
payment intent per checkout and does not query or reference Payment Service
tables.

`processed_payment_events` uses composite primary key
`(consumer_name, event_id)`, stores the canonical payload hash, bounded claim
lease, safe state/outcome/error, and optional confirmed order reference.
Payload-hash reuse conflicts fail closed; retryable or expired claims can be
recovered after restart.

`orders`, `business_orders`, `order_items`, `order_addresses`, and
`order_status_history` copy the immutable checkout totals, business/store
scope, item fields, policy version, and shipping address. Unique checkout and
payment-intent keys enforce one buyer order; unique `(order_id, business_id)`
enforces one fulfillment group per business. Platform fee projection remains
null until a later approved money-movement slice.

After idempotent inventory commit, order header/groups/snapshots/history,
checkout `COMPLETED`, processed-event completion, and version-1
`order.confirmed` outbox insertion commit in one Order Service transaction.

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

### `payment_outbox_events`

The verified provider event, terminal payment status/history, payment attempt,
and version-1 `payment.succeeded` or `payment.failed` outbox row commit in one
payment-service transaction. Transport availability never participates in
that transaction.

The dispatcher extension stores the next and last attempt times, retry and
attempt counts, a bounded claim token/lease, publication time, bounded safe
error code/message, and terminal-failure time. Due rows are claimed in stable
`(created_at, id)` order with row locking. Transport acknowledgement occurs
before publication marking, so delivery is at-least-once and consumers
deduplicate by event ID. Published and terminal-failed states are mutually
exclusive, and claim fields are all present or all absent.

Forward-only payment-service migrations:

- V2 creates the provider-neutral payment-intent foundation.
- V3 creates verified webhook, immutable provider-event/history/attempt, and
  transactional outbox persistence.
- V4 adds bounded outbox claim, retry, backoff, publication, and terminal-error
  metadata plus due/lease indexes and state constraints.

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

Notification Service owns this schema. `V2-NOT-01A` stores an opaque recipient
user ID, stable `ORDER_CONFIRMED` type, `ORDER_CONFIRMED_V1` message key,
bounded `{orderId}` arguments, allowlisted `/account` route, source event
identity/type/version/time/hash, nullable read time, optimistic version,
local/test retention metadata, and creation/update times.

Unique `(recipient_user_id, source_event_id, type)`. `V2-NOT-01B` uses the
existing recipient-created index for stable `(created_at DESC,id DESC)` pages
and the recipient-unread index for owned read commands. Mark-one sets
`read_at` once and preserves it on replay; mark-all updates only the resolved
recipient's unread rows. `V2-NOT-01C` adds no schema and keeps the UI
projection to notification ID, type, message key, validated bounded args,
allowlisted route, read state, and timestamps. There is no cross-service
foreign key and no source envelope, email, address, payment, provider data,
source hash, raw JSON, or consumer metadata in the UI/API projection.

### `notification_source_events`

Durable `(consumer_name, source_event_id)` deduplication with source
type/version/hash, processing state/outcome, bounded safe error code,
source/correlation times, attempt count, and P180D local/test retention
metadata. Supported creation and notification projection commit atomically.
Same-ID/same-hash replay is safe; same-ID/different-hash conflicts. Unsupported
events and identifiable poison are terminally rejected. Purge is unavailable
and disabled pending legal and operations approval.

### `notification_preferences`

User preference by channel and notification class.

### `notification_deliveries`

Channel attempt, template version, provider reference, status, retry count, and
last error code.

## 9. Agent Service Schema And Knowledge Projection (V3)

The agent service owns a separate MySQL schema. It uses forward-only Flyway SQL
migrations stored with `agent-service`; application startup does not create or
update schema automatically. A dedicated migration job applies the schema
before the matching service version is promoted.

No agent table has a foreign key to another service schema. Actor and listing
IDs are immutable references validated through authenticated application APIs.

### Authoritative knowledge source records

Knowledge remains in its owning service before it is projected:

- Product Service listing tables own approved public listing description and
  attribute versions.
- Product Service owns future immutable `category_guidance_versions`.
- The moderation/support module owns future immutable
  `marketplace_knowledge_source_versions` for `MARKETPLACE_POLICY`,
  `SAFETY_GUIDANCE`, and `MARKETPLACE_FAQ`.

Versioned knowledge records include stable source key, source type, language,
version, status, content hash, effective-from and optional effective-to time,
body, created time, and activating actor or service. Unique
`(source_type, source_key, language, version)` prevents version reuse.

Publishing or retiring a source creates a new immutable version or explicit
state transition with audit history and an outbox event. The agent service
does not own or write these authoritative records. Exact source-authoring APIs
and migrations belong to their later owning feature slices.

#### Product Service `category_guidance_versions` (`AI-KNOW-01`)

Product Service stores one immutable source stream per
`(category_id, language)`. The primary key is
`(category_id, language, source_version)`, where source versions are positive
and strictly increasing within that stream.

An `ACTIVE` row stores `PUBLIC` visibility, category slug/name snapshots,
plain-text title/body, lowercase canonical content hash, effective time,
activating admin user ID, correlation ID, and creation time. An
`INVALIDATED` row is a newer tombstone with exact `supersedes_version`,
invalidation time, invalidating admin user ID, correlation ID, and no content
or content hash.

Historical rows are never updated. Publication after an active or invalidated
version inserts the next active version. Retirement and category deactivation
insert the next invalidated version. Checks enforce active/tombstone shape and
`supersedes_version < source_version`. A same-service foreign key references
`categories`.

Indexes support locked latest-version lookup, latest active export as of a
fixed watermark, and creation-time audit scans. Only an active category may
receive a new active guidance version. Category deactivation invalidates every
active language in the same category-status transaction; reactivation does not
restore historical guidance.

The Product Service `outbox_events` table is reused. Source-version and outbox
inserts are atomic. Events carry category/language/version/lifecycle
references only and never carry source bodies or admin identity.

Forward-only migration:

```text
V202607191600__create_category_guidance_publication.sql
```

#### Product Service `listing_knowledge_versions` (`AI-RAG-02A`)

Product Service stores an immutable row keyed by
`(listing_id, source_version)`. `source_version` equals the authoritative
listing aggregate version.

An `ACTIVE` row contains only seller type `INDIVIDUAL`, visibility `PUBLIC`,
language `und`, title, approved description, decimal price/currency, public
city/region, content hash, effective time, optional original listing
publication time, and creation time.

An `INVALIDATED` row contains the new source version, exact
`supersedes_version`, invalidation time, and safe source metadata but no source
body or content hash. Immutable old active rows may remain for exact-version
replay; current export selects only the greatest version per listing and
requires that version to be active.

The table does not store seller identity, contact data, exact location, media,
moderation evidence, payment/delivery preferences, storage fields, or
credentials.

Indexes support latest active export, exact listing lifecycle/version lookup,
and operational creation-time scans. A bounded Product Service worker
backfills current eligible listings that predate this table.

#### Product Service `outbox_events` (`AI-RAG-02A`)

The Product Service outbox stores event/topic/key, aggregate reference,
event type/version, producer, occurred time, correlation ID, reference-only
JSON payload, unique deduplication key, publication/retry times, bounded claim
lease, safe error code, and creation time.

The listing mutation, immutable knowledge version, and outbox insert commit in
one transaction. Multiple publisher instances claim rows with row locking.
Broker acknowledgement precedes `published_at`; failures clear the claim and
schedule bounded retry. Duplicate broker delivery remains possible and
consumers deduplicate by event ID.

### Agent durable ingestion (`AI-RAG-02B`)

Forward-only Agent Service Flyway `V1` creates the following tables. FastAPI
startup checks their presence but never creates or changes them.

#### `processed_events`

Stores consumer name, event identity/type/version, aggregate reference,
contracted payload hash, accepted time, and correlation ID. Composite primary
key `(consumer_name, event_id)` provides durable replay deduplication. A replay
with a different payload hash fails closed.

#### `knowledge_ingestion_jobs`

Stores one unique event-backed job with source type/ID/version, language,
lifecycle, nullable superseded version, occurrence time, payload hash, status,
attempt count, next attempt, expiring claim, safe error code, and lifecycle
timestamps.

Statuses are `PENDING`, `PROCESSING`, `RETRY_WAIT`, `SUCCEEDED`, and
`DEAD_LETTER`. Claim and completion checks prevent contradictory row shapes.
Indexes support bounded ordered claims, source/version lookup, and expired
claim recovery.

#### `knowledge_source_state`

Reserves one row per `(source_type, source_id, language)` for the latest
observed/indexed version, content hash, superseded version, active/tombstone/
failure state, chunker and embedding identity, source/index/invalidation
timestamps, last event/failure, and optimistic version.

`AI-RAG-02B` creates but does not write this projection state. State advancement
begins with `AI-RAG-02C` after content verification and OpenSearch writes.

All three tables exclude source bodies, passages, embeddings, provider
responses, credentials, seller identity, contact data, exact locations, media,
and moderation evidence.

### Agent rebuild and deletion operations (`AI-RAG-02D`)

Forward-only Agent Service Flyway `V2` adds `knowledge_rebuild_runs` and
`knowledge_deletion_jobs`.

`knowledge_rebuild_runs` stores one operator-initiated listing rebuild with
its exact target and prior read generations, embedding/chunker identity,
opaque export cursor and fixed watermark, completion flag, bounded progress
counts, status, safe error code, operator identity, lifecycle timestamps, and
optimistic version. Status is `RUNNING`, `FAILED`, `READY_TO_PROMOTE`,
`PROMOTED`, or `ROLLED_BACK`. It stores no source body, passage, vector,
provider response, or credential.

`knowledge_deletion_jobs` stores one unique exact
`(source_type, source_id, source_version, language)` cleanup operation with
invalidation time, bounded attempt count, next attempt, expiring claim,
status, safe error code, and lifecycle timestamps. Status is `PENDING`,
`PROCESSING`, `RETRY_WAIT`, `SUCCEEDED`, or `DEAD_LETTER`. Tombstone or
supersession processing invalidates the exact version in every live
generation before this row is scheduled, so delayed physical deletion cannot
make content retrievable.

Both tables remain agent-owned and reference no other service schema.

### `agent_sessions`

Stores:

- session ID and type;
- actor application user ID;
- subject type and listing ID;
- `OPEN`, `READ_ONLY`, or `CLOSED` status;
- created, updated, last-activity, and closed timestamps.

One open `LISTING_CUSTOMER_SERVICE` session is allowed per
`(actor_user_id, subject_listing_id)`. Agent Service Flyway V4 enforces this
with a MySQL-safe nullable generated open-session marker and unique key, not
only an application check.

Index `(actor_user_id, updated_at, id)` supports session lookup.

### `agent_messages`

Stores:

- session and actor references;
- `USER` or `ASSISTANT` role;
- bounded message body;
- assistant resolution type;
- validated source and action payloads for assistant messages;
- created time.

Index `(session_id, created_at, id)` supports cursor pagination.

Message bodies and model answer payloads are retained for 90 days after session
activity, then deleted by the agent retention job. They are not copied into
unrestricted logs, traces, analytics, or evaluation datasets.

### `agent_invocations`

Stores:

- session, actor, and user-message references;
- client message ID and request hash;
- `PENDING`, `SUCCEEDED`, or `FAILED` result status;
- safe provider error classification;
- prompt, model, schema, tool-registry, and policy versions;
- token usage, latency, estimated cost, retry count, and correlation ID;
- created and completed timestamps.

Unique `(session_id, actor_user_id, client_message_id)` deduplicates client
retries. Reuse with a different request hash is rejected.

Agent Service Flyway V5 expands invocation correlation IDs to 128 characters
so the Agent persistence contract matches the gateway correlation boundary.

### `agent_tool_calls`

Stores:

- invocation reference and sequence;
- allowlisted tool name;
- argument and result hashes;
- safe source IDs and versions used;
- result status, latency, and created time.

Raw tool payloads, retrieved passages, prompts, credentials, private contact
data, exact locations, storage internals, and moderation data are not stored in
this audit table.

Safe invocation and tool-call metadata is retained for 365 days. Production
message content is not used as an evaluation fixture unless separately
approved and redacted.

### `agent_listing_proposals` (`AI-LIST-02A`)

Agent Flyway V6 stores one seller-review-only proposal row per
`(actor_user_id, client_request_id)`. The row stores actor/listing IDs, exact
source listing version, canonical request and media hashes, status and
proposal version, bounded proposal/evidence/result JSON, and lifecycle
timestamps. It never stores media bytes, prompts, provider bodies, storage
references, seller PII, Product patch data, or model-owned trusted IDs.

Status is only `READY`, `DISMISSED`, or `EXPIRED`. `READY` contains bounded
review content. Dismissal immediately nulls proposal, evidence, and result
metadata. Content expires exactly 24 hours after creation and is lazily purged
on owner access plus by an hourly bounded retention job. Safe hashes,
identifiers, versions, and timestamps remain as an idempotency tombstone until
exactly 90 days after creation, then the row is hard deleted.

`agent_listing_proposal_claims` is a short-lived DB coordination table, not
proposal state. Its unique actor/client key and expiring claim token ensure
concurrent same-key requests have at most one active Product/provider
execution without a process lock. Failed or cancelled generation deletes the
claim and creates no proposal row.

`agent_listing_proposal_dismissals` stores only actor/proposal/key, a fixed
DISMISS hash, and creation time. Its composite key makes dismiss retries
idempotent and it cascades when the 90-day proposal tombstone is deleted.

Forward-only Agent migration:

```text
V6__create_listing_proposal_review_persistence.sql
```

Forward-only Agent Service migration:

```text
V4__create_agent_customer_service_persistence.sql
```

V4 creates all four tables with actor-bound same-schema foreign keys, terminal
row-shape checks, retry and pagination indexes, allowlisted tool names, and
retention-supporting indexes. After 90-day content purge, message references,
client message IDs, and request hashes are cleared while safe terminal
invocation/tool metadata may remain until the 365-day audit cutoff.

### Agent OpenSearch knowledge chunks

The rebuildable V3 knowledge index stores:

- chunk ID and ordinal;
- source type, ID, version, and content hash;
- nullable subject listing ID;
- `PUBLIC` visibility;
- language;
- nullable effective-from and effective-to timestamps;
- indexed and nullable invalidated timestamps;
- safe section label, sanitized text, and embedding vector.

Initial source types are `LISTING`, `MARKETPLACE_POLICY`, `SAFETY_GUIDANCE`,
`MARKETPLACE_FAQ`, and `CATEGORY_GUIDANCE`.

The active alias returns only non-invalidated, effective, public content.
Listing-specific retrieval requires the exact session subject listing ID and
matching current listing version. Conversation messages, private or
permission-scoped content, media bytes, contact data, exact locations,
moderation evidence, internal notes, object keys, signed URLs, and credentials
are never indexed.

Updates and tombstones are idempotent by source ID and version. The operational
target for update or deletion propagation is 15 minutes; excessive lag disables
affected vector-backed answers. OpenSearch remains derived and may be rebuilt
from authoritative source APIs and durable events.

## 10. Reliability and Audit Tables

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

## 11. Migration Sequence

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
21. Agent sessions, messages, invocations, and tool-call audit.
22. Shared idempotency, processed-event, and audit support as owned per schema.

Each migration must be backward compatible with the application version that
precedes it. Destructive cleanup is a separate, later migration.
