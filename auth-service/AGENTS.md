# Auth Service Instructions

These rules apply under `auth-service/` in addition to the repository
instructions.

## Ownership and authorization

- This service owns shared user identity, authentication, account/profile data,
  and scoped role or business-membership data assigned to it by the approved
  contract.
- Keep buyer, individual seller, business staff, and admin identities within
  the shared identity model; do not create parallel identity systems.
- Validate actor identity server-side. Never trust client-supplied roles, user
  IDs, business IDs, authentication state, or provider claims without the
  approved verification path.
- Business access requires `businessId` membership and the relevant permission.
  Downstream services must still enforce authorization for their resources.
- Admin capabilities are granular and auditable; do not infer broad admin
  authority from UI routes or gateway presence.

## API and persistence

- External endpoints use `/api/v1`, the standard error envelope, and correlation
  IDs. Preserve compatibility or version an intentional contract change.
- Use Flyway forward migrations; never rely on Hibernate schema auto-update or
  rewrite an applied migration.
- Use MySQL/InnoDB, UTC, `utf8mb4`, stable explicit statuses, and indexes based
  on actual access patterns.
- Keep avatar rules in this service; use `common-storage` only for technical
  object-storage plumbing.
- Never store raw passwords, access tokens, provider credentials, or completion
  secrets. Store approved secrets or challenges hashed, single-use, expiring,
  and rate-limited where the contract requires them.
- Verify external-provider or webhook signatures and deduplicate provider event
  IDs when an approved flow uses them.
- Redact credentials, tokens, and unnecessary PII from errors, logs, and
  metrics.

## Verification

Test authentication and account rules, persistence and API behavior, expired or
invalid credentials, and cross-user/cross-business denial. Include audit and
idempotency coverage where the approved command contract requires them; use
Testcontainers for MySQL integration where practical.
