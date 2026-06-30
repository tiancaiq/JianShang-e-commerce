# SIGNUP-00 Sign-Up And External Identity Provider Plan

Status: Complete

Scope: MVP authentication and accounts planning

Related decisions and slices:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam/iam-01-keycloak-local-setup.md`
- `docs/mvp/iam/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/iam-03-identity-user-keycloak-sub-mapping.md`
- `docs/mvp/iam/iam-04-frontend-auth-session-awareness.md`
- `docs/mvp/iam/login-01-login-session-ux-plan.md`

## Purpose

SIGNUP-00 defines how user sign-up and external identity providers fit the
approved Keycloak plus gateway BFF architecture before implementation begins.

The product goal is:

- Users can create a marketplace account with email/password through
  Keycloak-managed registration.
- Users can sign in or sign up with Google through Keycloak identity
  brokering.
- After first successful authentication, the existing `GET /api/v1/users/me`
  flow creates or returns the application-owned identity user mapped to the
  immutable Keycloak `sub`.
- Browser JavaScript still receives no access token, refresh token, ID token,
  or password.

## Architectural Decision

Sign-up remains an identity-provider concern.

Keycloak owns:

- Registration form and account creation.
- Password storage and password policy.
- Email verification.
- Password recovery.
- Google federation and account linking.
- MFA and required actions.
- OAuth tokens and identity-provider sessions.

Application services own:

- Internal user record keyed by Keycloak `sub`.
- Display name, phone, avatar, and account status.
- Platform roles and business membership checks.
- Seller activation and product-domain permissions.

The application must not reintroduce custom signup, custom password login, or
custom JWT issuance.

## Slice Sequence

### SIGNUP-01 Keycloak Self-Registration

Enable email/password sign-up through Keycloak.

Expected work:

- Update `infra/keycloak/realm-msb-local.json` to allow registration.
- Keep `registrationEmailAsUsername=true`.
- Keep unique email addresses.
- Keep email verification enabled.
- Keep default realm role `BUYER`.
- Ensure users created through registration can complete the existing
  `LOGIN-01` BFF callback/session flow.
- Confirm first call to `GET /api/v1/users/me` creates the local identity
  mapping.
- Update local/demo documentation for developer test users.

Non-goal:

- Do not add `POST /api/v1/auth/register` in application code.

### SIGNUP-02 Google Identity Provider

Add Google as a Keycloak identity provider for sign-in and sign-up.

Expected work:

- Add a Google identity-provider entry to the local realm export or document
  the exact local manual setup when secrets cannot be committed.
- Use environment variables or local secret placeholders for:
  - `KEYCLOAK_GOOGLE_CLIENT_ID`
  - `KEYCLOAK_GOOGLE_CLIENT_SECRET`
- Document the Google OAuth redirect URI required by Keycloak.
- Confirm Google-authenticated users still map to application users through
  Keycloak `sub`, not email or Google subject.
- Confirm default application access remains buyer-only unless later
  application flows grant seller, business, or admin access.

Non-goals:

- Do not store Google access tokens in application services.
- Do not let Angular call Google OAuth directly for MVP web login.
- Do not grant seller, business, or admin permissions from Google login.

### SIGNUP-03 Frontend Sign-In/Sign-Up UX

Polish the marketplace entry point for new and returning users.

Expected work:

- Update the login page copy to say sign in or create account.
- Preserve `LOGIN-01` client and safe return URL behavior.
- Prefer Keycloak-hosted registration and Google buttons for MVP.
- If adding an Angular "Create account" link, route through a gateway
  endpoint that redirects to Keycloak registration instead of collecting
  passwords in Angular.
- Keep admin portal self-registration hidden or unavailable.

Possible gateway helper:

```text
GET /api/v1/auth/register?client=marketplace&returnUrl=/account/profile
```

The helper would use the same safe-return-url rules as login and redirect to
Keycloak's registration flow. It must not accept credentials.

## User Flows

### Email/Password Sign-Up

1. User opens the marketplace login/sign-up entry point.
2. User chooses Keycloak registration.
3. Keycloak collects email, password, and required profile data.
4. Keycloak sends verification when configured.
5. After successful registration/authentication, Keycloak redirects to the
   gateway callback.
6. Gateway creates the BFF session and returns to the safe `returnUrl`.
7. Angular loads `GET /api/v1/auth/session`.
8. Angular calls `GET /api/v1/users/me`; `auth-service` creates or returns
   the local identity mapping.

### Google Sign-In/Sign-Up

1. User opens the marketplace login/sign-up entry point.
2. User chooses Google from the Keycloak login screen.
3. Google authenticates the user and returns to Keycloak.
4. Keycloak creates or links the Keycloak user.
5. Keycloak redirects to the gateway callback.
6. Gateway creates the BFF session and returns to the safe `returnUrl`.
7. Angular calls `GET /api/v1/users/me`; `auth-service` maps the Keycloak
   subject to the local user.

## Security Rules

- Email is not an identifier. The stable app mapping remains Keycloak `sub`.
- Do not trust a Google email as verified unless Keycloak maps a verified
  email claim from Google.
- Do not allow unvalidated external `returnUrl` values.
- Do not store passwords, password reset tokens, Google tokens, OAuth access
  tokens, OAuth refresh tokens, ID tokens, MFA secrets, or Keycloak sessions
  in application tables.
- Default sign-up grants only the baseline buyer capability.
- Business seller access still requires business membership.
- Admin access still requires platform admin authorization and production MFA.
- Account linking conflicts, duplicate email handling, and provider unlinking
  must remain Keycloak-owned unless an approved later slice changes that
  boundary.

## API Contract Direction

The existing contract entries for application-owned registration should be
reconciled before implementation.

Replace or supersede application credential endpoints with BFF redirect
contracts:

```text
GET /auth/login?client=&returnUrl=
GET /auth/register?client=&returnUrl=     optional helper
GET /auth/session
POST /auth/logout
GET /users/me
```

The registration helper, if implemented, redirects to Keycloak and does not
accept JSON credentials. It returns no token payload.

## Configuration Direction

Local realm:

- `registrationAllowed` becomes `true` in the local realm export when
  SIGNUP-01 is implemented.
- `registrationEmailAsUsername` remains `true`.
- `verifyEmail` remains `true`.
- Default role remains `BUYER`.

Google provider:

- Client ID and secret must not be committed.
- Local developer setup may use `.env` values or manual Keycloak admin-console
  configuration.
- Production setup must use a secret manager and environment-specific Google
  OAuth clients.

## Acceptance Criteria

- The sign-up architecture keeps Keycloak as the sole credential owner.
- The application does not add password registration endpoints.
- Email/password registration has a clear implementation slice.
- Google sign-in/sign-up has a separate implementation slice.
- First authenticated access still creates the application user through
  `GET /api/v1/users/me`.
- Login, sign-up, and Google flows preserve safe return URL behavior from
  `LOGIN-01`.
- Seller, business, and admin access are not granted by account creation.
- The API contract cleanup needed for registration is identified.

## Test Expectations

SIGNUP-01 should test or manually verify:

- Keycloak registration is enabled in the local realm.
- A newly registered user can sign in through the gateway BFF flow.
- The session response exposes no OAuth tokens.
- `/users/me` creates the application-owned user mapping.
- Default access is buyer-only.

SIGNUP-02 should test or manually verify:

- Google sign-in reaches Keycloak and returns through the gateway callback.
- Google-created users map by Keycloak `sub`.
- No Google token is exposed to Angular or stored by application services.
- Safe return URLs still work.

SIGNUP-03 should test:

- Login/sign-up page copy and links.
- Registration link preserves client and return URL.
- Admin self-registration is not advertised.
- No browser token storage is introduced.

## Non-Goals

- No custom application signup endpoint.
- No direct Angular password registration form.
- No direct Angular Google OAuth flow.
- No application storage of identity-provider tokens.
- No business onboarding, seller activation, chat, listings, reviews, or
  notifications.
- No admin invitation or admin self-registration flow.
