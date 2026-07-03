# SIGNUP-03 Marketplace Native Auth

Status: Implemented

Scope: MVP authentication and accounts

Related decisions and slices:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/signup/signup-02-marketplace-auth-dialog-popup.md`

## Purpose

SIGNUP-03 lets marketplace users sign in and create accounts from the
marketplace dialog without seeing the Keycloak UI during the normal
email/password path.

Keycloak remains the identity provider and password authority. The marketplace
submits credentials only to the gateway BFF over same-origin HTTPS. The gateway
exchanges credentials with Keycloak, stores OAuth tokens server-side, creates
the Spring Security session, and returns the same safe session summary used by
`GET /api/v1/auth/session`.

## Implemented Behavior

### Marketplace UI

- The marketplace auth dialog contains native sign-in and create-account tabs.
- Email/password sign-in posts to the gateway and keeps the user in the
  marketplace dashboard.
- Account creation posts display name, email, and password to the gateway.
- Google remains an external-provider action. It can still open a provider
  popup because Google consent cannot be completed fully inside the app.

### Gateway

- `POST /api/v1/auth/native/login` accepts marketplace email/password
  credentials and requires CSRF protection.
- `POST /api/v1/auth/native/register` creates a Keycloak user through a
  gateway service account, then signs the new user into the same BFF session.
- The gateway never returns access tokens, refresh tokens, ID tokens, token
  type metadata, or Keycloak session internals to browser JavaScript.
- The gateway stores the authorized client server-side so existing token relay
  behavior continues for downstream services.

### Local Keycloak

- The local `msb-marketplace` client enables direct access grants for gateway
  credential exchange.
- A local `msb-gateway-admin` confidential service-account client is added for
  registration user creation with `manage-users` and `view-users`.
- Production must rotate the local secrets and may replace this with a more
  tightly managed Keycloak client or registration adapter.

## Security Notes

- Passwords are sent only to the gateway, never to auth-service or other
  application services.
- Password values must not be logged by the gateway.
- CSRF is required for native auth POSTs.
- Browser JavaScript still receives only session status and safe user summary.
- Seller/admin login can continue to use full OIDC browser flows; this slice
  changes only the marketplace email/password account UX.

## Verification

- Gateway auth controller tests verify existing BFF auth behavior still builds
  and passes.
- Frontend auth service tests verify native login posts with CSRF and hydrates
  the identity user.
- Marketplace layout tests verify native registration submits through the new
  service path.

## Deployment Notes

Existing Keycloak volumes must be re-imported or updated manually to enable
direct access grants and the `msb-gateway-admin` service account. Without those
settings, the native UI will render but Keycloak will reject native login or
registration.
