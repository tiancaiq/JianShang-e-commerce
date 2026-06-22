# IAM Authentication and Accounts Implementation Plan

Status: Planned

Scope: First MVP feature area after Phase 1

Related docs:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam-00-legacy-auth-cleanup-plan.md`
- `docs/mvp/iam-01-keycloak-local-setup.md`
- `docs/mvp/requirements.md`
- `docs/mvp/api-contract.md`
- `docs/mvp/database.md`

## Boundary

This plan breaks Authentication and Accounts into small implementation slices.

Do not implement seller profiles, listings, media, search, chat, moderation,
cart, checkout, payment, notifications, reviews, AI, or analytics as part of
these slices.

The accepted authentication architecture is Keycloak OIDC with the API gateway
acting as the BFF for first-party Angular applications. Browser applications
must not store access or refresh tokens in browser storage.

## Slice Order

1. IAM-00: Legacy auth cleanup plan
2. IAM-01: Keycloak local setup
3. IAM-02: Gateway BFF login/logout/session
4. IAM-03: Identity user table and Keycloak `sub` mapping
5. IAM-04: Frontend auth session awareness
6. IAM-05: Protected route test
7. IAM-06: Profile view/edit

## IAM-00 Legacy Auth Cleanup Plan

### Goal

Identify existing legacy authentication code and decide what will be removed,
replaced, or retained before implementing the target Keycloak/BFF flow.

### Files Likely Touched

- `docs/mvp/iam-00-legacy-auth-cleanup-plan.md`
- Existing legacy references only for inspection:
  - `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
  - `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
  - `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
  - `auth-service/src/main/java/com/msb/ecom/auth_service/config/SessionAuthenticationFilter.java`
  - `frontend/src/app/core/services/auth.service.ts`
  - `frontend/src/app/core/interceptors/auth.interceptor.ts`
  - `frontend/src/app/core/guards/auth.guard.ts`
  - `frontend/src/app/core/models/auth.model.ts`

### Database Impact

None.

### API Impact

None.

### Tests

Documentation review only.

### Acceptance Criteria

- Legacy JWT issuance code is identified.
- Legacy browser `localStorage` auth usage is identified.
- Legacy custom session authentication path is identified.
- Files to remove later are listed.
- Files to replace later are listed.
- Files that can remain are listed.
- No application behavior is changed.

### Non-Goals

- No code deletion.
- No Keycloak implementation.
- No login implementation.
- No database migration.

## IAM-01 Keycloak Local Setup

### Goal

Provide local Keycloak infrastructure and versioned realm configuration for
future authentication slices.

### Files Likely Touched

- `docker-compose.yml`
- `.env.example`
- `infra/keycloak/realm-msb-local.json`
- `docs/mvp/iam-01-keycloak-local-setup.md`

### Database Impact

Keycloak uses its own local persistence database. Application services must
not query, migrate, or reference Keycloak tables.

No application-owned identity tables are created in this slice.

### API Impact

None.

### Tests

- Realm JSON parses.
- Docker Compose config validates.
- Keycloak starts locally.
- OIDC discovery endpoint returns `200`.
- Realm contains:
  - marketplace client
  - seller portal client
  - admin portal client

### Acceptance Criteria

- Local Keycloak is available through Docker Compose.
- A versioned local realm export exists.
- Marketplace, seller portal, and admin portal clients exist.
- Setup is documented.
- No app login or registration behavior is implemented.

### Non-Goals

- No gateway BFF.
- No frontend login.
- No user registration.
- No identity user table.
- No production Keycloak deployment.

## IAM-02 Gateway BFF Login/Logout/Session

### Goal

Implement gateway-managed browser authentication using Keycloak Authorization
Code flow with PKCE. The browser receives only a secure opaque session cookie.

### Files Likely Touched

- `api-gateway/pom.xml`
- `api-gateway/src/main/resources/application.properties`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`
- New gateway auth/session classes as needed
- `docs/mvp/api-contract.md`
- `docs/mvp/iam-authentication-accounts-implementation-plan.md`

### Database Impact

No MySQL application tables.

Gateway session storage should target Redis. A local in-memory session store
may be used only as a documented temporary local implementation if Redis is
not yet wired.

Refresh tokens must remain server-side.

### API Impact

Gateway-owned endpoints:

```text
GET  /api/v1/auth/login
GET  /api/v1/auth/callback/{client}
POST /api/v1/auth/logout
GET  /api/v1/auth/session
```

Example session response:

```json
{
  "data": {
    "authenticated": true,
    "user": {
      "subject": "keycloak-sub",
      "displayName": "Alex",
      "email": "alex@example.com"
    }
  }
}
```

### Tests

- Unauthenticated session request returns the documented unauthenticated
  response.
- Login endpoint redirects to Keycloak.
- Callback creates a server-side session.
- Logout clears the local session.
- OAuth tokens are not returned to browser JavaScript.
- CSRF protection exists for state-changing browser requests.

### Acceptance Criteria

- Browser receives only an `HttpOnly` session cookie.
- Access and refresh tokens remain server-side.
- Logout is idempotent.
- Gateway uses Keycloak issuer/client configuration.
- No custom JWT issuance is extended.

### Non-Goals

- No user registration.
- No profile editing.
- No identity user table unless explicitly moved to IAM-03.
- No seller or admin feature authorization.

## IAM-03 Identity User Table And Keycloak Sub Mapping

### Goal

Create the application-owned identity record and map it to immutable Keycloak
`sub`.

### Files Likely Touched

- `auth-service/pom.xml`
- `auth-service/src/main/resources/application.properties`
- `auth-service/src/main/resources/db/migration/VyyyyMMddHHmm__create_identity_users.sql`
- `auth-service/src/main/java/com/msb/ecom/auth_service/...`
- `auth-service/src/test/java/com/msb/ecom/auth_service/...`
- `docs/mvp/database.md`
- `docs/mvp/api-contract.md`

### Database Impact

Create MySQL-owned identity tables under the `identity` schema.

Likely table:

```text
users
```

Target columns:

- `id CHAR(26)` internal ULID
- `keycloak_sub VARCHAR(64)` unique
- `email`
- `email_verified`
- `display_name`
- `phone`
- `phone_verified`
- `avatar_url`
- `status`
- `version`
- `created_at`
- `updated_at`

Do not store:

- passwords
- password reset tokens
- Keycloak access tokens
- Keycloak refresh tokens
- MFA secrets
- Keycloak sessions

### API Impact

Add or prepare:

```text
GET /api/v1/users/me
```

The service should derive the actor from the authenticated Keycloak subject,
not from a client-supplied user ID.

### Tests

- Flyway migration validates.
- Clean MySQL database migrates successfully.
- Unique `keycloak_sub` prevents duplicate mappings.
- Authenticated subject creates or returns one local user mapping.
- Unauthenticated request returns `401`.
- User spoofing through request fields or headers is rejected.

### Acceptance Criteria

- Each Keycloak `sub` maps to exactly one internal user ID.
- Application identity data is stored in MySQL, not Keycloak tables.
- No password or token data is stored in application tables.
- Existing legacy PostgreSQL auth schema is not extended.

### Non-Goals

- No registration screen.
- No password reset implementation.
- No seller profile.
- No business membership.

## IAM-04 Frontend Auth Session Awareness

### Goal

Make the Angular frontend aware of gateway BFF session state without storing
tokens.

### Files Likely Touched

- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `frontend/src/app/core/guards/auth.guard.ts`
- `frontend/src/app/core/models/auth.model.ts`
- `frontend/src/app/features/auth/login.component.ts`
- Current route configuration files
- Frontend tests for auth service/guard

### Database Impact

None.

### API Impact

Consumes:

```text
GET  /api/v1/auth/session
GET  /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/users/me
```

### Tests

- Auth service loads session from the gateway.
- No token is read from or written to `localStorage`.
- Login redirects to gateway login.
- Logout calls gateway logout and clears frontend state.
- Guard handles loading, authenticated, and unauthenticated states.

### Acceptance Criteria

- Frontend no longer depends on JWT in `localStorage`.
- Auth state survives browser refresh through session check.
- Browser JavaScript cannot access OAuth tokens.
- Existing non-auth application behavior is not changed.

### Non-Goals

- No registration page.
- No profile edit UI.
- No seller portal app split.
- No admin portal app split.
- No role-based navigation beyond minimal authenticated state.

## IAM-05 Protected Route Test

### Goal

Prove protected frontend and backend routes enforce authentication correctly.

### Files Likely Touched

- `api-gateway/src/test/java/com/msb/ecom/api_gateway/...`
- `auth-service/src/test/java/com/msb/ecom/auth_service/...`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- Frontend route/service tests
- CI docs only if a new verification command is added

### Database Impact

None unless the protected route test uses `GET /api/v1/users/me`, which may
require the IAM-03 identity test database.

### API Impact

No new API is required. Prefer testing real protected APIs such as:

```text
GET /api/v1/users/me
```

### Tests

- Missing session or token returns `401`.
- Invalid token returns `401`.
- Valid Keycloak-like JWT reaches protected service code.
- Service maps subject to the local identity user.
- Frontend protected route blocks unauthenticated users.
- Frontend protected route allows authenticated sessions.

### Acceptance Criteria

- Authentication is enforced at gateway and service boundaries.
- Protected frontend route cannot be loaded as an unauthenticated user.
- Header or subject spoofing does not grant access.
- CI runs the protected route/auth tests.

### Non-Goals

- No business authorization.
- No admin authorization.
- No profile editing.
- No MFA test beyond Keycloak configuration expectations.

## IAM-06 Profile View/Edit

### Goal

Allow authenticated users to view and edit their basic application profile.

### Files Likely Touched

Backend:

- `auth-service/src/main/java/com/msb/ecom/auth_service/...`
- `auth-service/src/main/resources/db/migration/...`
- `auth-service/src/test/java/com/msb/ecom/auth_service/...`

Frontend:

- `frontend/src/app/features/account/profile.component.ts`
- `frontend/src/app/core/services/user-profile.service.ts`
- `frontend/src/app/core/models/user.model.ts`
- route/navigation files as needed

Docs:

- `docs/mvp/api-contract.md`
- `docs/mvp/database.md`

### Database Impact

Use or extend the IAM-03 `users` table with profile fields:

- `display_name`
- `phone`
- `phone_verified`
- `avatar_url`
- `version`
- `updated_at`

Do not create address tables in this slice.

### API Impact

Implement:

```text
GET   /api/v1/users/me
PATCH /api/v1/users/me
```

Allowed patch fields:

```json
{
  "displayName": "Alex",
  "phone": "+19495551234",
  "avatarUrl": "https://example.com/avatar.png"
}
```

Email changes require a later reverification flow and must not be silently
implemented here.

### Tests

Backend:

- Authenticated user can view own profile.
- Unauthenticated user receives `401`.
- User cannot edit roles, status, email verification, or internal IDs.
- Invalid display name, phone, or avatar URL is rejected.
- Optimistic locking conflict returns `409` if versioning is exposed.
- `updated_at` changes on successful update.

Frontend:

- Profile page loads current user.
- Save sends only allowed fields.
- Validation errors display.
- Unauthorized state redirects or shows sign-in required.
- No token storage is introduced.

### Acceptance Criteria

- User can view basic profile.
- User can edit only allowed fields.
- API uses standard response/error envelopes and correlation ID.
- Roles and account status cannot be changed by the user.
- Email change/reverification is deferred.

### Non-Goals

- No address book.
- No email change flow.
- No avatar upload flow unless explicitly approved.
- No seller activation.
- No admin user management.

## Cross-Slice Rules

- Do not extend the custom JWT path.
- Do not put OAuth tokens in browser storage.
- Do not store passwords or Keycloak tokens in application tables.
- Services must derive the actor from validated authentication, not from
  client-submitted user IDs.
- New application identity data uses MySQL and Flyway conventions from
  ADR-0002.
- Update docs when an approved API or database contract changes.
