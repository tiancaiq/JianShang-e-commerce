# IND-01 Individual Seller Activation

Status: implemented.

## Scope

IND-01 lets an authenticated user activate one individual seller profile.

Implemented behavior:

- `POST /api/v1/individual-seller/activation`
- creates an active `individual_seller_profiles` row
- grants the local `INDIVIDUAL_SELLER` role in `user_roles`
- stores public city and region only
- requires current terms version `2026-01`
- rejects duplicate activation, exact address fields, internal IDs, status, and role fields
- adds legacy Angular route `/seller/activate`

MVP target UI:

- individual seller activation should move to the marketplace account route
  `/account/seller-profile`
- see `docs/mvp/ind/ind-03-marketplace-individual-selling-plan.md`

## Non-goals

- no listing creation
- no chat
- no seller profile editing
- no business seller onboarding
- no Keycloak role mutation
- no exact address or meeting-location storage

## Verification

Backend:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl auth-service -am test
```

Frontend:

```powershell
cd frontend
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

Manual test:

1. Run Keycloak, MySQL, auth-service, api-gateway, and Angular.
2. Log in through the gateway BFF.
3. Open `http://localhost:4200/seller/activate`.
4. Accept the off-platform disclosure.
5. Submit city, region, and current terms.
6. Confirm `201` from `/api/v1/individual-seller/activation`.
7. Confirm duplicate submit returns `409 INDIVIDUAL_SELLER_ALREADY_ACTIVE`.
