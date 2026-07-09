# P1-10 CI Baseline

## Purpose

P1-10 adds pull-request quality checks for the existing backend and frontend
setup. It does not add deployment automation and does not implement product
features.

Workflow:

```text
.github/workflows/pull-request-quality.yml
```

## Jobs

| Job | Purpose | Main command |
| --- | --- | --- |
| `backend` | Compile and test the full Maven reactor | `./mvnw -B test` |
| `architecture` | Run source-based package and boundary guardrails | `python tools/architecture_checks.py` |
| `frontend` | Install, build, and test Angular | `npm ci`, `npm run build`, `npm test -- --watch=false --browsers=ChromeHeadless` |
| `migrations` | Validate active MVP services that own Flyway migrations | `./mvnw -B -pl auth-service,product-service,chat-service -am test` |
| `dependencies` | Resolve backend dependencies and scan frontend dependencies | `./mvnw -DskipTests test-compile`, `npm audit --audit-level=critical`, dependency review |
| `secrets` | Scan for high-confidence committed secret patterns | `grep` based secret scan |

## Dependency Scan Boundary

The baseline blocks critical frontend advisories through `npm audit`.

High-severity Angular/build-tooling advisories currently exist in transitive
dependencies, and some have no available fix. Those are intentionally not made
blocking in P1-10 so CI can be introduced before a dedicated dependency
remediation slice.

GitHub dependency review runs on pull requests to report dependency risk in
changed manifests.

## Secret Scan Boundary

The secret scan uses high-confidence token and private-key patterns. It avoids
generic words such as `PASSWORD` because the current repository contains
tracked tutorial and local-development placeholders.

P1-08 already documents that tracked `.env` and `.env.dev` require a separate
cleanup and rotation slice.

## Controlled Failure

The workflow supports a manual `workflow_dispatch` input:

```text
force_failure_job
```

Allowed values:

```text
backend
architecture
frontend
migrations
dependencies
secrets
```

Selecting one value intentionally fails that job. This gives the repository a
safe way to verify that the relevant check reports failure without committing
broken application code.

## Completion Boundary

P1-10 does not:

- deploy artifacts
- publish containers
- create environments
- change application behavior
- add app features
- remediate existing dependency advisories
