# IAM-00 Legacy Auth Cleanup Record

Status: complete.

Scope: MVP authentication and accounts.

Related decision: `docs/adr/0001-keycloak-oidc-bff-authentication.md`.

## Purpose

IAM-00 originally identified legacy authentication paths before the Keycloak
OIDC gateway BFF migration. STAB-P0-04 completed the remaining source cleanup
for custom JWT issuance.

The accepted target architecture remains:

- Keycloak owns credentials, login sessions, password recovery, email
  verification, MFA, access tokens, ID tokens, refresh tokens, signing keys,
  and key rotation.
- The API gateway owns browser BFF login, logout, session, CSRF, and token
  relay.
- Angular stores no access tokens or refresh tokens in browser storage.
- `auth-service` owns application identity/profile data mapped to immutable
  Keycloak `sub`.

## Current State

The active authentication path is:

1. Browser calls gateway BFF auth endpoints under `/api/v1/auth`.
2. Gateway redirects to Keycloak for login and owns the browser session.
3. Gateway relays validated Keycloak tokens to protected services.
4. `auth-service` validates Keycloak JWTs as a resource server.
5. `auth-service` creates or updates the application user mapping through
   `/api/v1/users/me`.

`auth-service` no longer exposes custom login/signup endpoints and no longer
issues browser-facing JWTs.

## Removed Legacy JWT Issuance Code

STAB-P0-04 removed:

- `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/LoginRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/SignupRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/UserResponse.java`
- `jjwt-api`, `jjwt-impl`, and `jjwt-jackson` from `auth-service/pom.xml`

These artifacts belonged to the old custom HS256 JWT implementation and must
not be reintroduced for MVP authentication.

## Browser Token Storage

Frontend authentication is BFF-session aware:

- `frontend/src/app/core/services/auth.service.ts` loads session state from
  `GET /api/v1/auth/session`.
- `frontend/src/app/core/interceptors/auth.interceptor.ts` does not inject a
  bearer token from browser storage.
- `frontend/src/app/features/auth/login.component.ts` delegates login to
  `GET /api/v1/auth/login`.

The only allowed `localStorage` references in auth tests are spies that assert
token storage is not read or written.

## Legacy Session Path

The previously documented `SessionAuthenticationFilter` file is not present in
the current source tree.

`auth-service` remains stateless:

- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
  uses `SessionCreationPolicy.STATELESS`.
- Protected service authentication is OAuth2 resource-server JWT validation.

Gateway-owned browser sessions remain the only approved first-party browser
session mechanism.

## Files That Remain

These files are still active and should remain:

- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/AuthBffController.java`
  for BFF login/logout/session.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
  for gateway BFF and resource-server policy.
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
  for `/api/v1/users/me`.
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
  for Keycloak-sub identity mapping and profile updates.
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/CurrentUserResponse.java`
  for safe application user/profile responses.

## Guardrails

Future MVP work must not:

- Add application-owned password login or signup endpoints.
- Issue custom browser-facing JWTs.
- Add shared HS256 signing secrets for user authentication.
- Store access, ID, or refresh tokens in browser storage.
- Treat gateway validation as the only service authorization boundary.

## Verification

Recommended checks:

```powershell
rg -n "JwtService|LoginRequest|SignupRequest|UserResponse|jjwt|jwt\.secret|jwt\.expiration" auth-service
rg -n "localStorage" frontend/src/app
.\mvnw.cmd -pl auth-service -am test
.\mvnw.cmd -pl api-gateway -am test
npm.cmd test -- --include src/app/core/services/auth.service.spec.ts --include src/app/core/interceptors/auth.interceptor.spec.ts --include src/app/core/guards/auth.guard.spec.ts --watch=false
```

Expected result:

- No `auth-service` JWT issuance matches.
- Frontend `localStorage` matches are limited to tests proving browser token
  storage is not used.
- Auth-service, gateway, and frontend auth tests pass.
