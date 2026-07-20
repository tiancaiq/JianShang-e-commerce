# AI-RAG-02B Agent Durable Ingestion Intake

Status: implemented and verified on 2026-07-19.

Release: V3.

## Requirement

Implement the Agent Service-owned durable intake boundary for
`AI-RAG-02` without adding sanitization, embeddings, OpenSearch writes,
retrieval, customer routes, or frontend behavior.

## Implemented Contract

- Python 3.12/FastAPI remains the isolated Agent Service runtime.
- Forward-only Flyway `V1` creates the agent-owned ingestion schema.
- Startup validates the externally migrated schema and never mutates it.
- One async Kafka consumer subscribes to the configured allowlisted listing
  topic with auto-commit disabled.
- Version-1 event envelopes and reference payloads are validated before
  persistence. Unknown additive fields are ignored.
- `processed_events` and one `knowledge_ingestion_jobs` row are inserted in one
  MySQL transaction.
- Kafka offset `message.offset + 1` is committed only after that transaction
  commits. Failed validation, persistence, or offset commit rewinds the
  partition and does not advance it.
- Duplicate event delivery is safe. Reuse of one event ID with a different
  contracted payload hash fails closed.
- Job claims use `FOR UPDATE SKIP LOCKED`, expiring ownership, bounded attempts,
  exponential retry scheduling, terminal success, and dead-letter states.
- Exact Product Service versions are fetched only through the authenticated
  `X-Agent-Internal-Service-Token` route and validated with a strict response
  schema.
- FastAPI lifespan owns clean Kafka, HTTP-client, metrics-task, and MySQL-pool
  shutdown.

## Migration

```text
agent-service/db/migration/
V1__create_knowledge_ingestion_foundation.sql
```

The migration creates:

- `processed_events`;
- `knowledge_ingestion_jobs`;
- `knowledge_source_state`.

These tables store source references, versions, state, hashes, timestamps, and
safe operational error codes only. They store no source body, passage,
embedding, provider response, credential, seller identity, contact data, exact
location, media, or moderation evidence.

Flyway runs as a separate deployment job. The optional Compose `ai` profile
pins Redgate Flyway 12.11.0 and packages the Agent Service as a non-root Python
container.

## Runtime And Readiness

Durable intake is disabled by default. Enabled configuration requires:

- agent-owned MySQL connection and password;
- Kafka bootstrap servers, topic, stable group, and client ID;
- Product Service base URL and dedicated service token.

`GET /ready` adds:

```json
{
  "knowledgeIngestion": "DISABLED|READY|UNAVAILABLE"
}
```

The status is `READY` only while the Kafka intake task is running. Overall AI
readiness may remain unavailable when the OpenAI credential is absent; this
does not stop durable intake and neither readiness route calls a provider.

Low-cardinality Prometheus metrics cover event intake result, offset commits,
job transitions and counts, oldest pending age, and future exact-source fetch
result/latency.

## Verification

The default Agent Service suite passed:

```text
58 tests, 0 failures, 0 errors, 3 opt-in integrations skipped
```

The opt-in MySQL 8.4 Testcontainers suite passed:

```text
2 tests, 0 failures, 0 errors
```

It verifies the migration, atomic enqueue, replay deduplication, event-ID hash
conflict, isolated claims, retry state, and expired-claim recovery.

Live local verification used the already-running Product Service, MySQL 8.3,
Kafka 7.5, and `listing-knowledge-v1` topic:

- Flyway schema version: `1`, successful;
- published topic end offset: `165`;
- consumer committed offset: `165`;
- consumer lag: `0`;
- processed events: `165`;
- `PENDING` ingestion jobs: `165`;
- knowledge source state rows: `0`, as required before `02C`;
- Agent Service container: healthy;
- ingestion readiness: `READY`;
- authenticated exact-version Product Service fetch: successful.

No OpenAI, Gemini, embedding, or other paid provider request was made.

## Deferred

`AI-RAG-02C` remains the next slice. It will own deterministic source
sanitization, content-hash verification, chunking, the replaceable embedding
adapter, OpenAI embedding calls, OpenSearch writes, version replacement, and
source-state advancement.

Until then, accepted jobs intentionally remain `PENDING`; no worker loop is
started and no knowledge document is indexed.
