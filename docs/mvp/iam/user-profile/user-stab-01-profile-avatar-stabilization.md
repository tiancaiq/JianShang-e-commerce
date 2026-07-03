# USER-STAB-01 Profile/Avatar Stabilization

Status: complete.

## Goal

Stabilize completed marketplace profile and avatar work before starting chat,
likes, reviews, notifications, or other user communication features.

This slice does not add feature behavior. It aligns documentation, verifies
the completed direct avatar upload flow, and records a teammate smoke
checklist.

## Scope

- Review `USER-01` through `USER-05` behavior.
- Align API contract docs with the signed avatar upload flow.
- Confirm profile save still works after avatar upload.
- Confirm app-owned avatar URLs render through the gateway in frontend image
  bindings.
- Preserve API contracts and database schema.

## Stabilized Behavior

Preferred avatar upload flow:

1. `POST /api/v1/users/me/avatar/upload-request`
2. Browser `PUT uploadUrl` with the selected image bytes and exact
   `Content-Type`
3. `POST /api/v1/users/me/avatar/confirm` with `If-Match`

Compatibility behavior:

- `POST /api/v1/users/me/avatar` multipart upload may remain available for
  older clients, but the marketplace UI should use the signed upload target
  flow.

Profile save behavior:

- `PATCH /api/v1/users/me` accepts `displayName`, `phone`, and `avatarUrl`.
- `avatarUrl` may be an `http` or `https` URL or the app-owned avatar URL:
  `/api/v1/public/user-avatars/{userId}?v={profileVersion}`.
- The app-owned avatar URL is stored in `users.avatar_url`; raw buckets,
  object keys, local paths, and signed URLs are not public profile data.

Frontend display behavior:

- `/account/profile` resolves app-owned avatar image URLs through the gateway
  before binding to `<img>`.
- The marketplace profile card resolves app-owned avatar image URLs through
  the gateway before binding to `<img>`.

## Manual Smoke Checklist

Use a marketplace account such as a local demo user.

1. Open `http://localhost:4200/account/profile`.
2. If redirected, sign in through the marketplace-native login flow.
3. Confirm profile fields load with display name, email, optional phone, and
   avatar state.
4. Choose a PNG, JPEG, or WebP image smaller than 5 MB.
5. Click `Upload`.
6. Confirm the network flow shows:
   - `POST /api/v1/users/me/avatar/upload-request`
   - `PUT` to the returned signed upload URL or local demo upload URL
   - `POST /api/v1/users/me/avatar/confirm`
   - `GET /api/v1/public/user-avatars/{userId}?v=...`
7. Confirm the avatar preview loads as an image, not broken alt text.
8. Change display name or phone and click `Save`.
9. Confirm `PATCH /api/v1/users/me` succeeds and does not return
   `400 VALIDATION_FAILED` for `avatarUrl`.
10. Navigate to `/account` and confirm the marketplace profile card avatar
    loads.
11. Return to `/account/profile`, click `Remove`, and confirm the initials
    fallback appears.
12. Log out and log back in. Confirm `/account/profile` still loads through
    the marketplace auth/session flow.

## GCS Troubleshooting Notes

- Browser CORS preflight must return `200` for `OPTIONS` with origin
  `http://localhost:4200`, request method `PUT`, and request header
  `content-type`.
- Bucket CORS must allow `PUT`, `OPTIONS`, and the `Content-Type` header.
- The upload request `Content-Type` must match the content type used when the
  signed URL was created.
- A remote signed URL upload should not include gateway cookies, CSRF headers,
  or unrelated custom headers.
- `SignatureDoesNotMatch` usually means the browser changed a signed header or
  used a stale signed URL.
- `400 VALIDATION_FAILED` for `avatarUrl` means the profile update validator
  does not match the app-owned avatar URL format.
- A broken avatar image in Angular dev mode usually means an app-owned
  `/api/...` URL was bound directly without prefixing the gateway origin.

## Verification

Focused regression coverage:

- Auth-service profile update accepts app-owned avatar URLs.
- Auth-service direct avatar upload request, local upload, confirm, public
  read, and delete flow.
- Frontend profile save preserves the internal avatar URL.
- Frontend avatar upload service uses upload-request, byte PUT, then confirm.
- Frontend profile and account card resolve app-owned avatar URLs for display.

Suggested commands:

```text
mvn -pl auth-service -am -Dtest=AuthServiceApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/account/profile.component.spec.ts --include src/app/features/account/user-profile-card.component.spec.ts --include src/app/core/services/user-profile.service.spec.ts
```

## Non-Goals

- No new database migration.
- No new profile fields.
- No avatar crop editor.
- No chat implementation.
- No likes, saved listings, reviews, ratings, notifications, address book,
  cart, checkout, payment, orders, or AI behavior.
