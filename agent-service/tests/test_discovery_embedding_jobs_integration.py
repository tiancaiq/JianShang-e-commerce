from __future__ import annotations

import asyncio
import json
import os
import unittest
from copy import deepcopy
from datetime import UTC, datetime, timedelta
from pathlib import Path

from testcontainers.core.container import DockerContainer

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.discovery_embedding_contract import (
    EVENT_TOPIC,
    DiscoveryEmbeddingContractError,
    DiscoveryEmbeddingContractErrorCode,
    parse_discovery_embedding_event,
)
from msb_agent_service.discovery_embedding_jobs import (
    DiscoveryEmbeddingEnqueueResult,
    DiscoveryEmbeddingRecoveryError,
    DiscoveryEmbeddingRecoveryErrorCode,
    DiscoveryEmbeddingRecoveryOutcome,
    DiscoveryEmbeddingJobRepository,
    DiscoveryEmbeddingJobStatus,
)

RUN_INTEGRATION = os.getenv("RUN_MYSQL_INTEGRATION") == "1"
EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"
NOW = datetime(2026, 7, 23, 10, tzinfo=UTC)


def event_value(
    *,
    event_id: str = EVENT_ID,
    request_id: str = REQUEST_ID,
    document_hash: str = "a" * 64,
    listing_version: int = 7,
) -> dict[str, object]:
    return {
        "eventId": event_id,
        "eventType": "listing.discovery.embedding-requested",
        "eventVersion": 1,
        "occurredAt": "2026-07-23T10:00:00Z",
        "producer": "product-service",
        "aggregateType": "listing",
        "aggregateId": LISTING_ID,
        "correlationId": "correlation-04b",
        "payload": {
            "requestId": request_id,
            "listingId": LISTING_ID,
            "listingVersion": listing_version,
            "documentSchemaVersion": "MARKETPLACE_LISTING_DISCOVERY_V2",
            "documentHash": document_hash,
            "embeddingInputSchemaVersion":
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
            "embeddingInputHash": "b" * 64,
            "normalizerVersion": "NFKC_WHITESPACE_V1",
            "redactorVersion": "PUBLIC_CONTACT_REDACTION_V1",
            "language": "und",
            "embeddingIdentity": {
                "provider": "openai",
                "model": "text-embedding-3-small",
                "dimensions": 1536,
            },
        },
    }


def event(**updates):
    value = event_value(**updates)
    return parse_discovery_embedding_event(
        topic=EVENT_TOPIC,
        message_key=LISTING_ID,
        body=json.dumps(value).encode(),
    )


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_MYSQL_INTEGRATION=1 to run MySQL integration tests",
)
class DiscoveryEmbeddingJobRepositoryIntegrationTests(
    unittest.IsolatedAsyncioTestCase
):
    container: DockerContainer
    settings: KnowledgeIngestionSettings
    migration_apply_count = 0

    @classmethod
    def setUpClass(cls) -> None:
        cls.migration_apply_count = 0
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
            mysql_host=cls.container.get_container_host_ip(),
            mysql_port=int(cls.container.get_exposed_port(3306)),
            mysql_database="agent",
            mysql_username="agent",
            mysql_password="agent-test-password",
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    async def asyncSetUp(self) -> None:
        self.repository = await self._connect_repository()
        try:
            await self.repository.validate_schema()
        except RuntimeError as error:
            self.assertEqual(
                "DISCOVERY_EMBEDDING_SCHEMA_NOT_MIGRATED",
                str(error),
            )
            await self._apply_migrations()
            await self.repository.validate_schema()
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute("DELETE FROM discovery_embedding_jobs")
            await connection.commit()

    async def asyncTearDown(self) -> None:
        await self.repository.close()

    async def test_v1_through_v11_schema_and_privacy_columns(self) -> None:
        await self.repository.validate_schema()
        await self.repository.validate_recovery_schema()
        self.assertEqual(1, type(self).migration_apply_count)
        columns = await self._column_names()
        for forbidden in (
            "embedding_text",
            "raw_event",
            "vector",
            "provider_response",
            "prompt",
            "actor_user_id",
            "seller_id",
        ):
            self.assertNotIn(forbidden, columns)
        self.assertIn(
            "discovery_embedding_recovery_commands",
            await self._table_names(),
        )

    async def test_event_and_request_replay_and_conflicts(self) -> None:
        accepted = await self.repository.enqueue(event(), NOW)
        replay = await self.repository.enqueue(event(), NOW)
        self.assertEqual(DiscoveryEmbeddingEnqueueResult.ACCEPTED, accepted)
        self.assertEqual(DiscoveryEmbeddingEnqueueResult.DUPLICATE, replay)

        with self.assertRaises(DiscoveryEmbeddingContractError) as event_conflict:
            await self.repository.enqueue(
                event(document_hash="d" * 64),
                NOW,
            )
        self.assertEqual(
            DiscoveryEmbeddingContractErrorCode.EVENT_ID_CONFLICT,
            event_conflict.exception.code,
        )

        with self.assertRaises(DiscoveryEmbeddingContractError) as request_conflict:
            await self.repository.enqueue(
                event(
                    event_id="01ARZ3NDEKTSV4RRFFQ69G5FAD",
                    document_hash="d" * 64,
                ),
                NOW,
            )
        self.assertEqual(
            DiscoveryEmbeddingContractErrorCode.REQUEST_ID_CONFLICT,
            request_conflict.exception.code,
        )
        self.assertEqual(1, await self._count())

    async def test_version_zero_event_is_durable_after_v10(self) -> None:
        accepted = await self.repository.enqueue(
            event(listing_version=0),
            NOW,
        )
        self.assertEqual(DiscoveryEmbeddingEnqueueResult.ACCEPTED, accepted)
        self.assertEqual(0, await self._listing_version())

    async def test_concurrent_exact_intake_creates_one_job(self) -> None:
        results = await asyncio.gather(*(
            self.repository.enqueue(event(), NOW)
            for _ in range(8)
        ))
        self.assertEqual(1, results.count(DiscoveryEmbeddingEnqueueResult.ACCEPTED))
        self.assertEqual(7, results.count(DiscoveryEmbeddingEnqueueResult.DUPLICATE))
        self.assertEqual(1, await self._count())

    async def test_claim_retry_lease_recovery_and_success(self) -> None:
        await self.repository.enqueue(event(), NOW)
        first = await self.repository.claim(
            claim_owner="worker-a",
            now=NOW,
            limit=1,
            claim_seconds=10,
            max_attempts=3,
        )
        self.assertEqual(1, first[0].attempt_count)

        before_expiry = await self.repository.claim(
            claim_owner="worker-b",
            now=NOW + timedelta(seconds=9),
            limit=1,
            claim_seconds=10,
            max_attempts=3,
        )
        self.assertEqual([], before_expiry)

        recovered = await self.repository.claim(
            claim_owner="worker-b",
            now=NOW + timedelta(seconds=11),
            limit=1,
            claim_seconds=10,
            max_attempts=3,
        )
        self.assertEqual(2, recovered[0].attempt_count)
        self.assertTrue(await self.repository.schedule_retry(
            job_id=recovered[0].job_id,
            claim_owner="worker-b",
            next_attempt_at=NOW + timedelta(seconds=20),
            error_code="SOURCE_TIMEOUT",
        ))
        retried = await self.repository.claim(
            claim_owner="worker-c",
            now=NOW + timedelta(seconds=20),
            limit=1,
            claim_seconds=10,
            max_attempts=3,
        )
        self.assertEqual(3, retried[0].attempt_count)
        self.assertTrue(await self.repository.mark_succeeded(
            job_id=retried[0].job_id,
            claim_owner="worker-c",
            completed_at=NOW + timedelta(seconds=21),
        ))
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.SUCCEEDED.value,
            await self._status(),
        )

    async def test_recovery_requeues_exact_dead_letters_once(self) -> None:
        await self._insert_dead_lettered_job()
        disabled = await self.repository.recover_dead_lettered_max_attempts(
            recovery_key="recovery-key-0001",
            expected_count=2,
            recovered_at=NOW,
        )
        self.assertEqual(
            DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH,
            disabled.outcome,
        )
        self.assertEqual(DiscoveryEmbeddingJobStatus.DEAD_LETTER.value, await self._status())

        result = await self.repository.recover_dead_lettered_max_attempts(
            recovery_key="recovery-key-0002",
            expected_count=1,
            recovered_at=NOW + timedelta(seconds=1),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, result.outcome)
        self.assertEqual(1, result.recovered_count)
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.RETRY_WAIT.value,
            await self._status(),
        )
        replay = await self.repository.recover_dead_lettered_max_attempts(
            recovery_key="recovery-key-0002",
            expected_count=1,
            recovered_at=NOW + timedelta(seconds=2),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.REPLAY, replay.outcome)
        self.assertEqual(1, replay.recovered_count)

        claimed = await self.repository.claim(
            claim_owner="worker-recovery",
            now=NOW + timedelta(seconds=3),
            limit=1,
            claim_seconds=10,
            max_attempts=8,
        )
        self.assertEqual(1, len(claimed))
        self.assertEqual(1, claimed[0].attempt_count)

    async def test_recovery_rejects_key_conflict_and_concurrent_replay(self) -> None:
        await self._insert_dead_lettered_job()
        results = await asyncio.gather(*(
            self.repository.recover_dead_lettered_max_attempts(
                recovery_key="recovery-key-0003",
                expected_count=1,
                recovered_at=NOW + timedelta(seconds=index),
            )
            for index in range(4)
        ))
        self.assertEqual(
            1,
            sum(
                item.outcome == DiscoveryEmbeddingRecoveryOutcome.RECOVERED
                for item in results
            ),
        )
        self.assertEqual(
            3,
            sum(
                item.outcome == DiscoveryEmbeddingRecoveryOutcome.REPLAY
                for item in results
            ),
        )
        with self.assertRaises(DiscoveryEmbeddingRecoveryError) as raised:
            await self.repository.recover_dead_lettered_max_attempts(
                recovery_key="recovery-key-0003",
                expected_count=2,
                recovered_at=NOW + timedelta(seconds=10),
            )
        self.assertEqual(
            DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_KEY_CONFLICT,
            raised.exception.code,
        )

    async def test_version_zero_callback_recovery_requeues_exact_jobs_once(self) -> None:
        await self._insert_callback_invalid_job()
        mismatch = (
            await self.repository.recover_product_version_zero_callback_contract(
                recovery_key="recovery-key-0004",
                expected_count=137,
                recovered_at=NOW,
            )
        )
        self.assertEqual(
            DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH,
            mismatch.outcome,
        )
        self.assertEqual(DiscoveryEmbeddingJobStatus.DEAD_LETTER.value, await self._status())

        result = await self.repository.recover_product_version_zero_callback_contract(
            recovery_key="recovery-key-0005",
            expected_count=1,
            recovered_at=NOW + timedelta(seconds=1),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, result.outcome)
        self.assertEqual(1, result.recovered_count)
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.RETRY_WAIT.value,
            await self._status(),
        )
        self.assertEqual(1, await self._attempt_count())
        self.assertEqual(
            "RECOVERED_PRODUCT_VERSION_ZERO_CALLBACK",
            await self._last_error_code(),
        )

        replay = await self.repository.recover_product_version_zero_callback_contract(
            recovery_key="recovery-key-0005",
            expected_count=1,
            recovered_at=NOW + timedelta(seconds=2),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.REPLAY, replay.outcome)
        self.assertEqual(1, replay.recovered_count)

        claimed = await self.repository.claim(
            claim_owner="worker-version-zero-recovery",
            now=NOW + timedelta(seconds=3),
            limit=1,
            claim_seconds=10,
            max_attempts=8,
        )
        self.assertEqual(1, len(claimed))
        self.assertEqual(2, claimed[0].attempt_count)
        self.assertEqual(0, claimed[0].listing_version)
        self.assertTrue(await self.repository.mark_succeeded(
            job_id=claimed[0].job_id,
            claim_owner="worker-version-zero-recovery",
            completed_at=NOW + timedelta(seconds=4),
        ))
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.SUCCEEDED.value,
            await self._status(),
        )

    async def test_version_zero_callback_recovery_rejects_mixed_sets_and_leases(
        self,
    ) -> None:
        await self._insert_callback_invalid_job()
        await self._insert_callback_invalid_job(
            event_id="01ARZ3NDEKTSV4RRFFQ69G5FAD",
            request_id="01ARZ3NDEKTSV4RRFFQ69G5FAE",
            listing_version=1,
        )
        await self._insert_callback_invalid_job(
            event_id="01ARZ3NDEKTSV4RRFFQ69G5FAF",
            request_id="01ARZ3NDEKTSV4RRFFQ69G5FAG",
            error_code="MAX_ATTEMPTS_EXHAUSTED",
        )
        await self._insert_callback_invalid_job(
            event_id="01ARZ3NDEKTSV4RRFFQ69G5FAH",
            request_id="01ARZ3NDEKTSV4RRFFQ69G5FAJ",
            claim_owner="worker-a",
        )

        result = await self.repository.recover_product_version_zero_callback_contract(
            recovery_key="recovery-key-0006",
            expected_count=4,
            recovered_at=NOW,
        )
        self.assertEqual(
            DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH,
            result.outcome,
        )
        self.assertEqual(0, result.recovered_count)
        self.assertEqual(3, await self._dead_letter_count())

        recovered = await self.repository.recover_product_version_zero_callback_contract(
            recovery_key="recovery-key-0007",
            expected_count=1,
            recovered_at=NOW + timedelta(seconds=1),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, recovered.outcome)
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.RETRY_WAIT.value,
            await self._status(),
        )
        self.assertEqual(2, await self._dead_letter_count())

    async def test_version_zero_callback_recovery_rejects_mode_hash_conflict(
        self,
    ) -> None:
        await self._insert_callback_invalid_job()
        result = await self.repository.recover_product_version_zero_callback_contract(
            recovery_key="recovery-key-0008",
            expected_count=1,
            recovered_at=NOW,
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, result.outcome)
        with self.assertRaises(DiscoveryEmbeddingRecoveryError) as raised:
            await self.repository.recover_dead_lettered_max_attempts(
                recovery_key="recovery-key-0008",
                expected_count=1,
                recovered_at=NOW + timedelta(seconds=1),
            )
        self.assertEqual(
            DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_KEY_CONFLICT,
            raised.exception.code,
        )

    async def test_version_zero_callback_recovery_concurrent_replay(self) -> None:
        await self._insert_callback_invalid_job()

        results = await asyncio.gather(*(
            self.repository.recover_product_version_zero_callback_contract(
                recovery_key="recovery-key-0009",
                expected_count=1,
                recovered_at=NOW + timedelta(seconds=index),
            )
            for index in range(4)
        ))

        self.assertEqual(
            1,
            sum(
                result.outcome == DiscoveryEmbeddingRecoveryOutcome.RECOVERED
                for result in results
            ),
        )
        self.assertEqual(
            3,
            sum(
                result.outcome == DiscoveryEmbeddingRecoveryOutcome.REPLAY
                for result in results
            ),
        )
        self.assertTrue(all(result.recovered_count == 1 for result in results))
        self.assertEqual(
            DiscoveryEmbeddingJobStatus.RETRY_WAIT.value,
            await self._status(),
        )

    async def _connect_repository(self) -> DiscoveryEmbeddingJobRepository:
        deadline = asyncio.get_running_loop().time() + 60
        while True:
            try:
                return await DiscoveryEmbeddingJobRepository.create(self.settings)
            except Exception:
                if asyncio.get_running_loop().time() >= deadline:
                    raise
                await asyncio.sleep(1)

    async def _apply_migrations(self) -> None:
        migration_directory = Path(__file__).parents[1] / "db" / "migration"
        migrations = sorted(
            migration_directory.glob("V*.sql"),
            key=lambda path: int(path.name.split("__", 1)[0][1:]),
        )
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                for migration_path in migrations:
                    for statement in (
                        item.strip()
                        for item in migration_path.read_text(
                            encoding="utf-8"
                        ).split(";")
                        if item.strip()
                    ):
                        await cursor.execute(statement)
            await connection.commit()
        type(self).migration_apply_count += 1

    async def _insert_dead_lettered_job(self) -> None:
        await self.repository.enqueue(event(), NOW)
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE discovery_embedding_jobs
                    SET status = 'DEAD_LETTER',
                        attempt_count = 8,
                        next_attempt_at = %s,
                        claim_owner = NULL,
                        claim_expires_at = NULL,
                        last_error_code = 'MAX_ATTEMPTS_EXHAUSTED',
                        completed_at = %s,
                        updated_at = %s
                    WHERE request_id = %s
                    """,
                    (NOW, NOW, NOW, REQUEST_ID),
                )
            await connection.commit()

    async def _insert_callback_invalid_job(
        self,
        *,
        event_id: str = EVENT_ID,
        request_id: str = REQUEST_ID,
        listing_version: int = 0,
        error_code: str = "CALLBACK_INVALID_RESPONSE",
        claim_owner: str | None = None,
    ) -> None:
        await self.repository.enqueue(
            event(
                event_id=event_id,
                request_id=request_id,
                listing_version=listing_version,
            ),
            NOW,
        )
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                status = "PROCESSING" if claim_owner else "DEAD_LETTER"
                completed_at = None if claim_owner else NOW
                await cursor.execute(
                    """
                    UPDATE discovery_embedding_jobs
                    SET status = %s,
                        attempt_count = 1,
                        next_attempt_at = %s,
                        claim_owner = %s,
                        claim_expires_at = %s,
                        last_error_code = %s,
                        completed_at = %s,
                        updated_at = %s
                    WHERE request_id = %s
                    """,
                    (
                        status,
                        NOW,
                        claim_owner,
                        NOW + timedelta(seconds=60) if claim_owner else None,
                        error_code,
                        completed_at,
                        NOW,
                        request_id,
                    ),
                )
            await connection.commit()

    async def _count(self) -> int:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute("SELECT COUNT(*) FROM discovery_embedding_jobs")
                row = await cursor.fetchone()
        return int(row[0])

    async def _status(self) -> str:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT status FROM discovery_embedding_jobs "
                    "WHERE request_id = %s",
                    (REQUEST_ID,),
                )
                row = await cursor.fetchone()
        return str(row[0])

    async def _listing_version(self) -> int:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT listing_version FROM discovery_embedding_jobs "
                    "WHERE request_id = %s",
                    (REQUEST_ID,),
                )
                row = await cursor.fetchone()
        return int(row[0])

    async def _attempt_count(self) -> int:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT attempt_count FROM discovery_embedding_jobs "
                    "WHERE request_id = %s",
                    (REQUEST_ID,),
                )
                row = await cursor.fetchone()
        return int(row[0])

    async def _last_error_code(self) -> str:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT last_error_code FROM discovery_embedding_jobs "
                    "WHERE request_id = %s",
                    (REQUEST_ID,),
                )
                row = await cursor.fetchone()
        return str(row[0])

    async def _dead_letter_count(self) -> int:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT COUNT(*) FROM discovery_embedding_jobs "
                    "WHERE status = 'DEAD_LETTER'"
                )
                row = await cursor.fetchone()
        return int(row[0])

    async def _column_names(self) -> set[str]:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'agent'
                      AND table_name = 'discovery_embedding_jobs'
                    """
                )
                rows = await cursor.fetchall()
        return {str(row[0]) for row in rows}

    async def _table_names(self) -> set[str]:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'agent'
                    """
                )
                rows = await cursor.fetchall()
        return {str(row[0]) for row in rows}


if __name__ == "__main__":
    unittest.main()
