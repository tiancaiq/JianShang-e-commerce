# SIGNUP-04 Backend Credential Registration Bridge

Status: Implemented

Scope: MVP authentication and accounts

Related slices:

- `docs/mvp/iam/signup-00-sign-up-external-identity-provider-plan.md`
- `docs/mvp/iam/signup-03-marketplace-native-auth.md`
- `docs/mvp/iam/login-02-backend-password-login-bridge.md`

## Purpose

SIGNUP-04 lets marketplace users create an email/password account from the
marketplace auth dialog while keeping Keycloak as the credential store. The
application does not store passwords or issue custom browser tokens.

## Implemented Behavior

- `POST /api/v1/auth/native/register` accepts display name, email, and
  password from the marketplace dialog.
- The endpoint is CSRF-protected and returns a no-store session response.
- The gateway uses a Keycloak service-account client to create the Keycloak
  user.
- The gateway maps marketplace display name into Keycloak first/last name
  fields so Keycloak's local profile requirements do not block immediate
  marketplace sign-in.
- After user creation, the gateway signs the user in through the same
  `LOGIN-02` password bridge.
- Duplicate emails return `409 EMAIL_ALREADY_REGISTERED`.
- Browser JavaScript receives no access token, refresh token, ID token, token
  type metadata, or Keycloak session internals.

## Local Keycloak Requirements

- The local `msb-marketplace` client must allow direct access grants for the
  gateway login exchange.
- The local `msb-gateway-admin` service-account client must have user
  management permissions for account creation.
- Existing local Keycloak volumes may need a manual realm update or re-import
  because Keycloak does not automatically re-apply changed import JSON to an
  already-created realm.

## Verification

- Gateway controller tests verify CSRF enforcement and token-free registration
  responses.
- Browser verification covers creating a new marketplace account and then
  logging in through the modal.

## Limits

- Google sign-up remains an external provider flow because provider consent
  cannot be completed entirely inside the marketplace UI.
- Password recovery remains a Keycloak-owned lifecycle concern. Production
  email verification policy can tighten this bridge once outbound email is
  wired into the account lifecycle.
