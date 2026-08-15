# Agent Service Database Migrations

The Agent Service owns the `agent` MySQL schema. Forward-only Flyway SQL
migrations live in `db/migration/`; FastAPI startup validates required tables
but never creates or changes schema.

`V4__create_agent_customer_service_persistence.sql` adds the internal
`AI-CS-01A` session, message, invocation, and tool-call tables. The
`V5__expand_agent_invocation_correlation_id.sql` aligns stored correlation IDs
with the 128-character gateway boundary. `AGENT_PERSISTENCE_ENABLED` remains
false by default; when enabled, startup validates both migrations.

`V6__create_listing_proposal_review_persistence.sql` adds the AI-LIST-02A
terminal proposal, expiring DB claim, and dismiss-idempotency tables. Proposal
content expires after 24 hours; safe idempotency tombstones expire after 90
days. The separately gated listing-proposal API validates V6 only when that
API is enabled.

`AI-CS-01B` adds the separately gated authenticated customer-service API.
`AGENT_CUSTOMER_SERVICE_API_ENABLED` remains false by default, and enabling it
before `AI-CS-01C` installs the bounded answerer fails readiness and execution
closed with `ORCHESTRATION_DEFERRED`.

For the local demo stack, run the migration job before starting an enabled
Agent Service:

```powershell
docker compose -p msb-ecom -f docker-compose.demo.yml --profile ai run --rm agent-migrations migrate
docker compose -p msb-ecom -f docker-compose.demo.yml --profile ai up -d --build --no-deps agent-service
```

Deployment automation must run Flyway as a separate step and stop promotion
when validation or migration fails. Do not enable `baselineOnMigrate`,
`outOfOrder`, or schema-clean operations.

`V7__create_marketplace_discovery_sessions.sql` extends the Agent-owned
session/message/tool schema for default-off `MARKETPLACE_DISCOVERY` sessions.
It keeps listing customer-service subjects mandatory, permits only discovery
sessions to be subjectless, enforces one open discovery session per actor, and
stores detail-revalidated recommendation snapshots without cross-service
foreign keys.

`V9__create_discovery_embedding_jobs.sql` adds the separate default-off
`AI-DISC-SEARCH-P0-04B` queue for Product-owned public discovery documents. It
stores only reference identities, hashes, state, lease/retry metadata, and safe
error codes. It never stores source text, vectors, provider responses, prompts,
actors, or credentials, and it does not share the Agent RAG knowledge tables.

`V16__allow_discovery_availability_tool_audit.sql` forward-expands the existing
tool-name check constraint for the Product-owned `CHECK_AVAILABILITY` audit.
It changes no rows and preserves every previously allowlisted tool name.

`V17__create_parallel_marketplace_agent_v2_boundary.sql` adds the isolated
`MARKETPLACE_AGENT_V2` session type and one-open-session-per-actor marker, then
allowlists only `search_listings` and `get_listing` for V2 tool audits. Existing
discovery and listing customer-service session/message semantics are preserved.

`V18__allow_marketplace_agent_v2_availability_tool_audit.sql` forward-expands
the shared tool-name check for V2's Product-owned `check_availability` audit.
It changes no rows and retains every previously allowlisted tool name.
