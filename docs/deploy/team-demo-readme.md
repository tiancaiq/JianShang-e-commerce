# MSB Commerce MVP Demo

This is the shared online MVP demo environment for teammate review.
It is not production and should be treated as a temporary assessment server.

## Demo URL

Open:

```text
https://demo.bigjianshang.shop/login
```

Use the test account assigned to you by the project owner. If you cannot sign
in, ask for a Keycloak user in the `msb-local` realm.

## What Is Included

- Angular frontend
- API gateway
- Keycloak login
- Auth/account service
- Product/listing service
- MySQL
- MongoDB

The current demo is intended for reviewing the MVP foundation and recently
completed slices:

- IAM-00 to IAM-06
- IND-01 and IND-02
- BUS-01 to BUS-04
- LIST-00 and LIST-01

## What To Test

Please focus on the main MVP flows:

- Sign in and session behavior
- Profile view/edit
- Individual seller activation/profile
- Business application draft and submission
- Admin business application review/decision, if your account has admin access
- Listing category and draft creation paths

When reporting an issue, include:

- The URL you were on
- The account/role you used
- What you clicked or entered
- Expected result
- Actual result
- Screenshot or browser console/network error, if available

## Demo Limitations

This is a low-cost demo deployment, not a full production environment.

- The stack runs on one EC2 instance with Docker Compose.
- Keycloak is still running in demo/development mode.
- Databases are container-backed, not managed RDS.
- Order, payment, inventory, notification, Kafka, and search infrastructure are
  not part of this online demo yet.
- Individual buyer/seller payment and delivery are off-platform. The platform
  must not be described as verifying or protecting those off-platform actions.

## Do Not Use

Do not use these internal ports or local development URLs:

```text
http://localhost:4200
http://localhost:9000
http://localhost:8181
https://demo.bigjianshang.shop:4200
```

Use only:

```text
https://demo.bigjianshang.shop/login
```

## Admin Notes

Keycloak admin is available at:

```text
https://auth.bigjianshang.shop/admin
```

Only project maintainers should use the admin console. Do not share admin
credentials in chat, screenshots, GitHub, or documents.

## Deployment Owner Checklist

Before deploying updates:

```bash
cd ~/msb-ecom
mkdir -p backups
MYSQL_ROOT_PASSWORD=$(grep '^MYSQL_ROOT_PASSWORD=' .env.demo | cut -d= -f2- | tr -d '"')
docker exec msb-demo-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases > backups/mysql-$(date +%Y%m%d-%H%M%S).sql
git pull
docker compose --env-file .env.demo -f docker-compose.demo.yml up -d --build
docker compose --env-file .env.demo -f docker-compose.demo.yml ps
```

After deploying, verify:

```text
https://demo.bigjianshang.shop/login
```
