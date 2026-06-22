# IND-02 Current Individual Seller Profile

Status: implemented.

## Scope

IND-02 lets an authenticated user read their individual seller profile and
basic reputation summary.

Implemented behavior:

- `GET /api/v1/individual-seller/me`
- returns active seller profile fields from `individual_seller_profiles`
- returns `404 INDIVIDUAL_SELLER_NOT_FOUND` for authenticated non-sellers
- returns `401` for unauthenticated callers
- Angular `/seller/activate` loads this endpoint on page entry and shows the
  active seller summary instead of the activation form when the user is already
  activated

## Non-goals

- no seller profile editing
- no listing creation
- no public seller profile page
- no chat
- no business seller onboarding

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

1. Log in through the gateway BFF.
2. Open `http://localhost:4200/seller/activate`.
3. If already activated, confirm the page shows active seller status.
4. Directly open `http://localhost:9000/api/v1/individual-seller/me`.
5. Confirm it returns the seller profile JSON while logged in.
