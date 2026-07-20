from __future__ import annotations

from datetime import UTC, datetime, timedelta

from .config import KnowledgeIngestionSettings
from .knowledge_index import KnowledgeIndexWriter
from .knowledge_index_client import KnowledgeIndexError
from .knowledge_jobs import retry_delay_seconds
from .knowledge_operations import KnowledgeOperationsRepository


class KnowledgeDeletionWorker:
    """Retries exact physical cleanup after tombstones become retrieval-safe."""

    def __init__(
        self,
        repository: KnowledgeOperationsRepository,
        settings: KnowledgeIngestionSettings,
        writer: KnowledgeIndexWriter,
        *,
        claim_owner: str,
    ) -> None:
        self._repository = repository
        self._settings = settings
        self._writer = writer
        self._claim_owner = claim_owner

    async def run_once(self) -> int:
        """Process one bounded deletion claim without accepting raw queries."""

        jobs = await self._repository.claim_deletions(
            self._claim_owner,
            datetime.now(UTC),
            self._settings.job_claim_batch_size,
        )
        for job in jobs:
            now = datetime.now(UTC)
            try:
                await self._writer.delete_source(
                    job.source_type,
                    job.source_id,
                    str(job.source_version),
                )
                await self._repository.finish_deletion(
                    job.deletion_id,
                    self._claim_owner,
                    succeeded=True,
                    now=now,
                )
            except KnowledgeIndexError as error:
                exhausted = job.attempt_count >= self._settings.job_max_attempts
                retryable = error.retryable and not exhausted
                retry_at = None
                if retryable:
                    delay = retry_delay_seconds(
                        job.attempt_count,
                        self._settings.job_retry_base_seconds,
                        self._settings.job_retry_max_seconds,
                        0.0,
                    )
                    retry_at = now + timedelta(seconds=delay)
                await self._repository.finish_deletion(
                    job.deletion_id,
                    self._claim_owner,
                    succeeded=False,
                    now=now,
                    error_code=error.code.value,
                    retry_at=retry_at,
                )
        return len(jobs)
