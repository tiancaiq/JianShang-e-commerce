# MSB Agent Service

`agent-service` is the isolated V3 AI runtime. `AI-LLM-01` establishes the
OpenAI provider boundary and `AI-RAG-01` adds the versioned OpenSearch vector
foundation. `AI-RAG-02B` adds durable Kafka intake for reference-only listing
knowledge events. `AI-RAG-02C` adds deterministic listing sanitization,
chunking, OpenAI embeddings, monotonic source state, and strict OpenSearch
indexing. `AI-RAG-02D` adds
resumable listing rebuilds, dual-generation live mirroring, exact retryable
physical deletion, and guarded operator promotion/rollback. `AI-RAG-03` adds
the internal replaceable listing-only retriever with mandatory current-version
and cross-listing isolation. `AI-RAG-02E-CATEGORY` adds separately gated
category-guidance intake, exact-source verification, deterministic processing,
source-complete rebuilds, and category-scoped retrieval. `AI-CS-01A` adds the
agent-owned session/message/invocation/tool-audit persistence foundation.
`AI-CS-01B` adds the disabled-by-default authenticated listing
customer-service API boundary. `AI-CS-01C` adds the listing-only hybrid-RAG
orchestration source boundary with two allowlisted read tools, strict grounded
output validation, privacy and injection guardrails, bounded mocked/offline
model execution, and evaluation fixtures. `AI-CS-01C-2` binds that model
interface to the existing Responses provider through a strict redacted adapter,
but does not install it in the application runtime. `AI-CS-01E-A` adds a
deterministic zero-network offline evaluation runner and strict report schema.
`AI-LIST-01A` adds an unwired, default-off image-to-listing proposal contract
over an actor-scoped media protocol and injected fake vision transport. Its
strict output is review-only and cannot write, submit, or publish a listing.
`AI-LIST-01B` binds that protocol to the existing typed Responses provider
operation through a stricter multimodal adapter, still using fake transports
only and remaining disabled/unwired.
The API capability remains disabled and no marketplace mutation endpoint is
exposed.

## Offline Seller Listing Proposals

`listing_content_proposal.py` defines the internal `ai-list-proposal-v1`
schema, owned-draft media tool protocol, vision transport protocol, bounded
media/privacy guardrails, safe hashed audits, and replay semantics. It is not
wired into FastAPI or runtime settings. Tests use local bytes and fakes only:

```powershell
python -m unittest tests.test_listing_content_proposal
```

The output may suggest only title, description, and category labels with
confidence and selected-media evidence. Seller identity, price, exact
location, quantity, condition, negotiability, policy claims, contact data, and
other unsupported claims remain explicit unknowns. Applying a proposal through
the versioned Product listing update flow is deferred.

`listing_content_provider.py` reuses the existing provider's generic typed
structured call. It keeps system instructions separate from untrusted image
content, enforces a tighter provider-context/token/deadline budget, disables
provider storage, exposes no tools, and maps provider failures into the stable
proposal outcomes. Its composition factory defaults off and is not imported by
the application runtime.

## Offline Customer-Service Evaluation

The baseline uses only local fakes and does not read runtime configuration,
require a credential, call OpenSearch, or call a model provider:

```powershell
python -m msb_agent_service.customer_service_evaluation
python -m msb_agent_service.customer_service_evaluation --schema
```

The fixture is `evals/ai_cs_01e_offline_baseline_v1.json`. A passing report is
an offline regression result only: release remains blocked/default-off pending
live quality, production latency, pricing, and rollout approval.

The `AI-CS-01E-B` evaluator consumes a saved report plus explicit gate inputs:

```powershell
python -m msb_agent_service.customer_service_release_gate --report offline-report.json --inputs evals/ai_cs_01e_b_release_gate_default_blocked_v1.json
python -m msb_agent_service.customer_service_release_gate --schema input
python -m msb_agent_service.customer_service_release_gate --schema decision
python -m msb_agent_service.customer_service_release_gate --schema observability
```

The committed gate fixture keeps every switch and approval false, so it
returns `BLOCKED`. The evaluator reads no environment or runtime state.

## Configuration

Runtime configuration is read from process environment variables. This
service does not load or create `.env` files.

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `OPENAI_API_KEY` | For provider calls | none | OpenAI project credential |
| `OPENAI_MODEL` | No | `gpt-5-mini` | Responses API model |
| `OPENAI_TIMEOUT_SECONDS` | No | `30` | Per-request timeout |
| `OPENAI_MAX_RETRIES` | No | `2` | SDK retry count |
| `PORT` | No | `8086` | HTTP listen port |
| `AGENT_KNOWLEDGE_ENABLED` | No | `false` | Include the knowledge index in readiness |
| `AGENT_OPENSEARCH_URL` | When knowledge is enabled | none | Absolute OpenSearch HTTP(S) URL without credentials |
| `AGENT_OPENSEARCH_USERNAME` | No | none | Service username; must be paired with password |
| `AGENT_OPENSEARCH_PASSWORD` | No | none | Service password; never logged |
| `AGENT_OPENSEARCH_VERIFY_CERTS` | No | `true` | Verify HTTPS certificates |
| `AGENT_OPENSEARCH_REQUEST_TIMEOUT_SECONDS` | No | `3` | Total connection and operation budget |
| `AGENT_OPENSEARCH_MAX_RETRIES` | No | `2` | Bounded timeout retry count |
| `AGENT_KNOWLEDGE_INDEX_PREFIX` | No | `msb-agent-knowledge` | Physical index prefix |
| `AGENT_KNOWLEDGE_READ_ALIAS` | No | `msb-agent-knowledge-read` | Promoted retrieval generation |
| `AGENT_KNOWLEDGE_WRITE_ALIAS` | No | `msb-agent-knowledge-write` | Current ingestion generation |
| `AGENT_KNOWLEDGE_EMBEDDING_PROVIDER` | When knowledge is enabled | none | Mapping compatibility identity only |
| `AGENT_KNOWLEDGE_EMBEDDING_MODEL` | When knowledge is enabled | none | Mapping compatibility identity only |
| `AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS` | When knowledge is enabled | none | Immutable vector dimensions |
| `AGENT_KNOWLEDGE_SHARDS` | No | `1` | Physical index shards |
| `AGENT_KNOWLEDGE_REPLICAS` | No | `0` | Physical index replicas |
| `AGENT_KNOWLEDGE_BULK_MAX_DOCUMENTS` | No | `500` | Maximum documents per operation |
| `AGENT_KNOWLEDGE_BULK_MAX_BYTES` | No | `5000000` | Maximum serialized bulk body |
| `AGENT_KNOWLEDGE_INGESTION_ENABLED` | No | `false` | Start durable Kafka intake after schema validation |
| `AGENT_PERSISTENCE_ENABLED` | No | `false` | Validate and expose readiness for internal agent persistence |
| `AGENT_CUSTOMER_SERVICE_API_ENABLED` | No | `false` | Enable authenticated customer-service routes; requires persistence and service dependencies |
| `AUTH_SERVICE_URL` | When customer-service API is enabled | none | Resolve the app-owned actor from the BFF-relayed bearer token |
| `AGENT_API_DEPENDENCY_TIMEOUT_SECONDS` | No | `5` | Bounded Auth/Product dependency timeout |
| `AGENT_MESSAGE_PAGE_DEFAULT_LIMIT` | No | `50` | Default message page size |
| `AGENT_MESSAGE_PAGE_MAX_LIMIT` | No | `100` | Maximum message page size |
| `AGENT_MYSQL_HOST` | When ingestion or persistence is enabled | `localhost` | Agent-owned MySQL host |
| `AGENT_MYSQL_PORT` | No | `3306` | Agent-owned MySQL port |
| `AGENT_MYSQL_DATABASE` | No | `agent` | Agent-owned schema |
| `AGENT_MYSQL_USERNAME` | No | `agent` | Agent schema user |
| `AGENT_MYSQL_PASSWORD` | When ingestion or persistence is enabled | none | Agent schema credential; never logged |
| `AGENT_MYSQL_POOL_MIN_SIZE` | No | `1` | Minimum async database connections |
| `AGENT_MYSQL_POOL_MAX_SIZE` | No | `5` | Maximum async database connections |
| `AGENT_QUESTION_MAX_CHARACTERS` | No | `8000` | Maximum normalized user-message characters |
| `AGENT_ASSISTANT_MAX_CHARACTERS` | No | `12000` | Maximum validated assistant-message characters |
| `AGENT_MAXIMUM_FAILED_RETRIES` | No | `1` | Bounded retry count for a failed invocation |
| `AGENT_MESSAGE_RETENTION_DAYS` | No | `90` | Maximum session-content retention |
| `AGENT_AUDIT_RETENTION_DAYS` | No | `365` | Maximum safe invocation/tool audit retention |
| `AGENT_KAFKA_BOOTSTRAP_SERVERS` | No | `localhost:9092` | Kafka bootstrap list |
| `AGENT_KAFKA_TOPIC` | No | `listing-knowledge-v1` | Single allowlisted source-reference topic |
| `AGENT_KAFKA_GROUP_ID` | No | `msb-agent-listing-knowledge-v1` | Stable durable consumer group |
| `AGENT_KAFKA_CLIENT_ID` | No | `msb-agent-service` | Kafka client identity |
| `AGENT_CATEGORY_GUIDANCE_INTAKE_ENABLED` | No | `false` | Start the separately deployable category-guidance consumer |
| `AGENT_CATEGORY_GUIDANCE_KAFKA_TOPIC` | No | `category-guidance-v1` | Fixed category-guidance reference topic |
| `AGENT_CATEGORY_GUIDANCE_KAFKA_GROUP_ID` | No | `msb-agent-category-guidance-v1` | Stable category consumer group |
| `AGENT_CATEGORY_GUIDANCE_PROCESSING_ENABLED` | No | `false` | Permit workers to claim and embed category-guidance jobs |
| `AGENT_CATEGORY_GUIDANCE_RETRIEVAL_ENABLED` | No | `false` | Permit category guidance in future orchestration |
| `AGENT_KAFKA_SECURITY_PROTOCOL` | No | `PLAINTEXT` | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, or `SASL_SSL` |
| `AGENT_KAFKA_USERNAME` | For SASL | none | Kafka SASL username |
| `AGENT_KAFKA_PASSWORD` | For SASL | none | Kafka SASL password; never logged |
| `AGENT_PRODUCT_SERVICE_URL` | When ingestion is enabled | none | Product Service base URL without credentials |
| `AGENT_PRODUCT_SERVICE_TOKEN` | When ingestion is enabled | none | Dedicated exact-source credential; never logged |
| `AGENT_KNOWLEDGE_SOURCE_TIMEOUT_SECONDS` | No | `5` | Exact source-read timeout |
| `AGENT_KAFKA_CONSUMER_POLL_TIMEOUT_MS` | No | `1000` | Bounded consumer poll |
| `AGENT_KAFKA_CONSUMER_BATCH_SIZE` | No | `50` | Maximum records returned per poll |
| `AGENT_KNOWLEDGE_JOB_CLAIM_BATCH_SIZE` | No | `10` | Maximum jobs in one future worker claim |
| `AGENT_KNOWLEDGE_JOB_CLAIM_SECONDS` | No | `120` | Expiring job claim duration |
| `AGENT_KNOWLEDGE_JOB_MAX_ATTEMPTS` | No | `8` | Retry/dead-letter boundary |
| `AGENT_KNOWLEDGE_JOB_RETRY_BASE_SECONDS` | No | `5` | Initial retry delay |
| `AGENT_KNOWLEDGE_JOB_RETRY_MAX_SECONDS` | No | `900` | Maximum retry delay |
| `AGENT_KNOWLEDGE_PROCESSOR_ENABLED` | No | `false` | Claim and process durable listing jobs |
| `AGENT_KNOWLEDGE_WORKER_POLL_SECONDS` | No | `1` | Idle durable-worker polling interval |
| `AGENT_KNOWLEDGE_CHUNK_MAX_TOKENS` | No | `800` | Per-chunk tokenizer limit |
| `AGENT_KNOWLEDGE_CHUNK_OVERLAP_TOKENS` | No | `80` | Deterministic description overlap |
| `AGENT_KNOWLEDGE_CHUNK_MAX_COUNT` | No | `16` | Summary plus description chunk limit |
| `AGENT_EMBEDDING_MAX_INPUTS` | No | `16` | Maximum embedding inputs per request |
| `AGENT_EMBEDDING_MAX_INPUT_TOKENS` | No | `8000` | Maximum tokens in one embedding input |
| `AGENT_EMBEDDING_MAX_TOTAL_TOKENS` | No | `32000` | Maximum tokens in one embedding request |

The process starts without an API key so `/health` remains useful, but
`/ready` returns `503` until the key is supplied. When the knowledge feature is
enabled, `/ready` also validates cluster reachability, aliases, mappings,
settings, and embedding identity. When ingestion is enabled, startup validates
the externally migrated MySQL schema and connects the Kafka consumer; readiness
reports `knowledgeIngestion` as `READY` only while its intake task is running.
When agent persistence is enabled, startup validates the externally migrated
V4 tables plus the V5 correlation-width update and readiness reports
`agentPersistence=READY`. Customer-service
routes additionally require their own feature flag. The source-level provider
binding is not constructed by `create_app`; until a later activation slice
explicitly installs the AI-CS-01C bounded answerer, enabling that flag reports
`customerServiceApi=ORCHESTRATION_DEFERRED` and answer execution fails closed.
When the processor is enabled, startup also requires the exact
`openai`/`text-embedding-3-small`/`1536` index identity and an API key. Neither
endpoint makes an OpenAI request. Prometheus metrics are available at
`/metrics/`.

## Agent Persistence Foundation

`AI-CS-01A` owns four Agent MySQL tables: `agent_sessions`,
`agent_messages`, `agent_invocations`, and `agent_tool_calls`. The V4
migration enforces one open listing-customer-service session per actor and
listing, actor-bound message/invocation references, retry-key uniqueness,
message keyset indexes, terminal-state row shapes, and allowlisted tool names.

The internal repository atomically creates or resumes sessions, stores one
user message with a `PENDING` invocation before any future external work,
deduplicates `clientMessageId`, rejects request-hash reuse, permits one bounded
retry after failure, and stores one terminal assistant result. Reads always
include the trusted actor and return the same not-found result for absent and
cross-actor resources. Retention closes and purges inactive session content
after 90 days, redacts retry keys, and retains only safe invocation/tool
metadata for at most 365 days.

The repository never logs message bodies, prompts, tool payloads, retrieved
passages, credentials, private contact data, or exact locations.

## Authenticated Customer-Service API

`AI-CS-01B` exposes the four `/api/v1/agent/sessions` routes through the
authenticated BFF. The BFF enforces authentication and CSRF on commands and
relays the access token. Agent Service resolves the app-owned actor through
Auth Service, rejects client-supplied identity fields, hides cross-user
sessions, and checks the token-protected Product Service listing context before
session creation and message persistence. Errors use the standard envelope and
echo a safe `X-Correlation-Id`.

`AI-CS-01C` provides an injectable listing-only orchestration boundary. It
executes the fresh Product listing projection as `getListing`, injects the
requesting actor and exact listing/version into `retrieveKnowledge`, stores
only hashed tool arguments/results plus source identities, and validates every
model-proposed source and route-semantic action before persistence. Deterministic
guardrails handle seller-only decisions, the approved transaction notice,
prompt injection, and private-data requests without a model call. Provider and
OpenSearch failures fail closed, while normal marketplace behavior remains
independent because the customer-service API is disabled by default.

`AI-CS-01C-2` adds `OpenAICustomerServiceModelAdapter` and a composition
factory over the existing `OpenAIProvider`. It serializes one strict,
listing-only JSON request, redacts contact details, secrets, URLs, exact
addresses, and coordinates before the provider boundary, keeps that JSON in
user input separate from fixed instructions, requests strict
`ModelAnswerCandidate` output with `store=false`, no tools, disabled
truncation, and bounded output tokens, then returns token/latency metadata to
the orchestrator. The orchestrator still performs final source and action
validation. Logs contain correlation, bounded status/usage, and hashes instead
of prompts, passages, or responses. Adapter metrics use only fixed result,
field-class, and token-direction labels.

The current Product projection contains listing ID, version, title, thumbnail,
eligibility, seller type, and transaction notice. Therefore this source slice
does not invent price, location, quantity, condition, policy, safety, FAQ, or
category facts. Only `LISTING` retrieval is enabled; questions requiring an
unimplemented authoritative source return uncertainty or seller handoff. The
provider binding is verified with fake transports only. It is not activated,
and no credential is selected, created, read, or called by this slice.

The vector index is separate from Product Service's `msb-public-listings`
search index. Listing descriptions and approved public attributes are derived
content. Product Service remains authoritative for the current listing name,
price, currency, public city/region, eligibility, and version; exact locations
and private contact details must never enter this index.

## Filtered Retrieval

`AI-RAG-03` provides an internal `KnowledgeRetriever` protocol and an
OpenSearch implementation. It accepts trusted actor, subject listing, current
listing version, effective time, language, and bounded query context; only the
query is embedded. OpenSearch receives fixed public, `LISTING`, exact subject,
exact version, language/fallback, effective-date, and non-invalidated filters.
Returned hits are strictly revalidated and bounded before becoming model
context.

The category retriever accepts only a trusted category ID and exact
language/version scopes resolved from fully indexed Agent Service source
state. Category passages cannot contain `listingId`. Policy, safety, and FAQ
retrieval remain disabled until their owner contracts exist. There is no
retrieval HTTP endpoint in this slice.

## Local Commands

From this directory after installing the package:

```powershell
python main.py
python -m unittest discover -s tests -v
python -m msb_agent_service.smoke text
python -m msb_agent_service.smoke image --image C:\path\to\listing.jpg
python -m msb_agent_service.smoke tool
```

The smoke commands are intentional operator-only checks. They require runtime
credentials and are not public paid-request endpoints.

## Seller Listing-Media Tool

`AI-LIST-01C` adds `ProductListingDraftMediaTool` behind the Agent-owned
`ListingDraftMediaTool` protocol. It calls only the fixed internal Product
route, passes application-owned actor/listing/media context, follows no
redirect, and strictly revalidates returned media order, size, MIME, digest,
and bounded base64 content.

The adapter is not wired into FastAPI or runtime composition and its settings
default to `enabled=false`. Product has a separate default-false gate. No
Product request, provider request, proposal application, or listing write
occurs in default configuration.

## Durable Knowledge Intake

`AI-RAG-02B` consumes `listing-knowledge-v1`; the separately gated category
adapter consumes `category-guidance-v1`. Each validates its version-1 event
envelope and reference payload, atomically inserts `processed_events`
and one `knowledge_ingestion_jobs` row, then manually commits the Kafka
offset. Replays are deduplicated by consumer name and event ID. A reused event
ID with a different contracted payload hash fails closed.

Source bodies, embeddings, provider responses, private locations, contact
data, credentials, and seller identity are not written to these tables.
Network processing does not run in the Kafka poll loop. When
`AGENT_KNOWLEDGE_PROCESSOR_ENABLED=true`, `AI-RAG-02C` claims durable jobs,
fetches the exact Product Service version, verifies its canonical hash,
sanitizes and chunks the four approved public fields, embeds the bounded
batch, writes strict OpenSearch documents, deletes the exact superseded
version from retrieval, schedules exact physical cleanup, and advances
monotonic source state. The deletion worker retries independently with bounded
leases and never accepts a wildcard or raw query. Temporary dependency
failures retry with bounded backoff; permanent contract/provider failures
dead-letter with a safe error code.

FastAPI never applies migrations. For the optional local `ai` profile:

```powershell
docker compose -p msb-ecom -f docker-compose.demo.yml --profile ai run --rm agent-migrations migrate
docker compose -p msb-ecom -f docker-compose.demo.yml --profile ai up -d agent-opensearch
docker compose -p msb-ecom -f docker-compose.demo.yml --profile ai up agent-index-bootstrap
docker compose --env-file .env --env-file .env.local -p msb-ecom -f docker-compose.demo.yml --profile ai up -d --build agent-service
```

The Product Service outbox publisher and Kafka broker must already be
available on the same Compose network. The processor remains disabled by
default; enable it only after a live synthetic embedding check confirms the
configured project has usable quota. The demo profile supplies only
non-production local defaults; deployment environments must inject distinct
database, Kafka, Product Service, OpenSearch, and OpenAI credentials.

## Knowledge Index And Rebuild Operations

No OpenAI or Gemini key is needed for index status or bootstrap. Configure the
`AGENT_KNOWLEDGE_*` and `AGENT_OPENSEARCH_*` variables, then run:

```powershell
python -m msb_agent_service.knowledge_index status
python -m msb_agent_service.knowledge_index bootstrap
```

`bootstrap` is idempotent. Direct index promotion and rollback are rejected
after `AI-RAG-02D`; use the run-bound commands so gates and operator identity
are recorded:

```powershell
python -m msb_agent_service.knowledge_ingestion status
python -m msb_agent_service.knowledge_ingestion retry --job-id <exact-job-id>
python -m msb_agent_service.knowledge_ingestion rebuild-listings --operator <operator-id>
python -m msb_agent_service.knowledge_ingestion rebuild-listings --operator <operator-id> --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion rebuild-public-knowledge --operator <operator-id>
python -m msb_agent_service.knowledge_ingestion rebuild-public-knowledge --operator <operator-id> --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion validate-rebuild --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion promote --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion rollback --run-id <exact-run-id>
```

Rebuilds require the configured embedding provider because each active source
is processed through the same deterministic builder as live ingestion.
Promotion makes no provider request and is blocked until every enabled source
export is complete, mapping and embedding identity match, source/document
counts match, ingestion and deletion queues are drained, and each Kafka group
has zero lag. Once category retrieval is enabled, listing-only rebuilds cannot
be promoted.
Neither workflow deletes old physical generations.

The normal test command skips the Docker-backed integration suite. Run it
explicitly against a disposable OpenSearch 2.15 container:

```powershell
$env:RUN_OPENSEARCH_INTEGRATION = "1"
python -m unittest discover -s tests -p "test_knowledge_index_integration.py" -v
```

For a prestarted disposable OpenSearch instance, also set
`OPENSEARCH_TEST_URL`. CI leaves it unset so Testcontainers owns startup and
cleanup.

Run the MySQL-backed durability, deduplication, claim, retry, and lease tests
against a disposable MySQL 8.4 container:

```powershell
$env:RUN_MYSQL_INTEGRATION = "1"
python -m unittest discover -s tests -p "test_knowledge_jobs_integration.py" -v
```
