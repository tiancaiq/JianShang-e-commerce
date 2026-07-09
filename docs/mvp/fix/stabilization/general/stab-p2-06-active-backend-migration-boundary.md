# STAB-P2-06 Active Backend Migration Boundary

Status: complete.

## Goal

Make active backend module and Flyway migration ownership clear after
`common-storage` and `chat-service` became part of the MVP backend.

This is cleanup only. It adds no product behavior and no database schema
changes.

## Decision

Active MVP backend modules are:

- `common-core`
- `common-web`
- `common-storage`
- `common-testing`
- `auth-service`
- `product-service`
- `chat-service`
- `api-gateway`

Active MVP service-owned Flyway locations are:

- `auth-service`: `classpath:db/migration/identity`
- `product-service`: `classpath:db/migration/catalog`
- `chat-service`: `classpath:db/migration/chat`

New migrations for these services must be timestamped, forward-safe files in
the matching service-owned subfolder. Do not add new root-level SQL files
directly under `src/main/resources/db/migration/`.

The old auth-service `V1__init_users.sql` remains in the repository as an
archived pre-MVP tutorial migration. It is not part of the active identity
Flyway location.

## Files Changed

- `tools/architecture_checks.py`
- `auth-service/src/main/resources/db/migration/README.md`
- `docs/p1-10-ci-baseline.md`
- `docs/p1-11-architecture-guardrails.md`
- `docs/mvp/fix/stabilization/general/stab-p2-01-v2-service-stub-archive.md`
- `docs/mvp/database.md`
- `docs/adr/0002-mysql-database-ownership-and-migration-conventions.md`
- `docs/mvp/development-roadmap.md`

## Verification

```powershell
python tools/architecture_checks.py
python tools/architecture_checks.py --self-test
```

Result: both checks passed.
