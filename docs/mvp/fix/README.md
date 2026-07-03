# MVP Fix And Stabilization Docs

Use this folder for verification, stabilization, and cleanup slice documents.

## Folder Map

- `stabilization/general/`: baseline cleanup, V2 demo quarantine, legacy auth
  cleanup, and general stabilization slices.
- `stabilization/auth-login/`: auth/login-specific stabilization slices.
- `stabilization/pre-search/`: stabilization work completed before public
  search and storefront expansion.
- `stabilization/post-search/`: stabilization work after profile/avatar, public
  search, storefront search, cursor pagination, and OpenSearch projection.

When adding a new stabilization slice, place it in the matching stabilization
folder and update `docs/mvp/development-roadmap.md`.
