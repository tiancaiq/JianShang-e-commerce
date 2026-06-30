# LOGIN-01 Login And Session UX Plan

Status: Implemented

Scope: MVP authentication and accounts

Related decisions and slices:

- `docs/adr/0001-keycloak-oidc-bff-authentication.md`
- `docs/mvp/iam/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/iam-04-frontend-auth-session-awareness.md`
- `docs/mvp/iam/iam-05-protected-route-test.md`
- `docs/mvp/iam/iam-06-profile-view-edit.md`

## Purpose

LOGIN-01 verifies and polishes the existing first-party web login experience
without changing the approved authentication architecture.

The approved architecture remains:

- Keycloak owns credentials, password recovery, email verification, MFA,
  identity-provider sessions, and OAuth tokens.
- The API gateway owns the browser BFF session, CSRF protection, OIDC login,
  OIDC callback, token relay, refresh, and logout.
- Angular receives no access token or refresh token and stores no browser
  bearer token.
- Authenticated application profile data comes from `GET /api/v1/users/me`.

This slice exists because login is now a product workflow used by the
marketplace, business seller portal, and admin portal. It should behave
consistently before more user communication features depend on it.

## User Experience Goals

### Marketplace

- Guests can browse public marketplace pages without login.
- Login entry points are available from account/profile, sell, saved future
  user areas, and protected actions.
- After login, users return to the page or action they intended to use when
  that destination is safe.
- Signed-in users see a stable account/profile entry.
- Expired sessions return users to guest state or a login prompt without a
  broken route.

### Business Seller Portal

- Seller portal login uses the `seller-portal` client.
- Login alone does not grant business access.
- After login, business pages still check current business membership and
  permission in the owning service.
- Users without required business access see a clear forbidden/no-access
  state instead of a login loop.

### Admin Portal

- Admin login uses the `admin-portal` client.
- Login alone does not grant admin access.
- Admin pages still require platform admin authorization in backend services.
- Users without admin access see a clear forbidden/no-access state.

## Backend Scope

LOGIN-01 adjusted only the existing gateway behavior:

```text
GET  /api/v1/auth/login?client=marketplace|seller-portal|admin-portal
GET  /api/v1/auth/callback/{client}
GET  /api/v1/auth/session
POST /api/v1/auth/logout
```

Rules:

- `client` defaults to `marketplace`.
- Unknown clients are rejected or normalized to a safe default by documented
  gateway behavior.
- Login starts a redirect flow; it never accepts credentials in application
  JSON and never returns OAuth tokens.
- Session responses are cache-disabled and contain no access token, refresh
  token, ID token, token type, or raw Keycloak session data.
- Logout is state-changing and requires CSRF for browser callers.
- Redirect destinations must be first-party relative paths or validated
  first-party origins. Open redirects are not allowed.

No database migration was added for LOGIN-01.

## Frontend Scope

LOGIN-01 adjusted the Angular authentication path:

- App startup session loading.
- Login button/link behavior.
- Return URL handling for protected routes.
- Guest versus signed-in header state.
- Route guard behavior for unauthenticated users.
- Forbidden state behavior for authenticated users without required access.
- Logout form submission with the current CSRF token.
- In-memory user/session state reset after logout or session expiry.

Frontend code must continue to use same-origin `/api/v1/...` URLs for gateway
requests and must not store OAuth tokens in `localStorage`, `sessionStorage`,
or Angular services.

## Authorization Boundaries

LOGIN-01 must preserve these boundaries:

- Authenticated users can access account/profile routes.
- Authenticated users cannot manage another user's account profile.
- Authenticated users cannot access business management without current
  business membership and permission checks.
- Authenticated users cannot access admin operations without platform admin
  authorization.
- Frontend route guards improve user experience but do not replace backend
  authorization.

## Acceptance Criteria

- Marketplace login starts through the gateway and returns to a safe intended
  marketplace destination.
- Seller portal login uses the seller client and does not bypass business
  membership checks.
- Admin portal login uses the admin client and does not bypass admin checks.
- Already-authenticated users are not sent through unnecessary login loops.
- Unauthenticated protected-route access shows a login path with a safe return
  destination.
- Authenticated but unauthorized access shows a forbidden/no-access state.
- Logout clears frontend session state and delegates to gateway logout.
- Session expiry is handled without stale signed-in UI.
- Browser JavaScript does not read, store, or attach OAuth access or refresh
  tokens.
- No registration, password recovery, profile-edit, listing, chat, cart,
  checkout, payment, order, notification, review, or AI behavior is added.

## Implemented Behavior

- Protected frontend routes redirect unauthenticated users to `/login` with a
  safe `returnUrl` query parameter.
- Marketplace routes use the `marketplace` login client.
- Seller routes use the `seller-portal` login client.
- Admin routes use the `admin-portal` login client.
- The login page sends the selected client and safe return URL to the gateway
  login endpoint.
- Already-authenticated users who land on `/login` are sent to the safe return
  URL, or marketplace home when none is present.
- The gateway stores only sanitized same-site relative return URLs during the
  OIDC redirect flow.
- The gateway OIDC success handler redirects to the configured frontend base
  URI plus the sanitized return path.
- Unsafe external return URLs are ignored and cannot become login-success
  redirects.
- Marketplace header login explicitly uses the marketplace client.

## Files Changed

- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/AuthBffController.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/auth/LoginReturnUrl.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `api-gateway/src/main/resources/application.properties`
- `api-gateway/src/test/resources/application-test.properties`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/core/guards/auth.guard.ts`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `frontend/src/app/features/auth/login.component.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`

## Test Expectations

Frontend tests should cover:

- Guest header/login state.
- Signed-in header/account state.
- Login redirect URL generation for marketplace, seller portal, and admin
  portal entry points.
- Protected route redirect behavior for unauthenticated users.
- Forbidden/no-access behavior for authenticated users without required roles
  or access.
- Logout clears local session state and includes CSRF data.
- Interceptor sends cookies/CSRF for gateway requests and does not add bearer
  tokens.

Gateway tests should cover any changed behavior for:

- Login client selection.
- Safe return destination validation.
- Session response token exclusion.
- Logout and CSRF behavior.

Manual local verification should cover:

1. Guest marketplace browsing remains available.
2. Protected marketplace profile routes trigger login.
3. Seller portal requires login and then business authorization.
4. Admin portal requires login and then admin authorization.
5. Logout returns the browser to an unauthenticated state.

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

Verified on 2026-06-29:

- Frontend: 105 tests passed.
- Gateway: 9 `AuthBffControllerTests` passed.

## Non-Goals

- No new identity provider.
- No custom JWT issuance.
- No direct username/password login form in Angular.
- No browser OAuth token storage.
- No registration or password recovery implementation.
- No profile schema changes.
- No user communication, chat, likes, reviews, or notification behavior.
