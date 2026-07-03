# SIGNUP-01 Keycloak Self-Registration

Status: Implemented

Scope: MVP authentication and accounts

Related decisions and slices:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam/core/iam-01-keycloak-local-setup.md`
- `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/core/iam-03-identity-user-keycloak-sub-mapping.md`
- `docs/mvp/iam/login/login-01-login-session-ux-plan.md`
- `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md`

## Purpose

SIGNUP-01 enables email/password account creation through Keycloak
self-registration while keeping application services out of the password and
credential lifecycle.

The application still creates its own identity record only after a successful
Keycloak authentication, through the existing `GET /api/v1/users/me` flow.

## Implemented Behavior

### Keycloak

- Local realm self-registration is enabled:

```text
registrationAllowed=true
```

- Existing identity settings remain in place:
  - email is used as username;
  - email login is enabled;
  - duplicate emails are disabled;
  - email verification is enabled;
  - default realm role remains `BUYER`.

### Gateway

Added a BFF registration redirect helper:

```text
GET /api/v1/auth/register?client=marketplace|seller-portal|admin-portal&returnUrl=/safe/path
```

Rules:

- The endpoint starts the OIDC authorization flow with Keycloak's registration
  action.
- It accepts no credentials and returns no OAuth tokens.
- Unknown clients return `404`.
- Safe same-site relative return URLs are stored using the existing
  `LOGIN-01` return-url session handling.
- Unsafe external return URLs are ignored.
- The actual account form remains Keycloak-hosted.

### Frontend

- The login page copy now presents sign-in and account creation together.
- A `Create account` action sends the browser to the gateway registration
  helper.
- The create-account action preserves the selected login client and safe
  return URL.
- Admin-client sign-in does not advertise self-registration.
- Browser JavaScript still stores no OAuth tokens.

## Files Changed

- `infra/keycloak/realm-msb-local.json`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/AuthBffController.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/features/auth/login.component.ts`
- `frontend/src/app/features/auth/login.component.spec.ts`
- `docs/mvp/api-contract.md`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/iam/core/iam-01-keycloak-local-setup.md`

## Verification Commands

Frontend:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Gateway:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl api-gateway -am test "-Dtest=AuthBffControllerTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Manual Keycloak verification:

1. Re-import the local realm if the existing local Keycloak database already
   contains `msb-local`.
2. Open the marketplace login page.
3. Select `Create account`.
4. Confirm Keycloak opens the registration screen.
5. Register a user and complete any configured email verification step.
6. Confirm the browser returns through the gateway callback.
7. Confirm `GET /api/v1/users/me` creates the application-owned identity row.

Verified on 2026-06-29:

- Realm export JSON parses successfully.
- Frontend: 115 tests passed.
- Gateway: 12 `AuthBffControllerTests` passed.

Manual browser registration still requires re-importing the local Keycloak
realm when an existing `msb-local` database volume was created before
`registrationAllowed=true`.

## Non-Goals

- No custom `POST /api/v1/auth/register`.
- No Angular password registration form.
- No application password storage.
- No browser access-token or refresh-token storage.
- No Google identity provider. That remains `SIGNUP-05`.
- No seller, business, admin, chat, listing, review, or notification behavior.
