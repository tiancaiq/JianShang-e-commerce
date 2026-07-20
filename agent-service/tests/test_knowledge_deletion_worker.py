from __future__ import annotations

import unittest
from datetime import UTC, datetime

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.knowledge_deletion_worker import KnowledgeDeletionWorker
from msb_agent_service.knowledge_index_client import (
    KnowledgeIndexError,
    KnowledgeIndexErrorCode,
)
from msb_agent_service.knowledge_operations import KnowledgeDeletionJob


def job(*, attempt_count: int = 1) -> KnowledgeDeletionJob:
    return KnowledgeDeletionJob(
        deletion_id="01D00000000000000000000001",
        source_type="LISTING",
        source_id="01L00000000000000000000001",
        source_version=2,
        language="und",
        invalidated_at=datetime(2026, 7, 19, 1, 0, tzinfo=UTC),
        attempt_count=attempt_count,
    )


class FakeRepository:
    def __init__(self, deletion: KnowledgeDeletionJob) -> None:
        self.deletion = deletion
        self.finishes: list[dict[str, object]] = []

    async def claim_deletions(self, *args: object) -> list[KnowledgeDeletionJob]:
        return [self.deletion]

    async def finish_deletion(
        self,
        deletion_id: str,
        claim_owner: str,
        **values: object,
    ) -> bool:
        self.finishes.append(
            {
                "deletion_id": deletion_id,
                "claim_owner": claim_owner,
                **values,
            }
        )
        return True


class FakeWriter:
    def __init__(self, failure: KnowledgeIndexError | None = None) -> None:
        self.failure = failure
        self.calls: list[tuple[object, ...]] = []

    async def delete_source(self, *args: object) -> int:
        self.calls.append(args)
        if self.failure is not None:
            raise self.failure
        return 0


class KnowledgeDeletionWorkerTest(unittest.IsolatedAsyncioTestCase):
    async def test_exact_not_found_cleanup_completes_idempotently(self) -> None:
        repository = FakeRepository(job())
        writer = FakeWriter()
        worker = KnowledgeDeletionWorker(
            repository,  # type: ignore[arg-type]
            KnowledgeIngestionSettings(),
            writer,  # type: ignore[arg-type]
            claim_owner="delete-worker",
        )

        self.assertEqual(1, await worker.run_once())

        self.assertEqual(
            ("LISTING", "01L00000000000000000000001", "2"),
            writer.calls[0],
        )
        self.assertTrue(repository.finishes[0]["succeeded"])

    async def test_retryable_failure_is_bounded_by_attempt_count(self) -> None:
        repository = FakeRepository(job(attempt_count=1))
        writer = FakeWriter(
            KnowledgeIndexError(
                KnowledgeIndexErrorCode.UNAVAILABLE,
                "safe",
                retryable=True,
            )
        )
        worker = KnowledgeDeletionWorker(
            repository,  # type: ignore[arg-type]
            KnowledgeIngestionSettings(job_max_attempts=2),
            writer,  # type: ignore[arg-type]
            claim_owner="delete-worker",
        )

        await worker.run_once()

        self.assertFalse(repository.finishes[0]["succeeded"])
        self.assertIsNotNone(repository.finishes[0]["retry_at"])
        self.assertEqual(
            KnowledgeIndexErrorCode.UNAVAILABLE.value,
            repository.finishes[0]["error_code"],
        )


if __name__ == "__main__":
    unittest.main()
