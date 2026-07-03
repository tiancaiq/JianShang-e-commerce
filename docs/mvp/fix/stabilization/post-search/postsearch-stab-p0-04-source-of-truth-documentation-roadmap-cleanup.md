# POSTSEARCH-STAB-P0-04 Source-Of-Truth Documentation And Roadmap Cleanup

Status: complete.

## Goal

Align the active MVP roadmap and search/profile stabilization docs after
profile/avatar, split public search, cursor pagination, and OpenSearch
projection work.

## Changes Made

- Updated `docs/mvp/development-roadmap.md` so `SEARCH-04` is complete in both
  the search section and the next implementation sequence.
- Updated the roadmap `FIX-05` section from planned to in progress and added
  references for completed `POSTSEARCH-STAB-P0-01` through
  `POSTSEARCH-STAB-P0-04`.
- Removed stale wording that still suggested `SEARCH-04` was the next search
  slice.
- Updated the active/deferred index to mention completed split search and the
  OpenSearch-derived projection.
- Corrected the stale search filename reference from
  `search-03-cursor-pagination-and-filters.md` to
  `search-03-shared-cursor-pagination.md`.

## Duplicate Docs Check

The remaining top-level slice files under `docs/mvp/` are already redirect
stubs kept to avoid breaking older links:

- `docs/mvp/fix-01-restore-verification-baseline.md`
- `docs/mvp/list-02-media-upload-request-confirm.md`
- `docs/mvp/site-00-logical-three-site-separation.md`

No file moves or deletions were needed in this slice.

## Current Source Of Truth

- MVP top-level requirements and decisions remain in `docs/mvp/requirements.md`,
  `docs/mvp/architecture.md`, `docs/mvp/database.md`,
  `docs/mvp/api-contract.md`, and `docs/mvp/development-roadmap.md`.
- Search slice docs are under `docs/mvp/search/`.
- Post-search/profile stabilization docs are under `docs/mvp/fix/`.
- After remaining P0 stabilization completes, the next MVP feature area is
  `CHAT-00` planning and authorization design.

## Verification

Planned command:

```powershell
rg -n "SEARCH-04|SEARCH-02A|SEARCH-02B|SEARCH-03|chat|avatar|OpenSearch|fix-05|planned|complete" docs/mvp AGENTS.md
git diff --check -- docs AGENTS.md
```

Results will be recorded after the final P0 verification pass.

## Non-Goals

- No application code changed.
- No API response shape changed.
- No V2 or V3 scope was added.
