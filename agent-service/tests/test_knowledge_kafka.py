from __future__ import annotations

import asyncio
import json
import unittest
from types import SimpleNamespace

from aiokafka import TopicPartition
from prometheus_client import CollectorRegistry

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from msb_agent_service.knowledge_jobs import EnqueueResult
from msb_agent_service.knowledge_kafka import KnowledgeKafkaIntake

LISTING_ID = "01L00000000000000000000001"
EVENT_ID = "01E00000000000000000000001"


def encoded_event() -> bytes:
    return json.dumps(
        {
            "eventId": EVENT_ID,
            "eventType": "listing.activated",
            "eventVersion": 1,
            "occurredAt": "2026-07-19T01:00:00Z",
            "producer": "product-service",
            "aggregateType": "listing",
            "aggregateId": LISTING_ID,
            "correlationId": "01C00000000000000000000001",
            "payload": {
                "listingId": LISTING_ID,
                "listingVersion": "1",
                "knowledgeLifecycle": "ACTIVE",
                "supersedesVersion": None,
                "language": "und",
            },
        }
    ).encode()


class FakeRepository:
    def __init__(
        self,
        operations: list[str],
        *,
        result: EnqueueResult = EnqueueResult.ACCEPTED,
        failure: Exception | None = None,
    ) -> None:
        self.operations = operations
        self.result = result
        self.failure = failure
        self.schema_validated = False

    async def validate_schema(self) -> None:
        self.schema_validated = True

    async def enqueue_event(self, consumer_name, event, accepted_at):
        self.operations.append("enqueue")
        if self.failure is not None:
            raise self.failure
        return self.result


class FakeConsumer:
    def __init__(self, operations: list[str]) -> None:
        self.operations = operations
        self.started = False
        self.stopped = False
        self.listener = None
        self.commits: list[dict[object, object]] = []
        self.seeks: list[tuple[object, int]] = []

    def subscribe(self, topics, listener) -> None:
        self.listener = listener
        self.topics = topics

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def getmany(self, *, timeout_ms, max_records):
        await asyncio.sleep(0.01)
        return {}

    async def commit(self, offsets) -> None:
        self.operations.append("commit")
        self.commits.append(offsets)

    def seek(self, partition, offset) -> None:
        self.operations.append("seek")
        self.seeks.append((partition, offset))


def message(value: bytes | None = None):
    return SimpleNamespace(
        topic="listing-knowledge-v1",
        partition=0,
        offset=7,
        key=LISTING_ID.encode(),
        value=encoded_event() if value is None else value,
    )


class KnowledgeKafkaIntakeTest(unittest.IsolatedAsyncioTestCase):
    async def test_commit_happens_only_after_durable_enqueue(self) -> None:
        operations: list[str] = []
        repository = FakeRepository(operations)
        consumer = FakeConsumer(operations)
        intake = KnowledgeKafkaIntake(
            KnowledgeIngestionSettings(),
            repository,
            KnowledgeIngestionMetrics(CollectorRegistry()),
            consumer_factory=lambda settings: consumer,
        )
        partition = TopicPartition("listing-knowledge-v1", 0)

        await intake.start()
        try:
            committed = await intake.process_message(partition, message())
        finally:
            await intake.stop()

        self.assertTrue(committed)
        self.assertEqual(["enqueue", "commit"], operations)
        self.assertTrue(repository.schema_validated)
        committed_offset = next(iter(consumer.commits[0].values()))
        self.assertEqual(8, committed_offset.offset)
        self.assertTrue(consumer.stopped)

    async def test_replayed_event_is_committed_as_duplicate(self) -> None:
        operations: list[str] = []
        repository = FakeRepository(
            operations,
            result=EnqueueResult.DUPLICATE,
        )
        consumer = FakeConsumer(operations)
        metrics = KnowledgeIngestionMetrics(CollectorRegistry())
        intake = KnowledgeKafkaIntake(
            KnowledgeIngestionSettings(),
            repository,
            metrics,
            consumer_factory=lambda settings: consumer,
        )

        await intake.start()
        try:
            committed = await intake.process_message(
                TopicPartition("listing-knowledge-v1", 0),
                message(),
            )
        finally:
            await intake.stop()

        self.assertTrue(committed)
        self.assertEqual(1, metrics.kafka_events.labels(result="duplicate")._value.get())

    async def test_database_failure_does_not_commit_and_rewinds(self) -> None:
        operations: list[str] = []
        repository = FakeRepository(
            operations,
            failure=RuntimeError("database unavailable"),
        )
        consumer = FakeConsumer(operations)
        intake = KnowledgeKafkaIntake(
            KnowledgeIngestionSettings(),
            repository,
            KnowledgeIngestionMetrics(CollectorRegistry()),
            consumer_factory=lambda settings: consumer,
        )
        partition = TopicPartition("listing-knowledge-v1", 0)

        await intake.start()
        try:
            committed = await intake.process_message(partition, message())
        finally:
            await intake.stop()

        self.assertFalse(committed)
        self.assertEqual(["enqueue", "seek"], operations)
        self.assertEqual([(partition, 7)], consumer.seeks)
        self.assertEqual([], consumer.commits)

    async def test_invalid_event_does_not_commit(self) -> None:
        operations: list[str] = []
        consumer = FakeConsumer(operations)
        intake = KnowledgeKafkaIntake(
            KnowledgeIngestionSettings(),
            FakeRepository(operations),
            KnowledgeIngestionMetrics(CollectorRegistry()),
            consumer_factory=lambda settings: consumer,
        )
        partition = TopicPartition("listing-knowledge-v1", 0)

        await intake.start()
        try:
            committed = await intake.process_message(
                partition,
                message(b"{"),
            )
        finally:
            await intake.stop()

        self.assertFalse(committed)
        self.assertEqual(["seek"], operations)
        self.assertEqual([], consumer.commits)


if __name__ == "__main__":
    unittest.main()
