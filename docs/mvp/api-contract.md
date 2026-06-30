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
```

Rules:

- `client` defaults to `marketplace`.
- Unknown clients return `404`.
- `returnUrl` must be a safe same-site relative path; unsafe values are
  ignored.
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

Query: `client=marketplace|seller-portal|admin-portal`.

The endpoint does not accept credentials and does not return access or refresh
tokens.

### `GET /auth/session` (`IAM-02`)

Returns authenticated state, a safe Keycloak subject/user summary when signed
in, and CSRF metadata for browser mutations. It never returns access tokens,
refresh tokens, ID tokens, or token type metadata.

### `POST /auth/logout` (`IAM-02`)

Invalidates the gateway browser session and delegates OIDC logout/revocation
to Keycloak when supported. Logout is idempotent and requires CSRF protection
for browser callers.

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
- `avatarUrl`: nullable, `http` or `https` URL only, max 2048 characters.
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

### Address APIs (`IAM-05`)

```text
GET    /users/me/addresses
POST   /users/me/addresses
PATCH  /users/me/addresses/{addressId}
DELETE /users/me/addresses/{addressId}
POST   /users/me/addresses/{addressId}/default
```

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
- Only one draft business application per applicant is allowed.
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

### Admin business application queue (`ADM-BUS-01`)

```text
GET /admin/business-applications
```

Query:

```text
status=PENDING_VERIFICATION|UNDER_REVIEW
```

Rules:

- Requires authenticated platform admin role `PLATFORM_ADMIN`.
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
```

Rules:

- Requires authenticated platform admin role `PLATFORM_ADMIN`.
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

- Requires authenticated platform admin role `PLATFORM_ADMIN`.
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
GET   /businesses/{businessId}/store
PATCH /businesses/{businessId}/store
GET   /businesses/{businessId}/policies
POST  /businesses/{businessId}/policies
GET   /stores/{slug}
```

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

### Listing moderation (`LIST-06`, `LST-09`)

```text
GET  /admin/listings/moderation
GET  /admin/moderation/listing-cases?filter=open|unassigned|assigned_to_me|resolved
GET  /admin/moderation/listing-cases/{caseId}
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
review detail and resolution. Both decision paths require authenticated
platform admin role `PLATFORM_ADMIN`.

Queue response returns submitted listings where `status=PENDING_REVIEW` and
`moderationStatus=PENDING`.

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

Claim and release commands require `If-Match` with the current case version.
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
endpoint. The case must be claimed by the current admin. Resolution applies the
listing decision and closes the case in one transaction. Invalid assignment,
stale case version, non-claimed case state, or already-resolved case state
returns `409 MODERATION_CASE_VERSION_CONFLICT`. The admin detail UI treats
unclaimed, stale, non-pending, and resolved cases as read-only review context.

ADM-LIST-04 adds active listing admin maintenance. `GET
/admin/listings/{listingId}` returns the current listing draft-shaped response
with image metadata for platform admins. `PATCH /admin/listings/{listingId}`
edits active approved listing content fields only and requires `If-Match` with
the current listing version plus a required `reason`. `POST
/admin/listings/{listingId}/remove` removes an active approved listing from
public marketplace visibility and requires `If-Match` plus a required `reason`.
Removal sets listing status `REMOVED_BY_ADMIN`; it is not a physical delete.
Both commands append moderation history decisions `ADMIN_EDIT` or
`ADMIN_REMOVE`. Non-active listings return `400 LISTING_INVALID_REQUEST`; stale
listing versions return `409 LISTING_VERSION_CONFLICT`.

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
- Only listings with `status=ACTIVE` and `moderationStatus=APPROVED` are
  visible.
- Non-public listing states return `404 LISTING_NOT_FOUND`.
- The response omits owner user IDs, business internal IDs, internal status,
  moderation status, versions, media object IDs, object bucket, and object
  key.
- Individual listings include an off-platform payment and delivery notice.

Response:

```json
{
  "id": "01J...",
  "sellerType": "INDIVIDUAL",
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
  "images": [
    {
      "id": "01J...",
      "displayOrder": 0,
      "altText": "Blue bike",
      "originalFileName": "bike.png",
      "contentType": "image/png",
      "sizeBytes": 1024,
      "uploadUrl": "local-demo://..."
    }
  ]
}
```

### Search (`SRC-02`, `SRC-03`)

```text
GET /public/listings
GET /search/listings?q=&categoryId=&sellerType=&condition=&minPrice=&maxPrice=&city=&region=&cursor=&limit=
GET /stores/{slug}/listings?cursor=&limit=
```

SEARCH-01 implements the initial database-backed public browse path:
`GET /public/listings`. It returns newest approved active listings using the
same safe public projection as `GET /public/listings/{listingId}`. It has a
server-side cap and no client filters yet.

SEARCH-00 defines the MVP read-model direction: public browse and storefront
reads start from MySQL source tables and expose only safe public fields.
OpenSearch is deferred until the database-backed browse, storefront, filters,
sorting, and cursor pagination contracts are stable.

Public listing cards may expose listing ID, seller type, category display
data, title, condition, price, public location, published time, negotiable
state, transaction notice, and approved image URLs. They must not expose owner
user IDs, business staff/member data, internal status, moderation state,
versions, media bucket/key, exact individual locations, or private contact
data.

Maximum `limit` is server-controlled. Search result includes seller type and
checkout/off-platform disclosure.

## 7. Chat

### Conversation APIs (`CHT-01`, `CHT-02`)

```text
POST /listings/{listingId}/conversations
GET  /conversations
GET  /conversations/{conversationId}
GET  /conversations/{conversationId}/messages?cursor=&limit=
POST /conversations/{conversationId}/messages
POST /conversations/{conversationId}/read
```

Message request:

```json
{"type": "TEXT", "body": "Is this still available?"}
```

Realtime endpoint may deliver message events, but HTTP remains the authoritative
command and history interface.

### Safety (`CHT-03`)

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

### Cart (`CRT-01` through `CRT-04`)

```text
GET    /cart
POST   /cart/items
PATCH  /cart/items/{listingId}
DELETE /cart/items/{listingId}
DELETE /cart
POST   /cart/validate
```

Add request: `{"listingId": "id", "quantity": 1}`.

Cart validation response returns current item status, authoritative price,
availability, and warnings.

### Business inventory (`INV-01`)

```text
GET  /businesses/{businessId}/inventory?cursor=&limit=
GET  /businesses/{businessId}/inventory/{listingId}
POST /businesses/{businessId}/inventory/{listingId}/adjustments
GET  /businesses/{businessId}/inventory/{listingId}/movements
```

Adjustment request contains signed quantity delta, reason, and expected
version.

### Internal reservation APIs (`INV-02` through `INV-04`)

```text
POST /internal/inventory/reservations
POST /internal/inventory/reservations/{id}/commit
POST /internal/inventory/reservations/{id}/release
GET  /internal/inventory/reservations/{id}
```

Service authentication and `Idempotency-Key` are mandatory.

## 10. Checkout, Payment, and Orders (V2)

### Checkout (`CHK-01`, `CHK-02`)

```text
POST /checkouts
GET  /checkouts/{checkoutId}
POST /checkouts/{checkoutId}/cancel
```

Create request references cart and address ID. Response contains authoritative
snapshots, totals, expiry, and reservation state.

### Payment (`PAY-01`, `PAY-02`)

```text
POST /checkouts/{checkoutId}/payment-intent
POST /webhooks/payments
GET  /payments/{paymentId}
```

Payment-intent command requires `Idempotency-Key`. Response exposes only the
provider client data intended for the browser.

### Buyer orders (`ORD-02`, `ORD-04`)

```text
GET  /orders?cursor=&limit=
GET  /orders/{orderId}
POST /orders/{orderId}/cancellation-requests
```

### Business orders (`ORD-03`)

```text
GET /businesses/{businessId}/orders?status=&cursor=&limit=
GET /businesses/{businessId}/orders/{businessOrderId}
```

### Admin payment/order operations (`PAY-03`, `ADM-06`)

```text
GET  /admin/operations/payment-order-mismatches
POST /admin/operations/payment-order-mismatches/{id}/retry
```

Retry requires `FINANCE_ADMIN` or a narrower configured permission.

## 11. Fulfillment and Shipping (V2)

```text
POST /businesses/{businessId}/orders/{businessOrderId}/accept
POST /businesses/{businessId}/orders/{businessOrderId}/shipments
POST /businesses/{businessId}/shipments/{shipmentId}/mark-shipped
GET  /orders/{orderId}/shipments
POST /webhooks/shipping
```

Shipment create request includes carrier, tracking number, and item quantities.
State-changing commands require idempotency.

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

```text
GET   /notifications?cursor=&limit=
POST  /notifications/{notificationId}/read
POST  /notifications/read-all
GET   /notification-preferences
PATCH /notification-preferences
```

## 14. Administration and Support

MVP admin includes business application review and listing moderation only.
User/business suspensions, reports, support cases, chat evidence review,
payment/order/finance operations, advanced trust/disputes, and AI moderation
assistance are admin roadmap scope but deferred until later release slices.
The first planned report admin view is ADM-REP-01 user-reported listings, which
depends on report submission and report persistence being implemented first.

```text
GET  /admin/users
GET  /admin/users/{userId}
POST /admin/users/{userId}/suspensions
POST /admin/users/{userId}/restore

GET  /admin/businesses
GET  /admin/businesses/{businessId}
POST /admin/businesses/{businessId}/suspensions
POST /admin/businesses/{businessId}/restore

GET  /admin/reports
POST /admin/reports/{reportId}/claim
POST /admin/reports/{reportId}/resolve

GET  /admin/support-cases
POST /admin/support-cases/{caseId}/notes
POST /admin/support-cases/{caseId}/responses

GET  /admin/audit-logs
```

Admin list endpoints require bounded filters and cursor pagination.
Future listing-report admin reads should filter `GET /admin/reports` to listing
subjects rather than mixing user reports into the listing submission moderation
queue.

## 15. Agent APIs and Tools (V3)

User-facing agent endpoint:

```text
POST /agent/sessions
POST /agent/sessions/{sessionId}/messages
GET  /agent/sessions/{sessionId}
```

MVP tool allowlist:

```text
searchListings
getListing
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

## 16. Event Contract

Envelope:

```json
{
  "eventId": "id",
  "eventType": "listing.activated",
  "eventVersion": 1,
  "occurredAt": "2026-06-13T12:00:00Z",
  "producer": "marketplace-service",
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
