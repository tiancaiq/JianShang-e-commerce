# Team Git and Environment Safety

This project has two kinds of files:

- **Code and documentation**: commit, push, pull, and deploy through Git.
- **Environment/secrets files**: keep local or on the VM only. Do not commit them.

## Do Not Push

Never commit or push these files:

- `.env`
- `.env.*`
- `.env.demo`
- private SSH keys such as `*.pem` or files under `~/.ssh/`
- local database dumps or backups
- generated build output such as `target/`, `dist/`, `.angular/`, and `node_modules/`
- IDE-only files such as `.idea/` and personal run configurations

The repository `.gitignore` already ignores `.env` and `.env.*`. If `git status`
shows one of these files, stop and check before committing.

## Local Environment Files

Each developer should keep their own local environment file.

Typical local file:

```text
.env
```

Use it for local database passwords, local Keycloak URLs, local storage keys,
and development-only overrides.

This file is not shared through Git. Pulling from GitHub should not overwrite it.

## Demo VM Environment Files

The demo VM has its own environment file:

```text
/home/ubuntu/msb-ecom/.env.demo
```

This file belongs to the VM. It contains demo database passwords, Keycloak
settings, domain names, storage settings, and deployment-only flags.

Do not copy the VM `.env.demo` into Git. Do not replace it during deploy unless
you are intentionally changing VM environment settings.

## What Is Safe To Push

Safe to commit and push:

- Java source code
- Angular source code
- tests
- Flyway migrations
- Dockerfiles
- `docker-compose.demo.yml`
- GitHub Actions workflows
- docs under `docs/`
- sample files such as `.env.example`

`docker-compose.demo.yml` is safe to push because it should contain variable
names and defaults only, not real secrets.

## What Pull Does

`git pull` updates tracked files from GitHub.

It should update files like:

- application code
- Docker Compose definitions
- docs
- migrations
- tests

It should not update ignored local files like:

- `.env`
- `.env.demo`

If `git pull` says local changes would be overwritten, do not force it. Run:

```bash
git status
```

Then decide whether those changes should be committed, stashed, or kept only on
that machine.

## Before Committing

Always check:

```bash
git status
git diff --cached --name-only
```

Make sure no secret or local environment file is staged.

If a secret file is staged by mistake:

```bash
git restore --staged .env
git restore --staged .env.demo
```

Then check again.

## Manual Demo Deploy

The demo VM should deploy from the `dev` branch:

```bash
cd ~/msb-ecom
git pull --ff-only origin dev
chmod +x mvnw
./mvnw -DskipTests package
docker compose --env-file .env.demo -f docker-compose.demo.yml up -d --build
```

This pulls code from GitHub but keeps the VM `.env.demo` file on the VM.

## Current Demo Notes

Current demo domain:

```text
https://demo.bigjianshang.shop
https://auth.bigjianshang.shop
```

The demo VM needs this setting in `.env.demo` because older moderation migrations
were added after a newer demo migration had already run:

```text
SPRING_FLYWAY_OUT_OF_ORDER=true
```

This is a demo-environment fix. Do not use it as a reason to rewrite old Flyway
migrations.
