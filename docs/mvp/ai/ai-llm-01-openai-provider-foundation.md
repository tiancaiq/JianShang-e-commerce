# AI-LLM-01 OpenAI Provider Foundation

Status: implemented; replacement runtime credential verified with a live
synthetic embedding check on 2026-07-19.

Release: V3.

## Goal

Establish the isolated OpenAI runtime before implementing customer-service,
listing-content, or report-operation agents. This slice proves the provider
boundary, configuration behavior, strict structured output, multimodal input,
and one allowlisted function-tool loop without adding a product feature.

## Scope

Implemented:

- standalone Python 3.12 and FastAPI `agent-service`;
- direct OpenAI Python SDK adapter using the Responses API;
- optional runtime `OPENAI_API_KEY` configuration with secret-safe handling;
- configurable model, timeout, retry count, and HTTP port;
- `/health` liveness that never calls OpenAI;
- `/ready` configuration readiness that returns `503` while the key is absent;
- strict structured text and image smoke operations;
- one fixed, non-production `get_demo_listing` function-tool smoke operation;
- stable provider error classification;
- safe request metadata logs for operation, model, correlation ID, latency,
  token usage, status, and retryability;
- mocked configuration, HTTP, structured-output, multimodal, and function-tool
  tests in CI.

Not implemented:

- public or gateway-routed AI endpoints;
- authenticated agent sessions or the marketplace chat UI integration;
- marketplace application tools or database access;
- seller listing-content generation;
- report classification or automated operations;
- Agents SDK orchestration, sessions, handoffs, or tracing;
- a live OpenAI request, because the runtime key is intentionally deferred.

## Architecture Decision

The provider foundation uses the official OpenAI Python SDK directly. The
OpenAI Agents SDK remains the approved orchestration layer for `AI-CS-01`,
where agent sessions, tool authorization, and application-tool policy become
real concerns. Keeping this slice at the provider boundary makes API failure,
multimodal input, and strict schema behavior testable before orchestration is
introduced.

The service has no database credentials and no application API tools. Its
function smoke check executes only fixed in-memory demo data and cannot read or
modify marketplace state.

## Runtime Contract

Internal service endpoints:

```text
GET /health
GET /ready
```

`/health` means the process can serve HTTP. `/ready` currently means required
OpenAI configuration exists; it deliberately does not spend tokens or test
network/model access. A later deployment slice may add a separate operator
probe for verified provider access.

No endpoint in this slice is exposed through `/api/v1`.

## Verification

Automated verification uses fakes and must never make a paid provider request:

```text
python -m unittest discover -s agent-service/tests -v
```

After runtime credentials are supplied, an operator can run text, image, and
tool smoke checks through `python -m msb_agent_service.smoke`. Live verification
must record the model, result status, latency, and token usage without recording
the credential, prompt body, image bytes, or full model response.

## Data And Migration Impact

- Database ownership: none.
- Flyway migrations: none.
- Kafka events: none.
- External API contract changes: none.

## Deferred Dependencies

- Runtime `OPENAI_API_KEY` and project/model access for live verification.
- Authenticated gateway/session integration in `AI-CS-01`.
- Application-owned listing tool API and actor authorization in `AI-CS-01`.
- Pricing metadata or billing export integration for exact per-request cost;
  this foundation records token usage and latency but does not hardcode prices.
