# USER-05 Avatar Image Upload

Status: complete.

## Goal

Allow a signed-in marketplace user to upload and remove their own avatar image
without exposing storage internals or changing the identity database schema.

## Implemented Behavior

- Added authenticated signed avatar upload:
  `POST /api/v1/users/me/avatar/upload-request`.
- Added direct byte upload to the returned `uploadUrl`.
- Added authenticated avatar confirmation:
  `POST /api/v1/users/me/avatar/confirm`.
- Kept authenticated multipart avatar upload as compatibility:
  `POST /api/v1/users/me/avatar`.
- Added authenticated avatar removal:
  `DELETE /api/v1/users/me/avatar`.
- Added public avatar delivery:
  `GET /api/v1/public/user-avatars/{userId}`.
- Avatar upload accepts PNG, JPEG, and WebP images up to 5 MB.
- Avatar bytes are stored through the same S3-compatible storage path used by
  listing media, which targets Google Cloud Storage in VM/demo environments.
- Profile updates continue to use optimistic locking through `If-Match`.
- The application stores only a safe app URL in `users.avatar_url`.
- The `/account/profile` UI now uses image choose/upload/remove controls
  instead of raw avatar URL editing.
- The marketplace frontend uploads avatar bytes directly to GCS when
  auth-service returns a signed remote upload URL.

## Preferred Upload Flow

The marketplace UI should use the direct-to-storage flow:

1. `POST /api/v1/users/me/avatar/upload-request` with content type, file name,
   and size.
2. `PUT uploadUrl` with the selected file bytes and the exact same
   `Content-Type`.
3. `POST /api/v1/users/me/avatar/confirm` with `If-Match`, object key,
   content type, and size.

The confirm step verifies the object exists and metadata matches before
updating `users.avatar_url`.

`POST /api/v1/users/me/avatar` remains a compatibility multipart route. New UI
work should prefer the signed upload URL flow.

## Storage

The auth service supports `s3` and `local-demo` avatar storage. VM/demo
deployments should use `s3` with the existing Google Cloud Storage compatible
settings:

```properties
USER_AVATAR_STORAGE=s3
S3_ENDPOINT_URL=https://storage.googleapis.com
S3_REGION=auto
S3_BUCKET=...
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
S3_PATH_STYLE_ACCESS=true
USER_AVATAR_MAX_SIZE_BYTES
```

`USER_AVATAR_STORAGE=s3` explicitly enables remote avatar storage. When
avatar-specific `USER_AVATAR_S3_*` variables are absent, auth-service reuses
the shared `S3_*` variables already used by listing media. Local demo mode
remains available with `USER_AVATAR_STORAGE=local-demo`.

The public API serves avatar bytes through the application instead of exposing
local paths, buckets, object keys, or signed storage URLs.

GCS/browser requirements:

- Bucket CORS must allow `PUT` and `OPTIONS` from the marketplace origin.
- Bucket CORS must allow the `Content-Type` request header.
- The browser PUT must send the same `Content-Type` used to sign the upload
  URL.
- Remote signed URL uploads must not include gateway cookies, CSRF headers, or
  unrelated custom headers.
- Expired signed URLs require requesting a new upload target.

## Stabilization Notes

`USER-STAB-01` verified that profile save still works after avatar upload.
`PATCH /api/v1/users/me` accepts the app-owned avatar URL:

```text
/api/v1/public/user-avatars/{userId}?v={profileVersion}
```

The frontend resolves app-owned avatar URLs through the gateway before binding
them to image tags in the profile page and marketplace profile card.

## Files Changed

- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/PublicAvatarController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AvatarUploadRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AvatarUploadConfirmRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/AvatarUploadResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/UpdateCurrentUserRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarStorageService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarStorageConfig.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarStorageProperties.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/LocalAvatarStorageService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/S3AvatarStorageService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarStorageSupport.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarContent.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AvatarNotFoundException.java`
- `auth-service/src/main/resources/application.properties`
- `frontend/src/app/core/services/user-profile.service.ts`
- `frontend/src/app/features/account/profile.component.ts`
- `frontend/src/app/features/account/user-profile-card.component.ts`

## Non-Goals

- No avatar crop editor.
- No moderation workflow.
- No image history.
- No database migration.
- No raw storage URLs in public profile responses.
- No reviews, ratings, likes, chat, notifications, cart, or order behavior.
