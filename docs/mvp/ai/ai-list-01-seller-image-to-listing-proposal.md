# AI-LIST-01 Seller Image-To-Listing Proposal

Status: `AI-LIST-01A` contract/offline orchestration, `AI-LIST-01B`
offline multimodal provider adapter binding, and `AI-LIST-01C` authorized
Product listing-media tool adapter implemented and verified on 2026-07-20.
`AI-LIST-CLEAN-P0-01` cleanup was completed and verified on 2026-07-20.
Runtime/API/UI activation remains deferred.

Release: V3.

## Requirement And Owner Mapping

This slice implements the proposal-only foundation for `AI-03` and the
seller-image direction in `AI-00`.

- Product Service remains authoritative for listing ownership, draft
  eligibility, listing/media versions, media authorization, and every write.
- `AI-LIST-01C` adds a distinct service-authenticated Product read boundary;
  browser seller-preview URLs are never followed or exposed to Agent Service.
- Agent Service owns only the strict proposal schema, offline orchestration,
  safe invocation metadata, and evaluation regressions.
- A seller must later review and explicitly apply selected fields through the
  existing versioned listing update contract. Nothing in this slice calls that
  contract.

## AI-LIST-01A Internal Contract

`ListingContentProposalOrchestrator` is constructed with:

- one `ListingDraftMediaTool` that accepts trusted actor, listing, selected
  media, and correlation context and returns only an `OWNED_DRAFT` media
  context;
- one replaceable `ListingVisionProvider` transport;
- an idempotent replay store;
- a safe audit sink and low-cardinality metrics.

Both interfaces are Agent-owned Python protocols. They expose no LangChain,
OpenAI, storage, Product, API, or persistence types. The module is not wired
into FastAPI or runtime configuration, and `enabled` defaults to `false`.
Tests use only injected fakes.

The strict `ai-list-proposal-v1` output contains:

- an optional bounded suggested title with confidence and evidence IDs;
- an optional bounded suggested description with confidence and evidence IDs;
- at most three bounded category labels with confidence and evidence IDs;
- bounded observations that reference only the selected media IDs;
- explicit unknown fields;
- fixed `proposalOnly=true` and `requiresSellerConfirmation=true` markers.

Seller identity, price, exact location, quantity, condition, negotiability,
policy claims, contact data, brand, model, authenticity, and safety always
remain explicitly unknown. Missing title, description, or category output is
also marked unknown.

## Media And Safety Limits

- Only JPEG, PNG, and WebP are accepted.
- At most four selected images are processed.
- Each image is limited to 10 MiB and the request total to 20 MiB.
- Byte length and SHA-256 digest must match the authorized media reference.
- Returned listing and ordered media IDs must exactly match trusted command
  context.
- Media bytes are passed only as an internal `untrustedMedia` value and never
  appear in output, audit metadata, metrics, or logs.
- Prompt-injection signals or instruction-like output fail closed.
- Contact, URL, secret, exact-address, coordinate, and phone/email patterns are
  redacted before proposal output.
- Unsupported condition, policy, price, location, quantity, negotiability,
  identity, authenticity, and safety assertions fail closed.

The allowlist contains only the owned-draft media read. There is no general
HTTP, database, storage, Product write, browser, file-search, shell, MCP, or
listing mutation capability.

## Replay, Failure, And Audit Behavior

The replay key is actor, listing, and client request ID. Reusing the same key
and selected-media fingerprint returns the existing proposal without another
media or vision call. Reusing it with different media is a stable conflict.
Failed attempts are removable and may be retried safely.

Disabled, unavailable/cross-actor, unsupported-media, rejected, timeout,
provider-outage, replay, conflict, and success outcomes have stable bounded
classifications. Audits contain only SHA-256 actor/listing/request/correlation
identifiers, versions, media count, latency, token counters, and deterministic
zero-cost metadata. Metrics use fixed result/rule labels only.

## Verification

- Focused proposal contract/orchestration/evaluation suite: 21 passed.
- Full default Agent suite: 217 passed with 14 expected opt-in
  MySQL/OpenSearch integration skips.
- Python compilation and strict schema construction passed.
- Scoped diff/whitespace, dependency, credential-pattern, and unsafe-log
  checks passed.
- No provider, network, external OpenSearch, database, or Product request
  occurred.

## Deferred Dependencies

- No live multimodal provider adapter is bound to this orchestrator.
- No authenticated API, gateway route, seller UI, persistence, runtime flag,
  cohort, or rollout exists.
- Category IDs/attributes, alt text, and application of selected fields remain
  later slices. This slice returns category labels only and performs no write.
- Production vision quality, latency, token usage, pricing/cost, privacy
  review, and rollout approval remain unknown and blocking.

`AI-LIST-01A` advances the AI lane from 0/3 to 1/3. No successor starts
automatically.

## AI-LIST-01B Offline Multimodal Provider Adapter Binding

Completed on 2026-07-20 without expanding the existing provider abstraction.
`ResponsesListingVisionAdapter` reuses the generic typed
`OpenAIProvider.customer_service_answer` operation, which already accepts
structured multimodal input items, a strict Pydantic result type, bounded
output tokens, `store=false`, and no tools.

Binding rules:

- fixed trusted instructions are passed through the provider instruction
  channel, while media manifests and images are placed only in untrusted user
  content;
- every image is encoded as a data URL only after its MIME type and SHA-256
  digest are revalidated;
- the provider boundary accepts at most four images, 32 KiB per image and
  40 KiB total, with a 60,000-byte serialized-input ceiling, a conservative
  15,000-token context estimate, 400 default output tokens, and a four-second
  deadline;
- those provider-context limits are intentionally stricter than the 01A
  authorized-media limits and fail closed before any provider call;
- output remains `ListingVisionCandidate`, preserving 01A evidence IDs,
  explicit unknown fields, proposal-only semantics, and seller confirmation;
- invalid structured output, invalid usage metadata, rate limit, timeout,
  provider outage, cancellation, and context rejection map to stable safe
  outcomes without provider body leakage;
- cancellation is recorded through the existing hashed proposal audit and
  removes the in-flight replay entry so the same request can retry safely;
- adapter metrics contain only bounded result, guardrail, and token-direction
  labels.

The composition factory keeps `enabled=false` by default and is referenced by
no FastAPI/configuration/runtime module. Tests use either an injected fake
structured provider or an `OpenAIProvider` with an in-memory fake Responses
transport. No live provider is constructed or called.

Verification:

- focused provider-adapter suite: 11 passed;
- combined AI-LIST-01A/01B suite: 32 passed;
- full default Agent suite: 228 passed with 14 expected opt-in
  MySQL/OpenSearch integration skips;
- Python compilation and strict proposal/request/limits schema generation
  passed;
- scoped diff/whitespace, credential, dependency, network-import, runtime
  wiring, and unsafe-log scans passed;
- 01A baseline and 01B bound output were exactly equal for the deterministic
  clear-image fixture;
- no live provider, credential, network, Product, database, OpenSearch, or
  external runtime request occurred.

No dependency, provider abstraction, API, persistence, migration, Product,
gateway, frontend, runtime configuration, or environment contract changed.
Production multimodal quality, real image-token accounting, latency, cost,
privacy review, runtime-enabled Product/media/provider composition, and
rollout approval remain unknown and blocking. `AI-LIST-01B` advances the AI
lane from 1/3 to 2/3. No successor starts automatically.

## AI-LIST-01C Authorized Product Listing-Media Tool Adapter

Completed on 2026-07-20 without adding a migration, Product write, browser
route, shared-storage change, or runtime activation.

Product Service now owns the default-disabled internal route:

```text
POST /api/v1/internal/agent/listings/{listingId}/draft-media
```

The route requires the existing `X-Agent-Internal-Service-Token` credential.
Its strict request carries schema version, application-owned actor ID, and one
through four selected media IDs. Product independently verifies:

- the listing is an individual listing owned by that actor;
- the listing is `DRAFT` or `CHANGES_REQUESTED`;
- every media object belongs to the same listing and actor;
- every media object is uploaded and is not pending or rejected;
- declared and actual count, per-image size, total size, MIME signature, and
  optional stored SHA-256 digest agree.

The response is `ai-list-owned-draft-media-v1` and contains only listing/media
versions, deterministic request-order media IDs, verified JPEG/PNG/WebP
content, actual byte size, and SHA-256. It contains no seller identity,
contact/moderation fields, object bucket/key, signed/public URL, storage
credential, or unrelated listing data. Cross-actor, cross-listing, missing,
deleted, and ineligible state is hidden as not found. Storage and integrity
failures use bounded safe errors.

Agent Service adds `ProductListingDraftMediaTool` behind the existing
`ListingDraftMediaTool` protocol. Its own gate also defaults false, it is not
wired into FastAPI or composition, it accepts no model-supplied identity or
URL, follows no redirect, bounds response buffering and timeout, revalidates
the strict Product response plus base64/size/digest/order, and preserves
cancellation. Product and Agent metrics use fixed result/rule labels; audits
and logs contain only hashed actor/listing/correlation identifiers and bounded
counts/latency.

Verification:

- Product unit/API/MySQL focused suite: 14 passed;
- Agent fake-HTTP adapter suite: 9 passed;
- disabled Product service and disabled Agent adapter both make zero
  repository/storage/HTTP reads;
- no Product write, provider call, external network, external OpenSearch,
  credential workflow, runtime refresh, or environment-file read occurred.

The Product switch remains
`agent.listing-media-tool-enabled=false`; the Agent adapter is unconfigured,
unwired, and `enabled=false` by default. Seller proposal API/UI, proposal
application, live multimodal execution, production evidence, and rollout
approval remain blocked. `AI-LIST-01C` advances the AI lane from 2/3 to 3/3.
That 3/3 checkpoint triggered the mandatory AI-LIST cleanup documented below.

## AI-LIST-CLEAN-P0-01 Listing Proposal And Media Boundary Cleanup

Completed on 2026-07-20 without changing an external API, migration, runtime
composition, provider dependency, or feature flag.

The cleanup:

- rejects contradictory proposal output where title, description, or category
  is both suggested and marked unknown;
- centralizes Python JPEG/PNG/WebP magic validation and applies it to
  authorized media, provider input, and Product HTTP response boundaries;
- records bounded hashed cancellation audit/metrics when cancellation happens
  during the owned-media tool call and preserves retry-safe replay cleanup;
- maps Product adapter failures to fixed metric outcomes without adding
  identity/resource labels;
- separates Product's transactional authorization snapshot from object-storage
  reads, so a slow storage read does not hold a catalog transaction open; and
- keeps the existing constant-time service token check, hidden ownership/state
  denial, deterministic media order, strict byte/size/digest/base64 limits,
  proposal-only output, privacy redaction, and default-off/no-call behavior.

Verification:

- combined AI-LIST proposal/provider/media focused suite: 43 passed;
- full default Agent suite: 239 run with 14 expected opt-in
  MySQL/OpenSearch skips and no failures;
- Product focused unit/API/MySQL media suite: 14 passed;
- bounded Product suite excluding the known pathological migration harness:
  155 passed;
- Product offline package, Python compilation/schema, default-off/wiring,
  dependency, credential, unsafe-log, diff, and whitespace checks passed;
- no live provider, Product write, external network, runtime activation,
  environment-file read, or dependency change occurred.

The prior `ListingDomainFoundationMigrationTests` report remains green, but
that harness previously took approximately 3 hours 51 minutes and left its
Maven process waiting after the report. Per the cleanup assignment it was not
repeated. Product's shared synchronous S3 read still owns no independently
configurable per-read deadline and can continue after an upstream client
cancels; the Agent HTTP deadline remains bounded, and the catalog transaction
now closes before that read. Changing the shared storage transport requires a
separate approved slice.

Release remains `BLOCKED`: both proposal/media gates are false and unwired,
and seller API/UI, proposal application, live multimodal execution, production
evidence, and rollout approval remain deferred. This cleanup resets the AI
lane from 3/3 to 0/3. No successor starts automatically.

The separately approved successor plan is:
`docs/mvp/ai/ai-list-02-seller-proposal-review-and-apply-plan.md`.
It does not activate any gate or authorize implementation automatically.
