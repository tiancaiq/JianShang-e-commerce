# USER-00 User Profile Roadmap

Status: planned.

## Goal

Define how marketplace user profile work should proceed after the completed
IAM profile foundation without mixing it into seller profiles, business store
profiles, chat, reviews, likes, notifications, or admin trust tooling.

The user profile area is the authenticated marketplace account experience for
one human identity. Keycloak remains the credential and identity-provider
engine. The application profile remains app-owned data tied to the Keycloak
subject through `users.keycloak_sub`.

## Current Baseline

Already implemented:

- `IAM-03` creates or returns the application-owned identity row through
  `GET /api/v1/users/me`.
- `IAM-06` lets the authenticated user edit `displayName`, `phone`, and
  `avatarUrl` with optimistic locking.
- Marketplace auth keeps browser tokens out of JavaScript.
- Individual seller profile is separate from basic user profile and lives in
  the `IND-*` slices.

Current profile fields:

- internal user ID
- immutable Keycloak subject
- email and email verification projection from Keycloak
- display name
- phone and phone verification state
- avatar URL
- account status
- version and timestamps

## Product Boundaries

User profile is not the same as:

- individual seller profile
- business store profile
- public business storefront
- chat conversation state
- reviews or reputation
- saved listings, item likes, or recommendations
- notifications
- admin user suspension/support tooling

One human identity may later participate in all of those flows, but each flow
must own its own persistence and authorization rules.

## MVP Profile Principles

- A signed-in user can manage their own basic profile from marketplace account
  routes.
- Public surfaces expose only safe display labels and approved public seller
  data.
- Email, password, MFA, password recovery, and external identity linking stay
  with Keycloak.
- Application services never store passwords, OAuth tokens, Keycloak sessions,
  MFA secrets, or password reset tokens.
- Users cannot edit roles, status, internal IDs, verification flags, or
  Keycloak subject.
- Exact personal address and private contact data are never exposed on public
  listing, seller, or chat entry points.

## Recommended Slices

### USER-01 Marketplace account profile consolidation

Scope:

- Make `/account/profile` the canonical marketplace profile route.
- Keep legacy `/profile` behavior only as compatibility if still needed.
- Ensure marketplace header/account navigation consistently points to
  `/account/profile`.
- Keep profile form fields limited to display name, phone, and avatar URL.

Acceptance criteria:

- Authenticated users can open and save their own profile under
  `/account/profile`.
- Unauthenticated users are sent through the marketplace auth flow and returned
  safely after login.
- Users cannot update email, roles, status, verification fields, or another
  user's profile.

### USER-02 Safe public identity labels

Scope:

- Define a small auth-service read contract for safe public user labels used
  by listing cards, listing detail, and chat headers.
- Return only display name fallback, optional avatar URL, and minimal public
  status needed by the caller.
- Do not expose email, phone, internal role lists, account status internals, or
  Keycloak subjects.

Acceptance criteria:

- Product/listing/chat services can display user labels without querying the
  auth-service database directly.
- Missing or suspended users fail closed or return a neutral safe label based
  on the calling flow.
- Cross-service access uses API contracts, not shared database reads.

### USER-03 Account dashboard shell

Scope:

- Add a compact marketplace account landing area that links to profile,
  seller profile, my listings, and later messages.
- Keep it operational and account-focused, not a marketing landing page.
- Do not add notifications, likes, reviews, orders, cart, or payments.

Acceptance criteria:

- Signed-in marketplace users have one clear account home.
- The account home links only to implemented or intentionally disabled MVP
  routes.
- Business seller portal and admin portal remain separate surfaces.

### USER-04 Marketplace profile card shell

Scope:

- Add a marketplace-styled profile card to the account dashboard and main
  marketplace home rail.
- Show avatar URL when already present, otherwise show a safe initials
  fallback.
- Show display name, generated handle-style label, marketplace seller badge,
  placeholder stat row, and a `Sell an Item` action.
- Keep ratings, trades, reviews, likes, notifications, cart, and wishlist as
  non-functional placeholders or absent UI.

Acceptance criteria:

- Signed-in marketplace users see a polished profile summary on `/account` and
  the main marketplace home.
- Missing avatar URL falls back to initials.
- Placeholder stats do not claim verified reputation or completed trades.
- `Sell an Item` links to `/account/listings/new`.

### USER-05 Avatar image upload

Scope:

- Let authenticated marketplace users upload and remove their own avatar image
  from the account profile route.
- Validate image type and size before storing avatar bytes.
- Store only a safe app-owned avatar URL in `users.avatar_url`.
- Serve public avatar images through an application endpoint rather than raw
  object storage or local file paths.

Acceptance criteria:

- Signed-in users can upload PNG, JPEG, or WebP avatars from `/account/profile`.
- Signed-in users can remove their own avatar.
- Stale profile versions return conflict and do not silently overwrite profile
  state.
- Public avatar delivery hides missing, suspended, or avatarless users.
- The marketplace profile card updates through the existing `avatarUrl` field.

### USER-STAB-01 Profile/avatar stabilization

Scope:

- Review completed `USER-01` through `USER-05` profile/avatar behavior.
- Align the API contract and slice docs with the signed avatar upload flow.
- Preserve existing API contracts and database schema.
- Add focused regression coverage and a manual smoke checklist for profile
  save, avatar upload, avatar removal, and avatar image display.
- Do not add chat, likes, reviews, notifications, address book, or new profile
  fields.

Acceptance criteria:

- Documentation describes the preferred upload-request, direct PUT, and
  confirm flow.
- `PATCH /users/me` accepts the app-owned avatar URL produced by avatar
  upload.
- Profile and account card avatar images resolve through the gateway.
- Focused auth-service and Angular profile/avatar tests pass.
- Manual smoke checklist exists for teammate browser verification.

### USER-06 Chat identity display support

Scope:

- After `CHAT-00`, define how conversation list and message headers show safe
  buyer/seller labels.
- Use `USER-02` public label behavior or a chat-owned snapshot, depending on
  the chat plan.
- Keep message authorization in the chat service.

Acceptance criteria:

- Conversation participants see understandable names/avatars.
- Participants cannot infer private contact data from chat profile display.
- Block/report behavior remains deferred unless the chat slice explicitly
  includes it.

## Deferred User-Related Areas

These are intentionally not part of `USER-00` MVP profile work:

- Item likes, saved listings, and favorites: later discovery/personalization
  slice after MVP browse/search is stable.
- Reviews and reputation: V3, through `REV-*`.
- Notifications and notification preferences: V2, through `NOT-*`.
- Address book: V2 with order/address snapshot rules.
- Account suspension, support cases, and advanced user admin: V3 admin/trust.
- Public completed-sales reputation: V3 trade-completion flow.
- AI profile, chat, or recommendation assistants: V3.

## API Impact

No immediate API changes are required by this planning slice.

Potential later contract additions:

```text
GET /api/v1/users/me                         existing
PATCH /api/v1/users/me                       existing
GET /api/v1/users/public-labels?ids=...      USER-02 candidate
```

The candidate public-label endpoint should be finalized only when listing or
chat implementation needs batched identity labels.

## Database Impact

No migration is required for `USER-00`.

Potential later persistence changes:

- none for `USER-01` if it only consolidates routes
- none for `USER-02` if it reads from existing `users`
- no likes/favorites, notification, review, address, or reputation tables in
  MVP profile slices

## Tests

Minimum tests for later implementation slices:

- Profile route auth and return-url behavior.
- Profile update authorization and optimistic locking.
- Rejection of unsupported/internal profile fields.
- Public label endpoint hides private fields.
- Cross-user profile access is impossible.
- Marketplace account navigation does not expose V2/V3 features.

## Non-Goals

- No new identity provider.
- No password or token storage.
- No reviews.
- No item likes or saved listings.
- No notifications.
- No address book.
- No user suspension/support admin tooling.
- No chat implementation.
- No AI behavior.
