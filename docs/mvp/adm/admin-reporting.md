# Marketplace Reporting and Admin Triage

Status: `ADM-REP-00/01/02/03/04` implemented through 2026-08-16.

## Boundary and ownership

Auth Service owns the cross-domain report aggregate because it already owns marketplace identities, business membership, admin permissions, user/business enforcement context, and Trust & Safety coordination. Reports reference `USER`, `BUSINESS`, and `LISTING` targets without cross-service foreign keys. Product Service remains authoritative for listings and exposes a token-authenticated, allow-listed internal listing context read. The older Product-local `REP-01A` listing intake remains disabled, unrouted, and quarantined; its historical migrations are not rewritten.

A report is an allegation. Submission, assignment, severity changes, dismissal, and readiness for investigation never call an enforcement mutation or alter a target. Only the explicit ADM-REP-03 promotion command creates a separate investigation case.

## Domain

Operational target types are `USER`, `BUSINESS`, and `LISTING`. `ORDER`, `MESSAGE`, and `REVIEW` are reserved in storage but rejected by the public API.

| Target | Reasons |
| --- | --- |
| `LISTING` | `SCAM`, `COUNTERFEIT`, `PROHIBITED_ITEM`, `MISLEADING_LISTING`, `SPAM`, `OTHER` |
| `USER` | `SCAM`, `HARASSMENT`, `SPAM`, `IMPERSONATION`, `OTHER` |
| `BUSINESS` | `SCAM`, `COUNTERFEIT`, `HARASSMENT`, `SPAM`, `IMPERSONATION`, `OTHER` |

The operational lifecycle is `SUBMITTED -> UNDER_TRIAGE -> DISMISSED | READY_FOR_INVESTIGATION -> LINKED_TO_CASE`. `ACTIONED` and `WITHDRAWN` remain reserved. Claims move `SUBMITTED` to `UNDER_TRIAGE`; releases return it to `SUBMITTED`. An explicit atomic case link moves ready to linked; active-case unlink restores ready. Resolved/linked reports are read-only in triage and there is no reopen command.

Initial severity is deterministic: `SPAM=LOW`, `PROHIBITED_ITEM|HARASSMENT=HIGH`, and all other reasons `MEDIUM`. Authorized assigned admins may set `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`; severity never triggers enforcement.

## Snapshots and privacy

The backend constructs an immutable allow-listed JSON snapshot. Clients cannot provide the reporter, snapshot, severity, assignment, status, or actor identity. Listing snapshots contain listing text, price/currency, category/SKU, seller ownership references, lifecycle state, public image references, capture time, and version. Business snapshots contain a safe display name, state, safe owner reference, capture time, and version. User snapshots contain only safe display name, account state, capture time, and version.

Admin detail resolves the current target independently. The UI presents the immutable report-time snapshot beside the current state. A deleted or unavailable target does not delete or hide the report; current state becomes `UNAVAILABLE` or `TEMPORARILY_UNAVAILABLE`. Reporter-facing responses contain only the report ID, public status, created time, and support reference. Queue rows omit email and private account data. Enforcement context exposes only active action type and scopes, never internal reasons.

## Submission and abuse controls

`POST /api/v1/reports` requires authentication and accepts only `targetType`, `targetId`, `reasonCode`, and `description`. `OTHER` requires a description; all text is whitespace-normalized, control-character rejected, plain text, and bounded to 2,000 characters. The backend rejects a user reporting themselves, an active member reporting their business, or a seller/member reporting their own listing.

The duplicate key is reporter + target type + target ID + reason for a 24-hour cooldown. A durable unique slot makes concurrent duplicate attempts deterministic. Transactional rate buckets allow five accepted submissions per UTC hour and twenty per UTC day. Failed or rolled-back submissions do not consume a slot.

## Admin workflow

`admin.report.read` gates inbox/detail, `admin.report.assign` gates claim/release, `admin.report.investigate` gates investigation mutations, and `admin.report.resolve` gates triage and investigation conclusions. Trust & Safety and super admins have all four; Support and Auditor remain read-only; Listing Moderator receives no report authority by implication. `AI_ADMIN_AGENT` remains unassigned and has no report permissions or execution path.

Inbox queries are server-paginated and support exact report/target/reporter ID search plus status, target, reason, severity, assignment, creation-time, and stable-sort filters. Related reports are bounded to the twenty newest reports for the same target, with an indexed count.

All mutations require the expected report version. Only one concurrent claim wins. Normal admins may release or triage only a report assigned to themselves; an assignment held by another admin is read-only. Stale requests return `REPORT_VERSION_CONFLICT` and the frontend reloads instead of retrying silently.

Append-only events also include `REPORT_LINKED_TO_CASE` and `REPORT_UNLINKED_FROM_CASE`. Denied, invalid, stale, rolled-back, and read operations create no event.

## Frontend and deferred work

Authenticated marketplace users can open a neutral report dialog from public listing and store detail. There is no new public user-profile system, so `USER` reporting is API-supported while its public UI entry point is deferred. Admin routes are `/admin/reports` and `/admin/reports/:reportId`. The detail uses an explicit reported-snapshot/current-state evidence seam, related reports, read-only enforcement context, timeline, and backend capability flags.

`ADM-REP-03` investigation cases are documented in [admin-investigation-cases.md](admin-investigation-cases.md); the explicit `ADM-REP-04` handoff is in [admin-case-enforcement.md](admin-case-enforcement.md). Enforcement appeals are now a separate second-review ledger documented in [admin-appeals.md](admin-appeals.md); they do not rewrite reports or reporter privacy. Chat/message/review/order reporting, evidence uploads, notifications, semantic clustering, AI triage/recommendations, and automated decisions remain deferred.
