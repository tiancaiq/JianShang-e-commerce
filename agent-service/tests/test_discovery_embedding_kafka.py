from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace

from aiokafka import TopicPartition

from msb_agent_service.config import (
    DiscoveryEmbeddingSettings,
    KnowledgeIngestionSettings,
)
from msb_agent_service.discovery_embedding_jobs import (
    DiscoveryEmbeddingEnqueueResult,
)
from msb_agent_service.discovery_embedding_kafka import (
    DiscoveryEmbeddingKafkaIntake,
)
from msb_agent_service.discovery_embedding_worker import DiscoveryEmbeddingMetrics

try:
    from test_discovery_embedding_worker import (
        LISTING_ID,
        event_body,
    )
except ModuleNotFoundError:
    from tests.test_discovery_embedding_worker import (
        LISTING_ID,
        event_body,
    )


class FakeRepository:
    def __init__(self) -> None:
        self.validated = 0
        self.enqueued = 0
        self.result = DiscoveryEmbeddingEnqueueResult.ACCEPTED
        self.error: Exception | None = None
        self.listing_versions: list[int] = []

    async def validate_schema(self):
        self.validated += 1

    async def enqueue(self, event, accepted_at):
        if self.error is not None:
            raise self.error
        self.enqueued += 1
        self.listing_versions.append(event.payload.listing_version)
        return self.result


class FakeConsumer:
    def __init__(self) -> None:
        self.started = 0
        self.stopped = 0
        self.subscriptions: list[list[str]] = []
        self.commits: list[object] = []
        self.seeks: list[tuple[object, int]] = []
        self.release = asyncio.Event()

    def subscribe(self, topics, listener):
        self.subscriptions.append(topics)

    async def start(self):
        self.started += 1

    async def stop(self):
        self.stopped += 1

    async def getmany(self, **kwargs):
        await self.release.wait()
        return {}

    async def commit(self, offsets):
        self.commits.append(offsets)

    def seek(self, partition, offset):
        self.seeks.append((partition, offset))


class DiscoveryEmbeddingKafkaIntakeTests(unittest.IsolatedAsyncioTestCase):
    async def test_disabled_start_creates_no_consumer_or_repository_call(self) -> None:
        repository = FakeRepository()
        created = 0

        def factory(settings):
            nonlocal created
            created += 1
            return FakeConsumer()

        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=KnowledgeIngestionSettings(),
            settings=DiscoveryEmbeddingSettings(),
            repository=repository,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            consumer_factory=factory,
        )
        await intake.start()
        self.assertEqual(0, created)
        self.assertEqual(0, repository.validated)

    async def test_start_subscribes_only_to_dedicated_topic(self) -> None:
        repository = FakeRepository()
        consumer = FakeConsumer()
        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=KnowledgeIngestionSettings(),
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            consumer_factory=lambda settings: consumer,
        )
        await intake.start()
        self.assertEqual(1, repository.validated)
        self.assertEqual(
            [["listing-discovery-embedding-request-v1"]],
            consumer.subscriptions,
        )
        consumer.release.set()
        await intake.stop()
        self.assertEqual(1, consumer.stopped)

    async def test_commits_only_after_durable_enqueue_and_seeks_invalid_record(
        self,
    ) -> None:
        repository = FakeRepository()
        consumer = FakeConsumer()
        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=KnowledgeIngestionSettings(),
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            consumer_factory=lambda settings: consumer,
        )
        intake._consumer = consumer  # noqa: SLF001
        partition = TopicPartition("listing-discovery-embedding-request-v1", 0)
        valid = SimpleNamespace(
            topic="listing-discovery-embedding-request-v1",
            partition=0,
            offset=7,
            key=LISTING_ID.encode(),
            value=event_body(),
        )
        self.assertTrue(await intake.process_message(partition, valid))
        self.assertEqual(1, repository.enqueued)
        self.assertEqual(1, len(consumer.commits))

        invalid = SimpleNamespace(
            topic="listing-discovery-embedding-request-v1",
            partition=0,
            offset=8,
            key=LISTING_ID.encode(),
            value=b'{"unexpected":"body"}',
        )
        self.assertFalse(await intake.process_message(partition, invalid))
        self.assertEqual(1, repository.enqueued)
        self.assertEqual((partition, 8), consumer.seeks[-1])

    async def test_version_zero_records_are_committed_only_after_durable_enqueue(
        self,
    ) -> None:
        repository = FakeRepository()
        consumer = FakeConsumer()
        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=KnowledgeIngestionSettings(),
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            consumer_factory=lambda settings: consumer,
        )
        intake._consumer = consumer  # noqa: SLF001
        partition = TopicPartition("listing-discovery-embedding-request-v1", 0)
        version_zero = SimpleNamespace(
            topic="listing-discovery-embedding-request-v1",
            partition=0,
            offset=9,
            key=LISTING_ID.encode(),
            value=event_body(listing_version=0),
        )

        self.assertTrue(await intake.process_message(partition, version_zero))
        self.assertEqual([0], repository.listing_versions)
        self.assertEqual(1, len(consumer.commits))
        self.assertEqual([], consumer.seeks)

    async def test_persistence_failure_leaves_offset_uncommitted_for_restart(
        self,
    ) -> None:
        repository = FakeRepository()
        repository.error = RuntimeError("mysql unavailable")
        consumer = FakeConsumer()
        intake = DiscoveryEmbeddingKafkaIntake(
            connection_settings=KnowledgeIngestionSettings(),
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            consumer_factory=lambda settings: consumer,
        )
        intake._consumer = consumer  # noqa: SLF001
        partition = TopicPartition("listing-discovery-embedding-request-v1", 0)
        message = SimpleNamespace(
            topic="listing-discovery-embedding-request-v1",
            partition=0,
            offset=10,
            key=LISTING_ID.encode(),
            value=event_body(listing_version=0),
        )

        self.assertFalse(await intake.process_message(partition, message))
        self.assertEqual([], consumer.commits)
        self.assertEqual((partition, 10), consumer.seeks[-1])


if __name__ == "__main__":
    unittest.main()
