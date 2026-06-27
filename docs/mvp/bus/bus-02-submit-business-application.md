# BUS-02 Submit Business Application

Status: implemented.

## Scope

BUS-02 lets the authenticated applicant submit their own draft business
application.

Implemented behavior:

- `POST /api/v1/business-applications/{id}/submit`
- requires `If-Match`
- enforces applicant ownership
- accepts only `DRAFT` applications
- revalidates required draft fields
- changes status to `PENDING_VERIFICATION`
- sets `submitted_at`
- updates the Angular `/business/apply` success panel with a submit action

## Non-goals

- no admin review
- no approval/rejection decision
- no business creation
- no owner membership creation
- no store profile
- no external verification webhook
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
3. Save a draft application.
4. Click `Submit application`.
5. Confirm the status changes to `PENDING_VERIFICATION`.
