# AI-CS-02C Controlled Demo Chat Activation Configuration

Status: implemented as tracked configuration only. Runtime activation,
credentials, deployment, browser verification, production evidence, cohorts,
and rollout remain `BLOCKED`. Verified completion advances the AI lane from
1/3 to 2/3.

## Boundary

This slice adds one explicit Angular `demo-ai` build configuration and the
tracked Compose wiring needed by a later, separately approved VM deployment.
It does not enable a running environment. The normal Angular production and
development environments retain `aiAssistant=false`; the Dockerfile and both
tracked Compose frontend builds default to `production`.

The opt-in build changes only `features.aiAssistant` to `true`. All other
frontend feature flags remain false. It exposes only the previously approved
authenticated listing customer-service entry points and reserved Agent routes.
It does not add another API, tool, seller message, listing action, trade action,
or Product write.

## Exact Build And Runtime Gates

The explicit frontend selection is:

```text
FRONTEND_BUILD_CONFIGURATION=demo-ai
```

Without that value, the container build uses `production` and emits no Agent
UI control or Agent request from ordinary marketplace flows.

The gateway route remains absent unless this existing flag is explicitly true:

```text
GATEWAY_FEATURE_AGENT=false
```

Agent Service receives the existing settings below, all with committed false
defaults:

```text
AGENT_CUSTOMER_SERVICE_API_ENABLED=false
AGENT_CUSTOMER_SERVICE_KILL_SWITCH_ENABLED=false
AGENT_CUSTOMER_SERVICE_ORCHESTRATION_ENABLED=false
AGENT_CUSTOMER_SERVICE_RETRIEVAL_ENABLED=false
AGENT_CUSTOMER_SERVICE_PROVIDER_ENABLED=false
AGENT_PERSISTENCE_ENABLED=false
AGENT_KNOWLEDGE_ENABLED=false
AGENT_KNOWLEDGE_INGESTION_ENABLED=false
AGENT_KNOWLEDGE_PROCESSOR_ENABLED=false
```

`AGENT_CUSTOMER_SERVICE_KILL_SWITCH_ENABLED=true` overrides the generation
gates. Its committed default is false because the feature, gateway, API,
orchestration, retrieval, provider, persistence, knowledge, ingestion, and
processor gates already default off.

This slice adds no provider credential or secret default. `OPENAI_API_KEY`
resolves only from deployment input and otherwise remains empty. A later
deployment task must provision required values through the secure VM-owned
`/home/ubuntu/msb-ecom/.env.demo` boundary under separate approval. That file
must never be committed, printed, copied into an image layer, or inspected by
this slice.

## Explicit Demo Preparation Runbook

These are operator prerequisites for the later deployment task, not actions
performed by AI-CS-02C.

1. Confirm the secure VM configuration supplies every credential and endpoint
   required by the enabled Agent settings. Do not enable a gate whose required
   configuration is absent.
2. Build the frontend explicitly with
   `FRONTEND_BUILD_CONFIGURATION=demo-ai`. Keep `production` for every normal
   build.
3. Start the Compose `ai` profile only after resource and port review. It
   includes Agent migrations, Agent Service, OpenSearch, and the repository's
   approved Confluent Platform 7.5 Kafka/Zookeeper topology. Kafka is required
   only when knowledge ingestion is explicitly enabled.
4. Let Flyway apply the Agent-owned forward migrations V1 through V6. Do not
   rewrite or bypass them.
5. Bootstrap the knowledge index as a separate, explicit `ai-bootstrap`
   operation with `AGENT_KNOWLEDGE_ENABLED=true` and the approved OpenSearch
   embedding identity. Agent Service no longer bootstraps an index as a startup
   side effect.
6. Check `msb-knowledge-index status`, then use the guarded
   `msb-knowledge-ingestion` status/rebuild/validate/promote commands documented
   in the Agent Service README. Rebuild processing can call the configured
   embedding provider and therefore belongs only to the later authorized
   deployment.
7. Enable knowledge ingestion and the processor only after migrations,
   Kafka, the Product source adapter, OpenSearch aliases/mapping, and provider
   configuration are all healthy.
8. Enable persistence, the customer API, orchestration, retrieval, and
   provider gates in dependency order. Enable `GATEWAY_FEATURE_AGENT` and use
   the `demo-ai` frontend only after Agent readiness passes.
9. Verify `/health`, `/ready`, and `/metrics/` without exposing their internal
   ports publicly. A browser check additionally requires an authenticated test
   user, an eligible approved listing context, CSRF state from the existing BFF
   session, and explicit approval for browser work.

Index bootstrap is idempotent. Rebuild promotion is still guarded by source
completion, mapping/embedding identity, counts, drained queues, and Kafka lag.
Neither offline evaluation success nor this demo configuration is production
quality, latency, cost, privacy/policy, or rollout evidence.

## Rollback And Default-Off Order

1. Remove browser exposure first: rebuild the frontend with `production` and
   set `GATEWAY_FEATURE_AGENT=false`.
2. Engage `AGENT_CUSTOMER_SERVICE_KILL_SWITCH_ENABLED=true` before changing
   downstream generation dependencies.
3. Set provider, retrieval, orchestration, customer API, processor, ingestion,
   knowledge, and persistence enable flags to false.
4. Stop only the explicit `ai` profile. Preserve Agent data for the approved
   retention period; rollback never drops schemas or indexes automatically.
5. After traffic and work are stopped, the kill-switch setting may return to
   its committed false default while every enable flag remains false.

Normal marketplace, buyer/seller chat, Product, Auth, and business flows remain
independent throughout. This configuration must never be described as rollout
`READY`.
