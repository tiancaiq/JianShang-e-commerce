# ADM-RC-02 final production-style acceptance — 2026-09-01

## Verdict

**PASS WITH FOLLOW-UP**

The admin platform has no open observed release-blocking product defect. One
P1 catalog idempotency race was reproduced on MySQL 8.4, fixed without a
contract or schema change, and retained as a passing regression. The deployed
critical path, permission/privacy boundaries, concurrency, migration,
production-build, configuration, secret, and health gates are green.

The follow-up is release hygiene rather than missing product behavior: the
tested working tree also contains pre-existing, unrelated marketplace visual
work and the ADM-RC-02 changes are not yet committed. Cut the release from a
clean, reviewed commit. The Gateway also logs client-aborted Playwright reads
as `ERROR` (`AsyncRequestNotUsableException`); no domain mutation failed, but
that expected disconnect should be downgraded or filtered for cleaner
operational signal.

## Scope and non-goals

- Target: disposable local `demo-checkout` composition at
  `http://localhost:4200`, rebuilt from the current source with `.env.demo`.
- Required runtime: Frontend, Gateway, Auth, Product, Chat, Order, Payment,
  Inventory, Notification, Keycloak, MySQL, MongoDB, Postgres, and Cart Redis.
- Fixture mutation: only the explicitly guarded deterministic E2E reset and
  test-owned disposable records/actions. The final support privacy check
  creates one disposable support ticket and private note.
- No real provider, payment, refund, catalog, enforcement, or marketplace
  production record was touched.
- No API contract, architecture, service ownership, migration, or AI behavior
  was added or changed.

## Release-blocking finding fixed

### ARC-001 — concurrent catalog replay returned a stale pre-mutation result

Severity: **P1 before fix; closed**.

Two simultaneous, identical category-status commands used the same actor,
payload, expected version, and idempotency key. Under MySQL's default
`REPEATABLE_READ`, the losing transaction opened a snapshot while checking the
missing idempotency row, waited for the category lock, and could then return an
old `ACTIVE` category snapshot even though the winning command had committed
`DISABLED`.

The narrow fix runs the category-status transaction at `READ_COMMITTED` and
rechecks the actor-scoped idempotency row after acquiring the category lock.
Both callers now receive the committed result, while the database contains one
category version increment, one command record, and one audit event. A replay
after the command boundary creates nothing further.

## Deployed-stack acceptance

The final uninterrupted run passed **28/28** Playwright tests through the real
frontend, Gateway, identity session, and owner services. No frontend API route
was mocked in the added production-acceptance specification.

Coverage included:

- all 17 top-level admin modules: Dashboard, Analytics, Users, Businesses,
  Business Review, Listing Review, Orders, Disputes, Payments, Refunds,
  Support, Catalog, System, Governance, Reports, Cases, and Appeals;
- participant report and appeal flows, case-linked enforcement, partial
  execution/retry, and immutable final appeal outcomes;
- user, business, and listing enforcement composition and revocation;
- business approval and listing moderation ownership;
- a live stale catalog write returning controlled HTTP `409` with category
  state/version unchanged;
- live Support Admin and Auditor permissions, masked user identity, safe
  finance payload keys, and forbidden direct mutations returning `403`;
- a live requester ticket whose uniquely marked private admin note and
  assignment identity were absent from the requester response.

The final browser run reported no `5xx` admin API responses and no browser
console errors in the 17-module sweep.

## Database concurrency and idempotency

All tests below used MySQL 8.4 with real parallel threads/connections. No test
was skipped.

| Owner / risk boundary | Result | Invariant proved |
| --- | ---: | --- |
| Auth enforcement, reports, cases, support, appeals, governance | 18/18 | One claim/decision/reservation/execution winner; immutable terminal state; safe replay/rollback |
| Product enforcement, appeal execution, catalog commands, search work | 24/24 | One enforcement/result/audit link; one catalog mutation; deterministic lease recovery and deduplication |
| Payment intent/refund, upgrade, and outbox | 26/26 | Same-key refund executes once; admin-versus-return ceiling is bounded; one provider attempt; durable retry/replay |
| Order dispute and payment-confirmation recovery | 12/12 | One dispute assignee/final outcome; restart/retry does not duplicate an order |

Total focused database/recovery checks: **80/80 passed**.

High-risk outcomes verified explicitly:

- identical concurrent admin refunds: one command/refund/provider attempt and
  one replay;
- admin refund versus business-return refund: the shared ceiling is never
  exceeded;
- user/listing enforcement and appeal execution: one terminal mutation and
  one owner link;
- dispute claim and resolution: exactly one administrator and one immutable
  final outcome;
- governance request, approver decision, and execution claim: one durable row
  or winner per key/version;
- catalog status command: one category version, idempotency row, and audit
  event under a simultaneous same-key call;
- search projection/vector work: expired claims/leases resume without duplicate
  intent/application.

## Optimistic locking and restart/retry

- Live Catalog stale-version command: HTTP `409`; no mutation.
- Appeal conflict controller mapping and requester-safe Support service checks:
  **7/7 passed**.
- Payment outbox lease/retry/terminal handling: **6/6 passed**.
- Order payment-confirmation failure/restart/replay: **10/10 passed**.
- Product projection and vector lease restart recovery: **7/7 passed** within
  the Product concurrency run.

## Permission and privacy matrix

| Actor | Verified boundary |
| --- | --- |
| Super Admin / Platform Admin fixture | All admin reads; controlled mutations, confirmations, audit, and version checks |
| Business Reviewer | Business review visible; listing moderation read/command denied |
| Listing Moderator | Listing review visible; business decision read/command denied |
| Support Admin | Support/finance/refund reads; no refund execution or PII permission |
| User Restrictor | User enforcement only; unrelated admin mutations absent/denied |
| Auditor | Read-only controls; direct mutation `403`; no PII or refund execution |
| Participant requester | Own support/appeal/report data only; internal note and assignment identity absent |

Payment payloads were recursively checked for card number/PAN, CVV/CVC, client
or webhook secret, raw provider payload, bank-account, and routing-number keys;
none were present. User email remained masked without `admin.user.pii`.

## Migration verification

- Auth: clean schema through all **31** migrations.
- Product: clean schema through all **35** migrations.
- Payment: clean schema through **v10**, plus explicit expected previous state
  **v7 → v10** with three ordered migrations and no repair/out-of-order mode.
- Order: explicit expected previous state **v15 → v17**, followed by dispute
  concurrency; separate clean-schema restart tests applied all **17**
  migrations.
- No existing migration was edited and ADM-RC-02 adds no migration.

## Builds, runtime, configuration, and repository checks

- Backend production package: all **13/13** Maven reactor modules succeeded.
- Frontend production build: succeeded.
- Full Angular suite: **753/753 passed**.
- Architecture check and its self-test: passed.
- Compose configuration with `.env.demo`: `config --quiet` passed.
- High-confidence committed-secret scan: **0 files matched**.
- `git diff --check`: passed; only Windows line-ending conversion warnings.
- Environment-file status: `.env`, `.env.demo`, and `.env.example` unchanged.
- Required Compose services running: **14/14**.
- HTTP health/readiness: Frontend, Gateway, Auth, Product, Inventory, Order,
  Payment, Notification, Chat, and Keycloak all returned **200**.
- Optional Agent profile containers are stopped and outside this admin RC
  composition.

Docker Desktop initially could not start because of one stale runtime socket;
only that generated socket was removed. Recreating the demo identity containers
also exposed a persisted Postgres-role password mismatch. The disposable role
was aligned to its container-configured value without printing credentials or
editing an environment file, after which Keycloak started normally.

Post-recovery log review found no domain/service exception class. It did find
repeated Gateway `AsyncRequestNotUsableException` error entries, all corresponding
to Playwright closing/navigating clients while reads were still being written.
This did not produce a browser `5xx`, console error, failed mutation, or unhealthy
container, but remains an operational logging follow-up.

## Release signoff

- Open observed P0 defects: **0**
- Open observed P1 defects: **0**
- Open observed product P2 defects: **0**
- Closed during ADM-RC-02: **ARC-001 catalog concurrent replay**
- Final result: **PASS WITH FOLLOW-UP**

Before publishing the artifact, commit/review the ADM-RC-02 changes separately
from the pre-existing marketplace visual work and verify a clean release
worktree. No additional feature work is required for the admin release.
