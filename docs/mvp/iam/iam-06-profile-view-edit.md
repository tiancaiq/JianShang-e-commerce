# IAM-06 Profile View/Edit

## Requirement

IAM-06 allows an authenticated user to view and edit their basic application
profile.

This slice uses the IAM-03 `users` table. No address table, email change flow,
avatar upload flow, seller activation, registration, listing, or chat behavior
was added.

## Implemented Behavior

### Backend

- `GET /api/v1/users/me` continues to return the authenticated identity user.
- `PATCH /api/v1/users/me` updates only:
  - `displayName`
  - `phone`
  - `avatarUrl`
- Patch requests use `If-Match` with the current `version` for optimistic
  conflict detection.
- Stale versions return `409 VERSION_CONFLICT`.
- Unsupported/internal fields are rejected.
- Email and email verification remain Keycloak-owned.
- `updated_at` changes and `version` increments on successful profile update.

### Frontend

- Added protected `/profile` route.
- Added profile navigation item.
- Profile page loads `GET /api/v1/users/me`.
- Save sends only `displayName`, `phone`, and `avatarUrl`.
- Save sends the current profile version in `If-Match`.
- Validation errors and unauthorized states are handled.
- No token storage or bearer token dependency was added.

## Files Changed

- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthController.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/controller/AuthServiceExceptionHandler.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/dto/UpdateCurrentUserRequest.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/model/User.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/AuthService.java`
- `auth-service/src/main/java/com/msb/ecom/auth_service/service/ProfileVersionConflictException.java`
- `auth-service/src/test/java/com/msb/ecom/auth_service/AuthServiceApplicationTests.java`
- `frontend/src/app/core/models/auth.model.ts`
- `frontend/src/app/core/models/user.model.ts`
- `frontend/src/app/core/services/auth.service.ts`
- `frontend/src/app/core/services/user-profile.service.ts`
- `frontend/src/app/core/services/user-profile.service.spec.ts`
- `frontend/src/app/features/account/profile.component.ts`
- `frontend/src/app/features/account/profile.component.spec.ts`
- `frontend/src/app/app.routes.ts`
- `frontend/src/app/layout/sidebar/sidebar.component.ts`
- `docs/mvp/api-contract.md`
- `docs/mvp/database.md`

## Verification Commands

Frontend:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

Auth service:

```powershell
.\mvnw.cmd -pl auth-service -am test
```

## Non-Goals

- No address book.
- No email change or reverification flow.
- No avatar upload.
- No seller activation.
- No registration.
- No listing or chat changes.
