# PRESEARCH-STAB-P2-04 Historical Demo And Deferred-Service Index

Status: complete.

## Goal

Help teammates distinguish active MVP code from deferred V2/tutorial material.

This cleanup is documentation only unless broken references need correction.

## Scope

Review and index:

- active MVP backend modules
- deferred V2/tutorial backend modules
- active Angular route groups
- deferred or archived UI screens
- source-of-truth MVP docs

## Tasks

- List which services and screens are active MVP.
- List which services and screens are deferred V2/tutorial references.
- Link the current roadmap, architecture, and AGENTS guidance.
- Avoid deleting code in this nice-to-have slice.

## Non-Goals

- No code deletion.
- No Maven reactor change.
- No route change.
- No behavior change.

## Verification

```powershell
rg -n "deferred|V2|tutorial|active MVP" docs/mvp docs/deploy
git diff --check -- docs
```

## Acceptance Criteria

- Teammates can identify active versus deferred code quickly.
- No build, API, schema, or behavior changes are made.

## Completion Notes

- Added `docs/mvp/fix/stabilization/pre-search/presearch-active-deferred-index.md`.
- The index lists active MVP backend modules, active Angular route groups,
  deferred V2/tutorial backend modules, deferred UI folders, and the search
  work boundary.
- No code, route, API contract, Maven reactor, or schema changes were made.

## Verification Results

```powershell
rg -n "deferred|V2|tutorial|active MVP" docs/mvp docs/deploy
git diff --check -- docs
```

Result: documentation references found; whitespace check passed.
