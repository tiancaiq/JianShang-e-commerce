# USER-06 Chat Identity Display Support

Status: planned.

## Goal

Define the safe user identity display contract that future MVP chat screens
will use for conversation lists, conversation headers, and message rows without
starting chat implementation in this slice.

This slice bridges the completed user profile work and the upcoming `CHAT-*`
slices. It keeps identity data owned by auth-service while leaving message
authorization, message persistence, unread state, and conversation rules to the
future chat service.

## Baseline

Completed profile work already provides:

- application-owned user IDs mapped to Keycloak subjects
- editable display names through `PATCH /api/v1/users/me`
- app-owned public avatar URLs through `USER-05`
- safe public user labels through `USER-02`

`USER-02` is the preferred source for display labels:

```text
GET /api/v1/users/public-labels?userIds=01J...
```

## Scope

- Define how chat participant display should use safe public identity labels.
- Define frontend fallback behavior for missing names and avatars.
- Define how future chat APIs should avoid exposing private identity fields.
- Document where participant label hydration belongs once `CHAT-00` selects
  the chat service shape.
- Keep the current database schema and API behavior unchanged.

## Display Rules

Chat surfaces may show only:

- safe display name
- app-owned public avatar URL
- initials fallback derived from the safe display name
- neutral fallback text when a label is missing
- whether the participant is the current user
- chat/listing context labels, such as buyer or seller, when derived from the
  authorized conversation

Chat surfaces must not show:

- email
- phone
- Keycloak subject
- role lists
- account status internals
- verification flags
- private profile/contact metadata
- storage object bucket, object key, signed URL, or raw GCS URL

## Participant Label Fallbacks

Recommended fallback behavior:

- Current user: `You` in compact message rows when useful.
- Missing display name for a visible participant: `Marketplace user`.
- Missing seller label in listing conversation context: `Marketplace seller`.
- Missing avatar URL: initials generated from the safe display name.
- Missing, closed, or suspended user label from auth-service: neutral fallback
  text with no avatar.

These fallbacks prevent chat from leaking whether a private account, suspended
account, or deleted identity exists.

## Future Chat Integration

`CHAT-00` should choose one of these two implementation approaches:

1. Chat-service hydration, preferred for MVP:
   - Chat APIs return participant summaries already enriched with safe labels.
   - Chat service calls auth-service `GET /api/v1/users/public-labels` in
     batches.
   - Chat service never queries auth-service tables directly.
   - If auth-service is unavailable, chat returns neutral participant labels
     while preserving authorization decisions from chat-owned participant rows.

2. Frontend hydration, acceptable only for early UI scaffolding:
   - Chat APIs return authorized participant user IDs.
   - Angular batches public-label reads through the gateway.
   - The UI applies the same neutral fallbacks.
   - This should not become the long-term authorization model.

If `CHAT-00` decides chat needs immutable participant display snapshots, the
chat service may store a chat-owned display snapshot at conversation creation.
That snapshot is chat domain data and must not replace auth-service as the
current profile owner.

## API Contract Notes

No new endpoint is required for `USER-06`.

Future chat responses should use a frontend-ready participant shape similar to:

```json
{
  "participantId": "01J...",
  "displayName": "Alex Seller",
  "avatarUrl": "/api/v1/public/user-avatars/01J...?v=4",
  "initials": "AS",
  "roleInConversation": "SELLER",
  "currentUser": false
}
```

Rules:

- `participantId` is the app-owned user ID, not the Keycloak subject.
- `avatarUrl` must be an app-owned public avatar URL or `null`.
- `roleInConversation` is derived from the authorized conversation, not from
  global user role lists.
- Private fields remain absent even when both participants are authenticated.

## Non-Goals

- No chat message persistence.
- No conversation creation.
- No unread count.
- No realtime transport.
- No block/report behavior.
- No reviews, ratings, completed-sales reputation, likes, or notifications.
- No database migration.
- No new profile fields.

## Acceptance Criteria

- Future chat list/header/message designs can show participant names and
  avatars using only safe label data.
- Chat identity display cannot expose email, phone, Keycloak subject, roles,
  verification flags, or account status internals.
- App-owned avatar URLs follow the existing `USER-05` public avatar rules.
- Future chat service implementation uses auth-service APIs or chat-owned
  snapshots, never direct auth-service database reads.
- Missing or hidden identity labels degrade to neutral display text.

## Tests For Later Implementation

When chat slices implement this contract, add tests that verify:

- participant summaries omit private fields
- missing labels fall back to neutral text
- suspended or closed users do not expose avatar URLs
- current-user rendering does not expose a different participant's data
- chat authorization remains enforced by chat service participant membership
