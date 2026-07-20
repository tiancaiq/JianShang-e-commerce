from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from enum import StrEnum

from opensearchpy import AsyncOpenSearch
from .config import KnowledgeIngestionSettings, Settings
from .embedding_provider import OpenAIEmbeddingProvider
from .knowledge_chunker import CategoryGuidanceChunker, ListingKnowledgeChunker
from .knowledge_deletion_worker import KnowledgeDeletionWorker
from .knowledge_index import (
    KnowledgeIndexAdmin,
    KnowledgeIndexWriter,
    KnowledgeReadinessStatus,
    close_open_search_client,
)
from .knowledge_index_client import create_open_search_client
from .knowledge_index_metrics import KnowledgeIndexMetrics
from .knowledge_ingestion import (
    CategoryGuidanceProcessor,
    KnowledgeProcessorDispatcher,
    ListingKnowledgeProcessor,
)
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from .knowledge_job_worker import KnowledgeJobWorker
from .knowledge_jobs import KnowledgeJobRepository, new_ulid
from .knowledge_events import parse_category_guidance_event
from .knowledge_kafka import KnowledgeKafkaIntake
from .knowledge_operations import KnowledgeOperationsRepository
from .knowledge_source_client import KnowledgeSourceClient

logger = logging.getLogger(__name__)


class KnowledgeIngestionStatus(StrEnum):
    DISABLED = "DISABLED"
    READY = "READY"
    UNAVAILABLE = "UNAVAILABLE"


class KnowledgeIngestionRuntime:
    """Owns enabled 02B/02C resources and clean FastAPI lifespan shutdown."""

    def __init__(
        self,
        settings: KnowledgeIngestionSettings,
        repository: KnowledgeJobRepository,
        metrics: KnowledgeIngestionMetrics,
        source_client: KnowledgeSourceClient,
        operations_repository: KnowledgeOperationsRepository,
        intake: KnowledgeKafkaIntake,
        *,
        category_intake: KnowledgeKafkaIntake | None = None,
        worker: KnowledgeJobWorker | None = None,
        deletion_worker: KnowledgeDeletionWorker | None = None,
        embedding_provider: OpenAIEmbeddingProvider | None = None,
        open_search_client: AsyncOpenSearch | None = None,
        index_admin: KnowledgeIndexAdmin | None = None,
    ) -> None:
        self.settings = settings
        self.repository = repository
        self.metrics = metrics
        self.source_client = source_client
        self.operations_repository = operations_repository
        self.intake = intake
        self.category_intake = category_intake
        self.worker = worker
        self.deletion_worker = deletion_worker
        self.embedding_provider = embedding_provider
        self.open_search_client = open_search_client
        self.index_admin = index_admin
        self._metrics_task: asyncio.Task[None] | None = None
        self._worker_task: asyncio.Task[None] | None = None
        self._deletion_task: asyncio.Task[None] | None = None
        self._stop_metrics = asyncio.Event()
        self._stop_worker = asyncio.Event()
        self._stop_deletion = asyncio.Event()

    @classmethod
    async def create(
        cls,
        runtime_settings: Settings,
        index_metrics: KnowledgeIndexMetrics,
    ) -> "KnowledgeIngestionRuntime":
        """Create resources only after enabled configuration has validated."""

        settings = runtime_settings.knowledge_ingestion
        repository = await KnowledgeJobRepository.create(settings)
        operations_repository = await KnowledgeOperationsRepository.create(settings)
        metrics = KnowledgeIngestionMetrics(index_metrics.registry)
        source_client = KnowledgeSourceClient(settings)
        intake = KnowledgeKafkaIntake(settings, repository, metrics)
        category_intake = (
            KnowledgeKafkaIntake(
                settings,
                repository,
                metrics,
                topic=settings.category_guidance_kafka_topic,
                group_id=settings.category_guidance_kafka_group_id,
                event_parser=parse_category_guidance_event,
                task_name="category-guidance-kafka-intake",
            )
            if settings.category_guidance_intake_enabled
            else None
        )
        if not settings.processor_enabled:
            return cls(
                settings,
                repository,
                metrics,
                source_client,
                operations_repository,
                intake,
                category_intake=category_intake,
            )

        open_search_client: AsyncOpenSearch | None = None
        embedding_provider: OpenAIEmbeddingProvider | None = None
        try:
            knowledge_settings = runtime_settings.knowledge
            if (
                runtime_settings.openai_api_key is None
                or knowledge_settings.embedding_model is None
                or knowledge_settings.embedding_dimensions is None
            ):
                raise ValueError(
                    "Knowledge processor configuration is incomplete"
                )
            open_search_client = create_open_search_client(knowledge_settings)
            index_admin = KnowledgeIndexAdmin(
                open_search_client,
                knowledge_settings,
                index_metrics,
            )
            writer = KnowledgeIndexWriter(
                open_search_client,
                knowledge_settings,
                index_metrics,
                mirror_live_generations=True,
            )
            embedding_provider = OpenAIEmbeddingProvider(
                api_key=runtime_settings.openai_api_key,
                model=knowledge_settings.embedding_model,
                dimensions=knowledge_settings.embedding_dimensions,
                timeout_seconds=runtime_settings.openai_timeout_seconds,
                max_retries=runtime_settings.openai_max_retries,
                maximum_inputs=settings.embedding_max_inputs,
                maximum_input_tokens=settings.embedding_max_input_tokens,
                maximum_total_tokens=settings.embedding_max_total_tokens,
                metrics=metrics,
            )
            listing_chunker = ListingKnowledgeChunker(
                model=knowledge_settings.embedding_model,
                maximum_tokens=settings.chunk_max_tokens,
                overlap_tokens=settings.chunk_overlap_tokens,
                maximum_chunks=settings.chunk_max_count,
            )
            listing_processor = ListingKnowledgeProcessor(
                repository=repository,
                source_client=source_client,
                operations_repository=operations_repository,
                chunker=listing_chunker,
                embedding_provider=embedding_provider,
                writer=writer,
                metrics=metrics,
            )
            category_processor = None
            if settings.category_guidance_processing_enabled:
                category_processor = CategoryGuidanceProcessor(
                    repository=repository,
                    source_client=source_client,
                    operations_repository=operations_repository,
                    chunker=CategoryGuidanceChunker(
                        model=knowledge_settings.embedding_model,
                        maximum_tokens=settings.chunk_max_tokens,
                        overlap_tokens=settings.chunk_overlap_tokens,
                        maximum_chunks=settings.chunk_max_count,
                    ),
                    embedding_provider=embedding_provider,
                    writer=writer,
                    metrics=metrics,
                )
            processor = KnowledgeProcessorDispatcher(
                listing_processor,
                category_processor,
            )
            source_types = (
                ("LISTING", "CATEGORY_GUIDANCE")
                if settings.category_guidance_processing_enabled
                else ("LISTING",)
            )
            worker = KnowledgeJobWorker(
                repository,
                settings,
                metrics,
                processor,
                claim_owner=f"{settings.kafka_client_id}-{new_ulid()}",
                source_types=source_types,
            )
            deletion_worker = KnowledgeDeletionWorker(
                operations_repository,
                settings,
                writer,
                claim_owner=f"{settings.kafka_client_id}-delete-{new_ulid()}",
            )
            return cls(
                settings,
                repository,
                metrics,
                source_client,
                operations_repository,
                intake,
                category_intake=category_intake,
                worker=worker,
                deletion_worker=deletion_worker,
                embedding_provider=embedding_provider,
                open_search_client=open_search_client,
                index_admin=index_admin,
            )
        except BaseException:
            if embedding_provider is not None:
                await embedding_provider.close()
            if open_search_client is not None:
                await close_open_search_client(open_search_client)
            await source_client.close()
            await operations_repository.close()
            await repository.close()
            raise

    async def start(self) -> None:
        if self.index_admin is not None:
            status = await self.index_admin.status()
            if status.status != KnowledgeReadinessStatus.READY:
                raise RuntimeError(
                    f"KNOWLEDGE_INDEX_{status.status.value}"
                )
        await self.intake.start()
        if self.category_intake is not None:
            await self.category_intake.start()
        if self.worker is not None:
            self._stop_worker.clear()
            self._worker_task = asyncio.create_task(
                self._run_worker_loop(),
                name="knowledge-ingestion-worker",
            )
        if self.deletion_worker is not None:
            self._stop_deletion.clear()
            self._deletion_task = asyncio.create_task(
                self._run_deletion_loop(),
                name="knowledge-deletion-worker",
            )
        self._stop_metrics.clear()
        self._metrics_task = asyncio.create_task(
            self._refresh_metrics_loop(),
            name="knowledge-ingestion-metrics",
        )

    async def stop(self) -> None:
        await self.intake.stop()
        if self.category_intake is not None:
            await self.category_intake.stop()
        self._stop_worker.set()
        if self._worker_task is not None:
            try:
                await asyncio.wait_for(
                    asyncio.shield(self._worker_task),
                    timeout=10.0,
                )
            except TimeoutError:
                self._worker_task.cancel()
                try:
                    await self._worker_task
                except asyncio.CancelledError:
                    pass
            self._worker_task = None
        self._stop_deletion.set()
        if self._deletion_task is not None:
            try:
                await asyncio.wait_for(
                    asyncio.shield(self._deletion_task),
                    timeout=10.0,
                )
            except TimeoutError:
                self._deletion_task.cancel()
                try:
                    await self._deletion_task
                except asyncio.CancelledError:
                    pass
            self._deletion_task = None
        self._stop_metrics.set()
        if self._metrics_task is not None:
            self._metrics_task.cancel()
            try:
                await self._metrics_task
            except asyncio.CancelledError:
                pass
            self._metrics_task = None
        if self.embedding_provider is not None:
            await self.embedding_provider.close()
        if self.open_search_client is not None:
            await close_open_search_client(self.open_search_client)
        await self.source_client.close()
        await self.operations_repository.close()
        await self.repository.close()

    def status(self) -> KnowledgeIngestionStatus:
        intake_ready = self.intake.running
        category_intake_ready = (
            not self.settings.category_guidance_intake_enabled
            or (
                self.category_intake is not None
                and self.category_intake.running
            )
        )
        worker_ready = (
            not self.settings.processor_enabled
            or (
                self._worker_task is not None
                and not self._worker_task.done()
            )
        )
        deletion_ready = (
            not self.settings.processor_enabled
            or (
                self._deletion_task is not None
                and not self._deletion_task.done()
            )
        )
        return KnowledgeIngestionStatus.READY if (
            intake_ready
            and category_intake_ready
            and worker_ready
            and deletion_ready
        ) else KnowledgeIngestionStatus.UNAVAILABLE

    def category_intake_status(self) -> KnowledgeIngestionStatus:
        """Report category intake separately from the listing pipeline."""

        if not self.settings.category_guidance_intake_enabled:
            return KnowledgeIngestionStatus.DISABLED
        return (
            KnowledgeIngestionStatus.READY
            if self.category_intake is not None and self.category_intake.running
            else KnowledgeIngestionStatus.UNAVAILABLE
        )

    async def _run_worker_loop(self) -> None:
        if self.worker is None:
            return
        while not self._stop_worker.is_set():
            processed = await self.worker.run_once()
            if processed > 0:
                continue
            try:
                await asyncio.wait_for(
                    self._stop_worker.wait(),
                    timeout=self.settings.worker_poll_seconds,
                )
            except TimeoutError:
                pass

    async def _refresh_metrics_loop(self) -> None:
        while not self._stop_metrics.is_set():
            try:
                self.metrics.update_job_backlog(
                    await self.repository.job_counts(),
                    await self.repository.oldest_pending_age_seconds(
                        datetime.now(UTC)
                    ),
                )
                self.metrics.update_operation_backlog(
                    await self.operations_repository.deletion_counts(),
                    await self.operations_repository.rebuild_counts(),
                )
            except Exception:
                logger.exception(
                    "Knowledge ingestion metric refresh failed "
                    "errorCode=MYSQL_METRIC_REFRESH_FAILED"
                )
            try:
                await asyncio.wait_for(self._stop_metrics.wait(), timeout=10.0)
            except TimeoutError:
                pass

    async def _run_deletion_loop(self) -> None:
        if self.deletion_worker is None:
            return
        while not self._stop_deletion.is_set():
            processed = await self.deletion_worker.run_once()
            if processed > 0:
                continue
            try:
                await asyncio.wait_for(
                    self._stop_deletion.wait(),
                    timeout=self.settings.worker_poll_seconds,
                )
            except TimeoutError:
                pass
