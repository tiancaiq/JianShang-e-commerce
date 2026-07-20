from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime
from typing import Any, Callable, Protocol

from aiokafka import (
    AIOKafkaConsumer,
    ConsumerRebalanceListener,
    OffsetAndMetadata,
    TopicPartition,
)

from .config import KnowledgeIngestionSettings
from .knowledge_events import (
    KnowledgeEventEnvelope,
    KnowledgeEventValidationError,
    parse_listing_knowledge_event,
)
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from .knowledge_jobs import EnqueueResult, KnowledgeJobStore

logger = logging.getLogger(__name__)


class KafkaConsumerAdapter(Protocol):
    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    def subscribe(
        self,
        topics: list[str],
        listener: ConsumerRebalanceListener,
    ) -> None: ...

    async def getmany(
        self,
        *,
        timeout_ms: int,
        max_records: int,
    ) -> dict[Any, list[Any]]: ...

    async def commit(self, offsets: dict[Any, OffsetAndMetadata]) -> None: ...

    def seek(self, partition: Any, offset: int) -> None: ...


ConsumerFactory = Callable[[KnowledgeIngestionSettings], KafkaConsumerAdapter]
EventParser = Callable[[bytes, bytes | None], KnowledgeEventEnvelope]


class _IntakeRebalanceListener(ConsumerRebalanceListener):
    def __init__(self, intake_lock: asyncio.Lock) -> None:
        self._intake_lock = intake_lock

    async def on_partitions_revoked(
        self, revoked: set[TopicPartition]
    ) -> None:
        """Wait for the current durable enqueue/commit boundary before revoke."""

        async with self._intake_lock:
            logger.info(
                "Knowledge Kafka partitions revoked partitionCount=%s",
                len(revoked),
            )

    async def on_partitions_assigned(
        self, assigned: set[TopicPartition]
    ) -> None:
        logger.info(
            "Knowledge Kafka partitions assigned partitionCount=%s",
            len(assigned),
        )


class KnowledgeKafkaIntake:
    """Consumes one allowlisted topic and commits only after durable enqueue."""

    def __init__(
        self,
        settings: KnowledgeIngestionSettings,
        repository: KnowledgeJobStore,
        metrics: KnowledgeIngestionMetrics,
        consumer_factory: ConsumerFactory | None = None,
        *,
        topic: str | None = None,
        group_id: str | None = None,
        event_parser: EventParser = parse_listing_knowledge_event,
        task_name: str = "knowledge-kafka-intake",
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._metrics = metrics
        self._topic = topic or settings.kafka_topic
        self._group_id = group_id or settings.kafka_group_id
        self._event_parser = event_parser
        self._task_name = task_name
        self._consumer_factory = consumer_factory or (
            lambda current: _create_consumer(
                current,
                group_id=self._group_id,
                client_id=f"{current.kafka_client_id}-{self._task_name}",
            )
        )
        self._consumer: KafkaConsumerAdapter | None = None
        self._task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._intake_lock = asyncio.Lock()

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        """Start Kafka after schema validation; never create schema at startup."""

        if self._task is not None:
            raise RuntimeError("Knowledge Kafka intake has already been started")
        await self._repository.validate_schema()
        consumer = self._consumer_factory(self._settings)
        consumer.subscribe(
            topics=[self._topic],
            listener=_IntakeRebalanceListener(self._intake_lock),
        )
        await consumer.start()
        self._consumer = consumer
        self._stop_event.clear()
        self._task = asyncio.create_task(
            self._run(),
            name=self._task_name,
        )

    async def stop(self) -> None:
        """Finish the in-flight transaction boundary and stop the consumer."""

        self._stop_event.set()
        task = self._task
        if task is None:
            return
        try:
            await asyncio.wait_for(
                asyncio.shield(task),
                timeout=(self._settings.consumer_poll_timeout_ms / 1000) + 5,
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
        """Validate, enqueue, and manually commit one partition-ordered record."""

        consumer = self._consumer
        if consumer is None:
            raise RuntimeError("Knowledge Kafka intake has not started")
        async with self._intake_lock:
            try:
                event = self._event_parser(message.value, message.key)
                result = await self._repository.enqueue_event(
                    self._group_id,
                    event,
                    datetime.now(UTC),
                )
            except KnowledgeEventValidationError as exc:
                self._metrics.record_event("invalid")
                logger.error(
                    "Knowledge Kafka record rejected topic=%s partition=%s "
                    "offset=%s errorCode=%s",
                    message.topic,
                    message.partition,
                    message.offset,
                    exc.code.value,
                )
                consumer.seek(partition, message.offset)
                return False
            except Exception:
                self._metrics.record_event("persistence_failure")
                logger.exception(
                    "Knowledge Kafka durable enqueue failed topic=%s "
                    "partition=%s offset=%s errorCode=MYSQL_ENQUEUE_FAILED",
                    message.topic,
                    message.partition,
                    message.offset,
                )
                consumer.seek(partition, message.offset)
                return False

            try:
                await consumer.commit(
                    {
                        partition: OffsetAndMetadata(
                            message.offset + 1,
                            "",
                        )
                    }
                )
            except Exception:
                self._metrics.record_commit("failure")
                logger.exception(
                    "Knowledge Kafka offset commit failed topic=%s partition=%s "
                    "offset=%s errorCode=KAFKA_COMMIT_FAILED",
                    message.topic,
                    message.partition,
                    message.offset,
                )
                consumer.seek(partition, message.offset)
                return False

            intake_result = (
                "accepted" if result == EnqueueResult.ACCEPTED else "duplicate"
            )
            self._metrics.record_event(intake_result)
            self._metrics.record_commit("success")
            logger.info(
                "Knowledge Kafka record durably enqueued eventId=%s "
                "sourceId=%s sourceVersion=%s result=%s correlationId=%s",
                event.event_id,
                event.source_id,
                event.source_version,
                intake_result,
                event.correlation_id,
            )
            return True

    async def _run(self) -> None:
        consumer = self._consumer
        if consumer is None:
            raise RuntimeError("Knowledge Kafka intake consumer is unavailable")
        try:
            while not self._stop_event.is_set():
                batches = await consumer.getmany(
                    timeout_ms=self._settings.consumer_poll_timeout_ms,
                    max_records=self._settings.consumer_batch_size,
                )
                for partition in sorted(
                    batches,
                    key=lambda value: (value.topic, value.partition),
                ):
                    for message in batches[partition]:
                        if self._stop_event.is_set():
                            return
                        committed = await self.process_message(partition, message)
                        if not committed:
                            await asyncio.sleep(0.5)
                            break
        finally:
            await consumer.stop()
            self._consumer = None


def _create_consumer(
    settings: KnowledgeIngestionSettings,
    *,
    group_id: str | None = None,
    client_id: str | None = None,
) -> AIOKafkaConsumer:
    kwargs: dict[str, object] = {
        "bootstrap_servers": settings.kafka_bootstrap_servers,
        "group_id": group_id or settings.kafka_group_id,
        "client_id": client_id or settings.kafka_client_id,
        "enable_auto_commit": False,
        "auto_offset_reset": "earliest",
        "security_protocol": settings.kafka_security_protocol,
    }
    if settings.kafka_security_protocol.startswith("SASL_"):
        kwargs["sasl_mechanism"] = "PLAIN"
        kwargs["sasl_plain_username"] = settings.kafka_username
        kwargs["sasl_plain_password"] = settings.kafka_password
    return AIOKafkaConsumer(**kwargs)
