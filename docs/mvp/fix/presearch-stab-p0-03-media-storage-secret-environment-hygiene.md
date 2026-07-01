# PRESEARCH-STAB-P0-03 Media Storage Secret And Environment Hygiene

Status: complete.

## Goal

Review object-storage configuration before search and storefront work starts
using listing images more broadly.

This cleanup preserves existing media behavior and API contracts.

## Scope

Review MEDIA-01 configuration and docs for:

- object storage endpoint
- bucket name
- access key references
- secret key references
- demo environment values
- local environment values
- public image URL behavior

## Tasks

- Search environment files, compose files, product-service config, and docs for
  storage credentials.
- Confirm real secrets are not promoted as shared defaults.
- Confirm demo placeholders are clearly marked when present.
- Confirm local-only secrets remain local.
- Confirm docs explain existing signed/public image delivery behavior.

## Changes

- Replaced real HMAC values in local `.env.demo` with placeholder values.
- Added a short warning comment in `.env.demo` that real cloud credentials do
  not belong in the demo file.
- Left existing runtime property names, product-service configuration, compose
  environment wiring, and MEDIA-01 API behavior unchanged.

## Files Changed

- `.env.demo`
- `docs/mvp/fix/presearch-stab-p0-03-media-storage-secret-environment-hygiene.md`
- `docs/mvp/fix/fix-04-pre-search-stabilization-sprint.md`
- `docs/mvp/development-roadmap.md`

## Notes

- `.env.demo` is local/ignored in this working tree, so the credential cleanup
  prevents accidental local reuse but does not add a tracked secret change.
- `.env` and `.env.demo.example` already used placeholder storage credentials.
- The storage bucket name and public base URL are not treated as secrets, but
  access key ID and secret access key must remain private.

## Non-Goals

- No storage provider change.
- No bucket policy change.
- No new media endpoint.
- No image transformation, thumbnailing, virus scanning, or moderation.
- No schema changes.

## Verification

```powershell
rg -n "GOOG|HMAC|ACCESS_KEY|SECRET|storage.googleapis.com|bucket|S3|GCS" .env* docker-compose*.yml product-service docs
git diff --check
```

Actual focused scan:

```powershell
rg -n "<known-demo-access-key>|<known-demo-secret-key>" .env .env.demo .env.example .env.demo.example docker-compose.yml docker-compose.demo.yml product-service/src/main/resources docs/mvp/list docs/deploy
```

Result:

- No real HMAC access key or secret key values were found.
- Placeholder values remain in `.env`, `.env.demo`, and
  `.env.demo.example`.
- Compose and product-service config use environment variable references.
- MEDIA-01 docs show redacted `...` placeholders.

## Acceptance Criteria

- No real shared secret is introduced.
- Existing media upload and delivery contracts are preserved.
- No database schema changes were made.
