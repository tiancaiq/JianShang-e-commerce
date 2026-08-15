from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import replace
from datetime import UTC, datetime
from types import SimpleNamespace

from prometheus_client import CollectorRegistry

from msb_agent_service.config import DiscoveryEmbeddingSettings
from msb_agent_service.discovery_embedding_clients import (
    DiscoveryEmbeddingClientError,
    DiscoveryEmbeddingClientErrorCode,
)
from msb_agent_service.discovery_embedding_contract import (
    EVENT_TOPIC,
    DiscoveryEmbeddingSource,
    EmbeddingIdentity,
)
from msb_agent_service.discovery_embedding_jobs import (
    DiscoveryEmbeddingEnqueueResult,
    DiscoveryEmbeddingJob,
    DiscoveryEmbeddingJobStatus,
)
from msb_agent_service.discovery_embedding_worker import (
    DiscoveryEmbeddingIntake,
    DiscoveryEmbeddingMetrics,
    DiscoveryEmbeddingProcessingError,
    DiscoveryEmbeddingProcessor,
    DiscoveryEmbeddingWorker,
)
from msb_agent_service.embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProviderError,
    OpenAIEmbeddingProvider,
)

EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"
NOW = datetime(2026, 7, 23, 10, tzinfo=UTC)
PRIVATE_TEXT = "TITLE\nDesk\nDESCRIPTION\nprivate-user@example.test"


def event_body(*, listing_version: int = 7) -> bytes:
    return json.dumps({
        "eventId": EVENT_ID,
        "eventType": "listing.discovery.embedding-requested",
        "eventVersion": 1,
        "occurredAt": "2026-07-23T10:00:00Z",
        "producer": "product-service",
        "aggregateType": "listing",
        "aggregateId": LISTING_ID,
        "correlationId": "correlation-04b",
        "payload": {
            "requestId": REQUEST_ID,
            "listingId": LISTING_ID,
            "listingVersion": listing_version,
            "documentSchemaVersion": "MARKETPLACE_LISTING_DISCOVERY_V2",
            "documentHash": "a" * 64,
            "embeddingInputSchemaVersion":
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
            "embeddingInputHash": "b" * 64,
            "normalizerVersion": "NFKC_WHITESPACE_V1",
            "redactorVersion": "PUBLIC_CONTACT_REDACTION_V1",
            "language": "und",
            "embeddingIdentity": {
                "provider": "openai",
                "model": "text-embedding-3-small",
                "dimensions": 1536,
            },
        },
    }).encode()


def job(attempt_count: int = 1, listing_version: int = 7) -> DiscoveryEmbeddingJob:
    return DiscoveryEmbeddingJob(
        job_id="01ARZ3NDEKTSV4RRFFQ69G5FAD",
        event_id=EVENT_ID,
        request_id=REQUEST_ID,
        listing_id=LISTING_ID,
        listing_version=listing_version,
        document_schema_version="MARKETPLACE_LISTING_DISCOVERY_V2",
        document_hash="a" * 64,
        embedding_input_schema_version="MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
        embedding_input_hash="b" * 64,
        normalizer_version="NFKC_WHITESPACE_V1",
        redactor_version="PUBLIC_CONTACT_REDACTION_V1",
        language="und",
        embedding_provider="openai",
        embedding_model="text-embedding-3-small",
        embedding_dimensions=1536,
        event_occurred_at=NOW,
        correlation_id="correlation-04b",
        event_payload_hash="c" * 64,
        status=DiscoveryEmbeddingJobStatus.PROCESSING,
        attempt_count=attempt_count,
    )


def source(*, listing_version: int = 7) -> DiscoveryEmbeddingSource:
    return DiscoveryEmbeddingSource(
        schemaVersion="MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1",
        requestId=REQUEST_ID,
        listingId=LISTING_ID,
        listingVersion=listing_version,
        documentSchemaVersion="MARKETPLACE_LISTING_DISCOVERY_V2",
        documentHash="a" * 64,
        embeddingInputSchemaVersion="MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
        embeddingInputHash="b" * 64,
        normalizerVersion="NFKC_WHITESPACE_V1",
        redactorVersion="PUBLIC_CONTACT_REDACTION_V1",
        language="und",
        embeddingIdentity=EmbeddingIdentity(
            provider="openai",
            model="text-embedding-3-small",
            dimensions=1536,
        ),
        embeddingText=PRIVATE_TEXT,
    )


class FakeRepository:
    def __init__(self) -> None:
        self.enqueue_calls: list[object] = []
        self.claim_calls = 0
        self.claimed: list[DiscoveryEmbeddingJob] = []
        self.succeeded: list[str] = []
        self.terminal: list[tuple[str, str]] = []
        self.retried: list[tuple[str, str, datetime]] = []

    async def enqueue(self, event, accepted_at):
        self.enqueue_calls.append((event, accepted_at))
        return DiscoveryEmbeddingEnqueueResult.ACCEPTED

    async def claim(self, **kwargs):
        self.claim_calls += 1
        return list(self.claimed)

    async def mark_succeeded(self, **kwargs):
        self.succeeded.append(kwargs["job_id"])
        return True

    async def mark_terminal(self, **kwargs):
        self.terminal.append((kwargs["job_id"], kwargs["error_code"]))
        return True

    async def schedule_retry(self, **kwargs):
        self.retried.append((
            kwargs["job_id"],
            kwargs["error_code"],
            kwargs["next_attempt_at"],
        ))
        return True


class FakeSourceClient:
    def __init__(
        self,
        error: Exception | None = None,
        *,
        listing_version: int = 7,
    ) -> None:
        self.error = error
        self.listing_version = listing_version
        self.calls: list[str] = []

    async def fetch(self, selected_job):
        self.calls.append(selected_job.request_id)
        if self.error is not None:
            raise self.error
        return source(listing_version=self.listing_version), 0.01


class FakeEmbeddingProvider:
    def __init__(
        self,
        *,
        error: Exception | None = None,
        dimensions: int = 1536,
        value: float = 0.25,
    ) -> None:
        self.error = error
        self.dimensions = dimensions
        self.value = value
        self.calls: list[tuple[list[str], str | None]] = []

    async def embed(self, texts, *, correlation_id):
        self.calls.append((list(texts), correlation_id))
        if self.error is not None:
            raise self.error
        return EmbeddingBatchResult(
            vectors=((self.value,) * self.dimensions,),
            provider="openai",
            model="text-embedding-3-small",
            dimensions=self.dimensions,
            input_tokens=12,
        )

    async def close(self):
        return None


class FakeCallbackClient:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[object] = []

    async def submit(self, *, job, result):
        self.calls.append((job, result))
        if self.error is not None:
            raise self.error
        return 0.02


class FakeOpenAIEmbeddings:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(
            model="text-embedding-3-small",
            data=[
                SimpleNamespace(
                    index=0,
                    embedding=[0.125] * 1536,
                )
            ],
            usage=SimpleNamespace(total_tokens=14),
        )


class FakeOpenAIClient:
    def __init__(self) -> None:
        self.embeddings = FakeOpenAIEmbeddings()
        self.closed = 0

    async def close(self) -> None:
        self.closed += 1


class DiscoveryEmbeddingIntakeTests(unittest.IsolatedAsyncioTestCase):
    async def test_disabled_and_kill_switch_precede_parsing_and_persistence(self) -> None:
        for settings in (
            DiscoveryEmbeddingSettings(),
            DiscoveryEmbeddingSettings(
                intake_enabled=True,
                kill_switch_enabled=True,
            ),
        ):
            repository = FakeRepository()
            intake = DiscoveryEmbeddingIntake(
                settings=settings,
                repository=repository,
                metrics=DiscoveryEmbeddingMetrics(),
            )
            result = await intake.accept(
                topic="malformed-topic",
                message_key=None,
                body=b"not-json",
                accepted_at=NOW,
            )
            self.assertEqual(DiscoveryEmbeddingEnqueueResult.DISABLED, result)
            self.assertEqual([], repository.enqueue_calls)

    async def test_enabled_intake_validates_then_persists_only_metadata(self) -> None:
        repository = FakeRepository()
        intake = DiscoveryEmbeddingIntake(
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,
            metrics=DiscoveryEmbeddingMetrics(),
        )
        result = await intake.accept(
            topic=EVENT_TOPIC,
            message_key=LISTING_ID,
            body=event_body(),
            accepted_at=NOW,
        )
        self.assertEqual(DiscoveryEmbeddingEnqueueResult.ACCEPTED, result)
        self.assertEqual(1, len(repository.enqueue_calls))
        persisted_event = repository.enqueue_calls[0][0]
        self.assertNotIn(PRIVATE_TEXT, repr(persisted_event))

    async def test_intake_accepts_product_zero_listing_version_event(self) -> None:
        repository = FakeRepository()
        intake = DiscoveryEmbeddingIntake(
            settings=DiscoveryEmbeddingSettings(intake_enabled=True),
            repository=repository,
            metrics=DiscoveryEmbeddingMetrics(),
        )
        result = await intake.accept(
            topic=EVENT_TOPIC,
            message_key=LISTING_ID,
            body=event_body(listing_version=0),
            accepted_at=NOW,
        )

        self.assertEqual(DiscoveryEmbeddingEnqueueResult.ACCEPTED, result)
        persisted_event = repository.enqueue_calls[0][0]
        self.assertEqual(0, persisted_event.payload.listing_version)


class DiscoveryEmbeddingProcessorTests(unittest.IsolatedAsyncioTestCase):
    async def test_success_embeds_once_and_submits_ephemeral_vector(self) -> None:
        source_client = FakeSourceClient(listing_version=0)
        provider = FakeEmbeddingProvider()
        callback = FakeCallbackClient()
        processor = DiscoveryEmbeddingProcessor(
            source_client=source_client,  # type: ignore[arg-type]
            embedding_provider=provider,  # type: ignore[arg-type]
            callback_client=callback,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
        )
        await processor.process(job(listing_version=0))

        self.assertEqual([REQUEST_ID], source_client.calls)
        self.assertEqual([([PRIVATE_TEXT], "correlation-04b")], provider.calls)
        self.assertEqual(1, len(callback.calls))
        callback_result = callback.calls[0][1]
        self.assertEqual(1536, len(callback_result.vector))
        self.assertNotIn(PRIVATE_TEXT, callback_result.model_dump_json())

    async def test_production_openai_provider_metrics_reaches_product_callback(
        self,
    ) -> None:
        openai_client = FakeOpenAIClient()
        source_client = FakeSourceClient()
        callback = FakeCallbackClient()
        metrics = DiscoveryEmbeddingMetrics()
        provider = OpenAIEmbeddingProvider(
            api_key="offline-openai-placeholder",
            model="text-embedding-3-small",
            dimensions=1536,
            timeout_seconds=1,
            max_retries=0,
            maximum_inputs=1,
            maximum_input_tokens=8_000,
            maximum_total_tokens=8_000,
            metrics=metrics,
            client=openai_client,
            encoding=SimpleNamespace(encode=lambda value: value.encode("utf-8")),
        )
        processor = DiscoveryEmbeddingProcessor(
            source_client=source_client,  # type: ignore[arg-type]
            embedding_provider=provider,
            callback_client=callback,  # type: ignore[arg-type]
            metrics=metrics,
        )

        await processor.process(job())

        self.assertEqual(1, len(openai_client.embeddings.calls))
        self.assertEqual(1, len(callback.calls))
        self.assertNotIn(PRIVATE_TEXT, repr(callback.calls))

    async def test_provider_transient_and_malformed_results_are_classified(self) -> None:
        cases = (
            (
                FakeEmbeddingProvider(
                    error=EmbeddingProviderError(
                        "EMBEDDING_RATE_LIMITED",
                        retryable=True,
                    )
                ),
                "EMBEDDING_RATE_LIMITED",
                True,
            ),
            (
                FakeEmbeddingProvider(dimensions=3),
                "EMBEDDING_RESULT_INVALID",
                False,
            ),
            (
                FakeEmbeddingProvider(value=float("nan")),
                "EMBEDDING_RESULT_INVALID",
                False,
            ),
        )
        for provider, code, retryable in cases:
            with self.subTest(code=code):
                callback = FakeCallbackClient()
                processor = DiscoveryEmbeddingProcessor(
                    source_client=FakeSourceClient(),  # type: ignore[arg-type]
                    embedding_provider=provider,  # type: ignore[arg-type]
                    callback_client=callback,  # type: ignore[arg-type]
                    metrics=DiscoveryEmbeddingMetrics(),
                )
                with self.assertRaises(DiscoveryEmbeddingProcessingError) as raised:
                    await processor.process(job())
                self.assertEqual(code, raised.exception.code)
                self.assertEqual(retryable, raised.exception.retryable)
                self.assertEqual([], callback.calls)

    async def test_source_failure_stops_before_provider_and_callback(self) -> None:
        source_client = FakeSourceClient(
            DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_STALE,
                retryable=False,
            )
        )
        provider = FakeEmbeddingProvider()
        callback = FakeCallbackClient()
        processor = DiscoveryEmbeddingProcessor(
            source_client=source_client,  # type: ignore[arg-type]
            embedding_provider=provider,  # type: ignore[arg-type]
            callback_client=callback,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
        )
        with self.assertRaises(DiscoveryEmbeddingProcessingError) as raised:
            await processor.process(job())
        self.assertEqual("SOURCE_STALE", raised.exception.code)
        self.assertEqual([], provider.calls)
        self.assertEqual([], callback.calls)


class FakeProcessor:
    def __init__(self, error: BaseException | None = None) -> None:
        self.error = error
        self.calls = 0

    async def process(self, selected_job):
        self.calls += 1
        if self.error is not None:
            raise self.error


class DiscoveryEmbeddingWorkerTests(unittest.IsolatedAsyncioTestCase):
    def settings(self, **updates) -> DiscoveryEmbeddingSettings:
        return replace(
            DiscoveryEmbeddingSettings(),
            worker_enabled=True,
            provider_enabled=True,
            claim_batch_size=2,
            worker_concurrency=2,
            **updates,
        )

    async def test_disabled_kill_and_provider_gates_make_zero_repository_calls(self) -> None:
        configurations = (
            DiscoveryEmbeddingSettings(),
            DiscoveryEmbeddingSettings(
                worker_enabled=True,
                provider_enabled=True,
                kill_switch_enabled=True,
            ),
            DiscoveryEmbeddingSettings(worker_enabled=True),
        )
        for settings in configurations:
            repository = FakeRepository()
            processor = FakeProcessor()
            worker = DiscoveryEmbeddingWorker(
                settings=settings,
                repository=repository,
                processor=processor,  # type: ignore[arg-type]
                metrics=DiscoveryEmbeddingMetrics(),
                clock=lambda: NOW,
                claim_owner="worker-1",
            )
            self.assertEqual(0, await worker.run_once())
            self.assertEqual(0, repository.claim_calls)
            self.assertEqual(0, processor.calls)

    async def test_success_marks_complete_only_after_processor_acknowledges(self) -> None:
        repository = FakeRepository()
        repository.claimed = [job()]
        processor = FakeProcessor()
        worker = DiscoveryEmbeddingWorker(
            settings=self.settings(),
            repository=repository,
            processor=processor,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            clock=lambda: NOW,
            claim_owner="worker-1",
        )
        self.assertEqual(1, await worker.run_once())
        self.assertEqual([job().job_id], repository.succeeded)
        self.assertEqual([], repository.retried)
        self.assertEqual([], repository.terminal)

    async def test_callback_unknown_outcome_retries_without_success(self) -> None:
        repository = FakeRepository()
        repository.claimed = [job()]
        processor = FakeProcessor(
            DiscoveryEmbeddingProcessingError(
                "CALLBACK_TIMEOUT",
                retryable=True,
            )
        )
        worker = DiscoveryEmbeddingWorker(
            settings=self.settings(),
            repository=repository,
            processor=processor,  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            clock=lambda: NOW,
            claim_owner="worker-1",
        )
        await worker.run_once()
        self.assertEqual([], repository.succeeded)
        self.assertEqual("CALLBACK_TIMEOUT", repository.retried[0][1])
        self.assertEqual(5.0, (
            repository.retried[0][2] - NOW
        ).total_seconds())

    async def test_terminal_failure_and_retry_exhaustion_do_not_complete(self) -> None:
        cases = (
            (job(), "SOURCE_STALE", False),
            (job(attempt_count=8), "SOURCE_TIMEOUT", True),
        )
        for selected_job, code, retryable in cases:
            with self.subTest(code=code):
                repository = FakeRepository()
                repository.claimed = [selected_job]
                worker = DiscoveryEmbeddingWorker(
                    settings=self.settings(),
                    repository=repository,
                    processor=FakeProcessor(  # type: ignore[arg-type]
                        DiscoveryEmbeddingProcessingError(
                            code,
                            retryable=retryable,
                        )
                    ),
                    metrics=DiscoveryEmbeddingMetrics(),
                    clock=lambda: NOW,
                    claim_owner="worker-1",
                )
                await worker.run_once()
                self.assertEqual([], repository.succeeded)
                self.assertEqual(code, repository.terminal[0][1])

    async def test_cancellation_leaves_lease_for_restart_recovery(self) -> None:
        repository = FakeRepository()
        repository.claimed = [job()]
        worker = DiscoveryEmbeddingWorker(
            settings=self.settings(),
            repository=repository,
            processor=FakeProcessor(asyncio.CancelledError()),  # type: ignore[arg-type]
            metrics=DiscoveryEmbeddingMetrics(),
            clock=lambda: NOW,
            claim_owner="worker-1",
        )
        with self.assertRaises(asyncio.CancelledError):
            await worker.run_once()
        self.assertEqual([], repository.succeeded)
        self.assertEqual([], repository.retried)
        self.assertEqual([], repository.terminal)

    async def test_metrics_expose_only_low_cardinality_labels(self) -> None:
        registry = CollectorRegistry()
        metrics = DiscoveryEmbeddingMetrics(registry)
        metrics.record("worker", "success", input_tokens=12)
        samples = [
            sample
            for family in registry.collect()
            for sample in family.samples
        ]
        for sample in samples:
            self.assertTrue(set(sample.labels).issubset({"stage", "result"}))
            self.assertNotIn(LISTING_ID, repr(sample))
            self.assertNotIn(REQUEST_ID, repr(sample))


if __name__ == "__main__":
    unittest.main()
