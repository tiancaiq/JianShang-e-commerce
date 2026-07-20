# AI-RAG-03 Listing-Only Knowledge Retriever

Status: implemented and verified on 2026-07-19.

Release: V3.

## Goal

Implement the replaceable, filtered `KnowledgeRetriever` boundary for the
listing customer-service assistant without waiting for unavailable policy,
safety, FAQ, and category source-owner contracts.

This first retriever supports `LISTING` only. `AI-RAG-02E` remains deferred;
the retriever rejects those future source types until their owning services
expose approved immutable versions, APIs, and durable events.

## Implemented Boundary

`ListingKnowledgeRetrievalRequest` separates trusted runtime context from the
bounded question:

- authenticated actor user ID;
- exact subject listing ID;
- current listing version returned by the future `getListing` call;
- effective question time;
- normalized language;
- bounded top-k and query;
- optional correlation ID;
- source types fixed to exactly `LISTING`.

The actor, subject listing, current version, effective time, and limits are
runtime-owned values. There is no customer-facing endpoint in this slice.

`KnowledgeRetriever` is a replaceable protocol.
`OpenSearchListingKnowledgeRetriever` is the initial implementation.

## Retrieval Rules

One bounded query embedding uses the same provider, model, and dimensions as
the promoted knowledge index. The response identity, vector count, and
dimensions are validated before search.

OpenSearch always targets the configured read alias and applies:

- `visibility=PUBLIC`;
- `sourceType=LISTING`;
- exact `sourceId` and `listingId` equal to the trusted session subject;
- exact `sourceVersion` equal to the current Product Service version;
- requested language plus the explicit `und` fallback;
- effective-from/effective-to selection at the trusted question time;
- absence of `invalidatedAt`.

Query text is used only to generate the embedding. It cannot add an index,
filter, actor, listing, source type, version, visibility, effective time, or
raw OpenSearch clause.

## Defense-In-Depth Validation

Every hit is parsed through a strict response model before it can become model
context. The retriever discards a hit when its listing, version, language,
effective window, or invalidation state differs from trusted context.

It fails closed when:

- a response contains unknown/private fields or an invalid shape;
- embedding identity or dimensions do not match;
- chunks for one exact source version disagree on content hash;
- two different chunks claim the same ordinal;
- OpenSearch returns an unsafe or malformed score.

Empty or entirely stale results return an empty passage collection. Provider
and OpenSearch failures use stable retryable/non-retryable classifications
without response bodies, queries, credentials, or endpoints.

## Bounds And Output

Initial defaults:

- query: 2,000 characters;
- top-k: 8 maximum;
- candidates: four times top-k, capped at 32;
- passage text: 2,400 characters;
- total returned context: 8,000 characters;
- default language fallback: `und`.

Passages contain bounded untrusted text plus exact citation metadata:
chunk/source/listing identity, source version, content hash, language,
effective window, ordinal, section label, public visibility, and score.
Embeddings are excluded from search responses.

## Observability And Verification

Low-cardinality metrics record result, latency, locally discarded hits,
passage count, and context characters. Logs contain safe result metadata and
optional correlation ID, never query text, actor identity, passage bodies,
embeddings, or credentials.

Synthetic unit tests cover strict requests, fixed filters, stale/invalidated
and cross-listing rejection, bounds, conflicts, strict response parsing, and
safe provider/OpenSearch failures. The OpenSearch 2.15 integration test
verifies vector relevance, exact version selection, empty stale-version
behavior, and cross-listing isolation. It uses synthetic embeddings and makes
no paid OpenAI request.

## Persistence, APIs, And Deferred Work

- No database migration is required.
- No external API or event contract changes.
- No customer-service endpoint is exposed.
- No agent-owned policy, safety, FAQ, or category authority is created.
- The separate `AI-RAG-02E-CATEGORY` adapter now adds category-guidance
  retrieval without changing this listing-only contract; its rollout flag
  remains disabled.
- Policy, safety, and FAQ sources remain deferred until source-owner contracts
  exist.
