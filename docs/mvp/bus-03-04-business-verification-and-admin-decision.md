# BUS-03/BUS-04 Business Verification and Admin Decision

## Scope

BUS-03 adds a minimal signed provider callback endpoint for business
verification state. BUS-04 adds manual platform-admin decisions for submitted
business applications.

This slice does not add an admin UI, store editing, staff invitations,
business listings, inventory, checkout, or external provider integration
setup.

## API

Provider callback:

```text
POST /api/v1/webhooks/business-verification
X-MSB-Signature: sha256=<hmac-sha256 hex>
```

Body:

```json
{
  "eventId": "provider-event-001",
  "applicationId": "01JY...",
  "outcome": "UNDER_REVIEW",
  "reason": "Provider accepted packet"
}
```

Admin decision:

```text
POST /api/v1/admin/business-applications/{id}/decision
```

Body:

```json
{
  "decision": "APPROVE",
  "reason": "Business information verified"
}
```

Valid decisions are `APPROVE`, `REJECT`, and `REQUEST_INFORMATION`.

Frontend admin review path:

```text
/admin/business-applications
```

The current MVP frontend page accepts an application ID, decision, and reason.
It does not include an admin queue or application search yet.

## Behavior

- Webhook callbacks require an HMAC over the raw request body.
- Duplicate provider `eventId` values are ignored without duplicate effects.
- Provider callbacks can move a submitted application to `UNDER_REVIEW` or
  `VERIFICATION_FAILED`.
- Admin decisions require local role `PLATFORM_ADMIN`.
- Approval creates an active business and an active owner membership for the
  applicant.
- Rejection and information requests store decision reason and reviewer
  metadata on the application.
- Provider callbacks and admin decisions are appended to
  `business_verification_events`.

## Local Verification

Run backend tests:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl auth-service -am test
```

Run gateway tests:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -pl api-gateway -am test
```

Run frontend checks:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
npm.cmd run build
```

## Deferred

- Admin portal UI.
- Store profile creation/editing.
- Business staff invitations and permissions.
- Business listings and inventory.
- Real provider onboarding and secret rotation workflow.
