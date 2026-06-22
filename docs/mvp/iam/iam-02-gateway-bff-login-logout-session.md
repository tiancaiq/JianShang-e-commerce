# IAM-02 Gateway BFF Login, Logout, And Session

Status: Implemented

Scope: MVP authentication and accounts

Related decision: `docs/adr/0001-keycloak-oidc-bff-authentication.md`

Depends on: IAM-01 local Keycloak setup

## Purpose

IAM-02 implements the API gateway as the backend-for-frontend authentication
host for first-party browser applications.

The gateway starts Keycloak OIDC Authorization Code login, stores OAuth tokens
server-side in the Spring Security authorized-client session, exposes a safe
session summary, applies CSRF protection to state-changing browser requests,
and clears the local session on logout.

This slice does not implement registration, an application identity user
table, profile APIs, frontend auth changes, seller features, listing features,
chat, cart, payment, or orders.

## Local Keycloak Realm

The gateway is configured for the IAM-01 local realm:

```text
http://localhost:8181/realms/msb-local
```

The configured local BFF clients are:

| Registration ID | Keycloak client ID | Callback |
| --- | --- | --- |
| `marketplace` | `msb-marketplace` | `/api/v1/auth/callback/marketplace` |
| `seller-portal` | `msb-seller-portal` | `/api/v1/auth/callback/seller-portal` |
| `admin-portal` | `msb-admin-portal` | `/api/v1/auth/callback/admin-portal` |

Local client secrets are placeholders from
`infra/keycloak/realm-msb-local.json` and `.env.example`. Production secrets
must come from a secret manager.

## Gateway Endpoints

### `GET /api/v1/auth/login`

Starts OIDC login by redirecting to Spring Security's client authorization
endpoint.

Query parameters:

```text
client=marketplace|seller-portal|admin-portal
```

Default client: `marketplace`.

The browser is redirected to Keycloak. The endpoint does not accept email or
password, and it does not return an access token or refresh token.

### `/api/v1/auth/callback/{client}`

Handled by Spring Security OIDC login. Keycloak redirects here after
successful authentication. The gateway exchanges the authorization code
server-side and creates the browser session.

### `GET /api/v1/auth/session`

Returns a cache-disabled session summary:

```json
{
  "authenticated": true,
  "user": {
    "subject": "keycloak-sub",
    "email": "user@example.com",
    "displayName": "User Name",
    "roles": ["BUYER"],
    "expiresAt": "2026-06-15T12:00:00Z"
  },
  "csrf": {
    "headerName": "X-CSRF-TOKEN",
    "parameterName": "_csrf",
    "token": "csrf-token"
  }
}
```

Anonymous callers receive `authenticated: false`, no user object, and a CSRF
token. The response intentionally excludes access tokens, refresh tokens, ID
tokens, token type, and raw Keycloak session values.

### `POST /api/v1/auth/logout`

Logs the browser out through Spring Security logout. The gateway invalidates
the local session, deletes `JSESSIONID`, and uses the OIDC logout handler for
real OIDC sessions so Keycloak can end the identity-provider session when
supported.

Logout is a state-changing request and requires a valid CSRF token.

## Browser Token Boundary

The browser receives only an opaque HTTP session cookie for authentication.
OAuth access and refresh tokens remain server-side in the gateway session.
The gateway uses token relay to attach the server-held access token to
downstream routed API requests.

Local cookie settings:

```text
HttpOnly=true
SameSite=Lax
Secure=false
Idle timeout=30m
```

`Secure=false` is for local HTTP development. Production must set
`GATEWAY_SESSION_COOKIE_SECURE=true` and serve the gateway only over TLS.

## CSRF

Because browser authentication is cookie-based, CSRF protection is enabled.

State-changing requests must send:

```text
X-CSRF-TOKEN: <token from GET /api/v1/auth/session>
```

The CSRF token is not an OAuth credential and is safe for browser JavaScript to
read. It is used only to bind browser mutations to an intentional same-site
request.

## CORS

Credentialed CORS is restricted to configured first-party origins:

```text
GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:4200
```

Wildcard credentialed CORS is no longer used.

## Contract Reconciliation

`docs/mvp/api-contract.md` still contains older token-shaped text for
`POST /auth/login` and `POST /auth/refresh`. ADR-0001 supersedes that behavior
for first-party web clients:

- login is a gateway redirect starter, not a credential JSON endpoint;
- refresh is handled server-side by Spring Security's authorized client
  support rather than by browser JavaScript;
- logout is the gateway BFF logout endpoint;
- browser storage must not contain access or refresh tokens.

A later API contract cleanup should replace the older token-returning examples
with the BFF session contract once frontend auth migration is approved.

## Acceptance Criteria

- Gateway login redirects to the configured Keycloak OIDC client.
- Gateway callback path matches the IAM-01 `msb-local` realm export.
- Browser session awareness is available at `GET /api/v1/auth/session`.
- Session response contains no access or refresh token.
- State-changing requests require CSRF protection.
- Gateway logout clears the local session cookie and delegates OIDC logout for
  real OIDC sessions.
- Downstream gateway routes use server-side token relay.
- No registration endpoint, identity user table, frontend auth change, seller
  feature, listing feature, or chat feature is implemented.

## Tests

Run:

```powershell
%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd -pl api-gateway -am test
```

Verified on 2026-06-15:

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

The standard `mvnw.cmd` wrapper failed in this PowerShell session before
Maven startup with `Cannot index into a null array`, so the already-downloaded
wrapper Maven distribution was invoked directly.
