from __future__ import annotations

import asyncio
import math
import secrets
import time
from datetime import UTC, datetime, timedelta
from typing import Callable, Protocol

from prometheus_client import CollectorRegistry, Counter, Histogram

from .config import DiscoveryEmbeddingSettings
from .discovery_embedding_clients import DiscoveryEmbeddingClientError
from .discovery_embedding_contract import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    EMBEDDING_PROVIDER,
    DiscoveryEmbeddingResult,
    DiscoveryEmbeddingSource,
    EmbeddingIdentity,
    parse_discovery_embedding_event,
)
from .discovery_embedding_jobs import (
    DiscoveryEmbeddingEnqueueResult,
    DiscoveryEmbeddingJob,
    DiscoveryEmbeddingJobStore,
    retry_delay_seconds,
)
from .embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProvider,
    EmbeddingProviderError,
)

_PROVIDER_METRIC_RESULTS = {
    "success",
    "OPENAI_AUTHENTICATION_FAILED",
    "OPENAI_MODEL_UNAVAILABLE",
    "OPENAI_QUOTA_EXHAUSTED",
    "OPENAI_RATE_LIMITED",
    "OPENAI_TIMED_OUT",
    "OPENAI_UNAVAILABLE",
    "OPENAI_INVALID_RESPONSE",
}


class DiscoveryEmbeddingSourceReader(Protocol):
    async def fetch(
        self,
        job: DiscoveryEmbeddingJob,
    ) -> tuple[DiscoveryEmbeddingSource, float]: ...


class DiscoveryEmbeddingResultWriter(Protocol):
    async def submit(
        self,
        *,
        job: DiscoveryEmbeddingJob,
        result: DiscoveryEmbeddingResult,
    ) -> float: ...


class DiscoveryEmbeddingProcessingError(RuntimeError):
    """Carries one bounded job result into durable retry policy."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class DiscoveryEmbeddingMetrics:
    """Records fixed outcomes and usage without event, request, listing, or text tags."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        selected = registry or CollectorRegistry()
        self._operations = Counter(
            "agent_discovery_document_embedding_operations_total",
            "Discovery document embedding operations by fixed stage and result.",
            ("stage", "result"),
            registry=selected,
        )
        self._latency = Histogram(
            "agent_discovery_document_embedding_duration_seconds",
            "Discovery document embedding stage latency.",
            ("stage",),
            registry=selected,
        )
        self._usage = Counter(
            "agent_discovery_document_embedding_input_tokens_total",
            "Provider-reported aggregate input tokens for discovery documents.",
            registry=selected,
        )

    def record(
        self,
        stage: str,
        result: str,
        *,
        duration_seconds: float | None = None,
        input_tokens: int = 0,
    ) -> None:
        self._operations.labels(stage=stage, result=result).inc()
        if duration_seconds is not None:
            self._latency.labels(stage=stage).observe(max(0.0, duration_seconds))
        if input_tokens > 0:
            self._usage.inc(input_tokens)

    def record_embedding(
        self,
        result: str,
        duration_seconds: float,
        *,
        input_tokens: int = 0,
    ) -> None:
        """Adapt the shared OpenAI embedding provider to document-worker metrics."""

        safe_result = (
            result
            if result in _PROVIDER_METRIC_RESULTS
            else "OPENAI_UNAVAILABLE"
        )
        self.record(
            "provider_client",
            safe_result,
            duration_seconds=duration_seconds,
            input_tokens=input_tokens,
        )


class DiscoveryEmbeddingIntake:
    """Validates Product 04A records before creating the separate durable job."""

    def __init__(
        self,
        *,
        settings: DiscoveryEmbeddingSettings,
        repository: DiscoveryEmbeddingJobStore,
        metrics: DiscoveryEmbeddingMetrics,
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._metrics = metrics

    async def accept(
        self,
        *,
        topic: str,
        message_key: bytes | str | None,
        body: bytes,
        accepted_at: datetime,
    ) -> DiscoveryEmbeddingEnqueueResult:
        """Apply gates before parsing or persistence, then durably deduplicate."""

        if (
            self._settings.kill_switch_enabled
            or not self._settings.intake_enabled
        ):
            self._metrics.record("intake", "disabled")
            return DiscoveryEmbeddingEnqueueResult.DISABLED
        event = parse_discovery_embedding_event(
            topic=topic,
            message_key=message_key,
            body=body,
        )
        result = await self._repository.enqueue(event, accepted_at)
        self._metrics.record("intake", result.value.lower())
        return result


class DiscoveryEmbeddingProcessor:
    """Keeps source text and vector ephemeral between Product read and callback."""

    def __init__(
        self,
        *,
        source_client: DiscoveryEmbeddingSourceReader,
        embedding_provider: EmbeddingProvider,
        callback_client: DiscoveryEmbeddingResultWriter,
        metrics: DiscoveryEmbeddingMetrics,
    ) -> None:
        self._source_client = source_client
        self._embedding_provider = embedding_provider
        self._callback_client = callback_client
        self._metrics = metrics

    async def process(self, job: DiscoveryEmbeddingJob) -> None:
        """Refetch, embed once, and await Product acknowledgement before return."""

        try:
            source, source_duration = await self._source_client.fetch(job)
            self._metrics.record(
                "source",
                "success",
                duration_seconds=source_duration,
            )
            embedding_started = time.monotonic()
            embedding = await self._embedding_provider.embed(
                [source.embedding_text],
                correlation_id=job.correlation_id,
            )
            embedding_duration = time.monotonic() - embedding_started
            vector = _validate_embedding(job, embedding)
            self._metrics.record(
                "provider",
                "success",
                duration_seconds=embedding_duration,
                input_tokens=embedding.input_tokens,
            )
            result = DiscoveryEmbeddingResult(
                listingVersion=job.listing_version,
                documentSchemaVersion=job.document_schema_version,
                documentHash=job.document_hash,
                embeddingInputSchemaVersion=job.embedding_input_schema_version,
                embeddingInputHash=job.embedding_input_hash,
                embeddingIdentity=EmbeddingIdentity(
                    provider=EMBEDDING_PROVIDER,
                    model=EMBEDDING_MODEL,
                    dimensions=EMBEDDING_DIMENSIONS,
                ),
                vector=list(vector),
            )
            callback_duration = await self._callback_client.submit(
                job=job,
                result=result,
            )
            self._metrics.record(
                "callback",
                "success",
                duration_seconds=callback_duration,
            )
        except DiscoveryEmbeddingClientError as error:
            stage = (
                "source"
                if error.code.value.startswith("SOURCE_")
                else "callback"
            )
            self._metrics.record(
                stage,
                "retryable_failure" if error.retryable else "terminal_failure",
            )
            raise DiscoveryEmbeddingProcessingError(
                error.code.value,
                retryable=error.retryable,
            ) from error
        except EmbeddingProviderError as error:
            self._metrics.record(
                "provider",
                "retryable_failure" if error.retryable else "terminal_failure",
            )
            raise DiscoveryEmbeddingProcessingError(
                error.code,
                retryable=error.retryable,
            ) from error


class DiscoveryEmbeddingWorker:
    """Runs bounded leased jobs without owning source text or embedding vectors."""

    def __init__(
        self,
        *,
        settings: DiscoveryEmbeddingSettings,
        repository: DiscoveryEmbeddingJobStore,
        processor: DiscoveryEmbeddingProcessor,
        metrics: DiscoveryEmbeddingMetrics,
        clock: Callable[[], datetime] | None = None,
        claim_owner: str | None = None,
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._processor = processor
        self._metrics = metrics
        self._clock = clock or (lambda: datetime.now(UTC))
        self._claim_owner = claim_owner or f"discovery-embedding-{secrets.token_hex(8)}"

    async def run_once(self) -> int:
        """Claim and finish one bounded batch; disabled gates do not touch storage."""

        if (
            self._settings.kill_switch_enabled
            or not self._settings.worker_enabled
            or not self._settings.provider_enabled
        ):
            self._metrics.record("worker", "disabled")
            return 0
        now = self._clock()
        jobs = await self._repository.claim(
            claim_owner=self._claim_owner,
            now=now,
            limit=self._settings.claim_batch_size,
            claim_seconds=self._settings.claim_seconds,
            max_attempts=self._settings.max_attempts,
        )
        semaphore = asyncio.Semaphore(self._settings.worker_concurrency)

        async def bounded(job: DiscoveryEmbeddingJob) -> None:
            async with semaphore:
                await self._process_claimed(job)

        await asyncio.gather(*(bounded(job) for job in jobs))
        return len(jobs)

    async def _process_claimed(self, job: DiscoveryEmbeddingJob) -> None:
        try:
            await self._processor.process(job)
        except asyncio.CancelledError:
            self._metrics.record("worker", "cancelled")
            raise
        except DiscoveryEmbeddingProcessingError as error:
            if error.retryable and job.attempt_count < self._settings.max_attempts:
                delay = retry_delay_seconds(
                    job.attempt_count,
                    base_seconds=self._settings.retry_base_seconds,
                    maximum_seconds=self._settings.retry_max_seconds,
                )
                updated = await self._repository.schedule_retry(
                    job_id=job.job_id,
                    claim_owner=self._claim_owner,
                    next_attempt_at=self._clock() + timedelta(seconds=delay),
                    error_code=error.code,
                )
                self._metrics.record(
                    "worker",
                    "retry" if updated else "lost_claim",
                )
                return
            updated = await self._repository.mark_terminal(
                job_id=job.job_id,
                claim_owner=self._claim_owner,
                completed_at=self._clock(),
                error_code=error.code,
            )
            self._metrics.record(
                "worker",
                "terminal" if updated else "lost_claim",
            )
            return
        except Exception:
            updated = await self._repository.schedule_retry(
                job_id=job.job_id,
                claim_owner=self._claim_owner,
                next_attempt_at=self._clock()
                + timedelta(seconds=self._settings.retry_base_seconds),
                error_code="UNEXPECTED_PROCESSING_FAILURE",
            )
            self._metrics.record(
                "worker",
                "retry" if updated else "lost_claim",
            )
            return
        updated = await self._repository.mark_succeeded(
            job_id=job.job_id,
            claim_owner=self._claim_owner,
            completed_at=self._clock(),
        )
        self._metrics.record(
            "worker",
            "success" if updated else "lost_claim",
        )


def _validate_embedding(
    job: DiscoveryEmbeddingJob,
    embedding: EmbeddingBatchResult,
) -> tuple[float, ...]:
    """Recheck provider identity, count, dimensions, and finite values."""

    if (
        embedding.provider != EMBEDDING_PROVIDER
        or embedding.model != EMBEDDING_MODEL
        or embedding.dimensions != EMBEDDING_DIMENSIONS
        or job.embedding_provider != EMBEDDING_PROVIDER
        or job.embedding_model != EMBEDDING_MODEL
        or job.embedding_dimensions != EMBEDDING_DIMENSIONS
        or len(embedding.vectors) != 1
        or len(embedding.vectors[0]) != EMBEDDING_DIMENSIONS
        or any(not math.isfinite(value) for value in embedding.vectors[0])
        or embedding.input_tokens < 0
    ):
        raise DiscoveryEmbeddingProcessingError(
            "EMBEDDING_RESULT_INVALID",
            retryable=False,
        )
    return embedding.vectors[0]
