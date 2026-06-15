# Phase 1 Toolchain and Build Baseline

Date: 2026-06-14

Scope: `P1-01`, `P1-02`, and `P1-03` only. No application feature behavior
was added or changed.

## Toolchain

Required project baseline:

- Java 21
- Maven 3.9.11 through the repository wrapper
- Node.js 20 or newer
- npm
- Docker Desktop with Docker Compose

Observed on the verification machine:

| Tool | Observed | Result |
|---|---|---|
| Java | Oracle JDK 25.0.2 | Available, but does not match the Java 21 baseline |
| Maven wrapper | 3.9.11 | Pass after restoring wrapper metadata and Windows script |
| Node.js | 24.14.0 | Pass |
| npm | 11.9.0 | Pass through `npm.cmd` |
| Docker | 29.5.3 | Pass |
| Docker Compose | 5.1.4 | Configuration parses |
| Chrome Headless | 149.0.0.0 | Available to Karma |

Notes:

- PowerShell execution policy blocks `npm.ps1` on this machine. Use
  `npm.cmd` from PowerShell.
- Docker reports that the Compose top-level `version` attribute is obsolete.
  This warning does not prevent configuration parsing.
- JDK 21 should be installed and selected through `JAVA_HOME` before feature
  development and CI are treated as authoritative.

## Setup Commands

Verify tools from the repository root:

```powershell
java -version
.\mvnw.cmd -version
node --version
npm.cmd --version
docker --version
docker compose version
docker compose config --quiet
```

Install frontend dependencies:

```powershell
Set-Location frontend
npm.cmd ci
```

## Backend Baseline

Compile and package without tests:

```powershell
.\mvnw.cmd -DskipTests package
```

Result on 2026-06-14: pass for the parent and all seven service modules.

Run the complete backend test suite:

```powershell
.\mvnw.cmd test
```

Result on 2026-06-14:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| product-service | 3 | 0 | 0 | 0 |
| order-service | 3 | 0 | 0 | 0 |
| inventory-service | 5 | 0 | 0 | 0 |
| api-gateway | 5 | 0 | 0 | 0 |
| notification-service | 2 | 0 | 0 | 0 |
| payment-service | 5 | 0 | 0 | 0 |
| auth-service | 1 | 0 | 0 | 0 |
| **Total** | **24** | **0** | **0** | **0** |

Backend tests require a running Docker engine because they use Testcontainers.

Observed non-blocking warnings:

- Java 25 native-access and dynamic-agent deprecation warnings
- Mockito inline mock-maker self-attachment warning
- Multiple Kafka test dependency `junit-platform.properties` resources
- Deprecated API use in a notification service test
- Existing JPA/Open Session in View and dialect warnings

These warnings are baseline observations, not changes made by P1-01 through
P1-03.

## Frontend Baseline

Install:

```powershell
Set-Location frontend
npm.cmd ci
```

Result: pass, 658 packages installed.

`npm ci` reported 43 dependency audit findings:

- 15 moderate
- 27 high
- 1 critical

No automatic `npm audit fix` was run because dependency upgrades can change
the build or application behavior. Dependency remediation belongs in a
separately reviewed setup task.

Production build:

```powershell
npm.cmd run build
```

Result: pass. Angular generated browser/server bundles and prerendered 10
routes.

Unit tests:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Result: pass.

Summary:

- 2 tests executed
- 2 passed
- 0 failed

The generated starter-title assertion was updated to verify the current root
template's router outlet. Application behavior was not changed.

## Current Baseline Status

- `P1-01`: complete with one environment follow-up: select JDK 21.
- `P1-02`: complete; backend package and all 24 tests pass.
- `P1-03`: complete; install, build, and both frontend tests pass.

No authentication, listing, chat, seller, or other application feature was
implemented.
