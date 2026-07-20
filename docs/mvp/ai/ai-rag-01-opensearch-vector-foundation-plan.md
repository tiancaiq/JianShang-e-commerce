# AI-RAG-01 OpenSearch Vector Foundation Plan

Status: implemented and verified on 2026-07-18.

Release: V3.

## Goal

Add the agent-owned OpenSearch vector-index foundation required by the later
knowledge ingestion and retrieval slices.

This slice proves configuration, client lifecycle, strict vector mappings,
versioned physical indexes, atomic aliases, readiness, safe errors, metrics,
and real OpenSearch integration tests. It does not ingest marketplace content
or expose a customer-facing agent endpoint.

## Implementation Result

The agent service now owns:

- validated, disabled-by-default OpenSearch configuration;
- one bounded async client lifecycle;
- strict schema-versioned `knn_vector` mappings with embedding identity in
  `_meta`;
- deterministic physical generations plus atomic read/write aliases;
- safe bootstrap, generation creation, promotion, rollback, bounded upsert,
  invalidation, and deletion operations;
- knowledge-index readiness, Prometheus metrics, structured safe logs, and an
  operator CLI;
- unit tests plus a pinned OpenSearch `2.15.0` lifecycle integration test and
  dedicated CI job.

Listing description and approved public attributes are derived index content.
Product Service remains authoritative for current listing name, price,
currency, public city/region, eligibility, and version. Exact location and
private contact data are excluded. Content ingestion and the exact composition
of description/name/public-location/price passages remain `AI-RAG-02`.

## Requirement And Roadmap Mapping

- `AI-RAG-00`: source ownership, chunk metadata, visibility, staleness,
  deletion, retention, and privacy contract.
- `AI-05`: future listing customer-service assistant.
- `AI-RAG-01`: versioned OpenSearch vector foundation.
- Depends on `AI-LLM-01` for the isolated Python/FastAPI service.
- Precedes `AI-RAG-02` ingestion and embeddings and `AI-RAG-03` retrieval.

## Baseline Before Implementation

- Local Docker Compose already runs OpenSearch `2.15.0` on host port `9201`
  and OpenSearch Dashboards on `5602`.
- Product Service owns `msb-public-listings`, a mutable text-search projection
  used by `SEARCH-04`.
- Product Service lazily creates that index and revalidates candidate IDs from
  MySQL.
- `agent-service` currently contains only the OpenAI provider foundation and
  has no OpenSearch dependency, configuration, index client, or vector tests.
- The agent-service CI job runs Python 3.12 unit tests and does not currently
  start OpenSearch.

The agent knowledge index must not reuse the Product Service index, mapping,
client, aliases, or ownership. The services may share the local OpenSearch
cluster, but their index families and production permissions remain separate.

## Approved Decisions

1. Reuse the repository's pinned OpenSearch `2.15.0` cluster for local and CI
   verification. Do not add another search engine or vector database.
2. Add the asynchronous official `opensearch-py` client to `agent-service`.
3. Use a dedicated agent knowledge index prefix and separate read/write
   aliases.
4. Use immutable, versioned physical indexes. Never mutate an incompatible
   mapping in place.
5. Set `dynamic: strict` and explicitly map every `AI-RAG-00` chunk field.
6. Store embeddings in `knn_vector` with HNSW, the Lucene engine, and cosine
   similarity.
7. Do not guess or hardcode a production embedding model or dimension in this
   slice. Index creation requires a validated embedding provider, model, and
   dimension identity. `AI-RAG-02` approves and supplies the production
   embedding choice.
8. Keep index administration in an operator CLI or deployment job, not a
   browser or customer-facing HTTP endpoint.
9. Extend readiness only when the knowledge capability is enabled. `/health`
   remains dependency-free.
10. Use deterministic synthetic vectors in tests. No OpenAI credential or paid
    request is required.

## Scope

In scope:

- bounded OpenSearch client dependency and async lifecycle;
- validated, secret-safe configuration;
- deterministic mapping and index settings generation;
- versioned physical index creation;
- read/write alias creation, validation, promotion, and rollback;
- bounded strict document index/update/invalidate/delete primitives;
- fixed metadata-filter builders used to prove vector filtering;
- mapping and embedding-identity compatibility checks;
- operator status and bootstrap commands;
- backward-compatible readiness reporting;
- stable OpenSearch error classification;
- structured logs and metrics;
- unit tests and Testcontainers-backed OpenSearch integration tests;
- agent-service documentation and CI updates.

Out of scope:

- authoritative source APIs or database reads;
- Kafka consumers, outbox events, checkpoints, or deduplication;
- content sanitization, chunking, or embeddings;
- listing, policy, FAQ, safety, or category ingestion;
- bulk rebuild content loading;
- production `retrieveKnowledge`;
- relevance ranking, reranking, or answer generation;
- agent sessions, messages, gateway routes, or frontend work;
- LangChain or LangGraph;
- changes to `msb-public-listings` or Product Service search behavior;
- automatic deletion of old physical index generations;
- a live OpenAI request.

## Component Boundary

Planned agent-service components:

```text
KnowledgeIndexSettings
    -> validates feature, connection, aliases, embedding identity, and limits

KnowledgeIndexMapping
    -> generates deterministic settings, mappings, and mapping metadata

OpenSearchClientFactory
    -> owns AsyncOpenSearch construction and shutdown

KnowledgeIndexAdmin
    -> status, bootstrap, create generation, promote, rollback, validate

KnowledgeIndexWriter
    -> bounded chunk upsert, exact invalidation, and exact deletion primitives

KnowledgeIndexReadiness
    -> verifies cluster reachability, aliases, mapping, and embedding identity

KnowledgeIndexError
    -> stable safe classification without response bodies or credentials
```

`KnowledgeRetriever`, `EmbeddingProvider`, source adapters, and ingestion
workers remain later interfaces. No OpenSearch client type may appear in an
external API contract.

## Index Naming And Alias Contract

Default names:

```text
prefix:      msb-agent-knowledge
physical:    msb-agent-knowledge-v0001-000001
read alias:  msb-agent-knowledge-read
write alias: msb-agent-knowledge-write
```

The physical name contains:

- a code-owned mapping schema version;
- an operator-created, monotonically increasing generation.

The prefix and aliases must be lowercase and contain only approved
OpenSearch-safe characters. Wildcards, commas, whitespace, path characters,
and names that collide with the Product Service index are rejected.

The read alias points to exactly one promoted generation. The write alias
points to exactly one generation marked `is_write_index=true`.

The service never queries a physical index name during normal retrieval.
Future ingestion writes through the write alias; future retrieval reads
through the read alias.

## Index Lifecycle

### Bootstrap

1. Validate configuration without logging secrets.
2. Verify OpenSearch reachability and compatible major version.
3. Refuse to continue if either alias has an invalid multi-index or write-index
   state.
4. If neither alias exists, create generation `000001` with the complete
   mapping and both aliases in the same index-creation request.
5. If aliases already exist, validate them and return the current status
   without recreating or changing the index.

Bootstrap is idempotent for identical configuration.

### Create a new generation

1. Resolve the next unused generation under the configured prefix.
2. Create it with the code-owned schema and configured embedding identity.
3. Validate settings, mappings, and `_meta`.
4. Atomically move only the write alias to the new generation.
5. Keep the read alias on the previously promoted generation.

`AI-RAG-02` later fills the new write generation and verifies ingestion before
promotion.

### Promote

Promotion uses one `_aliases` request that atomically:

- removes the read alias from its current generation;
- adds the read alias to the fully validated target generation.

Promotion refuses:

- a missing target;
- a target outside the configured prefix;
- schema or embedding-identity mismatch;
- an invalid write-alias state;
- a target that has not passed the caller's explicit readiness gate.

`AI-RAG-01` tests promotion with synthetic documents. Production content-count,
source-version, and ingestion-checkpoint gates belong to `AI-RAG-02`.

### Rollback

Rollback is the same atomic read-alias swap to the previously promoted,
compatible generation. It does not move the write alias or delete an index.

The foundation retains current and previous generations. No automatic cleanup
or destructive index command is added in this slice.

## Mapping Contract

Index settings:

- `index.knn=true`;
- configurable positive shard and replica counts;
- explicit maximum field count;
- no default ingest pipeline;
- no automatic rollover policy.

Mappings use `dynamic: strict`.

| Field | OpenSearch type | Rules |
| --- | --- | --- |
| `chunkId` | `keyword` | Also used as the deterministic document `_id` |
| `sourceType` | `keyword` | One of the `AI-RAG-00` source types |
| `sourceId` | `keyword` | Stable authoritative source identifier |
| `sourceVersion` | `keyword` | Exact immutable source version |
| `contentHash` | `keyword` | Lowercase digest format validated before write |
| `listingId` | `keyword` | Nullable; required for listing-specific content |
| `visibility` | `keyword` | Initial value must be `PUBLIC` |
| `language` | `keyword` | Normalized supported language tag |
| `effectiveFrom` | `date` | Nullable ISO 8601 UTC |
| `effectiveTo` | `date` | Nullable ISO 8601 UTC |
| `indexedAt` | `date` | Required ISO 8601 UTC |
| `invalidatedAt` | `date` | Nullable ISO 8601 UTC |
| `ordinal` | `integer` | Non-negative chunk order |
| `sectionLabel` | `keyword` | Bounded safe display label |
| `text` | `text` | Sanitized public passage; returned as source context |
| `embedding` | `knn_vector` | Configured dimension, HNSW/Lucene, cosine similarity |

Search responses must exclude `embedding` from `_source`. The foundation does
not log or return indexed `text` through readiness or operator status output.

The mapping `_meta` records:

- mapping schema version;
- embedding provider;
- embedding model;
- embedding dimensions;
- distance space;
- agent-service version that created the index.

A different model, dimension, distance space, or incompatible mapping requires
a new physical generation. It is never applied to an existing vector field.

HNSW construction parameters are code-owned defaults for the first baseline.
Changing them after measurement requires a new schema version and generation,
not an environment-only in-place change.

## Document Operation Contract

`AI-RAG-01` provides storage primitives for later use by `AI-RAG-02`. It does
not decide which authoritative sources should be indexed.

Allowed operations:

- bounded bulk upsert of strict `KnowledgeChunkDocument` objects through the
  write alias;
- mark chunks for one exact `(sourceType, sourceId, sourceVersion)` invalid by
  setting `invalidatedAt`;
- delete exact chunk IDs;
- delete chunks for one exact `(sourceType, sourceId, sourceVersion)`;
- count or validate documents through fixed safe filters.

Rules:

- no operation accepts a raw OpenSearch query, script, index name, wildcard, or
  caller-supplied alias;
- document `_id` is the validated `chunkId`;
- bulk size and serialized request size are bounded;
- every document must satisfy the source-type, visibility, listing-ID,
  timestamp, hash, ordinal, text-size, and embedding-dimension schema before a
  network call;
- listing content requires `listingId`; non-listing source types reject it
  unless a later contract explicitly allows scoped guidance;
- initial visibility is exactly `PUBLIC`;
- upsert and exact deletion are idempotent;
- invalidation is monotonic and does not clear an existing `invalidatedAt`;
- writes do not force a refresh for every document; the caller requests one
  bounded refresh or waits at a batch boundary;
- partial bulk failures return per-item safe status to the ingestion caller
  and are never reported as full success;
- raw document bodies, passage text, and embeddings are excluded from logs and
  exceptions.

The fixed filter builder supports only fields approved by `AI-RAG-00`:

- source type;
- source ID and version;
- exact subject listing ID;
- `visibility=PUBLIC`;
- language;
- effective date range;
- `invalidatedAt` missing.

`AI-RAG-03` later composes these filters with the production vector query,
top-k limit, source precedence, and result contract. This slice executes only
synthetic filter/vector probes in integration tests.

## Configuration Contract

Implemented environment settings:

| Variable | Required | Default | Rule |
| --- | --- | --- | --- |
| `AGENT_KNOWLEDGE_ENABLED` | No | `false` | Enables readiness and index administration |
| `AGENT_OPENSEARCH_URL` | When enabled | none | Absolute `http` or `https` URL |
| `AGENT_OPENSEARCH_USERNAME` | Production policy dependent | none | Never logged |
| `AGENT_OPENSEARCH_PASSWORD` | Production policy dependent | none | Secret and excluded from representations |
| `AGENT_OPENSEARCH_VERIFY_CERTS` | No | `true` | May be disabled only for approved local development |
| `AGENT_OPENSEARCH_REQUEST_TIMEOUT_SECONDS` | No | `3` | Total connection/request timeout; positive and bounded |
| `AGENT_OPENSEARCH_MAX_RETRIES` | No | `2` | Integer from `0` through `5` |
| `AGENT_KNOWLEDGE_INDEX_PREFIX` | No | `msb-agent-knowledge` | Safe index prefix |
| `AGENT_KNOWLEDGE_READ_ALIAS` | No | `msb-agent-knowledge-read` | Must differ from prefix and write alias |
| `AGENT_KNOWLEDGE_WRITE_ALIAS` | No | `msb-agent-knowledge-write` | Must differ from prefix and read alias |
| `AGENT_KNOWLEDGE_EMBEDDING_PROVIDER` | When enabled | none | Stored in mapping `_meta` |
| `AGENT_KNOWLEDGE_EMBEDDING_MODEL` | When enabled | none | Stored in mapping `_meta`; no provider call |
| `AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS` | When enabled | none | Integer `1` through `16000` |
| `AGENT_KNOWLEDGE_SHARDS` | No | `1` | Positive bounded integer |
| `AGENT_KNOWLEDGE_REPLICAS` | No | `0` locally | Non-negative bounded integer |
| `AGENT_KNOWLEDGE_BULK_MAX_DOCUMENTS` | No | `500` | Positive bounded batch limit |
| `AGENT_KNOWLEDGE_BULK_MAX_BYTES` | No | implementation-safe limit | Positive bounded serialized request limit |

There is intentionally no default production embedding model or dimension.
Integration tests supply a synthetic identity and small deterministic
dimension. `AI-RAG-02` selects the production embedding configuration and must
create a new generation when that identity changes.

When knowledge is disabled, missing OpenSearch and embedding settings do not
prevent service startup. When enabled, invalid or incomplete settings fail
configuration before any network request.

## Client And Runtime Direction

- Add the async extra of the official `opensearch-py` client.
- Construct one `AsyncOpenSearch` client through the FastAPI application
  lifespan and close it on shutdown.
- Use bounded connection/request timeouts and retries.
- Do not retry validation errors, mapping conflicts, authentication failures,
  or forbidden responses.
- Retry only the approved temporary connection and service statuses with a
  bounded total attempt count.
- Do not enable node sniffing in the initial single-endpoint deployment.
- Keep TLS verification enabled by default.
- Redact credentials, URL user information, response bodies, and indexed text
  from exceptions, logs, and traces.

The existing Product Service raw HTTP client is not moved into a shared module
and is not reused by the Python agent service.

## Readiness And Failure Contract

`GET /health` remains process liveness and never calls OpenSearch or OpenAI.

`GET /ready` keeps its existing OpenAI status and adds a backward-compatible
knowledge-index component:

- `DISABLED` when `AGENT_KNOWLEDGE_ENABLED=false`;
- `READY` when the cluster, read alias, write alias, mapping, and embedding
  identity are valid;
- `NOT_CONFIGURED` for missing required enabled configuration;
- `UNAVAILABLE` for bounded connection or temporary service failure;
- `UNAUTHORIZED` for authentication or permission failure;
- `INCOMPATIBLE` for cluster, mapping, alias, schema, or embedding mismatch.

An enabled non-ready knowledge index returns `503`. Readiness makes no OpenAI
request and reads no knowledge document body.

Stable internal error classes:

- `KNOWLEDGE_INDEX_CONFIGURATION_INVALID`;
- `KNOWLEDGE_INDEX_UNAVAILABLE`;
- `KNOWLEDGE_INDEX_UNAUTHORIZED`;
- `KNOWLEDGE_INDEX_INCOMPATIBLE`;
- `KNOWLEDGE_INDEX_ALIAS_INVALID`;
- `KNOWLEDGE_INDEX_DOCUMENT_INVALID`;
- `KNOWLEDGE_INDEX_PARTIAL_FAILURE`;
- `KNOWLEDGE_INDEX_OPERATION_REJECTED`.

No customer-facing error code is added because this slice exposes no
customer-facing endpoint.

## Operator Commands

Planned module:

```text
python -m msb_agent_service.knowledge_index status
python -m msb_agent_service.knowledge_index bootstrap
python -m msb_agent_service.knowledge_index create-generation
python -m msb_agent_service.knowledge_index promote --index <physical-name>
python -m msb_agent_service.knowledge_index rollback --index <physical-name>
```

Commands print only safe index names, alias state, schema/embedding identity,
document count, health, and result status. They never print credentials,
indexed passages, embeddings, or raw OpenSearch responses.

Promotion and rollback require an exact physical index name. Wildcards and
implicit deletion are forbidden. No cleanup/delete command is included.

## Security And Deployment

- Local Compose may continue using the existing loopback-bound,
  security-disabled OpenSearch node.
- Production requires TLS verification and service credentials supplied at
  runtime.
- Runtime retrieval credentials receive read access only to the read alias.
- Future ingestion credentials receive write access only to the write alias
  and required document operations.
- Index creation and alias administration use a deployment/operator credential,
  not the normal web-request credential.
- Production permissions are limited to the agent index prefix and aliases;
  they do not grant access to the Product Service index.
- Credentials never enter source code, `.env` changes in this planning slice,
  logs, metrics, mappings, index documents, or test fixtures.

The initial deployment may share the existing OpenSearch cluster. A separate
cluster requires a later measured scaling or isolation decision.

## Observability

Structured logs:

- configuration validation result;
- client startup/shutdown result;
- cluster compatibility result;
- index create result;
- alias validation and atomic swap result;
- document batch, invalidation, and deletion result counts;
- readiness result;
- operation failure classification and retry count.

Safe log context:

- correlation or operator command ID;
- operation;
- physical index and alias names;
- schema version;
- embedding provider/model identifier and dimensions;
- duration and result status.

Do not log credentials, full URLs with user information, request/response
bodies, passage text, embeddings, or document `_source`.

Metrics:

- `agent_knowledge_index_operation_total{operation,status}`;
- `agent_knowledge_index_operation_duration_seconds{operation}`;
- `agent_knowledge_index_ready`;
- `agent_knowledge_index_alias_valid`;
- `agent_knowledge_index_documents`;
- `agent_knowledge_index_last_success_timestamp_seconds`;
- `agent_knowledge_index_freshness_seconds`.

`freshness_seconds` is the time since the latest successful indexed document
timestamp when documents exist. True source-event-to-index lag requires event
timestamps and checkpoints and is completed by `AI-RAG-02`; the metric name
and reporting boundary are established here without inventing source events.
Index names, source IDs, listing IDs, and error messages are not metric labels.

## Test Plan

### Unit tests

- knowledge-disabled startup accepts missing OpenSearch configuration;
- enabled mode rejects missing URL, embedding identity, or dimensions;
- URL, alias, prefix, timeout, retry, shard, replica, and dimension bounds;
- credentials are absent from `repr`, logs, and mapped errors;
- deterministic mapping JSON and `_meta`;
- all required chunk fields exist with `dynamic: strict`;
- vector mapping uses configured dimensions and cosine similarity;
- document validation rejects disallowed source/visibility/listing combinations,
  oversized text, malformed hashes, and wrong vector dimensions;
- bulk document and request-size limits;
- raw query, script, index, alias, and wildcard input cannot enter document
  operations;
- physical generation names are deterministic and wildcard-safe;
- alias-state validation rejects multiple read or write targets;
- temporary and permanent OpenSearch errors classify correctly;
- `/health` performs no dependency call;
- `/ready` reports disabled, ready, unavailable, unauthorized, and
  incompatible states.

### OpenSearch integration tests

Use Testcontainers with pinned
`opensearchproject/opensearch:2.15.0`, single-node discovery, security disabled,
and a random host port.

Tests must:

- wait for HTTP readiness before assertions;
- bootstrap the first physical index and both aliases;
- prove idempotent bootstrap;
- inspect exact settings, mappings, and `_meta`;
- reject an unknown field because mappings are strict;
- reject a vector with the wrong dimension;
- upsert deterministic synthetic vectors and prove an identical replay is
  idempotent;
- execute one basic cosine k-NN query with the fixed public, listing, source,
  language, effective-date, and non-invalidated filters;
- invalidate one exact source version and prove it is excluded;
- delete exact chunk IDs and one exact source version idempotently;
- surface partial bulk item failures without reporting full success;
- create a second generation;
- move the write alias while the read alias remains stable;
- atomically promote the read alias;
- roll the read alias back without moving the write alias;
- reject incompatible schema or embedding metadata;
- verify no test document contains disallowed private fields;
- clean up the container automatically.

Local runs may skip the integration class when Docker is unavailable, but the
dedicated CI integration job must run it and fail on a missing or unhealthy
container.

### Regression tests

- existing provider/configuration, structured-output, image, and tool smoke
  tests remain green;
- no test needs `OPENAI_API_KEY`;
- no test makes an OpenAI request;
- Product Service OpenSearch tests remain unchanged.

## Anticipated Files

Implementation should remain inside the agent-service and focused
documentation/CI surfaces:

```text
agent-service/pyproject.toml
agent-service/README.md
agent-service/src/msb_agent_service/config.py
agent-service/src/msb_agent_service/api.py
agent-service/src/msb_agent_service/schemas.py
agent-service/src/msb_agent_service/knowledge_index.py
agent-service/src/msb_agent_service/knowledge_index_client.py
agent-service/src/msb_agent_service/knowledge_index_mapping.py
agent-service/src/msb_agent_service/knowledge_index_documents.py
agent-service/tests/test_config.py
agent-service/tests/test_api.py
agent-service/tests/test_knowledge_index.py
agent-service/tests/test_knowledge_index_integration.py
.github/workflows/pull-request-quality.yml
```

The implementer may split modules differently when responsibilities remain
clear. Do not edit `.env`, Product Service search code, frontend code, gateway
routes, or database migrations in this slice.

## Implementation Order

1. Add validated knowledge-index settings and secret-safe tests.
2. Add the bounded async OpenSearch client and safe error mapping.
3. Add deterministic settings/mapping generation.
4. Add strict chunk validation and bounded document operations.
5. Add status, bootstrap, and alias validation.
6. Add generation creation, promotion, and rollback.
7. Integrate client lifecycle and optional readiness.
8. Add structured logs and metrics.
9. Add real OpenSearch integration tests and the dedicated CI path.
10. Update agent-service documentation.
11. Run agent unit/integration tests and existing Product Service OpenSearch
    regression tests.

## Acceptance Criteria

- Agent knowledge uses its own versioned index family and aliases.
- The Product Service public listing index remains unchanged.
- Configuration is disabled by default and fails fast when enabled but invalid.
- No production embedding model or dimension is guessed.
- Strict mappings contain every `AI-RAG-00` field and reject unknown fields.
- Vector mapping and synthetic cosine search pass against OpenSearch `2.15.0`.
- Bounded upsert, invalidation, and deletion primitives accept only strict
  documents and fixed filters and handle partial failure safely.
- Bootstrap is idempotent.
- Read and write aliases each resolve to exactly one valid generation.
- Promotion and rollback are atomic and non-destructive.
- `/health` remains dependency-free and enabled readiness fails safely.
- Logs, metrics, errors, and commands expose no secrets or indexed content.
- CI runs a real OpenSearch integration path.
- No source ingestion, embedding request, retrieval tool, customer API,
  persistence migration, gateway route, or UI is added.

## Completion Evidence

- Agent unit suite: 35 tests passed with the integration test skipped in the
  normal path.
- Live vector lifecycle: 1 test passed against
  `opensearchproject/opensearch:2.15.0`.
- Product Service isolation regression: 2
  `OpenSearchListingSearchClientTests` passed.
- No live OpenAI request, source ingestion, embedding request, database
  migration, external/customer API, gateway route, or frontend change was
  added.
- No `.env` or unrelated worktree change was modified by this slice.
- `AI-RAG-02` still owns authoritative source adapters, sanitized deterministic
  content, embeddings, events, rebuild/checkpoint logic, and deletion
  propagation. `AI-RAG-03` still owns production retrieval and ranking.
