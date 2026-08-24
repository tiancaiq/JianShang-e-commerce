# ADM-ANL-01 Admin Analytics and Operational Insights

## Status and release boundary

The read-only analytics surface, owner-service aggregate APIs, and
`ADM-APL-03`-backed final appeal metrics are implemented. Final milestone
acceptance remains pending the complete live and repository verification
matrix. Recommendation and pending states remain separate operational facts;
they are never relabeled as final outcomes or included in the appeal-adjustment
rate.

This slice adds no mutation API, source-of-truth copy, warehouse, Redis cache,
Kafka flow, AI, anomaly detection, user/seller score, or individual-risk
leaderboard.

## Ownership and architecture

Auth Service owns the public admin facade because it owns effective admin
authorization and the existing ADM-SYS aggregator. It reads only Auth-owned
identity, business-application, reporting, case, user/business-enforcement,
appeal, support, and governance tables. Token-protected, bounded internal reads
provide the other domains:

```text
Product Service -> listing, category, moderation, listing enforcement
Order Service   -> order, fulfillment, dispute
Payment Service -> payment and refund
ADM-SYS client  -> health and operational signals
```

Each owner performs database-side counts, sums, duration calculations, and
bounded grouping in its own schema. Auth calls owners concurrently with the
existing one-second connection/two-second read convention and a bounded facade
deadline. A failed owner marks only the dependent section `DEGRADED` or
`UNAVAILABLE`; it never turns missing data into zero. Auth-owned Marketplace,
business moderation, Trust & Safety, Support, and Governance reads are also
isolated by section, so a local Support query failure does not erase unrelated
dashboard data.

Final appeal capability is now supported. A healthy appeal source with no final
rows returns zero finalized appeals and an `N/A` adjustment rate; it does not
degrade Trust & Safety merely because pending or recommendation-only rows
exist. A real Auth appeal/query failure still marks that section unavailable
or degraded according to the remaining Product-owned Trust data, without
fabricating appeal metrics.

## Authorization and privacy

`admin.analytics.read` gates both `/admin/analytics` and the public analytics
API. It is granted to `SUPER_ADMIN`, legacy `PLATFORM_ADMIN`,
`OPERATIONS_ADMIN`, and `AUDITOR`.

- Counts and timing aggregates require analytics read.
- Currency amount breakdowns are requested from owners and returned only when
  the actor also has `admin.finance.read`.
- System detail requires `admin.system.read`; otherwise the operations section
  is `RESTRICTED`.
- Governance signals require `admin.governance.read`; otherwise that section is
  `RESTRICTED`.
- Analytics contains no reporter identities, support notes, case notes, raw
  payloads, payment-method data, credentials, tokens, or PII lists. Destination
  queues retain their own permissions.

## Public API and ranges

```text
GET /api/v1/admin/analytics/overview
GET /api/v1/admin/analytics/trends?metric=...
```

Overview accepts `range=TODAY|LAST_7_DAYS|LAST_30_DAYS|LAST_90_DAYS|CUSTOM`,
optional RFC 3339 `from`/`to` for `CUSTOM`, `timezone=UTC`, and `compare`.
Intervals are half-open `[from,to)`, must have `from < to`, cannot end in the
future, and cannot exceed 90 days. Presets are rolling UTC windows except
`TODAY`, which begins at 00:00 UTC. The comparison window is the immediately
preceding equal duration `[from-(to-from),from)`.

Comparison values contain current, previous, absolute change, percentage
change, and a typed state. Previous zero plus current positive is `NEW`; both
zero or a metric without a historical snapshot is `NOT_APPLICABLE`. No
infinite percentage is returned. Current queue backlog is explicitly as-of
`generatedAt` and is not falsely reconstructed for the prior period.

Trend keys are allow-listed: reports submitted, orders created, disputes
opened, refunds succeeded, support tickets created, and listings created.
`HOUR` is limited to 48 hours; `DAY` and `WEEK` remain under the 90-day and
100-point bounds. No arbitrary field, SQL, formula, or grouping input exists.

## Metric definitions

All period flows use authoritative timestamps and `[from,to)`:

| Metric | Definition |
| --- | --- |
| New users | Human accounts by `users.created_at`; no invented active-user metric |
| New businesses | Approved business rows by `businesses.created_at` |
| New listings | Product listings by `listings.created_at` |
| Business review time | `submitted_at` to `decided_at`; submission-to-decision, not handling time |
| Listing moderation time | Moderation-case `created_at` to `resolved_at` |
| Reports submitted | Report creation; a report is not a confirmed violation |
| Reports dismissed/ready | Normalized report-event occurrence time |
| Unresolved reports | Current `SUBMITTED`, `UNDER_TRIAGE`, or `READY_FOR_INVESTIGATION`; `LINKED_TO_CASE` is resolved/read-only in triage and is excluded |
| Cases opened/closed | Case `created_at` or authoritative `closed_at` plus exact final state |
| Enforcement created | Owner enforcement-row creation; active uses effective/revoked/expiry boundaries at generation time |
| Enforcement owner groups | Auth reports user/business enforcement separately from Product listing enforcement so an unavailable owner is never converted to zero |
| Enforcement breakdowns | Created-in-range and current-active actions are grouped by owner target/action and target/scope; scope rows count applied action scopes, not distinct targets |
| Appeals submitted | Appeal `submitted_at`, regardless of later lifecycle |
| Appeals finalized | Final `UPHELD`, `MODIFIED`, or `REVOKED` rows by `resolved_at`; recommendation and pending states are excluded |
| Appeal final outcomes | Separate final `UPHELD`, `MODIFIED`, and `REVOKED` counts by `resolved_at` |
| Appeal adjustment rate | `(MODIFIED + REVOKED) / (UPHELD + MODIFIED + REVOKED) * 100`; `N/A` when no appeal was finalized |
| Queue backlog | Current open, unassigned, and oldest-open age as of `generatedAt`; multi-state queue totals have no drill-down unless the destination supports the same state set |
| Orders created | Parent order `created_at` |
| Fully delivered orders | Parent orders whose non-cancelled business groups are all delivered; there is no native order `COMPLETED` state |
| Cancellation rate | Orders created in the selected cohort that are currently `CANCELLED` divided by orders created in that cohort |
| Gross order amount created | Currency-grouped total of orders created in the cohort; finance permission required |
| Disputes opened/resolved | Dispute `created_at`/`resolved_at`, preserving every terminal outcome |
| Dispute rate | Not emitted: one order may contain several business groups/disputes and no approved eligible-order denominator exists; raw dispute count is never divided by parent orders |
| Refund recommendations | Dispute outcomes only; never counted as a Payment refund |
| Payments succeeded/failed | Terminal Payment transitions; success rate is `SUCCEEDED/(SUCCEEDED+FAILED)` |
| Payment success rate without outcomes | `N/A`, because a zero terminal-outcome denominator has no defined success rate |
| Refund requested/outcomes | Payment-owned request and terminal lifecycle, split by full/partial request and terminal result; return refunds contribute only to succeeded outcomes |
| Refund failure rate | `FAILED/(SUCCEEDED+FAILED)` terminal refund outcomes |
| Support created/resolved | Ticket `created_at`/`resolved_at` |
| First response | Earliest actual `SUPPORT_ADMIN` message minus ticket creation |
| Support resolution time | `resolved_at-created_at`; no SLA breach claim exists |
| Catalog state | Current Product listing state; admin-removed is distinct from active listing enforcement |
| Listing rejection rate | Rejected moderation decisions divided by resolved decisions in the selected period |
| Governance signals | Current non-expired pending approvals, time-derived expiry, failed executions, and active temporary assignments |

Top-category lists are capped at ten. Current and new listing categories are
Product-owned. Historical reported categories may use immutable report-time
snapshots. Disputed-category and exact historical moderation-category rankings
remain deferred because purchase/decision rows do not store an immutable
category snapshot; no N+1 current-listing approximation is used.

## Operations and drill-downs

Operations and the ADM-SYS dashboard use the same pure summary assembler for
health, failed/retryable job, redacted outbox, reconciliation, inventory, and
search-index definitions. Analytics never calls a recovery endpoint.
Drill-downs are typed keys resolved by an Angular
allow-list to existing admin routes and known query parameters; the server does
not supply arbitrary URLs. Aggregate cards without an exact destination filter
do not claim a misleading drill-down. Exact aggregate filters include unresolved
unassigned reports (`assignment=UNASSIGNED&unresolved=true`), failed jobs
(`status=FAILURE`, meaning `FAILED|DEAD_LETTER|TERMINAL`), failed outbox events
(`status=FAILURE`, meaning `FAILED|DEAD_LETTER`), and dead-letter outbox events
(`status=DEAD_LETTER`).

## Frontend and accessibility

`/admin/analytics` provides UTC preset/custom range controls, comparison,
manual refresh, generated time, section-level availability, summary cards,
bounded category/breakdown tables, and lightweight SVG trends. Every chart has
an accessible numeric table/caption; controls are keyboard operable and charts
do not rely on color. Change direction is neutral because an increase in
reports, refunds, or orders does not share one universal meaning.

## Storage and performance

No analytics table is created. Forward migrations add only access-path indexes
for bounded time/status/event queries in Auth, Product, Order, and Payment.
Overview makes one aggregate request per owner (plus a Product comparison read
because its typed summary separates current snapshot from one selected flow
window). Direct aggregate queries are appropriate at current scale and the API
can later be backed by materialized projections without a frontend contract
change.

Deferred: an approved dispute-rate denominator, historical disputed categories,
immutable category-at-moderation-decision
analytics, arbitrary timezones, ranges beyond 90 days, an analytics warehouse,
configurable formulas/reports, external BI, predictive models, and all AI.
