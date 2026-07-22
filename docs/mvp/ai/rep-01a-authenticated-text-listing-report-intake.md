# REP-01A Authenticated Text-Only Listing Report Intake And Persistence

## Status

Source implementation complete only when the Product unit, API, migration, and
MySQL integration suites are green. The capability remains disabled by default
and is not approved for production activation.

This document is intentionally isolated from the shared MVP API, database, and
development-roadmap documents while the ORD-03A publication boundary is active.
Those shared documents require reconciliation in a later cleanup checkpoint.

## External contract

Authenticated routes:

```text
POST /api/v1/reports
GET  /api/v1/reports/{reportId}
```

`POST` requires `Content-Type: application/json` and `Idempotency-Key` containing
8-128 visible ASCII characters. The complete JSON body is limited to 8,192
bytes and rejects unknown fields.

```json
{
  "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAC",
  "reasonCode": "FRAUD_OR_MISREPRESENTATION",
  "statement": "The description appears inconsistent with the public images.",
  "listingMediaIds": ["01ARZ3NDEKTSV4RRFFQ69G5FAE"]
}
```

`statement` is optional. When present it is normalized, must contain 1-2,000
characters, and rejects control characters, HTML, URLs, contact details,
coordinates, and likely street addresses. `listingMediaIds` is optional and
contains at most four unique public listing-image ULIDs. Binary uploads,
screenshots, external URLs, chat evidence, exact locations, and contact fields
are not accepted in REP-01A.

Create returns `201` for a new report and `200` for an exact idempotent replay or
24-hour semantic duplicate. `GET` and create responses expose only:

- `reportId`
- `status`
- `listingId`
- `reasonCode`
- `policyVersion`
- `createdAt`
- `updatedAt`

They never expose reporter/seller identity, statement/evidence content,
moderation case identity, aggregate counts, routing, priority, storage data,
admin state, or AI/provider metadata.

## Eligibility and hidden access

The Product database is authoritative. Only a currently public listing can be
reported. Individual listings must be `ACTIVE/APPROVED`; business listings must
be active and `BUSINESS_SELF_PUBLISHED`. The listing row is share-locked while
the immutable snapshot is created so visibility cannot change halfway through
intake.

The actor is resolved from the existing bearer-to-Auth current-user flow. An
individual owner, or an actor with editable membership on the owning business,
cannot report that listing. Disabled capability, missing/non-public/owned
listing, missing report, and cross-actor report reads use the same hidden 404
report boundary.

## Taxonomy and cases

The fixed REP-00 taxonomy is stored with `REP-00-V1`. Reason, priority, and
routing are allegations and queue metadata only. They do not authorize any
listing or account action.

Every new report links to one `LISTING_REPORT` moderation case. There is at most
one active case per listing and routing queue. `LISTING_REPORT` has a separate
database identity from `LISTING_REVIEW`; report intake never reopens, claims,
resolves, or changes a seller-submission case.

## Atomic persistence

One successful new intake transaction creates:

- an immutable public listing/version/policy snapshot and public-media hashes;
- the `RECEIVED` report and selected text/media-reference evidence;
- a `RECEIVED` history row;
- a separate open/unassigned `LISTING_REPORT` case or link to its existing case;
- durable 90-day actor/key idempotency metadata;
- a durable 24-hour actor/listing/reason duplicate window;
- HMAC-pseudonymized hourly and daily accepted-count buckets;
- one `listing-report.received.v1` outbox event.

The event contains fixed report/case/listing/snapshot IDs, listing version,
reason, route, priority, policy version, and schema version only. It contains no
reporter identity, statement, evidence, seller data, prompt, or provider field.
There are no Agent Service calls.

## Retention and release gate

The migration records the approved engineering defaults:

- idempotency: 90 days;
- semantic duplicate window: 24 hours;
- report content: 180 days;
- report metadata: 730 days;
- abuse bucket metadata: no more than 30 days after its bucket ends.

REP-01A adds no purge job. Legal/privacy approval remains mandatory before
production activation. Startup rejects intake enablement unless the retention
approval flag and a non-default abuse HMAC secret are present. Reporter identity
disclosure is unavailable.

Default configuration:

```properties
listing-reports.intake-enabled=false
listing-reports.retention-policy-approved=false
listing-reports.reporter-identity-disclosure-enabled=false
```

No seller notice, appeal, admin queue, restriction, removal, suspension, AI
classification, binary evidence, frontend, or gateway behavior is part of this
slice.
