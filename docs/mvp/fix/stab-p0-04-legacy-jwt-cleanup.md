# STAB-P0-04 Legacy JWT Cleanup

Status: complete.

## Goal

Remove the remaining legacy custom JWT issuance path from active auth-service
source after the approved Keycloak OIDC gateway BFF migration.

## Scope

This slice removed only orphaned custom JWT artifacts. It did not implement
registration, login, Keycloak configuration, or new product behavior.

## Removed

- `auth-service/src/main/java/com/msb/ecom/auth_service/service/JwtService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/LoginRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/SignupRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/UserResponse.java`
- `jjwt-api`, `jjwt-impl`, and `jjwt-jackson` from `auth-service/pom.xml`

## Kept

- Gateway BFF `/api/v1/auth/login`, `/api/v1/auth/logout`, and
  `/api/v1/auth/session`.
- Auth-service `/api/v1/users/me` identity/profile behavior.
- Keycloak JWT resource-server validation in protected backend services.
- Frontend BFF session awareness.

## Notes

`SessionAuthenticationFilter` was already absent from the current source tree.
The IAM-00 document was updated from a pre-migration cleanup plan to the
current cleanup record.

The frontend still contains `localStorage` references in auth tests only. Those
tests assert that browser token storage is not read or written.

## Verification

Run:

```powershell
rg -n "JwtService|LoginRequest|SignupRequest|UserResponse|jjwt|jwt\.secret|jwt\.expiration" auth-service
rg -n "localStorage" frontend/src/app
.\mvnw.cmd -pl auth-service -am test
.\mvnw.cmd -pl api-gateway -am test
npm.cmd test -- --include src/app/core/services/auth.service.spec.ts --include src/app/core/interceptors/auth.interceptor.spec.ts --include src/app/core/guards/auth.guard.spec.ts --watch=false
```
