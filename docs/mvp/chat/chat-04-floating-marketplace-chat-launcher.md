# CHAT-04 Marketplace Chat UX Polish

Status: complete.

## Goal

Make buyer/seller listing conversations reachable from a polished bottom-right
chat launcher in the signed-in marketplace experience.

## Dependencies

- `CHAT-00` chat domain plan.
- `CHAT-01` start listing conversation.
- `CHAT-02` send and read text messages.
- `CHAT-03` conversation list and unread state.

## Scope

In scope:

- Show a bottom-right marketplace chat icon for authenticated marketplace
  users.
- Hide the launcher for guests.
- Open a compact split panel with recent users/conversations on the left and
  the selected thread on the right.
- Show clearer loading, retry, and empty states in the panel.
- Show clearer unread and last-message-time indicators in the conversation
  list.
- Make the listing context link in the selected thread visually obvious.
- Send text messages from the panel.
- Mark the selected conversation read after loading its messages.
- Show a non-functional marketplace agent placeholder in the launcher.

Out of scope:

- New conversation creation from the launcher.
- Realtime delivery.
- Push/email notifications.
- Typing indicators.
- Attachments.
- Blocking, reporting, or moderation evidence review.
- Customer service chat.
- Business seller to admin chat.
- Admin direct messaging.
- Working AI agent sessions.
- Reviews, trade completion, offers, cart, checkout, payment, orders,
  shipping, inventory, or notifications.

## Product Rules

- The launcher uses only existing participant-authorized chat APIs.
- The launcher does not expose chat to guests.
- The launcher is mounted in the marketplace layout, not the business seller
  portal or admin portal.
- A user can open only conversations returned by
  `GET /api/v1/conversations`.
- Read state remains per participant and is updated through
  `POST /api/v1/conversations/{conversationId}/read`.
- Listing chat remains free-text only.
- The marketplace agent option is a UI placeholder only and does not call chat,
  support, or AI APIs.

## API Contract

No new backend endpoints are introduced. The launcher uses the `CHAT-03`
conversation list and read-state endpoints plus the `CHAT-02` thread and text
message endpoints:

```text
GET  /api/v1/conversations?cursor=&limit=
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages?cursor=&limit=
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/read
```

## UI Behavior

- Closed state is a circular chat icon with an unread badge.
- Open panel state shows recent users/conversations on the left with avatar or
  initials, listing title, last message preview, unread marker, and message
  time.
- The left rail includes a marketplace agent option that opens a coming-soon
  placeholder.
- Selecting a user opens the thread on the right with listing context,
  transaction notice, messages, and a text composer capped at 2000 characters.
- Conversation load failure shows a retry control without opening any
  unauthorized conversation.

## Implementation Notes

- Frontend implementation lives in
  `frontend/src/app/features/chat/floating-chat.component.ts`.
- The marketplace layout mounts the launcher once through
  `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`.
- No Flyway migration is needed.
- No OpenAPI/API client shape changes are needed.
