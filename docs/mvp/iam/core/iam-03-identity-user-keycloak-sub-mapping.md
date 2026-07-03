# IAM-03 Identity User Table And Keycloak Sub Mapping

Status: Implemented

Scope: MVP authentication and accounts

Related decisions:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/adr/0002-mysql-database-ownership-and-migration-conventions.md`

Depends on:

- IAM-01 Keycloak local setup
- IAM-02 Gateway BFF login/logout/session

## Purpose

IAM-03 creates the application-owned identity user record and maps it to the
immutable Keycloak `sub` claim.

The auth service is now the owner of the `identity` MySQL schema for
application identity/profile data. Keycloak remains the owner of credentials,
login sessions, email verification actions, password recovery, MFA, signing
keys, access tokens, ID tokens, and refresh tokens.

## Implemented API

```text
GET /api/v1/users/me
```

The endpoint requires an authenticated Keycloak JWT. It derives the actor from
the validated token subject and never accepts a user ID, Keycloak subject,
role, account status, email, or business identifier from request input.

First request for a Keycloak subject creates a local application user mapping.
Later requests for the same subject return the same internal user ID and
refresh safe projected fields from token claims.

Example response:

```json
{
  "data": {
    "id": "01J...",
    "keycloakSub": "keycloak-sub",
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

The response contains no password hash, access token, refresh token, ID token,
MFA secret, or Keycloak session value.

## Database

New migration:

```text
auth-service/src/main/resources/db/migration/identity/V202606160315__create_identity_users.sql
```

Table:

```text
users
```

Important constraints:

- `id` is a `CHAR(26)` ULID.
- `keycloak_sub` is unique and immutable.
- `status` is constrained to `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- MySQL uses InnoDB and `utf8mb4`.

The legacy PostgreSQL migration remains in the repository but is not part of
the active IAM-03 Flyway location. It was not edited.

## Gateway Route

The API gateway routes:

```text
/api/v1/users/**
```

to `auth-service` with token relay. This allows the browser BFF session from
IAM-02 to call the identity service without exposing OAuth tokens to browser
JavaScript.

## Configuration

Local identity database variables:

```text
IDENTITY_DB_HOST=localhost
IDENTITY_DB_PORT=3306
IDENTITY_DB_NAME=identity
IDENTITY_DB_USERNAME=root
IDENTITY_DB_PASSWORD=local-dev-only-change-me
```

The local JDBC URL uses `createDatabaseIfNotExist=true` for developer
convenience. Tables are still created by Flyway.

`auth-service` imports the repository `.env` file when it is started from the
repository root, so local `spring-boot:run` can use the same MySQL password as
Docker Compose without manually exporting every `IDENTITY_DB_*` variable.

## Tests

Auth-service tests cover:

- Clean MySQL database migrates successfully.
- `keycloak_sub` uniqueness prevents duplicate mappings.
- Authenticated subject creates one local user mapping.
- Repeat requests for the same subject return the same internal user ID.
- Unauthenticated request returns `401`.
- Spoofed identity headers are ignored.
- Response does not expose passwords or OAuth tokens.

Gateway tests cover:

- `/api/v1/users/**` remains protected behind gateway authentication/token
  relay through the existing route security behavior.

## Non-Goals

IAM-03 does not implement:

- registration;
- password recovery/reset;
- frontend auth/session changes;
- profile edit;
- address book;
- seller profiles;
- business membership;
- listings, media, search, chat, moderation, cart, checkout, payment, orders,
  notifications, reviews, AI, or analytics.

## Requirement Note

Older `docs/mvp/requirements.md` labels IAM-03 as password recovery. The
current implementation sequence in
`docs/mvp/iam/core/iam-authentication-accounts-implementation-plan.md` defines IAM-03
as identity user table and Keycloak `sub` mapping. This implementation follows
the current IAM slice plan. Password recovery remains Keycloak-owned and is
not implemented in application code here.
