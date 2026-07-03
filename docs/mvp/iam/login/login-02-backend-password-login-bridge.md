# LOGIN-02 Backend Password Login Bridge

Status: Implemented

Scope: MVP authentication and accounts

Related slices:

- `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/login/login-01-login-session-ux-plan.md`
- `docs/mvp/iam/signup/signup-03-marketplace-native-auth.md`

## Purpose

LOGIN-02 lets marketplace users sign in with email and password without leaving
the marketplace auth dialog. Keycloak remains the identity provider and
password authority; the gateway only acts as the BFF bridge.

## Implemented Behavior

- `POST /api/v1/auth/native/login` accepts marketplace email/password
  credentials.
- The endpoint is CSRF-protected and returns a no-store session response.
- The gateway exchanges the credentials with Keycloak using the marketplace
  client and stores OAuth tokens only server-side.
- Browser JavaScript receives only authenticated state, safe user summary, and
  CSRF metadata.
- Invalid credentials return `401 INVALID_CREDENTIALS`.
- A disabled Keycloak direct-access grant returns
  `503 IDENTITY_PROVIDER_CONFIGURATION` so local setup problems are visible.

## Verification

- Gateway controller tests verify CSRF enforcement and token-free session
  responses.
- Gateway service tests verify Keycloak bridge error mapping.
- Browser verification covers marketplace modal sign-in after local Keycloak is
  configured with direct access grants.

## Limits

- This bridge is marketplace-only for the native email/password UI.
- Seller portal and admin portal can continue using hosted OIDC login.
- Password recovery remains Keycloak-owned.
