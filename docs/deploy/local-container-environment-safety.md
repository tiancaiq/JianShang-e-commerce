# Local Container Environment Safety

This guide prevents local application containers from silently starting without
the environment configuration they need.

## Environment File Rule

Use the environment file that belongs to the runtime being started:

| Runtime | Environment file |
| --- | --- |
| Demo stack, including local walkthroughs | `.env.demo` |
| Separate developer-only stack | `.env` (only when explicitly configured) |

The two files are not interchangeable. The generic `.env` may contain safe
placeholder storage values and must not be used to recreate demo services.
Never copy one file over the other, commit either file, or print their contents
in logs or support messages.

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

When Product Service runs with `LISTING_MEDIA_STORAGE=s3`, its container health
probe calls `/actuator/health/listingMedia`. The probe performs an authenticated
metadata read for a known uploaded object, using the same endpoint, region,
credentials, bucket, signing mode, and path-style setting as public media reads.
It uses the newest persisted uploaded object by default. Set
`LISTING_MEDIA_HEALTH_CHECK_OBJECT_KEY` only when a deployment needs a stable
private canary object instead.

## Supported Local Commands

Run these commands from the repository root.

Start or update the demo stack with its configured environment:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml up -d --build
```

For the full commerce overlay, use the wrapper. It defaults to `.env.demo` and
passes that file through to both Compose definitions:

```powershell
.\tools\start-commerce-demo.ps1
```

Use `-EnvironmentFile` only for an intentionally separate, fully configured
developer environment. The post-start verification requires S3 media mode and
a successful authenticated storage read.

Recreate only Product Service after a code or configuration update. Include
both demo Compose definitions so its runtime remains aligned with the commerce
stack:

```powershell
docker compose --env-file .env.demo `
  -f docker-compose.demo.yml `
  -f docker-compose.cart-runtime.yml `
  up -d --no-deps --force-recreate product-service
```

Check the local stack:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml ps
```

Do not omit `--env-file .env.demo`, even though Docker Compose may automatically
discover a file named `.env`. Keeping the demo file explicit prevents
placeholder credentials from replacing the configured demo credentials.

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

For a manually created demo Product Service container, build the replacement
before stopping the current process:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml build product-service
```

Then replace only Product Service and return it to Compose ownership:

```powershell
docker stop msb-demo-product-service
docker rm msb-demo-product-service
docker compose --env-file .env.demo -f docker-compose.demo.yml -f docker-compose.cart-runtime.yml up -d --no-deps product-service
```

Removing this application container does not remove its MySQL data or object
storage. Do not add `-v`, `down -v`, or `--remove-orphans` to this recovery.

## Verification Checklist

After a local recreation:

1. Confirm Product Service and its media storage dependency are healthy:

   ```powershell
   curl.exe http://localhost:8091/actuator/health/listingMedia
   ```

   S3 mode must return `UP`. A failure returns only a safe reason code such as
   `ACCESS_DENIED`, `OBJECT_NOT_FOUND`, `NO_CHECK_OBJECT`, or
   `STORAGE_UNAVAILABLE`; the response never contains credentials, endpoints,
   bucket names, object keys, signed URLs, or provider response bodies.

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

With the storage health probe installed, S3 credential, signing, and object
availability failures also make the Product container unhealthy instead of
allowing this partial-success state to pass deployment checks.

When those signals appear together, fix the container configuration. Do not
delete listings, replace database rows, seed new media records, or copy data
from the VM.

## Safety Summary

- Demo runtime, local or VM: `.env.demo`
- Separate developer runtime: `.env` only when explicitly configured
- Application containers: create and recreate through Compose only
- Persistent services and volumes: never delete while repairing an app
  container
- Secrets: never commit, print, or paste them
- Image metadata without bytes: investigate storage configuration before data
  repair
