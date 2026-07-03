# PRESEARCH-STAB-P2-02 Manual Browser Smoke Checklist

Status: complete.

## Goal

Create a short teammate-friendly manual smoke checklist for completed MVP
flows.

This cleanup is documentation only.

## Scope

Document browser checks for:

- login
- logout
- protected route return
- individual seller activation
- draft listing with images
- submit for review
- admin listing approval
- public marketplace browse
- business stores browse
- public listing detail

## Tasks

- Write the minimum local service prerequisites.
- List exact browser URLs and expected visible results.
- Call out any required test data setup.
- Link source-of-truth slice docs for deeper debugging.

## Non-Goals

- No automated E2E test.
- No new UI behavior.
- No route changes.
- No backend changes.

## Verification

```powershell
rg -n "smoke|login|listing|stores|admin approval" docs/mvp/fix docs/deploy
git diff --check -- docs
```

## Acceptance Criteria

- A teammate can smoke-test completed MVP flows without reading chat history.
- No code changes are required.

## Completion Notes

- Added `docs/mvp/fix/stabilization/pre-search/presearch-manual-browser-smoke-checklist.md`.
- The checklist covers local prerequisites, auth/session, protected route
  return, individual seller activation, draft listing with images, review
  submit, admin approval, public marketplace browse, business stores browse,
  and public listing detail.
- No application code, API contract, or schema changes were made.

## Verification Results

```powershell
rg -n "smoke|login|listing|stores|admin approval" docs/mvp/fix docs/deploy
git diff --check -- docs
```

Result: documentation references found; whitespace check passed.
