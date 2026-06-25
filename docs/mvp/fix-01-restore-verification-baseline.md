# FIX-01 Restore Verification Baseline

## Scope

FIX-01 restores the local verification baseline after the same-origin BFF URL
change and demo deployment documentation updates.

This fix does not add product behavior.

## Changes

- Frontend service and interceptor tests now expect same-origin `/api/...`
  gateway paths instead of absolute `http://localhost:9000/api/...` URLs.
- `docs/deploy/team-demo-readme.md` is intentionally kept as teammate demo
  documentation.
- Backend verification commands and the local Docker/Testcontainers limitation
  are documented below.

## Verification

Frontend tests:

```powershell
npm.cmd test -- --watch=false --browsers=ChromeHeadless
```

Result: passed, 48 tests.

Frontend build:

```powershell
npm.cmd run build
```

Result: passed.

Backend tests:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" test
```

Result: blocked in this Codex execution environment when product-service
Testcontainers tried to start Docker-backed MySQL/MongoDB containers.

Observed Docker check:

```powershell
docker version
```

Result:

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine;
check if the path is correct and if the daemon is running
```

Repeat the Maven backend command in a normal local shell after Docker Desktop
is running and Testcontainers can access the Docker daemon.

## Acceptance Notes

- Frontend tests pass.
- Frontend build passes.
- Backend command is repeatable and the Docker/Testcontainers local limitation
  is documented.
- No new product feature behavior was added.
