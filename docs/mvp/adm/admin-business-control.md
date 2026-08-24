# ADM-BUS-04/05 active business control

Status: implementation and release-candidate browser validation complete on
2026-08-15.

## Ownership and workflow

Auth Service owns business identity, membership, admin search/detail, business enforcement, effective capability decisions, and enforcement audit events. Product Service owns listing data and protects business listing creation/publication transitions. Order Service owns checkout and order state and protects new-sale commitment boundaries. No service reads another service's database.

The staff workflow is `/admin/businesses` → business detail → dry run → confirm → audit timeline → revoke one action. Search is server-side, paginated, stably sorted, and supports business state, effective enforcement state, active scope, query, and created-date filters. Detail distinguishes business, verification, store, membership, user-enforcement, and business-enforcement state. Product supplies a bounded listing-count summary. Order counts remain unavailable rather than being copied into Auth Service.

## Operational policy

| Scope | Status | Authoritative behavior |
|---|---|---|
| `BUSINESS_LISTING_CREATION` | Operational | Product rejects new business listing drafts for every member. |
| `BUSINESS_LISTING_PUBLICATION` | Operational | Product rejects submit, admin approval/activation, self-publish, and relist. Read/edit behavior and existing active status are unchanged. |
| `BUSINESS_NEW_SALES` | Operational | Order rejects before checkout persistence/inventory reservation, payment-intent creation, and paid-event inventory commit/order creation. |
| `BUSINESS_PAYOUTS` | Reserved | Not selectable and no financial behavior is implemented. |

The marketplace business ban is a server-owned policy profile. It persists all three operational scopes explicitly. It does not delete the business or store, alter listings, remove memberships, enforce users, disable Keycloak identities, cancel/refund orders, or freeze payouts.

Seller mutations compose three independent decisions: membership authorization, the acting user's capability, and the target business capability. A business restriction blocks every member acting for that business but does not affect the same person's individual-seller activity. User and business actions remain separate records and are revoked separately.

Existing active listings are not rewritten. Existing orders remain readable and fulfillable. Only new purchase commitments are controlled. A paid event blocked during confirmation remains retryable so reinstatement can resume safely; it does not create an order or commit inventory while blocked.

## Internal decision API

Authenticated commerce services use:

- `POST /api/v1/internal/businesses/capabilities/evaluate`
- `POST /api/v1/internal/businesses/capabilities/evaluate-batch`

Requests contain only business IDs and operational scopes. Responses contain allow/deny, effective action/action ID, effective/expiry times, and a safe support reference. They contain no reason, actor, case data, PII, or metadata. Correlation IDs propagate. Protected mutations fail closed with `503 ENFORCEMENT_DECISION_UNAVAILABLE` when Auth Service cannot decide; an authoritative denial is `403 BUSINESS_CAPABILITY_RESTRICTED`.

Authorized business members may read the safe view at `GET /api/v1/businesses/{businessId}/marketplace-capabilities`.

## Safety and lifecycle

Creates and revocations retain ADM-ENF-00 idempotency and optimistic locking. A repeated key with the same fingerprint returns the original result; reuse with different input conflicts. Creation locks/checks the business version. Revocation locks/checks the selected action version. Dry runs, failures, denials, runtime blocks, and rolled-back commands create no timeline event.

There is no protected/system-business type in the current domain. No name-based protection was invented.

Cross-target report intake and triage are implemented separately by
`ADM-REP-00/01/02` and never mutate business enforcement records. Investigation
cases, appeals, payout enforcement, automatic cascades, and AI recommendations
or decisions remain deferred. Reversible listing suspension is implemented
separately by `ADM-LIST-06` and never changes business enforcement records.
