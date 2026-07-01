# AUTH-STAB-P0-01 Auth Verification Baseline

Status: complete.

## Goal

Record the repeatable verification baseline for completed login, logout,
sign-up, native auth, and external-provider wiring before more user profile or
communication work starts.

This cleanup slice does not add features, change API contracts, or change the
database schema.

## Scope Reviewed

- Gateway BFF login, register, session, logout, popup, and provider redirect
  behavior.
- Marketplace native email/password login and registration bridge.
- Frontend auth session state, popup URL generation, native auth CSRF use,
  logout form submission, and hidden Google CTA.
- Local Keycloak realm JSON validity and Google IdP placeholder.

## Verification Commands

Gateway focused auth tests:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerTests,AuthBffControllerNativeTests,NativeAuthServiceTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Auth service tests:

```powershell
.\mvnw.cmd -pl auth-service -am test
```

Frontend focused auth tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/core/services/auth.service.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts --include=src/app/core/guards/auth.guard.spec.ts
```

Realm JSON check:

```powershell
python -c "import json; json.load(open(r'infra/keycloak/realm-msb-local.json', encoding='utf-8')); print('realm_json_ok')"
```

Token-storage check:

```powershell
rg -n "localStorage|sessionStorage|accessToken|refreshToken|idToken" frontend/src/app api-gateway auth-service
```

## Browser Smoke Checklist

Use the local marketplace base URL for the environment under test. In local
development this is usually `http://localhost:4200`.

1. Open the marketplace as a guest and confirm public browse remains visible.
2. Open a protected marketplace account route and confirm the marketplace auth
   dialog appears or the login flow preserves a safe relative return URL.
3. Create a new marketplace account through the native create-account tab.
4. Log out and confirm the browser returns to the marketplace root, not a
   stale login page.
5. Sign in again through the native sign-in tab.
6. Try invalid credentials and confirm the error is generic.
7. Try duplicate registration and confirm the duplicate-account error is safe.
8. Confirm `Continue with Google` is not visible while the provider is not
   configured.

## Current Results

Verified during `AUTH-STAB-P0` cleanup:

- Gateway focused auth tests: 27 tests passed.
- Auth-service tests: 56 tests passed.
- Frontend focused auth tests: 26 specs passed.
- Realm JSON check: `realm_json_ok`.
- Token-storage search: no browser OAuth token storage path found. Matches are
  server-side token handling, JSON assertions that tokens are absent, or
  legacy-storage cleanup tests.

Local warnings observed:

- Maven/JDK warnings for dynamic agents, native access, and deprecated
  `Unsafe` calls under the local Java runtime.
- Common-web intentional test log includes a fake secret string in a thrown
  exception to assert the API response remains safe.

No production code behavior, API contract, or database schema changed for this
verification slice.

## Notes

- Browser JavaScript must not receive or store OAuth tokens.
- Logout is a gateway BFF form POST with CSRF and server-side OAuth client
  cleanup.
- Google provider wiring remains backend-ready but hidden in the marketplace
  UI until a later enablement slice.
