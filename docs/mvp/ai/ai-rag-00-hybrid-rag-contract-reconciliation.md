# AI-RAG-00 Hybrid-RAG Contract Reconciliation

Status: complete as a documentation-only contract slice.

Release: V3.

## Goal

Reconcile the original `getListing`-only customer-service plan with the
approved hybrid-RAG direction before any OpenSearch, embedding, persistence,
gateway, orchestration, or frontend slice begins.

This slice changes contracts only. It adds no dependency, migration, runtime
code, environment configuration, provider request, or product endpoint.

## Reconciled Product Contract

The listing customer-service assistant uses two complementary retrieval paths:

1. `getListing` reads current authoritative structured facts from Product
   Service.
2. `retrieveKnowledge` reads bounded, source-attributed public passages from
   the agent-owned OpenSearch knowledge projection.

The assistant remains authenticated-only, read-only, and bound to one active,
approved individual listing. It is a platform AI assistant, not the seller,
and it never participates in buyer/seller chat.

Changing facts such as listing eligibility, price, quantity, negotiability,
condition, location, payment or delivery preferences, and the transaction
notice must come from `getListing`. Vector retrieval cannot establish or
override those facts.

## Source Types And Ownership

| Source type | Authoritative owner | Initial content |
| --- | --- | --- |
| `LISTING` | Product Service | Current structured facts returned by `getListing`; approved public description and attributes supplied to the indexer |
| `MARKETPLACE_POLICY` | Moderation/support module | Versioned marketplace rules effective for the question time |
| `SAFETY_GUIDANCE` | Moderation/support module | Versioned payment, delivery, and meeting-safety guidance |
| `MARKETPLACE_FAQ` | Moderation/support module | Versioned public marketplace help content |
| `CATEGORY_GUIDANCE` | Product Service | Versioned public buying guidance associated with approved categories |

Source owners remain authoritative for text, version, effective dates,
visibility, and deletion. They expose versioned source reads or exports and
publish durable change or invalidation events where required. The agent
service never reads an owning service's database.

The agent service owns:

- deterministic sanitization and chunking;
- embedding generation through a replaceable `EmbeddingProvider`;
- the versioned OpenSearch index, mappings, aliases, and retrieval adapter;
- indexing checkpoints, event deduplication, tombstones, and lag metrics;
- source-attributed answer orchestration and evaluation.

OpenSearch stores only a rebuildable derived projection. Conversation history,
private contact data, exact locations, moderation evidence, internal notes,
identity-provider data, storage credentials, object keys, signed URLs, and
listing media bytes are never indexed.

## Source Precedence And Conflict Rules

The runtime applies these rules before model output is accepted:

1. Product Service eligibility and structured fields are authoritative for the
   subject listing.
2. Effective `MARKETPLACE_POLICY` and `SAFETY_GUIDANCE` are authoritative for
   normative marketplace and safety guidance.
3. Approved listing description and public attributes may explain
   seller-provided details but cannot override structured listing fields.
4. `MARKETPLACE_FAQ` and `CATEGORY_GUIDANCE` are explanatory sources and cannot
   override current listing facts, policy, or safety guidance.
5. When two eligible sources at the same precedence level conflict, the
   assistant reports uncertainty instead of merging them.
6. Missing, expired, invalidated, version-mismatched, or unauthorized passages
   are excluded from model context.

Agent instructions and model memory are not evidence sources. The Product
Service transaction notice is always preserved when the answer concerns
payment or delivery.

## Knowledge Chunk Contract

Every indexed chunk has strict metadata:

- `chunkId`;
- `sourceType`;
- `sourceId`;
- `sourceVersion`;
- `contentHash`;
- nullable `listingId`;
- `visibility`, initially only `PUBLIC`;
- `language`;
- nullable `effectiveFrom` and `effectiveTo`;
- `indexedAt`;
- nullable `invalidatedAt`;
- chunk ordinal and safe section label.

The initial retriever applies mandatory server-side filters:

- allowlisted source types only;
- `visibility=PUBLIC`;
- exact subject `listingId` for listing-specific chunks;
- no other listing's listing-specific content;
- effective source version at the question time;
- supported language or an explicit platform-default fallback;
- not invalidated.

The model cannot supply actor ID, subject listing ID, visibility, policy date,
or unrestricted index filters. Those values come from trusted runtime context.
Top-k, query length, passage size, total context, timeout, and retry counts are
bounded configuration.

## Tool Contracts

### `getListing`

Purpose: read current eligibility and authoritative public facts for the
session's subject listing.

The tool accepts no model-selected listing or actor identity. The handler
injects the authenticated actor and session subject, calls Product Service,
and returns a strict safe projection.

It must return or establish:

- listing ID and version;
- individual seller type;
- active and approved eligibility;
- title and safe thumbnail route;
- price and currency;
- negotiability;
- quantity;
- condition;
- public city and region;
- payment and delivery preferences;
- approved transaction notice.

### `retrieveKnowledge`

Purpose: retrieve bounded approved passages relevant to the question.

Allowed model arguments are:

- bounded `query`;
- optional allowlisted `sourceTypes`;
- optional supported `language`.

The handler injects actor, session, subject listing, visibility, policy time,
and retrieval limits. It returns passages plus safe source metadata. It has no
write effect.

No general HTTP, database, OpenSearch query, browser, web-search, file-search,
MCP, shell, or sandbox tool is exposed to the agent.

## Answer, Citation, And Action Contract

The model returns a strict candidate answer. The agent service validates that:

- every cited source was returned by `getListing` or `retrieveKnowledge`;
- the answer does not contradict authoritative structured facts;
- every action is in the session-type allowlist and targets the subject
  listing;
- no private or disallowed field is present.

The stored and returned assistant message uses this direction:

```json
{
  "id": "01M00000000000000000000002",
  "role": "ASSISTANT",
  "body": "Only the seller can confirm whether the item can be held.",
  "resolutionType": "CONTACT_SELLER",
  "sources": [
    {
      "sourceType": "LISTING",
      "sourceId": "01L00000000000000000000001",
      "sourceVersion": "12",
      "label": "Current listing"
    }
  ],
  "actions": [
    {
      "type": "MESSAGE_SELLER",
      "listingId": "01L00000000000000000000001"
    }
  ],
  "createdAt": "2026-07-18T12:01:01Z"
}
```

Initial `resolutionType` values:

- `ANSWERED`;
- `PARTIAL`;
- `UNKNOWN`;
- `CONTACT_SELLER`;
- `REFUSED`.

Initial action allowlist:

- `MESSAGE_SELLER`;
- `VIEW_LISTING`;
- `BROWSE_MARKETPLACE`.

Actions are route semantics, not arbitrary URLs or commands. The frontend
renders only validated actions and never parses answer text to infer behavior.
`MESSAGE_SELLER` opens the existing user-controlled conversation flow and does
not send a message.

Action payloads are fixed:

- `MESSAGE_SELLER` requires only the session subject `listingId`;
- `VIEW_LISTING` requires only the session subject `listingId`;
- `BROWSE_MARKETPLACE` has no model-supplied route, query, or identifier.

Factual answers cite at least one supporting source. A refusal for an
unsupported action may omit sources. `UNKNOWN` and `PARTIAL` identify the
missing or conflicting information and cite any source used to establish that
gap.

## Staleness, Invalidation, And Deletion

- Product Service eligibility is checked when a session is created and before
  every answer that relies on listing data.
- Listing deactivation, moderation loss, removal, or deletion emits a durable
  invalidation event through the owning service's outbox.
- Source updates and deletions are idempotent and keyed by source ID and
  version. Consumers deduplicate by event ID.
- A newer version replaces the retrievable version; old chunks may remain only
  in an inactive versioned index during a bounded rollback window and are never
  returned by the active alias.
- Tombstoned or invalidated content is excluded immediately when known and
  physically removed from active indexes by the deletion worker.
- The operational target for source update or deletion propagation is 15
  minutes. If indexing lag exceeds the approved threshold, affected
  vector-backed answers are disabled or degraded to authoritative listing
  facts and uncertainty.
- `getListing` always wins over stale vector content. If the retriever reports
  a listing-content version different from the current listing version, those
  passages are discarded.
- OpenSearch loss is recoverable by rebuilding from authoritative sources and
  durable events.

Policy, safety, FAQ, and category sources use effective dates and explicit
version activation. Retrieval never selects a future, expired, or superseded
version.

## Persistence And Migration Decision

The agent service owns a separate MySQL schema. It uses forward-only Flyway SQL
migrations stored with `agent-service`; application startup does not create or
update schema automatically. A dedicated migration job or Flyway runtime
applies migrations before the service version is promoted.

Future `AI-CS-01A` migrations create:

- `agent_sessions`;
- `agent_messages`;
- `agent_invocations`;
- `agent_tool_calls`.

Required constraints include:

- one open `LISTING_CUSTOMER_SERVICE` session per actor and subject listing;
- unique retry key for session owner and `clientMessageId`;
- message pagination by `(session_id, created_at, id)`;
- actor session lookup by `(actor_user_id, updated_at, id)`;
- invocation and tool-call lookup by status, correlation ID, and created time.

No migration is added by `AI-RAG-00`.

## Retention And Privacy

- Open agent sessions and message bodies are retained for 90 days after last
  activity so authenticated users can resume the thread.
- A retention job closes inactive sessions and deletes message bodies and
  model answer payloads after the 90-day window.
- Safe invocation, tool-call, version, usage, latency, cost, result, and
  correlation metadata is retained for 365 days for security, reliability,
  and rollout evaluation.
- Deduplication records may retain request hashes and result references for the
  same 90-day session window; they do not retain duplicate body copies.
- Production message bodies, prompts, and retrieved passages are not copied
  into unrestricted logs, traces, analytics, or evaluation fixtures.
- Evaluation fixtures use synthetic or explicitly approved redacted data.
- Provider requests disable provider-side response storage where supported by
  the provider adapter. The service sends only the bounded question, approved
  source context, and runtime instructions required for the invocation.
- User deletion or an approved privacy request removes eligible agent content
  while retaining only the minimum non-content audit record required by
  security and legal policy.

Retention periods are configuration with the values above as the initial
contract. Any longer message-content retention requires a separately approved
privacy change.

## Failure Behavior

- Product Service failure returns a temporary dependency error and creates no
  assistant message.
- OpenSearch failure may produce a listing-facts-only answer only when the
  question can be fully grounded by `getListing`; otherwise the invocation
  fails temporarily or returns an explicit `UNKNOWN` result under the approved
  orchestration policy.
- Embedding pipeline, Kafka, or OpenSearch failure never affects listing
  detail, search fallback behavior, or buyer/seller chat.
- Invalid citations, actions, or structured output fail closed and are not
  shown as trusted assistant output.
- Retries remain bounded and `clientMessageId` prevents duplicate user or
  assistant messages.

## Required Follow-On Slices

1. `AI-RAG-01` versioned OpenSearch vector foundation.
2. `AI-RAG-02` source ingestion, embedding, update, and deletion pipeline.
3. `AI-RAG-03` filtered `KnowledgeRetriever`.
4. `AI-CS-01A` agent persistence and Flyway migrations.
5. `AI-CS-01B` authenticated session APIs.
6. `AI-CS-01C` hybrid-RAG orchestration.
7. `AI-CS-01D` existing chat UI activation.
8. `AI-CS-01E` evaluation and rollout.

LangChain remains deferred until the baseline customer-service path is
measurable. No follow-on slice may weaken source filtering, precedence,
authorization, citation validation, action allowlisting, retention, or outage
isolation.

## Acceptance Criteria

- Supported knowledge sources and authoritative owners are explicit.
- Structured and vector source precedence is deterministic.
- Chunk metadata and mandatory visibility filters are defined.
- `getListing` and `retrieveKnowledge` have strict read-only boundaries.
- The assistant response includes validated resolution, source, and action
  fields.
- Stale, conflicting, invalidated, deleted, and unavailable-source behavior is
  defined.
- Agent schema ownership and Flyway migration responsibility are decided.
- Message, audit, vector, provider, and evaluation privacy rules are defined.
- Top-level MVP and detailed AI documents use the same hybrid-RAG contract.
- No runtime code, dependency, migration, environment configuration, or live
  provider request is introduced.
