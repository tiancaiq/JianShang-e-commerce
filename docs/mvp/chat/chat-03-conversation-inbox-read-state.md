# CHAT-03 Conversation Inbox And Read State

Status: complete.

## Goal

Add the marketplace account messages inbox and per-participant read state for
buyer/seller listing conversations.

## Dependencies

- `CHAT-00` chat domain plan.
- `CHAT-01` start listing conversation.
- `CHAT-02` send and read text messages.

## Scope

In scope:

- List only conversations where the current user is a fixed participant:
  `GET /api/v1/conversations?cursor=&limit=`.
- Mark the current participant's conversation read:
  `POST /api/v1/conversations/{conversationId}/read`.
- Compute unread state per participant.
- Add `/account/messages` inbox UI.
- Make `/account/messages/:conversationId` open the selected real thread.

Out of scope:

- Realtime delivery.
- Push/email notifications.
- Typing indicators.
- Attachments.
- Blocking, reporting, or moderation evidence review.
- Customer service chat.
- Business seller to admin chat.
- Admin direct messaging.
- AI agent sessions.
- Reviews, trade completion, offers, cart, checkout, payment, orders,
  shipping, inventory, or notifications.

## Product Rules

- A user sees only conversations where they have a `conversation_participants`
  row.
- Buyer and seller both use the marketplace account inbox.
- Unread state is isolated per participant.
- Mark-read updates only the authenticated participant row.
- Sending a message does not automatically mark the other participant read.
- Listing chat remains free-text only.

## API Contract

### List conversations

```text
GET /api/v1/conversations?cursor=&limit=
```

Response:

```json
{
  "items": [
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
        "transactionNotice": "Payment and delivery are arranged directly by participants."
      },
      "otherParticipant": {
        "participantId": "01J...",
        "displayName": "Alex Seller",
        "avatarUrl": null,
        "initials": "AS",
        "roleInConversation": "SELLER",
        "currentUser": false
      },
      "lastMessage": {
        "id": "01J...",
        "conversationId": "01J...",
        "senderUserId": "01J...",
        "messageType": "TEXT",
        "body": "Still available.",
        "moderationState": "VISIBLE",
        "currentUser": false,
        "createdAt": "2026-07-06T12:00:00Z"
      },
      "unread": true,
      "lastMessageAt": "2026-07-06T12:00:00Z",
      "createdAt": "2026-07-06T11:00:00Z",
      "updatedAt": "2026-07-06T12:00:00Z"
    }
  ],
  "nextCursor": null
}
```

### Mark read

```text
POST /api/v1/conversations/{conversationId}/read
```

Response:

```json
{
  "conversationId": "01J...",
  "lastReadMessageId": "01J...",
  "lastReadAt": "2026-07-06T12:01:00Z",
  "unread": false
}
```

## Data Model

Uses existing `conversation_participants` fields:

- `last_read_message_id`
- `last_read_at`

Unread is true when the latest message exists, was sent by another
participant, and the current participant has not read that latest message.

## Frontend Direction

`/account/messages`:

- Shows a conversation list.
- Shows unread indicators.
- Opens the first conversation by default when available.
- Supports direct `/account/messages/:conversationId`.
- Displays the selected conversation and messages.
- Sends text messages from the selected thread.

The listing-page mini chat from `CHAT-02` remains the primary listing-context
entry point.

## Acceptance Criteria

- Participant sees only their conversations.
- Buyer and seller each see the same conversation from their own perspective.
- Unread state is isolated per participant.
- Mark-read does not affect the other participant.
- Direct conversation links load the selected real thread.
- No realtime, notifications, support/admin/AI, review, trade, checkout,
  payment, order, shipping, inventory, or notification behavior is added.
