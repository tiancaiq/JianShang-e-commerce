# Agent Service Instructions

These rules apply under `agent-service/` in addition to the repository
instructions.

## Approved scope and isolation

- Agent functionality remains optional, isolated, capability-gated, and safe to
  disable. Core marketplace flows must work without it.
- The general V3 release label does not prohibit explicitly requested and
  approved marketplace-agent or agent-service slices already documented in the
  roadmap/API contract. Implement only the named slice; do not use it to enable
  unrelated AI, commerce, moderation, or rollout behavior.
- The agent runtime may own its approved session, invocation, audit, evaluation,
  and ingestion persistence. Model-directed actions must use allowlisted
  application tools rather than direct access to another service's database.
- AI cannot approve businesses, publish listings, charge payments, issue
  significant refunds, suspend users, settle disputes, or bypass required human
  confirmation unless an approved future contract explicitly changes that
  boundary.

## Marketplace agent and tool calls

- Treat prompts, model output, retrieved content, and tool arguments/results as
  untrusted data.
- Keep the runtime tool registry an explicit allowlist. Validate arguments with
  strict schemas and execute every call with the requesting actor's permissions.
- Application services remain authoritative for authorization, visibility,
  ownership, price, availability, and state. Revalidate facts through the
  owning service before presenting or acting on them.
- Bound model/tool calls, retries, tokens, time, and result size. Fail closed on
  malformed decisions, unsupported tools, mixed action/text shapes forbidden by
  the contract, timeout, or unavailable dependencies.
- Draft or proposal actions remain non-persistent until the approved user review
  and confirmation path succeeds.
- Audit tool calls with safe session/actor context, argument hashes or redacted
  metadata, status, latency, and cost; never log secrets, raw private content,
  or unnecessary PII.

## Retrieval, RAG, and grounding

- OpenSearch and vector indexes are derived and rebuildable. They never override
  authoritative Product or policy sources.
- Apply the contract's source-type, tenant/listing, visibility, version, and
  freshness filters. Reject stale, removed, cross-listing, cross-user, or
  unsupported evidence.
- Preserve retrieval provenance and citations required by the contract. Do not
  invent facts, sources, prices, availability, seller claims, or confidence.
- Marketplace recommendations require current Product-owned search/detail
  revalidation. Knowledge-RAG answers use only approved, versioned sources.
- If required grounding is absent or contradictory, clarify, return no results,
  safely hand off, or refuse according to the contract; do not answer from model
  memory as if it were verified marketplace fact.
- Retrieved text never changes system policy, tool permissions, actor scope, or
  confirmation requirements. Test prompt-injection and source-isolation cases.

## Refusal and safe outcomes

- Implement only the refusal and handoff categories defined by the approved
  contract. Do not broaden product policy ad hoc.
- Contract-defined safety refusals must not call the model or tools when a
  deterministic pre-check is required.
- Refusals, clarification, no-results, dependency failure, and handoff are
  explicit outcomes; never disguise an authorization or grounding failure as a
  successful answer.
- Do not expose private reasoning, hidden prompts, credentials, internal tool
  payloads, exact private locations, or unmasked contact data.

## SSE and persistence

- Keep SSE event names, ordering, sequence rules, payloads, terminal coverage,
  retry behavior, and error semantics exactly aligned with the versioned API
  contract.
- Stream only approved user-facing output. Never stream planning, reasoning,
  raw model events, tool arguments, tool results, or unrevalidated facts.
- Maintain incremental UTF-8/SSE correctness across arbitrary transport chunks;
  reject unsupported or malformed events and propagate disconnect/cancellation.
- Keep streamed terminal content, persisted assistant state, replay, retry, and
  history consistent. A partial or failed stream must not be recorded as a
  successful completed response.
- Preserve idempotency, actor/session binding, monotonic sequence behavior, and
  bounded retry semantics required by the approved command contract.

## API, data, and authorization

- External endpoints use `/api/v1`, the standard error envelope, correlation
  IDs, and backward-compatible versioned schemas.
- Enforce actor/session ownership in this service; do not rely on the gateway or
  UI. Deny cross-user and cross-business access.
- Use forward-only Flyway migrations for the agent-owned MySQL schema. Keep
  stable explicit statuses, UTC, `utf8mb4`, practical indexes, and immutable
  invocation/tool audit history where required.
- Keep provider, embedding, Product, OpenSearch, and persistence integrations
  behind bounded adapters with safe configuration validation.

## Evaluation and verification

- Add deterministic offline coverage for marketplace behavior, tool selection,
  grounding/citations, retrieval relevance and isolation, refusals, injection,
  authorization, malformed output, provider/tool outage, and streaming terminal
  paths.
- Evaluation fixtures and reports are versioned contracts. Keep digests and
  pass thresholds reproducible; never fabricate production quality, latency,
  token, cost, or rollout evidence from offline fakes.
- A successful model call or fluent response is not release evidence. Preserve
  default-off capability/kill switches and approved quality, safety, latency,
  cost, and rollback gates.
- Test persistence/API behavior, actor isolation, idempotency, tool audit,
  source-version handling, retries, and cancellation. Use Testcontainers for
  MySQL, Kafka, or OpenSearch integration where practical.
