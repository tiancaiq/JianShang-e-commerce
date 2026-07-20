# CLEAN-P1-03 AI Quarantine Checklist

Status: Complete  
Date: 2026-07-19

## Goal

Keep AI, RAG, embedding, and agent-service work separate from the MVP
stabilization PR.

This checklist does not delete, revert, stage, or change application behavior.

## Implementation Result

The current worktree was inspected with:

```powershell
git status --short --untracked-files=all agent-service docs/mvp/ai product-service/src/main/java/com/msb/ecom/product_service/knowledge product-service/src/test/java/com/msb/ecom/product_service/knowledge product-service/src/main/java/com/msb/ecom/product_service/controller/InternalListingKnowledgeController.java product-service/src/main/resources/db/migration/catalog/V202607160000__add_listing_publication_source.sql product-service/src/main/resources/db/migration/catalog/V202607180000__create_listing_knowledge_publication.sql .github/workflows/pull-request-quality.yml
git diff -- .github/workflows/pull-request-quality.yml AGENTS.md product-service/pom.xml product-service/src/main/java/com/msb/ecom/product_service/ProductServiceApplication.java product-service/src/main/resources/application.properties docker-compose.yml docker-compose.demo.yml run.sh
```

Result: AI/V3 work is present as whole-file untracked additions and as hunks
inside CI, runtime, and product-service files. It must be quarantined from the
MVP stabilization PR.

No files were staged during this cleanup. No environment files were edited.

## AI/V3 Work To Quarantine

### Agent Service

Park the entire Python agent service together:

- `agent-service/`

This includes the service runtime, ingestion workers, provider adapters,
OpenSearch indexing code, MySQL job code, tests, Dockerfile, and README.

Current agent-service inventory:

- `agent-service/.dockerignore`
- `agent-service/Dockerfile`
- `agent-service/README.md`
- `agent-service/db/README.md`
- `agent-service/db/migration/V1__create_knowledge_ingestion_foundation.sql`
- `agent-service/db/migration/V2__create_knowledge_rebuild_and_deletion.sql`
- `agent-service/main.py`
- `agent-service/pyproject.toml`
- `agent-service/src/msb_agent_service/`
- `agent-service/tests/`

### AI Documentation

Park these docs together:

- `docs/mvp/ai/ai-00-agent-and-automated-operations-plan.md`
- `docs/mvp/ai/ai-cs-01-listing-customer-service-assistant.md`
- `docs/mvp/ai/ai-llm-01-openai-provider-foundation.md`
- `docs/mvp/ai/ai-rag-00-hybrid-rag-contract-reconciliation.md`
- `docs/mvp/ai/ai-rag-01-opensearch-vector-foundation-plan.md`
- `docs/mvp/ai/ai-rag-02-ingestion-embedding-pipeline-plan.md`
- `docs/mvp/ai/ai-rag-02a-product-listing-source-publication.md`
- `docs/mvp/ai/ai-rag-02b-agent-durable-ingestion-intake.md`
- `docs/mvp/ai/ai-rag-02c-listing-embedding-indexing.md`
- `docs/mvp/ai/ai-rag-02d-rebuild-deletion-and-promotion.md`
- `docs/mvp/ai/general-ai-agent-implementation-roadmap.md`

### Product-Service Knowledge Publication

Park these product-service files together:

- `product-service/src/main/java/com/msb/ecom/product_service/controller/InternalListingKnowledgeController.java`
- `product-service/src/main/java/com/msb/ecom/product_service/knowledge/`
- `product-service/src/main/resources/db/migration/catalog/V202607160000__add_listing_publication_source.sql`
- `product-service/src/main/resources/db/migration/catalog/V202607180000__create_listing_knowledge_publication.sql`
- `product-service/src/test/java/com/msb/ecom/product_service/knowledge/`
- product-service configuration hunks that only enable listing knowledge
  publication, Kafka publication for agent ingestion, embedding, or RAG.

Current product-service mixed-file hunks to quarantine:

- `product-service/pom.xml`
  - Do not stage `spring-kafka` if it is only needed for listing knowledge
    publication.
  - Review `spring-boot-starter-actuator` separately; it may be general
    observability, but current diff is adjacent to AI publication work.
- `product-service/src/main/java/com/msb/ecom/product_service/ProductServiceApplication.java`
  - Do not stage `@EnableScheduling` unless the MVP PR includes a non-AI
    scheduled task that requires it.
- `product-service/src/main/resources/application.properties`
  - Do not stage `agent.internal-service-token`.
  - Do not stage `listing.knowledge.publication.*`.
  - Do not stage Kafka producer configuration that is only used by listing
    knowledge publication.

### CI And Runtime

Park these together with the AI branch:

- agent-service jobs added to `.github/workflows/pull-request-quality.yml`
- Docker Compose entries required only by `agent-service`
- OpenAI or agent-specific environment variables in example files only
- any runtime script changes that start the agent service

Do not include real OpenAI keys or local provider secrets in any commit.

Current mixed-file rules:

- `.github/workflows/pull-request-quality.yml`
  - Do not stage the `agent` forced-failure option.
  - Do not stage `agent`, `agent-opensearch`, or `agent-mysql` jobs.
- `AGENTS.md`
  - Do not stage AI folder guidance unless the AI docs folder is being staged
    in the same AI PR.
- `docker-compose.demo.yml`
  - Do not stage `agent-migrations`, `agent-opensearch`,
    `agent-index-bootstrap`, or `agent-service`.
  - Do not stage `LISTING_KNOWLEDGE_*`,
    `AGENT_INTERNAL_SERVICE_TOKEN`, `OPENAI_API_KEY`, or agent MySQL/Kafka
    environment hunks.
  - Do not stage `demo_agent_opensearch_data`.
- `docker-compose.yml`
  - The visible current diff is Redis for V2 cart state, not AI. Keep it out
    of the MVP PR and handle it with the V2 quarantine.
- `run.sh`
  - Current diff is mixed runtime cleanup and chat-service startup. Do not
    stage from this file in the AI quarantine step; review separately in a
    runtime/devex PR.

## Quarantine Branch Shape

When AI is ready for review, prefer one branch with this scope:

```text
codex/v3-ai-agent-quarantine
```

Suggested PR contents:

- `agent-service/`
- `docs/mvp/ai/`
- product-service listing knowledge source and publication implementation.
- product-service migrations for listing knowledge publication.
- CI jobs for the agent service.
- demo compose profile for AI services.

Do not include V2 cart, inventory, order, checkout, or address-book work in
the AI PR.

## MVP PR Rules

For the MVP stabilization PR:

- Do not require Python or agent-service dependencies.
- Do not require OpenAI credentials.
- Do not require an agent OpenSearch instance.
- Do not require listing knowledge migrations.
- Do not require Kafka for public marketplace browsing/search unless the MVP
  OpenSearch projection path already explicitly requires it.

## Why

AI is V3 in the approved roadmap. The MVP marketplace should keep working when
AI is disabled, not running, or not configured.

## Acceptance Criteria

- MVP tests do not install Python agent dependencies.
- MVP CI does not require OpenAI, vector search, or agent-service secrets.
- AI docs and code are reviewed in a separate AI/V3 PR.
- No environment files are edited or staged by this cleanup.
