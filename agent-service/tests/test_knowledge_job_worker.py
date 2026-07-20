from __future__ import annotations

import unittest
from datetime import UTC, datetime
from unittest.mock import AsyncMock

from prometheus_client import CollectorRegistry

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.knowledge_ingestion import KnowledgeProcessingError
from msb_agent_service.knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from msb_agent_service.knowledge_job_worker import KnowledgeJobWorker
from msb_agent_service.knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobStatus,
    retry_delay_seconds,
)
from msb_agent_service.knowledge_source_client import (
    KnowledgeSourceError,
    KnowledgeSourceErrorCode,
)


def job(attempt_count: int = 1) -> KnowledgeIngestionJob:
    return KnowledgeIngestionJob(
        job_id="01J00000000000000000000001",
        event_id="01E00000000000000000000001",
        source_type="LISTING",
        source_id="01L00000000000000000000001",
        source_version=1,
        language="und",
        lifecycle="ACTIVE",
        superseded_version=None,
        event_occurred_at=datetime(2026, 7, 19, tzinfo=UTC),
        payload_hash="a" * 64,
        status=KnowledgeJobStatus.PROCESSING,
        attempt_count=attempt_count,
    )


class KnowledgeJobWorkerTest(unittest.IsolatedAsyncioTestCase):
    async def test_retryable_source_failure_schedules_bounded_retry(self) -> None:
        repository = AsyncMock()
        repository.claim_jobs.return_value = [job()]
        repository.schedule_retry.return_value = True
        repository.job_counts.return_value = {"RETRY_WAIT": 1}
        repository.oldest_pending_age_seconds.return_value = 3.0

        async def processor(claimed_job):
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.TIMEOUT,
                retryable=True,
            )

        worker = KnowledgeJobWorker(
            repository,
            KnowledgeIngestionSettings(
                job_retry_base_seconds=2,
                job_retry_max_seconds=10,
            ),
            KnowledgeIngestionMetrics(CollectorRegistry()),
            processor,
            claim_owner="worker-a",
            clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
            jitter=lambda: 0.0,
        )

        processed = await worker.run_once()

        self.assertEqual(1, processed)
        repository.schedule_retry.assert_awaited_once()
        retry_call = repository.schedule_retry.await_args.args
        self.assertEqual("SOURCE_TIMEOUT", retry_call[3])
        self.assertEqual(
            datetime(2026, 7, 19, 0, 0, 2, tzinfo=UTC),
            retry_call[2],
        )
        repository.mark_dead_letter.assert_not_awaited()

    async def test_permanent_source_failure_dead_letters(self) -> None:
        repository = AsyncMock()
        repository.claim_jobs.return_value = [job()]
        repository.mark_dead_letter.return_value = True
        repository.job_counts.return_value = {"DEAD_LETTER": 1}
        repository.oldest_pending_age_seconds.return_value = 0.0

        async def processor(claimed_job):
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAUTHORIZED,
                retryable=False,
            )

        worker = KnowledgeJobWorker(
            repository,
            KnowledgeIngestionSettings(),
            KnowledgeIngestionMetrics(CollectorRegistry()),
            processor,
            claim_owner="worker-a",
            clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
        )

        await worker.run_once()

        repository.mark_dead_letter.assert_awaited_once_with(
            job().job_id,
            "worker-a",
            datetime(2026, 7, 19, tzinfo=UTC),
            "SOURCE_UNAUTHORIZED",
        )
        repository.schedule_retry.assert_not_awaited()

    async def test_processor_string_error_code_dead_letters_safely(self) -> None:
        repository = AsyncMock()
        repository.claim_jobs.return_value = [job()]
        repository.mark_dead_letter.return_value = True
        repository.job_counts.return_value = {"DEAD_LETTER": 1}
        repository.oldest_pending_age_seconds.return_value = 0.0

        async def processor(claimed_job):
            raise KnowledgeProcessingError(
                "OPENAI_QUOTA_EXHAUSTED",
                retryable=False,
            )

        worker = KnowledgeJobWorker(
            repository,
            KnowledgeIngestionSettings(),
            KnowledgeIngestionMetrics(CollectorRegistry()),
            processor,
            claim_owner="worker-a",
            clock=lambda: datetime(2026, 7, 19, tzinfo=UTC),
        )

        await worker.run_once()

        repository.mark_dead_letter.assert_awaited_once_with(
            job().job_id,
            "worker-a",
            datetime(2026, 7, 19, tzinfo=UTC),
            "OPENAI_QUOTA_EXHAUSTED",
        )

    def test_retry_delay_is_exponential_and_capped(self) -> None:
        self.assertEqual(2.0, retry_delay_seconds(1, 2.0, 10.0, 0.0))
        self.assertEqual(4.0, retry_delay_seconds(2, 2.0, 10.0, 0.0))
        self.assertEqual(10.0, retry_delay_seconds(8, 2.0, 10.0, 0.5))


if __name__ == "__main__":
    unittest.main()
