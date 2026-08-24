# ADM-SUP-00/01 — Support Operations

Status: implemented; final acceptance awaits the repository-wide Playwright
orchestration gate. The focused Support workflows are green.

## Purpose and ownership

Support is the coordination and customer-help layer for marketplace users. The aggregate is an Auth Service module because authenticated requester identity, business membership, admin permissions, and the existing Auth-owned reports/cases/appeals administration already live there. It is not a new microservice and it never reads another service's schema.

Support is intentionally separate from Orders, Disputes, Trust & Safety, Payments/Refunds, and Enforcement. It validates and links their records through token-protected, allow-listed read APIs. A support response, escalation, or resolution cannot cancel an order, create or execute a refund, decide a dispute, or apply enforcement.

## Aggregate and lifecycle

`support_tickets` stores requester, broad category, subject/description, status, priority, assignment, resolution, correlation, timestamps, and optimistic version. Categories are `ACCOUNT_HELP`, `ORDER_HELP`, `PAYMENT_HELP`, `REFUND_HELP`, `SELLER_HELP`, `BUSINESS_VERIFICATION`, `LISTING_HELP`, `TECHNICAL_ISSUE`, and `OTHER`.

The lifecycle is deliberately small:

```text
OPEN -> ASSIGNED -> UNDER_REVIEW -> RESOLVED
                         |
                         v
                 WAITING_FOR_USER
                         |
                         +-> UNDER_REVIEW (requester reply)
```

`RESOLVED` is final; a separate `CLOSED` archive state is not used. Priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`) changes queue ordering only.

## Requester workflow

Authenticated users use `/support`, `/support/new`, and `/support/:ticketId`. Auth derives the requester; the client cannot supply it. An optional order is accepted only when Order Service confirms the requester is the buyer. A business requires active Auth-owned membership. A listing must be public/reportable or owned by that individual seller. A deterministic ten-minute fingerprint cooldown rejects an obvious repeated submission without blocking later legitimate requests.

Participant messages are append-only plain text. A requester sees only their own ticket, safe linked labels, participant messages, a safe resolution summary, and a reduced history. Assignment, admin identity, internal notes, priority operations, escalation metadata, correlation IDs, and unrestricted PII are absent.

## Admin console

`/admin/support` is a server-paginated/filterable inbox; `/admin/support/:ticketId` is the operational detail. Admins can claim/release, reply, request information, add visually separate private notes, reprioritize, validate links, create explicit handoffs, and resolve when backend capabilities allow it.

The admin dashboard shows a permission-gated Support inbox card with the current
unassigned-ticket count. It derives the count from the existing filtered inbox
contract, links directly to `/admin/support`, and remains navigable when the
count request is temporarily unavailable.

Permissions are `admin.support.read`, `admin.support.assign`, `admin.support.respond`, `admin.support.resolve`, and `admin.support.escalate`. Super Admin, Platform Admin, and Support Admin receive all five. Auditor receives read only. Related order/dispute/finance mutation permissions remain independent. Requester email is returned only with `admin.user.pii.read`.

Claim and every aggregate mutation use `expectedVersion`; stale commands return `409 SUPPORT_TICKET_VERSION_CONFLICT`. A ticket assigned to another admin is read-only and cannot be silently taken over. Final tickets reject normal mutations. Creation, participant/admin messages, information requests, notes, resolution, and escalation use request-hash idempotency where duplicate user-visible work is possible.

## Messages, notes, links, and escalation

`support_messages` contains only requester-visible communication. `support_internal_notes` is an independent append-only admin-only table and is never queried for requester projections. `support_ticket_links` stores normalized typed references without cross-service foreign keys. Admin links are validated by the owning service; unavailable or arbitrary IDs fail closed.

Escalation is an auditable link to one existing destination:

- Order operations -> existing Order ID and `/admin/orders/:id`
- Dispute -> existing Dispute ID and `/admin/disputes/:id`
- Trust & Safety -> existing report ID and `/admin/reports/:id`
- Finance -> existing payment ID and `/admin/payments/:id`

Refunds and investigation cases can also be independently validated and linked. Creation of a new dispute/report/case must occur in its owning workflow so its eligibility rules remain authoritative. Escalation leaves the support ticket open until an admin intentionally resolves it, commonly with `ESCALATED_TO_SPECIALIZED_WORKFLOW`.

## Audit, persistence, and deferred scope

`support_ticket_events` is append-only and records creation, assignment, participant messages, information requests, priority, private notes, entity linking, escalation, and resolution with server-derived actor/correlation data. Failed validation, stale conflicts, and page views create no events.

Migration `V202608210100__create_support_operations.sql` creates the ticket, message, note, link, escalation, event, and command-idempotency tables plus support permissions and role mappings. No Order, Payment, Product, dispute, or enforcement table is copied or changed.

AI classification/response, chatbots, external email/help-desk integrations, automatic escalation, chargebacks, payouts, and direct specialized-domain mutations remain deferred.
