from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any, Callable

from aiokafka import (
    AIOKafkaConsumer,
    ConsumerRebalanceListener,
    OffsetAndMetadata,
    TopicPartition,
)

from .config import DiscoveryEmbeddingSettings, KnowledgeIngestionSettings
from .discovery_embedding_contract import DiscoveryEmbeddingContractError
from .discovery_embedding_jobs import (
    DiscoveryEmbeddingEnqueueResult,
    DiscoveryEmbeddingJobRepository,
)
from .discovery_embedding_worker import (
    DiscoveryEmbeddingIntake,
    DiscoveryEmbeddingMetrics,
)
from .knowledge_kafka import KafkaConsumerAdapter

logger = logging.getLogger(__name__)
ConsumerFactory = Callable[[KnowledgeIngestionSettings], KafkaConsumerAdapter]


class _DiscoveryEmbeddingRebalanceListener(ConsumerRebalanceListener):
    def __init__(self, lock: asyncio.Lock) -> None:
        self._lock = lock

    async def on_partitions_revoked(
        self,
        revoked: set[TopicPartition],
    ) -> None:
        """Wait for the current durable enqueue/commit boundary before revoke."""

        async with self._lock:
            logger.info(
                "Discovery embedding partitions revoked partitionCount=%s",
                len(revoked),
            )

    async def on_partitions_assigned(
        self,
        assigned: set[TopicPartition],
    ) -> None:
        logger.info(
            "Discovery embedding partitions assigned partitionCount=%s",
            len(assigned),
        )


class DiscoveryEmbeddingKafkaIntake:
    """Commits the dedicated Product topic only after durable Agent enqueue."""

    def __init__(
        self,
        *,
        connection_settings: KnowledgeIngestionSettings,
        settings: DiscoveryEmbeddingSettings,
        repository: DiscoveryEmbeddingJobRepository,
        metrics: DiscoveryEmbeddingMetrics,
        consumer_factory: ConsumerFactory | None = None,
    ) -> None:
        self._connection_settings = connection_settings
        self._settings = settings
        self._repository = repository
        self._metrics = metrics
        self._intake = DiscoveryEmbeddingIntake(
            settings=settings,
            repository=repository,
            metrics=metrics,
        )
        self._consumer_factory = consumer_factory or (
            lambda current: _create_discovery_consumer(
                current,
                group_id=settings.kafka_group_id,
                client_id=f"{current.kafka_client_id}-discovery-embedding",
            )
        )
        self._consumer: KafkaConsumerAdapter | None = None
        self._task: asyncio.Task[None] | None = None
        self._stop = asyncio.Event()
        self._lock = asyncio.Lock()

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        """Start no consumer while disabled or while the kill switch is engaged."""

        if not self._settings.intake_enabled or self._settings.kill_switch_enabled:
            return
        if self._task is not None:
            raise RuntimeError("Discovery embedding intake has already started")
        await self._repository.validate_schema()
        consumer = self._consumer_factory(self._connection_settings)
        consumer.subscribe(
            topics=[self._settings.kafka_topic],
            listener=_DiscoveryEmbeddingRebalanceListener(self._lock),
        )
        await consumer.start()
        self._consumer = consumer
        self._stop.clear()
        self._task = asyncio.create_task(
            self._run(),
            name="discovery-embedding-kafka-intake",
        )

    async def stop(self) -> None:
        self._stop.set()
        task = self._task
        if task is None:
            return
        try:
            await asyncio.wait_for(
                asyncio.shield(task),
                timeout=(self._connection_settings.consumer_poll_timeout_ms / 1000)
                + 5,
            )
        except TimeoutError:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        finally:
            self._task = None

    async def process_message(self, partition: Any, message: Any) -> bool:
        """Validate, persist, then commit one partition-ordered Product record."""

        consumer = self._consumer
        if consumer is None:
            raise RuntimeError("Discovery embedding Kafka intake has not started")
        async with self._lock:
            try:
                result = await self._intake.accept(
                    topic=message.topic,
                    message_key=message.key,
                    body=message.value,
                    accepted_at=datetime.now(UTC),
                )
            except DiscoveryEmbeddingContractError as error:
                self._metrics.record("kafka", error.code.value.lower())
                logger.warning(
                    "Discovery embedding record rejected partition=%s offset=%s "
                    "errorCode=%s",
                    message.partition,
                    message.offset,
                    error.code.value,
                )
                consumer.seek(partition, message.offset)
                return False
            except Exception:
                self._metrics.record("kafka", "persistence_failure")
                logger.warning(
                    "Discovery embedding durable enqueue failed partition=%s "
                    "offset=%s errorCode=MYSQL_ENQUEUE_FAILED",
                    message.partition,
                    message.offset,
                )
                consumer.seek(partition, message.offset)
                return False
            if result == DiscoveryEmbeddingEnqueueResult.DISABLED:
                consumer.seek(partition, message.offset)
                return False
            try:
                await consumer.commit({
                    partition: OffsetAndMetadata(message.offset + 1, "")
                })
            except Exception:
                self._metrics.record("kafka", "commit_failure")
                logger.warning(
                    "Discovery embedding offset commit failed partition=%s "
                    "offset=%s errorCode=KAFKA_COMMIT_FAILED",
                    message.partition,
                    message.offset,
                )
                consumer.seek(partition, message.offset)
                return False
            self._metrics.record("kafka", result.value.lower())
            return True

    async def _run(self) -> None:
        consumer = self._consumer
        if consumer is None:
            raise RuntimeError("Discovery embedding Kafka consumer is unavailable")
        try:
            while not self._stop.is_set():
                batches = await consumer.getmany(
                    timeout_ms=self._connection_settings.consumer_poll_timeout_ms,
                    max_records=self._connection_settings.consumer_batch_size,
                )
                for partition in sorted(
                    batches,
                    key=lambda value: (value.topic, value.partition),
                ):
                    for message in batches[partition]:
                        if self._stop.is_set():
                            return
                        if not await self.process_message(partition, message):
                            await asyncio.sleep(0.5)
                            break
        finally:
            await consumer.stop()
            self._consumer = None


def _create_discovery_consumer(
    settings: KnowledgeIngestionSettings,
    *,
    group_id: str,
    client_id: str,
) -> AIOKafkaConsumer:
    """Create the dedicated consumer without coupling to the RAG intake queue."""

    kwargs: dict[str, object] = {
        "bootstrap_servers": settings.kafka_bootstrap_servers,
        "group_id": group_id,
        "client_id": client_id,
        "enable_auto_commit": False,
        "auto_offset_reset": "earliest",
        "security_protocol": settings.kafka_security_protocol,
    }
    if settings.kafka_security_protocol.startswith("SASL_"):
        kwargs["sasl_mechanism"] = "PLAIN"
        kwargs["sasl_plain_username"] = settings.kafka_username
        kwargs["sasl_plain_password"] = settings.kafka_password
    return AIOKafkaConsumer(**kwargs)
