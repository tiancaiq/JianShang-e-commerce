# STAB-P2-01 V2 Service Stub Archive

Status: complete.

## Goal

Archive tutorial-era V2 backend service stubs so MVP teammates focus on active
MVP services.

## Decision

Keep these directories as reference code only:

- `order-service`
- `inventory-service`
- `payment-service`
- `notification-service`

They are not part of the active MVP Maven reactor, migration validation job, or
architecture guardrails.

## Active MVP Backend Modules

The active Maven reactor is:

- `common-core`
- `common-web`
- `common-testing`
- `auth-service`
- `product-service`
- `api-gateway`

## V2 Reintroduction Rule

Do not build features on the archived stubs directly.

When V2 starts, create an approved architecture slice that defines:

- service boundary and ownership
- API/event contracts
- database schemas and migrations
- idempotency and recovery rules
- tests and CI scope

Then either replace the archived stub with real implementation code or create a
new module from the current service template.

## Verification

Expected checks:

```powershell
.\mvnw.cmd test
python tools/architecture_checks.py
python tools/architecture_checks.py --self-test
```
