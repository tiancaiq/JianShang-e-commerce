# MVP Demo Runbook

This runbook starts the smallest local demo stack for teammate assessment.
It is not a production deployment.

## Scope

Included services:

- Angular frontend
- API gateway
- Auth service
- Product service
- MySQL
- MongoDB
- Keycloak
- Postgres for Keycloak

Excluded for now:

- order, payment, inventory, notification services
- Kafka, schema registry, Kafka UI
- Elasticsearch, Logstash, Kibana
- production HTTPS and managed databases

## First-Time Setup

Create a local demo env file:

```powershell
Copy-Item .env.demo.example .env.demo
```

Keep `.env.demo` out of git. Before online deployment, replace every
`demo-change-me` value.

Build the backend JARs from the repository root:

```powershell
.\mvnw.cmd -pl common-core,common-web,auth-service,product-service,api-gateway -am package -DskipTests
```

If the Maven wrapper fails in a shell, run the same command from a normal
PowerShell terminal at the repository root.

## Start Demo

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml up -d --build
```

Check status:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml ps
```

Expected result:

```text
msb-demo-auth-service       healthy
msb-demo-product-service    healthy
msb-demo-api-gateway        healthy
msb-demo-frontend           healthy
```

## URLs

- Frontend: `http://localhost:4200`
- API gateway health: `http://localhost:9000/actuator/health`
- Keycloak: `http://localhost:8181`

## Login Check

Open:

```text
http://localhost:4200
```

Click **Continue to sign in**. After Keycloak login, the app should return to
the dashboard.

If the browser stays on `/login`, check:

```text
http://localhost:9000/api/v1/auth/session
```

The response should include:

```json
{
  "authenticated": true
}
```

## Logs

All demo services:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml logs --tail=100
```

One service:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml logs --tail=100 api-gateway
```

Useful services to inspect:

- `api-gateway`
- `auth-service`
- `product-service`
- `keycloak`

## Stop Demo

Stop containers without deleting data:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml down
```

Stop and reset demo data:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml down -v
```

## Database Backup

Local one-off MySQL backup:

```powershell
docker exec msb-demo-mysql sh -c "mysqldump -uroot -p$MYSQL_ROOT_PASSWORD --all-databases" > demo-mysql-backup.sql
```

For an online demo, schedule a nightly `mysqldump`, compress it, and upload it
to S3 or another private backup location. Keep at least seven daily backups.

## Known Demo Limitations

- This is a single-machine demo shape, not production.
- Keycloak runs in development mode.
- HTTPS is not included yet.
- MySQL and MongoDB are container volumes, not managed databases.
- Payment, order, inventory, notification, Kafka, and ELK are intentionally
  excluded.
- The platform must not claim to verify or protect off-platform individual
  buyer/seller payment or delivery.

## Next Online Step

After this local demo Compose file is verified, deploy the same stack to one
EC2 Ubuntu instance and add Caddy in front for HTTPS.
