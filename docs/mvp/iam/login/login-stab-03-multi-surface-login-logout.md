# LOGIN-STAB-03 Multi-Surface Login And Logout Stabilization

Status: Implemented

Scope: login/logout stabilization only

Related slices:

- `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/login/login-01-login-session-ux-plan.md`
- `docs/mvp/iam/login/login-02-backend-password-login-bridge.md`
- `docs/mvp/iam/signup/signup-04-backend-credential-registration-bridge.md`

## Purpose

This stabilization slice keeps the three first-party login surfaces clear
without changing identity ownership. Marketplace keeps the friendly in-page
dialog. Business seller and admin still use the regular Keycloak redirect
flow, but the `/login` page labels the selected portal so users know which
surface they are entering.

## Implemented Behavior

- Marketplace login remains the native marketplace dialog backed by the
  gateway BFF and Keycloak.
- Seller and admin login surfaces show distinct portal labels and supporting
  text before redirecting to Keycloak.
- Logout form submissions include a validated `client` hint:
  `marketplace`, `seller-portal`, or `admin-portal`.
- The gateway clears the BFF session, deletes the session cookie, and removes
  the server-side OAuth authorized client before redirecting.
- Marketplace logout returns to the configured marketplace URL with
  `signedOut=1`.
- Seller logout returns to
  `/login?client=seller-portal&signedOut=1`.
- Admin logout returns to `/login?client=admin-portal&signedOut=1`.
- Admin access denied includes a logout action so a non-admin user can clear
  the current BFF session and sign in with the intended account.
- Login and marketplace surfaces show a short "Signed out successfully"
  confirmation.
- Return URLs remain relative-path only. Unsafe absolute or protocol-relative
  destinations are dropped before login redirect.
- Marketplace native login/register performs a fresh `GET /auth/session` after
  credentials succeed so the frontend uses the post-login CSRF token for later
  logout.
- Marketplace navbar logout calls the shared `AuthService.logout('marketplace')`
  path directly, matching the seller/admin layouts.
- The logout form is appended to the top-level document body and includes the
  current CSRF parameter plus a validated client hint.
- Angular in-memory user state is cleared only after the logout form submit
  starts successfully. Marketplace cart state is reset only after that same
  successful start.
- If marketplace logout cannot start, the user remains signed in and the
  navbar shows a retryable error toast instead of silently changing state.
- Client hydration is not enabled in the current CSR dev/build setup, avoiding
  browser NG0505 warnings from non-SSR markup.

## Demo Account Contract

Local/demo environments should use separate Keycloak accounts for:

- Marketplace user: default buyer/account profile testing.
- Business owner: seller portal access and business membership testing.
- Admin: platform moderation testing.

Do not reuse one shared account across these surfaces. Application services
must continue to enforce role, business membership, and admin authorization;
the selected login client is only a surface hint.

## Admin Security Contract

Admin credential strength and MFA are Keycloak-owned controls. Production and
shared-demo admin accounts must use stronger passwords and MFA or equivalent
required actions in Keycloak. The Angular app and gateway do not store admin
passwords, MFA secrets, or recovery data.

## Verification

- Gateway tests cover login redirects for marketplace, seller, and admin
  clients.
- Gateway tests cover CSRF-protected logout, server-side OAuth client removal,
  surface-specific post-logout redirects, and unsafe logout client fallback.
- Frontend tests cover marketplace dialog behavior, seller/admin login labels,
  unsafe return URL dropping, logout client hints, admin access-denied logout,
  native-login post-auth CSRF refresh, protected-route reload behavior, and
  the signed-out confirmation. They also verify the top-level logout form and
  failure behavior that keeps in-memory auth state when form submission cannot
  start.
- Existing session refresh tests cover visible-page session refresh so active
  users do not lose the BFF session due only to frontend inactivity.

## Non-Goals

- No new authentication provider.
- No changes to user database schema.
- No browser token storage.
- No custom JWT issuance.
- No cart, checkout, payment, order, notification, or V2/V3 behavior.
