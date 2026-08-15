from __future__ import annotations

import asyncio
import logging
from enum import StrEnum

from prometheus_client import CollectorRegistry

from .config import Settings
from .discovery_embedding_clients import (
    ProductDiscoveryEmbeddingCallbackClient,
    ProductDiscoveryEmbeddingSourceClient,
)
from .discovery_embedding_contract import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from .discovery_embedding_jobs import DiscoveryEmbeddingJobRepository
from .discovery_embedding_kafka import DiscoveryEmbeddingKafkaIntake
from .discovery_embedding_worker import (
    DiscoveryEmbeddingMetrics,
    DiscoveryEmbeddingProcessor,
    DiscoveryEmbeddingWorker,
)
from .embedding_provider import EmbeddingProvider, OpenAIEmbeddingProvider

logger = logging.getLogger(__name__)


class DiscoveryEmbeddingRuntimeStatus(StrEnum):
    DISABLED = "DISABLED"
    READY = "READY"
    UNAVAILABLE = "UNAVAILABLE"
    DEFERRED = "DEFERRED"


class _Utf8ByteEncoding:
    """Bounds document embedding input without loading network tokenizer assets."""

    @staticmethod
    def encode(value: str) -> bytes:
        return value.encode("utf-8")


class DiscoveryEmbeddingRuntime:
    """Owns Product discovery-document embedding intake and worker lifecycle."""

    def __init__(
        self,
        *,
        settings: Settings,
        repository: DiscoveryEmbeddingJobRepository,
        metrics: DiscoveryEmbeddingMetrics,
        source_client: ProductDiscoveryEmbeddingSourceClient | None,
        callback_client: ProductDiscoveryEmbeddingCallbackClient | None,
        embedding_provider: EmbeddingProvider | None,
        intake: DiscoveryEmbeddingKafkaIntake,
        worker: DiscoveryEmbeddingWorker | None,
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._source_client = source_client
        self._callback_client = callback_client
        self._embedding_provider = embedding_provider
        self._intake = intake
        self._worker = worker
        self._metrics = metrics
        self._stop_worker = asyncio.Event()
        self._worker_task: asyncio.Task[None] | None = None
        self._started = False

    @classmethod
    async def create(
        cls,
        settings: Settings,
        registry: CollectorRegistry,
    ) -> "DiscoveryEmbeddingRuntime":
        """Create enabled resources without contacting Product, Kafka, or OpenAI."""

        embedding_settings = settings.discovery_embedding
        if (
            embedding_settings.kill_switch_enabled
            or not (
                embedding_settings.intake_enabled
                or embedding_settings.worker_enabled
            )
        ):
            raise RuntimeError("Discovery embedding runtime is disabled")
        if (
            settings.knowledge_ingestion.product_service_url is None
            or settings.knowledge_ingestion.product_service_token is None
        ):
            raise RuntimeError("Discovery embedding runtime configuration is incomplete")
        repository = await DiscoveryEmbeddingJobRepository.create(
            settings.knowledge_ingestion
        )
        metrics = DiscoveryEmbeddingMetrics(registry)
        source_client: ProductDiscoveryEmbeddingSourceClient | None = None
        callback_client: ProductDiscoveryEmbeddingCallbackClient | None = None
        embedding_provider: EmbeddingProvider | None = None
        worker: DiscoveryEmbeddingWorker | None = None
        if embedding_settings.worker_enabled:
            if settings.openai_api_key is None:
                raise RuntimeError(
                    "Discovery embedding worker configuration is incomplete"
                )
            source_client = ProductDiscoveryEmbeddingSourceClient(
                base_url=settings.knowledge_ingestion.product_service_url,
                service_token=settings.knowledge_ingestion.product_service_token,
                timeout_seconds=embedding_settings.source_timeout_seconds,
            )
            callback_client = ProductDiscoveryEmbeddingCallbackClient(
                base_url=settings.knowledge_ingestion.product_service_url,
                service_token=settings.knowledge_ingestion.product_service_token,
                timeout_seconds=embedding_settings.callback_timeout_seconds,
            )
            embedding_provider = OpenAIEmbeddingProvider(
                api_key=settings.openai_api_key,
                model=EMBEDDING_MODEL,
                dimensions=EMBEDDING_DIMENSIONS,
                timeout_seconds=settings.openai_timeout_seconds,
                max_retries=settings.openai_max_retries,
                maximum_inputs=1,
                maximum_input_tokens=(
                    settings.knowledge_ingestion.embedding_max_input_tokens
                ),
                maximum_total_tokens=(
                    settings.knowledge_ingestion.embedding_max_input_tokens
                ),
                metrics=metrics,
                encoding=_Utf8ByteEncoding(),
            )
            processor = DiscoveryEmbeddingProcessor(
                source_client=source_client,
                embedding_provider=embedding_provider,
                callback_client=callback_client,
                metrics=metrics,
            )
            worker = DiscoveryEmbeddingWorker(
                settings=embedding_settings,
                repository=repository,
                processor=processor,
                metrics=metrics,
            )
        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=settings.knowledge_ingestion,
            settings=embedding_settings,
            repository=repository,
            metrics=metrics,
        )
        return cls(
            settings=settings,
            repository=repository,
            metrics=metrics,
            source_client=source_client,
            callback_client=callback_client,
            embedding_provider=embedding_provider,
            intake=intake,
            worker=worker,
        )

    async def start(self) -> None:
        """Validate V9, start Kafka intake first, then start bounded worker loop."""

        if self._started:
            raise RuntimeError("Discovery embedding runtime has already started")
        await self._repository.validate_schema()
        await self._intake.start()
        if self._worker is not None:
            self._stop_worker.clear()
            self._worker_task = asyncio.create_task(
                self._run_worker_loop(),
                name="discovery-embedding-worker",
            )
        self._started = True

    async def stop(self) -> None:
        """Stop worker before Kafka intake, then close outbound and DB resources."""

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
        try:
            await self._intake.stop()
        finally:
            if self._embedding_provider is not None:
                await self._embedding_provider.close()
            if self._callback_client is not None:
                await self._callback_client.close()
            if self._source_client is not None:
                await self._source_client.close()
            await self._repository.close()
            self._started = False

    def status(self) -> DiscoveryEmbeddingRuntimeStatus:
        settings = self._settings.discovery_embedding
        if settings.kill_switch_enabled or not (
            settings.intake_enabled or settings.worker_enabled
        ):
            return DiscoveryEmbeddingRuntimeStatus.DISABLED
        if not self._started:
            return DiscoveryEmbeddingRuntimeStatus.UNAVAILABLE
        intake_ready = not settings.intake_enabled or self._intake.running
        worker_ready = (
            not settings.worker_enabled
            or (
                self._worker_task is not None
                and not self._worker_task.done()
            )
        )
        return (
            DiscoveryEmbeddingRuntimeStatus.READY
            if intake_ready and worker_ready
            else DiscoveryEmbeddingRuntimeStatus.UNAVAILABLE
        )

    async def _run_worker_loop(self) -> None:
        while not self._stop_worker.is_set():
            try:
                if self._worker is None:
                    return
                processed = await self._worker.run_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                self._metrics.record("worker", "loop_failure")
                logger.warning(
                    "Discovery embedding worker loop failed "
                    "errorCode=DISCOVERY_EMBEDDING_WORKER_LOOP_FAILED"
                )
                processed = 0
            if processed > 0:
                continue
            try:
                await asyncio.wait_for(
                    self._stop_worker.wait(),
                    timeout=1.0,
                )
            except TimeoutError:
                pass
