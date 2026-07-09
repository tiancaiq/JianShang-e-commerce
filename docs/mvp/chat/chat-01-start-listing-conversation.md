# CHAT-01 Start Listing Conversation

Status: complete.

## Goal

Implement the first MVP chat behavior: a signed-in buyer can start or reopen a
conversation with the individual seller of an active approved listing.

This slice establishes the initial `chat-service` module, schema, creation API,
and minimal frontend entry point needed for later text messaging work.

## Dependencies

- `CHAT-00` chat domain plan.
- `USER-06` chat identity display support.
- Active approved individual listing reads from product-service.
- Gateway-authenticated current user context.

## Scope

In scope:

- Create the initial `chat-service` deployable module.
- Add Flyway migration for initial chat-owned tables:
  - `conversations`
  - `conversation_participants`
- Add conversation creation endpoint:

```text
POST /api/v1/listings/{listingId}/conversations
```

- Validate that the listing is eligible for MVP chat.
- Create or return one `LISTING_BUYER_SELLER` conversation for the
  listing/buyer/seller tuple.
- Add fixed buyer and seller participant rows.
- Return conversation summary with safe listing context and participant
  summaries.
- Add marketplace listing-detail `Message seller` action for eligible signed-in
  users.

Out of scope:

- Sending text messages.
- Message history.
- Conversation list.
- Read/unread state.
- Realtime delivery.
- Customer service chat.
- Business seller to admin chat.
- Admin direct messaging.
- AI agent sessions.
- Reports, blocking, moderation evidence, or chat evidence review.
- Trade creation or trade completion.
- Reviews, completed-sales count, notifications, cart, checkout, payment,
  orders, shipping, or inventory.

## Product Rules

- Only authenticated users can start conversations.
- Only active approved `INDIVIDUAL` listings can start MVP buyer/seller chat.
- The seller is derived from the listing owner.
- The buyer is the authenticated current user.
- A seller cannot start a buyer conversation with themselves through their own
  listing.
- Participants are fixed after creation.
- Duplicate starts return the existing conversation instead of creating another
  one.
- The same user can be buyer in one conversation and seller in another.
- Buyer/seller role is conversation-scoped and does not come from global roles.
- Business listings do not use this endpoint for MVP chat.

## Backend Service Shape

Create a new Maven module:

```text
chat-service/
```

Initial dependencies should match the smallest active service pattern:

- Spring Boot web
- Spring Security resource-server/session-compatible current actor support used
  by active MVP services
- JDBC or the repository style already used by the repository
- Flyway
- MySQL driver
- `common-web`
- `common-core`
- `common-testing` for tests

Do not add Kafka, Redis, WebSocket, or SSE in this slice.

## API Contract

### Start listing conversation

```text
POST /api/v1/listings/{listingId}/conversations
```

Request body: none.

Authorization: authenticated marketplace user.

Response `201` when created, `200` when the existing conversation is returned:

```json
{
  "id": "01J...",
  "conversationType": "LISTING_BUYER_SELLER",
  "status": "OPEN",
  "listing": {
    "id": "01J...",
    "title": "Used bicycle",
    "sellerType": "INDIVIDUAL",
    "publicCity": "Irvine",
    "publicRegion": "CA",
    "thumbnailUrl": "/api/v1/public/listing-media/01J...",
    "transactionNotice": "Payment and delivery are arranged directly by participants. The platform does not verify or protect off-platform payment."
  },
  "participants": [
    {
      "participantId": "01J...",
      "displayName": "You",
      "avatarUrl": null,
      "initials": "Y",
      "roleInConversation": "BUYER",
      "currentUser": true
    },
    {
      "participantId": "01J...",
      "displayName": "Alex Seller",
      "avatarUrl": "/api/v1/public/user-avatars/01J...?v=4",
      "initials": "AS",
      "roleInConversation": "SELLER",
      "currentUser": false
    }
  ],
  "createdAt": "2026-07-04T12:00:00Z",
  "updatedAt": "2026-07-04T12:00:00Z"
}
```

Response fields must not expose:

- seller email or phone
- buyer email or phone
- Keycloak subject
- role lists
- account status internals
- listing moderation internals
- listing version
- media object bucket/key
- signed storage URLs
- raw object-storage URLs
- exact individual location

## Listing Validation Contract

`chat-service` needs a chat-safe product-service validation read. Prefer an
internal or protected product-service endpoint that returns only the fields
needed to create a conversation:

```text
GET /api/v1/internal/chat/listings/{listingId}/conversation-eligibility
```

Recommended response:

```json
{
  "listingId": "01J...",
  "eligible": true,
  "sellerType": "INDIVIDUAL",
  "sellerUserId": "01J...",
  "title": "Used bicycle",
  "publicCity": "Irvine",
  "publicRegion": "CA",
  "thumbnailUrl": "/api/v1/public/listing-media/01J...",
  "transactionNotice": "Payment and delivery are arranged directly by participants. The platform does not verify or protect off-platform payment."
}
```

Rules:

- Product-service remains authoritative for listing visibility and seller
  ownership.
- Chat-service must not query product-service tables.
- Chat-service must not use public listing DTOs if doing so would couple chat
  creation to storefront response shape.
- Product-service should return `404` for missing, inactive, unapproved,
  removed, rejected, or otherwise hidden listings.
- Product-service should return `422` or equivalent business error for
  non-MVP seller types such as `BUSINESS`.

If a dedicated internal endpoint is too much for the first implementation,
using an existing public detail endpoint is acceptable only as a temporary
adapter behind a chat-service client interface. The chat domain code should
still consume a chat-specific eligibility object.

## Data Model

Add a Flyway migration in the chat-service schema.

### `conversations`

Fields:

- `id`
- `conversation_type`
- `subject_listing_id`
- `buyer_user_id`
- `seller_user_id`
- `status`
- `last_message_id`
- `last_message_at`
- `version`
- `created_at`
- `updated_at`

Initial statuses:

```text
OPEN
```

Reserved statuses:

```text
ARCHIVED
LOCKED
```

Indexes:

- unique `(conversation_type, subject_listing_id, buyer_user_id,
  seller_user_id)`
- `(buyer_user_id, updated_at, id)`
- `(seller_user_id, updated_at, id)`

### `conversation_participants`

Fields:

- `conversation_id`
- `user_id`
- `role_in_conversation`
- `last_read_message_id`
- `last_read_at`
- `joined_at`

Initial roles:

```text
BUYER
SELLER
```

Indexes:

- primary or unique `(conversation_id, user_id)`
- `(user_id, conversation_id)`

`last_read_message_id` and `last_read_at` can remain `null` until `CHAT-03`.

## Creation Transaction

The chat-service creation flow should run in one database transaction:

1. Resolve current actor user ID.
2. Fetch listing conversation eligibility from product-service.
3. Reject ineligible listing states.
4. Reject self-chat.
5. Try to insert the conversation.
6. Insert buyer and seller participant rows.
7. If the unique conversation constraint is hit, load the existing
   conversation and participants.
8. Hydrate safe participant labels through auth-service or neutral fallbacks.
9. Return the conversation summary.

The unique constraint is the final duplicate-conversation guard. Application
logic should still check first for clearer behavior, but concurrency safety
must not rely on check-then-insert alone.

## Authorization

Gateway route protection is not enough. Chat-service must enforce:

- authenticated actor required
- actor becomes the buyer for creation
- actor cannot create conversation with self
- only the buyer or seller participant can read the returned conversation
  summary

Future read/send endpoints must use chat-owned participant rows for
authorization, not listing ownership or profile label availability.

## Frontend Direction

Listing detail page:

- Guests see a sign-in prompt or auth dialog trigger for messaging.
- Signed-in users viewing another user's active approved individual listing see
  `Message seller`.
- Signed-in seller viewing their own listing does not see `Message seller`.
- Business listing detail does not use this action in MVP.
- On success, navigate to the conversation detail route planned for `CHAT-02`,
  or to a temporary `/account/messages/:conversationId` shell if implemented in
  the same slice.

Keep individual seller chat in the marketplace account surface. Do not add chat
to the business seller portal or admin portal in this slice.

## Observability

Add structured logs and metrics for important failure paths without private
contact data or message bodies:

- listing eligibility lookup failed
- listing not eligible
- self-chat rejected
- duplicate conversation returned
- conversation creation succeeded
- participant label hydration failed

Logs may include listing ID, conversation ID, actor user ID, seller user ID,
and correlation ID. Logs must not include email, phone, exact location,
Keycloak subject, storage object keys, or raw storage URLs.

## Tests

Backend integration tests:

- authenticated buyer creates a conversation for an active approved individual
  listing
- duplicate creation returns the existing conversation
- concurrent duplicate creation results in one conversation row
- guest creation returns `401`
- self-chat returns a business-rule error
- business listing is rejected
- inactive, unapproved, rejected, removed, or missing listings are hidden or
  rejected according to the contract
- conversation response omits private user, listing, and storage fields
- product-service lookup failure returns a temporary dependency error without
  creating a conversation

Authorization tests:

- buyer is derived from current actor, not request input
- seller is derived from product-service listing data, not request input
- user who is an individual seller can still be buyer for another seller's
  listing

Frontend tests:

- guest listing detail shows sign-in prompt for messaging
- signed-in non-owner on individual listing can trigger conversation creation
- listing owner does not see the self-chat action
- business listing does not show MVP `Message seller` action
- successful creation navigates to the planned message route or temporary
  conversation shell

## Verification Commands

Expected checks once implemented:

```powershell
.\mvnw.cmd -pl chat-service -am test
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/marketplace/public-listing-detail.component.spec.ts
npm.cmd run build
```

## Acceptance Criteria

- `chat-service` exists and owns initial conversation tables.
- `POST /api/v1/listings/{listingId}/conversations` creates or returns one
  conversation for a buyer/listing/seller tuple.
- Only active approved individual listings can start MVP chat.
- Buyer and seller are derived server-side.
- Self-chat is rejected.
- Duplicate and concurrent duplicate starts do not create duplicate
  conversations.
- Conversation response uses safe participant display data only.
- Marketplace listing detail has a minimal `Message seller` entry point for
  eligible signed-in users.
- No message sending, conversation list, admin chat, support chat, AI chat,
  trade, review, notification, cart, checkout, payment, order, shipping, or
  inventory behavior is added.
