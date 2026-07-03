# PRESEARCH-STAB-P0-04 Source-Of-Truth Documentation Cleanup

Status: complete.

## Goal

Make the completed MVP docs consistent before search work begins.

This cleanup changes documentation only unless a broken doc reference points to
a missing file that must be restored.

## Scope

Review source-of-truth docs and slice docs for:

- current public route names
- marketplace versus business store language
- listing lifecycle status language
- media slice naming
- search slice ordering
- deferred V2/V3 boundaries

## Tasks

- Check for duplicate slice names and stale top-level slice docs.
- Align route and surface names across roadmap, requirements, API contract,
  UI docs, site docs, list docs, and search docs.
- Confirm `SEARCH-02A`, `SEARCH-02B`, and `SEARCH-03` remain the next feature
  slices after stabilization.
- Keep older docs only when they are clearly historical or redirect readers to
  the current source of truth.

## Changes

- Added `docs/mvp/site/site-00-logical-three-site-separation.md` as the
  source-of-truth SITE-00 slice doc.
- Converted the old top-level `docs/mvp/site-00-logical-three-site-separation.md`
  into a redirect note.
- Converted the old top-level `docs/mvp/list-02-media-upload-request-confirm.md`
  into a redirect note to the foldered LIST-02 doc.
- Converted the old top-level `docs/mvp/fix-01-restore-verification-baseline.md`
  into a redirect note to the foldered FIX-01 doc.
- Updated `docs/mvp/development-roadmap.md` to reference the foldered SITE-00
  doc.
- Updated `AGENTS.md` so approved MVP slice folders include
  `docs/mvp/search/` and `docs/mvp/site/`.
- Updated the marketplace UI direction example so it no longer says individual
  and business listings share one marketplace surface.
- Updated `docs/mvp/fix/stabilization/general/fix-02-stabilization-sprint-plan.md` to mark the
  older duplicate-doc cleanup as complete.

## Files Changed

- `AGENTS.md`
- `docs/mvp/development-roadmap.md`
- `docs/mvp/site/site-00-logical-three-site-separation.md`
- `docs/mvp/site-00-logical-three-site-separation.md`
- `docs/mvp/list-02-media-upload-request-confirm.md`
- `docs/mvp/fix-01-restore-verification-baseline.md`
- `docs/mvp/ui/marketplace-ui-redesign.md`
- `docs/mvp/fix/stabilization/general/fix-02-stabilization-sprint-plan.md`
- `docs/mvp/fix/stabilization/pre-search/presearch-stab-p0-04-source-of-truth-documentation-cleanup.md`
- `docs/mvp/fix/stabilization/pre-search/fix-04-pre-search-stabilization-sprint.md`

## Behavior

No production code, API contract, route behavior, database schema, or runtime
configuration changed.

## Non-Goals

- No production code changes.
- No API contract changes.
- No schema changes.
- No new feature planning beyond clarifying already-approved next slices.

## Verification

```powershell
rg -n "LIST-04|LIST-07|MEDIA-01|SITE-01|SEARCH-02A|SEARCH-02B|SEARCH-03|business store|marketplace" docs/mvp AGENTS.md
git diff --check -- docs AGENTS.md
```

Additional focused checks:

```powershell
rg -n "docs/mvp/site-00-logical-three-site-separation|docs/mvp/list-02-media-upload-request-confirm|docs/mvp/fix-01-restore-verification-baseline|Status: redirected|docs/mvp/site/site-00-logical-three-site-separation" docs AGENTS.md
```

Result:

- Old top-level SITE-00, LIST-02, and FIX-01 docs are redirect notes.
- Roadmap references SITE-00 under `docs/mvp/site/`.
- LIST-02 and FIX-01 roadmap references already point to foldered source docs.
- `SEARCH-02A`, `SEARCH-02B`, and `SEARCH-03` remain the next search feature
  slices after the pre-search P0 stabilization gate.

## Acceptance Criteria

- Roadmap and slice docs do not conflict about current behavior.
- Teammates can find one source of truth for each completed area.
- No product behavior, API contract, or schema changes were made.
