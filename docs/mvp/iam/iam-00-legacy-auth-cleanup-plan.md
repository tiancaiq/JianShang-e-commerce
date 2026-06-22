# IAM-00 Legacy Auth Cleanup Plan

Status: Planned

Scope: MVP authentication and accounts

Related decision: `docs/adr/0001-keycloak-oidc-bff-authentication.md`

## Purpose

This document identifies the legacy authentication paths that must be removed
or replaced before new MVP authentication work is built.

IAM-00 is documentation only. It does not change application behavior, delete
code, implement Keycloak, implement login, or create account/profile tables.

The accepted target architecture is Keycloak OIDC with the API gateway acting
as the BFF for first-party Angular applications. Browser code must not receive
or store OAuth access or refresh tokens.

## Current Legacy Auth Summary

The repository currently contains three overlapping authentication approaches:

1. Custom HS256 JWT issuance in `auth-service`.
2. Browser `localStorage` token storage and `Authorization` header injection
   in the Angular frontend.
3. A custom HTTP session authentication filter in `auth-service` that is not
   aligned with the stateless Spring Security configuration.

These paths conflict with ADR-0001 and must not be extended for new MVP
features.

## 1. Legacy JWT Issuance Code

### Files

- `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/UserResponse.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/LoginRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/SignupRequest.java`
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
- `auth-service/src/main/resources/application.properties`
- `api-gateway/src/main/resources/application.properties`

### Current Behavior

- `JwtService` creates and validates HS256 JWTs using a shared symmetric
  secret.
- `AuthService.signup` and `AuthService.login` call `JwtService.generateToken`.
- `UserResponse` returns the generated token to the browser.
- `AuthController` exposes legacy `/auth/signup` and `/auth/login` endpoints.
- The API gateway validates bearer tokens with a shared HS256 secret instead
  of Keycloak issuer metadata and JWKS.

### Why This Is Legacy

ADR-0001 requires Keycloak to own credentials, login sessions, password
recovery, email verification, MFA, token issuance, signing keys, and key
rotation.

The application must not continue issuing browser-facing custom JWTs for new
MVP auth work.

### Later Cleanup Direction

- Remove custom JWT issuance from `auth-service`.
- Remove shared HS256 JWT secret usage from gateway validation.
- Replace bearer-token validation with Keycloak OIDC/JWKS validation.
- Replace legacy `/auth/signup` and `/auth/login` behavior with the approved
  BFF flow and Keycloak-managed account lifecycle.

## 2. Legacy Browser localStorage Auth Usage

### Files

- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
- `frontend/src/app/core/guards/auth.guard.ts`
- `frontend/src/app/core/models/auth.model.ts`
- `frontend/src/app/features/auth/login.component.ts`

### Current Behavior

- `AuthService` reads `token` and `user` from `localStorage`.
- `AuthService` writes `user.token` and the serialized user object to
  `localStorage` after login or signup.
- `AuthInterceptor` reads the stored token and sends it as
  `Authorization: Bearer <token>`.
- `UserResponse` in the frontend model requires a `token` field.
- `LoginComponent` posts email/password directly to the legacy auth service
  and handles signup directly in the Angular app.

### Why This Is Legacy

ADR-0001 requires first-party browser apps to use the gateway BFF session.
OAuth access and refresh tokens must remain server-side. Browser JavaScript
must not store tokens in `localStorage`.

### Later Cleanup Direction

- Replace token storage with session awareness from a gateway endpoint such as
  `GET /api/v1/auth/session`.
- Replace direct email/password login calls with a redirect to the gateway BFF
  login endpoint.
- Replace frontend logout with a gateway BFF logout call.
- Remove `Authorization` header injection for browser-originated first-party
  requests once the gateway session cookie is authoritative.
- Replace token-shaped frontend auth models with session/user summary models.

## 3. Legacy Session Authentication Path

### Files

- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SessionAuthenticationFilter.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`

### Current Behavior

- `SessionAuthenticationFilter` reads `USER_ID` from an HTTP session and
  populates the Spring Security context.
- `AuthService` still declares the legacy `SESSION_USER_ID` constant.
- `SecurityConfig` sets `SessionCreationPolicy.STATELESS`.
- The custom session filter is not registered in the displayed
  `SecurityFilterChain`.

### Why This Is Legacy

The filter represents an incomplete custom session design. ADR-0001 requires
gateway-owned BFF sessions for browser clients, not service-local HTTP session
authentication inside `auth-service`.

### Later Cleanup Direction

- Remove `SessionAuthenticationFilter` after the BFF session flow is in place.
- Remove unused session constants from auth-service code.
- Keep downstream services stateless resource servers that validate Keycloak
  access tokens independently.

## 4. Files That Will Be Removed Later

These files are expected to be deleted after replacement behavior exists and
tests prove that no runtime path depends on them:

- `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SessionAuthenticationFilter.java`
- Legacy auth DTOs if no longer used:
  - `auth-service/src/main/java/com/msb/ecom/auth_service/dto/LoginRequest.java`
  - `auth-service/src/main/java/com/msb/ecom/auth_service/dto/SignupRequest.java`
- Legacy frontend token model fields if replaced completely:
  - token-bearing shape in `frontend/src/app/core/models/auth.model.ts`

Removal must happen in a later implementation slice, not in IAM-00.

## 5. Files That Will Be Replaced Later

These files should remain for now, but their behavior is expected to change
when IAM-01 through IAM-06 are implemented:

- `api-gateway/src/main/java/com/msb/ecom/api_gateway/config/SecurityConfig.java`
  - Replace HS256 resource-server validation with Keycloak OIDC/JWKS
    validation, BFF login/logout/session handling, CSRF protection, and
    explicit first-party CORS rules.
- `api-gateway/src/main/resources/application.properties`
  - Replace shared JWT secret configuration with issuer/client/session
    configuration.
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
  - Replace legacy signup/login endpoints with account/profile endpoints that
    rely on authenticated Keycloak subjects.
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
  - Replace password/JWT logic with identity user mapping and profile logic.
- `auth-service/src/main/java/com/msb/ecom/auth_service/config/SecurityConfig.java`
  - Replace legacy public `/auth/signup` and `/auth/login` assumptions with
    resource-server protection for account APIs.
- `auth-service/src/main/resources/application.properties`
  - Replace legacy datasource/JWT settings with MySQL identity schema and
    Keycloak resource-server settings.
- `frontend/src/app/core/services/auth.service.ts`
  - Replace localStorage token handling with BFF session awareness.
- `frontend/src/app/core/interceptors/auth.interceptor.ts`
  - Remove bearer-token injection for first-party browser requests or replace
    with cross-cutting request behavior such as correlation/CSRF headers.
- `frontend/src/app/core/guards/auth.guard.ts`
  - Replace synchronous local signal checks from stored user data with
    session-aware route protection.
- `frontend/src/app/features/auth/login.component.ts`
  - Replace direct credential form behavior with gateway/Keycloak login
    redirect behavior or remove it when Keycloak hosts the login screen.
- `frontend/src/app/core/models/auth.model.ts`
  - Replace token-bearing login/signup response models with BFF session and
    safe user summary models.

## 6. Files That Can Remain

These files can remain, although some may need small future adjustments:

- `auth-service/src/main/java/com/msb/ecom/auth_service/AuthServiceApplication.java`
  - The service remains the application identity/profile boundary.
- `auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java`
  - Can remain as a context smoke test and be expanded for identity behavior.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/ApiGatewayApplication.java`
  - Gateway remains the external API entry point and BFF host.
- `api-gateway/src/main/java/com/msb/ecom/api_gateway/routes/Routes.java`
  - Routes can remain, with later auth-sensitive route policy updates.
- `api-gateway/src/test/java/com/msb/ecom/api_gateway/ApiGatewayApplicationTests.java`
  - Can remain and be expanded for BFF/security behavior.
- Shared technical modules:
  - `common-core`
  - `common-web`
  - `common-testing`
  - These modules remain valid as long as they do not gain auth business
    logic, JPA entities, repositories, or service-specific behavior.
- Documentation:
  - `docs/adr/0001-keycloak-oidc-bff-authentication.md`
  - `docs/adr/0002-mysql-database-ownership-and-migration-conventions.md`
  - `docs/mvp/requirements.md`
  - `docs/mvp/architecture.md`
  - `docs/mvp/api-contract.md`
  - `docs/mvp/database.md`
  - `docs/mvp/development-roadmap.md`

## Cleanup Sequence For Later Slices

1. IAM-01: Add local Keycloak infrastructure and realm/client configuration.
2. IAM-02: Implement gateway BFF login, logout, session, CSRF, and Keycloak
   validation.
3. IAM-03: Add application identity user table and Keycloak `sub` mapping.
4. IAM-04: Replace frontend localStorage auth with BFF session awareness.
5. IAM-05: Add protected route tests across frontend, gateway, and service
   boundaries.
6. IAM-06: Add profile view/edit using the new identity model.
7. Later cleanup: delete legacy JWT/session files only after replacement paths
   are tested and no routes depend on them.

## IAM-00 Acceptance Criteria

- Legacy JWT issuance code is identified.
- Legacy browser `localStorage` auth usage is identified.
- Legacy custom session authentication path is identified.
- Later-remove files are listed.
- Later-replace files are listed.
- Files that can remain are listed.
- No application behavior is changed.
- No code is deleted.
- Keycloak and login are not implemented.
