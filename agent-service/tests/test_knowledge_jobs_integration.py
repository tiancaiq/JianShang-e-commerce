from __future__ import annotations

import asyncio
import json
import os
import unittest
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import aiomysql
from testcontainers.core.container import DockerContainer

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.knowledge_events import (
    KnowledgeEventErrorCode,
    KnowledgeEventValidationError,
    parse_category_guidance_event,
    parse_listing_knowledge_event,
)
from msb_agent_service.knowledge_jobs import (
    EnqueueResult,
    KnowledgeJobRepository,
    SourceStateApplyResult,
)
from msb_agent_service.knowledge_operations import (
    KnowledgeOperationsRepository,
    RebuildStatus,
)

RUN_INTEGRATION = os.getenv("RUN_MYSQL_INTEGRATION") == "1"
LISTING_ID = "01L00000000000000000000001"
CATEGORY_ID = "01K00000000000000000000002"


def event(event_id: str, version: int = 1):
    body = {
        "eventId": event_id,
        "eventType": "listing.activated" if version == 1 else "listing.updated",
        "eventVersion": 1,
        "occurredAt": "2026-07-19T01:00:00Z",
        "producer": "product-service",
        "aggregateType": "listing",
        "aggregateId": LISTING_ID,
        "correlationId": "01C00000000000000000000001",
        "payload": {
            "listingId": LISTING_ID,
            "listingVersion": str(version),
            "knowledgeLifecycle": "ACTIVE",
            "supersedesVersion": None if version == 1 else str(version - 1),
            "language": "und",
        },
    }
    return parse_listing_knowledge_event(
        json.dumps(body).encode(),
        LISTING_ID.encode(),
    )


def category_event(event_id: str):
    body = {
        "eventId": event_id,
        "eventType": "category-guidance.activated",
        "eventVersion": 1,
        "occurredAt": "2026-07-19T01:00:00Z",
        "producer": "product-service",
        "aggregateType": "category-guidance",
        "aggregateId": CATEGORY_ID,
        "correlationId": "01C00000000000000000000001",
        "payload": {
            "sourceType": "CATEGORY_GUIDANCE",
            "sourceId": CATEGORY_ID,
            "sourceVersion": "1",
            "knowledgeLifecycle": "ACTIVE",
            "supersedesVersion": None,
            "language": "en",
        },
    }
    return parse_category_guidance_event(
        json.dumps(body).encode(),
        f"{CATEGORY_ID}:en".encode(),
    )


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_MYSQL_INTEGRATION=1 to run MySQL integration tests",
)
class KnowledgeJobRepositoryIntegrationTest(unittest.IsolatedAsyncioTestCase):
    container: DockerContainer
    settings: KnowledgeIngestionSettings

    @classmethod
    def setUpClass(cls) -> None:
        cls.container = (
            DockerContainer("mysql:8.4")
            .with_env("MYSQL_ROOT_PASSWORD", "root-test-password")
            .with_env("MYSQL_DATABASE", "agent")
            .with_env("MYSQL_USER", "agent")
            .with_env("MYSQL_PASSWORD", "agent-test-password")
            .with_exposed_ports(3306)
        )
        cls.container.start()
        cls.settings = KnowledgeIngestionSettings(
            enabled=True,
            mysql_host=cls.container.get_container_host_ip(),
            mysql_port=int(cls.container.get_exposed_port(3306)),
            mysql_database="agent",
            mysql_username="agent",
            mysql_password="agent-test-password",
            product_service_url="http://product-service.test",
            product_service_token="source-test-token",
            job_claim_batch_size=1,
            job_claim_seconds=10,
            job_max_attempts=2,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    async def asyncSetUp(self) -> None:
        self.repository = await self._connect_repository()
        try:
            await self.repository.validate_schema()
        except RuntimeError:
            await self._apply_migration()
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute("DELETE FROM knowledge_deletion_jobs")
                await cursor.execute("DELETE FROM knowledge_rebuild_sources")
                await cursor.execute("DELETE FROM knowledge_rebuild_runs")
                await cursor.execute("DELETE FROM knowledge_ingestion_jobs")
                await cursor.execute("DELETE FROM processed_events")
                await cursor.execute("DELETE FROM knowledge_source_state")
            await connection.commit()

    async def test_category_jobs_are_source_gated_and_rebuild_is_complete(self) -> None:
        now = datetime.now(UTC)
        await self.repository.enqueue_event(
            "category-consumer",
            category_event("01E00000000000000000000009"),
            now,
        )

        self.assertEqual([], await self.repository.claim_jobs("listing-worker", now))
        claimed = await self.repository.claim_jobs(
            "category-worker",
            now,
            source_types=("CATEGORY_GUIDANCE",),
        )

        self.assertEqual(1, len(claimed))
        self.assertEqual("CATEGORY_GUIDANCE", claimed[0].source_type)
        operations = KnowledgeOperationsRepository(
            self.repository._pool,  # noqa: SLF001
            self.settings,
        )
        await operations.schedule_deletion(
            source_type="CATEGORY_GUIDANCE",
            source_id=CATEGORY_ID,
            source_version=1,
            language="en",
            invalidated_at=now,
            now=now,
        )
        run = await operations.create_rebuild(
            target_generation="msb-agent-knowledge-v0001-000099",
            previous_read_generation="msb-agent-knowledge-v0001-000001",
            embedding_provider="openai",
            embedding_model="text-embedding-3-small",
            embedding_dimensions=1536,
            chunker_version="public-knowledge-pipeline-v1",
            initiated_by="integration-test",
            now=now,
            source_type="PUBLIC_KNOWLEDGE",
            rebuild_sources=(
                ("LISTING", "listing-sanitizer-v1", "listing-chunker-v1"),
                (
                    "CATEGORY_GUIDANCE",
                    "category-guidance-sanitizer-v1",
                    "category-guidance-chunker-v1",
                ),
            ),
        )
        children = await operations.get_rebuild_sources(run.run_id)

        self.assertEqual("PUBLIC_KNOWLEDGE", run.source_type)
        self.assertEqual(
            {"LISTING", "CATEGORY_GUIDANCE"},
            {child.source_type for child in children},
        )

    async def asyncTearDown(self) -> None:
        await self.repository.close()

    async def test_migration_enqueue_deduplication_and_hash_conflict(self) -> None:
        await self.repository.validate_schema()
        accepted_at = datetime.now(UTC)
        first = event("01E00000000000000000000001")

        accepted = await self.repository.enqueue_event(
            "integration-consumer",
            first,
            accepted_at,
        )
        replay = await self.repository.enqueue_event(
            "integration-consumer",
            first,
            accepted_at,
        )

        self.assertEqual(EnqueueResult.ACCEPTED, accepted)
        self.assertEqual(EnqueueResult.DUPLICATE, replay)
        self.assertEqual({"PENDING": 1}, await self.repository.job_counts())

        conflicting = event("01E00000000000000000000001", version=2)
        with self.assertRaises(KnowledgeEventValidationError) as captured:
            await self.repository.enqueue_event(
                "integration-consumer",
                conflicting,
                accepted_at,
            )
        self.assertEqual(
            KnowledgeEventErrorCode.EVENT_ID_CONFLICT,
            captured.exception.code,
        )

    async def test_rebuild_checkpoints_and_exact_deletion_are_resumable(
        self,
    ) -> None:
        operations = KnowledgeOperationsRepository(
            self.repository._pool,  # noqa: SLF001
            self.settings,
        )
        now = datetime.now(UTC)
        run = await operations.create_rebuild(
            target_generation="msb-agent-knowledge-v0001-000002",
            previous_read_generation="msb-agent-knowledge-v0001-000001",
            embedding_provider="openai",
            embedding_model="text-embedding-3-small",
            embedding_dimensions=1536,
            chunker_version="listing-chunker-v1",
            initiated_by="integration@example.test",
            now=now,
        )
        await operations.checkpoint_rebuild_page(
            run.run_id,
            next_cursor=None,
            export_watermark=now,
            expected_delta=1,
            processed_delta=1,
            skipped_delta=0,
            tombstoned_delta=0,
            complete=True,
            now=now,
        )
        complete = await operations.get_rebuild(run.run_id)
        self.assertIsNotNone(complete)
        assert complete is not None
        self.assertTrue(complete.export_complete)
        self.assertEqual(1, complete.processed_count)
        await operations.mark_ready_to_promote(run.run_id, now)
        ready = await operations.get_rebuild(run.run_id)
        self.assertEqual(RebuildStatus.READY_TO_PROMOTE, ready.status)  # type: ignore[union-attr]

        await operations.schedule_deletion(
            source_type="LISTING",
            source_id=LISTING_ID,
            source_version=1,
            language="und",
            invalidated_at=now,
            now=now,
        )
        await operations.schedule_deletion(
            source_type="LISTING",
            source_id=LISTING_ID,
            source_version=1,
            language="und",
            invalidated_at=now,
            now=now,
        )
        self.assertEqual({"PENDING": 1}, await operations.deletion_counts())
        claimed = await operations.claim_deletions("integration-worker", now)
        self.assertEqual(1, len(claimed))
        self.assertEqual(1, claimed[0].source_version)
        self.assertTrue(
            await operations.finish_deletion(
                claimed[0].deletion_id,
                "integration-worker",
                succeeded=True,
                now=now,
            )
        )
        self.assertEqual({"SUCCEEDED": 1}, await operations.deletion_counts())

    async def test_claim_isolation_retry_and_expired_claim_recovery(self) -> None:
        now = datetime.now(UTC)
        await self.repository.enqueue_event(
            "integration-consumer",
            event("01E00000000000000000000001"),
            now,
        )
        await self.repository.enqueue_event(
            "integration-consumer",
            event("01E00000000000000000000002", version=2),
            now,
        )

        first_claim = await self.repository.claim_jobs("worker-a", now)
        second_claim = await self.repository.claim_jobs("worker-b", now)
        self.assertEqual(1, len(first_claim))
        self.assertEqual(1, len(second_claim))
        self.assertNotEqual(first_claim[0].job_id, second_claim[0].job_id)

        retried = await self.repository.schedule_retry(
            first_claim[0].job_id,
            "worker-a",
            now + timedelta(seconds=1),
            "SOURCE_TIMEOUT",
        )
        self.assertTrue(retried)
        replay_claim = await self.repository.claim_jobs(
            "worker-c",
            now + timedelta(seconds=2),
        )
        self.assertEqual(first_claim[0].job_id, replay_claim[0].job_id)
        self.assertEqual(2, replay_claim[0].attempt_count)

        exhausted = await self.repository.mark_dead_letter(
            replay_claim[0].job_id,
            "worker-c",
            now + timedelta(seconds=3),
            "MAX_ATTEMPTS_EXHAUSTED",
        )
        succeeded = await self.repository.mark_succeeded(
            second_claim[0].job_id,
            "worker-b",
            now + timedelta(seconds=3),
        )
        self.assertTrue(exhausted)
        self.assertTrue(succeeded)
        self.assertEqual(
            {"DEAD_LETTER": 1, "SUCCEEDED": 1},
            await self.repository.job_counts(),
        )

    async def test_source_state_is_idempotent_monotonic_and_tombstoned(
        self,
    ) -> None:
        now = datetime.now(UTC)
        await self.repository.enqueue_event(
            "integration-consumer",
            event("01E00000000000000000000001"),
            now,
        )
        first = (await self.repository.claim_jobs("worker-a", now))[0]
        active_arguments = {
            "content_hash": "a" * 64,
            "chunker_version": "listing-chunker-v1",
            "embedding_provider": "openai",
            "embedding_model": "text-embedding-3-small",
            "embedding_dimensions": 1536,
            "indexed_at": now,
        }

        self.assertEqual(
            SourceStateApplyResult.APPLIED,
            await self.repository.apply_active_source_state(
                first,
                **active_arguments,
            ),
        )
        self.assertEqual(
            SourceStateApplyResult.IDEMPOTENT,
            await self.repository.apply_active_source_state(
                first,
                **active_arguments,
            ),
        )
        self.assertEqual(
            SourceStateApplyResult.CONFLICT,
            await self.repository.apply_active_source_state(
                first,
                **{**active_arguments, "content_hash": "b" * 64},
            ),
        )

        second = replace(
            first,
            job_id="01J00000000000000000000002",
            event_id="01E00000000000000000000002",
            source_version=2,
            superseded_version=1,
            event_occurred_at=now + timedelta(seconds=1),
        )
        self.assertEqual(
            SourceStateApplyResult.APPLIED,
            await self.repository.apply_active_source_state(
                second,
                **{**active_arguments, "content_hash": "b" * 64},
            ),
        )
        self.assertEqual(
            SourceStateApplyResult.STALE,
            await self.repository.apply_active_source_state(
                first,
                **active_arguments,
            ),
        )

        tombstone = replace(
            second,
            job_id="01J00000000000000000000003",
            event_id="01E00000000000000000000003",
            source_version=3,
            lifecycle="INVALIDATED",
            superseded_version=2,
            event_occurred_at=now + timedelta(seconds=2),
        )
        self.assertEqual(
            SourceStateApplyResult.APPLIED,
            await self.repository.apply_tombstone_source_state(
                tombstone,
                invalidated_at=now + timedelta(seconds=2),
            ),
        )
        state = await self.repository.get_source_state(
            "LISTING",
            LISTING_ID,
            "und",
        )
        self.assertIsNotNone(state)
        assert state is not None
        self.assertEqual(3, state.latest_observed_version)
        self.assertEqual(2, state.latest_indexed_version)
        self.assertEqual("TOMBSTONED", state.state)
        self.assertEqual(2, state.last_superseded_version)

    async def _connect_repository(self) -> KnowledgeJobRepository:
        deadline = asyncio.get_running_loop().time() + 60
        while True:
            try:
                return await KnowledgeJobRepository.create(self.settings)
            except Exception:
                if asyncio.get_running_loop().time() >= deadline:
                    raise
                await asyncio.sleep(1)

    async def _apply_migration(self) -> None:
        migration_directory = Path(__file__).parents[1] / "db" / "migration"
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                for migration_path in sorted(migration_directory.glob("V*.sql")):
                    statements = [
                        statement.strip()
                        for statement in migration_path.read_text(
                            encoding="utf-8"
                        ).split(";")
                        if statement.strip()
                    ]
                    for statement in statements:
                        await cursor.execute(statement)
            await connection.commit()


if __name__ == "__main__":
    unittest.main()
