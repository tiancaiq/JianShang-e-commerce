# CHAT-00 Chat Domain Plan

Status: complete.

## Scope

CHAT-00 defines the MVP chat domain, service boundary, authorization model, and
build order before adding message persistence or UI behavior.

This slice is documentation-only. It does not add migrations, API
implementation, frontend routes, realtime transport, customer service, admin
direct messaging, AI agent sessions, reports, reviews, trade completion,
notifications, cart, checkout, payment, orders, shipping, or inventory.

## Product Decision

Create a dedicated `chat-service` as the reusable messaging foundation.

The `chat-service` owns conversation mechanics:

- conversations
- participants
- text messages
- message ordering and cursor pagination
- participant read state
- participant-only authorization checks after a conversation exists
- safe participant display snapshots or auth-service label hydration
- chat-specific logs and metrics

Feature domains own why a conversation exists and whether it may be created.
For MVP, the only enabled feature domain is buyer-to-individual-seller listing
chat.

## Conversation Types

Supported in MVP:

```text
LISTING_BUYER_SELLER
```

Reserved for future releases, but not implemented in MVP chat slices:

```text
SUPPORT_CASE
BUSINESS_ADMIN_SUPPORT
AI_AGENT_SESSION
```

Future conversation types require their own authorization, lifecycle, audit,
and UI slices before they are enabled.

## MVP Conversation Rules

`LISTING_BUYER_SELLER` conversations are tied to one active approved
individual listing.

Rules:

- A signed-in buyer starts the conversation from a listing detail page.
- The seller is derived from the listing owner.
- A seller cannot create a buyer conversation with themselves through their own
  listing.
- Participants are fixed after creation.
- One conversation exists for each `(listingId, buyerUserId, sellerUserId)`.
- The same user may be a buyer in one conversation and an individual seller in
  another conversation.
- Only fixed participants can read, send, or mark messages read.
- Conversation role is derived from the conversation row, not from global role
  lists.
- Buyer and seller negotiate only with free-text messages.

MVP chat must not add:

- structured offers
- counteroffers
- offer acceptance
- checkout
- platform payment
- shipping
- trade creation
- trade completion
- seller completed-sales count changes
- admin/customer-service direct messages
- AI-generated automatic message sends

## Service Ownership

`chat-service` owns its own database schema and migrations. Other services must
not query or update chat tables directly.

The chat service may call other services through APIs:

- product/listing APIs for listing visibility and seller ownership checks
- auth-service public-label APIs for safe participant display labels
- auth/session or gateway-propagated actor context for the authenticated user

The chat service must not query auth-service or product-service databases.

## Listing Conversation Creation

`CHAT-01` should add:

```text
POST /api/v1/listings/{listingId}/conversations
```

Creation flow:

1. Authenticate the current user.
2. Resolve the listing through product-service using a chat-safe validation
   endpoint or an existing protected internal read.
3. Require the listing to be active, approved, and `sellerType=INDIVIDUAL`.
4. Derive `sellerUserId` from the listing owner.
5. Reject self-chat when `buyerUserId == sellerUserId`.
6. Create or return the existing conversation for the listing/buyer/seller
   tuple.
7. Create fixed participant rows for buyer and seller.

Guest users are redirected or prompted to sign in by the marketplace frontend.
The backend still returns `401` for unauthenticated creation attempts.

## Message And Read APIs

`CHAT-02` should add:

```text
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages?cursor=&limit=
POST /api/v1/conversations/{conversationId}/messages
```

`CHAT-03` should add:

```text
GET  /api/v1/conversations
POST /api/v1/conversations/{conversationId}/read
```

Message rules:

- MVP accepts only `TEXT` messages from users.
- Blank messages are rejected.
- Message body length is server-limited.
- Message history is ordered by `(createdAt, id)` and cursor-paginated.
- Message bodies are untrusted input and must be output-encoded by clients.
- Non-participants receive `404` or `403` according to the final API error
  convention chosen for hidden resources.

Read-state rules:

- Read state is stored per participant.
- Mark-read accepts a conversation and records the latest readable message for
  that participant.
- Unread count or unread boolean is computed per participant.

## Participant Display

Chat display follows `USER-06`.

Responses may include frontend-ready participant summaries with:

- app-owned user ID
- safe display name or neutral fallback
- app-owned public avatar URL or `null`
- initials fallback
- conversation role: `BUYER` or `SELLER`
- current-user flag

Responses must not include:

- email
- phone
- Keycloak subject
- role lists
- account status internals
- verification flags
- private profile/contact metadata
- raw object-storage URLs
- object buckets or keys
- signed upload/read URLs

Preferred MVP implementation: chat-service hydrates labels in batches from
auth-service `GET /api/v1/users/public-labels`. If auth-service is unavailable,
chat responses use neutral labels while preserving participant authorization
from chat-owned rows.

## Data Model Direction

Initial chat-owned tables:

- `conversations`
- `conversation_participants`
- `messages`

Recommended conversation fields:

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

Recommended participant fields:

- `conversation_id`
- `user_id`
- `role_in_conversation`
- `last_read_message_id`
- `last_read_at`
- `joined_at`

Recommended message fields:

- `id`
- `conversation_id`
- `sender_user_id`
- `message_type`
- `body`
- `moderation_state`
- `created_at`

Recommended indexes:

- unique `(subject_listing_id, buyer_user_id, seller_user_id)` for
  `LISTING_BUYER_SELLER`
- `(buyer_user_id, last_message_at)`
- `(seller_user_id, last_message_at)`
- `(conversation_id, created_at, id)`
- `(sender_user_id, created_at)` for abuse investigation

## Frontend Direction

Marketplace listing detail:

- Guests see a sign-in prompt for messaging.
- Signed-in users see `Message seller` on active approved individual listings.
- The action is not shown for the signed-in seller viewing their own listing.
- Business listing contact behavior remains separate and does not introduce
  cart or checkout.

Marketplace account:

- Add `/account/messages`.
- Individual sellers use the same marketplace account messages page.
- Do not add chat to the business seller portal for MVP.
- Do not add admin chat UI for MVP.

Conversation UI:

- Show listing context.
- Show safe buyer/seller labels.
- Show ordered text messages.
- Include a text composer.
- Show off-platform payment/delivery disclosure for individual listing chat.

## Future Boundaries

Customer service chat should be case-based. A future support workflow can
create a `SUPPORT_CASE` conversation, but support case ownership, assignment,
visibility, internal notes, staff permissions, and audit rules belong to a
support/admin domain slice.

Business seller to admin chat should be `BUSINESS_ADMIN_SUPPORT` and should be
created from a business support or admin workflow. It is not part of MVP.

AI agent sessions should not be treated as normal user-to-user chat. A future
agent service owns sessions, tools, cost tracking, prompt/tool/result audit,
and human confirmation rules. It may reuse chat-style UI patterns or chat
storage only after an agent-specific contract approves that integration.

Admin "chat with everyone" is deferred. Admin communication should be
case/context based with granular permissions and audit, not unrestricted direct
messages.

Reviews and reputation do not belong to chat-service. Future review work
should use a separate review/reputation domain because eligibility,
publication, moderation, aggregation, and public display rules differ from
private conversation rules.

## Observability And Safety

Important failure paths should emit structured logs and metrics without message
bodies or private contact data:

- conversation creation denied
- listing validation unavailable
- self-chat rejected
- duplicate conversation returned
- non-participant access attempt
- message validation rejected
- message send rate-limited
- auth-service label hydration unavailable

Message bodies should not be written to unrestricted application logs.

## Build Order

1. `CHAT-01` start listing conversation.
2. `CHAT-02` send and read text messages.
3. `CHAT-03` conversation list and unread state.

Realtime delivery, customer service, business-admin support, AI agent sessions,
reports, blocking, and trade creation are separate future slices.

## Acceptance Criteria

- The MVP chat service boundary is documented.
- The only enabled MVP conversation type is `LISTING_BUYER_SELLER`.
- Buyer/seller roles are conversation-scoped.
- The same user can be buyer in one conversation and seller in another.
- Admin, customer service, business-admin, AI, review, trade, and checkout
  behavior are explicitly deferred.
- Chat participant display follows `USER-06` and safe-label rules.
- `CHAT-01` through `CHAT-03` have clear API, data, authorization, frontend,
  and test direction.

## Test Expectations For Implementation Slices

`CHAT-01`:

- buyer can start one conversation for another user's active approved
  individual listing
- duplicate start returns the existing conversation
- guest cannot start a conversation
- seller cannot start a buyer conversation with self
- inactive, unapproved, missing, or business listings cannot start MVP
  buyer/seller chat

`CHAT-02`:

- only participants can read conversation detail
- only participants can read message history
- only participants can send text messages
- blank, too-long, and unsupported message types are rejected
- messages are ordered and cursor-paginated

`CHAT-03`:

- each participant sees only their conversations
- unread state is isolated per participant
- mark-read cannot affect another participant
- participant summaries expose safe labels only
- missing labels degrade to neutral text
