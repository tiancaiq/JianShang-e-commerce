# IAM-05 Protected Route Test

## Requirement

IAM-05 proves that authentication is enforced at the frontend route, gateway,
and protected service boundaries introduced by IAM-02 through IAM-04.

No new API, database table, registration behavior, profile editing, seller,
listing, or chat feature was added.

## Implemented Coverage

### Frontend

- The Angular auth guard still blocks unauthenticated sessions.
- Router-level tests prove `/dashboard` redirects to `/login` when
  `GET /api/v1/auth/session` reports no authenticated session.
- Router-level tests prove `/dashboard` loads when the BFF session is
  authenticated.

### API Gateway

- Missing gateway session or bearer token cannot access
  `GET /api/v1/users/me`.
- Invalid bearer tokens return `401`.
- Valid bearer tokens pass gateway authentication and reach the protected
  routing layer.
- Spoofed identity headers such as `X-User-Id` or `X-Keycloak-Sub` do not
  authenticate a request.

### Auth Service

- Missing bearer token returns `401`.
- Invalid bearer token returns `401`.
- Valid Keycloak-like bearer token reaches `GET /api/v1/users/me`.
- The service derives the actor from the validated JWT `sub` and creates or
  reuses the local identity user mapping.
- Spoofed identity headers do not change the authenticated subject.

## Files Changed

- `api-gateway/src/test/java/com/msb/ecom/api_gateway/ApiGatewayApplicationTests.java`
- `auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java`
- `frontend/src/app/core/guards/auth.guard.spec.ts`
- `docs/mvp/iam/core/iam-05-protected-route-test.md`

## Verification Commands

Frontend:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Gateway:

```powershell
.\mvnw.cmd -pl api-gateway -am test
```

Auth service:

```powershell
.\mvnw.cmd -pl auth-service -am test
```

The auth-service command uses Testcontainers for the IAM-03 MySQL identity
schema.

## Non-Goals

- No business authorization.
- No admin authorization.
- No MFA behavior beyond Keycloak configuration expectations.
- No profile editing.
- No registration, seller, listing, or chat changes.
