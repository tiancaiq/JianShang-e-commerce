# POSTSEARCH-STAB-P0-02 Auth, Session, Avatar, And Storage Security Audit

Status: complete.

## Goal

Audit the completed auth/session/profile/avatar/storage paths before starting
more user-to-user MVP work. This slice made no product behavior changes.

One cleanup was applied: a real-looking GCS S3-compatible access key pair in
the tracked local `.env` working tree was replaced with placeholder values.

## Files Reviewed

- `.env`
- `.env.example`
- `.env.demo.example`
- `docker-compose.yml`
- `docker-compose.demo.yml`
- `docker-compose.prod.yml`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/AuthBffController.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/NativeAuthService.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/AvatarUploadProxyController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/PublicAvatarController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/LocalAvatarStorageService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/S3AvatarStorageService.java`
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/user-profile.service.ts`
- profile/avatar frontend specs and docs

## Findings

### 1. Browser Token Boundary

Result: pass.

- Browser JavaScript does not receive OAuth access tokens, refresh tokens, ID
  tokens, or token type metadata.
- Angular API calls use cookies through `withCredentials` and do not set an
  `Authorization` header.
- Gateway native login/register exchanges credentials with Keycloak and stores
  OAuth tokens server-side through Spring Security authorized-client storage.
- Logout clears known legacy browser token keys from `localStorage` and
  `sessionStorage`.

Remaining note:

- `GET /auth/session` returns safe session identity fields and CSRF metadata.
  It does not return OAuth tokens.

### 2. CSRF Boundary

Result: pass.

- Gateway CSRF is enabled with `X-CSRF-TOKEN`.
- Angular adds the CSRF header only for state-changing gateway requests.
- Logout submits a POST form with the CSRF parameter.
- Auth-service is stateless JWT/resource-server protected and keeps CSRF
  disabled because browser requests go through the gateway BFF.
- The business verification webhook remains explicitly excluded from gateway
  CSRF because it uses provider signature validation instead of browser
  session authentication.

### 3. Avatar Upload Boundary

Result: pass.

- Upload-request and confirm endpoints require the authenticated current user.
- Remote signed avatar upload uses direct `PUT {uploadUrl}` with only the
  selected image `Content-Type`; Angular sets `withCredentials: false` for
  remote signed URLs.
- Local demo app-relative upload URLs go through the gateway and keep normal
  session/CSRF behavior.
- Confirm verifies current profile version, object key ownership, object
  existence, expected content type, and expected size before storing the
  app-owned public avatar URL.
- The app-owned avatar URL format remains accepted by `PATCH /users/me`.

Remaining note:

- Authenticated upload-request responses intentionally include bucket/key and
  signed upload URL as temporary upload metadata. Those values are not exposed
  by public avatar delivery or public identity labels.

### 4. Public Avatar And Identity Data

Result: pass.

- Public avatar delivery returns image bytes through
  `/api/v1/public/user-avatars/{userId}`.
- Public avatar delivery checks that the user exists, is active, and has an
  avatar URL before reading storage.
- Public avatar responses do not expose storage bucket, object key, raw signed
  URL, local file path, email, phone, Keycloak subject, roles, or account
  status.
- Public seller labels return only public IDs, display names, optional avatar
  URL, and business legal names. They omit email, phone, Keycloak subject,
  roles, verification flags, and private contact metadata.

### 5. Environment And Secret Hygiene

Result: one working-tree issue fixed.

- The current local `.env` working tree contained a real-looking GCS
  S3-compatible access key pair.
- This slice replaced those values with placeholder values.
- A redacted check of `HEAD:.env` showed placeholders for the same S3
  variables, so this local check did not find the key pair in committed
  history.
- `.env.example`, `.env.demo.example`, compose files, and application
  properties now contain placeholders, environment references, or non-secret
  config paths for the scanned secret-like keys.

Required manual follow-up:

- Revoke or rotate the exposed GCS/S3-compatible key pair in Google Cloud if it
  was active. Even though this local check did not find it in committed
  history, it existed in the tracked working tree and may have been used in a
  demo environment.

False positives from the redacted scan:

- `client-authentication-method` entries are configuration names, not secrets.
- `AWS_SECRETS_PREFIX` is a path prefix, not a secret value.
- Token URI properties are endpoint URLs, not token values.

## Commands Run

Security scans:

```powershell
rg -n "access_token|refresh_token|id_token|TokenResponse|setAttribute|OAuth2AuthorizedClient|csrf|X-CSRF|Cookie|credentials|withCredentials|uploadUrl|avatar|public/user-avatars|objectBucket|objectKey|secret|password|Authorization" api-gateway auth-service frontend/src/app
rg -n "ACCESS_KEY|SECRET|TOKEN|GOOG|GCS|S3|storage.googleapis.com|avatar|csrf|refresh|id_token|access_token|PASSWORD|CLIENT" .env .env.example .env.demo.example docker-compose*.yml docs api-gateway auth-service frontend/src/app
rg -n "localStorage|sessionStorage|accessToken|refreshToken|idToken|Authorization|Bearer|withCredentials|X-CSRF-TOKEN|csrf" frontend/src/app/core frontend/src/app/features/account api-gateway/src/main/java auth-service/src/main/java
rg -n "objectBucket|objectKey|uploadUrl|public/user-avatars|storage.googleapis.com|S3_ACCESS_KEY|S3_SECRET|KEYCLOAK_GOOGLE_CLIENT_SECRET|GOOGLE_CLIENT_SECRET" .env .env.example .env.demo.example docker-compose.yml docker-compose.demo.yml docker-compose.prod.yml docs/mvp api-gateway auth-service frontend/src/app
```

Redacted env classification was also run against `.env`, examples, compose
files, gateway properties, and auth-service properties without printing secret
values.

Committed `.env` comparison:

```powershell
git show HEAD:.env
```

The comparison classified committed S3 access-key variables as placeholders.
The actual values were not copied into this note.

## Verification

Backend:

```powershell
C:\Users\b\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd -pl api-gateway,auth-service -am test
```

Result: passed.

- `common-core`: success
- `common-web`: success
- `common-testing`: success
- `api-gateway`: success
- `auth-service`: success

Frontend:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include src/app/features/account/profile.component.spec.ts --include src/app/features/account/user-profile-card.component.spec.ts --include src/app/core/services/auth.service.spec.ts --include src/app/core/services/user-profile.service.spec.ts --include src/app/core/interceptors/auth.interceptor.spec.ts
```

Result: passed.

- `TOTAL: 32 SUCCESS`
- Output included expected test-server `404` warnings for mocked public avatar
  image URLs. They did not fail the suite.

## Acceptance Criteria

- No real shared secrets are committed or documented as defaults: pass after
  replacing local `.env` S3 credential values with placeholders.
- Auth/session/token boundaries still match ADR-0001 and API contract: pass.
- Avatar upload and public delivery remain app-owned and private-data safe:
  pass.
- No new profile fields, identity provider behavior, or storage provider
  behavior added: pass.

## Deferred Follow-Up

- Rotate/revoke the exposed GCS S3-compatible key pair outside the repository.
- Keep `POSTSEARCH-STAB-P1-05` for shared listing/avatar storage plumbing; do
  not move domain authorization or persistence rules into shared code.
