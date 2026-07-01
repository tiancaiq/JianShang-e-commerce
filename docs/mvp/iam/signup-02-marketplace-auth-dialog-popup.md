# SIGNUP-02 Marketplace Auth Dialog And Popup OIDC

Status: Implemented

Scope: MVP authentication and accounts

Related decisions and slices:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/iam-04-frontend-auth-session-awareness.md`
- `docs/mvp/iam/signup-01-keycloak-self-registration.md`

## Purpose

SIGNUP-02 keeps the marketplace login and account creation entry point inside
the marketplace experience while preserving the Keycloak credential boundary.

Angular does not collect or submit passwords. It opens the Keycloak OIDC flow
in a small popup and refreshes the gateway BFF session after the popup
completes.

## Implemented Behavior

### Marketplace UI

- The marketplace header opens a marketplace-themed sign-in dialog instead of
  directly navigating to Keycloak.
- The dialog offers:
  - sign in;
  - create account.
- Both actions open a popup-sized OIDC flow.
- The existing `/login` page also uses popup OIDC actions, so protected-route
  redirects do not force the main browser tab into Keycloak.

### Gateway

- `/api/v1/auth/login` and `/api/v1/auth/register` accept `mode=popup`.
- Popup mode is stored only in the gateway session for the current OAuth flow.
- After successful OAuth authentication, the gateway returns a small popup
  completion page that posts `MSB_AUTH_COMPLETE` to the marketplace origin and
  closes the popup.
- Normal full-page login remains supported when `mode=popup` is absent.

### Keycloak Theme

- The local/demo Keycloak realm is configured to use the `msb-marketplace`
  login theme.
- Docker Compose mounts `infra/keycloak/themes` into Keycloak.
- The theme inherits Keycloak's login templates and applies marketplace colors
  and card styling. Keycloak still owns password collection.

## Security Notes

- Passwords remain outside Angular and application services.
- OAuth tokens remain server-side in the gateway session.
- Browser token storage is still cleared on logout as a legacy hardening step.
- Logout still removes the server-side authorized client and redirects through
  Keycloak logout.

## Verification

- Gateway auth controller tests cover popup mode and normal redirect behavior.
- Frontend auth service tests cover popup URL construction and parent session
  refresh after callback.
- Marketplace layout tests cover the auth dialog entry point.

## Deployment Notes

Existing local/demo Keycloak volumes may not automatically pick up realm theme
or registration changes from `realm-msb-local.json`. Re-import/reset the local
realm or apply the equivalent settings through the Keycloak admin console when
testing an existing Keycloak volume.
