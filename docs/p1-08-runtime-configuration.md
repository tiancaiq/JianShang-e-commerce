# P1-08 Runtime Configuration and Secrets Guide

## Purpose

This guide defines how services receive runtime configuration, how secrets are
kept separate from local defaults, and how production should use AWS Secrets
Manager.

P1-08 is documentation and setup only. It does not migrate databases, replace
legacy auth, remove existing tracked values, or create a runtime
`common-service`.

## Core Rules

1. Non-secret local defaults may live in `application.properties`,
   `docker-compose.yml`, and `.env.example`.
2. Real secrets live outside Git.
3. `.env.example` lists required local variables with placeholder values only.
4. `.env` and `.env.*` are developer-local files and must not be committed.
5. Production secrets are loaded from AWS Secrets Manager, not from committed
   files or image layers.
6. Shared modules may define types and helpers, but environment variables and
   secrets do not belong in shared constants.
7. Do not create a runtime common/config service for MVP configuration.

## Configuration Categories

| Category | Examples | Git policy |
| --- | --- | --- |
| Non-secret defaults | ports, local URLs, feature flags defaulting off | May be committed |
| Local placeholders | `local-dev-only-change-me` | May be committed only in `.env.example` |
| Developer secrets | local DB passwords, local JWT fallback | Never commit |
| Production secrets | DB passwords, OAuth client secrets, provider keys | AWS Secrets Manager only |
| Generated runtime values | correlation IDs, request IDs | Never store as static config |

## Environment Variable Naming

Use uppercase snake case.

```text
<SERVICE_OR_SYSTEM>_<SETTING>
```

Examples:

```text
AUTH_DB_PASSWORD
PRODUCT_SERVICE_URL
KAFKA_BOOTSTRAP_SERVERS
MAILPIT_SMTP_PORT
```

Rules:

- Use `*_URL` for complete URLs.
- Use `*_HOST` and `*_PORT` when host and port are configured separately.
- Use `*_USERNAME` and `*_PASSWORD` for credentials.
- Use `*_SECRET` only for secret values.
- Use `AWS_*` only for AWS runtime integration.
- Avoid ambiguous names such as `Localhost`.
- Avoid placing service-owned values in a global `COMMON_*` namespace.

## Local Development

Local development uses:

```text
.env.example -> copied by developer to .env
```

`.env.example` must include every environment variable referenced by:

- root Docker Compose files
- Spring Boot `application.properties`
- local run scripts

The example file intentionally uses placeholder values for secret-like
settings. Developers may use those for disposable local containers, but must
replace them when testing security-sensitive behavior.

## Spring Boot Defaults

Use Spring placeholder syntax for local-only fallbacks:

```properties
spring.datasource.password=${AUTH_DB_PASSWORD:local-dev-only-change-me}
```

Allowed:

- local hostnames
- local ports
- local container URLs
- non-production placeholder passwords

Not allowed:

- production credentials
- long-lived JWT signing secrets
- OAuth client secrets
- payment, shipping, email, storage, or AI provider keys

When a fallback is only present for legacy bootstrapping, document that it is
not the target production design.

## Production Secrets Manager

Production secrets should be stored under one AWS Secrets Manager prefix per
environment:

```text
/msb-ecom/<env>/<service>/<secret-name>
```

Examples:

```text
/msb-ecom/prod/auth-service/db-password
/msb-ecom/prod/api-gateway/oauth-client-secret
/msb-ecom/prod/notification-service/smtp-password
```

Recommended production boot process:

1. Runtime identity receives IAM permission for only its service prefix.
2. The service loads secrets at startup.
3. Secret values override non-secret defaults.
4. Services fail fast when required production secrets are absent.
5. Secret rotation is handled provider by provider with an explicit rollout
   plan.

Do not copy AWS secret values into `.env`, Docker Compose files, Kubernetes
manifests, build arguments, logs, or documentation.

## Service Ownership

Each deployable service owns its own runtime configuration. A service may read
only the variables needed to run that service.

Examples:

- `api-gateway` reads service URLs and auth verifier settings.
- `notification-service` reads Kafka and mail settings.
- `inventory-service` reads its own datasource settings.

Do not add a new runtime configuration service just to share environment
variables. If several services need the same non-secret default, document the
convention and repeat the variable name explicitly.

## Current Repository Notes

The repository still has tutorial-era configuration:

- `product-service` has a legacy MongoDB path.
- `auth-service` has a legacy PostgreSQL/custom JWT path.
- Some Docker Compose and Spring properties contain local fallback passwords.
- Older tutorial documents include example credentials.
- `.env` and `.env.dev` are currently tracked by Git. They should not be used
  as the target pattern for new work; cleanup requires a separate rotation and
  removal slice.
- The tracked `.agent` and tutorial/reference folders produce noisy
  secret-pattern hits. Treat those as review input, not automatic proof of a
  leaked production credential.

P1-08 does not remove these because replacement configuration is not ready in
this slice. Future migration slices should replace them with the target MySQL
and Keycloak configuration from the accepted MVP architecture decisions.

## Required Local Variables

The canonical local variable list is `.env.example`.

When adding a new variable:

1. Add it to `.env.example`.
2. Document whether it is secret or non-secret.
3. Use a safe local placeholder if it is secret.
4. Avoid adding it to shared code constants.
5. Add or update validation only in the service that owns the variable.

## Secret Scan

A repository secret scan must run before completing this slice and before any
future configuration change.

Minimum local scan:

```powershell
rg -n --hidden --glob '!**/.git/**' --glob '!**/target/**' --glob '!**/node_modules/**' --glob '!frontend/package-lock.json' '(JWT_SECRET|PASSWORD|SECRET|TOKEN|PRIVATE_KEY|BEGIN [A-Z ]*PRIVATE KEY|AKIA[0-9A-Z]{16})' .
```

Review results manually:

- `.env.example` placeholder names are expected.
- Documentation examples are expected only when clearly fake.
- Real values in `.env` or `.env.*` must be rotated and removed from Git in a
  dedicated cleanup slice.
- Do not paste discovered secret values into tickets, docs, or chat.

For CI, use a dedicated scanner such as Gitleaks or TruffleHog with an
allowlist for known placeholder strings.

## Adding New Configuration

Before adding a runtime variable:

1. Decide which service owns it.
2. Decide whether it is secret.
3. Pick an uppercase snake-case name.
4. Add a local placeholder to `.env.example`.
5. Add the service property mapping.
6. Add production AWS Secrets Manager path documentation when it is a secret.
7. Add startup validation if missing configuration would create unsafe
   behavior.

## Completion Boundary

P1-08 creates configuration rules only. It does not:

- implement AWS integration
- remove legacy MongoDB or PostgreSQL configuration
- replace custom JWT behavior
- create app feature flags
- create a common runtime configuration service
- change service runtime behavior
