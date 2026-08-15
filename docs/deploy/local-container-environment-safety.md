# Local Container Environment Safety

This guide prevents local application containers from silently starting without
the environment configuration they need.

## Environment File Rule

Use the environment file that belongs to the machine where Docker is running:

| Runtime | Environment file |
| --- | --- |
| Developer computer | `.env` |
| Demo VM | `.env.demo` |

The two files are not interchangeable. Never copy one over the other, commit
either file, or print their contents in logs or support messages.

The same Compose definition may be used in both places, but the explicit
`--env-file` argument must match the runtime.

## Never Recreate Application Containers Manually

Do not use `docker run` to recreate MSB application services. Do not manually
start a generic Java image such as `eclipse-temurin` with only an application
JAR.

A manually created container does not receive the environment mappings from
the repository's Compose file. The service may still start and report healthy
while silently using application defaults.

For Product Service, a missing `LISTING_MEDIA_STORAGE` setting falls back to
`local-demo`. That mode intentionally stores media metadata only and returns
zero-byte image responses. The catalog can therefore load normally while every
listing image appears broken.

## Supported Local Commands

Run these commands from the repository root.

Start or update the local demo-shaped stack with the local environment:

```powershell
docker compose --env-file .env -f docker-compose.demo.yml up -d --build
```

Recreate only Product Service after a code or configuration update:

```powershell
docker compose --env-file .env -f docker-compose.demo.yml up -d --no-deps --build product-service
```

Check the local stack:

```powershell
docker compose --env-file .env -f docker-compose.demo.yml ps
```

Do not omit `--env-file .env`, even though Docker Compose may automatically
discover a file named `.env`. Keeping it explicit makes the target environment
clear and prevents a command copied from the VM runbook from using the wrong
configuration.

## Supported VM Command

On the demo VM, use its existing VM-only environment file:

```bash
cd /home/ubuntu/msb-ecom
docker compose --env-file .env.demo -f docker-compose.demo.yml up -d --build
```

Do not run that command with `.env` on the VM. Do not run the VM command on a
developer computer.

## If a Container Was Created Manually

First confirm that the problem is limited to a stateless application container.
Never remove MySQL, PostgreSQL, Keycloak, OpenSearch, Kafka, Redis, or their
volumes as part of this recovery.

For a manually created local Product Service container, build the replacement
before stopping the current process:

```powershell
docker compose --env-file .env -f docker-compose.demo.yml build product-service
```

Then replace only Product Service and return it to Compose ownership:

```powershell
docker stop msb-demo-product-service
docker rm msb-demo-product-service
docker compose --env-file .env -f docker-compose.demo.yml up -d --no-deps product-service
```

Removing this application container does not remove its MySQL data or object
storage. Do not add `-v`, `down -v`, or `--remove-orphans` to this recovery.

## Verification Checklist

After a local recreation:

1. Confirm Product Service is running:

   ```powershell
   curl.exe -I http://localhost:8091/actuator/health
   ```

2. Confirm the gateway is running:

   ```powershell
   curl.exe -I http://localhost:9000/actuator/health
   ```

3. Open `http://localhost:4200` and verify listing images render.
4. In browser Network tools, confirm listing-media requests return `200` with
   a nonzero response size.
5. Check Product Service logs for storage errors without printing environment
   values:

   ```powershell
   docker logs --since 5m msb-demo-product-service
   ```

## Failure Signature

The common misconfiguration looks like this:

- public listing JSON returns `200` and includes image metadata;
- `/api/v1/public/listing-media/{imageId}` returns `200` with
  `Content-Length: 0`;
- listing cards show broken images;
- the Product container is not Compose-owned or does not have the configured
  storage mode.

When those signals appear together, fix the container configuration. Do not
delete listings, replace database rows, seed new media records, or copy data
from the VM.

## Safety Summary

- Local runtime: `.env`
- VM runtime: `.env.demo`
- Application containers: create and recreate through Compose only
- Persistent services and volumes: never delete while repairing an app
  container
- Secrets: never commit, print, or paste them
- Image metadata without bytes: investigate storage configuration before data
  repair
