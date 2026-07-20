# V2-IAM-01 Buyer Address Book Plan

Status: implementation complete and browser verified on 2026-07-19.

Release: V2.

Requirement:

- `IAM-05 Manage addresses`

Naming note:

- This V2 requirement is not the older completed MVP slice named
  `IAM-05 Protected route test`.
- `V2-IAM-01` is the unambiguous delivery-slice ID used by the V2 commerce
  roadmap.

Parent plan:

- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

Depends on:

- Existing Keycloak/BFF authentication and auth-service user mapping.
- Existing marketplace account routes and profile conventions.

Required by:

- `V2-CHK-01`

Source documents:

- `docs/mvp/requirements.md`
- `docs/mvp/architecture.md`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- `docs/v2/commerce/v2-com-00-commerce-domain-plan.md`

## 1. Goal

Give each authenticated buyer a private address book that can be managed from
the marketplace account surface and safely resolved by trusted checkout
orchestration.

The slice must:

- Let the current user list, create, edit, delete, and choose a default
  address.
- Prevent all cross-user reads and writes.
- Keep exact address data private from public, seller, trade, and search
  surfaces.
- Give V2-CHK-01 one service-authenticated way to resolve a buyer-owned
  address.
- Ensure checkout and orders copy address values instead of referencing the
  mutable address-book row.
- Avoid creating buyer checkout, payment, order, or shipping behavior early.

## 2. Contract Decisions

### 2.1 Ownership

Auth-service owns:

- Address-book persistence.
- Current-user authorization.
- Address normalization and validation.
- Default-address invariants.
- Internal buyer/address ownership resolution.

Order-service will own immutable checkout and order address snapshots. It
must never query auth-service tables or update an address-book row.

V2-IAM-01 completes the address-management portion of `IAM-05` and provides
the ownership-checked source contract. The acceptance criterion requiring an
address used in an order to be copied is completed by V2-CHK-01 and
V2-ORD-01, where those snapshots are actually persisted.

### 2.2 Address purpose

V2-IAM-01 stores reusable recipient/shipping contact details. It does not add
separate `SHIPPING` and `BILLING` address types.

Checkout references one explicit address ID. Billing-address behavior remains
provider and checkout policy work for V2-PAY-01 or a later approved slice.

### 2.3 Bounded collection

- One user can store at most 20 active addresses.
- The collection is returned as one bounded list without cursor pagination.
- Default address appears first; remaining addresses sort by most recently
  updated, then ID.

The limit keeps account UI and transactional default selection bounded.

### 2.4 Default behavior

- An empty address book has no default.
- The first created address automatically becomes default.
- A nonempty address book has exactly one default after every committed
  command.
- Clients cannot set `isDefault` in create or patch payloads.
- The dedicated default command is the only way to change the default.
- Deleting the default automatically promotes the oldest remaining address,
  ordered by `created_at, id`.
- Deleting the last address leaves the book empty.

Promotion is deterministic and happens in the same transaction as deletion.

### 2.5 Deletion and snapshots

Address-book deletion is a hard delete so the user can remove mutable PII.
Orders and checkouts never hold a foreign key to `addresses`; they retain
their own immutable snapshots.

Deleting or editing an address after checkout has copied it does not alter
the checkout or order.

## 3. Slice Boundary

Included:

- Forward-only auth-service Flyway migration.
- User-owned address entity/repository/service/controller behavior.
- Current-user list/create/patch/delete/set-default APIs.
- Optimistic address versions.
- MySQL-enforced at-most-one-default constraint.
- Service-token-protected checkout address resolver.
- Gateway routing and token relay for `/users/me/addresses/**`.
- Marketplace account address-management page.
- Account-dashboard entry point.
- Unit, MySQL integration, authorization, gateway, frontend, and browser
  tests.
- PII-safe logs and focused metrics.

Not included:

- Checkout session creation or a checkout button.
- Cart changes.
- Tax, shipping-rate, geocoding, or deliverability providers.
- Address autocomplete.
- Payment-provider billing addresses.
- Order or shipment persistence.
- Address sharing with business sellers.
- Admin address browsing.
- Address history, soft-delete retention, or Kafka events.
- International address-format rendering beyond storing normalized fields.
- Individual trade pickup or meeting addresses.

## 4. Current Repository Reality

The repository already provides:

- Keycloak identity and gateway BFF sessions.
- Auth-service `users` mapped by Keycloak `sub`.
- `CurrentActorProvider` for the authenticated user.
- ULID generation and MySQL Flyway migrations.
- Standard API data and error envelopes.
- `If-Match` parsing for optimistic profile and business updates.
- Gateway token relay to auth-service.
- Marketplace account and profile routes.
- Service-token authorization for internal commerce reads.

Missing behavior:

- Address persistence and default constraints.
- Address DTOs, validation, and ownership rules.
- Address routes in auth-service and gateway.
- Internal checkout address resolution.
- Address-management frontend models, service, route, and UI.
- Address-specific tests and observability.

No existing migration is rewritten.

## 5. Address Model

Each address contains:

| Field | Rule |
|---|---|
| `id` | Opaque 26-character ULID |
| `label` | Optional user label, such as Home or Office |
| `recipientName` | Required recipient name |
| `phone` | Required international recipient phone |
| `line1` | Required primary street/address line |
| `line2` | Optional secondary line |
| `city` | Required locality/city |
| `region` | Required state, province, or region |
| `postalCode` | Required postal code |
| `countryCode` | Required uppercase ISO 3166-1 alpha-2 syntax |
| `isDefault` | Server-owned default flag |
| `version` | Optimistic row version |
| timestamps | UTC creation/update timestamps |

Validation limits:

- `label`: 1 through 40 characters when present.
- `recipientName`: 1 through 120 characters.
- `phone`: E.164-style `+` followed by 8 through 15 digits.
- `line1`, `line2`: at most 200 characters; `line1` is required.
- `city`, `region`: at most 100 characters and required.
- `postalCode`: at most 32 characters and required.
- `countryCode`: exactly two ASCII letters, stored uppercase.

Normalization:

- Trim outer whitespace.
- Convert empty optional values to null.
- Reject control characters and embedded CR/LF.
- Preserve meaningful spaces and punctuation inside address lines.
- Do not claim that syntax validation proves deliverability.

The API rejects user IDs, buyer IDs, `isDefault`, version, timestamps, and
internal ownership fields in request bodies.

## 6. Browser API Contract

All routes use `/api/v1`, require an authenticated BFF session, and derive the
owner from the token subject.

```text
GET    /api/v1/users/me/addresses
POST   /api/v1/users/me/addresses
PATCH  /api/v1/users/me/addresses/{addressId}
DELETE /api/v1/users/me/addresses/{addressId}
POST   /api/v1/users/me/addresses/{addressId}/default
```

State-changing browser requests use the existing BFF CSRF contract.

### 6.1 List

Response:

```json
{
  "data": [
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
      "version": 2,
      "createdAt": "2026-07-18T10:00:00Z",
      "updatedAt": "2026-07-18T10:05:00Z"
    }
  ]
}
```

Because the collection is capped at 20, this endpoint does not use a page
envelope.

### 6.2 Create

Request:

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

Behavior:

- Locks the current user row to serialize collection-size and first-default
  decisions.
- Rejects creation when 20 addresses already exist.
- Makes the address default only when the book was empty.
- Returns `201` with the created address.
- Does not require `Idempotency-Key`; it is ordinary user CRUD rather than a
  money, checkout, inventory, order, or shipping command.

### 6.3 Patch

`PATCH` uses presence-aware merge semantics:

- Omitted fields remain unchanged.
- `label: null` and `line2: null` clear optional fields.
- Required fields cannot be cleared.
- `isDefault`, ownership, ID, version, and timestamps are rejected.
- An empty patch is rejected.

`If-Match` must contain the current address version. Success returns the
updated address.

### 6.4 Delete

`DELETE` requires `If-Match` with the current address version.

Behavior:

- The current user's address is hard-deleted.
- If it was default and other addresses remain, the deterministic replacement
  becomes default in the same transaction.
- Returns `204`.
- The UI reloads the bounded list so an automatic default promotion is
  visible.

### 6.5 Set default

`POST .../{addressId}/default` requires `If-Match` with the target address
version and has no request body.

Behavior:

- Locks the current user row and current/target default rows.
- Clears the former default before setting the target.
- Increments versions for rows whose default flag changes.
- If the target is already default and the version matches, returns it
  unchanged.
- Returns the default address.

## 7. Internal Checkout Resolver

Auth-service exposes one internal route:

```text
GET /api/v1/internal/users/{buyerId}/addresses/{addressId}
```

Rules:

- Requires `X-Internal-Service-Token`.
- Is not routed through the public gateway.
- Returns the address only when it belongs to the supplied active buyer.
- Returns `404 BUYER_ADDRESS_NOT_FOUND` for a missing buyer, missing address,
  wrong owner, or inactive account.
- Returns the same normalized address fields plus address version.
- Does not return email, profile phone, roles, memberships, or other
  addresses.

V2-CHK-01 must derive `buyerId` from its authenticated actor. A checkout
request accepts `addressId`, never a replacement buyer ID or raw address
payload.

The resolver response is input to an immutable checkout snapshot. Checkout
does not retain a foreign key to the address-book row.

## 8. Authorization And Privacy

- Every browser operation calls `ensureUserEntity()` and scopes persistence
  by both current `user_id` and `address_id`.
- A guessed address ID owned by another user returns
  `404 ADDRESS_NOT_FOUND`, not an ownership-revealing response.
- Backend authorization is required even though the Angular route uses
  `authGuard`.
- Exact address values never appear in public APIs, seller APIs, search
  indexes, analytics events, URLs, logs, metrics, or exception messages.
- Business sellers receive only later order/shipping snapshot fields required
  for fulfillment, never the mutable address-book row.
- Individual trade flows never receive or reference this address book.
- Admin APIs do not expose buyer addresses in this slice.
- Service-token comparison uses the existing constant-time internal commerce
  authentication pattern.

## 9. Database Plan

Add:

```text
auth-service/src/main/resources/db/migration/identity/
V202607181800__create_user_addresses.sql
```

### 9.1 `addresses`

Columns:

| Column | Rule |
|---|---|
| `id` | `CHAR(26)` ASCII primary key |
| `user_id` | Required owner foreign key to `users` |
| `label` | Nullable `VARCHAR(40)` |
| `recipient_name` | Required `VARCHAR(120)` |
| `phone` | Required `VARCHAR(32)` |
| `line1` | Required `VARCHAR(200)` |
| `line2` | Nullable `VARCHAR(200)` |
| `city` | Required `VARCHAR(100)` |
| `region` | Required `VARCHAR(100)` |
| `postal_code` | Required `VARCHAR(32)` |
| `country_code` | Required `CHAR(2)` ASCII |
| `is_default` | Required boolean |
| `default_owner_user_id` | Generated owner ID only when default |
| `version` | Optimistic row version |
| `created_at`, `updated_at` | UTC |

Constraints and indexes:

- Foreign key `user_id -> users(id)` with cascade delete for account cleanup.
- Unique generated `default_owner_user_id` to enforce at most one default per
  user.
- `(user_id, is_default, updated_at, id)` for bounded list ordering.
- `(user_id, created_at, id)` for deterministic default promotion.
- Country-code and nonblank field checks where MySQL can enforce them.

The service locks the `users` row for every create, delete-default, and
set-default command. This enforces the collection cap and the stronger
application invariant that a nonempty book has at least one default.

No order or checkout table has a foreign key to `addresses`.

## 10. Concurrency And Versioning

Address updates use row-level optimistic versions:

- PATCH, DELETE, and set-default require `If-Match`.
- A stale version returns `409 ADDRESS_VERSION_CONFLICT`.
- Create does not mutate the profile version.

Collection transitions use a pessimistic lock on the owner `users` row:

- Two concurrent first-address creates cannot both become default.
- Two concurrent set-default commands serialize.
- Create cannot exceed the 20-address limit under concurrency.
- Delete-default and set-default cannot leave two defaults.

The database unique constraint remains the final at-most-one-default guard.

## 11. Error Contract

| Status | Code | Meaning |
|---|---|---|
| `400` | `ADDRESS_INVALID` | Invalid or forbidden fields |
| `401` | standard authentication error | Browser session is absent |
| `403` | `INTERNAL_COMMERCE_AUTH_REQUIRED` | Internal token missing/invalid |
| `404` | `ADDRESS_NOT_FOUND` | Current-user address absent |
| `404` | `BUYER_ADDRESS_NOT_FOUND` | Internal buyer/address pair cannot resolve |
| `409` | `ADDRESS_VERSION_CONFLICT` | `If-Match` is stale |
| `409` | `ADDRESS_BOOK_LIMIT_REACHED` | User already has 20 addresses |
| `503` | `ADDRESS_SERVICE_UNAVAILABLE` | Required persistence unavailable |

Validation errors use standard field errors without echoing complete address
values.

## 12. Frontend Plan

Add:

```text
/account/addresses
```

Account integration:

- Add an Addresses entry to the marketplace account dashboard.
- Keep the feature out of seller and admin navigation.
- Keep checkout entry points unchanged.

Address page:

- Compact account-management layout.
- Default address first with a clear status pill.
- Add-address command.
- Edit, delete, and set-default actions for each address.
- Add/edit form with native autocomplete attributes where appropriate.
- Delete confirmation that names only the address label or city, not the full
  address.
- Empty, loading, saving, validation, conflict, and service-error states.
- Reload after stale-version conflict and after delete/default changes.
- Disable duplicate submission while a command is pending.

Frontend files:

- Address model and address-book service under `frontend/src/app/core`.
- Address management component under `frontend/src/app/features/account`.
- Protected route in `app.routes.ts`.
- Account dashboard link.
- Focused service, component, route, and authorization tests.

The UI does not claim an address is verified or deliverable.

## 13. Gateway And Service Wiring

Gateway:

- Route `/api/v1/users/me/addresses` and
  `/api/v1/users/me/addresses/**` to auth-service.
- Relay the access token.
- Preserve the existing BFF CSRF behavior for state-changing requests.
- Add route and unauthenticated-access tests.

Auth-service:

- Keep browser routes authenticated by JWT.
- Keep `/api/v1/internal/**` outside browser authentication but require the
  internal token in the address resolver itself.
- Reuse the configured commerce service token; do not add a second secret for
  one endpoint.

## 14. Events And Observability

No Kafka event or outbox table is required:

- Checkout resolves the selected address synchronously.
- Orders store immutable snapshots.
- No approved consumer needs address-change events.

Structured logs include:

- Address created, updated, deleted, and default changed.
- Address-book limit conflicts.
- Version conflicts.
- Denied internal resolver calls.
- Internal resolver success/failure by safe IDs.

Logs may contain user/address opaque IDs, result codes, and correlation IDs.
They must not contain names, phones, street lines, city, region, or postal
codes.

Metrics:

- Address create/update/delete/default command counts by result.
- Address-book limit conflicts.
- Version conflicts.
- Internal resolver requests by result and latency.

## 15. Test Plan

Domain/unit:

- Normalization and length validation.
- E.164-style phone and country-code syntax.
- Presence-aware patch semantics.
- First-address default behavior.
- Deterministic default promotion.
- Empty patch and forbidden-field rejection.

MySQL integration:

- Forward migration against the current identity schema.
- Create/list/update/delete lifecycle.
- First address automatically default.
- Nonempty book maintains exactly one default.
- Concurrent first creates produce one default.
- Concurrent default switches cannot produce two defaults.
- Maximum 20-address limit holds under concurrency.
- Stale patch/delete/default versions conflict.
- Deleting default promotes the deterministic replacement.
- Hard delete removes the PII row.
- User deletion cascades address rows.

Authorization/API:

- Browser routes require authentication.
- Current user can access only their own rows.
- Guessed cross-user IDs return `404`.
- Request bodies cannot replace owner/default/version/internal fields.
- Internal resolver requires the exact service token.
- Internal resolver enforces buyer/address ownership and active account.
- Standard success and error envelopes match the contract.
- Gateway routes address paths only to auth-service.
- Gateway never exposes the internal resolver.

Frontend:

- Service builds exact URLs and `If-Match` headers.
- List renders default first.
- Add/edit/default/delete actions update or reload state correctly.
- Validation and backend field errors are visible.
- Version conflict reloads current state.
- Empty and maximum-limit states are usable.
- Account route remains protected.

Regression:

- Profile update versioning remains independent.
- Business membership/store APIs remain unchanged.
- Cart and inventory tests remain green.
- Individual trade UI never renders exact buyer address.

## 16. Browser Verification

Use an authenticated buyer account with a clean local identity fixture:

1. Open `/account` and enter Addresses.
2. Verify the empty state.
3. Add a Home address and verify it becomes default.
4. Add an Office address and verify Home remains default.
5. Edit Office and verify the changed fields and version appear.
6. Set Office as default and verify exactly one default status.
7. Delete Office and verify Home is automatically promoted.
8. Reload and verify persistence.
9. Attempt direct navigation while signed out and verify login protection.
10. Confirm seller, public store, marketplace listing, and cart views do not
    expose exact address data or a premature checkout action.
11. Confirm browser console has no application errors.

Use a dedicated test address and delete it after the walkthrough. Do not use a
real personal address.

## 17. Implementation Order

1. Add the forward-only address migration and migration test.
2. Add address entity/repository records and validation helpers.
3. Implement current-user list/create behavior and first-default invariant.
4. Implement presence-aware patch with optimistic versioning.
5. Implement set-default and delete/default-promotion transactions.
6. Add address error mapping and PII-safe logs/metrics.
7. Add the token-protected internal checkout resolver.
8. Add gateway address routes and route/security tests.
9. Add Angular models, service, protected route, and account entry point.
10. Build the address-management UI and component tests.
11. Run auth, gateway, frontend, cart, and inventory regressions.
12. Complete browser verification and update roadmap status.

## 18. Completion Criteria

- The address-management portion of `IAM-05 Manage addresses` is implemented
  under slice `V2-IAM-01`; CHK-01 and ORD-01 retain responsibility for the
  immutable snapshot portion of the requirement.
- Authenticated users can create, list, patch, delete, and choose a default
  address.
- Cross-user browser and internal reads are impossible.
- A nonempty address book has exactly one default after every command.
- Address count cannot exceed 20 under concurrency.
- Mutations use optimistic address versions.
- Exact address PII is absent from logs and unrelated surfaces.
- Checkout can resolve one buyer-owned address through the internal contract.
- Checkout and order schemas do not reference mutable address rows.
- Frontend and browser verification pass without adding checkout behavior.

Implementation result:

- Auth-service integration tests passed: 80 tests.
- API-gateway route/security tests passed: 62 tests.
- Frontend tests passed: 336 tests, including 20 focused address/route/account
  tests.
- Angular production build passed.
- Browser verification covered create, edit, default selection, deletion,
  automatic default promotion, persistence after reload, empty state, and
  cleanup with no console errors.

## 19. Deferred Work

- V2-CHK-01 owns address selection during checkout and immutable checkout
  snapshots.
- V2-CHK-01 includes `CHK-02` and owns shipping, tax, discount, and total
  calculation.
- V2-PAY-01 owns provider-specific billing details.
- V2-ORD-01 owns immutable order-address persistence.
- V2-SHP-01 owns fulfillment and shipment use of order snapshots.
- Address verification, autocomplete, geocoding, deliverability checks,
  international format templates, and saved pickup locations require separate
  approved requirements.
