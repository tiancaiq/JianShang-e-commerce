# ADM-SYS-01/02 marketplace and system operations

## Purpose and architecture

The Operations console is an administrative control plane over real runtime
signals. It is not an infrastructure shell or a database editor. Auth Service
owns the public admin facade, authorization, command idempotency, and normalized
request audit. Product, Order, Payment, and Inventory retain their domain data
and expose token-protected, payload-redacted operational snapshots. Gateway
routes `/api/v1/admin/system/**` to Auth.

The implemented flow is:

```text
owner-service state -> redacted snapshot -> Auth aggregation -> dry run
-> audited owner-specific command -> existing worker queue -> later worker result
```

An accepted command means the owning worker mechanism accepted recovery work;
it never means the underlying job, outbox delivery, or search projection
succeeded.

## Operational sources

| Area | Source and owner | Recovery behavior |
| --- | --- | --- |
| Service health | bounded Actuator aggregate health for Gateway, Auth, Product, Order, Payment, Inventory, Notification, and Chat | read-only; one unavailable service does not fail the aggregate |
| Durable work | Product listing-search/vector work; Order purchased-cart reconciliation | failed, unclaimed, nonterminal work can be rescheduled through its existing worker |
| Outbox | Product, Order, Payment, and Inventory outbox tables | Product/Order/Payment expose worker-supported failed delivery retries; Inventory is read-only |
| Finance reconciliation | Payment refund `REQUIRES_ATTENTION` projection | read-only because the current provider has no safe status lookup/recheck contract |
| Inventory | expired reservations still `ACTIVE` | read-only; existing batch expiry owns cleanup and there is no target-safe command |
| Search | Product listing projection queue and last applied timestamp | retry one failed work item or reindex one listing by rereading authoritative Product state |
| Features | Product/Order/Payment/Inventory environment/configuration-backed state | read-only; no second runtime flag store exists |

HTTP health and domain correctness remain separate. A healthy Payment endpoint
can coexist with refund reconciliation issues.

## Container configuration

When Auth runs in Docker, every aggregated service-health URL must use the
Compose service name rather than `localhost`. The demo and commerce overlays
provide `GATEWAY_SERVICE_URL`, `CHAT_SERVICE_URL`, `PRODUCT_SERVICE_URL`,
`ORDER_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `INVENTORY_SERVICE_URL`, and
`NOTIFICATION_SERVICE_URL`. A missing value can make a healthy container appear
`UNAVAILABLE` because `localhost` inside Auth refers to Auth itself.

`LISTING_SEARCH_PROJECTION_SYNC_ENABLED` remains disabled in the default demo
profile. Enable it only in a disposable test environment when validating the
accepted one-listing reindex path; a disabled reindex request is expected to
return `SEARCH_REINDEX_NOT_ALLOWED`.

## Routes and permissions

Angular provides `/admin/system`, `/health`, `/jobs`, `/jobs/:jobId`,
`/outbox`, `/outbox/:eventId`, `/reconciliation`, `/inventory`, `/search`, and
`/features`. List reads are bounded and server-filtered/paginated at the Auth
facade; owner snapshots are themselves bounded.

- `admin.system.read`: summary, health, jobs, outbox, reconciliation, inventory,
  search, and recovery audit reads.
- `admin.system.retry`: supported job/outbox retry previews and commands.
- `admin.search.maintenance`: one-listing reindex preview and command.
- `admin.feature.read`: curated feature/configuration state.

`admin.system.reconcile` and `admin.feature.manage` remain inactive because no
safe owning-service command exists. `SUPER_ADMIN`, `PLATFORM_ADMIN`, and the
new `OPERATIONS_ADMIN` receive all implemented permissions. `AUDITOR` receives
read and feature-read only. Support, catalog, listing-moderation, business-
review, and Trust & Safety roles do not receive operations authority.

## Command safety, idempotency, and audit

All mutations require a reason and a dry-run review in the UI. The final call
requires an `Idempotency-Key`; Auth stores an actor/command/key uniqueness
boundary plus a request hash. Exact replays return the original result, changed
content returns `409`, and an in-progress duplicate returns `409`. Attempts are
never reset. Owner commands only advance existing retry scheduling or append a
new current-state listing projection; they never edit marketplace domain status.

`system_operation_commands` stores command acceptance state. Append-only
`system_operation_events` records actor, target, reason, outcome, correlation,
request reference, and allow-listed metadata. Events such as
`JOB_RETRY_REQUESTED`, `OUTBOX_RETRY_REQUESTED`, and
`SEARCH_REINDEX_REQUESTED` describe the admin request, not worker completion.

Health responses omit component details, network locations, and configuration.
Operational DTOs omit raw outbox payloads, exception text, credentials, tokens,
addresses, and provider secrets. No generic SQL, URL, HTTP, shell, status-edit,
attempt-edit, delete, restart, or mark-sent endpoint exists.

## Search Maintenance reconciliation

The earlier Search Maintenance implementation consists of the
`admin-search-maintenance` Angular capability/routes/components and Product
search rebuild/vector coordination APIs. It is controlled by the
`adminSearchMaintenance` build/environment feature and remains disabled by
default. Full rebuild, embedding backfill, vector promotion, and recovery are
experimental/high-risk and were retained without being enabled or duplicated.

ADM-SYS integrates only production-bounded listing projection visibility,
retry, and one-listing current-state reindex. The legacy code was not removed
because it still contains non-overlapping experimental workflows. Therefore:

```text
Search Maintenance remains feature-gated/deferred.
Bounded listing search recovery is integrated into ADM-SYS.
```

## Deferred operations

Provider reconciliation commands, target-safe inventory cleanup, Inventory
outbox replay, full-marketplace reindex, runtime feature mutation, infrastructure
restart/shell access, cloud-console behavior, automatic recovery, and AI
diagnosis remain deferred. ADM-GOV supplies the approval foundation, but no
production-ready high-impact system command exists to attach to it. The
existing bounded retry and one-listing reindex commands therefore retain their
current permission/dry-run/idempotency controls. No fake system action was
introduced. See [admin-governance.md](admin-governance.md).

ADM-ANL-01 reuses the same health and source-snapshot definitions for a bounded
read-only operations summary. Analytics never calls a retry, replay, reindex,
or other maintenance endpoint, and an unavailable ADM-SYS source degrades only
the analytics operations section. See [admin-analytics.md](admin-analytics.md).
