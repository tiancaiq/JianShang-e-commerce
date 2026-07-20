# AI-RAG-02D Rebuild, Deletion, And Promotion

Status: implemented and locally deployed on 2026-07-19.

## Scope

This slice completes the listing rebuild and recovery portion of
`AI-RAG-02`. It adds:

- watermark-stable Product Service export paging with strict response models;
- durable cursor, watermark, count, generation, and operator checkpoints;
- crash-resumable listing rebuilds using the `AI-RAG-02C` deterministic
  sanitizer, chunker, and embedding builder;
- dynamic mirroring of live upserts, invalidations, and exact deletions to
  both read and write generations while aliases differ;
- pre/post-write monotonic source-state checks that prevent an exported stale
  version from surviving a concurrent update or tombstone;
- immediate exact invalidation plus a leased, idempotent physical deletion
  queue;
- explicit validation, promotion, rollback, status, and exact retry commands;
- promotion gates for export completion, mapping/embedding identity,
  reconciled source/document counts, drained ingestion/deletion work, zero
  oldest-pending age, and zero Kafka consumer-group lag;
- low-cardinality rebuild and deletion backlog metrics.

It adds no retriever, customer-facing API, frontend, session/message storage,
non-listing source, LangChain integration, Gemini provider, or physical old
generation cleanup.

## Persistence

Forward-only migration:

```text
agent-service/db/migration/V2__create_knowledge_rebuild_and_deletion.sql
```

The migration creates:

- `knowledge_rebuild_runs`;
- `knowledge_deletion_jobs`.

FastAPI startup continues to validate rather than mutate schema. Flyway
applied V2 successfully to the local Agent Service MySQL schema.

## Operator Contract

```text
python -m msb_agent_service.knowledge_ingestion status
python -m msb_agent_service.knowledge_ingestion retry --job-id <exact-job-id>
python -m msb_agent_service.knowledge_ingestion rebuild-listings --operator <operator-id>
python -m msb_agent_service.knowledge_ingestion rebuild-listings --operator <operator-id> --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion validate-rebuild --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion promote --run-id <exact-run-id>
python -m msb_agent_service.knowledge_ingestion rollback --run-id <exact-run-id>
```

Direct `knowledge_index` promotion and rollback are rejected so the durable
run and gates cannot be bypassed through the shipped operator CLI.

## Recovery Rules

- A crash after moving the write alias but before inserting the run is
  recovered by binding the divergent write generation to a new durable run.
- A crash after index writes but before a page checkpoint replays the same
  deterministic chunk IDs.
- Live operations are successful only after every distinct live generation
  succeeds.
- A rebuild write is followed by a monotonic state recheck; a newer active
  version or tombstone causes exact cleanup of the just-loaded old version.
- Tombstone/supersession invalidation occurs before the durable physical
  deletion is scheduled.
- Expired deletion claims are reclaimable; attempts at the configured limit
  transition to `DEAD_LETTER`.
- Promotion reruns every gate. A crash after the read-alias move is
  idempotently reconciled by the same exact run command.
- Rollback targets only the run's recorded prior read generation. The write
  alias remains on the rebuilt generation, so live mirroring continues.

## Verification

- Default Python suite: `86` discovered, `80` passed, `6` opt-in integration
  tests skipped.
- MySQL 8.4 Testcontainers suite: `4` passed, including V2 migration,
  rebuild checkpoints, idempotent exact deletion scheduling, claims, and
  completion.
- OpenSearch 2.15 integration suite: `2` passed against the local disposable
  test prefixes, including dual-generation live writes and exact deletion.
- Deployed Agent Service package: `0.5.0`.
- Deployed readiness: `READY` for OpenAI configuration, knowledge index, and
  knowledge ingestion.
- Deployed operator status: one shared read/write generation, `330`
  documents, `165` succeeded ingestion jobs, no deletion backlog, and no
  rebuild runs.
- Kafka lag inspector: `0` for
  `msb-agent-listing-knowledge-v1` without subscribing, consuming, committing,
  or joining the production assignment.

No OpenAI request was made by implementation or verification. A full rebuild
would intentionally re-embed active sources and remains an explicit operator
action. No promotion was performed.

## Security And Privacy

The new MySQL tables contain only identifiers, versions, safe status/error
codes, cursors, counts, generation names, timestamps, embedding/chunker
identity, and operator identity. They contain no source body, passage, vector,
provider response, credential, contact data, exact location, seller identity,
media, moderation evidence, or prompt content.

All unrelated worktree changes were preserved.

## Deferred

- `AI-RAG-02E` remaining authoritative sources;
- `AI-RAG-03` filtered retriever;
- listing customer-service sessions and orchestration;
- physical removal of old index generations as a separately approved
  destructive operation.
