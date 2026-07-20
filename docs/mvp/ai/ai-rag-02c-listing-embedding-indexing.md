# AI-RAG-02C Listing Embedding And Indexing

Status: implemented, activated, and verified on 2026-07-19. The local runtime
processed the full durable listing backlog successfully.

Release: V3.

## Scope Completed

`AI-RAG-02C` implements the first listing content path only:

- exact Product Service source-version fetch and reference validation;
- independent reconstruction and verification of the Product Service
  canonical SHA-256;
- versioned Unicode NFKC sanitization with control and bidi removal;
- one deterministic listing summary plus bounded description chunks;
- deterministic chunk IDs tied to source, sanitizer, chunker, and content
  identity;
- replaceable `EmbeddingProvider` and an async OpenAI adapter;
- explicit `text-embedding-3-small` requests with `1536` float dimensions;
- input count/token limits and response model, order, count, dimension, finite
  value, usage, timeout, and error validation;
- strict OpenSearch upsert, exact superseded-version invalidation/deletion,
  and race-safe cleanup;
- monotonic MySQL source state for applied, idempotent, stale, conflicting,
  and tombstoned versions;
- bounded worker lifecycle, retries, safe errors, metrics, and readiness;
- a Compose-managed OpenSearch node and idempotent index bootstrap.

It adds no retriever, customer-facing route, agent session, frontend,
rebuild/promotion workflow, non-listing source, LangChain integration, or
Gemini provider.

## Data And Security Contract

The provider and index receive only the approved public listing title,
description, public city/region, and price/currency. Hash verification runs
against the raw owner snapshot before Agent Service sanitization. Source
bodies, chunk text, vectors, provider responses, private locations, contacts,
seller identity, credentials, and raw exception details are excluded from
Kafka, Agent MySQL, and logs.

The OpenAI credential is read from the process environment and is redacted
from settings. The local replacement credential is stored only in ignored
`.env.local`; it is not committed or printed.

## Persistence And Contracts

No migration was added. `AI-RAG-02C` activates the
`knowledge_source_state` table created by
`V1__create_knowledge_ingestion_foundation.sql`.

No public API, Product Service API, Kafka event, or OpenAPI contract changed.
The existing exact-version Product Service source API and
`listing-knowledge-v1` reference event remain authoritative.

## Verification

- Python default suite: `77` discovered, `72` passed, and `5` opt-in
  integration tests skipped.
- MySQL 8.4 integration: `3` passed, including monotonic active, idempotent,
  conflict, stale, and tombstone state.
- OpenSearch 2.15 integration: `2` passed, including strict index lifecycle
  and synthetic processor-to-real-OpenSearch ingestion.
- Python compile check: passed.
- Compose configuration validation and Agent image build: passed.
- Production index bootstrap: `READY`, OpenSearch `2.15.0`, read/write alias
  `msb-agent-knowledge-v0001-000001`, embedding identity
  `openai`/`text-embedding-3-small`/`1536`.
- Deployed Agent readiness: `READY` for OpenAI configuration, knowledge index,
  and durable intake.
- Live synthetic embedding smoke: succeeded with
  `text-embedding-3-small`, `1536` dimensions, one input, and `11` tokens; no
  marketplace content was sent.
- Activated durable run: `165` jobs succeeded, `0` remained pending or
  retrying, and `0` dead-lettered.
- Production source state: `165 ACTIVE` listing rows with exactly `1536`
  embedding dimensions.
- Production knowledge index: `330` strict listing chunks.
- Provider usage reported by the service: `53,238` embedding input tokens.
- Product Service was recreated briefly during the run, producing `136`
  bounded `SOURCE_UNAVAILABLE` retry transitions. Every affected job recovered
  after Product Service became healthy.

## Operational State And Deferred Work

`AGENT_KNOWLEDGE_PROCESSOR_ENABLED` remains disabled by default in shared
Compose configuration and is explicitly enabled in the ignored local runtime
configuration. This keeps activation deliberate in each environment.

`AI-RAG-02D` now owns the implemented rebuild/export checkpoints,
dual-generation live mirroring, promotion gates, recovery commands, and
physical deletion operations. `AI-RAG-02E`, `AI-RAG-03`, and all
customer-service APIs remain deferred.
