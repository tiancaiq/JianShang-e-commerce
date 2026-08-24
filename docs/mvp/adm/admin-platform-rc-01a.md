# ADM-PLATFORM-RC-01A admin platform stabilization

## Status and boundary

`ADM-PLATFORM-RC-01A` is complete at the repository-verification boundary.
This pass audited the human-admin control plane and made only targeted
correctness, consistency, and query-efficiency changes. It did not add an
admin feature, activate the reserved AI role, change a marketplace state,
modify an environment file, or implement `ADM-PLATFORM-RC-01B`.

Final deployed-stack, cross-role, and live-fixture acceptance belongs to
`ADM-PLATFORM-RC-01B`.

## Audit result

| Area | Result |
| --- | --- |
| Routes and navigation | All implemented major admin areas have guarded Angular routes and gateway ownership. Navigation is grouped into Overview, Marketplace, Trust & Safety, Commerce, Customer Operations, Operations, and Governance. Empty groups are omitted. |
| Permissions and roles | The frontend and Auth Service expose the same 64 `admin.*` permission identifiers. An incomplete legacy admin response now fails closed instead of synthesizing `SUPER_ADMIN`. Backend authorization remains authoritative. The reserved `AI_ADMIN_AGENT` remains unassignable and has no permission activation. |
| Actor identity | Admin command actors are derived from the authenticated server-side principal. No client actor override was added. |
| Privacy | PII and private support notes remain permission-filtered server-side. Owner-service admin projections remain allow-listed, and financial views do not expose raw payment credentials. |
| Audit and dry runs | Case-enforcement dry runs no longer advance the case version or append a success event. The bounded proposal preview cache remains technical confirmation state and does not imply a marketplace or case transition. |
| Concurrency and idempotency | Existing optimistic versions, row locks, typed idempotency keys, request hashes, and stale-preview revalidation were retained. The complete Auth test suite covers the existing conflict and replay paths. |
| Cross-domain ownership | No cross-service database access or new shared domain model was introduced. Support remains coordination-only; specialized mutations stay with their owner services. |
| Performance | Governance admin pages load effective roles in one bounded batch. Support inbox order links load in one bounded batch, and support message author names load in the message query. Detail rendering no longer repeats link or administrator-name queries. |
| Migrations | 124 Flyway SQL files were inventoried with zero duplicate service/version pairs. Historical migrations were not edited and no RC migration was needed. Auth's 31 migrations apply successfully from an empty MySQL schema. |
| Search Maintenance | Bounded one-listing recovery remains part of System Operations. The legacy full-rebuild Search Maintenance surface remains explicitly feature-gated and deferred. |
| Dead code | No code was removed without proof of being unreachable. The obsolete dry-run timeline write and unused frontend all-permissions fallback import were removed as part of the fixes. |
| Error contracts | The audited admin paths retain structured validation, forbidden, not-found, conflict, stale-state, and upstream-unavailable handling. No stack trace or secret was added to a response. |

## Fixed findings

1. **Critical least-privilege fallback:** an incomplete legacy `/admin/me`
   response could be normalized in the browser as active `SUPER_ADMIN` with
   every permission. It now normalizes to no roles, no permissions, and a
   suspended state. The server was authoritative before and remains so.
2. **False dry-run audit:** a successful case-enforcement preview advanced the
   case version and appended `ENFORCEMENT_DRY_RUN_SUCCEEDED`. A preview now
   updates only the technical proposal confirmation cache and leaves the case
   timeline/version unchanged.
3. **Governance N+1:** the admin list queried effective roles once per row.
   Roles are now loaded for the bounded page in one query.
4. **Support N+1/repeated reads:** the inbox queried order links once per
   ticket, detail queried administrator names once per message, and detail
   loaded links twice. Those reads are now batched or joined and the already
   loaded detail links are reused.
5. **Navigation coherence:** the flat admin menu is grouped by operating area,
   permission-gated at group and item level, and covered for ordering and empty
   group behavior.

## Verification record

The following checks passed on 2026-08-24:

- `python tools/architecture_checks.py --self-test` — passed.
- `python tools/architecture_checks.py` — passed.
- frontend/backend permission comparison — 64/64 with no differences.
- migration inventory — 124 SQL files, zero duplicate service/version pairs.
- `mvn package -DskipTests` — all 13 reactor modules compiled and packaged.
- `mvn -pl auth-service -am test` — 228 tests passed; zero failures, errors,
  or skips.
- focused case/support/Auth tests — 108 passed.
- Support persistence regression tests — 3 passed against disposable MySQL.
- `npm test -- --watch=false` — 751 Angular tests passed.
- `npm run build` — production Angular build passed.
- isolated `admin-governance.spec.ts` Playwright run with destructive global
  setup disabled — 8 passed.
- `git diff --check` — passed after the final documentation update.

The default Playwright global setup intentionally refused to run without an
explicit disposable database target. No destructive reset flag was bypassed;
the isolated mocked governance suite was run with global setup skipped.

## Remaining technical debt and explicit deferrals

- `ADM-PLATFORM-RC-01B` must perform the deployed-stack, cross-role browser
  walkthrough and live service-log/health acceptance.
- Legacy full Search Maintenance stays feature-gated until its separate
  rollout contract is approved.
- Broad pagination changes to established detail timelines are deferred unless
  production sizing evidence shows the existing bounded domain views are
  insufficient; this RC did not redesign response contracts speculatively.
- Flyway reports that the repository's current Flyway version has not been
  certified beyond MySQL 8.1 while tests use MySQL 8.4. Migrations pass, but a
  dependency compatibility upgrade should be handled as a separate tested
  maintenance change.
- Existing Mockito dynamic-agent and deprecated `@MockBean` compiler warnings
  remain dependency-maintenance work and are not RC correctness defects.

Unrelated working-tree changes, demo configuration, media work, and all
environment files were preserved.
