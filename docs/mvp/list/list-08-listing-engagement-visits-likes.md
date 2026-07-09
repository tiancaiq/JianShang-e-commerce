# LIST-08 Listing Engagement Visits And Likes

Status: complete.

## Scope

LIST-08 adds lightweight account-scoped engagement for approved public
listings:

- one authenticated account can count as visited at most once per listing
- one authenticated account can like at most once per listing
- likes can be removed and re-added
- public listing cards and detail pages can show visit and like counts
- signed-in users can see whether they have liked the listing

This slice does not implement wishlist pages, recommendations, ranking changes,
notifications, reviews, reports, analytics dashboards, cart, checkout,
payment, inventory, orders, shipping, or trade completion.

Guest browsing remains public. Guest visits are not counted in this slice.

## Product Rules

- Engagement applies only to listings that are public: `status=ACTIVE` and
  `moderationStatus=APPROVED`.
- The backend derives the actor from the authenticated session or resource
  server principal. Clients cannot submit user IDs.
- Duplicate visit commands by the same account for the same listing are
  idempotent and do not increment the count again.
- Duplicate like commands by the same account for the same listing are
  idempotent and do not increment the count again.
- Unlike is idempotent. Removing a non-active like does not decrement below
  zero.
- Seller self-engagement follows the same one-account-one-listing rule as any
  other authenticated account.
- Engagement writes must not update the listing aggregate `version`, because
  likes and visits are not seller listing edits.

## Backend

Implemented endpoints:

```text
POST   /api/v1/listings/{listingId}/visit
POST   /api/v1/listings/{listingId}/like
DELETE /api/v1/listings/{listingId}/like
GET    /api/v1/listings/{listingId}/engagement/me
GET    /api/v1/users/me/liked-listings
```

Rules:

- All endpoints require an authenticated user.
- `POST /visit` records a one-time visit for the current account and returns
  the current engagement summary.
- `POST /like` activates the current account's like for the listing and
  returns the current engagement summary.
- `DELETE /like` deactivates the current account's like for the listing and
  returns the current engagement summary.
- `GET /engagement/me` returns the current account's engagement state and the
  current public counts.
- `GET /users/me/liked-listings` returns the current account's active liked
  listings using the safe public listing projection.
- Hidden, draft, pending-review, rejected, changes-requested, closed, removed,
  or otherwise non-public listings return `404 LISTING_NOT_FOUND`.

Example response:

```json
{
  "listingId": "01J...",
  "visitCount": 12,
  "likeCount": 4,
  "visitedByMe": true,
  "likedByMe": false
}
```

Public listing search and detail responses should add:

```json
{
  "visitCount": 12,
  "likeCount": 4
}
```

## Persistence

Add forward-safe product-service Flyway migrations for catalog-owned tables:

### `listing_visits`

One row per authenticated account/listing pair.

Important columns:

- `listing_id`
- `user_id`
- `created_at`

Constraints and indexes:

- Unique `(listing_id, user_id)`
- Index `(user_id, created_at)`

### `listing_likes`

One row per authenticated account/listing pair, with an active flag so unlike
and re-like history does not require destructive deletes.

Important columns:

- `listing_id`
- `user_id`
- `active`
- `created_at`
- `updated_at`

Constraints and indexes:

- Unique `(listing_id, user_id)`
- Index `(user_id, active, updated_at)`
- Index `(listing_id, active)`

### `listing_engagement_stats`

Small public count projection owned by product-service.

Important columns:

- `listing_id`
- `visit_count`
- `like_count`
- `updated_at`

Rules:

- Counts are derived from `listing_visits` and active `listing_likes`.
- Updates must be transactionally consistent with the command that creates a
  visit or changes like state.
- Counts must never become negative.
- MySQL remains authoritative. OpenSearch, if enabled, may use counts only as a
  derived projection and must not be the source of truth.

## Frontend

Marketplace listing detail:

- If signed in, record a visit when the detail page loads.
- Show visit count and like count.
- Show a like/unlike control.
- If a guest clicks like, route them through the existing marketplace
  login/auth flow before retrying.

Marketplace listing cards:

- Show compact visit and like counts when available.
- Keep cards image-forward and avoid introducing wishlist/cart language.

No wishlist collections, folders, notes, recommendations, or notification
features are part of this slice.

Marketplace account:

- `/account/liked` shows active public listings the current account has liked.
- Unliked or no-longer-public listings disappear from this view.

## Authorization And Safety

- Enforce all engagement authorization in product-service.
- Do not rely on frontend hiding.
- Do not accept actor IDs, owner IDs, listing status, moderation status, or
  counts from the client.
- Do not expose private seller IDs, business member data, exact individual
  locations, media object keys, or internal moderation state.
- State-changing browser requests use the existing gateway BFF session and
  CSRF behavior.

## Verification

Expected automated coverage:

- duplicate visit by the same user increments once
- visits by two users increment twice
- duplicate like by the same user increments once
- unlike decrements once and is idempotent
- re-like after unlike increments once
- hidden listings reject engagement commands
- owner self-engagement counts once like any other authenticated account
- public search/detail responses include counts
- `engagement/me` returns only the current user's state
- frontend detail records an authenticated visit
- frontend like/unlike toggles count and state
- guest like prompts login/auth flow
- listing cards render counts without breaking image/layout states

Expected local checks:

```powershell
.\mvnw.cmd -pl product-service -am test
.\mvnw.cmd -pl api-gateway -am test
cd frontend
npm.cmd test -- --watch=false
npm.cmd run build
```

## Deferred

- Guest/device-based visit counting.
- Wishlist or saved-listing page.
- Ranking/search boosting from likes or visits.
- Recommendations and analytics dashboards.
- Notifications.
- Reviews, reputation, reports, and trade completion.
