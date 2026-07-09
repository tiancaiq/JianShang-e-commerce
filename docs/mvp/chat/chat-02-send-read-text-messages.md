# CHAT-02 Send And Read Text Messages

Status: complete.

## Goal

Implement MVP text messaging for an existing buyer/seller listing
conversation, with the primary marketplace UX opening as a mini chat window on
the listing detail page.

## Dependencies

- `CHAT-00` chat domain plan.
- `CHAT-01` start listing conversation.
- `USER-06` safe participant labels.

## Scope

In scope:

- Add chat-owned `messages` persistence.
- Add participant-authorized conversation detail read:
  `GET /api/v1/conversations/{conversationId}`.
- Add cursor-paginated message history:
  `GET /api/v1/conversations/{conversationId}/messages?cursor=&limit=`.
- Add text message send:
  `POST /api/v1/conversations/{conversationId}/messages`.
- Add gateway routing for `/api/v1/conversations/**`.
- Replace the listing-detail redirect with a mini chat drawer.
- Keep `/account/messages/:conversationId` as a temporary fallback route.

Out of scope:

- Conversation inbox.
- Unread count or mark-read.
- Realtime delivery.
- Attachments or images.
- Typing indicators.
- Blocking, reporting, or moderation review UI.
- Customer service chat.
- Business seller to admin chat.
- Admin direct messaging.
- AI agent sessions.
- Reviews, trade completion, offers, cart, checkout, payment, orders,
  shipping, inventory, or notifications.

## Product Rules

- Only fixed conversation participants can read conversation detail.
- Only fixed conversation participants can read message history.
- Only fixed conversation participants can send messages.
- Sender is always the authenticated current user.
- MVP accepts only user-authored `TEXT` messages.
- Blank messages are rejected.
- Message body length is limited to 2000 characters.
- Message history is ordered by `(created_at, id)`.
- The chat drawer keeps buyers in listing context.
- Individual listing payment and delivery disclosure remains visible in chat.

## API Contract

### Get conversation

```text
GET /api/v1/conversations/{conversationId}
```

Returns the same safe conversation summary shape used by `CHAT-01`.

### Get messages

```text
GET /api/v1/conversations/{conversationId}/messages?cursor=&limit=
```

Response:

```json
{
  "items": [
    {
      "id": "01J...",
      "conversationId": "01J...",
      "senderUserId": "01J...",
      "messageType": "TEXT",
      "body": "Is this still available?",
      "moderationState": "VISIBLE",
      "currentUser": true,
      "createdAt": "2026-07-05T12:00:00Z"
    }
  ],
  "nextCursor": null
}
```

### Send message

```text
POST /api/v1/conversations/{conversationId}/messages
```

Request:

```json
{
  "messageType": "TEXT",
  "body": "Is this still available?"
}
```

Response: `201` with the created message.

## Data Model

Add `messages`:

- `id`
- `conversation_id`
- `sender_user_id`
- `message_type`
- `body`
- `moderation_state`
- `created_at`

Indexes:

- `(conversation_id, created_at, id)`
- `(sender_user_id, created_at, id)`

The sender is constrained to an existing `conversation_participants` row.
Successful sends update `conversations.last_message_id`,
`conversations.last_message_at`, `conversations.updated_at`, and
`conversations.version`.

## Frontend Direction

Listing detail `Message seller`:

1. Guest users still go to sign-in.
2. Signed-in users create or reopen the conversation.
3. The listing page opens a mini chat drawer.
4. The drawer loads message history.
5. The drawer sends text messages without leaving the listing page.

The full inbox route is deferred to `CHAT-03`.

## Acceptance Criteria

- Participants can read conversation detail.
- Non-participants cannot read conversation detail.
- Participants can read cursor-paginated messages.
- Non-participants cannot read messages.
- Participants can send text messages.
- Blank, too-long, and unsupported message types are rejected.
- Message sends update conversation last-message fields.
- Listing detail opens a chat drawer instead of navigating away.
- No inbox, unread state, realtime, support/admin/AI, review, trade, checkout,
  payment, order, shipping, inventory, or notification behavior is added.
