# IAM-04 Frontend Auth Session Awareness

## Requirement

IAM-04 makes the Angular frontend use the gateway BFF session established in
IAM-02 and the identity user endpoint added in IAM-03.

The browser receives and sends only the gateway-managed HttpOnly session cookie.
Angular does not read, store, or attach access tokens or refresh tokens.

## Implemented Behavior

- Protected frontend routes call `GET /api/v1/auth/session` through the
  gateway before allowing navigation.
- When the gateway session is authenticated, Angular calls
  `GET /api/v1/users/me` and stores only the returned application user in
  in-memory signals.
- The login screen redirects the browser to `GET /api/v1/auth/login`.
- Logout submits a browser `POST /api/v1/auth/logout` form with the CSRF
  parameter from the gateway session response so Keycloak logout redirects can
  complete as top-level navigation.
- The HTTP interceptor no longer reads `localStorage`, no longer calls
  `getToken`, and no longer writes an `Authorization: Bearer ...` header.
- Gateway HTTP requests use `withCredentials`; unsafe gateway requests include
  the current BFF CSRF header when available.

## Files Changed

- `frontend/src/app/core/models/auth.model.ts`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `frontend/src/app/core/guards/auth.guard.ts`
- `frontend/src/app/features/auth/login.component.ts`
- `frontend/src/app/layout/navbar/navbar.component.ts`
- `frontend/src/app/core/services/auth.service.spec.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.spec.ts`
- `frontend/src/app/core/guards/auth.guard.spec.ts`

## API Contract Consumed

```text
GET  /api/v1/auth/session
GET  /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/users/me
```

## Local Test Steps

Start the required backend services from the previous IAM slices:

```powershell
docker compose up -d keycloak mysql
.\mvnw.cmd -pl auth-service -am spring-boot:run
.\mvnw.cmd -pl api-gateway -am spring-boot:run
```

Start Angular:

```powershell
cd frontend
npm.cmd start
```

Manual browser check:

1. Open `http://localhost:4200`.
2. Unauthenticated access should land on `/login`.
3. Click `Continue to sign in`; the browser should go through the gateway
   login endpoint and Keycloak.
4. After login, the frontend should return to the app and show the user from
   `GET /api/v1/users/me`.
5. Click logout; the browser should call gateway logout and return to the app
   unauthenticated.

Automated frontend checks:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

## Tests Added

- Auth service loads unauthenticated sessions without touching token storage.
- Auth service loads `/api/v1/users/me` only after an authenticated gateway
  session.
- Login redirects to the gateway login endpoint.
- Logout posts to the gateway logout endpoint with the CSRF parameter.
- Auth guard allows authenticated BFF sessions and redirects unauthenticated
  sessions.
- Auth interceptor sends cookies/CSRF for gateway requests without adding
  bearer tokens.

## Non-Goals

- No registration flow.
- No profile edit UI.
- No seller, listing, or chat feature changes.
- No browser access-token or refresh-token storage.
- No new database schema or migration.
