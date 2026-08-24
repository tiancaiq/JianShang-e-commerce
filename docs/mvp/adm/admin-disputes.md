# ADM-DSP-00/01/02 — Transaction Disputes

Status: implemented; release verification is recorded in the completion report.

## Purpose and ownership

Order Service owns disputes because every dispute is scoped to one existing
business-commerce `orderId + businessOrderId`. A multi-business buyer order can
therefore have a dispute against one seller group without affecting siblings.
Individual marketplace trades remain out of scope.

Disputes are distinct from Order status, post-delivery returns, Trust & Safety
reports/cases, enforcement, and Payment refunds. Payment, Inventory, Product,
and Auth remain authoritative. A dispute resolution never edits those domains.

## Eligibility and participation

The backend derives the buyer or active business member. The group must belong
to that participant, the order must be confirmed and paid, cancellation must be
absent, and fulfillment must be `ACCEPTED`, `PROCESSING`, `SHIPPED`, or
`DELIVERED`. Delivery-condition reasons require delivery; non-receipt requires
shipment; seller-failure reasons may be raised before delivery. The window ends
30 days after recorded delivery, or 60 days after order creation when delivery
has not occurred. A generated active-scope key allows only one non-final dispute
per business-order group. A later distinct dispute may be opened after a final
decision; the original remains immutable and cannot reopen.

Buyers may use all supported transaction reasons. Seller initiation is limited
to `RETURN_DISAGREEMENT` and `OTHER`; active members may respond to a buyer-opened
dispute. Statements are append-only. Evidence is limited to validated existing
order-item, listing-image, or shipment references—no external URLs, filesystem
paths, HTML, or executable uploads are accepted.

## Lifecycle and administration

```text
OPEN -> UNDER_ADMIN_REVIEW
  -> WAITING_FOR_BUYER | WAITING_FOR_SELLER -> UNDER_ADMIN_REVIEW
  -> READY_FOR_DECISION
  -> RESOLVED_NO_ACTION | RETURN_APPROVED
   | REFUND_RECOMMENDED | PARTIAL_REFUND_RECOMMENDED
```

Final states are read-only. `LOW` through `CRITICAL` priority affects queue
ordering only. Claim/release uses optimistic versions; investigation and
resolution require both permission and assignment to the current admin. There
is no silent takeover. Participant-visible information requests are stored as
statements; append-only admin notes are returned only from admin APIs.

Permissions are `admin.dispute.read`, `admin.dispute.assign`,
`admin.dispute.investigate`, and `admin.dispute.resolve`. Auditors are read-only.
The admin queue defaults to all assignments so assigned work is not hidden;
administrators can explicitly narrow it to unassigned, assigned-to-me, or all
assigned disputes.

## Frontend routes and local verification

The supported frontend participant routes are:

- buyer creation: `/account/orders/:orderId/disputes/new?businessGroupId=:businessGroupId`;
- buyer detail: `/account/disputes/:disputeId`;
- business seller detail: `/seller/businesses/:businessId/disputes/:disputeId`;
- admin queue and detail: `/admin/disputes` and `/admin/disputes/:disputeId`.

The seller route includes the `/seller` portal prefix. The shorthand
`/businesses/:businessId/disputes/:disputeId` is not a supported frontend URL;
the similarly shaped `/api/v1/businesses/...` path is the backend contract.
Unknown and unauthorized participant detail requests share the frontend state
`Dispute unavailable or not found.` so the UI remains useful without exposing
whether a dispute exists outside the participant's scope.

Buyer dispute creation depends on V2 buyer order history and therefore must be
verified with the approved commerce composition, not the marketplace-only base
demo. Start it with `tools/start-commerce-demo.ps1`, or rebuild an already
healthy commerce frontend with both Compose files:

```powershell
docker compose -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --no-deps --build frontend
```

The overlay selects the Angular `demo-checkout` build and enables the matching
gateway and Order capabilities. Base `docker-compose.demo.yml` intentionally
keeps `buyerCheckout=false`; its redirects from `/account/orders` are expected
and must not be treated as dispute authorization failures.

## Remedies and financial separation

`RETURN_APPROVED` stores instructions and a deadline only. It does not generate
a carrier label, mark goods in transit, receive inventory, or issue a refund.

Full and partial refund outcomes are recommendations. Before recording one,
Order Service re-reads the current Payment intent and calculates the remaining
refundable group amount from authoritative order totals and completed refund
projections. A full recommendation equals that amount; a partial recommendation
must be greater than zero and strictly lower. Currency must match. Resolution
does not call a refund adapter, modify Payment, reserve funds, alter captured
amount, or affect payouts. `ADM-FIN-00/01/02` must revalidate financial state
again before any future execution.

## Trust & Safety and privacy

Admin detail provides read-only navigation to related report/case, buyer,
business, listing, and order surfaces. It does not automatically create a
report, case, user/business/listing enforcement, or appeal. Participant APIs
omit assigned-admin identity, internal notes, internal correlation IDs, private
evidence, and unrelated PII.

Admin detail labels immutable purchase-time item facts separately from current
Product listing summaries and includes read-only Payment/refund, Order shipment,
fulfillment, cancellation, and Inventory reservation/release projections. An
owner outage leaves the corresponding live context unavailable; it never causes
Order Service to invent or persist source-of-truth state.

## Concurrency and retries

Lifecycle commands use dispute versions and locked predicates. Concurrent
claim or resolution has one winner; stale commands return
`DISPUTE_VERSION_CONFLICT`. Creation, statements, information requests, notes,
and resolution use actor-scoped idempotency records. Same-key replay returns the
stored aggregate, changed content conflicts, and final decisions remain
immutable.

## Deferred

Secure new-media upload, active Trust & Safety escalation, reopening,
chargebacks, return-carrier integration, payout holds, and all refund execution
remain deferred. `ADM-FIN-00/01/02` is now implemented: finance operators can execute a linked recommendation only after matching the same dispute amount/currency and revalidating the current Payment-owned refundable boundary. The dispute decision remains unchanged. See [admin-financial-operations.md](admin-financial-operations.md).

`ADM-SUP-00/01` may validate and link an existing dispute as an explicit
handoff, but Support cannot bypass participant eligibility, create dispute
rows directly, decide a dispute, or execute a refund. See
[admin-support-operations.md](admin-support-operations.md).
