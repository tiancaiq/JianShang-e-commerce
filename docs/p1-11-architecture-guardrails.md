# P1-11 Code Quality and Architecture Guardrails

## Purpose

P1-11 adds documented and automated checks for package ownership, service
boundaries, shared module purity, and baseline formatting/quality behavior.

This slice does not implement application features.

## Automated Checks

Script:

```text
tools/architecture_checks.py
```

Local commands:

```powershell
python tools/architecture_checks.py
python tools/architecture_checks.py --self-test
```

CI command:

```bash
python tools/architecture_checks.py
python tools/architecture_checks.py --self-test
git diff --check
```

## What The Script Enforces

### Service Package Ownership

Each deployable service owns one Java package root:

| Module | Package root |
| --- | --- |
| `api-gateway` | `com.msb.ecom.api_gateway` |
| `auth-service` | `com.msb.ecom.auth_service` |
| `inventory-service` | `com.msb.ecom.inventory_service` |
| `notification-service` | `com.msb.ecom.notification_service` |
| `order-service` | `com.msb.ecom.order_service` |
| `payment-service` | `com.msb.ecom.payment_service` |
| `product-service` | `com.msb.ecom.product_service` |

Java source in a service must stay under its package root.

### Common Package Ownership

Shared Java modules own these package roots:

| Module | Package root |
| --- | --- |
| `common-core` | `com.msb.ecom.common.core` |
| `common-web` | `com.msb.ecom.common.web` |
| `common-testing` | `com.msb.ecom.common.testing` |

### No Service-To-Service Java Imports

A service may import:

- its own package
- `com.msb.ecom.common.*`
- external libraries

A service may not import another service package. Use a synchronous API,
Kafka event, or shared transport type instead.

### No Cross-Service Database Access

Service resource files must not point their datasource URL at another
service-owned database. SQL migrations must not qualify table references with
another service schema.

This is a guardrail for the MVP rule: one service owns one schema, and no
service queries another service database.

### No Business Code In Common Modules

Common modules must not contain:

- JPA entities
- repositories
- Spring business services
- MVC controllers
- domain-specific package names such as `listing`, `order`, `payment`,
  `seller`, `trade`, or `business`

Common modules are for technical contracts and reusable infrastructure only.

## Formatting and Lint Baseline

Current baseline checks:

- `git diff --check` catches trailing whitespace and conflict markers.
- Backend compile/tests catch Java syntax and architecture tests.
- Frontend build/tests catch Angular and TypeScript strictness.

Dedicated Java formatter, Angular lint, and broader style checks are deferred
until a formatting toolchain is selected. This avoids a large formatting-only
diff during Phase 1 setup.

## Controlled Violation Test

`tools/architecture_checks.py --self-test` creates temporary invalid source
files and verifies the scanner catches:

- service-to-service imports
- JPA entity code in a common module
- domain package names in a common module
- datasource access to another service database

No invalid example code is committed to the repository.

## Completion Boundary

P1-11 does not:

- add business features
- move service code
- split or merge services
- change database schemas
- apply broad formatting changes
- choose a permanent Java or Angular formatter
