# IAM-01 Keycloak Local Setup

Status: Implemented

Scope: MVP authentication and accounts infrastructure

Related decision: `docs/adr/0001-keycloak-oidc-bff-authentication.md`

## Purpose

IAM-01 adds local Keycloak infrastructure and versioned realm configuration
for future authentication slices.

This slice is infrastructure only. It does not implement login, gateway BFF
behavior, user registration, identity tables, frontend session awareness, or
application authorization behavior.

## Local Services

Docker Compose defines:

- `postgres-keycloak`: local Keycloak persistence.
- `keycloak`: local Keycloak server using `start-dev --import-realm`.

The Keycloak service imports:

```text
infra/keycloak/realm-msb-local.json
```

The local Keycloak URL is:

```text
http://localhost:8181
```

The local realm is:

```text
msb-local
```

The OIDC discovery URL is:

```text
http://localhost:8181/realms/msb-local/.well-known/openid-configuration
```

## Local Admin Access

Local admin credentials should come from a developer-local `.env` copied from
`.env.example`:

```text
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=local-dev-only-change-me
```

If no `.env` file is present, Docker Compose uses its disposable local
fallbacks from `docker-compose.yml`.

These are disposable local values only. Production Keycloak credentials must
come from a secret manager.

## Realm Configuration

The local realm enables:

- email login
- self-registration after `SIGNUP-01`
- unique email addresses
- email verification
- password reset
- short-lived access tokens
- refresh-token rotation
- local development HTTPS relaxation through Docker Compose settings

Realm-level coarse roles are present for future claims:

- `BUYER`
- `INDIVIDUAL_SELLER`
- `BUSINESS_USER`
- `ADMIN_ELIGIBLE`

Application services remain authoritative for account status, individual
seller activation, business membership, business permissions, listing
ownership, and granular admin permissions.

## OIDC Clients

The realm export creates three confidential local clients for the future
gateway BFF flow.

| Client | Purpose | Redirect URI |
| --- | --- | --- |
| `msb-marketplace` | User marketplace site | `http://localhost:9000/api/v1/auth/callback/marketplace` |
| `msb-seller-portal` | Business seller portal | `http://localhost:9000/api/v1/auth/callback/seller-portal`, `http://localhost:4200/api/v1/auth/callback/seller-portal` |
| `msb-admin-portal` | Admin portal | `http://localhost:9000/api/v1/auth/callback/admin-portal` |

Each client:

- uses Authorization Code flow
- disables implicit flow
- disables direct password grants
- requires PKCE method `S256`
- is confidential for future gateway BFF server-side token handling
- uses local placeholder secrets only

The committed client secrets are local development placeholders. They are not
production secrets and must not be reused outside disposable local
environments.

## Running Locally

Start Keycloak and its database:

```powershell
docker compose up -d postgres-keycloak keycloak
```

Check container status:

```powershell
docker compose ps keycloak
```

Check the OIDC discovery document:

```powershell
Invoke-WebRequest http://localhost:8181/realms/msb-local/.well-known/openid-configuration
```

Open the admin console:

```text
http://localhost:8181/admin
```

## Re-importing The Realm

Keycloak imports the realm during startup when the realm does not already
exist in the database volume.

If the local realm must be recreated from the export, remove only the local
Keycloak database volume and start the services again:

```powershell
docker compose down
docker volume rm msb-ecom_postgres_keycloak_data
docker compose up -d postgres-keycloak keycloak
```

This deletes local Keycloak users and realm changes. It does not delete
application databases.

## Security Notes

- This setup is for local development only.
- Production admin authentication must require MFA.
- Production clients must use secrets from AWS Secrets Manager or another
  approved secret store.
- Browser apps must not receive or store access or refresh tokens.
- Future IAM-02 work will implement the gateway BFF login, logout, session,
  CSRF, and token relay behavior.

## Acceptance Criteria

- Local Keycloak is available through Docker Compose.
- A versioned local realm export exists.
- Marketplace, seller portal, and admin portal clients exist in the realm
  export.
- Self-registration is enabled for local account creation after `SIGNUP-01`.
- The setup is documented.
- IAM-01 itself did not implement login, registration, gateway BFF, frontend
  auth, or application behavior.
