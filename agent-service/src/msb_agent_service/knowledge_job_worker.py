from __future__ import annotations

import logging
import random
from datetime import UTC, datetime, timedelta
from typing import Awaitable, Callable, Protocol

from .config import KnowledgeIngestionSettings
from .knowledge_ingestion import KnowledgeProcessingError
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from .knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobRepository,
    retry_delay_seconds,
)
from .knowledge_source_client import KnowledgeSourceError

logger = logging.getLogger(__name__)


class KnowledgeJobProcessor(Protocol):
    async def __call__(self, job: KnowledgeIngestionJob) -> None: ...


class KnowledgeJobWorker:
    """Applies bounded claim/retry/dead-letter rules around source processing."""

    def __init__(
        self,
        repository: KnowledgeJobRepository,
        settings: KnowledgeIngestionSettings,
        metrics: KnowledgeIngestionMetrics,
        processor: KnowledgeJobProcessor,
        *,
        claim_owner: str,
        clock: Callable[[], datetime] | None = None,
        jitter: Callable[[], float] | None = None,
        source_types: tuple[str, ...] = ("LISTING",),
    ) -> None:
        self._repository = repository
        self._settings = settings
        self._metrics = metrics
        self._processor = processor
        self._claim_owner = claim_owner
        self._clock = clock or (lambda: datetime.now(UTC))
        self._jitter = jitter or random.random
        self._source_types = source_types

    async def run_once(self) -> int:
        """Process one bounded claimed batch; no runtime loop starts in 02B."""

        now = self._clock()
        jobs = await self._repository.claim_jobs(
            self._claim_owner,
            now,
            source_types=self._source_types,
        )
        for job in jobs:
            await self._process_claimed(job)
        await self.refresh_metrics()
        return len(jobs)

    async def refresh_metrics(self) -> None:
        now = self._clock()
        self._metrics.update_job_backlog(
            await self._repository.job_counts(),
            await self._repository.oldest_pending_age_seconds(now),
        )

    async def _process_claimed(self, job: KnowledgeIngestionJob) -> None:
        try:
            await self._processor(job)
        except (KnowledgeSourceError, KnowledgeProcessingError) as exc:
            error_code = (
                exc.code.value
                if hasattr(exc.code, "value")
                else str(exc.code)
            )
            if exc.retryable and job.attempt_count < self._settings.job_max_attempts:
                await self._retry(job, error_code)
            else:
                await self._dead_letter(job, error_code)
        except Exception:
            if job.attempt_count < self._settings.job_max_attempts:
                await self._retry(job, "JOB_PROCESSOR_TEMPORARY_FAILURE")
            else:
                await self._dead_letter(job, "MAX_ATTEMPTS_EXHAUSTED")
        else:
            completed = self._clock()
            changed = await self._repository.mark_succeeded(
                job.job_id,
                self._claim_owner,
                completed,
            )
            self._metrics.record_job_transition(
                "SUCCEEDED",
                "success" if changed else "lost_claim",
            )

    async def _retry(self, job: KnowledgeIngestionJob, error_code: str) -> None:
        delay = retry_delay_seconds(
            job.attempt_count,
            self._settings.job_retry_base_seconds,
            self._settings.job_retry_max_seconds,
            self._jitter(),
        )
        changed = await self._repository.schedule_retry(
            job.job_id,
            self._claim_owner,
            self._clock() + timedelta(seconds=delay),
            error_code,
        )
        self._metrics.record_job_transition(
            "RETRY_WAIT",
            "success" if changed else "lost_claim",
        )
        logger.warning(
            "Knowledge job scheduled for retry jobId=%s sourceId=%s "
            "sourceVersion=%s attempt=%s errorCode=%s",
            job.job_id,
            job.source_id,
            job.source_version,
            job.attempt_count,
            error_code,
        )

    async def _dead_letter(
        self,
        job: KnowledgeIngestionJob,
        error_code: str,
    ) -> None:
        changed = await self._repository.mark_dead_letter(
            job.job_id,
            self._claim_owner,
            self._clock(),
            error_code,
        )
        self._metrics.record_job_transition(
            "DEAD_LETTER",
            "success" if changed else "lost_claim",
        )
        logger.error(
            "Knowledge job dead-lettered jobId=%s sourceId=%s "
            "sourceVersion=%s attempt=%s errorCode=%s",
            job.job_id,
            job.source_id,
            job.source_version,
            job.attempt_count,
            error_code,
        )
