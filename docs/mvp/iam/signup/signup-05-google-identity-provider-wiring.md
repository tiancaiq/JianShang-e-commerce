# SIGNUP-05 Google Identity Provider Wiring

Status: Implemented

Scope: MVP authentication and accounts

Related slices:

- `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md`
- `docs/mvp/iam/signup/signup-02-marketplace-auth-dialog-popup.md`
- `docs/mvp/iam/login/login-02-backend-password-login-bridge.md`

## Current UI State

The gateway and Keycloak-provider wiring is implemented, but the marketplace
`Continue with Google` button is currently hidden until real Google OAuth
credentials are configured and the local/VM Keycloak provider is enabled.

The API contract for `provider=google` remains in place so a later enablement
slice can expose the UI entry point without redesigning the BFF flow.

## Purpose

SIGNUP-05 wires the marketplace Google action into the Keycloak
identity-broker flow while preserving the gateway BFF boundary.
Angular never calls Google OAuth directly and never receives Google, access,
refresh, or ID tokens.

## Implemented Behavior

- The gateway supports opening the existing auth popup with a Google provider
  hint.
- `GET /api/v1/auth/login` accepts `provider=google` and only allows the
  whitelisted Google provider.
- The gateway turns that safe provider request into Keycloak's
  `kc_idp_hint=google` authorization hint.
- The OAuth callback, popup completion, BFF session refresh, and
  `GET /api/v1/users/me` identity mapping remain the same as other OIDC
  logins.
- Unknown provider values return `404` at the gateway and are not passed
  through to Keycloak.

## Local Keycloak Setup

The realm export contains a disabled Google identity-provider placeholder.
Real Google OAuth credentials must be supplied out of band:

```text
KEYCLOAK_GOOGLE_CLIENT_ID
KEYCLOAK_GOOGLE_CLIENT_SECRET
```

Configure the Google OAuth client with this local redirect URI:

```text
http://localhost:8181/realms/msb-local/broker/google/endpoint
```

After setting credentials, enable the `google` identity provider in Keycloak.
Existing local Keycloak volumes may need a manual admin-console update because
realm import JSON is not re-applied to an already-created realm.

## Security Notes

- Google tokens remain Keycloak-owned.
- Application services still map users by Keycloak `sub`, not email or Google
  subject.
- Google sign-in grants only default buyer access. Seller, business, and admin
  access still require application-owned flows and authorization checks.
- Do not commit real Google OAuth secrets.

## Verification

- Gateway tests verify `provider=google` becomes `kc_idp_hint=google`.
- Gateway tests verify unknown provider hints are rejected or ignored.
- Frontend auth service tests verify the popup URL includes
  `provider=google`.
- Marketplace layout tests verify the Google CTA is hidden until provider
  credentials are configured.
- Frontend auth service tests keep the dormant Google popup URL covered.

## Limits

- End-to-end Google login requires real Google OAuth credentials configured in
  the local Keycloak realm.
- The marketplace UI entry point remains hidden until a later enablement
  slice intentionally exposes it.
- The current slice does not implement account-link conflict policy beyond
  Keycloak's default first-broker-login behavior.
