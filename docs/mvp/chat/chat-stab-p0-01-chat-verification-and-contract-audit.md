# CHAT-STAB-P0-01 Chat Verification And Contract Audit

Status: complete.

## Goal

Verify the MVP buyer/seller chat implementation after `CHAT-00` through
`CHAT-04` and confirm the service, API, listing eligibility, privacy, gateway,
and frontend route contracts are still aligned.

## Scope

In scope:

- Verify `chat-service` conversation and message APIs.
- Verify listing conversation creation is limited to approved active
  individual listings.
- Verify business listings cannot start MVP chat.
- Verify chat responses do not expose email, phone, Keycloak subject, private
  contact metadata, storage object keys, buckets, or signed storage URLs.
- Verify marketplace message routes and chat entry points still work.
- Run focused backend and frontend chat tests.

Out of scope:

- New chat features.
- Realtime delivery.
- Push/email notifications.
- Customer service chat.
- Business seller to admin chat.
- Admin direct messaging.
- AI agent sessions.
- Reviews, trade completion, offers, cart, checkout, payment, orders,
  shipping, inventory, or notifications.

## Contract Audit

### Chat service APIs

Verified endpoints:

```text
POST /api/v1/listings/{listingId}/conversations
GET  /api/v1/conversations?cursor=&limit=
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages?cursor=&limit=
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/read
```

Audit result:

- All conversation detail, message history, message send, inbox, and read-state
  paths enforce chat-owned participant authorization.
- Non-participants receive `CHAT_CONVERSATION_NOT_FOUND`.
- Message sends derive the sender from the current authenticated actor.
- Message sends allow only `TEXT`, reject blank content, and reject bodies over
  2000 characters.
- Message history is cursor-paginated and ordered by `(created_at, id)`.
- Conversation inbox returns only conversations where the actor is a fixed
  participant.
- Read state is isolated to the current participant.

### Listing eligibility

Product-service eligibility endpoint:

```text
GET /api/v1/internal/chat/listings/{listingId}/conversation-eligibility
```

Audit result:

- Product-service returns eligibility only for listings with
  `status='ACTIVE'` and `moderation_status='APPROVED'`.
- Product-service rejects `BUSINESS` listings for MVP chat.
- Product-service hides inactive and unapproved listings from chat
  eligibility.
- Chat-service rejects ineligible listings and rejects self-chat.
- Buyer and seller IDs are derived server-side, not accepted from the client.

### Privacy and data exposure

Audit result:

- Chat DTOs expose only safe listing context, app-owned user IDs, display
  names, avatar URLs, initials, conversation role, current-user flag, message
  metadata, and message body.
- Chat responses do not include email, phone, Keycloak subject, private
  contact metadata, listing moderation internals, storage object keys, buckets,
  or signed storage URLs.
- Product-service chat eligibility DTO omits listing status, moderation
  status, version, private seller labels, object keys, and buckets.
- Frontend chat models and services consume only the safe chat DTO shapes.

### Frontend routes and surfaces

Audit result:

- `/account/messages` and `/account/messages/:conversationId` remain protected
  by `authGuard`.
- Listing detail starts or opens listing chat through the chat service.
- Listing detail hides MVP chat for business listings.
- Listing detail handles self-chat rejection by removing the message action.
- Floating marketplace chat uses existing participant-authorized APIs only.
- Chat remains mounted in the marketplace account/marketplace layout, not the
  business seller portal or admin portal.

## Verification Tests

Backend:

- `chat-service/src/test/java/com/msb/ecom/chat_service/ConversationApiTests.java`
- `product-service/src/test/java/com/msb/ecom/product_service/ListingDraftApiTests.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/ApiGatewayApplicationTests.java`

Frontend:

- `frontend/src/app/app.routes.spec.ts`
- `frontend/src/app/features/marketplace/public-listing-detail.component.spec.ts`
- `frontend/src/app/features/account/conversation-shell.component.spec.ts`
- `frontend/src/app/features/chat/floating-chat.component.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`

## Verification Commands

```powershell
.\mvnw.cmd -pl chat-service -Dtest=ConversationApiTests test
.\mvnw.cmd -pl product-service -Dtest=ListingDraftApiTests test
.\mvnw.cmd -pl api-gateway -Dtest=ApiGatewayApplicationTests test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/app.routes.spec.ts --include=src/app/features/marketplace/public-listing-detail.component.spec.ts --include=src/app/features/account/conversation-shell.component.spec.ts --include=src/app/features/chat/floating-chat.component.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

## Verification Results

Run on 2026-07-06:

- `ConversationApiTests`: 19 tests passed.
- `ListingDraftApiTests`: 96 tests passed.
- `ApiGatewayApplicationTests`: 23 tests passed.
- Focused Angular chat/routes specs: 32 tests passed.

## Acceptance Criteria

- Chat-service APIs pass participant authorization and message validation
  tests.
- Product-service eligibility allows only active approved individual listings.
- Business, inactive, and unapproved listings cannot start MVP chat.
- Chat and eligibility responses do not expose private contact/storage fields.
- Marketplace message routes and components pass focused frontend tests.
- No future chat, AI, review, trade, or commerce behavior is added.
