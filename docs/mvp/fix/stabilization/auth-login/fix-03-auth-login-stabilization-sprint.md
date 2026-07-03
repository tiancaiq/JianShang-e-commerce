# FIX-03 Auth/Login Stabilization Sprint

Status: planned.

## Scope

This stabilization sprint reviews only the completed authentication, login,
logout, sign-up, native account, and external identity provider work.

Completed scope reviewed:

- `IAM-02` gateway BFF login/logout/session
- `IAM-03` identity user mapping
- `IAM-04` frontend session awareness
- `IAM-05` protected route tests
- `IAM-06` profile view/edit as it depends on authenticated session state
- `LOGIN-01` login and session UX
- `LOGIN-02` backend password login bridge
- `SIGNUP-00` sign-up and external identity provider plan
- `SIGNUP-01` Keycloak self-registration
- `SIGNUP-02` marketplace auth dialog and popup OIDC
- `SIGNUP-03` marketplace native auth
- `SIGNUP-04` backend credential registration bridge
- `SIGNUP-05` Google identity provider wiring, with the marketplace Google CTA
  currently hidden until provider credentials are configured

This sprint must not add product features. Each cleanup slice is intended to
be one pull request.

## Stabilization Rules

Every cleanup slice must:

- preserve current user-visible behavior
- preserve API contracts
- preserve database schema unless a verified migration bug requires a
  backward-safe fix
- avoid adding new login, registration, profile, chat, likes, reviews,
  notifications, or admin behavior
- keep Keycloak as the credential, identity-provider, password-recovery, and
  external-provider authority
- keep browser JavaScript free of access tokens, refresh tokens, ID tokens,
  Google tokens, and custom JWTs
- include verification notes in the slice PR

## Review Summary

The completed login/sign-up work has the right product direction: marketplace
users can sign in and create accounts from the native marketplace UI while
Keycloak remains the identity engine behind the gateway BFF.

The cleanup risk is mainly clarity and regression prevention:

- Several auth documents now describe both Keycloak-hosted and marketplace
  native flows; they need a crisp current-state summary.
- Google provider wiring exists, but the visible marketplace CTA is hidden
  until credentials are configured; docs and tests should say that clearly.
- Logout and stale-session handling were fixed during implementation and need
  a compact regression checklist.
- Native auth depends on CSRF/session refresh behavior that should be covered
  by focused tests and docs.
- Keycloak local realm settings now carry more auth responsibilities and
  should be checked for accidental secret or environment drift.

## P0: Must Fix

### AUTH-STAB-P0-01 Auth Verification Baseline

Status: complete.

One PR: yes.

Problem:

- Completed login/sign-up slices touched gateway, Keycloak realm config,
  Angular auth service, marketplace layout, and docs.
- Teammates need one repeatable verification record before more user/profile
  work builds on this auth surface.

Likely files:

- `docs/mvp/fix/stabilization/auth-login/fix-03-auth-login-stabilization-sprint.md`
- optional new verification record under `docs/mvp/fix/`

Tasks:

- Record the exact focused gateway auth test commands.
- Record the exact focused frontend auth/layout test commands.
- Record the local browser smoke paths for create account, login, logout, and
  protected route return URL.
- Confirm no browser token storage was reintroduced.
- Confirm logout removes the local BFF session and returns to marketplace.

Tests:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerTests,AuthBffControllerNativeTests,NativeAuthServiceTests" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl auth-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/core/services/auth.service.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- Verification commands and results are documented.
- Any local-only limitation is explicitly named.
- No production code behavior changes.

Completion note:

- Verification baseline recorded in
  `docs/mvp/fix/stabilization/auth-login/auth-stab-p0-01-auth-verification-baseline.md`.
- Focused gateway, auth-service, and frontend auth tests pass.

### AUTH-STAB-P0-02 Align Google Provider Docs With Hidden CTA

Status: complete.

One PR: yes.

Problem:

- `SIGNUP-05` wiring exists, but the marketplace Google button is currently
  hidden.
- Docs and tests should not imply Google is available to users before real
  provider credentials and enablement are configured.

Likely files:

- `docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md`
- `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md`
- `docs/mvp/api-contract.md`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`

Tasks:

- State that backend/BFF Google provider wiring is complete but the UI entry
  point is hidden.
- Keep the API contract for `provider=google` unchanged.
- Keep the Keycloak provider placeholder disabled by default.
- Keep tests focused on hidden CTA plus backend allowlisted provider behavior.

Tests:

```powershell
rg -n "Continue with Google|Google button|provider=google|kc_idp_hint" docs/mvp frontend/src/app
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- User-facing docs match the current UI.
- Backend provider contract remains documented.
- No Google UI is exposed.
- No API contract or schema changes.

Completion note:

- `SIGNUP-05` and `SIGNUP-00` now state that Google BFF/provider wiring is
  present but the marketplace CTA is hidden until credentials are configured.
- Marketplace layout tests assert `Continue with Google` is not visible.

### AUTH-STAB-P0-03 Native Auth Session And CSRF Regression Coverage

Status: complete.

One PR: yes.

Problem:

- Native login/register must refresh or reuse the gateway session correctly so
  stale CSRF tokens do not send users into HTML redirects or invalid JSON
  states.
- This behavior is security-sensitive and easy to regress.

Likely files:

- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/auth/AuthBffControllerNativeTests.java`
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/auth/NativeAuthServiceTests.java`

Tasks:

- Add or tighten tests proving native login/register obtain CSRF/session state
  before POST.
- Add or tighten tests proving unexpected HTML responses show a safe error and
  do not store tokens.
- Add or tighten gateway tests for invalid credentials, duplicate email, and
  dependency failure mapping.
- Preserve request/response shapes.

Tests:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerNativeTests,NativeAuthServiceTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/core/services/auth.service.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- Native auth session/CSRF behavior has explicit regression tests.
- Error handling remains generic and safe.
- No endpoint behavior changes.

Completion note:

- Frontend auth service tests cover session refresh before native login and
  native registration POSTs.
- Gateway native auth tests cover token-free responses, invalid credentials,
  direct-access-grant configuration failure, and duplicate-email mapping.

### AUTH-STAB-P0-04 Logout And Return-URL Regression Coverage

Status: complete.

One PR: yes.

Problem:

- Logout was adjusted to return users to the marketplace instead of a stale
  localhost login page.
- Login/register/popup flows depend on safe relative return URLs.

Likely files:

- `api-gateway/src/test/java/com/msb/ecom/api_gateway/AuthBffControllerTests.java`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `docs/mvp/iam/login/login-01-login-session-ux-plan.md`

Tasks:

- Verify logout clears local session state and uses environment-safe
  marketplace return behavior.
- Verify unsafe return URLs are ignored or replaced with `/`.
- Verify popup completion returns to a safe path only.
- Document the local and VM expectation without hardcoding localhost as the
  product behavior.

Tests:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerTests" "-Dsurefire.failIfNoSpecifiedTests=false"
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/core/services/auth.service.spec.ts --include=src/app/core/guards/auth.guard.spec.ts
```

Acceptance criteria:

- Logout and return-url behavior is covered by tests and docs.
- Behavior remains same-origin/environment-safe.
- No API contract or schema changes.

Completion note:

- Gateway tests cover safe return URLs, unsafe return URL rejection, popup
  mode, logout CSRF, server-side authorized-client removal, and marketplace
  post-logout redirect configuration.
- Frontend tests cover unsafe popup return URL dropping, guarded-route return
  URLs, and logout legacy token cleanup.

## P1: Should Fix

### AUTH-STAB-P1-01 Auth Documentation Current-State Cleanup

One PR: yes.

Problem:

- Auth docs now include initial Keycloak-hosted registration, marketplace
  popup OIDC, marketplace native login/register, and hidden Google provider
  wiring.
- New teammates need a concise current-state map.

Likely files:

- `docs/mvp/iam/core/iam-02-gateway-bff-login-logout-session.md`
- `docs/mvp/iam/login/login-01-login-session-ux-plan.md`
- `docs/mvp/iam/login/login-02-backend-password-login-bridge.md`
- `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md`
- `docs/mvp/iam/signup/signup-03-marketplace-native-auth.md`
- `docs/mvp/iam/signup/signup-04-backend-credential-registration-bridge.md`
- `docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md`

Tasks:

- Add one short "current behavior" section or cross-link that explains which
  flow is active for marketplace users.
- Mark older Keycloak-hosted UI paths as supported infrastructure rather than
  the preferred marketplace UX where appropriate.
- Keep all endpoints and contracts unchanged.

Tests:

```powershell
rg -n "Keycloak-hosted|native|popup|provider=google|hidden|current behavior" docs/mvp/iam
git diff --check -- docs/mvp/iam
```

Acceptance criteria:

- Teammates can understand the current auth flow in one pass.
- No stale doc tells users to use a disabled or hidden UI path as primary.

### AUTH-STAB-P1-02 Keycloak Local Realm Configuration Review

One PR: yes.

Problem:

- Realm export now covers self-registration, service account user creation,
  direct access grants for native auth, logout, and a disabled Google IdP
  placeholder.
- Config drift here can break local auth without obvious code changes.

Likely files:

- `infra/keycloak/realm-msb-local.json`
- `docker-compose.yml`
- `docker-compose.demo.yml`
- `docs/mvp/iam/core/iam-01-keycloak-local-setup.md`
- `docs/mvp/iam/signup/signup-04-backend-credential-registration-bridge.md`
- `docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md`

Tasks:

- Confirm no real secrets are committed.
- Confirm Google IdP remains disabled by default.
- Confirm required local clients and service-account permissions are
  documented.
- Confirm direct access grant usage is documented as gateway-only local/native
  bridge behavior.

Tests:

```powershell
python -c "import json; json.load(open(r'infra/keycloak/realm-msb-local.json', encoding='utf-8')); print('realm_json_ok')"
rg -n "secret|clientSecret|KEYCLOAK_GOOGLE|direct access|service-account" infra/keycloak docs/mvp/iam docker-compose*.yml
```

Acceptance criteria:

- Local realm JSON is valid.
- Secrets remain environment-backed or demo-only.
- No product behavior changes.

### AUTH-STAB-P1-03 Reduce Marketplace Auth Modal Responsibilities

One PR: yes.

Problem:

- `MarketplaceLayoutComponent` now owns layout navigation, auth modal state,
  native login/register form handling, hidden Google method wiring, logout
  entry points, and current return-url validation.
- It can stay working, but it is getting large.

Likely files:

- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- optional new small component/helper under `frontend/src/app/features/auth/`
- existing marketplace layout tests

Tasks:

- Extract the auth dialog into a focused component or pure helper only if it
  reduces complexity.
- Preserve markup, copy, routes, and behavior.
- Keep Google CTA hidden.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- Component responsibilities are smaller.
- No visual, route, or API behavior changes.

### AUTH-STAB-P1-04 Same-Origin And Environment URL Audit

One PR: yes.

Problem:

- Local work involved `localhost:4200`, gateway `localhost:9000`, and VM
  environment behavior.
- Auth code should avoid hardcoded localhost product assumptions.

Likely files:

- `api-gateway/src/main/resources/application.properties`
- `api-gateway/src/test/resources/application-test.properties`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/environments/*`
- `docker-compose*.yml`
- auth docs

Tasks:

- Search for hardcoded auth origins.
- Keep same-origin browser API calls where currently intended.
- Document which localhost URLs are local-dev examples only.
- Preserve runtime behavior.

Tests:

```powershell
rg -n "localhost:4200|localhost:9000|127\\.0\\.0\\.1|returnUrl|post_logout_redirect_uri" api-gateway frontend docs/mvp/iam docker-compose*.yml
```

Acceptance criteria:

- Any hardcoded localhost reference is either test-only, local-doc-only, or
  deliberately configurable.
- No behavior changes.

## P2: Nice To Have

### AUTH-STAB-P2-01 Browser Smoke Checklist For Auth

One PR: yes.

Problem:

- Browser verification has been done manually during implementation, but the
  exact flow is scattered through chat context.

Likely files:

- `docs/mvp/fix/`
- `docs/deploy/team-demo-readme.md`

Tasks:

- Write a short manual checklist for create account, login, logout, protected
  route return, duplicate registration, invalid credentials, and hidden Google
  CTA.
- Keep it environment-neutral.

Tests:

```powershell
rg -n "create account|logout|protected route|Google" docs/mvp/fix docs/deploy
```

Acceptance criteria:

- A teammate can manually smoke-test auth without reading implementation
  notes.
- No code changes.

### AUTH-STAB-P2-02 Auth Error Copy And Accessibility Review

One PR: yes.

Problem:

- The native modal handles invalid credentials, duplicate email, unexpected
  HTML, and popup failure states.
- Copy should be consistent and accessible without changing flow behavior.

Likely files:

- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`

Tasks:

- Review error copy for clarity and security.
- Confirm dialog labels and required fields are accessible.
- Avoid adding new UI options.

Tests:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- Copy remains generic where security-sensitive.
- Form/dialog accessibility is not worse.
- No behavior changes.

### AUTH-STAB-P2-03 Future Google Enablement Switch Plan

One PR: yes.

Problem:

- Google backend wiring exists but UI is hidden.
- When real provider credentials are ready, the team should know whether to
  show the CTA through config, feature flag, or a small follow-up slice.

Likely files:

- `docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md`
- `docs/mvp/iam/signup/signup-00-sign-up-external-identity-provider-plan.md`

Tasks:

- Document the preferred future enablement path.
- Do not expose the CTA in this cleanup.
- Do not add a new feature flag unless a separate implementation slice is
  approved.

Tests:

```powershell
rg -n "Google|enable|hidden|feature flag" docs/mvp/iam/signup/signup-05-google-identity-provider-wiring.md
```

Acceptance criteria:

- Future Google enablement is clear.
- Current UI remains unchanged.

### AUTH-STAB-P2-04 Auth Test Naming And Fixture Cleanup

One PR: yes.

Problem:

- Auth tests grew across IAM, LOGIN, and SIGNUP slices.
- Some test fixture names may now be dated or too broad.

Likely files:

- `api-gateway/src/test/java/com/msb/ecom/api_gateway/**`
- `auth-service/src/test/java/com/msb/ecom/auth_service/**`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts`

Tasks:

- Rename test methods/fixtures for readability only.
- Remove duplicated test setup if it is clearly safe.
- Preserve assertions and behavior coverage.

Tests:

```powershell
.\mvnw.cmd -pl api-gateway -am test "-Dtest=AuthBffControllerTests,AuthBffControllerNativeTests,NativeAuthServiceTests" "-Dsurefire.failIfNoSpecifiedTests=false"
.\mvnw.cmd -pl auth-service -am test
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless --include=src/app/core/services/auth.service.spec.ts --include=src/app/layout/marketplace-layout/marketplace-layout.component.spec.ts
```

Acceptance criteria:

- Tests are easier to read.
- Coverage and behavior stay the same.

## Recommended Order

1. `AUTH-STAB-P0-01`
2. `AUTH-STAB-P0-02`
3. `AUTH-STAB-P0-03`
4. `AUTH-STAB-P0-04`
5. P1 slices as capacity allows
6. P2 slices only when they reduce teammate confusion

After P0 is complete, user profile and chat planning can continue on top of a
stable login/sign-up baseline.
