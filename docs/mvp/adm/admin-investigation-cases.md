# Admin Investigation Cases

Status: `ADM-REP-03/04` implemented on 2026-08-16.

## Purpose and ownership

An allegation (`REPORT`), a human investigation (`INVESTIGATION CASE`), an administrative action (`ENFORCEMENT`), and a challenge to an action (`APPEAL`) are separate aggregates. Auth Service owns reports and investigation cases. It stores only `targetType + targetId` for USER, BUSINESS, and LISTING references; Product Service remains authoritative for listing state and is read through the existing token-authenticated internal context endpoint. There are no cross-service foreign keys and no new service.

`READY_FOR_ACTION` still has no automatic effect. `ADM-REP-04` adds an explicit proposal, target-specific dry run, and human-confirmed handoff to the existing enforcement engines; see [admin-case-enforcement.md](admin-case-enforcement.md).

## Lifecycle and severity

The operational lifecycle is:

```text
OPEN -> UNDER_INVESTIGATION -> READY_FOR_ACTION
                              -> CLOSED_ACTIONED
                              -> CLOSED_NO_ACTION
```

`READY_FOR_ACTION` permits only the versioned enforcement-plan workflow. `CLOSED_ACTIONED` and `CLOSED_NO_ACTION` are read-only, and there is no reopen command.

Severity is `LOW|MEDIUM|HIGH|CRITICAL` and is prioritization only. Creating from a report defaults deterministically to that primary report's severity unless an authorized admin selects a value. Changing severity requires the current case version, the assigned investigator, and a reason.

## Reports and targets

Creating a case from `READY_FOR_INVESTIGATION` atomically creates the case, derives its primary target from the report, inserts the primary target relationship, links the report, moves it to `LINKED_TO_CASE`, and writes case/report events. A report has at most one current case link. Linking an additional report requires both current case and report versions; it must still be `READY_FOR_INVESTIGATION`. Unlinking is limited to active investigations, restores `READY_FOR_INVESTIGATION`, and records the reason on both timelines. Closing no-action deliberately leaves linked reports as `LINKED_TO_CASE`; the case conclusion is the authoritative non-destructive outcome.

Every case has exactly one non-removable primary target. Assigned investigators may add validated USER, BUSINESS, or LISTING related targets. Existence is checked through the owning Auth/Product read seam. Related targets can be removed with a reason while the investigation is active; target history remains in case events.

## Assignment, permissions, and concurrency

- `admin.report.read`: inbox/detail, linked reports, internal notes/evidence, and timeline.
- `admin.report.assign`: claim and release.
- `admin.report.investigate`: explicit start, report/target links, notes, evidence, and severity.
- `admin.report.resolve`: ready-for-action, close-no-action, proposal planning, and actioned closure. Target-specific dry-run/execution also requires the matching enforcement permission.

Trust & Safety, Super Admin, and legacy Platform Admin receive investigate authority. Support and Auditor remain read-only. Actor identity is always server-derived. An unassigned case can be claimed; the current owner can release and investigate; other admins can inspect but cannot mutate. All mutations carry an expected case version. Report link/unlink also carries the report version. Stale or ownership conflicts return deterministic `409` responses, and Angular reloads instead of silently retrying.

## Notes, evidence, and privacy

Internal notes are bounded to 4,000 characters, append-only, admin-only, and retry-safe through a case/admin/idempotency-key uniqueness slot. They have no public, reporter, seller, buyer, or business-member endpoint. Corrections are new notes.

Evidence is an immutable validated reference; no upload or arbitrary URL is accepted. Operational evidence types are:

- `REPORT_SNAPSHOT` referencing a report already linked to the case. The immutable snapshot remains on the report and is not duplicated.
- `CURRENT_TARGET_SNAPSHOT` referencing a linked case target and capturing the current safe context.
- `EXISTING_ENFORCEMENT` referencing a linked case target and capturing read-only active action/scope context.

Evidence deletion is intentionally absent. Chat/message ingestion, crawling, file upload, and arbitrary external references are deferred.

## Persistence and timeline

Migration `V202608150500__create_investigation_cases.sql` adds the case aggregate. Forward migration `V202608160100__create_case_linked_enforcement.sql` adds normalized proposals, scopes, and immutable case-enforcement links. Cases and proposals are never hard-deleted.

The append-only timeline records `CASE_CREATED`, claim/release, explicit start, report/target link and unlink, note/evidence additions, severity changes, ready-for-action, and close-no-action. Failed, denied, stale, read, and rolled-back requests create no event. Large report/target/note/evidence collections are bounded by workflow constraints; search is server-paginated and report related-history remains capped.

## Admin UI and deferred work

Routes are `/admin/cases` and `/admin/cases/:caseId`. The inbox provides stable server-side search, status/severity/target/assignment/date filters, accessible pressed-state tabs, and responsive table states. Detail separates summary, assignment, primary/current target state, linked reports and immutable snapshots, related targets, internal notes, evidence, read-only enforcement, timeline, and conclusion actions. Report detail creates a case, searches assigned active cases for linking, or opens the linked case.

Appeals are implemented as a separate second-review and final-resolution layer
in [admin-appeals.md](admin-appeals.md). They may read the original case context
but never reopen or rewrite the case. Deferred: reopen/handoff, evidence
removal/upload, notifications, message/chat evidence, and all AI
classification/recommendation/execution.
