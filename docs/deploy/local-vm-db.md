# Local Code Against Demo VM MySQL

Use this only when you need to debug local code with the online demo database.
Normal development should use the local Docker database.

## What This Does

```text
Local Docker services -> host.docker.internal:3307 -> SSH tunnel -> VM MySQL
IntelliJ services     -> 127.0.0.1:3307          -> SSH tunnel -> VM MySQL
```

The VM database is not opened to the internet. The connection goes through SSH.

## 1. Open the SSH Tunnel

Run this in PowerShell and keep the window open:

```powershell
ssh -i "C:\Users\b\Downloads\msb-demo.pem.pem" -L 3307:127.0.0.1:3306 ubuntu@18.191.207.104
```

## 2. Set the VM MySQL Password Locally

In your local `.env.demo`, add the same MySQL password used on the VM:

```properties
VM_MYSQL_ROOT_PASSWORD=your-vm-mysql-password
```

Do not commit `.env.demo`.

## 3. Run Local Containers Against VM MySQL

From the repo root:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml -f docker-compose.vm-db.yml up -d --build
```

The extra `docker-compose.vm-db.yml` file changes only the MySQL connection for:

- `auth-service`
- `product-service`

Other local infrastructure, such as Keycloak and MongoDB, still runs locally.

## IntelliJ-Run Services

If you run a Spring service directly from IntelliJ instead of Docker, use these
environment variables:

```properties
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3307
MYSQL_ROOT_PASSWORD=your-vm-mysql-password

IDENTITY_DB_HOST=127.0.0.1
IDENTITY_DB_PORT=3307
IDENTITY_DB_PASSWORD=your-vm-mysql-password

CATALOG_DB_HOST=127.0.0.1
CATALOG_DB_PORT=3307
CATALOG_DB_PASSWORD=your-vm-mysql-password
```

The inventory, order, and payment services also support service-specific host
and port variables now:

```properties
INVENTORY_DB_HOST=127.0.0.1
INVENTORY_DB_PORT=3307
ORDER_DB_HOST=127.0.0.1
ORDER_DB_PORT=3307
PAYMENT_DB_HOST=127.0.0.1
PAYMENT_DB_PORT=3307
```

## Stop VM DB Mode

Stop the containers:

```powershell
docker compose --env-file .env.demo -f docker-compose.demo.yml -f docker-compose.vm-db.yml down
```

Then close the PowerShell window running the SSH tunnel.

## Safety Notes

- Local code will read and write the online demo database.
- Do not run destructive tests or cleanup scripts in this mode.
- Do not expose VM port `3306` in AWS Security Groups.
- Prefer local Docker DB for normal feature work.
