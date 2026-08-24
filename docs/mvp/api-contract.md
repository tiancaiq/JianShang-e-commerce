# Product API Contract

## 0. Release Boundary

MVP contracts cover identity/accounts, seller/store profiles, listings/media,
search/storefront, basic text chat, and basic business/listing moderation.

V2 contracts cover cart, inventory, checkout/payment, orders/shipping, and
notifications.

V3 contracts cover individual trade completion/reputation, reviews, advanced
admin/trust operations, and AI.

During Phase 1 setup, no feature endpoint in this document should be
implemented.

## 1. Conventions

Base path:

```text
/api/v1
```

Content type:

```text
application/json
```

Service authentication:

```text
Authorization: Bearer <access-token>
```

First-party browser authentication follows ADR-0001: the gateway BFF owns the
OIDC login session, browser JavaScript receives no access or refresh token,
and state-changing browser requests send the CSRF token returned by
`GET /api/v1/auth/session`.

Required headers where applicable:

```text
X-Correlation-Id: optional client value; generated when absent
Idempotency-Key: required for payment, reservation, checkout, order, and retryable commands
If-Match: aggregate version for protected updates
```

Response IDs are opaque strings. Clients must not infer type or sequence.

Money:

```json
{"amount": "19.99", "currency": "USD"}
```

Timestamps use ISO 8601 UTC.

The standard money, pagination, error, correlation, authenticated-principal,
and event-envelope types should be supplied by the corresponding shared
backend/frontend libraries. Shared types define transport conventions only;
they do not expose service-owned domain entities.

## 2. Standard Envelopes

Single resource:

```json
{"data": {}}
```

Collection:

```json
{
  "data": [],
  "page": {"nextCursor": "opaque-or-null", "hasMore": false}
}
```

Error:

```json
{
  "error": {
    "code": "LISTING_NOT_AVAILABLE",
    "message": "The listing is no longer available.",
    "fieldErrors": [{"field": "quantity", "code": "OUT_OF_RANGE"}],
    "correlationId": "id"
  }
}
```

Current MVP implementation note (`STAB-P1-03`):

- Identity/auth-service endpoints currently return the single-resource
  `{"data": ...}` envelope.
- Product/listing endpoints currently return raw resources or arrays.
- Angular core services unwrap enveloped responses before component code sees
  them. Components should consume domain objects and collections directly,
  not transport envelopes.
- Changing product/listing endpoints to the standard envelope is deferred to a
  separate contract-change slice with backend and frontend tests.

Expected status codes:

- `200` read/update success
- `201` create success
- `202` asynchronous operation accepted
- `204` delete/no body
- `400` malformed or invalid request
- `401` unauthenticated
- `403` unauthorized for resource
- `404` resource not found or intentionally hidden
- `409` state/version/idempotency conflict
- `422` valid syntax but business rule failure
- `429` rate limited
- `503` temporary dependency failure

## 3. Identity and Profile

### `GET /auth/register` (`SIGNUP-01`)

Starts Keycloak-hosted self-registration through the gateway BFF. The gateway
stores no credentials, accepts no password JSON, and returns no access token,
refresh token, ID token, or token type metadata.

Query:

```text
client=marketplace|seller-portal|admin-portal
returnUrl=/safe/relative/path
mode=popup
```

Rules:

- `client` defaults to `marketplace`.
- Unknown clients return `404`.
- `returnUrl` must be a safe same-site relative path; unsafe values are
  ignored.
- `mode=popup` uses the same OIDC flow but returns a popup completion page
  after successful authentication.
- The browser is redirected into the OIDC authorization flow with Keycloak's
  registration action.
- After registration/authentication, the gateway callback creates the BFF
  session and returns to the safe destination.
- The application-owned identity row is created or returned by the next
  `GET /users/me` call.

### Email verification (`SIGNUP-01`)

Email verification is Keycloak-owned. Application services do not accept
verification tokens or store verification secrets.

### `GET /auth/login` (`IAM-02`)

Starts the gateway BFF OIDC login redirect. The gateway, not browser
JavaScript, exchanges the authorization code and stores OAuth tokens
server-side.

Query:

- `client=marketplace|seller-portal|admin-portal`
- `returnUrl=/safe/relative/path`
- `mode=popup`
- `provider=google`

The endpoint does not accept credentials and does not return access or refresh
tokens. When `mode=popup` is present, a successful OAuth callback returns a
small popup completion page that notifies the marketplace window to refresh
its BFF session.

When `provider=google` is present, the gateway adds Keycloak's
`kc_idp_hint=google` authorization parameter after validating the provider
allowlist. Angular still talks only to the gateway BFF; Google OAuth is
completed through Keycloak identity brokering.

### `GET /auth/session` (`IAM-02`)

Returns authenticated state, a safe Keycloak subject/user summary when signed
in, and CSRF metadata for browser mutations. It never returns access tokens,
refresh tokens, ID tokens, or token type metadata.

### `POST /auth/native/login` (`LOGIN-02`)

Marketplace-native email/password sign-in through the gateway BFF. This keeps
the user inside the marketplace UI while Keycloak remains the credential and
token authority.

Headers:

```text
X-CSRF-TOKEN: value from GET /auth/session
```

Request:

```json
{
  "email": "user@example.com",
  "password": "secret"
}
```

Response:

```json
{
  "authenticated": true,
  "user": {
    "subject": "keycloak-sub",
    "email": "user@example.com",
    "displayName": "Alex Buyer",
    "roles": ["BUYER"],
    "expiresAt": "2026-06-16T12:00:00Z"
  },
  "csrf": {
    "headerName": "X-CSRF-TOKEN",
    "parameterName": "_csrf",
    "token": "rotated-or-current-token"
  }
}
```

Rules:

- Marketplace browser JavaScript posts credentials only to the gateway.
- The gateway exchanges credentials with Keycloak and stores OAuth tokens
  server-side.
- The response never returns access tokens, refresh tokens, ID tokens, or
  token type metadata.
- Invalid credentials return `401 INVALID_CREDENTIALS`.

### `POST /auth/native/register` (`SIGNUP-04`)

Marketplace-native account creation through the gateway BFF. The gateway uses
a Keycloak service account to create the identity-provider user, then signs
the user into the same BFF session.

Headers:

```text
X-CSRF-TOKEN: value from GET /auth/session
```

Request:

```json
{
  "displayName": "Alex Buyer",
  "email": "user@example.com",
  "password": "secret"
}
```

Response: same shape as `POST /auth/native/login`.

Rules:

- Passwords are never stored in application service databases.
- Duplicate emails return `409 EMAIL_ALREADY_REGISTERED`.
- Google and other external identity providers remain redirect/popup provider
  flows because external consent cannot be completed entirely inside the app.

### `POST /auth/logout` (`IAM-02`)

Invalidates the gateway browser session and delegates OIDC logout/revocation
to Keycloak when supported. Logout is idempotent and requires CSRF protection
for browser callers.

Form/query parameters:

- `client=marketplace|seller-portal|admin-portal` optional logout surface
  hint.

Rules:

- The gateway clears the BFF HTTP session and server-side OAuth authorized
  client regardless of which first-party client created the session.
- Unknown logout client hints are ignored and fall back to the authenticated
  OIDC client registration or marketplace.
- Marketplace logout returns to the configured marketplace logout URI with
  `signedOut=1`.
- Seller portal logout returns to
  `/login?client=seller-portal&signedOut=1`.
- Admin portal logout returns to
  `/login?client=admin-portal&signedOut=1`.
- Login pages and marketplace shell may display "Signed out successfully"
  when `signedOut=1` is present, then remove the confirmation query from the
  active browser URL where possible.

### `GET /users/me` (`IAM-03`)

Creates or returns the application-owned identity user mapped to the
authenticated Keycloak `sub`.

Authorization: authenticated Keycloak subject, relayed by the gateway BFF or
validated directly by the service as a resource server.

Request: no body. The service ignores client-submitted user IDs, subject
headers, role headers, email headers, and status fields.

Response:

```json
{
  "data": {
    "id": "01J...",
    "keycloakSub": "provider-subject",
    "email": "user@example.com",
    "emailVerified": true,
    "displayName": "Alex",
    "phone": null,
    "phoneVerified": false,
    "avatarUrl": null,
    "status": "ACTIVE",
    "version": 0,
    "createdAt": "2026-06-16T03:15:00Z",
    "updatedAt": "2026-06-16T03:15:00Z"
  }
}
```

The response never returns passwords, OAuth access tokens, refresh tokens,
ID tokens, MFA secrets, or Keycloak session data.

### `POST /auth/password-recovery`, `POST /auth/password-reset`

Deferred to Keycloak-managed account recovery. Not implemented by the
application service in IAM-03.

### `PATCH /users/me` (`IAM-06`)

Patch supports display name, phone, and avatar only. The request must be made
as the authenticated user; client-supplied user IDs, subjects, roles, account
status, email, and verification flags are ignored or rejected.

Headers:

```text
If-Match: 0
```

`If-Match` is the current `version` from `GET /users/me`. A stale value returns
`409 VERSION_CONFLICT`.

Request:

```json
{
  "displayName": "Alex",
  "phone": "+19495551234",
  "avatarUrl": "https://example.com/avatar.png"
}
```

Rules:

- `displayName`: nullable, trimmed, max 200 characters, blank rejected.
- `phone`: nullable, E.164 format only, verification remains false until a
  later verification flow.
- `avatarUrl`: nullable, `http` or `https` URL only, or the app-owned
  `/api/v1/public/user-avatars/{userId}` URL produced by avatar upload, max
  2048 characters.
- Email changes are deferred to a later reverification flow.

Response:

```json
{
  "data": {
    "id": "01J...",
    "keycloakSub": "provider-subject",
    "email": "user@example.com",
    "emailVerified": true,
    "displayName": "Alex",
    "phone": "+19495551234",
    "phoneVerified": false,
    "avatarUrl": "https://example.com/avatar.png",
    "status": "ACTIVE",
    "version": 1,
    "createdAt": "2026-06-16T03:15:00Z",
    "updatedAt": "2026-06-16T03:20:00Z"
  }
}
```

### Avatar upload (`USER-05`)

```text
POST   /users/me/avatar/upload-request
PUT    {uploadUrl}
POST   /users/me/avatar/confirm
DELETE /users/me/avatar
GET    /public/user-avatars/{userId}
```

The preferred marketplace avatar flow mirrors listing media upload:

1. The frontend requests an upload target from auth-service.
2. The frontend uploads bytes directly to the returned `uploadUrl`.
3. The frontend confirms the uploaded object with auth-service.

#### Request upload target

`POST /users/me/avatar/upload-request`

Request:

```json
{
  "contentType": "image/png",
  "fileName": "avatar.png",
  "sizeBytes": 12345
}
```

Response:

```json
{
  "data": {
    "objectBucket": "msb-media",
    "objectKey": "users/01J.../avatar.png",
    "contentType": "image/png",
    "sizeBytes": 12345,
    "uploadMethod": "PUT",
    "uploadUrl": "https://storage.googleapis.com/..."
  }
}
```

Rules:

- Requires the authenticated user.
- Accepts only `image/png`, `image/jpeg`, and `image/webp`.
- Rejects empty files and files larger than the configured avatar limit
  (5 MB by default).
- For remote storage, `uploadUrl` is a short-lived signed PUT URL for the
  auth-service owned avatar object.
- For local demo storage, `uploadUrl` may be an app-relative gateway URL.
- The browser must send the same `Content-Type` used to create the upload
  target.
- The returned object key and bucket are storage metadata for confirmation,
  not public profile data.

#### Upload bytes

`PUT {uploadUrl}`

Headers:

```text
Content-Type: image/png
```

The signed URL has required headers baked into its signature. The frontend
must not add credentials, CSRF headers, or unrelated custom headers when
uploading to a remote signed URL. Local demo app-relative upload URLs still
go through the gateway and use the normal browser session.

#### Confirm upload

`POST /users/me/avatar/confirm`

Headers:

```text
If-Match: 0
```

Request:

```json
{
  "objectKey": "users/01J.../avatar.png",
  "contentType": "image/png",
  "sizeBytes": 12345
}
```

Rules:

- Requires the authenticated user.
- Requires the current profile version through `If-Match`.
- Verifies the uploaded object exists in storage.
- Verifies object size and content type match the upload request.
- Stores avatar bytes in auth-service owned S3-compatible storage. VM/demo
  deployments target Google Cloud Storage through its S3-compatible XML API.
- Updates `users.avatar_url` to an app-owned URL:
  `/api/v1/public/user-avatars/{userId}?v={profileVersion}`.
- Does not expose local file paths, object keys, buckets, or raw storage URLs
  in public profile responses.

`DELETE /users/me/avatar` clears the authenticated user's avatar URL and
best-effort removes stored avatar bytes. It also requires `If-Match`.

`GET /public/user-avatars/{userId}` serves public avatar bytes for active users
who have an avatar URL. Missing, suspended, closed, or avatarless users return
`404 AVATAR_NOT_FOUND`.

`POST /users/me/avatar` multipart upload may remain available for backward
compatibility, but the marketplace UI should use the signed upload target
flow above.

Google Cloud Storage requirements for VM/demo:

- Bucket CORS allows `PUT` and `OPTIONS` from the marketplace origin such as
  `http://localhost:4200`.
- Bucket CORS allows the `Content-Type` request header.
- Auth-service and product-service storage configuration must point at the
  intended bucket, endpoint, region, and credentials.
- Signed URLs are short-lived; expired URLs require a new upload-request.

### Address APIs (`IAM-05`)

```text
GET    /users/me/addresses
POST   /users/me/addresses
PATCH  /users/me/addresses/{addressId}
DELETE /users/me/addresses/{addressId}
POST   /users/me/addresses/{addressId}/default
```

These routes derive ownership from the authenticated user. Request bodies
contain recipient and address fields only; they cannot replace user ID,
default state, version, timestamps, or internal IDs.

The collection is capped at 20 and returns default first. The first address
becomes default automatically. PATCH, DELETE, and set-default require
`If-Match` with the current address version. PATCH uses presence-aware merge
semantics: omitted fields remain unchanged, while nullable `label` and `line2`
can be cleared with explicit null.

Create request:

```json
{
  "label": "Home",
  "recipientName": "Alex Buyer",
  "phone": "+19495550123",
  "line1": "100 Main Street",
  "line2": "Apt 4",
  "city": "Irvine",
  "region": "CA",
  "postalCode": "92618",
  "countryCode": "US"
}
```

Address response:

```json
{
  "id": "01...",
  "label": "Home",
  "recipientName": "Alex Buyer",
  "phone": "+19495550123",
  "line1": "100 Main Street",
  "line2": "Apt 4",
  "city": "Irvine",
  "region": "CA",
  "postalCode": "92618",
  "countryCode": "US",
  "isDefault": true,
  "version": 0,
  "createdAt": "2026-07-18T10:00:00Z",
  "updatedAt": "2026-07-18T10:00:00Z"
}
```

Deleting the default promotes the oldest remaining address in the same
transaction. Deleting the last address leaves the book empty. Address-book
rows are hard-deleted because checkout and orders retain immutable snapshots
instead of foreign keys.

Trusted checkout resolution:

```text
GET /internal/users/{buyerId}/addresses/{addressId}
```

The internal route requires `X-Internal-Service-Token`, is not gateway-routed,
and returns the address only when the address belongs to the supplied active
buyer. Missing, wrong-owner, and inactive-buyer cases return the same
`404 BUYER_ADDRESS_NOT_FOUND`. V2-CHK-01 derives `buyerId` from its
authenticated actor and copies the response into a checkout snapshot.

Detailed validation, default, privacy, concurrency, and error rules are
defined in
`docs/v2/commerce/v2-iam-01-buyer-address-book-plan.md`.

## 4. Individual Seller

### `POST /individual-seller/activation` (`IND-01`)

Request:

```json
{
  "publicCity": "Irvine",
  "publicRegion": "CA",
  "termsVersion": "2026-01"
}
```

Rules:

- Requires an authenticated user.
- Creates one active individual seller profile for the current user.
- Grants the local `INDIVIDUAL_SELLER` role.
- Stores only public city and region; exact address, meeting location,
  replacement user ID, status, and role fields are rejected.
- `termsVersion` must match the current individual-selling terms.
- The UI must disclose that individual payment and delivery are arranged
  off-platform and are not verified or protected by the platform.

Response `201`:

```json
{
  "data": {
    "id": "01JY...",
    "userId": "01JY...",
    "publicCity": "Irvine",
    "publicRegion": "CA",
    "status": "ACTIVE",
    "completedSalesCount": 0,
    "termsVersion": "2026-01",
    "version": 0,
    "createdAt": "2026-06-16T12:00:00Z",
    "updatedAt": "2026-06-16T12:00:00Z"
  }
}
```

Duplicate activation returns `409 INDIVIDUAL_SELLER_ALREADY_ACTIVE`.

### `GET /individual-seller/me`

Returns the authenticated user's individual seller profile and reputation
summary.

Response `200`:

```json
{
  "data": {
    "id": "01JY...",
    "userId": "01JY...",
    "publicCity": "Irvine",
    "publicRegion": "CA",
    "status": "ACTIVE",
    "completedSalesCount": 0,
    "termsVersion": "2026-01",
    "version": 0,
    "createdAt": "2026-06-16T12:00:00Z",
    "updatedAt": "2026-06-16T12:00:00Z"
  }
}
```

Authenticated users without an individual seller profile receive
`404 INDIVIDUAL_SELLER_NOT_FOUND`. Unauthenticated requests receive `401`.

## 5. Business and Store

### Business application APIs

```text
POST  /business-applications                    BUS-01
GET   /business-applications/me                 BUS-01
GET   /business-applications/{id}
PATCH /business-applications/{id}               BUS-01
POST  /business-applications/{id}/submit        BUS-02
POST  /webhooks/business-verification           BUS-03
```

`POST /business-applications` creates a draft application owned by the
authenticated applicant. The applicant is the proposed business owner.

Request:

```json
{
  "legalName": "Acme Trading LLC",
  "businessType": "LLC",
  "country": "US",
  "contactEmail": "owner@example.com",
  "contactPhone": "+19495551234",
  "publicCity": "Irvine",
  "publicRegion": "CA",
  "websiteUrl": "https://example.com",
  "description": "Local marketplace seller"
}
```

Response `201`:

```json
{
  "data": {
    "id": "01JY...",
    "applicantUserId": "01JY...",
    "legalName": "Acme Trading LLC",
    "businessType": "LLC",
    "country": "US",
    "contactEmail": "owner@example.com",
    "contactPhone": "+19495551234",
    "publicCity": "Irvine",
    "publicRegion": "CA",
    "websiteUrl": "https://example.com",
    "description": "Local marketplace seller",
    "status": "DRAFT",
    "submittedAt": null,
    "version": 0,
    "createdAt": "2026-06-16T12:00:00Z",
    "updatedAt": "2026-06-16T12:00:00Z"
  }
}
```

Rules:

- Requires an authenticated user.
- Client cannot supply applicant user, status, submitted time, approval state,
  reviewer, business ID, membership, or verification result fields.
- Only one non-rejected business application or approved business account per
  applicant is allowed. A rejected applicant may start a new application.
- `GET /business-applications/me` returns the applicant's current non-rejected
  application/account when one exists. If only rejected applications exist, it
  returns the latest rejected application with the decision reason. If the
  applicant has never applied, `data` is `null`.
- `GET /business-applications/{id}` returns only applications owned by the
  authenticated applicant; otherwise return `404 BUSINESS_APPLICATION_NOT_FOUND`.
- `PATCH /business-applications/{id}` updates only owned `DRAFT`
  applications and requires `If-Match` with the current version.
- Stale updates return `409 VERSION_CONFLICT`.

Submit requires expected application version.

`POST /business-applications/{id}/submit` (`BUS-02`) submits an owned draft
application for verification/review.

Headers:

```text
If-Match: 0
```

Rules:

- Requires an authenticated applicant.
- The application must belong to the authenticated applicant.
- The application must currently be `DRAFT`.
- Required draft fields are revalidated before submit.
- On success, status becomes `PENDING_VERIFICATION` and `submittedAt` is set.
- Stale versions return `409 VERSION_CONFLICT`.
- Non-owned IDs return `404 BUSINESS_APPLICATION_NOT_FOUND`.
- Admin decision, business creation, owner membership, and external
  verification callbacks are handled by BUS-03/BUS-04; store creation remains
  deferred.

Response `200`:

```json
{
  "data": {
    "id": "01JY...",
    "status": "PENDING_VERIFICATION",
    "submittedAt": "2026-06-16T13:00:00Z",
    "version": 1
  }
}
```

`POST /webhooks/business-verification` (`BUS-03`) accepts signed verification
provider callbacks. MVP may still use manual review, but this endpoint records
provider outcomes when configured.

Headers:

```text
X-MSB-Signature: sha256=<hex hmac sha256 over raw request body>
```

Request:

```json
{
  "eventId": "provider-event-001",
  "applicationId": "01JY...",
  "outcome": "UNDER_REVIEW|VERIFICATION_FAILED",
  "reason": "Provider accepted packet"
}
```

Rules:

- Does not use browser/session authentication.
- Requires a valid HMAC signature using the configured webhook secret.
- `eventId` is unique; duplicate callbacks return the current application
  without applying a second effect.
- For `PENDING_VERIFICATION` applications, `UNDER_REVIEW` or
  `VERIFICATION_FAILED` is applied.
- Every accepted provider callback is appended to
  `business_verification_events`.
- Invalid signatures return `403 FORBIDDEN`.

### Effective admin session (`ADM-SEC-01`)

```text
GET /admin/me
```

The gateway exposes this as `GET /api/v1/admin/me`. Keycloak authentication
establishes the subject; Auth Service resolves that subject to the
application-owned user, active account state, persisted admin roles, and
effective role permissions.

Response `200`:

```json
{
  "data": {
    "userId": "01JY...",
    "role": "PLATFORM_ADMIN",
    "roles": ["SUPER_ADMIN"],
    "permissions": [
      "admin.dashboard.read",
      "admin.audit.read",
      "admin.business.application.read",
      "admin.business.application.decide",
      "admin.listing.moderation.read",
      "admin.listing.moderation.claim",
      "admin.listing.moderation.resolve",
      "admin.listing.edit",
      "admin.listing.remove"
    ],
    "accountState": "ACTIVE"
  }
}
```

`role` is retained as the legacy compatibility discriminator. New clients use
the ordered `roles` and `permissions` collections. The response never includes
identity-provider tokens or arbitrary claims. Missing admin assignment or an
inactive account returns `403`; command endpoints independently enforce their
required permission and derive the actor from the authenticated principal.

Current endpoint permission mapping:

| Permission | Existing operations |
|---|---|
| `admin.dashboard.read` | Dashboard summary |
| `admin.audit.read` | Business/listing workflow timelines and identity labels |
| `admin.business.application.read` | Business application queue and detail |
| `admin.business.application.decide` | Business application decision |
| `admin.listing.moderation.read` | Listing moderation queue and case/detail reads |
| `admin.listing.moderation.claim` | Listing case claim and release |
| `admin.listing.moderation.resolve` | Listing case resolution and compatible direct decision |
| `admin.listing.edit` | Active approved listing edit |
| `admin.listing.remove` | Active listing removal |
| `admin.user.read` | User administration search and detail |
| `admin.user.restrict` | Restrict selected operational user capabilities |
| `admin.user.suspend` | Suspend selected operational user capabilities |
| `admin.user.ban` | Apply the user marketplace-ban policy profile |
| `admin.user.reinstate` | Revoke one user enforcement action |
| `admin.user.pii.read` | Read full user email and use email search |
| `admin.business.read` | Active and historical business administration reads |
| `admin.business.restrict` | Restrict selected operational business capabilities |
| `admin.business.suspend` | Suspend selected operational business capabilities |
| `admin.business.ban` | Apply the business marketplace-ban policy profile |
| `admin.business.reinstate` | Revoke one business enforcement action |
| `admin.listing.suspend` | Restrict or suspend listing capabilities |
| `admin.listing.reinstate` | Revoke one listing enforcement action |

Frontend permission checks control navigation and action presentation only;
the backend permission check remains authoritative. Listing ownership,
case-assignment, resolved-state, and `If-Match` rules still apply after the
permission check.

### Internal enforcement contracts (`ADM-ENF-00`)

Auth Service provides package/application-service create, revoke, evaluate,
and timeline contracts for `USER` and `BUSINESS`; Product Service provides the
equivalent contracts for `LISTING`. Create includes explicit scopes, reason,
optional case/times, expected target version, idempotency key, allow-listed
metadata, and `dryRun`. Revoke includes action ID, expected action version,
reason, idempotency key, metadata, and `dryRun`. Results include lifecycle,
version, times, scopes, effective restrictions, correlation ID, and the dry-run
marker. Actor/source/request identity is trusted server context, never client
input.

ADM-ENF-00 itself deliberately introduced no
`/api/v1/admin/.../enforcements` mutation endpoint. The completed user,
business, and listing target verticals now expose the routes documented below
because each also implements the corresponding reversible runtime behavior.
The same idempotency key plus canonical payload replays the original result; a
changed payload is a `409` conflict. Stale target/action versions are `409` and
are not retried. The canonical contract is
`docs/mvp/adm/admin-enforcement-model.md`.

### Admin user control (`ADM-USER-01/02`)

```text
GET  /api/v1/admin/users
GET  /api/v1/admin/users/{userId}
GET  /api/v1/admin/users/{userId}/timeline
POST /api/v1/admin/users/{userId}/enforcements/dry-run
POST /api/v1/admin/users/{userId}/enforcements
POST /api/v1/admin/users/{userId}/enforcements/{enforcementId}/revoke/dry-run
POST /api/v1/admin/users/{userId}/enforcements/{enforcementId}/revoke
GET  /api/v1/users/me/marketplace-capabilities
```

Search supports `q`, `enforcementState`, operational `scope`, `createdFrom`,
`createdTo`, `page`, bounded `size`, and an allow-listed stable `sort`. It
requires `admin.user.read`. Without `admin.user.pii.read`, responses contain a
server-generated masked email and a query containing `@` is rejected; the
frontend never receives a full email to hide.

Detail keeps authentication, account type, seller state, business membership,
platform-admin state, and marketplace enforcement separate. The timeline
requires `admin.audit.read`. Create action permission is selected by action
type (`admin.user.restrict`, `.suspend`, or `.ban`); revocation requires
`admin.user.reinstate`. Dry-run requests omit an idempotency key and do not
write. Confirmed requests require the expected target/action version and a
stable idempotency key. `BAN` is a marketplace ban whose stored scopes are
server-expanded to `USER_BUYING` plus `USER_SELLING`.

The safe current-user response reports buying and selling availability plus
only the applicable action, effective/expiration times, and support reference.
It does not expose actor, reason, case, request/correlation internals, metadata,
or another user's action.

The internal decision contract is not Gateway-routed:

```text
POST /api/v1/internal/users/capabilities/evaluate
X-Internal-Service-Token: <configured service token>

{"userId":"01...","scopes":["USER_BUYING","USER_SELLING"]}
```

Its response contains one allow/deny decision per requested operational scope
with effective action, action ID, effective/expiration times, and a safe
support reference. It contains no PII or enforcement reason. Consumers forward
the correlation ID, treat an explicit denial as
`403 USER_CAPABILITY_RESTRICTED`, and treat an unavailable or malformed
decision as `503 ENFORCEMENT_DECISION_UNAVAILABLE`.

### Admin business application queue (`ADM-BUS-01`)

```text
GET /admin/business-applications
```

Query:

```text
status=PENDING_VERIFICATION|UNDER_REVIEW
```

Rules:

- Requires `admin.business.application.read`.
- When `status` is omitted, returns applications in `PENDING_VERIFICATION`
  and `UNDER_REVIEW`.
- Results are ordered by oldest `submittedAt` first.
- Returns business application fields already safe for platform admin review.

Response `200`:

```json
{
  "data": [
    {
      "id": "01JY...",
      "applicantUserId": "01JY...",
      "legalName": "Acme Trading LLC",
      "businessType": "LLC",
      "country": "US",
      "contactEmail": "owner@example.com",
      "status": "PENDING_VERIFICATION",
      "submittedAt": "2026-06-16T14:00:00Z",
      "version": 1
    }
  ]
}
```

### Admin business application detail (`ADM-BUS-02`)

```text
GET /admin/business-applications/{id}
GET /admin/business-applications/{id}/timeline
```

Rules:

- Both endpoints require `admin.business.application.read`; the timeline also
  requires `admin.audit.read`.
- Does not require applicant ownership.
- Returns the full business application review record visible to platform
  staff, including status, version, submission time, reviewer metadata,
  decision reason, and approved business ID when present.

Response `200`:

```json
{
  "data": {
    "id": "01JY...",
    "applicantUserId": "01JY...",
    "legalName": "Acme Trading LLC",
    "businessType": "LLC",
    "country": "US",
    "contactEmail": "owner@example.com",
    "contactPhone": "+19495551234",
    "publicCity": "Irvine",
    "publicRegion": "CA",
    "websiteUrl": "https://example.com",
    "description": "Local seller",
    "status": "PENDING_VERIFICATION",
    "submittedAt": "2026-06-16T14:00:00Z",
    "reviewerUserId": null,
    "approvedBusinessId": null,
    "decisionReason": null,
    "decidedAt": null,
    "version": 1
  }
}
```

The timeline endpoint verifies the required read/audit permissions and the
application exists, and returns oldest-first append-only submission, provider,
and admin decision events. Historical rows created before submission events
were persisted receive a derived submission entry without rewriting history.

Business and listing timeline endpoints share this normalized entry shape:

```json
{
  "data": [
    {
      "eventId": "01JY...",
      "occurredAt": "2026-08-12T10:00:00Z",
      "eventType": "BUSINESS_APPLICATION_DECISION",
      "actorType": "PLATFORM_ADMIN",
      "actorId": "01JY...",
      "actorDisplay": "Avery Stone",
      "source": "HUMAN_ADMIN",
      "targetType": "BUSINESS_APPLICATION",
      "targetId": "01JY...",
      "moderationCaseId": null,
      "previousState": "UNDER_REVIEW",
      "newState": "APPROVED",
      "reason": "Business information verified",
      "correlationId": "01JY...",
      "metadata": { "outcome": "APPROVED" }
    }
  ]
}
```

Unavailable legacy fields are `null`; `metadata` contains only safe,
workflow-specific scalar values. Actor identity is derived from the
authenticated backend principal and is never accepted from the request.

### Admin decision (`BUS-04`)

```text
POST /admin/business-applications/{id}/decision
If-Match: 1
```

Request:

```json
{"decision": "APPROVE|REJECT|REQUEST_INFORMATION", "reason": "text"}
```

Rules:

- Requires `admin.business.application.decide`.
- Requires `If-Match` with the current business application `version`.
- `reason` is required for every decision.
- The application must be `PENDING_VERIFICATION` or `UNDER_REVIEW`.
- Stale versions return `409 VERSION_CONFLICT`.
- `APPROVE` changes application status to `APPROVED`, creates an active
  `businesses` row, and creates an active `OWNER` membership for the applicant.
- `REJECT` changes application status to `REJECTED`.
- `REQUEST_INFORMATION` changes application status to
  `INFORMATION_REQUESTED`.
- The reviewer, decision reason, decision time, and approved business ID when
  applicable are visible through `GET /business-applications/{id}` for the
  applicant.
- Every admin decision is appended to `business_verification_events`.

Response `200`:

```json
{
  "data": {
    "id": "01JY...",
    "status": "APPROVED",
    "reviewerUserId": "01JY...",
    "approvedBusinessId": "01JY...",
    "decisionReason": "Business information verified",
    "decidedAt": "2026-06-16T14:00:00Z",
    "version": 2
  }
}
```

### Store APIs (`BUS-05`, `BUS-06`)

```text
GET   /businesses/me/store-context
GET   /businesses/{businessId}/store
PATCH /businesses/{businessId}/store
GET   /businesses/{businessId}/policies
POST  /businesses/{businessId}/policies
GET   /stores/{slug}
```

`GET /businesses/me/store-context` (`BUS-LIST-01`) returns the authenticated
user's active approved business/store context for seller portal bootstrapping,
or `data: null` when the user has no active approved business store. It
returns business ID/name/status, current membership role, permissions, and the
active store profile. It does not expose application internals, staff member
lists, private owner IDs, payment, inventory, order, shipping, or transaction
fields.

`GET /businesses/{businessId}/store` (`BUS-05`) returns the MVP store profile
for an approved business. It requires an authenticated active business member.
Missing businesses return `404 BUSINESS_STORE_NOT_FOUND`; non-members return
`403 FORBIDDEN`.

Response `200`:

```json
{
  "data": {
    "id": "01JY...",
    "businessId": "01JY...",
    "slug": "acme-trading",
    "name": "Acme Trading",
    "description": "Local marketplace seller",
    "logoUrl": "/api/v1/public/user-avatars/01JY...",
    "bannerUrl": null,
    "supportEmail": "help@example.com",
    "supportPhone": "+19495551234",
    "status": "ACTIVE",
    "version": 1,
    "createdAt": "2026-07-08T12:00:00Z",
    "updatedAt": "2026-07-08T12:30:00Z"
  }
}
```

`PATCH /businesses/{businessId}/store` (`BUS-05`) updates mutable
customer-facing store profile fields.

Headers:

```text
If-Match: 1
```

Request:

```json
{
  "name": "Acme Trading",
  "slug": "acme-trading",
  "description": "Local marketplace seller",
  "logoUrl": "https://example.com/logo.png",
  "bannerUrl": null,
  "supportEmail": "help@example.com",
  "supportPhone": "+19495551234"
}
```

Rules:

- Requires authenticated active business membership with role `OWNER` or
  `MANAGER`.
- `If-Match` is required and must match the current store `version`.
- Stale versions return `409 VERSION_CONFLICT`.
- Slugs are lowercase, URL-safe, and unique across stores.
- Duplicate slugs return `409 BUSINESS_STORE_SLUG_CONFLICT`.
- Clients cannot set business ID, status, version, timestamps, membership, or
  approval state.
- Logo and banner fields are URLs/API paths only in BUS-05; upload workflow is
  not part of this slice.

`GET /stores/{slug}` (`BUS-05`) returns the active public store profile for
guest storefront reads. It returns only active stores for active businesses and
does not expose business membership, staff, owner user IDs, internal review
data, or suspended stores.

### Staff APIs (`BUS-07`)

```text
GET    /businesses/{businessId}/members
POST   /businesses/{businessId}/invitations
POST   /business-invitations/{token}/accept
PATCH  /businesses/{businessId}/members/{userId}
DELETE /businesses/{businessId}/members/{userId}
```

## 6. Categories, Media, and Listings

### Categories (`LST-01`)

```text
GET /categories
GET /categories/{categoryId}
GET /categories/{categoryId}/attributes
```

### Media (`LIST-02`, `LIST-03`)

```text
POST /listings/{listingId}/media/upload-request
POST /listings/{listingId}/media/{mediaId}/confirm
PUT  /listings/{listingId}/images
```

LIST-02 scopes media to an existing draft listing. Upload request contains file
name, content type, size, and optional checksum. MEDIA-01 returns a signed
storage upload target, object key, upload status, moderation status, and
version. Confirm verifies that the storage object exists before marking the
media uploaded. LIST-03 attaches confirmed media to ordered draft listing
images. The request array order becomes the display order.

```text
GET /listings/{listingId}/media/{mediaId}/content
GET /public/listing-media/{imageId}
```

MEDIA-01 uses these read endpoints for seller preview and public approved image
delivery. The endpoints redirect to short-lived signed storage URLs. Public
media is exposed only when the listing, image, and media object are all
approved.

### Listing create (`LST-04`, `LST-05`)

```text
POST /listings
```

Individual request:

```json
{
  "categoryId": "id",
  "title": "Used bicycle",
  "description": "Description",
  "condition": "GOOD",
  "conditionNotes": "Small scratch",
  "price": {"amount": "250.00", "currency": "USD"},
  "negotiable": true,
  "location": {"city": "Irvine", "region": "CA"},
  "paymentPreferences": ["CASH_ON_MEETING"],
  "deliveryPreferences": ["PUBLIC_MEETING"],
  "attributes": {}
}
```

LIST-01 uses the unified draft endpoint above with `sellerType` set to
`INDIVIDUAL` or `BUSINESS`. Business requests add `businessId`, SKU, and
quantity; they omit negotiation and meeting fields. Publishing, image
attachment, moderation submission, and public browsing remain separate slices.

### Business store item self-publishing (`BUS-LIST-00` through `BUS-LIST-06`, `LST-05A`)

Implemented seller portal draft/edit/media/publication commands:

```text
GET    /businesses/{businessId}/store/items
GET    /businesses/{businessId}/store/items/search
POST   /businesses/{businessId}/store/items
GET    /businesses/{businessId}/store/items/{listingId}
PATCH  /businesses/{businessId}/store/items/{listingId}
POST   /businesses/{businessId}/store/items/{listingId}/media/upload-request
PUT    /businesses/{businessId}/store/items/{listingId}/media/{mediaId}/content
POST   /businesses/{businessId}/store/items/{listingId}/media/{mediaId}/confirm
PUT    /businesses/{businessId}/store/items/{listingId}/images
POST   /businesses/{businessId}/store/items/{listingId}/publish
POST   /businesses/{businessId}/store/items/{listingId}/pause
POST   /businesses/{businessId}/store/items/{listingId}/relist
```

Rules:

- Requires authenticated active membership in the approved business.
- Business and store ownership are server-derived or server-validated.
- Draft creation derives `sellerType=BUSINESS`, the path `businessId`, and
  the current active `storeId` from `GET /businesses/me/store-context`.
- Media routes reuse listing media validation and storage, but first verify
  the item belongs to the active business store context.
- BUS-LIST-06 management search accepts optional `q`, `status`, `cursor`, and
  `limit`. Search matches title or SKU, status accepts `DRAFT`, `ACTIVE`,
  `PAUSED`, or `REMOVED_BY_ADMIN`, and results sort by
  `updatedAt DESC, id DESC`.
- Management search returns `{ data, page, summary }`. `page` contains the
  opaque `nextCursor` and `hasMore`; `summary` contains catalog-wide `total`,
  `draft`, `active`, `paused`, and `removed` counts.
- A SKU is unique within one business. A create or seller edit that would
  duplicate another item SKU returns `409 BUSINESS_SKU_CONFLICT`; the existing
  item is not changed.
- Publish moves a complete business store item to public `/stores` visibility
  without item-level admin approval by setting
  `publicationSource=BUSINESS_SELF_PUBLISHED`.
- Draft, paused, removed, and inactive-business items are not public.
- Patch and state commands require `If-Match`.
- Business item fields and media are editable only in `DRAFT` or `PAUSED`.
  Active items must be paused before editing; paused edits preserve `PAUSED`
  until the seller explicitly relists.
- Clients cannot set owner IDs, business membership, status, publication
  source, moderation/admin fields, payment status, inventory, order, shipping,
  or transaction fields.
- Business item quantity is catalog/display quantity in MVP. It is not an
  authoritative inventory balance and cannot reserve stock.
- A removed business item response includes the latest `moderationAction`,
  `moderationReason`, and `moderationActionAt` so the seller can see why the
  item was removed.
- Payment transaction, cart, checkout, inventory reservation, order, shipping,
  fulfillment, and notifications remain V2 APIs.

### Listing management (`LIST-04` and later)

```text
GET    /listings/{listingId}
PATCH  /listings/{listingId}
POST   /listings/{listingId}/submit
POST   /listings/{listingId}/pause
POST   /listings/{listingId}/relist
POST   /listings/{listingId}/close
GET    /users/me/listings
GET    /businesses/{businessId}/listings
```

Patch and state commands require `If-Match`.

LIST-04 implements owner draft read/list/edit: `GET /listings/{listingId}`,
`GET /users/me/listings`, `GET /businesses/{businessId}/listings`, and
`PATCH /listings/{listingId}`.

LIST-05 implements `POST /listings/{listingId}/submit`. The command requires
the current version in `If-Match`, requires at least one attached uploaded
image, moves the listing to `PENDING_REVIEW`, and moves listing/image
moderation state to `PENDING`. It also creates or reuses one open
`LISTING_REVIEW` moderation case for the submitted listing without changing
the response body. A valid `DRAFT` listing can be submitted again after a
previous non-approved review; if an active listing review case already exists,
submission reopens it as `OPEN` and clears any admin assignment. Pause, relist,
close, and public read paths remain later slices.

An owner read of a `CHANGES_REQUESTED` individual listing includes the latest
`moderationAction`, exact `moderationReason`, and `moderationActionAt`.
The owner may edit its fields and media. The first successful field or attached
image update returns the listing to `DRAFT` with moderation status
`NOT_SUBMITTED`; the owner then resubmits through the normal submit command
using the new version. Ownership checks remain mandatory for every recovery
read and mutation.

### Listing moderation (`LIST-06`, `LST-09`)

```text
GET  /admin/listings/moderation
GET  /admin/moderation/listing-cases?filter=open|unassigned|assigned_to_me|resolved
GET  /admin/moderation/listing-cases/{caseId}
GET  /admin/moderation/listing-cases/{caseId}/timeline
POST /admin/moderation/listing-cases/{caseId}/claim
POST /admin/moderation/listing-cases/{caseId}/release
POST /admin/moderation/listing-cases/{caseId}/resolve
GET  /admin/listings/{listingId}
PATCH /admin/listings/{listingId}
POST /admin/listings/{listingId}/remove
POST /admin/listings/{listingId}/decision
```

LIST-06 implements the basic decision path without case claiming. ADM-LIST-03
is the admin MVP queue workflow and should be used by the admin site for
review detail and resolution. Both decision paths require
`admin.listing.moderation.resolve`.

Queue response returns submitted listings where `status=PENDING_REVIEW` and
`moderationStatus=PENDING`. Queue, case detail, admin listing detail, and admin
media reads require `admin.listing.moderation.read`.

ADM-LIST-02 adds the case-backed admin queue. `GET
/admin/moderation/listing-cases` returns `LISTING_REVIEW` cases joined with
safe listing summary fields. Supported filters are:

- `open`: open and claimed cases that are not resolved.
- `unassigned`: open cases with no assigned admin.
- `assigned_to_me`: claimed cases assigned to the current admin.
- `resolved`: resolved cases.

Case response fields include case ID, `caseStatus`, `priority`,
`assignedAdminUserId`, optional `assignedAdminDisplayName`, case `version`,
created/updated/resolved timestamps, `submittedByUserId`, `sellerId`, optional
`sellerDisplayName`, listing ID, title, seller type, listing status, listing
moderation status, price/currency, public location, SKU, and quantity.

ADM-LIST-05 adds an optional `q` query parameter to the case queue:

```http
GET /admin/moderation/listing-cases?filter=open&q=bicycle
```

When `q` is blank or absent, queue behavior is unchanged. When present, search
is applied within the selected filter and matches case ID, listing ID, listing
title, submitted-by user ID, seller user/business ID, assigned admin user ID,
and SKU. Display-name search is deferred until auth-service owns a stable
identity search contract.

Claim and release commands require `admin.listing.moderation.claim` and
`If-Match` with the current case version.
Claim succeeds only for open unassigned cases and assigns the current admin.
Release succeeds only for claimed cases assigned to the current admin. Stale
versions or invalid assignment state return
`409 MODERATION_CASE_VERSION_CONFLICT`.

ADM-LIST-03 adds case detail and resolution. `GET
/admin/moderation/listing-cases/{caseId}` returns the enriched case summary,
the current listing draft including attached image metadata, and listing
moderation decision history. `POST
/admin/moderation/listing-cases/{caseId}/resolve` requires `If-Match` with the
current case version and the same decision request body as the listing decision
endpoint. It requires `admin.listing.moderation.resolve`, and the case must be
claimed by the current admin. Resolution applies the
listing decision and closes the case in one transaction. Invalid assignment,
stale case version, non-claimed case state, or already-resolved case state
returns `409 MODERATION_CASE_VERSION_CONFLICT`. The admin detail UI treats
unclaimed, stale, non-pending, and resolved cases as read-only review context.
Cases claimed by a different admin are also rendered read-only before any
command is attempted. The timeline endpoint requires
`admin.listing.moderation.read` and `admin.audit.read` and
returns oldest-first case creation/reopen, claim, release, resolution, listing
decision, active edit, and active removal entries using the normalized timeline
shape defined by the business application timeline. Claim/release/resolve actor
IDs come only from the authenticated backend principal. Missing legacy case
creation or current assignment events are represented by derived entries
without modifying historical rows.

ADM-LIST-04 adds active listing admin maintenance. `GET
/admin/listings/{listingId}` returns the current listing draft-shaped response
with image metadata for platform admins. `PATCH /admin/listings/{listingId}`
edits active approved listing content fields only and requires `If-Match` with
the current listing version plus a required `reason`. `POST
/admin/listings/{listingId}/remove` removes either an active approved
individual listing or an active business self-published listing from public
visibility and requires `If-Match` plus a required `reason`.
Removal sets listing status `REMOVED_BY_ADMIN`; it is not a physical delete.
Both commands append moderation history decisions `ADMIN_EDIT` or
`ADMIN_REMOVE`. Non-active listings return `400 LISTING_INVALID_REQUEST`; stale
listing versions return `409 LISTING_VERSION_CONFLICT`. Edit requires
`admin.listing.edit`; removal requires `admin.listing.remove`.

Decision command requires `If-Match` with the current listing version.

Request:

```json
{"decision": "APPROVE|REJECT|REQUEST_CHANGES", "reason": "text"}
```

Rules:

- `reason` is required for every decision.
- Only pending-review listings can receive a decision.
- `APPROVE` sets listing status `ACTIVE`, moderation status `APPROVED`, and
  `publishedAt`.
- `REJECT` sets listing status `REJECTED` and moderation status `REJECTED`.
- `REQUEST_CHANGES` sets listing status `CHANGES_REQUESTED` and moderation
  status `CHANGES_REQUESTED`.
- Admin removal sets listing status `REMOVED_BY_ADMIN` while preserving
  moderation status and history.
- Attached listing image/media moderation status is updated with the listing
  decision result.
- Every decision and active listing admin action is appended to listing
  moderation decision history.
- A stale version returns `409 LISTING_VERSION_CONFLICT`.

LIST-06 implements the basic decision endpoint. ADM-LIST-00 through
ADM-LIST-02 add moderation cases plus claim/release. ADM-LIST-03 adds
case-backed resolution for the admin MVP queue workflow; the direct listing
decision endpoint remains for the basic LIST-06 contract and compatibility.
ADM-LIST-04 adds active approved listing maintenance for admins.

### Public listing detail (`LIST-07`)

```text
GET /public/listings/{listingId}
```

LIST-07 implements a guest-readable direct listing detail endpoint.

Rules:

- Login is not required.
- Individual listings are visible only with `status=ACTIVE` and
  `moderationStatus=APPROVED`. Business listings are visible only with
  `status=ACTIVE` and `publicationSource=BUSINESS_SELF_PUBLISHED`.
- Non-public listing states return `404 LISTING_NOT_FOUND`.
- The response includes a safe owner display label and omits owner user IDs,
  business membership IDs, internal status, moderation status, versions, media
  object IDs, object bucket, and object key.
- Individual listings include an off-platform payment and delivery notice.
- Business listings include public store identity (`storeId`, `storeSlug`,
  `storeName`, `businessVerified`) and public store location. Their quantity is
  informational catalog quantity only; MVP pages do not expose cart or
  authoritative inventory controls.

Response:

```json
{
  "id": "01J...",
  "sellerType": "INDIVIDUAL",
  "sellerDisplayName": "Alex Seller",
  "categoryId": "01J...",
  "categorySlug": "general",
  "categoryName": "General",
  "title": "Used bicycle",
  "description": "A reliable city bike.",
  "condition": "GOOD",
  "conditionNotes": null,
  "priceAmount": 250.00,
  "currency": "USD",
  "negotiable": true,
  "quantity": 1,
  "publicCity": "Irvine",
  "publicRegion": "CA",
  "publishedAt": "2026-06-17T12:00:00Z",
  "transactionNotice": "Payment and delivery are arranged directly by participants. The platform does not verify or protect off-platform payment.",
  "visitCount": 12,
  "likeCount": 4,
  "images": [
    {
      "id": "01J...",
      "displayOrder": 0,
      "altText": "Blue bike",
      "originalFileName": "bike.png",
      "contentType": "image/png",
      "sizeBytes": 1024,
      "uploadUrl": "/api/v1/public/listing-media/01J...",
      "url": "/api/v1/public/listing-media/01J..."
    }
  ]
}
```

### Listing engagement (`LIST-08`)

```text
POST   /listings/{listingId}/visit
POST   /listings/{listingId}/like
DELETE /listings/{listingId}/like
GET    /listings/{listingId}/engagement/me
GET    /users/me/liked-listings
```

LIST-08 implements authenticated account-scoped engagement for public
approved listings.

Rules:

- All engagement endpoints require login through the gateway BFF session.
- The backend derives the account from the current actor and auth-service;
  clients cannot submit user IDs, counts, listing state, or owner IDs.
- Only public listings with `status=ACTIVE` and
  `moderationStatus=APPROVED` accept engagement commands. Non-public listings
  return `404 LISTING_NOT_FOUND`.
- One account can record at most one visit per listing.
- One account can have at most one active like per listing.
- Like and visit commands are idempotent for the same account/listing pair.
- Unlike is idempotent and never decrements below zero.
- Seller self-engagement follows the same one-account-one-listing rule as any
  other authenticated account.
- Engagement does not change the listing aggregate `version`.
- `GET /users/me/liked-listings` returns the current user's active liked
  listings using the same safe public listing projection as public browse.
  Listings that are unliked, closed, removed, pending review, rejected, or no
  longer public are not returned.

Response:

```json
{
  "listingId": "01J...",
  "visitCount": 12,
  "likeCount": 4,
  "visitedByMe": true,
  "likedByMe": false
}
```

Public listing detail and public search/browse responses include
`visitCount` and `likeCount`. They do not expose per-user engagement state;
`GET /listings/{listingId}/engagement/me` is the authenticated per-account
read. `GET /users/me/liked-listings` returns a list of public listing objects.

### Safe public identity labels (`USER-02`)

```text
GET /users/public-labels?userIds=&businessIds=
GET /public/seller-labels?userIds=&businessIds=
```

`USER-02` defines an auth-service-owned safe seller-label contract for
marketplace listing display and future chat header use. The
`/public/seller-labels` route is retained for existing service callers; the
`/users/public-labels` route is the canonical user-profile contract.

Rules:

- Login is not required.
- Each requested ID set is capped at 50 IDs.
- Public labels include only user ID, display name, unique public handle,
  optional avatar URL, business ID, and business legal name.
- Email, phone, Keycloak subject, roles, account status, verification flags,
  and internal profile/contact metadata are never returned.
- Suspended, closed, or missing identities are omitted from the response.
- Listing APIs that cannot resolve a label use neutral fallback text such as
  `Marketplace seller` or `Business seller`.

Response:

```json
{
  "data": {
    "users": [
      {
        "id": "01J...",
        "displayName": "Alex Seller",
        "publicHandle": "alex-sells",
        "avatarUrl": "https://example.com/avatar.png"
      }
    ],
    "businesses": [
      {
        "id": "01J...",
        "legalName": "MSB Local Store LLC"
      }
    ]
  }
}
```

### Search (`SRC-02`, `SRC-03`)

```text
GET /public/listings
GET /public/marketplace/listings/search?q=&categoryId=&condition=&minPrice=&maxPrice=&city=&county=&sort=&cursor=&limit=
GET /public/stores/listings/search?q=&categoryId=&condition=&minPrice=&maxPrice=&city=&county=&sort=&cursor=&limit=
GET /public/stores/{slug}
GET /public/stores/{slug}/listings?categoryId=&condition=&minPrice=&maxPrice=&sort=&cursor=&limit=
GET /search/listings?q=&sellerType=&categoryId=&condition=&minPrice=&maxPrice=&city=&region=&sort=&cursor=&limit=
```

SEARCH-01 implements the initial database-backed public browse path:
`GET /public/listings`. It returns newest approved active listings using the
same safe public projection as `GET /public/listings/{listingId}`. It has a
server-side cap and no client filters yet.

SEARCH-00 defines and implements the split public read contract:
`GET /public/marketplace/listings/search` returns approved active
`INDIVIDUAL` listings. `GET /public/stores/listings/search` currently returns
approved active `BUSINESS` listings, and the planned `BUS-LIST-00` store item
self-publishing slice changes that business path to return active published
business store items without item-level admin approval. Both start from MySQL
source tables and expose only safe public fields. Storefront scoping and
OpenSearch are deferred until the split database-backed contracts are stable.

SEARCH-01A / SEARCH-02A is the implemented individual marketplace search path:
`GET /public/marketplace/listings/search` accepts `q`, `categoryId`,
`condition`, `minPrice`, `maxPrice`, `city`, `county`, and
optional `sort=newest|price_asc|price_desc`. When `sort` is absent, the
service uses its default stable result order without treating sorting as an
active user filter. It returns approved active `INDIVIDUAL` listings only and
keeps the off-platform individual trade disclosure.

Remaining search/storefront work is split by product experience:

- SEARCH-01B is the implemented business storefront search path:
  `GET /public/stores/listings/search` accepts `q`, `categoryId`,
  `condition`, `minPrice`, `maxPrice`, `city`, `county`, and
  optional `sort=newest|price_asc|price_desc`. When `sort` is absent, the
  service uses its default stable result order without treating sorting as an
  active user filter. Keyword search matches public item fields, SKU,
  category, active public store name, and active public business legal name.
  After `BUS-LIST-00`, it returns active published `BUSINESS` store items
  without item-level admin approval and does not introduce cart, inventory,
  checkout, payment, orders, or shipping. Business results are revalidated
  against auth-service public business/store visibility; inactive,
  suspended, closed, draft, paused, removed, and cross-business items must not
  leak through search.
- SEARCH-03 is the implemented shared cursor pagination path. Both split
  public search endpoints accept `cursor` and `limit` and return
  `{"data":[],"page":{"nextCursor":null,"hasMore":false}}`. Cursors are
  opaque, limits are server-capped, and sort order is deterministic.
- SEARCH-04 adds OpenSearch as a derived projection after the database-backed
  contracts are stable. It does not change this public response shape. When
  enabled, OpenSearch returns candidate listing IDs and product-service
  revalidates those IDs against MySQL before returning safe public cards.
- SEARCH-05 makes marketplace and business store search state URL-based in the
  frontend. Query, category, condition, price, city, county, and sorting are
  encoded as URL parameters so refresh, back navigation, and shared links
  preserve the active search.

Internal/admin operation:

```text
POST /admin/search/listings/rebuild
```

This rebuilds the derived listing-search projection from MySQL and requires a
platform admin session. It does not create or modify listings.

The legacy BM25 rebuild remains unchanged. The separately gated Product-owned
V2 workflow uses these platform-admin-only routes:

```text
POST /admin/search/listings/vector-rebuilds
GET  /admin/search/listings/vector-rebuilds/{runId}
POST /admin/search/listings/vector-rebuilds/{runId}/catch-up
POST /admin/search/listings/vector-rebuilds/{runId}/promote
POST /admin/search/listings/vector-rebuilds/{runId}/recover
```

Commands have no request body and never accept an index, alias, schema,
OpenSearch query, count, URL, state, vector, or operator identity. Product
derives the authenticated platform admin from the existing Auth boundary.
Responses use schema `MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2` and expose
only the canonical run ID, fixed durable state/outcome/error code, bounded
counts/timestamps, the fixed V2 schema identity, safe candidate/previous
generation roles, and the Product-derived boolean command snapshots
`canCatchUp`, `canPromote`, and `canRecover`. `canPromote` is true only when
the durable run and exact aliases are promotable and Product currently finds
zero unresolved projection work; `catchUpWorkCount` is not a remaining-lag
signal. Commands re-evaluate these rules, and promotion repeats them under the
exclusive database fence before alias mutation. Physical index names, listing
IDs/content, hashes, vectors, credentials, and OpenSearch bodies are excluded.
Command and status gates are false by default. Every authenticated command
outcome appends one protected,
immutable Product audit row.

Public listing cards may expose listing ID, seller type, safe owner display
label, category display data, title, condition, price, public location,
published time, negotiable state, transaction notice, and approved image URLs.
They must not expose owner user IDs, business staff/member data, internal
status, moderation state, versions, media bucket/key, exact individual
locations, or private contact data.

Public image URLs must be app-owned read URLs, such as
`/api/v1/public/listing-media/{imageId}`. Public listing responses must not
return raw object storage URLs, signed upload URLs, object buckets, or object
keys.

Maximum `limit` is server-controlled. Search results include seller type and
any applicable individual off-platform trade disclosure.

## 7. Chat

### Domain boundary (`CHAT-00`)

MVP chat uses a dedicated `chat-service` for reusable conversation mechanics:
conversations, participants, text messages, cursor-paginated history,
per-participant read state, and participant-only authorization.

The only enabled MVP conversation type is:

```text
LISTING_BUYER_SELLER
```

Future conversation types are reserved but disabled until their own approved
domain slices define authorization, lifecycle, audit, and UI behavior:

```text
SUPPORT_CASE
BUSINESS_ADMIN_SUPPORT
AI_AGENT_SESSION
```

Customer service, business seller to admin chat, admin direct messaging, AI
agent sessions, reports, blocking, reviews, trade creation, trade completion,
and realtime delivery are not part of the initial chat API behavior.

### Chat identity display (`USER-06`)

Chat participant display must use safe identity labels from `USER-02` or a
chat-owned display snapshot selected by `CHAT-00`. Chat APIs and UI surfaces
may show display name, unique app-owned public handle, app-owned public avatar
URL, neutral fallback text, and conversation-derived participant role such as
buyer or seller.

Chat participant display must not expose email, phone, Keycloak subject, role
lists, account status internals, verification flags, private profile metadata,
storage object keys, signed URLs, buckets, or raw object storage URLs.

Future chat participant summaries should use the app-owned user ID, not the
Keycloak subject. Missing, closed, suspended, or unavailable labels degrade to
neutral text such as `Marketplace user` or `Marketplace seller` without an
avatar. Message authorization remains chat-service-owned participant
authorization, not a profile-label decision.

### Conversation APIs (`CHAT-01`, `CHAT-02`, `CHAT-03`, `CHAT-04`)

```text
POST /api/v1/listings/{listingId}/conversations
GET  /api/v1/conversations?cursor=&limit=
GET  /api/v1/conversations/{conversationId}
GET  /api/v1/conversations/{conversationId}/messages?cursor=&limit=
POST /api/v1/conversations/{conversationId}/messages
POST /api/v1/conversations/{conversationId}/read
```

Message request:

```json
{"messageType": "TEXT", "body": "Is this still available?"}
```

`POST /api/v1/listings/{listingId}/conversations` creates or returns a
`LISTING_BUYER_SELLER` conversation for an active approved individual listing.
The service derives the seller from the listing. It rejects self-chat, business
listings, inactive listings, unapproved listings, and non-participant access.
The same user may be buyer in one conversation and seller in another.

`CHAT-01` response:

```json
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
  "participants": [
    {
      "participantId": "01J...",
      "displayName": "You",
      "publicHandle": "jon-buys",
      "avatarUrl": null,
      "initials": "Y",
      "roleInConversation": "BUYER",
      "currentUser": true
    },
    {
      "participantId": "01J...",
      "displayName": "Alex Seller",
      "publicHandle": "alex-sells",
      "avatarUrl": "/api/v1/public/user-avatars/01J...?v=4",
      "initials": "AS",
      "roleInConversation": "SELLER",
      "currentUser": false
    }
  ],
  "createdAt": "timestamp",
  "updatedAt": "timestamp"
}
```

New conversations return `201`; duplicate starts return `200` with the
existing conversation. The response omits email, phone, Keycloak subject, role
lists, account status internals, listing moderation internals, listing version,
exact individual location, media bucket/key, signed storage URLs, and raw
object-storage URLs.

`GET /api/v1/conversations/{conversationId}` returns the same safe conversation
summary shape for participants only.

`GET /api/v1/conversations/{conversationId}/messages` returns cursor-paginated
text history ordered by `(createdAt, id)`:

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
      "createdAt": "timestamp"
    }
  ],
  "nextCursor": null
}
```

`POST /api/v1/conversations/{conversationId}/messages` creates a user-authored
`TEXT` message. The sender is derived from the authenticated current user.
Blank messages, unsupported message types, bodies longer than 2000 characters,
and non-participant sends are rejected.

`GET /api/v1/conversations` returns only conversations where the authenticated
user is a participant. Items include safe listing context, the other
participant summary, last message preview, and participant-specific unread
state.

`POST /api/v1/conversations/{conversationId}/read` updates only the current
participant's `lastReadMessageId` and `lastReadAt`. It cannot update the other
participant.

`CHAT-04` adds the floating marketplace chat launcher UI. It introduces no new
backend endpoints and uses only the participant-authorized conversation list,
thread, message-send, and read-state APIs above.

`CHAT-05` adds a minimal conversation-gated individual completion path:

```text
GET  /api/v1/conversations/{conversationId}/completion
POST /api/v1/conversations/{conversationId}/completion/mark-done
POST /api/v1/conversations/{conversationId}/completion/confirm
```

The seller mark-done request accepts only the sold quantity:

```json
{"quantitySold": 1}
```

`quantitySold` defaults to `1` when omitted and cannot exceed the listing
quantity. The request accepts no buyer ID, email, phone, address, price, or
payment fields. The seller, buyer, listing, and conversation are derived from
the fixed `LISTING_BUYER_SELLER` conversation. Buyer confirmation is accepted
only from the buyer participant in that same conversation. Repeated seller
mark-done and buyer confirmation requests are idempotent.

On buyer confirmation, chat-service asks product-service to close the active
approved individual listing and locks the conversation. The listing no longer
appears in public marketplace/search results, but remains visible in the
seller's own listing history. A locked completed conversation is read-only for
both participants. This does not increment public completed-sales reputation
and does not add payment, shipping, order, review, or protection claims.

Realtime delivery, attachments, reports, and blocking are future slices.

### Safety (future)

```text
POST   /users/{userId}/block
DELETE /users/{userId}/block
POST   /reports
```

Report request identifies subject type/ID, reason, and permitted evidence IDs.

## 8. Individual Trades (V3)

Buyer and seller negotiate only through free-text chat. There are no structured
offer, counteroffer, or offer-acceptance endpoints in MVP.

### Trades (`TRD-01` through `TRD-03`)

```text
POST /conversations/{conversationId}/trade
GET  /trades
GET  /trades/{tradeId}
POST /trades/{tradeId}/cancel
POST /trades/{tradeId}/completion-requests
POST /trades/{tradeId}/buyer-confirmation
POST /trades/{tradeId}/report
```

#### Seller creates trade from conversation

`POST /conversations/{conversationId}/trade`

Authorization: authenticated listing owner.

Request:

```json
{"dealNote": "Optional private note"}
```

The request does not accept buyer ID, email, phone, price, or address. The
service derives the buyer and listing from the conversation. It creates one
trade and reserves the listing atomically. A concurrent request for another
conversation returns `409 LISTING_ALREADY_RESERVED`.

Every response contains:

```json
{
  "paymentHandledByPlatform": false,
  "safetyNotice": "Payment and delivery are arranged directly by participants."
}
```

No endpoint records bank/card credentials or claims external payment success.

#### Seller initiates completion

`POST /trades/{tradeId}/completion-requests`

Authorization: authenticated seller recorded on the trade.

Request:

```json
{"channel": "EMAIL"}
```

The request does not accept buyer ID, email, phone, or address. The service
derives the buyer from the trade and verifies that the selected channel is
available and verified.

Response:

```json
{
  "data": {
    "tradeId": "id",
    "sellerConfirmedAt": "timestamp",
    "buyerConfirmationStatus": "PENDING",
    "deliveryChannel": "EMAIL",
    "maskedDestination": "j***@mail.com",
    "expiresAt": "timestamp"
  }
}
```

Repeated requests are idempotent within a short window and rate-limited.
Creating a new challenge invalidates prior unused challenges.

#### Buyer confirms completion

`POST /trades/{tradeId}/buyer-confirmation`

Authorization: authenticated buyer recorded on the trade.

Request:

```json
{"challenge": "single-use-link-token-or-code"}
```

The service validates the challenge hash, expiry, current request, authenticated
buyer, and trade state. Success transitions the trade to `COMPLETED`, marks the
listing `SOLD`, and increments public seller completed-sales count exactly once.

Example completed response:

```json
{
  "data": {
    "tradeId": "id",
    "status": "COMPLETED",
    "completedAt": "timestamp",
    "seller": {
      "userId": "id",
      "displayName": "Seller name",
      "completedSalesCount": 12
    }
  }
}
```

The confirmation link may open the marketplace confirmation page, but the
buyer must sign in before the API accepts it.

## 9. Cart and Inventory (V2)

V2-COM-00 ownership, orchestration, state, and idempotency decisions are
defined in `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`. Cart
management and inventory foundation routes are active; later route families
remain contract sketches until their named implementation slice.

### Cart management (`CRT-01` through `CRT-03`)

```text
GET    /cart
POST   /cart/items
PATCH  /cart/items/{listingId}
DELETE /cart/items/{listingId}
DELETE /cart
```

All routes require authentication and derive the cart owner from the JWT
subject. Add and quantity replacement accept:

```json
{"listingId": "01JY...", "quantity": 1}
```

The add request includes both fields. The patch request includes only
`quantity`. Quantity must be from `1` through `999`.

Response:

```json
{
  "version": 3,
  "expiresAt": "2026-08-16T12:00:00Z",
  "itemCount": 1,
  "totalQuantity": 2,
  "totals": [{"currency": "USD", "amount": 39.98}],
  "items": [{
    "listingId": "01JY...",
    "title": "Store item",
    "thumbnailUrl": "/api/v1/public/listing-media/01JY...",
    "quantity": 2,
    "observedPrice": 19.99,
    "currency": "USD",
    "addedAt": "2026-07-17T12:00:00Z"
  }]
}
```

Only active business listings with initialized, sufficient available
inventory can be added or have quantity replaced. Add on an existing listing
replaces quantity and refreshes observed price. Patch preserves the previously
observed price. Successful mutations refresh the 30-day inactivity expiry.

V2-CART-02 implements full-cart validation for `CRT-04`:

```text
POST   /cart/validate
```

The request has no body. Validation derives the buyer from authentication,
reads the current cart version, and does not mutate the cart, refresh its TTL,
or reserve inventory.

Response:

```json
{
  "cartVersion": 3,
  "validatedAt": "2026-07-18T12:00:00Z",
  "checkoutReady": false,
  "itemCount": 1,
  "totalQuantity": 2,
  "validatedTotals": [],
  "cartIssues": [],
  "items": [{
    "listingId": "01JY...",
    "title": "Store item",
    "thumbnailUrl": "/api/v1/public/listing-media/01JY...",
    "requestedQuantity": 2,
    "availableQuantity": 1,
    "observedPrice": 19.99,
    "currentPrice": 21.99,
    "observedCurrency": "USD",
    "currentCurrency": "USD",
    "status": "QUANTITY_REDUCED",
    "issues": [{
      "code": "CART_QUANTITY_REDUCED",
      "message": "Only 1 is currently available.",
      "action": "SET_AVAILABLE_QUANTITY"
    }, {
      "code": "CART_PRICE_CHANGED",
      "message": "The price changed from 19.99 USD to 21.99 USD.",
      "action": "ACCEPT_CURRENT_PRICE"
    }]
  }]
}
```

Item statuses are `READY`, `PRICE_CHANGED`, `QUANTITY_REDUCED`,
`OUT_OF_STOCK`, `LISTING_UNAVAILABLE`, `SELLER_UNAVAILABLE`, and
`CURRENCY_CONFLICT`. Multiple issues may be returned for one line. Repair
actions use the existing cart mutation routes; validation never repairs the
cart automatically.

`checkoutReady=true` requires a nonempty cart, current active product and
seller/store eligibility, sufficient initialized inventory, unchanged
observed price and currency, and one current currency across all lines.
`validatedTotals` is populated from current server prices only when the cart
is ready and uses the cart total shape
`{"currency": "USD", "amount": 43.98}`. A dependency timeout or `5xx` fails
the whole operation with `503 CART_DEPENDENCY_UNAVAILABLE`.

Seller/store eligibility is resolved through the internal auth-service route:

```text
GET /internal/businesses/{businessId}/stores/{storeId}/commerce-eligibility
```

It requires `X-Internal-Service-Token`, confirms that the store belongs to the
business, and returns eligible only when both are active. It is not routed
through the public gateway. The detailed contract and slice boundary are in
`docs/v2/commerce/v2-cart-02-cart-validation-plan.md`.

Internal CART-01 composition reads:

```text
GET /internal/store/items/{listingId}/commerce-context
GET /internal/inventory/{listingId}/availability
```

Both require `X-Internal-Service-Token` and are not gateway browser routes.
The detailed contract and slice boundary are in
`docs/v2/commerce/v2-cart-01-redis-cart-plan.md`.

### Business inventory (`INV-01`)

```text
GET  /businesses/{businessId}/inventory?q=&listingStatus=&cursor=&limit=
POST /businesses/{businessId}/inventory/{listingId}/initialize
GET  /businesses/{businessId}/inventory/{listingId}
POST /businesses/{businessId}/inventory/{listingId}/adjustments
GET  /businesses/{businessId}/inventory/{listingId}/movements?cursor=&limit=
```

The list composes the current product-service business item page with nullable
inventory balances so initialized and uninitialized catalog items can share
one seller workflow. Product-service ordering and cursor remain authoritative
for this composed list.

Initialization explicitly sets non-negative on-hand stock and requires
`Idempotency-Key`. The existing business listing quantity is a suggestion only
and is never copied automatically.

Adjustment request selects `SET` with an absolute non-negative quantity or
`ADJUST` with a nonzero signed delta and includes a required reason.
`If-Match` contains the expected inventory version and `Idempotency-Key`
deduplicates retries.

Inventory is keyed by listing ID, not client-supplied SKU. Seller routes
require scoped `INVENTORY_VIEW` or `INVENTORY_MANAGE` permission.

Product service provides a service-authenticated validation read:

```text
GET /internal/businesses/{businessId}/store/items/commerce-context?q=&status=&cursor=&limit=
GET /internal/businesses/{businessId}/store/items/{listingId}/commerce-context
```

The detailed V2-INV-01 contract and error cases are defined in
`docs/v2/commerce/v2-inv-01-business-inventory-foundation-plan.md`.

### Internal reservation APIs (`INV-02` through `INV-04`)

```text
POST /internal/inventory/reservations
POST /internal/inventory/reservations/{id}/commit
POST /internal/inventory/reservations/{id}/release
GET  /internal/inventory/reservations/{id}
```

V2-INV-02 implements one checkout-scoped, multi-line reservation aggregate so all
requested inventory is reserved or none is. The reserve request contains
`checkoutId`, `purpose`, an absolute `expiresAt`, and one through 50 distinct
`{listingId, quantity}` lines. It never accepts business identity, prices,
totals, or inventory balances.

Service authentication is mandatory for every route. `Idempotency-Key` is
mandatory for the three POST commands and is not required for GET. Exact
states, replay behavior, errors, expiry, and concurrency rules are defined in
`docs/v2/commerce/v2-inv-02-inventory-reservation-lifecycle-plan.md`.

## 10. Checkout, Payment, and Orders (V2)

### Checkout (`CHK-01`, `CHK-02`)

```text
POST /checkouts
GET  /checkouts/{checkoutId}
POST /checkouts/{checkoutId}/cancel
```

Create request references cart and address ID. Response contains authoritative
snapshots, totals, expiry, and reservation state. Each item includes the
immutable nullable `storeName` captured with its `storeId`; clients use the
name when present and a neutral `Store` fallback rather than displaying the
opaque ID as a heading.

The approved local-demo implementation uses `ZERO_LOCAL_DEMO_V1` tax,
`FREE_LOCAL_DEMO_V1` shipping, immutable platform policy
`LOCAL_DEMO_V1`, and one shared `PT15M` checkout/reservation lifetime.
These adapter modes are invalid in a production profile.

### Payment (`PAY-01`, `PAY-02`)

```text
POST /checkouts/{checkoutId}/payment-intent
POST /webhooks/payments
GET  /payments/{paymentId}
```

Payment-intent command requires `Idempotency-Key`. Response exposes only the
provider client data intended for the browser.

`V2-PAY-01C` implements the order-owned form of the command at
`POST /api/v1/checkouts/{checkoutId}/payment-intent`. It accepts no request
body. Order service derives the opaque buyer ID, checkout version, persisted
cart snapshot hash, authoritative total/currency, expiry, and sorted distinct
business IDs from the authenticated buyer's immutable checkout. Only
`PENDING_PAYMENT` checkouts with an active, unreleased reservation and a future
expiry are payable.

The order response contains only payment intent ID, checkout ID, stable status
and version, amount/currency, expiry, bounded provider action, and safe error.
It excludes buyer ID, business IDs, the internal service token, provider
reference, and provider payload. Order service forwards the caller's original
`Idempotency-Key` once, forwards a bounded correlation ID, and does not
automatically retry the side-effecting payment command.

The order adapter is unavailable unless both `checkout.enabled=true` and
`checkout.payment-integration.enabled=true`. Payment service remains
independently gated by `payment.intents.enabled=true`. Disabled or
misconfigured boundaries fail closed and remain absent from gateway/frontend
navigation in this slice.

The current default-off payment-service boundary is narrower than the future
gateway-facing routes above:

```text
POST /api/v1/internal/payment-intents
GET  /api/v1/internal/payment-intents/{paymentIntentId}?buyerId={buyerId}
POST /api/v1/webhooks/payments
```

- Intent routes require the constant-time checked internal service token and
  are unavailable unless `payment.intents.enabled=true`.
- The webhook does not use browser/session or internal-service-token
  authentication. It requires
  `X-MSB-Signature: t=<epoch-seconds>,v1=<lowercase HMAC-SHA256>` over
  `<timestamp>.<raw-body>`, enforces a five-minute default tolerance, and is
  unavailable unless `payment.webhooks.enabled=true` with a configured fake
  webhook secret.
- The deterministic fake event body is limited to 16 KiB and contains exactly
  `eventId`, `type`, `occurredAt`, and `data`. Supported types are
  `payment_intent.succeeded` and `payment_intent.failed`; data contains the
  fake provider intent reference and, for failure only, a bounded failure code.
- Verified callbacks deduplicate by provider event ID and exact payload hash.
  Only `REQUIRES_ACTION` or `PROCESSING` may become `SUCCEEDED` or `FAILED`.
  Provider event, status history, attempt, and version-1 payment outbox writes
  commit atomically.
- `V2-PAY-01D` adds a default-disabled dispatcher contract for those outbox
  rows. Its version-1 transport envelope contains only stable `eventId`,
  `eventType`, `schemaVersion`, `occurredAt`, `correlationId`,
  `paymentIntentId`, `checkoutId`, deterministic `partitionKey`, and the
  bounded status/amount/currency/provider-event payload. The partition key is
  the payment-intent ID. Amount is a positive, non-coerced JSON number within
  `DECIMAL(19,4)` bounds; the stored provider-event causation ID must match the
  payload provider-event ID before dispatch.
- Dispatch is at-least-once: transport acknowledgement precedes
  `published_at`, an expired claim can be replayed after a crash, and consumers
  must deduplicate by event ID. Bounded concurrent claims, retry backoff, and
  terminal safe error metadata are internal payment-service behavior.
  Dispatch and its scheduled worker are both disabled by default. No Kafka
  adapter, broker activation, or gateway exposure is included.

### Order confirmation event (`ORD-01`)

`V2-ORD-01A` adds a default-disabled Order Service application handler for the
version-1 `payment.succeeded` envelope above. There is no HTTP, gateway,
browser, Kafka, or broker consumer surface in this slice.

Order Service persists the exact payment-intent ID, checkout version/snapshot
hash, buyer ID, sorted business IDs, amount, currency, and expiry that it
already validated while creating the payment intent. The event handler then:

- requires the exact version-1 envelope, `payment.succeeded`, `SUCCEEDED`
  payload, payment-intent partition key, bounded correlation/provider-event
  identity, positive USD amount, and canonical identifiers;
- matches event payment/checkout/amount/currency to the local payment binding
  and matches its buyer/business scope to immutable checkout snapshots;
- deduplicates durably by `(consumerName, eventId)` and rejects event-ID reuse
  with a different canonical payload hash;
- uses only `PENDING_PAYMENT -> PAYMENT_PROCESSING -> COMPLETED` for normal
  confirmation;
- commits inventory with a deterministic idempotency key before the local
  order transaction; and
- atomically creates one order per checkout/payment, one business group per
  business, immutable item/address/history snapshots, checkout completion,
  processed-event completion, the unchanged version-1 `order.confirmed` outbox
  row, and a notification-compatible version-2 row.

Checkout creation also persists one Order-owned cart-reconciliation command.
After checkout reaches `COMPLETED`, an independent retry worker atomically
removes only purchased Redis lines whose mutation identity still matches the
checkout snapshot. A wholly unchanged cart is removed by its exact cart
version; buyer changes made after checkout began are preserved. Redis failure
does not change payment or order outcome.

`V2-NOT-01A` adds a second, backward-compatible internal
`order.confirmed` version-2 outbox event. Version 1 remains unchanged and is
unsupported by Notification Service. The version-2 payload retains the
version-1 fields and adds only `recipientUserId`, resolved from persisted
`orders.buyer_id`. Outbox aggregate ID and future transport partition key are
the order ID; payload `orderId` must match. Correlation and payment-event
causation IDs remain bounded outbox metadata. No email, address, display name,
provider data, arbitrary title/body, or route is accepted.

Concurrent duplicates observe the active bounded lease, completed replay
returns the existing order, and abandoned/retryable work can resume safely.
Late or non-committable paid events fail closed for later recovery.
`payment.failed` is durably rejected as unsupported because no order-side
transition is approved yet.

### Buyer orders (`ORD-02`, `ORD-04`)

```text
GET  /orders?cursor=&limit=
GET  /orders/{orderId}
POST /orders/{orderId}/cancellation-requests
```

`V2-ORD-02A` exposes the first two routes as authenticated, default-disabled
Order Service reads under `/api/v1`. The feature flag is checked before actor
or repository resolution. While disabled, both reads return
`404 ORDERS_NOT_AVAILABLE` and perform no order repository access.

`GET /api/v1/orders` accepts an optional opaque, versioned cursor of at most
512 characters and an optional `limit` from 1 through 50, defaulting to 20.
Rows are ordered by `(created_at DESC, id DESC)`. The cursor binds both values
from the last returned row, so inserts newer than that position do not shift a
continued traversal. Malformed, unsupported-version, or overlong cursors
return `400 ORDER_CURSOR_INVALID`; invalid limits return
`400 ORDER_LIMIT_INVALID`.

The list response is:

```json
{
  "items": [
    {
      "orderId": "01...",
      "status": "CONFIRMED",
      "paymentStatus": "SUCCEEDED",
      "totalAmount": 25.0000,
      "currency": "USD",
      "createdAt": "2026-07-20T01:00:00Z",
      "updatedAt": "2026-07-20T01:00:00Z",
      "groups": [
        {
          "businessOrderId": "01...",
          "businessId": "01...",
          "storeName": "Demo Store",
          "status": "PENDING_ACCEPTANCE",
          "totalAmount": 25.0000,
          "currency": "USD"
        }
      ]
    }
  ],
  "page": {
    "nextCursor": null,
    "hasMore": false
  }
}
```

`GET /api/v1/orders/{orderId}` returns the same approved header fields and
immutable group summaries, with each group additionally containing its
persisted buyer-facing `storeId` and `items`:

```json
{
  "orderId": "01...",
  "status": "CONFIRMED",
  "paymentStatus": "SUCCEEDED",
  "totalAmount": 25.0000,
  "currency": "USD",
  "createdAt": "2026-07-20T01:00:00Z",
  "updatedAt": "2026-07-20T01:00:00Z",
  "groups": [
    {
      "businessOrderId": "01...",
      "businessId": "01...",
      "storeId": "01...",
      "storeName": "Demo Store",
      "status": "PENDING_ACCEPTANCE",
      "totalAmount": 25.0000,
      "currency": "USD",
      "items": [
        {
          "listingId": "01...",
          "title": "Snapshot title",
          "businessId": "01...",
          "storeId": "01...",
          "unitPrice": 25.0000,
          "currency": "USD",
          "quantity": 1,
          "lineTotal": 25.0000,
          "policyVersion": "LOCAL_DEMO_V1"
        }
      ]
    }
  ],
  "shippingAddress": {
    "label": "Home",
    "recipientName": "Buyer",
    "phone": "+15550123456",
    "line1": "1 Main St",
    "line2": null,
    "city": "Irvine",
    "region": "CA",
    "postalCode": "92618",
    "countryCode": "US"
  }
}
```

The service derives the buyer from the authenticated subject and reads only
rows owned by that buyer. A missing order and an order owned by another buyer
both return the same `404 ORDER_NOT_FOUND` response. The payload never exposes
payment intent or provider references, event IDs, payload hashes, claims,
leases, outbox or history data, internal service fields, seller PII, or
business/admin-private data.

`status` is the stored `orders.status`; aggregate derivation from fulfillment
groups begins only when a future fulfillment transition contract defines the
mapping. `groups[].status` is stored `business_orders.fulfillment_status`.
Shipment snapshots are omitted until `V2-SHP-01` and may be added later
without changing existing meanings. No aggregate `version` is returned because
none is persisted or approved; future shipment or version fields must be
additive.

### Business orders (`ORD-03`)

```text
GET /businesses/{businessId}/orders?status=&cursor=&limit=
GET /businesses/{businessId}/orders/{businessOrderId}
```

`V2-ORD-02B` exposes both routes as authenticated and default disabled through
`order.business-views.enabled=false`. The feature gate runs before validation,
Auth membership resolution, or Order repository access. Disabled calls return
`404 BUSINESS_ORDERS_NOT_AVAILABLE` with zero downstream work.

Auth Service maps `OWNER` to `ORDER_VIEW` plus `ORDER_FINANCE_VIEW` and maps
`MANAGER` to `ORDER_VIEW` only. Order Service forwards the authenticated actor
credential to the active-membership endpoint and also predicates every queue,
detail, item, and address query by the path `businessId`. Missing membership,
missing `ORDER_VIEW`, missing group, and cross-business group all return the
same `404 BUSINESS_ORDER_NOT_FOUND` response.

Queue `limit` defaults to `20` and is bounded to `1..50`. Its opaque,
versioned cursor is bounded to 512 characters and binds
`(business_orders.created_at, business_orders.id)` for descending stable
pagination. `status` is optional and accepts exactly:

```text
PENDING_ACCEPTANCE
ACCEPTED
PROCESSING
SHIPPED
DELIVERED
CANCELLED
```

`CANCELLED` is the seller-facing terminal queue filter and matches stored
`business_orders.cancellation_status = 'CANCELLED'`. The other values match
stored fulfillment status and exclude finally cancelled groups. Response
`status` remains the stored fulfillment status so fulfillment history is not
rewritten; seller clients render `Cancelled` as the primary operational status
whenever `cancellationStatus` is `CANCELLED`.

Queue items contain only business order ID and seller-visible number,
business/store IDs, stored fulfillment and cancellation statuses, buyer order
ID and number, item count and total quantity, group subtotal/total/currency,
confirmed/created/updated timestamps, and a platform fee projection only when
both persisted and authorized by `ORDER_FINANCE_VIEW`. They contain no item or
address detail.

Detail adds stored payment status, immutable item snapshots for that business
group only (`listingId`, title, SKU, condition, thumbnail, unit
price/currency, quantity, line total, and policy version), and the minimum
immutable shipping snapshot (`recipientName`, phone, address lines, city,
region, postal code, and country code).

Detail also returns the business-group optimistic `version`, safe status
timeline (`status`, `occurredAt`), and optional single manual shipment snapshot.
Neither response exposes buyer identity or email, address-book source
identifiers, payment intent/provider/event data, hashes, leases, idempotency
records, outbox/history actor internals, or sibling business groups. Malformed
IDs, status, cursor, and limit return
their bounded `BUSINESS_ORDER_*_INVALID` errors. Auth or Order dependency
failure returns `503 BUSINESS_ORDERS_DEPENDENCY_UNAVAILABLE`.

`V2-ORD-02C` adds the matching read-only seller portal routes
`/seller/orders` and `/seller/orders/{businessOrderId}`. Angular
`features.businessOrders` and gateway
`msb.gateway.features.business-orders` remain independently default-off.
Disabled Angular routes redirect without loading the feature or issuing Order
requests. The disabled gateway owns the business-order namespace locally.
When enabled, the gateway requires authentication, relays the trusted token,
preserves bounded correlation IDs, and removes browser-supplied identity and
role headers before forwarding.

### Admin payment/order operations (`PAY-03`, `ADM-06`)

```text
GET  /admin/operations/payment-order-mismatches
POST /admin/operations/payment-order-mismatches/{id}/retry
```

Retry requires `FINANCE_ADMIN` or a narrower configured permission.

## 11. Fulfillment and Shipping (V2)

```text
POST /businesses/{businessId}/orders/{businessOrderId}/accept
POST /businesses/{businessId}/orders/{businessOrderId}/processing
POST /businesses/{businessId}/orders/{businessOrderId}/shipments
POST /businesses/{businessId}/orders/{businessOrderId}/delivery-demo
```

The bounded shipment request includes manual carrier/service display names,
tracking number, and shipped time for the whole group. State-changing commands
require idempotency and optimistic `If-Match`. Multiple/partial shipments,
carrier APIs/webhooks, and labels remain deferred.

`V2-SHP-01A` defines the first command as authenticated and independently
default disabled through `business-orders.acceptance-enabled=false`. Disabled
requests return `404 BUSINESS_ORDER_ACCEPTANCE_NOT_AVAILABLE` before actor
resolution, Auth lookup, Order persistence, or outbox work. No gateway or
frontend route is added.

`POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/accept` accepts
no body and requires active matching membership with `ORDER_FULFILL`. Auth maps
the current `OWNER` role to this permission and does not map it to `MANAGER`.
Missing membership/permission/group and cross-business access all return `404
BUSINESS_ORDER_NOT_FOUND`; Auth throttling/outage returns `503
BUSINESS_ORDER_ACCEPTANCE_DEPENDENCY_UNAVAILABLE`.

Headers:

```text
If-Match: 0
Idempotency-Key: seller-accept-0001
```

`If-Match` accepts plain or quoted nonnegative long values. Missing/malformed
values return `400 BUSINESS_ORDER_VERSION_REQUIRED`. `Idempotency-Key` must
match `[A-Za-z0-9._:-]{8,128}`; missing/malformed values return `400
BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED`. Path IDs are canonical uppercase
ULIDs; malformed values return `400 BUSINESS_ORDER_ID_INVALID`.

The SQL-owned group must belong to the path business, be
`PENDING_ACCEPTANCE`, have cancellation status `NONE`, have a parent payment
status of `SUCCEEDED`, and match the expected version. Stale versions return
`409 BUSINESS_ORDER_VERSION_CONFLICT`; a correct-version state conflict
returns `409 BUSINESS_ORDER_STATE_CONFLICT`; an unpaid parent returns `409
BUSINESS_ORDER_NOT_PAID`.

Success is `200` with quoted ETag for the new version and exactly:

```json
{
  "businessOrderId": "01K...",
  "fulfillmentStatus": "ACCEPTED",
  "version": 1,
  "updatedAt": "2026-07-20T01:00:00Z"
}
```

Idempotency is unique by actor user ID, business ID,
`ACCEPT_BUSINESS_ORDER`, and key. Its SHA-256 request hash covers operation,
business ID, business-order ID, and expected version. Same hash replays the
original body/ETag; a different hash returns `409
BUSINESS_ORDER_IDEMPOTENCY_CONFLICT`. Unexpected persistence failure returns
`503 BUSINESS_ORDER_ACCEPTANCE_UNAVAILABLE`.

The transition increments only the business-group version, writes immutable
group history and one version-1 `business_order.accepted` outbox event, and
completes the durable idempotency record in one transaction. It does not
change the buyer order, create a shipment, or call payment/inventory.

## 12. Reviews (V3)

```text
POST  /order-items/{orderItemId}/reviews
POST  /trades/{tradeId}/reviews
GET   /listings/{listingId}/reviews?cursor=&limit=
GET   /stores/{storeId}/reviews?cursor=&limit=
GET   /users/{userId}/individual-reputation
PATCH /reviews/{reviewId}
POST  /reviews/{reviewId}/report
```

The server derives reviewer and eligibility from authentication and the
referenced transaction.

Individual reputation response includes:

```json
{
  "data": {
    "userId": "id",
    "completedSalesCount": 12,
    "ratingCount": 10,
    "ratingAverage": "4.80"
  }
}
```

## 13. Notifications (V2)

`V2-NOT-01A` has no HTTP surface. A direct/fake consumer accepts only the
strict `order.confirmed` version-2 envelope and is disabled by default before
parsing or persistence. It canonicalizes and hashes the complete envelope and
payload, durably deduplicates by `(consumerName,eventId)`, creates at most one
`ORDER_CONFIRMED` notification per recipient/event, rejects event-ID hash
conflicts, durably rejects unsupported type/version, and classifies malformed
input as nonretryable poison. The stored projection uses message key
`ORDER_CONFIRMED_V1`, bounded `{orderId}` arguments, and allowlisted route
`/account`; the source envelope is never stored.

`V2-NOT-01B` adds these default-off authenticated routes:

```text
GET   /api/v1/notifications?cursor=&limit=
POST  /api/v1/notifications/{notificationId}/read
POST  /api/v1/notifications/read-all
```

`notifications.read-api.enabled=false` is checked before bearer, cursor,
path, body, Auth, or repository work. Disabled routes return `404
NOTIFICATION_READ_API_NOT_AVAILABLE`. The service accepts only a bounded
`Authorization: Bearer ...` header and relays that exact value plus the
correlation ID to Auth Service `GET /api/v1/users/me`. It requires the returned
internal user ID to be canonical and status `ACTIVE`; it never trusts user or
actor headers, query values, paths, or request bodies.

List uses an opaque versioned cursor bound to `(created_at DESC,id DESC)`, a
default limit of 20, and a permitted limit of 1 through 50. Response:

```json
{
  "data": {
    "items": [{
      "id": "01K...",
      "type": "ORDER_CONFIRMED",
      "messageKey": "ORDER_CONFIRMED_V1",
      "presentationArgs": {"orderId": "01K..."},
      "safeRoute": "/account",
      "read": false,
      "readAt": null,
      "createdAt": "2026-07-20T12:00:00Z"
    }],
    "page": {"nextCursor": null, "hasMore": false}
  }
}
```

No response exposes recipient IDs, source event identity/hash/consumer,
optimistic version, raw JSON, address, payment, or provider fields. Corrupt
stored arguments fail closed. Mark-one and mark-all accept no body and return
`204`; mark-one is idempotent and preserves the first `readAt`, while missing
and cross-user notification IDs both return `404 NOTIFICATION_NOT_FOUND`.
Mark-all returns no affected-row count.

Stable errors include `NOTIFICATION_AUTHENTICATION_REQUIRED` (401),
`NOTIFICATION_ACCESS_DENIED` (403), `NOTIFICATION_CURSOR_INVALID`,
`NOTIFICATION_LIMIT_INVALID`, `NOTIFICATION_ID_INVALID`,
`NOTIFICATION_BODY_NOT_ALLOWED` (400), `NOTIFICATION_NOT_FOUND` (404), and
`NOTIFICATION_IDENTITY_UNAVAILABLE`, `NOTIFICATION_DATA_UNAVAILABLE`, or
`NOTIFICATION_READ_UNAVAILABLE` (503). Validation precedence is feature gate,
bearer shape, query/path/body, Auth resolution, then recipient-owned SQL.

`V2-NOT-01C` adds no new Notification Service API. It introduces an
independent default-off gateway feature,
`msb.gateway.features.notifications=false`, for `/api/v1/notifications/**`.
Disabled gateway routing returns an authenticated hidden 404 without upstream
work. Enabled routing requires normal authentication, relays bearer and
correlation headers, strips browser-supplied identity headers, enforces CSRF
for POST commands, and uses the standard circuit-breaker fallback. The Angular
notification center is also false by default; when enabled it calls only the
three NOT-01B routes, renders only `ORDER_CONFIRMED_V1`, allowlists `/account`,
and fails closed for unsafe routes, corrupt args, or internal fields.

`V2-NOT-01D` expands the same default-off boundary with a count route and
business-scoped seller routes:

```text
GET   /api/v1/notifications/unread-count
GET   /api/v1/businesses/{businessId}/notifications?cursor=&limit=
GET   /api/v1/businesses/{businessId}/notifications/unread-count
POST  /api/v1/businesses/{businessId}/notifications/{notificationId}/read
POST  /api/v1/businesses/{businessId}/notifications/read-all
```

Unread-count responses are `{"data":{"unreadCount":N}}` and always come
from Notification-owned SQL. Buyer calls resolve the active user through the
existing Auth bearer-relay contract. Business calls additionally resolve the
current membership through Auth and require active `ORDER_VIEW` or
`ORDER_FULFILL` permission. The requested business ID is the SQL recipient
scope; client-supplied user, role, or membership headers are never trusted.
Missing and cross-scope notification IDs return the same safe
`404 NOTIFICATION_NOT_FOUND` response.

Supported presentation types are `BUYER_ORDER_CONFIRMED`,
`BUYER_ORDER_CANCELLED`, `BUYER_REFUND_COMPLETED`, `BUYER_ORDER_ACCEPTED`,
`BUYER_ORDER_PROCESSING`, `BUYER_ORDER_SHIPPED`, `BUYER_ORDER_DELIVERED`,
`SELLER_NEW_ORDER`, and `SELLER_ORDER_CANCELLED`. Buyer safe routes are exactly
`/account/orders/{orderId}`; seller safe routes are exactly
`/seller/orders/{businessOrderId}`. Presentation arguments contain only the
required canonical order IDs and optional bounded store display name. The
Order detail endpoints perform their normal authorization after navigation.

The default-off internal `POST /api/v1/internal/notification-events` accepts
only the service-token-authenticated, versioned commerce projection contract.
It returns `202` for newly created and identical replayed sources, `409` for a
same-event-ID hash conflict, `422` for nonretryable invalid mappings, `503` for
retryable persistence failure, and hidden `404` while disabled. It is not a
gateway/browser route. Order's scheduled outbox adapter is the only runtime
caller in this slice.

These preference routes remain deferred:

```text
GET   /notification-preferences
PATCH /notification-preferences
```

## 14. Administration and Support

The original MVP admin boundary includes business application review and
listing moderation. The enforcement release train implements target-specific
user, business, and listing routes. `ADM-REP-00/01/02` adds authenticated
report submission and separate human Trust & Safety triage. `ADM-REP-03` adds
Auth-owned investigation cases without enforcement. Support cases, appeals,
chat evidence, finance/payout operations, advanced
disputes, and AI moderation assistance remain deferred.

```text
POST  /api/v1/reports

GET   /api/v1/admin/reports
GET   /api/v1/admin/reports/{reportId}
POST  /api/v1/admin/reports/{reportId}/claim
POST  /api/v1/admin/reports/{reportId}/release
PATCH /api/v1/admin/reports/{reportId}/severity
POST  /api/v1/admin/reports/{reportId}/dismiss
POST  /api/v1/admin/reports/{reportId}/ready-for-investigation
POST  /api/v1/admin/reports/{reportId}/investigation-case

GET   /api/v1/admin/cases
GET   /api/v1/admin/cases/{caseId}
POST  /api/v1/admin/cases/{caseId}/claim
POST  /api/v1/admin/cases/{caseId}/release
POST  /api/v1/admin/cases/{caseId}/start
POST  /api/v1/admin/cases/{caseId}/reports
POST  /api/v1/admin/cases/{caseId}/reports/{reportId}/unlink
POST  /api/v1/admin/cases/{caseId}/targets
POST  /api/v1/admin/cases/{caseId}/targets/{targetType}/{targetId}/unlink
POST  /api/v1/admin/cases/{caseId}/notes
POST  /api/v1/admin/cases/{caseId}/evidence
PATCH /api/v1/admin/cases/{caseId}/severity
POST  /api/v1/admin/cases/{caseId}/ready-for-action
POST  /api/v1/admin/cases/{caseId}/close-no-action

GET  /admin/support-cases
POST /admin/support-cases/{caseId}/notes
POST /admin/support-cases/{caseId}/responses

GET  /admin/audit-logs
```

Admin report search accepts `unresolved=true` only as a bounded status union of
`SUBMITTED`, `UNDER_TRIAGE`, and `READY_FOR_INVESTIGATION`. It composes with the
existing assignment filter and does not include resolved/read-only
`LINKED_TO_CASE` reports.

Public submission accepts only `targetType`, `targetId`, `reasonCode`, and
optional bounded `description`; identity and snapshots are server-derived. It
returns only `reportId`, `status`, `createdAt`, and `supportReference`. Supported
targets are `USER`, `BUSINESS`, and `LISTING`. Duplicate reporter/target/reason
submissions inside 24 hours return `409 REPORT_ALREADY_SUBMITTED`.

The inbox supports `q`, `status`, `targetType`, `reasonCode`, `severity`,
`assignment`, `createdFrom`, `createdTo`, `page`, `size`, and stable `sort`.
Assignment is `UNASSIGNED|ASSIGNED_TO_ME|ASSIGNED|ALL`. Mutations carry
`expectedVersion`; stale state returns `409 REPORT_VERSION_CONFLICT`. Claim and
release require `admin.report.assign`; reads require `admin.report.read`;
severity, dismiss, and ready require `admin.report.resolve` and assignment to
the current admin. Detail returns report-time snapshot and current target as
separate values, bounded same-target reports, safe active-enforcement context,
timeline, and authoritative capabilities. No report command creates enforcement
or changes marketplace target state. Full reason/status/privacy rules are in
`docs/mvp/adm/admin-reporting.md`.

Case search supports `q`, operational `status`, `severity`, `targetType`,
`assignment`, created/updated ranges, pagination, and stable sort. Reads require
`admin.report.read`; claim/release require `admin.report.assign`; investigation
mutations require `admin.report.investigate`; conclusions require
`admin.report.resolve`. Creating from a report derives the primary target and
defaults severity to the report when omitted. Report link/unlink validates both
expected versions atomically. Notes require a bounded idempotency key. Evidence
accepts only validated report snapshots, linked-target snapshots, or existing
enforcement context. `READY_FOR_ACTION` enables only the explicit case-linked
enforcement plan; `CLOSED_NO_ACTION` and `CLOSED_ACTIONED` are read-only.

Case-linked enforcement endpoints are:

```text
POST  /api/v1/admin/cases/{caseId}/enforcement-proposals
PATCH /api/v1/admin/cases/{caseId}/enforcement-proposals/{proposalId}
POST  /api/v1/admin/cases/{caseId}/enforcement-proposals/{proposalId}/dry-run
POST  /api/v1/admin/cases/{caseId}/enforcement-proposals/{proposalId}/execute
POST  /api/v1/admin/cases/{caseId}/enforcement-proposals/{proposalId}/cancel
POST  /api/v1/admin/cases/{caseId}/close-actioned
```

Creation, editing, cancellation, dry run, execution, and closure require the
assigned resolver and `admin.report.resolve`. Dry-run and execution also
compose the target-specific enforcement permission. Execute accepts only
expected case and proposal versions plus a stable idempotency key. Listing
dispatch uses a token-authenticated internal Product route with the current
admin bearer token; it is not gateway-routed. See
`docs/mvp/adm/admin-case-enforcement.md`.

A successful dry run may refresh only the proposal's bounded technical preview
cache. It does not change the case version, append a case timeline event,
consume the execution idempotency key, or mutate the target. Proposal edits and
target-version changes invalidate that preview before execution.

## 15. Agent APIs and Tools (V3)

`AI-LLM-01` adds only internal agent-service liveness and configuration
readiness endpoints:

```text
GET /health
GET /ready
```

They are not exposed through the gateway or `/api/v1`, and neither endpoint
makes a paid provider request. Customer-facing agent routes remain deferred to
their product slice.

`AI-RAG-02B` extends the readiness response with
`knowledgeIngestion=DISABLED|READY|UNAVAILABLE`. Enabled intake is `READY` only
while the schema-validated Kafka task is running. The overall response may
remain `503 NOT_READY` when provider configuration is absent even though
durable intake is ready; this does not stop ingestion or make a provider call.

`AI-DISC-SEARCH-STAB-P1-14` extends the same readiness response with
`discoveryDocumentEmbedding=DISABLED|READY|UNAVAILABLE|DEFERRED`. The field
describes only the default-off Agent discovery-document embedding intake and
worker lifecycle. Disabled and kill-switched states do not create Product,
Kafka, provider, persistence, or network work; enabled readiness does not
claim event-drain, V2 promotion, hybrid-search rollout, or browser evidence.

`AI-RAG-02D` adds no HTTP route. Its local/deployment operator CLI accepts
only exact run/job IDs, a bounded operator identity, and the fixed listing
rebuild operation. Rebuild validation and promotion cannot accept arbitrary
URLs, index names, raw OpenSearch queries, or wildcards. Direct low-level
index promotion/rollback is rejected; promotion and rollback are bound to a
durable rebuild run and its recorded generations.

### Internal listing knowledge sources (`AI-RAG-02A`)

Product Service exposes immutable approved public listing-source versions only
to the agent service:

```text
GET /api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}
GET /api/v1/internal/agent/knowledge/listings/export?cursor=&limit=
```

Both routes require `X-Agent-Internal-Service-Token`. This credential is
distinct from browser authentication and commerce service credentials and is
compared in constant time. Missing or invalid credentials return `403`.

The exact route accepts one listing ID and non-negative version and never
falls back to a newer version. Unknown exact versions return
`404 LISTING_KNOWLEDGE_SOURCE_NOT_FOUND`.

Active response:

```json
{
  "sourceType": "LISTING",
  "sourceId": "01L00000000000000000000001",
  "sourceVersion": "12",
  "supersedesVersion": "11",
  "lifecycle": "ACTIVE",
  "visibility": "PUBLIC",
  "language": "und",
  "effectiveFrom": "2026-07-18T12:00:00Z",
  "sourcePublishedAt": "2026-07-18T10:00:00Z",
  "contentHash": "lowercase-sha256",
  "content": {
    "title": "Used bicycle",
    "description": "Seller-provided approved public description.",
    "price": {
      "amount": "250.0000",
      "currency": "USD"
    },
    "publicLocation": {
      "city": "Irvine",
      "region": "CA"
    }
  }
}
```

An `INVALIDATED` source omits `content`, `contentHash`, `effectiveFrom`, and
`sourcePublishedAt`; it includes `invalidatedAt` and the exact
`supersedesVersion`.

Export uses `limit=1..200`, defaults to `100`, and returns:

```json
{
  "items": [],
  "nextCursor": null,
  "hasMore": false,
  "exportWatermark": "2026-07-18T12:00:00Z"
}
```

The cursor is opaque and carries the fixed watermark. Each listing contributes
only its latest version at that watermark, and only when that version is
`ACTIVE`. Draft, pending, rejected, paused, sold, closed, removed, business,
and invalidated sources are excluded.

Only title, approved description, decimal price/currency, and public
city/region may appear in an active source. Seller identity, contact data,
exact address or coordinates, media, moderation evidence, internal notes,
payment/delivery preferences, storage fields, and credentials are forbidden.

### Internal Product discovery embedding source (`AI-DISC-SEARCH-P0-04A`)

Product Service exposes one independently default-disabled, request-bound
source route:

```text
GET /api/v1/internal/agent/discovery/embedding-requests/{requestId}/source
```

`requestId` is a canonical 26-character uppercase ULID and is resolved to a
durable Product-owned embedding request. The route requires
`X-Agent-Internal-Service-Token`, using the existing constant-time comparison.
Gate precedence is feature disabled, internal authentication, request
validation, then Product lookup. The source is returned only while the exact
stored listing version and document/input hashes still match the current
active, approved public individual listing. Product never substitutes a newer
listing version.

Successful response:

```json
{
  "schemaVersion": "MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1",
  "requestId": "01ARZ3NDEKTSV4RRFFQ69G5FAC",
  "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAD",
  "listingVersion": 7,
  "documentSchemaVersion": "MARKETPLACE_LISTING_DISCOVERY_V2",
  "documentHash": "lowercase-sha256",
  "embeddingInputSchemaVersion": "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
  "embeddingInputHash": "lowercase-sha256",
  "normalizerVersion": "NFKC_WHITESPACE_V1",
  "redactorVersion": "PUBLIC_CONTACT_REDACTION_V1",
  "language": "und",
  "embeddingIdentity": {
    "provider": "openai",
    "model": "text-embedding-3-small",
    "dimensions": 1536
  },
  "embeddingText": "TITLE\n...\nCATEGORY\n...\n...\nDESCRIPTION\n..."
}
```

The text contains only normalized/redacted title, category name/slug, and
description. `publicCity` and `publicRegion` remain the canonical Product
location field names but are not included in embedding text. Seller/contact,
price, condition, availability, location, images, moderation, storage,
transaction, vector, provider-response, and credential fields are forbidden.

Stable errors are `404 FEATURE_DISABLED`,
`403 AGENT_INTERNAL_AUTHENTICATION_REQUIRED`, `400 INVALID_REQUEST`,
`404 LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND`, and
`503 LISTING_DISCOVERY_EMBEDDING_UNAVAILABLE`. Missing, stale, changed-hash,
deleted, and ineligible requests are intentionally non-enumerating.

### Product existing-listing embedding-request backfill (`AI-DISC-SEARCH-P0-09`)

Product exposes a separate default-disabled platform-admin boundary:

```text
POST /api/v1/admin/search/listings/embedding-request-backfills
GET  /api/v1/admin/search/listings/embedding-request-backfills/{runId}
POST /api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume
```

Commands accept no body. Product derives the finite listing-ID watermark,
cursor, page bounds, 04A request identity, event topic, and actor. The run ID
is a canonical 26-character Crockford ULID. Authentication and existing
platform-admin authorization precede feature gates and lookup.

The strict response schema
`MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1` contains only run
ID, command outcome, fixed state, page/created/already-present/skipped/failure
counts, fixed safe error code, and timestamps. It never returns watermark,
cursor, listing/request/event IDs, hashes, listing content, vector, provider
data, or credentials.

Stable errors are `404 FEATURE_DISABLED`,
`401 AUTHENTICATION_REQUIRED`, `403 LISTING_FORBIDDEN`,
`404 LISTING_EMBEDDING_BACKFILL_NOT_FOUND`,
`409 LISTING_EMBEDDING_BACKFILL_ACTIVE`,
`409 LISTING_EMBEDDING_BACKFILL_BUSY`,
`409 LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED`,
`400 LISTING_INVALID_REQUEST`, and
`503 LISTING_EMBEDDING_BACKFILL_UNAVAILABLE`.

### Internal owned-draft listing media tool (`AI-LIST-01C`)

Product Service exposes one default-disabled, read-only Agent tool route:

```text
POST /api/v1/internal/agent/listings/{listingId}/draft-media
```

The route is not gateway/browser-routed. It requires
`X-Agent-Internal-Service-Token`, compared in constant time, plus a bounded
correlation ID. The request is strict:

```json
{
  "schemaVersion": "ai-list-owned-draft-media-v1",
  "actorUserId": "01ARZ3NDEKTSV4RRFFQ69G5FAA",
  "mediaIds": ["01ARZ3NDEKTSV4RRFFQ69G5FAE"]
}
```

`actorUserId`, path listing ID, selected media IDs, and correlation context
come from authenticated application orchestration and are never model output.
Product still independently proves that the actor owns the individual listing,
that its state is `DRAFT` or `CHANGES_REQUESTED`, and that every selected media
object belongs to the same actor/listing and is uploaded and not
pending/rejected. Product captures that authorization and metadata in one
read-only catalog transaction, closes the transaction, and only then reads the
selected object bytes from storage.

At most four JPEG, PNG, or WebP images are returned, limited to 10 MiB each and
20 MiB total. Product checks stored metadata against actual byte length, MIME
signature, and SHA-256 before returning request-order content:

```json
{
  "schemaVersion": "ai-list-owned-draft-media-v1",
  "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAC",
  "listingVersion": "12",
  "eligibility": "OWNED_DRAFT",
  "media": [
    {
      "mediaId": "01ARZ3NDEKTSV4RRFFQ69G5FAE",
      "contentType": "image/png",
      "byteSize": 1024,
      "sha256": "lowercase-sha256",
      "sourceVersion": "7",
      "contentBase64": "bounded-base64"
    }
  ]
}
```

The response never contains seller identity/PII, moderation data, filenames,
object buckets/keys, signed/public URLs, credentials, or unrelated listing
fields. Cross-actor, cross-listing, missing/deleted, and ineligible state use
hidden `404 AGENT_LISTING_MEDIA_NOT_FOUND`. Unsupported or integrity-invalid
media uses `422 AGENT_LISTING_MEDIA_REJECTED`; disabled/storage-unavailable
behavior uses `503 AGENT_LISTING_MEDIA_UNAVAILABLE`; invalid service
authentication uses `403 AGENT_LISTING_MEDIA_FORBIDDEN`.

`agent.listing-media-tool-enabled` defaults to `false`. Agent Service has a
second default-false, unwired adapter gate and makes no Product request while
disabled. Agent revalidates base64, byte length, SHA-256, MIME magic,
listing/media identity, and deterministic order before the bytes can enter the
proposal boundary. No Product mutation or proposal application exists on this
route.

### Category guidance ownership (`AI-KNOW-01`)

Product Service owns one public guidance source per category and language.
Platform-admin routes are:

```text
GET  /api/v1/admin/categories/{categoryId}/guidance/{language}
GET  /api/v1/admin/categories/{categoryId}/guidance/{language}/versions?cursor=&limit=
POST /api/v1/admin/categories/{categoryId}/guidance/{language}/versions
POST /api/v1/admin/categories/{categoryId}/guidance/{language}/retire
```

Publish and retire require platform-admin authorization and `If-Match`.
`If-Match: "0"` means the caller expects no existing source; otherwise it
contains the latest source version. Stale or missing versions return
`409 CATEGORY_GUIDANCE_VERSION_CONFLICT`.

Publish accepts only:

```json
{
  "title": "Buying used electronics",
  "body": "Check the model, included accessories, and visible condition."
}
```

Title is 1 through 180 characters and body is 1 through 12,000 characters
after trimming. Both are plain text; unsupported controls and HTML/script
payloads are rejected. Path language is normalized and validated. The server
derives admin identity, correlation ID, category snapshots, next version,
visibility, effective time, and content hash.

Publication requires an active category and inserts a new immutable `ACTIVE`
version. Retirement inserts a newer `INVALIDATED` version. Both return the new
source shape and an `ETag` containing its version. Historical versions are not
edited.

Agent-only reads are:

```text
GET /api/v1/internal/agent/knowledge/category-guidance/{categoryId}/languages/{language}/versions/{sourceVersion}
GET /api/v1/internal/agent/knowledge/category-guidance/export?cursor=&limit=
```

Both require `X-Agent-Internal-Service-Token`, use constant-time credential
comparison, and never accept browser authorization as a substitute. The exact
route never falls back to latest.

Active response direction:

```json
{
  "sourceType": "CATEGORY_GUIDANCE",
  "sourceId": "01K00000000000000000000002",
  "sourceVersion": "1",
  "supersedesVersion": null,
  "lifecycle": "ACTIVE",
  "visibility": "PUBLIC",
  "language": "en",
  "effectiveFrom": "2026-07-19T08:00:00Z",
  "contentHash": "lowercase-sha256",
  "content": {
    "categorySlug": "electronics",
    "categoryName": "Electronics",
    "title": "Buying used electronics",
    "body": "Check the model, included accessories, and visible condition."
  }
}
```

An invalidated response omits content, content hash, and effective time and
includes `invalidatedAt` plus exact `supersedesVersion`.

Export uses `limit=1..200`, defaults to `100`, and carries a fixed watermark
in its opaque cursor. It returns only the greatest version per
category/language when that version is active and the category remains active.

Planned user-facing agent routes use the authenticated BFF and external
`/api/v1` prefix:

```text
POST /api/v1/agent/sessions
POST /api/v1/agent/sessions/{sessionId}/messages
GET  /api/v1/agent/sessions/{sessionId}
GET  /api/v1/agent/sessions/{sessionId}/messages?cursor=&limit=
```

`AI-CS-01A` implements the Agent Service persistence underneath these routes.
`AI-CS-01B` implements their authenticated BFF/service boundary,
authorization, validation, listing eligibility, cursor, correlation, and
standard error contracts. The capability remains disabled by default pending
AI-CS-01C answer orchestration.

### Seller listing proposal review (`AI-LIST-02A`)

The existing default-off Agent gateway family exposes:

```text
POST /api/v1/agent/listing-proposals
GET  /api/v1/agent/listing-proposals/{proposalId}
POST /api/v1/agent/listing-proposals/{proposalId}/dismiss
```

The gateway relays the authenticated bearer token, strips spoofed identity
headers, applies its existing session CSRF rule to both POST routes, and exempts
GET. Agent Service resolves the actor through Auth Service. The request never
accepts actor, user, role, prompt, provider, model, or generated-output fields.
The JSON body is limited to 8 KiB and rejects unknown fields.

Exact create request:

```json
{
  "schemaVersion": "LISTING_PROPOSAL_V1",
  "listingId": "01L00000000000000000000001",
  "expectedListingVersion": 12,
  "mediaIds": ["01M00000000000000000000001"],
  "clientRequestId": "seller-review-0001"
}
```

IDs are canonical ULIDs. `expectedListingVersion` is `1..2147483647`;
`mediaIds` contains one through four unique IDs; and `clientRequestId` is
16–64 ASCII characters matching `[A-Za-z0-9._:-]+`. Media IDs are sorted only
for canonical hashing.

A new create returns `201`; an exact replay returns `200` and never reruns
Product or provider work. The response includes `proposalId`, `status`,
`proposalVersion`, external and AI-LIST schema versions, listing/source
version, bounded verified media evidence, the strict AI-LIST-01 proposal,
seller-confirmation flags, timestamps, and bounded result metadata. Evidence
contains only media ID, evidence ID, SHA-256, verified MIME, and byte size.
Result metadata contains only instruction/schema version, `FAKE` or `LIVE`,
safe result code, latency, and token counts.

Statuses are `READY`, `DISMISSED`, and `EXPIRED`. Only `READY` contains
proposal, evidence, and result metadata. Dismissed or expired representations
contain only status, IDs, versions, and timestamps. `READY` transitions only
to `DISMISSED` or `EXPIRED`; terminal state never reopens.

Create is unique by `(actorUserId, clientRequestId)` for 90 days. Its canonical
hash covers schema version, listing ID, expected listing version, and sorted
media IDs. Same key plus a different hash returns
`409 AI_PROPOSAL_IDEMPOTENCY_CONFLICT` before Product/provider execution.
Dismiss requires `Idempotency-Key`, 8–128 visible ASCII characters, and is
idempotent by `(actorUserId, proposalId, key)`.

Stable errors are:

- `400 INVALID_REQUEST`;
- `401 AUTHENTICATION_REQUIRED`;
- hidden `404 AGENT_LISTING_PROPOSAL_NOT_FOUND` for missing/cross-actor rows;
- `404 FEATURE_DISABLED` when the direct API is off;
- `409 AI_PROPOSAL_IDEMPOTENCY_CONFLICT`,
  `AI_PROPOSAL_SOURCE_VERSION_CONFLICT`, or
  `AI_PROPOSAL_STATE_CONFLICT`;
- `410 AI_PROPOSAL_EXPIRED`;
- `413 PAYLOAD_TOO_LARGE`; and
- `503 AI_PROPOSAL_UNAVAILABLE`.

The gateway-off state produces its existing 404 with zero Agent request. The
direct API gate is checked before actor resolution or persistence. GET and
dismiss need only API, persistence, and actor resolution. Exact replay is
resolved before generation gates. A new create requires, in order, a clear
kill switch, enabled orchestration, enabled/configured Product media tool, and
enabled/configured multimodal provider. All committed/default flags are false.
No route writes Product data, applies a proposal, submits, or publishes.

Approved V3 tool direction outside the listing customer-service session:

```text
searchListings
compareListings
getMyOrder
draftTradeMessage
draftListingContent
summarizeAssignedSupportCase
```

Tool rules:

- Each tool executes with the requesting actor's permissions.
- `getMyOrder` cannot accept an arbitrary user ID.
- Support summary requires case assignment or explicit access.
- Draft tools return proposals and never persist automatically.
- Every tool call records session, actor, arguments hash, result status,
  latency, token usage, and correlation ID.

### Marketplace Discovery streaming messages (`AI-DISC-UX-P1-03`)

#### Parallel Marketplace Agent V2 (`AI-DISC-AGENT-V2-PLAN-01`)

The legacy `/api/v1/agent/discovery/**` routes remain operational. The parallel
V2 surface is independently default-off and uses:

```text
POST /api/v1/agent/marketplace-v2/sessions
GET  /api/v1/agent/marketplace-v2/sessions/{sessionId}/messages
POST /api/v1/agent/marketplace-v2/sessions/{sessionId}/messages
POST /api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/stream
POST /api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/{clientMessageId}/stop
POST /api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/{userMessageId}/response-retry/stream
```

V2 uses `MARKETPLACE_AGENT_V2` sessions and accepts one natural terminal model
response or one strict tool proposal per decision. The tools are
`check_availability`, `search_listings`, `get_listing`, and the no-I/O control
proposal `request_confirmation`. The additional no-I/O
`collect_listing_information` control starts structured seller field
collection but cannot create or publish a listing. The backend validates
schema, actor/session scope, known-listing context, duplicates, cancellation,
and the global maximum of five decisions. RAG, order, account, seller
draft/publication, and handoff integrations are not registered.

`AI-DISC-AGENT-V2-SELLER-CONTEXT-03` stores an additive private session state:

```json
{
  "activeWorkflow": {
    "type": "CREATE_LISTING",
    "status": "COLLECTING_INFORMATION",
    "collectedFields": {
      "itemType": {"status": "PROVIDED", "value": "book", "reason": null},
      "title": {"status": "MISSING", "value": null, "reason": null}
    },
    "itemTypeEligibility": "SUPPORTED"
  },
  "pendingInteraction": {
    "type": "ANSWER_FIELD",
    "workflowType": "CREATE_LISTING",
    "field": "TITLE",
    "question": "What title would you like to use for the listing?",
    "acceptsReplacement": false,
    "status": "WAITING"
  }
}
```

A committed answer to a `WAITING` seller field is consumed exactly once before
scope classification and never becomes an inventory query. The public message
still uses the generic assistant DTO; its optional `pendingInteraction` adds
nullable `workflowType`, `field`, and `question` properties. Confirmation
interactions retain their existing action/arguments contract. Explicit similar
listing or pricing requests may use Product search while preserving the seller
workflow. There is no create-draft or publish-listing tool and no migration.

`AI-DISC-AGENT-V2-PENDING-FIELD-04` makes the private field-state shape
explicit. Field status is `MISSING|PROVIDED|REJECTED|DEFERRED|NEEDS_HELP`.
`PROVIDED` contains a validated value; `REJECTED` contains the refused value
plus the allowlisted reason `UNSUPPORTED_CATEGORY`. Legacy string fields deserialize as
`PROVIDED`. Pending replies are resolved privately as
`VALUE_PROVIDED|UNKNOWN|DEFER|REQUEST_HELP|CANCEL_WORKFLOW|CORRECTION|REPLACE_FIELD_VALUE|UNRELATED_OR_NEW_INTENT`
before scope classification. Unknown/help replies retain the waiting field;
defer advances without a literal value; cancellation closes it; unrelated
turns do not consume it.

`AI-DISC-AGENT-V2-WORKFLOW-CONTINUITY-05` makes unsupported item type a field
rejection rather than a terminal workflow. The active `CREATE_LISTING` workflow
remains `COLLECTING_INFORMATION`, and its private persisted `ANSWER_FIELD`
interaction sets `acceptsReplacement: true`. Contextual replacement
replies are resolved exactly once before scope/model/tool routing; they cannot
run Product, embedding, or RAG work. `collect_listing_information` accepts an
optional bounded `itemType`, and the backend also preserves an explicit item
type from the initiating seller sentence after the model selected that tool.
Older stored `UNSUPPORTED` workflow JSON is upgraded under the same row lock on
the next replacement reply. The marker is passed only in model context and is
omitted from the public assistant DTO. No database migration or SSE event type
changes.

The generic assistant message contract is:

```json
{
  "role": "ASSISTANT",
  "content": "Customer-facing text",
  "attachments": [],
  "refinement": null,
  "pendingInteraction": null,
  "citations": [],
  "toolActivity": [],
  "inputTokens": 0,
  "outputTokens": 0
}
```

Listing attachments contain only revalidated public Product facts. The strict
`MARKETPLACE_AGENT_V2_STREAM_EVENT_V1` SSE types are `message_started`,
`activity`, `tool_completed`, `text_delta`, `attachments`, `error`, and `done`.
Only terminal `response.output_text.delta` content is forwarded. Provider
reasoning/lifecycle events, prompts, raw tool arguments, vectors, scores, and
private observations are neither streamed nor persisted.

Each `LISTING` attachment includes nullable `thumbnailUrl`. When present it is
the first ordered approved Product image and must match
`/api/v1/public/listing-media/{imageId}`; clients must not accept arbitrary
external image origins. A missing or failed image renders a non-authoritative
placeholder and does not invalidate the listing attachment. The image path is
persisted with the attachment so completed history restores the same card once.

`AI-DISC-AGENT-V2-TERMINAL-COVERAGE-04` clarifies that a terminal V2 SSE
`error` is response-retryable only after the committed USER invocation has a
guarded `PARTIAL` assistant row. Clients reconcile history immediately and
render that assistant plus `Retry response`; they never repost the USER turn.
If the five-decision budget ends after a successful `search_listings`
observation, the `done` response contains a deterministic grounded summary and
the already validated attachments in the same turn rather than asking the user
to continue.

`AI-DISC-AGENT-V2-GUARDED-RECOVERY-05` adds no wire fields. Explicit requests
for hidden instructions/internal reasoning/private seller contact and explicit
medical cure or treatment requests complete as normal canonical refusal
messages with no model call, tool call, attachments, or Retry response. An
unsupported contextual comparison may be replaced only with text composed from
the active recommendation set's validated public facts; it cannot trigger a
broad search or introduce a listing. An unsupported response to obvious
keyboard-noise input becomes one no-tool clarification. Generic validation
failures retain the guarded retryable failure contract but must not claim that
a marketplace service was unavailable.

`AI-DISC-AGENT-V2-ORDINAL-PRESENTATION-06` keeps the first and second ordinal
labels bound to the active recommendation order. Customer text must not contain
serialized condition values such as `LIKE_NEW`, relevance fields such as
`match: RELATED`, or a confirmation question asking to display cards already
attached to the response. The existing attachment order remains authoritative;
there are no new wire fields.

`AI-DISC-AGENT-V2-MESSAGES-CUTOVER-07` changes only frontend route/component
ownership. In a `marketplaceAgentV2` build, the Marketplace assistant entry in
`/account/messages` uses the existing `/api/v1/agent/marketplace-v2` session,
history, streaming, Stop, and response-Retry contracts. Buyer/seller messaging
continues to use Chat Service. Legacy assistant URLs redirect to
`/account/messages`; the separate authenticated V2 evaluation URL remains.

`AI-DISC-AGENT-V2-FLOATING-CUTOVER-08` applies the same frontend-only ownership
rule to the authenticated floating Marketplace assistant. Its compact V2
surface uses the existing V2 API contracts and does not route assistant turns
through Chat Service. Human buyer/seller conversations in that panel remain
unchanged, and flag-off builds retain the legacy Discovery fallback.

`AI-DISC-AGENT-V2-SCOPE-GATE-01` adds no public V2 request, response, history,
or SSE field. After durable USER acceptance, V2 records an internal strict
scope category and either continues into ReAct, answers conversationally with
tools blocked, applies the existing safety refusal, or completes a clear
out-of-scope request with one ordinary assistant message. Out-of-scope streams
contain `message_started`, terminal `text_delta`, and `done` only; they contain
no activity, tool-completion, attachment, citation, or refinement event.
Low-cardinality scope metadata may be retained in private assistant actions,
but is ignored by the public history decoder and never rendered.

`AI-DISC-AGENT-V2-SCOPE-GROUNDING-02` extends that private action with
`requiredGrounding=NONE|LISTING_DATA|KNOWLEDGE_RAG|PRIVATE_TOOL`. The public
request, response, history, and SSE schemas are unchanged. Unsupported direct
factual prose is rejected before exposure and returned to the controlled loop
as `GROUNDING_REQUIRED`. Successful listing answers retain private evidence
actions containing source type, source identity, version/hash, and retrieval
time; those fields are not included in customer text or frontend DTOs.
Knowledge-policy and actor-private questions safely abstain when their real
runtime tool is absent. They are never answered from general model knowledge or
routed through Product search.

The Stop result is one of `NOT_COMMITTED`, `STOPPED`, `COMPLETED`, or
`TERMINAL`. `STOPPED` means the USER row committed and a retryable `PARTIAL`
assistant is authoritative, including the zero-text case. Response Retry
accepts only the original `clientMessageId`, reuses the committed USER and
invocation identity, and never inserts another USER row. Failed history entries
include a bounded `responseRetryUserMessageId`; this also covers an orphaned
final USER row from a previously interrupted response.

`search_listings` observations distinguish `CATEGORY_UNAVAILABLE`,
`FILTERS_TOO_STRICT`, and `SEARCH_UNAVAILABLE`. Listing attachments are allowed
only from validated relevant matches, with exact matches ordered before related
alternatives; unavailable and failed outcomes have none.
`check_availability` performs only Product's authoritative broad-category count
probe and never creates attachments. An identical normalized proposal executes
at most once in one turn; a changed query or filter may execute after backend
validation. Stored observations inform the next model decision without becoming
a deterministic cross-turn routing gate. The exact `never mind` cancellation
response has decision count zero; all model-driven turns remain within the
five-decision ceiling.

`AI-DISC-AGENT-V2-INVENTORY-FACETS-02` makes broad-search clarification
result-aware. Results-first Agent V2 requests the additive internal Product
response version `MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3`; omitted
response-version requests continue to return V1 and explicit V2 requests keep
their deployed facet-only shape. V3 adds this server-owned object:

```json
{
  "discovery": {
    "normalizedCategory": "chair",
    "totalMatches": 25,
    "relevantMatchCount": 20,
    "exactMatchCount": 12,
    "relatedMatchCount": 8,
    "retrievalConfidence": "HIGH",
    "reason": "RESULTS_AVAILABLE",
    "facets": {
      "subtype": [{"value":"Dining Chair","count":12}],
      "condition": [],
      "priceBand": [],
      "location": []
    }
  }
}
```

Each facet list is bounded to 12 at the Product boundary; Agent retains at
most four values per facet in its private structured observation.
`totalMatches` counts bounded current filter-matching candidates;
`relevantMatchCount` counts candidates that also pass Product's product-concept
and accessory rejection rules. `exactMatchCount` and `relatedMatchCount`
classify that bounded relevant set; full multi-concept matches sort first and
fused rank breaks ties. Confidence is derived from bounded ratios and
lexical-branch evidence without exposing raw scores. Useful Product-revalidated
results remain visible even when internal confidence is low. The terminal model
sees customer-safe counts but not card facts, listing IDs, facets, or confidence
metadata. The nullable `refinement {question, options[]}` uses customer-oriented
`MATCH_SCOPE`, `MAXIMUM_PRICE`, `CONDITION`, or `LOCATION` actions; legacy
subtype and price-band options remain readable. New responses set `question`
to null: the backend supplies action metadata only and never appends prose to
the model answer. The UI renders at most one follow-up area after attachments,
and selecting a non-confirming suggestion fills the composer without
sending. Observation/refinement metadata is restored from bounded V2 actions
JSON.

`AI-DISC-AGENT-V2-CONTEXTUAL-RESPONSE-01` adds an optional strict pending
interaction to the generic message and V2 session JSON:

```json
{
  "id": "01...",
  "type": "CONFIRM_ACTION",
  "action": "RUN_REFINED_SEARCH",
  "arguments": {},
  "status": "WAITING",
  "createdAt": "2026-07-30T00:00:00Z"
}
```

Only a `WAITING` interaction binds `yes` or `no`. Consumption is atomic and
single-use; repeated confirmation cannot execute the action again. Ordinary
listing display, comparison, and ordinal questions do not create confirmation
state. The latest result-bearing assistant message is the active ordered
recommendation set, while earlier cards remain immutable history.

`AI-DISC-AGENT-V2-RESULTS-FIRST-CONTINUITY-03A` adds an internal-only
`contextualRefinement {facet,value,activeQuery,searchQuery}` to a V2 model
decision when the current short reply exactly equals a subtype in the latest
successful Product search observation, or its revalidated attachments' public
category when Product V4 omits subtype facets. It is not a browser or persistence DTO.
The provider must propose `search_listings` with `searchQuery`; backend policy
rejects terminal prose or a mismatched query and returns only the existing safe
`GROUNDING_TOOL_REQUIRED` observation within the five-decision budget. SSE
continues to expose only truthful tool activity, terminal text, attachments,
and completion. For a `CATEGORY` refinement, `searchQuery` remains the active
product query and the internal strict tool proposal must also carry the selected
`categoryName`. The Agent filters only Product-revalidated listing facts by that
public category name; it never appends a broad taxonomy value such as `General`
to the free-text query. An empty filtered slice is `FILTERS_TOO_STRICT`, not
proof of `CATEGORY_UNAVAILABLE`. This tool argument is not added to browser or
history DTOs.

`AI-DISC-AGENT-V2-RESULTS-FIRST-CONTINUITY-03B` does not add a wire field.
Within one invocation, the backend permits at most one executed
`search_listings` action; after its `SUCCEEDED|FAILED` observation, the next
provider decision receives no tool registry and must produce terminal prose.
The policy independently rejects any second search proposal as
`DUPLICATE_TOOL_CALL`. A grounded selection follow-up such as `the second one`
or `show me a cheaper one` may copy exactly the selected attachment from the
latest ordered active recommendation set onto the new assistant message. That
attachment keeps its existing internal listing identity for follow-up routing,
while customer prose and SSE continue to exclude IDs. Comparison-only turns
that do not select a card retain the existing no-duplicate-attachment behavior.
Individual listings link to a listing page for buyer-seller coordination; V2
must not describe that surface as a purchase, checkout, or order page.

`AI-DISC-AGENT-V2-ACTION-EVIDENCE-02` requires every customer-facing action
claim to match an allowlisted successful tool observation. A prose-only yes/no
question is invalid unless the response carries a persisted `WAITING`
interaction. V2 does not claim to open galleries, retrieve extra photos or
private seller instructions, contact sellers, or start purchases; it directs
customers to the existing listing attachment/page instead. Unsupported
terminal content fails with the existing retryable V2 failure contract, so a
committed user message still restores with `Retry response` rather than an
accepted fabricated action.

`AI-DISC-AGENT-V2-MULTICONCEPT-RERANK-05` adds the opt-in internal Product
response `MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4`; V1-V3 remain shape
compatible. V4 performs Product-owned concept compatibility filtering and
constrained deterministic reranking after hybrid candidate retrieval and
authoritative MySQL revalidation. Each returned result adds:

```json
{
  "conceptMatch": {
    "relevance": "HIGH",
    "completeConceptMatch": true,
    "productTypeCompatible": true,
    "matchedConcepts": ["key storage", "pouch or case"]
  }
}
```

V4 `discovery` also adds bounded `rerankContext {coreConcepts,
candidateProductTypes,excludedBroadTypes}` and `rejectedCandidateCount`.
Returned results contain only `HIGH|MEDIUM` matches, `HIGH` precedes `MEDIUM`,
and `exactMatchCount + relatedMatchCount == relevantMatchCount`.
`rejectedCandidateCount + relevantMatchCount == totalMatches`. Product returns
no numeric scores, vectors, private listing fields, or model deliberation.
Agent maps `HIGH` to attachment `matchQuality=EXACT` and `MEDIUM` to
`matchQuality=RELATED`, then revalidates each selected listing through the
existing detail API. `matchQuality` is nullable/omittable so earlier stored V2
history remains valid.

`AI-DISC-CS-P1-06` uses one model-first loop capped at five decisions. A
decision is either bounded natural assistant content or exactly one proposed
tool call. The runtime registry is the full tool allowlist:
`CHECK_AVAILABILITY`, `SEARCH_INDIVIDUAL`, and `GET_LISTING`. No direct-response,
knowledge, account, order, seller, or handoff pseudo-tool is advertised. The
provider uses optional tool choice; backend policy validates schemas, actor
scope, capability, cancellation, duplicates, and the remaining step budget.
Rejected calls become a low-cardinality structured observation for the next
model decision and are never silently replaced.

Natural terminal content is streamed from the same Responses API decision via
`response.output_text.delta`; it is accumulated, validated, persisted once,
and must exactly match the completed/history message. Reasoning events, raw
tool arguments, prompts, vectors, scores, IDs, and provider errors are never
streamed. A decision containing both user-facing text and a tool call fails
closed. The legacy `DISCOVERY_RESULT` public and persistence DTO remains a
forward-compatible envelope for ordinary messages and optional recommendation
attachments; ordinary answers have no recommendation cards or discovery badge.

After durable USER acceptance, any terminal generation failure persists a
guarded `HANDOFF` assistant and retains `FAILED` invocation semantics. History
may also return `responseFailure` plus `responseRetry` on a legacy USER entry
when its owned invocation is `FAILED` but has no assistant link. The recovery
command uses that committed USER/invocation identity, is bounded by the
existing retry count, and must not create another USER message. Failure copy
never exposes provider/Product details; no automatic retry is permitted.

Preference state may additionally contain `activeGoal`, `activeCategory`,
`workflowStatus`, up to five `referencedListings`, and up to five
`lastToolActions`. It may also contain up to five `recentObservations` with an
allowlisted tool, normalized non-sensitive query label, bounded filter
categories, observation timestamp, result category, and freshness. Each
action-memory entry contains only the allowlisted action
name, concrete tool name, and low-cardinality result category. No arguments,
listing facts, provider payload, reasoning, vector, score, or error detail is
stored there. Legacy preference objects without these fields remain valid.

`AI-DISC-CS-P1-05` adds an internal Product-owned availability contract:

```text
GET /api/v1/internal/agent/marketplace/listings/availability?category={broadCategory}&limit=1
X-Agent-Internal-Service-Token: <service credential>
```

The request accepts no actor, budget, location, condition, brand, specification,
vector, ranking, or paging fields. Its exact response is
`MARKETPLACE_AVAILABILITY_PROBE_V1` with `mode=AVAILABILITY_PROBE`,
`searchExecuted`, normalized `category`, `totalActiveCategoryInventory`,
`relatedCategoryMatches` (zero when unsupported), `failureReason`, and
`retryable`. Product service authentication is checked before catalog work.
This probe does not call OpenSearch or an embedding provider and cannot produce
recommendations.

Discovery turn results may add `searchOutcome` with mode
`AVAILABILITY_PROBE|FULL_DISCOVERY_SEARCH`, explicit reason
`CATEGORY_UNAVAILABLE|FILTERS_TOO_STRICT|TEMPORARY_SEARCH_FAILURE|SEARCH_UNAVAILABLE|RESULTS_AVAILABLE`,
the authoritative total when known, an exact-match count when known, and only
bounded applied/relaxable filter categories. Preference state additively carries
`status`, `requestedCategory`, `categoryAvailability`,
`categoryInventoryCount`, `clarificationsAsked`, and `lastSearchOutcome`.
Legacy results without these fields remain readable. `CHECKING_AVAILABILITY`
with label `Checking current availability` is an additional strict activity
stage and is emitted only while the Product probe runs.

`AI-DISC-CS-P1-04` treats this session surface as a Marketplace customer-service
conversation. New assistant results include an `intent` from:

```text
GENERAL_CONVERSATION | MARKETPLACE_DISCOVERY | LISTING_QUESTION |
CUSTOMER_SUPPORT | SELLER_SUPPORT | CLARIFICATION | HANDOFF | REFUSED
```

New customer-service outcomes are:

```text
ANSWER | CLARIFY | SEARCH | ACTION_REQUIRED | HANDOFF | REFUSED
```

The strict decoder also accepts stored discovery outcomes `ASK_CLARIFY`,
`RECOMMEND`, `COMPARE`, `DETAIL`, `NO_RESULTS`, and `REFUSE`. Absence of
`intent` is accepted only for backward-compatible stored responses; newly
generated turns set it. `ANSWER` and `ACTION_REQUIRED` persist as `ANSWERED`,
`CLARIFY` as `CLARIFY`, and `REFUSED` as `REFUSED`; discovery-specific
recommendation resolutions remain unchanged. Only `MARKETPLACE_DISCOVERY` may
invoke hybrid search/query embedding. `LISTING_QUESTION` may invoke current
listing verification only against the latest validated recommendation set.

The stream still begins with durable `MESSAGE_ACCEPTED`. Other activity frames
are optional and describe actual work: `SEARCHING` is emitted at the Product
search boundary and `CHECKING` at listing revalidation. Clients must render only
received stages and must not infer omitted search activity. Empty validated
`recommendations`/`metadata` events remain part of the strict terminal stream
shape for non-discovery answers, but the UI renders no cards or provenance.

The existing synchronous Discovery message command remains available. The
additive authenticated streaming form is:

```text
POST /api/v1/agent/discovery/sessions/{sessionId}/messages/stream
Accept: text/event-stream
Content-Type: application/json
```

It accepts the exact existing Discovery message body:

```json
{
  "clientMessageId": "01C00000000000000000000001",
  "expectedPreferenceVersion": 3,
  "body": "Find a used bicycle near Irvine"
}
```

The existing Discovery capability/generation gate runs before actor resolution
or service work. The BFF session supplies authentication and CSRF; browser
`Authorization` and identity headers are forbidden. The Agent response uses
exact `Content-Type: text/event-stream` without parameters. No padding or
opening comment is emitted. The BFF handles only the two Discovery SSE POST
forms with a dedicated authenticated streaming relay: it forwards an
allowlisted header set, strips browser identity/cookie headers, flushes upstream
bytes unchanged on the originating authenticated servlet request, and enforces
the unchanged 35-second Discovery ceiling. It does not use a servlet async
completion redispatch, so a framework error envelope cannot be appended during
stream completion. After
the SSE response starts, timeout or disconnect closes the response and never
appends a JSON fallback. Normal Discovery JSON routes remain on the existing
Gateway wildcard/circuit-breaker path.
Every sequenced SSE frame has one
`event` line and one single-line JSON `data` line. Payloads use exact fields,
`schemaVersion=MARKETPLACE_DISCOVERY_STREAM_EVENT_V2`, and a sequence beginning
at 1 and increasing by exactly one:

Standards-valid comment-only SSE blocks may be ignored by clients and carry no
sequence or application payload. Agent does not emit an opening padding comment;
unsupported SSE fields and malformed event blocks fail closed.

```text
event: activity
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":1,"type":"activity","stage":"MESSAGE_ACCEPTED","label":"Request accepted"}

event: text_delta
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":6,"type":"text_delta","delta":"These current listings match"}

event: recommendations
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":7,"type":"recommendations","items":[...validated recommendations...]}

event: metadata
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":8,"type":"metadata","citations":[],"provenance":[...validated provenance...]}

event: done
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":9,"type":"done","messageId":"01A...","response":{...exact existing SendDiscoveryMessageResponse...}}
```

Progress stages are `MESSAGE_ACCEPTED`, `UNDERSTANDING`,
`CHECKING_AVAILABILITY`, `SEARCHING`, `CHECKING`, and `COMPOSING`. Acceptance occurs only after durable begin/user
persistence. Stages describe application work, never model reasoning. Private
planning and allowlisted Product tool calls finish before a separate tool-free
final-answer provider stream begins. Reasoning and planning events are ignored.
The Agent waits for an explicit response-generator delivery acknowledgement
after each real activity or canonical text-delta enqueue. The producer cannot
advance into the next private phase until the prior frame has been handed to
ASGI; this adds neither a timer nor a synthetic milestone.
After
the final provider call, concatenated `text_delta` values equal the exact
validated and stored `response.result.message`. `recommendations` and `metadata`
contain only backend-validated facts and are emitted after persistence. Exact
completed replay performs no Product/provider work.

The browser may receive multiple SSE records in one transport read. It appends
each parsed `text_delta` immediately to one assistant message without timers,
animation frames, artificial cadence, or whole-answer replay. Cards and
provenance wait for their validated events. Failure, cancellation, and
disconnect preserve visible partial text and never trigger an automatic send.
Cancellation before `MESSAGE_ACCEPTED` retains the composer draft and creates
no stopped assistant only when the authoritative stop command reports that the
USER turn was not committed. Cancellation after durable acceptance persists a
retryable `PARTIAL`/`HANDOFF` assistant even when zero answer characters were
produced; the zero-text public message is
`Response stopped before the answer began.` and history exposes the same
response-only retry descriptor without duplicating the USER row.

Explicit cancellation uses:

```text
POST /api/v1/agent/discovery/sessions/{sessionId}/messages/{clientMessageId}/stop
```

The authenticated, generation-gated command returns only `sessionId`,
`clientMessageId`, and an `outcome` of `NOT_COMMITTED`, `STOPPED`, `COMPLETED`,
or `TERMINAL`. It cancels the matching active Agent task when present and
reconciles persistence by the original client-message identity. It never
inserts or reposts a USER message.
If explicit task cancellation has already produced the exact allowlisted
`DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_CANCEL` state without an assistant,
the Stop command atomically reconciles that invocation to
`DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED` and attaches the guarded zero-text
`PARTIAL` assistant. This replacement is not available to any other failed
state. An authoritative `STOPPED` response is followed by history
reconciliation using the same `clientMessageId`, so the pending USER
presentation is replaced by the single stored USER row.
Message bodies and final answer text accept newline and tab while rejecting all
other ASCII control characters. The same rule applies to live completion and
stored history validation. A guarded interrupted assistant is represented by
`resolutionType=PARTIAL` with `result.outcome=HANDOFF`; this exact pair remains
strictly retryable and does not fabricate recommendations. Successful `done`
may collapse the reached activity trail into a short application-owned summary.

A post-header failure terminates with exactly:

```text
event: error
data: {"schemaVersion":"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2","sequence":2,"type":"error","code":"AGENT_DISCOVERY_FINAL_STREAM_INTERRUPTED","message":"The answer stream was interrupted. You can retry this response.","retryable":true}
```

No event may contain chain-of-thought, prompt internals, vectors, raw tool
arguments, listing IDs outside the completed typed response, provider details,
or scores. Unknown/malformed/non-monotonic events, invalid UTF-8, disconnect
without `done`/`error`, and delta/completion mismatch fail closed. The
client never automatically retries or falls back to the JSON POST after an
uncertain stream; it preserves the exact `clientMessageId` for explicit history
reconciliation.

The UI renders canonical answer text literally with preserved newlines and no
Markdown/HTML interpretation. USD cards display the authoritative decimal with
exactly two fraction digits. Typed listing follow-up/compare context uses the
validated public title rather than a rank ordinal; it does not alter listing
authorization or Product revalidation.

#### Discovery response-only retry (`AI-DISC-UX-P1-03A`)

For any terminal post-commit generation failure, Agent Service retains the safe
`error` SSE event and also persists a guarded `HANDOFF` assistant message with
no questions, recommendations, sources, or fabricated listing facts. Specific
allowlisted failures may retain more precise safe copy. `GET .../messages` adds
exact nullable fields to every history item:
`responseFailure` contains the safe structured HANDOFF only for a legacy failed
USER row without its stored assistant; `responseRetry` contains only
`invocationId` and `userMessageId` when an explicit retry is currently allowed.
All ordinary history items return both fields as `null`.

The deliberate response-only command is:

```text
POST /api/v1/agent/discovery/sessions/{sessionId}/messages/{userMessageId}/response-retry/stream
Accept: text/event-stream
Content-Type: application/json
```

```json
{"expectedPreferenceVersion": 3}
```

The body never accepts message text or `clientMessageId`. Agent Service checks
the actor, open session, current preference version, FAILED state, presence of
a stable terminal failure code, and configured retry ceiling, then resolves and
reuses the committed USER body and invocation
`clientMessageId`. The existing
atomic idempotency transition enforces one in-flight generation and creates no
new USER, tool-audit, or recommendation row. It streams the same strict event
schema as the ordinary message stream. There is no automatic retry after any
disconnect or uncertain result.

The query embedding step performs at most one timeout retry, with both attempts
clipped to the same whole-turn deadline. Product lexical-only degradation is
not enabled by this slice; Product hybrid search and per-listing revalidation
remain required. Product-and-budget requests do not require location before
SEARCH_INDIVIDUAL; city/county remains an optional filter.

Malformed or missing provider final structure is resolved only from
application-owned evidence. A proven zero-result search completes as
`NO_RESULTS` without retry. Fresh Product detail results are deterministically
selected and must pass the ordinary final guard before cards or provenance are
persisted. If neither outcome can be proven, the request fails with
`DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA`
and history exposes the existing bounded `responseRetry` action.

### Listing customer-service sessions (`AI-RAG-00`, `AI-05`)

The existing `Marketplace agent` entry in the floating chat launcher and
`/account/messages` uses agent APIs, not buyer/seller conversation APIs.

```text
POST /api/v1/agent/sessions
POST /api/v1/agent/sessions/{sessionId}/messages
GET  /api/v1/agent/sessions/{sessionId}
GET  /api/v1/agent/sessions/{sessionId}/messages?cursor=&limit=
```

Create/resume request:

```json
{
  "sessionType": "LISTING_CUSTOMER_SERVICE",
  "subject": {
    "type": "LISTING",
    "id": "01L00000000000000000000001"
  }
}
```

The current actor is derived from the authenticated BFF session. The subject
must be an active, approved individual listing. The command creates or returns
the one open session for the actor/listing pair atomically. Client-supplied
actor, seller, business, role, visibility, or permission fields are rejected.

The BFF relays the authenticated access token. Agent Service resolves the
app-owned actor through Auth Service rather than trusting browser identity
headers. Product Service eligibility is read through:

```text
GET /api/v1/internal/agent/listings/{listingId}/customer-service-context
```

This internal route requires `X-Agent-Internal-Service-Token`, compares it in
constant time, and returns only the current active public individual listing
ID, source version, title, safe thumbnail URL, seller type, eligibility, and
transaction notice. Missing, inactive, unapproved, removed, or non-individual
listings return the hidden not-found behavior.

Create/resume response:

```json
{
  "id": "01A00000000000000000000001",
  "sessionType": "LISTING_CUSTOMER_SERVICE",
  "status": "OPEN",
  "subjectListing": {
    "id": "01L00000000000000000000001",
    "version": "12",
    "title": "Used bicycle",
    "thumbnailUrl": "/api/v1/public/listing-media/01I...",
    "transactionNotice": "Payment and delivery are arranged directly by participants."
  },
  "createdAt": "2026-07-18T12:00:00Z",
  "updatedAt": "2026-07-18T12:00:00Z"
}
```

Message request:

```json
{
  "clientMessageId": "01C00000000000000000000001",
  "body": "Is the price negotiable?"
}
```

`clientMessageId` deduplicates retries for the session owner. The first
implementation returns the stored user and assistant messages synchronously.
Only the session owner may read or send. Messages use cursor pagination. Agent
sessions do not appear in `GET /conversations`, do not affect buyer/seller
unread state, and cannot invoke trade-completion actions.

Message response:

```json
{
  "userMessage": {
    "id": "01M00000000000000000000001",
    "role": "USER",
    "body": "Can this item be held for me?",
    "createdAt": "2026-07-18T12:01:00Z"
  },
  "assistantMessage": {
    "id": "01M00000000000000000000002",
    "role": "ASSISTANT",
    "body": "Only the seller can confirm whether the item can be held.",
    "resolutionType": "CONTACT_SELLER",
    "sources": [
      {
        "sourceType": "LISTING",
        "sourceId": "01L00000000000000000000001",
        "sourceVersion": "12",
        "label": "Current listing"
      }
    ],
    "actions": [
      {
        "type": "MESSAGE_SELLER",
        "listingId": "01L00000000000000000000001"
      }
    ],
    "createdAt": "2026-07-18T12:01:01Z"
  }
}
```

`resolutionType` is one of `ANSWERED`, `PARTIAL`, `UNKNOWN`,
`CONTACT_SELLER`, or `REFUSED`.

Initial validated action types are `MESSAGE_SELLER`, `VIEW_LISTING`, and
`BROWSE_MARKETPLACE`. They represent application route semantics, never
arbitrary URLs or model commands. The frontend does not parse message text to
infer actions. `MESSAGE_SELLER` opens the existing user-controlled listing
conversation flow and never sends a message automatically.

`MESSAGE_SELLER` and `VIEW_LISTING` contain only the session subject
`listingId`. `BROWSE_MARKETPLACE` contains only its `type`; it cannot carry a
model-supplied route, query, or identifier.

Factual answers identify their supporting sources. `sources[].sourceType` is
one of `LISTING`, `MARKETPLACE_POLICY`, `SAFETY_GUIDANCE`,
`MARKETPLACE_FAQ`, or `CATEGORY_GUIDANCE`. A source ID and version must match a
result returned during the invocation. Invalid citations or actions fail
closed.

The initial tool allowlist for this session type is:

| Tool | Trusted context | Model arguments | Effect |
| --- | --- | --- | --- |
| `getListing` | actor, session, subject listing | none | Reads current eligible public listing facts from Product Service |
| `retrieveKnowledge` | actor, session, subject listing, visibility, policy time, limits | bounded query, optional allowlisted source types and language | Reads filtered approved passages from the agent-owned OpenSearch projection |

Neither tool returns private contact, exact location, identity-provider,
storage, or moderation fields. The model cannot choose actor identity, listing
identity, visibility, effective policy time, or unrestricted OpenSearch
filters. No general HTTP, database, OpenSearch query, browser, web-search,
file-search, MCP, shell, or sandbox tool is available.

Source precedence:

1. `getListing` is authoritative for listing eligibility and changing
   structured facts.
2. Effective marketplace policy and safety guidance are authoritative for
   normative guidance.
3. Approved listing description and public attributes may add seller-provided
   detail but cannot override structured fields.
4. FAQs and category guidance are explanatory only.
5. Same-precedence conflict, missing evidence, or stale content produces
   uncertainty.

Listing eligibility is checked at session creation and before every answer
that relies on listing data. A listing-version mismatch causes listing-specific
vector passages to be discarded. When the listing becomes ineligible, the
session becomes read-only.

The service stores the user message and a `PENDING` invocation before external
calls. It calls Product Service, OpenSearch, and OpenAI outside that database
transaction, then stores one assistant message and marks the invocation
`SUCCEEDED`. Failure marks it `FAILED` without creating an assistant message.
A retry returns the prior successful response or performs one bounded retry of
a failed invocation without duplicating messages.

Product Service or OpenAI failure returns the standard temporary dependency
error. OpenSearch failure may degrade to a listing-facts-only answer only when
`getListing` fully supports the answer; otherwise it fails temporarily or
returns explicit uncertainty under the approved orchestration policy. Core
listing, search, and chat routes never depend on agent readiness.

## 16. Event Contract

Envelope:

```json
{
  "eventId": "id",
  "eventType": "listing.activated",
  "eventVersion": 1,
  "occurredAt": "2026-06-13T12:00:00Z",
  "producer": "product-service",
  "aggregateType": "listing",
  "aggregateId": "id",
  "correlationId": "id",
  "payload": {}
}
```

Initial events:

```text
business.application.submitted
business.approved
business.rejected
listing.submitted
listing.activated
listing.updated
listing.deactivated
category-guidance.activated
category-guidance.updated
category-guidance.invalidated
message.created
trade.created
trade.cancelled
trade.completed
inventory.reservation.expired
payment.succeeded
payment.failed
order.confirmed
order.cancelled
shipment.shipped
shipment.delivered
review.published
review.removed
```

Compatibility:

- Existing fields are not reinterpreted.
- Additive optional fields may remain in the same event version.
- Removing, renaming, or changing meaning requires a new version.
- Consumers ignore unknown fields and deduplicate by event ID.

For Product Service listing-knowledge events, the version-1 payload is
reference-only:

```json
{
  "listingId": "01L00000000000000000000001",
  "listingVersion": "12",
  "knowledgeLifecycle": "ACTIVE",
  "supersedesVersion": "11",
  "language": "und"
}
```

`listing.activated`, `listing.updated`, and `listing.deactivated` use the
listing ID as aggregate ID and Kafka message key. Payloads never include the
source body or private fields.

The `AI-RAG-02B` consumer accepts only its configured allowlisted topic and
event version `1`, ignores unknown additive fields, and validates the
event/lifecycle relationship. It inserts durable deduplication and job records
in one Agent Service MySQL transaction, then manually commits the next Kafka
offset. Validation, persistence, or commit failure does not advance the
partition. Duplicate deliveries with the same contracted payload are safe;
event-ID reuse with a different contracted payload hash fails closed.

For Product Service category-guidance events, topic
`category-guidance-v1` uses `{categoryId}:{language}` as its message key.
Version-1 payload is reference-only:

```json
{
  "sourceType": "CATEGORY_GUIDANCE",
  "sourceId": "01K00000000000000000000002",
  "sourceVersion": "2",
  "knowledgeLifecycle": "ACTIVE",
  "supersedesVersion": "1",
  "language": "en"
}
```

Source bodies, category snapshots, admin identity, and credentials never
appear in the event.

## Post-delivery business-group returns (V2-RET-01)

```text
GET  /api/v1/orders/{orderId}/groups/{businessOrderId}/return
POST /api/v1/orders/{orderId}/groups/{businessOrderId}/returns
GET  /api/v1/businesses/{businessId}/orders/{businessOrderId}/return
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/returns/{returnId}/authorize
POST /api/v1/businesses/{businessId}/orders/{businessOrderId}/returns/{returnId}/receive
```

Mutations require `If-Match` and `Idempotency-Key`. Buyer creation accepts one
allowlisted reason and an optional 500-character comment. Seller receipt accepts
only `RESTOCK_SELLABLE` or `DO_NOT_RESTOCK`. Missing and cross-scope resources
are non-enumerating. Browser requests never contain refund amount, currency,
payment intent, provider identifiers, business membership, inventory movement,
or outbox metadata. Responses expose only policy, bounded state/timeline, demo
shipment disclosure, and safe refund projection.

## ADM-BUS-04/05 API addition

External admin endpoints (Auth Service through the gateway):

- `GET /api/v1/admin/businesses`
- `GET /api/v1/admin/businesses/{businessId}`
- `GET /api/v1/admin/businesses/{businessId}/timeline`
- `POST /api/v1/admin/businesses/{businessId}/enforcements/dry-run`
- `POST /api/v1/admin/businesses/{businessId}/enforcements`
- `POST /api/v1/admin/businesses/{businessId}/enforcements/{enforcementId}/revoke/dry-run`
- `POST /api/v1/admin/businesses/{businessId}/enforcements/{enforcementId}/revoke`
- `GET /api/v1/businesses/{businessId}/marketplace-capabilities`

Internal authenticated endpoints:

- `POST /api/v1/internal/businesses/capabilities/evaluate`
- `POST /api/v1/internal/businesses/capabilities/evaluate-batch`
- `POST /api/v1/internal/admin/businesses/listing-summaries`

Protected business mutations return `403 BUSINESS_CAPABILITY_RESTRICTED` for an authoritative denial and `503 ENFORCEMENT_DECISION_UNAVAILABLE` if no decision can be obtained. Full semantics are in `docs/mvp/adm/admin-business-control.md`.
### Reversible listing enforcement (`ADM-LIST-06`)

```text
GET  /api/v1/admin/listings/{listingId}/enforcements
GET  /api/v1/admin/listings/{listingId}/enforcements/timeline
POST /api/v1/admin/listings/{listingId}/enforcements/dry-run
POST /api/v1/admin/listings/{listingId}/enforcements
POST /api/v1/admin/listings/{listingId}/enforcements/{enforcementId}/revoke/dry-run
POST /api/v1/admin/listings/{listingId}/enforcements/{enforcementId}/revoke
POST /api/v1/internal/listings/capabilities/evaluate-batch
```

Admin reads require `admin.listing.moderation.read`; action creation requires `admin.listing.suspend`; revocation requires `admin.listing.reinstate`; timeline reads also require `admin.audit.read`. Create and revoke commits require idempotency keys and expected versions. Dry runs do not persist. The route listing ID is authoritative and an action from another listing returns `404`. Stale versions and duplicate active actions return `409`. Temporary enforcement on `REMOVED_BY_ADMIN` or another non-active listing returns `400`.

Listing actions are `RESTRICT` or `SUSPEND`; listing `BAN` is invalid. `SUSPEND` deterministically expands to both listing scopes. The internal endpoint accepts 1–50 listing IDs and one or both listing scopes, requires `X-Internal-Service-Token`, and returns one explicit allowed/restricted decision per requested listing and scope without PII or staff reasons.

### Enforcement appeals (`ADM-APL-00/01/02/03`)

Affected-actor endpoints:

```text
GET  /api/v1/enforcements/mine
POST /api/v1/enforcements/{enforcementActionId}/appeals
GET  /api/v1/appeals/mine
```

The create request accepts only `reasonCode`, bounded plain-text `explanation`,
and the currently empty safe-evidence-reference list. Actor, target, ownership,
case, and enforcement version are derived server-side. Only active enforcement
is eligible; duplicate action appeals return `409 APPEAL_ALREADY_EXISTS`.

Admin endpoints:

```text
GET  /api/v1/admin/appeals
GET  /api/v1/admin/appeals/{appealId}
POST /api/v1/admin/appeals/{appealId}/claim
POST /api/v1/admin/appeals/{appealId}/release
POST /api/v1/admin/appeals/{appealId}/start-review
POST /api/v1/admin/appeals/{appealId}/notes
POST /api/v1/admin/appeals/{appealId}/review
POST /api/v1/admin/appeals/{appealId}/resolution/dry-run
POST /api/v1/admin/appeals/{appealId}/resolution
```

Review-stage mutations require `expectedVersion`. Notes also require a bounded
idempotency key: replaying the same note is safe and changing its body returns
`409 APPEAL_NOTE_IDEMPOTENCY_CONFLICT`. Review accepts an outcome of
`UPHOLD_RECOMMENDED`, `MODIFY_RECOMMENDED`, or `REVOKE_RECOMMENDED`;
modify also requires a target-compatible replacement proposal. These commands
do not execute enforcement.

Final resolution is a separate `admin.appeal.resolve`-protected command. Dry
run accepts `expectedAppealVersion`, `expectedEnforcementVersion`, and
`expectedTargetVersion`; it performs no enforcement or appeal-state mutation,
stores only the expiring confirmation record, and returns the recommendation's
inferred final outcome, replacement proposal when applicable, predicted
effective enforcement, remaining restrictions, bounded impact/warnings, and a
short-lived preview token. Execution accepts the same versions plus
`previewToken`, a bounded `idempotencyKey`, and `confirmed=true`. The token is
bound to the executor and the authoritative appeal, target, original action,
replacement, and overlapping-action state. Expiry, changed state, or a stale
version requires a new dry run.

The result mapping is fixed:

```text
UPHOLD_RECOMMENDED -> UPHELD   (original action unchanged)
MODIFY_RECOMMENDED -> MODIFIED (original revoked; stored replacement created)
REVOKE_RECOMMENDED -> REVOKED  (original revoked)
```

`UPHELD`, `MODIFIED`, and `REVOKED` are authoritative final statuses, not
aliases for recommendation states. Same key and same request replay the final
detail; changed reuse returns `409 APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT`.
The key is reserved globally in Auth before any remote owner mutation, so it
cannot be raced across appeals. Concurrent resolution has one winner, and a
final result is immutable. Revoke also requires the target-specific reinstate
permission; modify additionally requires the replacement action's
target-specific create permission. The reviewer and executor may differ, and
the server records the actual executor. A MODIFY preview cannot outlive its
replacement expiry, and first execution rejects a replacement that has expired
since preview without revoking the original action.

Product's LISTING owner routes are internal, token-protected, and absent from
the gateway contract:

```text
POST /api/v1/internal/admin/appeals/{appealId}/listing-enforcement-resolution/dry-run
POST /api/v1/internal/admin/appeals/{appealId}/listing-enforcement-resolution
```

They require the trusted executor's `admin.appeal.resolve` and
`admin.listing.reinstate`; MODIFY also requires `admin.listing.suspend`. Product
locks the listing and its complete enforcement set for every outcome. UPHELD
pins the confirmed state without mutating enforcement; REVOKED and MODIFIED
perform revoke or revoke-plus-replacement in one local transaction. Product
stores an appeal-scoped request fingerprint so Auth can replay a completed
owner command after a partial cross-service response failure. Recovery-only
mode can return completed work but cannot begin a new stale mutation. No
cross-service database access or distributed transaction is introduced.

Affected-actor appeal results add only resolution time, a bounded safe outcome
summary, and current effective target state. They omit reviewer/executor
identity, private notes/reasons, case evidence, and correlation data. Full
privacy and lifecycle semantics are in
`docs/mvp/adm/admin-appeals.md`.

### Admin order operations (`ADM-ORD-01/02`)

```text
GET  /api/v1/admin/orders
GET  /api/v1/admin/orders/{orderId}
POST /api/v1/admin/orders/{orderId}/cancel/dry-run
POST /api/v1/admin/orders/{orderId}/cancel
```

Reads require `admin.order.read`; cancellation requires `admin.order.cancel`.
Search supports bounded `page`/`size`, allowlisted stable sort, order query,
buyer/business/listing IDs, order/payment/fulfillment states, and an exclusive
created-to date range. The business-only commerce model has no seller-user
filter. Queue responses contain no address or payment-provider PII.
`fulfillmentStatus` is evaluated against the same cancellation-aware aggregate
value returned by the queue. It does not match a cancelled order merely because
one of its groups retains a historical fulfillment value such as
`PENDING_ACCEPTANCE`.

Detail returns immutable purchase-time item/store/policy/address snapshots and
labels current listing state separately. Complete street/recipient/phone data
also requires `admin.user.pii.read`. Payment and inventory sections expose only
safe owner-provided fields and identify a last-known Order projection when the
live owner is unavailable. Order Service resolves safe labels and active
USER/BUSINESS enforcement through the Auth-owned, `admin.order.read`-protected
`GET /api/v1/admin/order-context` service boundary; current LISTING enforcement
remains Product-owned. The context is read-only and does not expose enforcement
mutation controls.

The cancel request is `{reasonCode, reason, expectedOrderVersion,
idempotencyKey}`; dry run may omit the idempotency key and persists nothing.
Execution returns `CANCELLATION_REQUESTED` and reuses the existing asynchronous
refund/restock compensation workflow. Stable failures include
`ORDER_NOT_FOUND`, `ORDER_ADMIN_ACTION_NOT_ALLOWED`,
`ORDER_VERSION_CONFLICT`, `ORDER_ALREADY_CANCELLED`,
`ORDER_FINANCIAL_STATE_UNSUPPORTED`, and `ORDER_IDEMPOTENCY_CONFLICT`.
There is no arbitrary order-status update endpoint.

### Transaction disputes (`ADM-DSP-00/01/02`)

Participant APIs:

```text
POST /api/v1/orders/{orderId}/disputes
GET  /api/v1/orders/{orderId}/disputes
GET  /api/v1/disputes/{disputeId}
POST /api/v1/disputes/{disputeId}/statements
POST /api/v1/businesses/{businessId}/disputes
GET  /api/v1/businesses/{businessId}/disputes/{disputeId}
POST /api/v1/businesses/{businessId}/disputes/{disputeId}/statements
```

Admin APIs:

```text
GET   /api/v1/admin/disputes
GET   /api/v1/admin/disputes/{disputeId}
POST  /api/v1/admin/disputes/{disputeId}/claim
POST  /api/v1/admin/disputes/{disputeId}/release
PATCH /api/v1/admin/disputes/{disputeId}/priority
POST  /api/v1/admin/disputes/{disputeId}/notes
POST  /api/v1/admin/disputes/{disputeId}/request-information
POST  /api/v1/admin/disputes/{disputeId}/ready-for-decision
POST  /api/v1/admin/disputes/{disputeId}/resolve
```

Participant identity and business membership are derived server-side. Creation
is scoped to one real business-order group and uses an `Idempotency-Key`.
Participant reads omit assignment, internal notes, admin identity, internal
correlation, Trust & Safety private data, and unrelated PII.

Admin detail distinguishes purchase-time item snapshots from current Product
listing summaries and returns safe read-only refund, inventory reservation /
release, fulfillment, and shipment context. The active-scope uniqueness rule
permits only one non-final dispute for a business-order group; final history is
retained and a later distinct issue may create a new dispute.

Admin queue filters `q`, order/buyer/business, status, reason, priority,
assignment, dates, page, size, and allowlisted sort in SQL. Read, assignment,
investigation, and resolution use their matching `admin.dispute.*` permission.
Normal mutation additionally requires assignment to the current admin and an
expected version.

Resolution types are `RESOLVED_NO_ACTION`, `RETURN_APPROVED`,
`REFUND_RECOMMENDED`, and `PARTIAL_REFUND_RECOMMENDED`. Return approval is an
authorization record only. Refund outcomes are bounded recommendations only;
the endpoint has no Payment refund side effect. Stable errors include
`DISPUTE_NOT_FOUND`, `DISPUTE_NOT_ELIGIBLE`, `DISPUTE_ALREADY_EXISTS`,
`DISPUTE_NOT_ASSIGNED_TO_CURRENT_ADMIN`, `DISPUTE_VERSION_CONFLICT`,
`DISPUTE_ALREADY_RESOLVED`, `DISPUTE_REFUND_AMOUNT_INVALID`, and
`DISPUTE_FINANCIAL_STATE_CHANGED`.
## ADM-FIN-00/01/02 finance administration API

All endpoints require an authenticated admin and return `Cache-Control: no-store`. Lists are server-side paginated. Backend permissions, not UI guards, authorize every operation.

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/admin/payments` | `admin.finance.read` | Filtered payment queue |
| `GET` | `/api/v1/admin/payments/{paymentId}` | `admin.finance.read` | Payment, order snapshot, disputes, refunds, timeline, capabilities |
| `GET` | `/api/v1/admin/refunds` | `admin.refund.read` | Unified refund queue |
| `GET` | `/api/v1/admin/refunds/{refundId}` | `admin.refund.read` | Refund financial impact, attempt, failure/reconciliation, timeline |
| `POST` | `/api/v1/admin/payments/{paymentId}/refund/dry-run` | `admin.refund.execute` | Non-mutating current-impact preview |
| `POST` | `/api/v1/admin/payments/{paymentId}/refund` | `admin.refund.execute` | Revalidated provider-backed refund |

The refund body contains `refundType` (`FULL` or `PARTIAL`), optional partial `amount`, payment `currency`, allow-listed `reasonCode`, optional operator `reason`, optional linked `disputeId`, `expectedPaymentVersion`, and an execution `idempotencyKey`. Execution also accepts the same key in `Idempotency-Key`; the controller makes the header authoritative.

Dry run returns captured, successful-refunded, pending, current refundable, requested, projected refunded, projected remaining, dispute comparison, warnings, allow/deny reason, and the version that must be confirmed. It never writes or calls a provider. A `409 PAYMENT_VERSION_CONFLICT` requires a new detail read and dry run. Idempotency replays identical completed commands and returns `REFUND_IDEMPOTENCY_CONFLICT` for changed content.

The Order-internal context routes are token-protected and not gateway/public APIs: batch `POST /api/v1/internal/admin-finance/payment-contexts` and `GET /api/v1/internal/admin-finance/orders/{orderId}`. Auth's `/api/v1/admin/finance-context` supplies safe labels after `admin.finance.read` authorization.

Provider credentials, raw card/bank data, tokens, exact addresses, and unrestricted PII are absent from every contract. See [adm/admin-financial-operations.md](adm/admin-financial-operations.md).

## ADM-SUP-00/01 support operations API

Requester endpoints require authentication and derive requester identity server-side:

```text
POST /api/v1/support/tickets
GET  /api/v1/support/tickets/mine
GET  /api/v1/support/tickets/{ticketId}
POST /api/v1/support/tickets/{ticketId}/messages
```

Creation accepts broad category, subject, description, and optional
order/business/listing IDs. `Idempotency-Key` is required. Owner-service
validation and a ten-minute same-requester fingerprint cooldown reject unsafe
or obvious duplicate links/submissions. Requester responses omit assignment,
priority operations, private notes, admin identity, escalation internals,
correlation IDs, and PII.

Admin endpoints are:

```text
GET   /api/v1/admin/support/tickets
GET   /api/v1/admin/support/tickets/{ticketId}
POST  /api/v1/admin/support/tickets/{ticketId}/claim
POST  /api/v1/admin/support/tickets/{ticketId}/release
POST  /api/v1/admin/support/tickets/{ticketId}/messages
POST  /api/v1/admin/support/tickets/{ticketId}/request-information
POST  /api/v1/admin/support/tickets/{ticketId}/notes
PATCH /api/v1/admin/support/tickets/{ticketId}/priority
POST  /api/v1/admin/support/tickets/{ticketId}/links
POST  /api/v1/admin/support/tickets/{ticketId}/links/{targetType}/{targetId}/unlink
POST  /api/v1/admin/support/tickets/{ticketId}/escalations
POST  /api/v1/admin/support/tickets/{ticketId}/resolve
```

Inbox filters are `q`, requester, category, status, priority, assignment,
linked order/business, dates, bounded page/size, and allow-listed sort. All
aggregate mutations require `expectedVersion`. Claim/release use
`admin.support.assign`; participant responses, information requests, notes,
priority, and links use `admin.support.respond`; resolution and escalation use
their dedicated permissions. Idempotency protects creation,
participant/admin messages, information requests, notes, resolution, and
escalation.

Escalation validates and records an existing Order, Dispute, Trust & Safety
report, or Payment destination and leaves the support ticket open. It never
calls a specialized mutation. Stable failures include
`SUPPORT_TICKET_NOT_FOUND`, `SUPPORT_TICKET_NOT_ASSIGNABLE`,
`SUPPORT_TICKET_NOT_ASSIGNED_TO_CURRENT_ADMIN`,
`SUPPORT_TICKET_ALREADY_RESOLVED`, `SUPPORT_TICKET_VERSION_CONFLICT`,
`SUPPORT_INVALID_LINK`, `SUPPORT_DUPLICATE_TICKET`, and
`SUPPORT_IDEMPOTENCY_CONFLICT`. Full semantics are in
[adm/admin-support-operations.md](adm/admin-support-operations.md).

## ADM-CAT-01/02 catalog governance API

Product Service exposes protected admin catalog reads/commands below
`/api/v1/admin/catalog/categories`. Overview filters name/slug, status, and
seller eligibility and returns aggregate listing counts. Detail returns path,
children, attributes/options, seller guidance, rule history, audit, and
server-derived capabilities. Status, move, policy, attribute creation/update,
and enum-option status have `/dry-run` companions. Attribute creation preview
is `/attributes/create/dry-run`; existing required-rule preview is
`/attributes/dry-run?attributeId=...`; attribute lifecycle and option previews append
`/status/dry-run` to the option resource. Mutations use `expectedVersion`;
dangerous duplicate-prone creates and status changes also use
`Idempotency-Key`.

`GET /api/v1/categories` is the seller schema: active creation-enabled
categories, current rule version, typed active attributes/options, and active
non-blocking guidance. Listing create/update accepts `categoryRuleVersion` and
`attributes`; owner-scoped
`GET /api/v1/listings/{listingId}/catalog-values` reloads them. Stale rules
return `409 CATEGORY_RULE_CHANGED`; business-rule/type failures return `422`.
See [adm/admin-catalog-governance.md](adm/admin-catalog-governance.md).

## ADM-SYS-01/02 system operations API

Auth exposes `GET /api/v1/admin/system/summary`, `/health`, `/jobs`,
`/jobs/{jobId}`, `/outbox`, `/outbox/{eventId}`, `/reconciliation`,
`/inventory`, `/search`, `/features`, and `/operations`. Job/outbox reads accept
bounded paging and allow-listed filters. Owner-service snapshots are obtained
through token-protected `/api/v1/internal/system/operations` adapters and never
include raw payload or exception data.

For exact analytics drill-downs, job `status=FAILURE` expands only to
`FAILED|DEAD_LETTER|TERMINAL`; outbox `status=FAILURE` expands only to
`FAILED|DEAD_LETTER`. All other status values retain exact-match semantics.

Supported commands are `POST .../jobs/{jobId}/retry`,
`.../outbox/{eventId}/retry`, and
`.../search/listings/{listingId}/reindex`; each has a `/dry-run` companion.
Final commands require `Idempotency-Key` and a reason and return `202` with
`ACCEPTED`, `REJECTED`, `ALREADY_COMPLETED`, or `NOT_RETRYABLE`. `ACCEPTED`
describes queue acceptance, not worker success. Stable facade failures include
`SYSTEM_TARGET_NOT_FOUND`, `SYSTEM_OPERATION_NOT_SUPPORTED`,
`SYSTEM_OPERATION_ALREADY_RUNNING`, and `SYSTEM_IDEMPOTENCY_CONFLICT`.
See [adm/admin-system-operations.md](adm/admin-system-operations.md).

## ADM-GOV-01/02 admin governance API

Auth exposes protected governance reads below `/api/v1/admin/governance`:

```text
GET /dashboard
GET /admins?q=&role=&status=&hasTemporaryElevation=&page=&size=&sort=
GET /admins/{adminId}
GET /roles
GET /approvals?status=&riskLevel=&actionType=&requesterAdminId=&targetType=&page=&size=&sort=
GET /approvals/{approvalId}
```

Role commands are `POST /admins/{adminId}/roles/dry-run`,
`POST /admins/{adminId}/roles`, and matching
`/roles/{assignmentId}/revoke/dry-run` and `/revoke` endpoints. Grant requests
carry an allow-listed role, optional effective/expiry times, reason, governance
version, and idempotency key. Revoke requests carry assignment version, reason,
and key. The server derives the actor. A direct grant returns `201`; a direct
revoke returns `200`; governed SUPER_ADMIN changes return `202` with an
approval summary and do not change authority until executed.

Approval commands are:

```text
POST /approvals/{approvalId}/approve
POST /approvals/{approvalId}/reject
POST /approvals/{approvalId}/cancel
POST /approvals/{approvalId}/execute
```

They require expected versions, reasons where applicable, and
`Idempotency-Key`. The requester cannot review an independent-approval request;
dual approval counts distinct reviewers only. Execution returns `202` and its
detail reports `EXECUTED`, `FAILED`, or another explicit lifecycle state.

Typed owner-service policy gates are
`POST /domain/refunds` and `POST /domain/catalog/category-disable`. Payment and
Product call them only after normal domain authorization and dry run. A
below-threshold response is `200 APPROVAL_NOT_REQUIRED`; a governed response is
`202 PENDING_APPROVAL`. The public refund/category command consequently has a
documented response union: its existing owner-domain result for a direct
operation or a safe approval reference for a paused operation.

Auth dispatches approved work only to token-protected typed owner routes:

```text
POST /api/v1/internal/admin/payments/{paymentId}/refund
POST /api/v1/internal/admin/catalog/categories/{categoryId}/status
```

These internal endpoints also require the executor bearer token and preserve
normal Payment/Product permission, version, idempotency, and audit behavior.
There is no arbitrary action/payload execution endpoint. Stable failures
include `GOVERNANCE_VERSION_CONFLICT`, `GOVERNANCE_IDEMPOTENCY_CONFLICT`,
`GOVERNANCE_SELF_APPROVAL_PROHIBITED`, `GOVERNANCE_LAST_SUPER_ADMIN`,
`GOVERNANCE_APPROVAL_EXPIRED`, and `GOVERNANCE_APPROVAL_STALE`. See
[adm/admin-governance.md](adm/admin-governance.md).

## ADM-ANL-01 admin analytics API

Auth exposes read-only, `admin.analytics.read`-protected endpoints:

```text
GET /api/v1/admin/analytics/overview?range=&from=&to=&timezone=UTC&compare=
GET /api/v1/admin/analytics/trends?metric=&range=&from=&to=&timezone=UTC&granularity=
```

Ranges are UTC, half-open `[from,to)`, future-ending and non-positive windows
are rejected, and the maximum is 90 days. Presets are `TODAY`,
`LAST_7_DAYS`, `LAST_30_DAYS`, `LAST_90_DAYS`, and `CUSTOM`. Comparison is the
immediately preceding equal-duration window. Trend metric and granularity are
enums; no arbitrary query/formula input is accepted. `HOUR` is limited to 48
hours and every series is bounded to at most 100 points.
Supplying `from` or `to` without `range` infers `CUSTOM` (and therefore requires
both bounds); omitting the preset and both bounds defaults to `LAST_30_DAYS`.

The overview returns range/capabilities/generated time plus named marketplace,
moderation, Trust & Safety, commerce, support, catalog, operations, and
governance sections. Each section has `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`,
or `RESTRICTED`, a safe optional message, data-as-of time, typed bounded metrics,
and bounded breakdowns. An owner failure returns HTTP 200 with only dependent
sections unavailable. Auth-owned Marketplace, business moderation, Trust &
Safety, Support, and Governance reads have the same section isolation. Auth
authorization and range validation may fail the request normally. Missing
dependency data is never fabricated as zero.

The Trust & Safety section counts final appeals by `resolved_at` in the selected
half-open interval. Its final metric keys are `appealsFinalized`,
`appealsUpheld`, `appealsModified`, `appealsRevoked`, and
`appealAdjustmentRate`. `appealsFinalized = appealsUpheld + appealsModified +
appealsRevoked`; `appealAdjustmentRate = (appealsModified + appealsRevoked) /
appealsFinalized * 100`, with `N/A` at a zero denominator. `SUBMITTED`,
`UNDER_REVIEW`, and all three `*_RECOMMENDED` states are excluded from those
final metrics.

Currency amount breakdowns are omitted unless the actor also has
`admin.finance.read`; operations and governance require their existing read
permissions. Drill-down values are allow-listed keys, not browser-controlled
paths. Owner reads use token-protected
`/api/v1/internal/admin/analytics/summary` and typed trend routes in Product,
Order, and Payment. No analytics POST/PATCH/PUT/DELETE endpoint exists. See
[adm/admin-analytics.md](adm/admin-analytics.md).
