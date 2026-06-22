# BUS-01 Business Application Draft

Status: implemented.

## Scope

BUS-01 lets an authenticated user start a draft business seller application.
The authenticated applicant becomes the proposed business owner.

Implemented behavior:

- `POST /api/v1/business-applications`
- `GET /api/v1/business-applications/{id}`
- `PATCH /api/v1/business-applications/{id}`
- creates `business_applications` rows with status `DRAFT`
- stores legal name, business type, country, public city/region, contact email,
  optional E.164 phone, optional website URL, and optional description
- rejects client-supplied applicant, status, submitted time, approval, reviewer,
  business, membership, or verification fields
- enforces applicant ownership on read/update
- requires `If-Match` for draft updates
- adds Angular route `/business/apply`

## Non-goals

- no submit flow
- no external verification
- no admin review
- no business creation
- no business membership
- no store profile
- no listings

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
2. Open `http://localhost:4200/business/apply`.
3. Fill the business draft form.
4. Save draft.
5. Confirm a `DRAFT` row exists in `business_applications`.
