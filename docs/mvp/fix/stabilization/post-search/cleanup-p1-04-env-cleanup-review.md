# CLEAN-P1-04 Environment Cleanup Review

Status: Complete  
Date: 2026-07-19

## Goal

Prevent local credentials and machine-specific values from entering cleanup
commits.

This slice intentionally does not edit `.env`, `.env.*`, or any local
environment file.

## Implementation Result

The current env state was inspected with read-only commands:

```powershell
git ls-files .env .env.* *.env
git status --short -- .env .env.dev .env.example .env.demo.example
```

Result:

- `.env` is currently modified.
- `.env.dev` is tracked but not currently modified.
- `.env.demo.example` is tracked but not currently modified.
- `.env.example` is tracked but not currently modified.

No env file was edited by this cleanup.

## Current Tracked Env Files

Git currently tracks:

- `.env`
- `.env.dev`
- `.env.demo.example`
- `.env.example`

## Current Risk

`.env` has local-development credential changes, including Keycloak, database,
and storage-related values. Some values look placeholder-like, but `.env` is a
tracked local runtime file and should not be included in a cleanup commit.

The cleanup rule is simple: no env file changes should be staged as part of the
MVP stabilization PR.

The current `.env` diff includes these risk categories:

- Keycloak/Postgres password values.
- Keycloak issuer/hostname values.
- Keycloak client-secret values.
- avatar/listing storage settings.
- storage access-key and secret-key placeholders.
- Flyway runtime setting changes.

Even when a value looks like a placeholder, the MVP cleanup PR should treat the
whole `.env` diff as local configuration and leave it unstaged.

## Safe Review Commands

These commands are read-only:

```powershell
git status --short -- .env .env.* *.env
git diff -- .env .env.dev .env.example .env.demo.example
git diff --cached -- .env .env.dev .env.example .env.demo.example
```

To inspect without printing values directly, use a redacted diff:

```powershell
$diff = git diff -- .env .env.dev .env.example .env.demo.example
$diff | ForEach-Object { if ($_ -match '^[+-][^+-].*=') { $_ -replace '=(.*)$','=***REDACTED***' } else { $_ } }
```

## Do Not Stage

Do not stage these during cleanup:

- `.env`
- `.env.dev`
- `.env.local`
- `.env.demo`
- any env file containing real local credentials.

If an env file is accidentally staged, unstage it without changing the local
file:

```powershell
git restore --staged -- .env .env.dev .env.example .env.demo.example
```

Do not use a worktree restore command for env files during cleanup, because it
would discard local runtime settings.

## Future Cleanup Approach

When the team intentionally handles environment configuration:

- move reusable placeholders into `.env.example` or `.env.demo.example`.
- keep real local values outside Git.
- document required variables in setup docs.
- rotate any value that was ever exposed publicly.

## Acceptance Criteria

- MVP cleanup PR contains no env file changes.
- `git diff --cached -- .env .env.* *.env` is empty before commit.
- teammate setup instructions use placeholders, not personal credentials.
- Any env cleanup is handled later as an explicit environment-configuration
  task, not hidden inside stabilization.
