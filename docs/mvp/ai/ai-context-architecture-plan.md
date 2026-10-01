# Marketplace Agent V2 context architecture plan

Status: **proposed architecture for future milestones**. CTX-00 and CTX-01 are implemented locally on `codex/context-management`; this document does not imply deployment or approval of later CTX slices.

Related approved work: AI-DISC-AGENT-V2-PLAN-01, AI-DISC-AGENT-V2-CONTEXTUAL-RESPONSE-01, AI-DISC-AGENT-V2-SELLER-CONTEXT-03, AI-POL-00, AI-COM-00/01, AI-CONF-01, AI-HELP-01, and AI-RAG-00/03. Future CTX IDs below are planning labels, not approved delivery slices.

## 1. Executive summary

Keep the model-first, five-decision ReAct loop and its existing policy, confirmation, SSE, and Java service boundaries. Introduce an Agent-owned context manager that assembles a typed, versioned packet for **each model decision**. Recent messages supply recency; durable structured state supplies continuity; optional semantic retrieval supplies relevant older history; approved knowledge retrieval supplies documentation; Skills supply procedure; owning Java services supply current commerce facts. Context is evidence for planning, never authorization or transactional truth.

MySQL remains sufficient to reconstruct every critical session and turn. Redis is a later, optional materialized hot snapshot and coordination accelerator. Separate Agent-owned OpenSearch memory and episode projections are later, rebuildable retrieval aids; they must not be mixed into the existing public listing knowledge index or treated as a prerequisite for core commerce. Add them only after measured value, privacy controls, and source deletion are proven. Do not enlarge the raw message window as the long-session solution.

## 2. Current-state architecture and evidence

- V2 is a parallel, default-off route with its own session type and generic message contract; legacy discovery remains separate ([plan](ai-disc-agent-v2-parallel-orchestrator-plan.md), [API contract](../api-contract.md)).
- The Agent Service owns agent_sessions, agent_messages, agent_invocations, and agent_tool_calls. V17 reuses those tables for V2, with workflow and pending-interaction state in preference_state_json ([V17](../../../agent-service/db/migration/V17__create_parallel_marketplace_agent_v2_boundary.sql), [database contract](../database.md)).
- A turn first stores a USER row and PENDING invocation with unique client-message identity and request hash. The successful assistant row and SUCCEEDED invocation commit together; a failed turn may retain a guarded PARTIAL assistant and response-only Retry ([persistence](../../../agent-service/src/msb_agent_service/agent_persistence.py), begin_invocation and complete_invocation).
- V2 loads 100 messages, excludes the current USER and prior out-of-scope pairs, then takes at most 12 recent pairs from that page. It derives the last result-bearing assistant attachment set and up to 12 persisted safe observations; the orchestrator sends up to five observations to each decision ([service](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/service.py), history load and context helpers; [orchestrator](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/orchestrator.py), AgentContext construction).
- AgentContext contains current message, recent messages, referenced listings, observations, pending interaction, active workflow, scope result, and compact or active Skill information. The provider serializes it into one request, with provider storage and truncation disabled ([schemas](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/schemas.py), AgentContext; [provider](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/provider.py), decide).
- Runtime tool schemas come from the V2 registry; load_skill is an optional model decision that reveals one full Skill and narrows its allowed tools. The global model-decision cap is five ([skill registry](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/skill_registry.py), [orchestrator](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/orchestrator.py)).
- The current retrieve_help tool reads bounded, approved docs/help articles through a local retriever, behind its own flag. The Agent-owned OpenSearch knowledge projection currently serves approved listing/category material through separate retrieval adapters; broader policy/FAQ source ownership is not established by that projection ([AI-HELP-01](ai-help-01-public-help-agent-integration.md), [architecture](../architecture.md)).
- API Stop tracks active V2 stream tasks in a process-local map and reconciles against durable invocation status. Existing invocation PENDING/SUCCEEDED/FAILED is retry and audit state, not a durable per-session processing queue ([API](../../../agent-service/src/msb_agent_service/api.py), V2 stream and Stop routes; [service](../../../agent-service/src/msb_agent_service/marketplace_agent_v2/service.py), Stop).

These are source-level findings, not a claim about deployed flags or production traffic.

Selected code anchors for review: service.py:184–254 and 1147–1279 (turn acceptance, history, references, observations); agent_persistence.py:655–812, 954–1114, and 1485–1564 (idempotent invocation, terminal commit, ascending history page); schemas.py:930–947 (bounded packet shape); orchestrator.py:745–830 and 1115–1174 (observation/message windows and five decisions); provider.py:215–265 (serialized context, disabled storage/truncation); skill_registry.py:125–149 (eligible Skills and compact selection); confirmations.py:536–698 (owned single-use consume); api.py:254, 1146–1203, and 1312–1334 (process-local stream task and Stop).

Existing tests reviewed include test_marketplace_agent_v2_service.py:568 (latest result-bearing set), :1072 (out-of-scope pair exclusion), :1122 (pending confirmation), :1314 (idempotent replay), :1426 (response-only Retry); test_marketplace_agent_v2_skills.py:155 and :224 (Skill integration and policy); test_marketplace_agent_v2_confirmation_integration.py:196 (concurrent consume); test_agent_persistence_integration.py:102 and :177 (actor isolation and request-hash retry). They do not constitute a >100-message context or cross-worker ordered-turn acceptance test.

## 3. Current limitations and known defects

1. **Oldest-page defect (resolved locally by CTX-00):** V2 formerly asked for 100 messages without a cursor while shared history ordered ascending, freezing context on the first page after 100 rows. The V2 latest-context read now selects the newest prior rows and returns them chronologically without changing the shared forward history API. This remains a bounded 100-row window, not long-term recall.
2. Active goals and ordinary constraints are inferred from a bounded transcript; only specific workflow and pending state survive outside it. There is no general durable fact supersession or episode retrieval.
3. No application-owned input-token selection/compaction precedes the provider's truncation-disabled request. The 12-message count bounds rows, not their combined token cost.
4. Same-session messages have no durable sequence/claim processor. A second distinct client message can begin while the first is still producing model/tool effects; a local active-stream map does not coordinate across workers.
5. Existing OpenSearch knowledge and local help retrieval are distinct capabilities. Neither is a private, actor-scoped semantic-memory index. Redis context caching and Agent session leases are not in the V2 runtime.
6. Tool audit, confirmations, stable commerce action keys, and response reconciliation already exist. New turn scheduling must preserve those guarantees instead of duplicating or weakening them.

## 4. Design goals

- Correct latest-turn context after arbitrarily long sessions; preserve early active constraints until changed or expired.
- Deterministic, actor-safe references and source provenance; current owning-service validation before claims or mutations.
- One ordered processor per session, concurrent processing across sessions, crash recovery, and no stale-worker commits.
- Bounded per-decision input with an explainable inclusion and drop trace.
- Reconstructable state without Redis/OpenSearch; graceful degradation for optional retrieval.
- Default-off, independently gated milestones with regression and failure evidence before rollout.

## 5. Non-goals

No new agent framework, microservice, vector database, graph store, blanket Kafka turn pipeline, new provider conversation storage, cross-user profiling by default, or public API/commerce behavior in this design document. No model-controlled save_memory tool, raw chain-of-thought storage, or authorization from memory. A later approved slice may change a public queued-turn status, but this plan does not define or activate one.

## 6. Target architecture

    Authenticated user
        -> Agent API: persist actor-owned turn in MySQL
        -> ordered session processor (durable claim/fence; optional Redis lease)
        -> Context Manager
             -> MySQL exact state + latest committed messages
             -> optional Redis versioned hot snapshot
             -> optional OpenSearch actor-scoped memory/episodes
             -> approved help/knowledge retriever
        -> Context Packet for this model decision
        -> model + optional Skill load + controlled ReAct
        -> policy-validated tool
        -> owning Java service for current facts/mutation
        -> durable outcome, context patch, version, audit
        -> invalidate/rebuild Redis; project eligible memory
        -> next decision or next queued turn

The context manager is inside agent-service. Its data-access adapters cannot query another service's schema. The orchestration policy and runtime registry continue to decide which tools are executable.

## 7. Component responsibilities

| Component | Responsibility | Boundary |
| --- | --- | --- |
| Turn repository/processor | Actor-bound accept, sequence, claim, heartbeat, terminal state, recovery | Short MySQL transactions; no DB lock during model/tool I/O |
| Context repository | Latest committed transcript, exact state, provenance, versions | Agent-owned MySQL only |
| Context cache | Optional versioned materialized session snapshot and rebuild single-flight | Disposable Redis; never pending confirmation authority |
| Memory retriever/projector | Optional actor-filtered semantic and episode candidates | Separate derived OpenSearch indexes |
| ContextBuilder | Gather, normalize, dedupe, select, compress, budget, trace | Builds typed packet per model decision; does not grant tools |
| Orchestrator/Skill registry | Model decision, Skill selection, observation loop, strict tool subset | Existing five-decision and policy budget |
| Tool registry/policy | Validate proposed action and fetch/execute against owner | Existing allowlist, actor identity outside model arguments |
| Owning Java services | Prices, inventory, cart, order, payment, return, seller state | Only source of current domain truth |

## 8. Context lifecycle

Accept a client message idempotently; sequence it in the Agent schema; process only after all earlier turns are terminal or explicitly reconciled. Build from a stable **as-of turn boundary** so the processor for turn N never sees queued USER N+1 as prior dialogue. For each ReAct decision, retain the same committed base version while adding only validated current-turn observations and the selected Skill. After a tool observation or Skill load, rebuild the packet and token allocation for the next decision. Persist the terminal answer and structured context patch with provenance under a conditional version/fence; invalidate the hot snapshot. Project eligible semantic memory only after durable commit. Exact pending confirmations/workflows remain authoritative in their existing rows/JSON until an approved migration moves them.

The post-turn patch is a separate internal stage, not a ReAct tool. Deterministic updates handle exact IDs, ordinal result-set bindings, confirmed tool observations, and explicit structured user corrections. A model-assisted extractor may propose allowlisted fact operations for less structured language; strict schema, provenance, scope, conflict, and retention validation precede commit. Failure to extract a noncritical preference does not turn a successful user response into failure; it emits a safe diagnostic and leaves the transcript recoverable.

## 9. Turn lifecycle

    request + clientMessageId
      -> MySQL USER + invocation identity + ordered PENDING turn
      -> claim oldest eligible turn with monotonically increasing fence
      -> PROCESSING (short transaction)
      -> build context through prior committed turn
      -> model/Skill/tool loop (no held MySQL transaction)
      -> reconcile uncertain external effects if necessary
      -> conditional terminal commit + context revision increment
      -> COMPLETED | WAITING_CONFIRMATION | FAILED_RETRYABLE | FAILED_FINAL
      -> release owned lease; schedule next PENDING turn

The existing invocation result_status remains the public retry/assistant linkage. A future turn lifecycle augments it; do not reinterpret PENDING as a queue state. WAITING_CONFIRMATION means the assistant turn is terminal and the durable confirmation awaits a **new** user action; it must not block later informational turns. Stop and response-only Retry retain their exact existing identity and history semantics. Queued-message acceptance and streaming need a separately reviewed API/UX contract before enablement; a disconnected client does not delete an accepted turn.

## 10. Storage responsibility matrix

| Context kind | Authoritative source | Optional accelerator/projection | Freshness rule |
| --- | --- | --- | --- |
| Session owner, messages, turn order, goal, explicit constraints, reference IDs, workflow/confirmation, provenance, version | Agent MySQL | Redis hot snapshot | Exact committed revision |
| Older semantic/episodic candidates | Agent MySQL source messages/facts/episodes | Agent-private OpenSearch indexes | Active source version and retention eligibility |
| Public help/knowledge | Approved document/source owner | Existing help retriever or approved Agent knowledge index | Published source version and effective period |
| Listing, cart, checkout, order, return, payment, business state | Owning Java service | Existing service-owned caches only | Fresh read/revalidation at claim or mutation |
| Prompt/Skill/tool definitions | Versioned application code and runtime registry | None required | Current enabled registry and policy version |

MySQL preserves context; Redis accelerates it; OpenSearch finds optional older context; Java services verify domain reality.

## 11. MySQL architecture

Reuse agent_sessions, agent_messages, agent_invocations, agent_tool_calls, and agent_confirmations. Keep preference_state_json as the current V2 workflow/pending decoder until a forward migration explicitly separates new context state. Future durable additions may include an ordered turn/claim record, one compact session-context aggregate, fact rows with provenance and supersession, entity/result-set reference metadata, and episode source metadata. Do not duplicate the immutable transcript or confirmation authority in each table.

The existing agent_sessions.optimistic_version already changes on several session writes. Before adding a second context_version, determine whether every context-affecting write can increment this field in one transaction; if not, add one clearly owned context revision and document how it relates to optimistic_version. Each context packet records the source revision and last committed turn sequence. Turn claim/fence and terminal/context writes must use conditional updates; a zero-row update means the worker lost authority and must reconcile, never overwrite. Migration details and indexes belong to their later slices.

## 12. Redis architecture

Introduce Redis only after measuring ContextBuilder read costs or when distributed coordination needs it. Cache one bounded, actor/session-scoped materialized snapshot: exact active state, recent message IDs/bodies allowed by retention, safe observation references, active ordered result set, source revision, schema version, and expiry. Never cache full transcript, private tool payloads, payment data, or entire orders. Cache-aside reads validate snapshot owner, schema, revision, and expiry; a miss or mismatch reconstructs from MySQL. Invalidate after durable commit, use a short configurable TTL, and use an owner-token single-flight rebuild to bound stampedes. Do not hard-code a 15–30 minute TTL before workload measurement. [Redis cache-aside guidance](https://redis.io/docs/latest/develop/use-cases/cache-aside/) supports this pattern.

An optional per-session renewable lease has a unique owner token, finite TTL, bounded renewal, and compare-owner release. It is an efficiency/coordination aid, not the correctness fence. Redis' own lock guidance calls out owner-safe release and fencing for long-running work ([Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)). If Redis is unavailable, use the durable MySQL claim/fence path at reduced throughput or pause new processing if that fallback is not implemented; never run two unfenced processors because the cache is down.

## 13. OpenSearch architecture

Keep public listing/category knowledge aliases separate from proposed private Agent memory and episode indexes. The latter require a distinct Agent-owned, versioned mapping/alias, source message or fact ID, actor ID, optional session ID, memory scope/type/status, source revision, provenance, created/expiry times, sanitized text, and optional embedding. Exact actor/session/status filters are mandatory **inside retrieval and again during local hit validation**; semantic similarity never supplies ownership. Hybrid lexical/vector retrieval may score relevance, recency, active goal, entity match, and confidence, with a bounded candidate set. Exact ranking weights require evaluation rather than an invented formula. OpenSearch supports filtered vector retrieval, but filter placement/engine behavior must be validated against the deployed version ([OpenSearch filtered vector documentation](https://docs.opensearch.org/latest/vector-search/filter-search-knn/index/)).

Index only promoted allowlisted facts or sanitized episodes, not every message or raw commerce payload. MySQL holds source/projection metadata and deletion intent; an idempotent, retryable Agent-owned projector follows commit. Search hits are checked against durable active/superseded/deleted status before model inclusion. Index lag or outage reduces recall, never changes transactional correctness. Documentation RAG stays on its approved public-source path; private memories must not enter that alias.

## 14. Authoritative Java service boundary

Context IDs help resolve what the customer refers to; they do not prove ownership, eligibility, price, inventory, order status, or payment state. Every current-state answer or consequential action continues through an actor-scoped allowlisted tool, with owning-service revalidation and existing confirmations. A stored order or listing reference can be used to request a read, never to assert current status. Tool timeouts on mutations are outcome-unknown until an idempotency-key lookup or owning-service reconciliation proves the result. Individual trades and business orders remain distinct state machines.

## 15. ContextBuilder and typed ContextPacket

ContextBuilder owns **dynamic context selection**, not system policy, runtime tool registration, or authorization. A packet is an immutable per-decision snapshot with actor/session and turn IDs, committed context revision/cutoff, active goal and facts, selected entity/result-set references, pending workflow/confirmation display-safe metadata, recent messages, selected safe observations/memories/episodes/knowledge citations, optional active Skill, exposed registry tool names/schema digest, source provenance, freshness, token counts, and drop reasons. IDs needed for validation stay in trusted backend context; the provider receives only its bounded, display-safe projection.

The provider may continue to serialize one structured packet into a Responses input. The system prompt remains separately versioned; the provider keeps store=false and truncation=disabled. The packet is rebuilt after each accepted observation or Skill load, within the existing five-decision budget. A context trace records stable IDs/hashes and selection reasons, not hidden prompts or private raw payloads.

## 16. Gather, normalize, deduplicate, select, compress

    GATHER candidates from owner-scoped durable/current/derived adapters
       -> NORMALIZE typed identity, provenance, scope, source version, freshness
       -> DEDUPLICATE by canonical fact/entity/source identity and supersession
       -> SELECT mandatory facts first, then relevant optional candidates
       -> COMPRESS older prose/episodes only, preserving structured values
       -> TOKEN BUDGET including policy, Skills, tools, output/next-step reserve
       -> ContextPacket + sanitized selection trace

Protected inputs are the current message, applicable safety/policy instructions, exact active workflow/confirmation state, current explicit constraints, and validation identities. Recent dialogue and active result-set references normally follow. Optional observations, older memories/episodes, and RAG chunks compete for remaining space by current-goal/entity relevance, freshness, confidence, and diversity. Exclude superseded, deleted, cross-actor, stale, redundant, or unsupported candidates before scoring. If protected context alone exceeds budget, fail safely or request a focused continuation; never let provider truncation silently remove it.

## 17. Active goal and structured memory

Start with one session active goal: domain, intent, object, status (ACTIVE/SUSPENDED/COMPLETED), concise non-authoritative summary, source message, version, and timestamps. Store exact constraints separately with typed key/value/unit, source, scope, and supersession. The laptop example resolves to product=laptop, maximum price=1500, condition=new, RAM minimum=16 GB after the newer explicit budget correction supersedes 1200. Goal changes or a clear cancellation close/suspend the old context; they do not erase its auditable source transcript. Do not turn a transient exploratory phrase into a durable preference automatically. A later goal stack needs a demonstrated return-to-goal requirement and its own acceptance suite.

## 18. Semantic, episodic, and workflow memory

Recent transcript remains raw and bounded. Structured session facts carry exact active state. Semantic memory is a selectively promoted, actor-scoped preference or constraint useful beyond its immediate turn; explicit user statements are stronger than inferred preferences. Episodic memory is a bounded summary of a completed span, with source range, topic/entity references, model/version, and no claim of live commerce status. Workflow memory remains in its existing durable confirmation or seller workflow owner and expires with that workflow. User-scope promotion is a separate opt-in policy decision; session scope is the default. MySQL provenance and status are authoritative even if OpenSearch carries a searchable copy.

## 19. Entity and result-set references

Persist a bounded, typed reference register for the active session: entity kind, opaque ID, source observation/message, ordinal/display order where applicable, result-set generation, selected-at time, and expiry/invalidation. The latest **displayed and validated** listing set is the sole source for “the second one”; older cards remain history. A result set is superseded by a new presented set, not merely by a background search. Cart-item and order references stay actor-bound and require current owner reads before use. An unambiguous ordinal can be resolved to a candidate ID deterministically, then passed to the model and policy validator; ambiguous “it” or stale references trigger one focused clarification. Reference resolution must not force a marketplace action or grant authority.

## 20. Same-session concurrency

Allocate a monotonic turn sequence in one short Agent MySQL transaction with the USER and invocation identity. Only the oldest eligible turn may claim PROCESSING; later accepted messages remain PENDING and do not enter its context. Different session IDs can process concurrently. A later turn builds from the preceding terminal state, including the previous assistant result and committed context patch. Failed/uncertain earlier mutations block dependent turns until reconciled or explicitly terminal; queueing does not mean automatically executing a contradictory later instruction against an unknown prior effect. Configure per-session/user pending limits and a user-facing queue/Stop contract in its implementation slice.

## 21. Lease, fencing, and context version

A durable claim row supplies a monotonically increasing fencing generation and lease deadline. Redis may mirror a renewable per-session lease to reduce contention; its token must be unique, renewed only by its owner, and released only if still owned. A worker verifies the MySQL claim/fence before each model decision and immediately before consequential I/O. Terminal answer, turn state, and exact context revision update commit conditionally on the same current owner, expected revision, and turn sequence. A stale worker cannot write assistant/context state after another owner advances. Fencing cannot undo an external call already in flight, so the owning service must deduplicate stable action keys and uncertain effects must be reconciled before advancing the queue. Lease expiry triggers recovery of the **same** turn first, not blind processing of the next turn. No MySQL transaction stays open during provider/tool work.

## 22. Idempotency and ownership of effects

Retain the existing unique (session, actor, clientMessageId) request hash, invocation identity, response-only Retry, audit sequence, durable single-use confirmation, and stable cart/checkout/cancellation/return action keys. New turn_id and claim generation are internal scheduling identities; do not replace existing public IDs. A tool_call_id or action key is derived once from the durable invocation, action stage, and validated arguments and is reused on retry. Agent Service deduplicates proposals/audits and reconciles responses; the owning Java service must enforce command idempotency and actor ownership at its API boundary. An Agent-only record cannot make a non-idempotent remote write exactly once. On timeout after mutation send, mark outcome uncertain, query the owning service by approved stable key/identity, and neither claim success nor send a new-key retry without proof. Preserve confirmation consumption semantics.

## 23. Token budget and compression

Keep provider truncation disabled. A configurable per-decision input ceiling reserves capacity for model output and subsequent ReAct observations; count or conservatively estimate system instructions, the active full Skill, tool schemas, packet JSON, and source excerpts before calling the provider. Allocate protected space to policy and exact active state; bound each optional category independently. Drop stale/duplicate episodes first, then low-relevance RAG/memory, older chatter, and low-value observations; retain recent user intent and references required for interpretation. Compress only older prose into source-linked summaries. Never compress exact IDs, units, hard constraints, workflow stage, confirmation state, or ownership into ambiguous prose. When a packet cannot fit protected content, return an explicit safe context-limit outcome. Record estimated versus provider-reported tokens and adjust the estimator offline.

## 24. Documentation RAG

Preserve AI-HELP-01's current approved docs/help source, local bounded retrieve_help tool, citations, and capability flag. A later OpenSearch documentation path must first establish public source ownership, versioning, publication/deletion, corpus quality, citation contract, and retrieval gate; do not silently relabel the existing listing/category index as a full policy corpus. An informational “How do returns work?” can use approved help/RAG, while “Return the second item I bought” needs actor-owned Order/Return reads and an approved confirmation path. Documentation passages are untrusted reference data and never override tool policy, source ownership, or customer-specific state.

## 25. Skills coordination

Retain the deterministic Skill registry and model-selected load_skill decision. The compact eligible Skill catalog fits the initial packet; selecting one consumes an existing model decision, after which ContextBuilder budgets its full instructions and the runtime registry exposes only the allowed enabled-tool intersection. A Skill can guide a procedure but cannot promote memory, authorize a tool, create a confirmation, or expand the five-decision budget. Tool schema hashes and Skill version enter the context trace so later debugging can explain exactly which instructions and capabilities were available. If a full Skill plus protected state does not fit, return a safe bounded outcome rather than silently clipping its instructions.

## 26. Failure and recovery model

| Failure | Required behavior |
| --- | --- |
| Redis miss/restart/down | Reconstruct from Agent MySQL; use durable claim/fence fallback or pause processing if not deployed. No lost state or unfenced double processor. |
| OpenSearch memory outage/lag | Omit optional old-memory/episode candidates and mark reduced recall; exact active facts and eligible commerce continue. A question requiring unavailable approved knowledge abstains. |
| Agent MySQL outage | Do not accept/execute a new durable turn or claim a mutation outcome. |
| Provider timeout/disconnect | Conditional terminal failure/partial assistant or recoverable turn state, preserving existing Stop/Retry contract. No fabricated completed answer. |
| Java read timeout | Bounded retry if safe, else explicit unavailable result; no facts from old memory substituted as current. |
| Java mutation timeout | Keep stable action key, mark outcome unknown, reconcile with owner; never retry under a new key. |
| Worker crash/lease expiry | Recovery worker atomically reclaims the oldest eligible turn with a higher fence; checks invocation, confirmation, tool audit, and owner effect before replay. |
| Stale cache/worker | Reject version or owner mismatch; rebuild cache or discard stale terminal write. |
| Memory extraction/index failure | Preserve committed response; queue/retry projection or skip noncritical extraction with metrics. |

Recovery is bounded and idempotent. It may not delete a committed USER row, consume a confirmation twice, or rewrite a successful assistant message. Existing partial/failed history remains readable while new turn statuses are introduced.

## 27. Backpressure and scaling

Limit pending turns per session and actor, global in-flight sessions, provider concurrency, tool concurrency, context-build time, cache rebuilds, and OpenSearch candidate counts. Tune from latency and load data, not a speculative 1,000-session target. Queue admission should be bounded and explicit; when full, decline **before** persisting an accepted turn or return a durable accepted/queued identity under an approved API contract. Prefer fair per-session scheduling and cancellation of unstarted turns to merging customer messages automatically, which could change intent and idempotency. A provider spike must not create unbounded workers. Kafka session-keyed scheduling is deferred until a measured need and a transactional outbox/deduplicated consumer contract justify it; partition order alone does not solve side-effect idempotency.

## 28. Privacy, isolation, and retention

Every Agent MySQL lookup binds authenticated actor and session; Redis keys are namespaced but a key name is never authorization; cache payloads carry owner and version checks. Private OpenSearch retrieval applies exact actor, permitted session/scope, status, and expiry filters **before scoring** and validates every hit again after retrieval. Separate indexes/credentials prevent public knowledge searches from reaching private memory. Memory extraction excludes credentials, payment details, addresses, unnecessary PII, hidden prompts, and private tool payloads. Default memory scope is session; user-scope promotion requires an explicit approved policy and deletion model. The existing 90-day inactive-session content purge and 365-day safe audit retention are the baseline ([database contract](../database.md)); no memory or episode may quietly outlive its source's approved retention. Conversation/account deletion writes durable tombstones, invalidates Redis, then retries exact OpenSearch deletion until reconciled; queries suppress tombstoned sources immediately, despite eventual physical deletion.

## 29. Observability

Record one safe context-build trace per turn/decision: actor/session opaque identifiers, turn/invocation ID, claim fence, source revision, packet/schema/policy/Skill/tool-registry versions, cache hit/miss/rebuild, candidate/selected/dropped counts per source, drop reasons, source versions, token estimate and provider usage, and stage latencies. Track queue age, lease renewal/loss, stale-write rejection, idempotency reconciliation, projection lag, token-limit failures, isolation-filter rejects, and final outcome. Use bounded low-cardinality metrics and structured logs with hashes/IDs where permitted; do not log raw messages, prompts, retrieved private passages, model reasoning, credentials, or payment data. Sample sanitized selection traces with access control and retention shorter than source content.

## 30. Developer context debugging

Extend the existing Agent CLI conventions with an actor-authorized or offline operator-only context-inspection command. It should render a **sanitized replay** of a chosen turn: source revision and cutoff, selected goal/facts/references, message IDs, memory/episode IDs and status, RAG source citations, Skill name/version, enabled tool names, per-category token counts, and exclusion reasons. It must not accept an arbitrary session ID as sufficient authority or dump private message bodies by default. Record packet/schema versions so a later code deployment can reproduce or explain an earlier selection; clearly mark any live-source recheck that cannot be replayed exactly.

## 31. Future testing and acceptance strategy

Start with a MySQL-backed >100-message latest-read regression, keeping history cursor behavior unchanged. Add deterministic unit/contract tests for context selection, provenance, supersession, entity ordinals, token dropping, Skill/tool-policy intersection, and source isolation. Integration tests exercise MySQL claim/fence, Redis hit/miss/stale/restart/lease-loss, OpenSearch actor-filter and tombstones, owning-service idempotency/reconciliation, and SSE/history/Stop/Retry. Fault injection covers provider timeout, Java read/mutation timeout, process crash after remote effect, Redis loss, and index lag.

The CTX-RC fixture should run at least 200–300 turns, placing preference/goal changes around turns 5, 40, 99, 101, 160, and 220. It tests return to an old topic, superseded constraints, latest and old result sets, “second one”/“it”/“that order,” out-of-scope detours, relevant versus irrelevant memories, documentation versus private reads, token pressure, two simultaneous messages, expired lease/stale worker, and cross-actor/session denial. Capture the exact packet IDs, source revisions, selected/drop reasons, model decision count, tool execution/audit, SSE order, and persisted answer. Use offline fakes for reproducibility and real MySQL/Redis/OpenSearch integration where practical; do not infer production latency or quality from fakes.

## 32. Incremental rollout plan

Each proposed ID needs its own approved roadmap entry, default-off gate where behavior changes, migration and API review where required, and rollback evidence. No milestone automatically enables the next. Recommended sequence:

| Milestone | Goal, scope, dependencies, key behavior | Main risk and required test | Explicitly out of scope |
| --- | --- | --- | --- |
| CTX-00 | Latest-context read after existing V2 persistence; return newest committed rows in chronological order without changing forward history pagination. Include current-turn cutoff. | Ordering/cursor regression; real MySQL >100 rows, queued-future exclusion, latest cards/observations. | Memory, Redis, new public API. |
| CTX-01 | Typed ContextBuilder/Packet and sanitized trace around existing sources; depends on CTX-00; preserve planner inputs. | Prompt drift; golden packet, five-step, Skill/tool and SSE tests. | New facts or retrieval sources. |
| CTX-02 | Deterministic input-token allocator; depends on CTX-01; protects state and bounds optional sections. | Underestimation or over-dropping; oversize and provider-limit tests. | Summary generation or memory promotion. |
| CTX-03 | Durable ordered turns, claims, fencing, context revision, stable action keys, and recovery; depends on existing invocation/confirmation semantics and CTX-01/02 packet boundaries. Establish conditional commits before adding durable context writers. | Side-effect race and API queue semantics; concurrent MySQL, crash, lost response, Stop/Retry, stale-worker tests. | Kafka turn transport and broad commerce rewrite. |
| CTX-04 | One active goal and typed session facts with provenance/supersession; depends on CTX-03 turn ownership and conditional context revision; forward Agent migration only if session JSON cannot meet transactional/retention needs. | False inferred preferences or stale commits; correction, restart, retention, actor-isolation tests. | User-wide profile memory. |
| CTX-05 | Durable entity/result-set register; depends on CTX-04 exact state and CTX-03 fence; bind ordinals to latest displayed set and refresh before action. | Wrong referent; multi-set, stale/ambiguous pronoun and Product/Order recheck tests. | Model-independent intent routing. |
| CTX-06 | Optional Redis hot snapshot and renewable coordination lease; depends on durable CTX-03 fallback, versioned packet, and the exact state selected for caching. | Stale/foreign cache, stampede; miss/restart/expiry/lease-loss tests. | Redis as queue or confirmation authority. |
| CTX-OBS | Context metrics and sanitized replay CLI; start with CTX-01, deepen as each source arrives. | PII leakage/high cardinality; logging redaction and trace-access tests. | Raw prompt dumps or production user profiling. |
| CTX-07 | Allowlisted session semantic-memory projection/retriever in separate OpenSearch alias; depends on MySQL provenance, retention, deletion, and measurement. | Cross-actor retrieval and stale memory; filter, tombstone, lag, hybrid relevance tests. | Automatic user-scope memory. |
| CTX-08 | Bounded episode compaction and projection; depends on CTX-07 deletion/trace path; retain raw recent turns. | Summary invention; source-linked fidelity and deletion/rebuild tests. | Replacing exact active facts with prose. |
| CTX-09 | Multi-source normalize/dedupe/select across current packet and optional memory; depends on CTX-07/08 and CTX-02 budget. | Relevant fact dropped/noise selected; long-session ranking and ablation tests. | Unrestricted model-as-selector. |
| CTX-10 | Coordinate approved help/RAG and progressive Skill load under one budget; depends on CTX-09; reuse retrieve_help and registry. | Wrong source authority/tool exposure; citation, private-read, Skill-policy tests. | New general policy corpus without source contract. |
| CTX-RC | Long-session and concurrency/recovery acceptance gate; depends on relevant enabled milestones. | False confidence from fakes; 200–300-turn plus real dependency failure evidence. | Automatic rollout or production activation. |

Readiness gates should include baseline V2 regression, default-off behavior, no legacy discovery change, rollback to the prior context path, and measured cost/latency/quality. Later OpenSearch/Redis slices may be deferred indefinitely if the accepted workload does not justify them.

## 33. Risks and trade-offs

- Redis improves repeated reads but adds coordination complexity; a durable MySQL fence is required regardless. Cache only after workload evidence.
- Private semantic memory improves old-context recall but adds privacy, deletion, index freshness, and evaluation burden. Exact session facts solve the first correctness problem more cheaply.
- Serializing a session protects context and mutations but adds queue latency and a user-visible waiting state requiring contract design. Do not hold DB locks across network calls.
- Strong deterministic selection protects exact facts but can suppress nuance; preserve bounded raw recent dialogue and evaluate false exclusions.
- Post-turn model-assisted extraction can improve recall but adds cost and false facts. Gate it by allowlisted categories and explicit-source precedence.
- Existing provider/tool policy has some trusted-context forced recovery shapes. ContextBuilder must preserve the current policy behavior during extraction; any planner-policy refactor is a separate approved slice.

## 34. Open questions for later slice approval

1. Which explicit statements, if any, may become USER-scope memory, and what user-facing controls/retention apply?
2. Can agent_sessions.optimistic_version become the sole context revision once all context-affecting writes use it, or is a separate aggregate revision needed?
3. Which queued-turn acceptance/streaming/status API is acceptable to Angular clients, including Stop before processing?
4. What owner-service idempotency/reconciliation endpoints exist for every approved mutation, especially a timeout after send?
5. What measured concurrency, cache-hit potential, token pressure, and long-session frequency justify Redis or private OpenSearch?
6. What source-owner publication path supplies general policy/FAQ documentation beyond the current local help corpus and listing/category knowledge?
7. What is the approved private-memory deletion SLA and how should an account deletion invalidate projections before physical cleanup?

## 35. Recommended first implementation milestone

Implementation checkpoint: **CTX-00** adds an actor/session-bound latest-context persistence method with a current-turn cutoff and chronological output; the real MySQL >100-row regression passes. **CTX-01** adds the typed in-process turn/decision packet while preserving the provider-facing context and ordered tools; its V2 and MySQL regressions pass locally. Neither adds new storage or long-term memory. All later CTX milestones in this document remain proposals.
