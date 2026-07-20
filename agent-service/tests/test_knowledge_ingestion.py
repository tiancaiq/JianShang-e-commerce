from __future__ import annotations

import unittest
from datetime import UTC, datetime

from prometheus_client import CollectorRegistry

from msb_agent_service.embedding_provider import EmbeddingBatchResult
from msb_agent_service.knowledge_chunker import ListingKnowledgeChunker
from msb_agent_service.knowledge_ingestion import (
    KnowledgeProcessingError,
    ListingKnowledgeProcessor,
)
from msb_agent_service.knowledge_ingestion_metrics import (
    KnowledgeIngestionMetrics,
)
from msb_agent_service.knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobStatus,
    SourceStateApplyResult,
)
from msb_agent_service.knowledge_sanitizer import (
    canonical_listing_source_hash,
)
from msb_agent_service.knowledge_source_client import ListingKnowledgeSource

LISTING_ID = "01L00000000000000000000001"
NOW = datetime(2026, 7, 19, 1, 0, tzinfo=UTC)


class FakeEncoding:
    def encode(self, text: str) -> list[int]:
        return list(text.encode("utf-8"))


def active_source(
    *,
    version: int = 2,
    supersedes: int | None = 1,
) -> ListingKnowledgeSource:
    listing = ListingKnowledgeSource.model_validate(
        {
            "sourceType": "LISTING",
            "sourceId": LISTING_ID,
            "sourceVersion": str(version),
            "supersedesVersion": (
                None if supersedes is None else str(supersedes)
            ),
            "lifecycle": "ACTIVE",
            "visibility": "PUBLIC",
            "language": "und",
            "effectiveFrom": "2026-07-19T00:00:00Z",
            "invalidatedAt": None,
            "sourcePublishedAt": "2026-07-19T00:00:00Z",
            "contentHash": "0" * 64,
            "content": {
                "title": "Walnut desk",
                "description": "Solid walnut adjustable standing desk.",
                "price": {"amount": "250.00", "currency": "USD"},
                "publicLocation": {"city": "Irvine", "region": "CA"},
            },
        }
    )
    return listing.model_copy(
        update={"content_hash": canonical_listing_source_hash(listing)}
    )


def tombstone_source() -> ListingKnowledgeSource:
    return ListingKnowledgeSource.model_validate(
        {
            "sourceType": "LISTING",
            "sourceId": LISTING_ID,
            "sourceVersion": "3",
            "supersedesVersion": "2",
            "lifecycle": "INVALIDATED",
            "visibility": "PUBLIC",
            "language": "und",
            "effectiveFrom": None,
            "invalidatedAt": "2026-07-19T02:00:00Z",
            "sourcePublishedAt": "2026-07-19T02:00:00Z",
            "contentHash": None,
            "content": None,
        }
    )


def job(
    *,
    version: int = 2,
    lifecycle: str = "ACTIVE",
    superseded: int | None = 1,
) -> KnowledgeIngestionJob:
    return KnowledgeIngestionJob(
        job_id="01J00000000000000000000001",
        event_id="01E00000000000000000000001",
        source_type="LISTING",
        source_id=LISTING_ID,
        source_version=version,
        language="und",
        lifecycle=lifecycle,
        superseded_version=superseded,
        event_occurred_at=NOW,
        payload_hash="a" * 64,
        status=KnowledgeJobStatus.PROCESSING,
        attempt_count=1,
    )


class FakeRepository:
    def __init__(self) -> None:
        self.state = None
        self.active_result = SourceStateApplyResult.APPLIED
        self.tombstone_result = SourceStateApplyResult.APPLIED
        self.active_calls: list[dict[str, object]] = []
        self.tombstone_calls: list[dict[str, object]] = []
        self.deletion_calls: list[dict[str, object]] = []

    async def get_source_state(self, *args: object) -> object:
        return self.state

    async def apply_active_source_state(
        self,
        current_job: KnowledgeIngestionJob,
        **kwargs: object,
    ) -> SourceStateApplyResult:
        self.active_calls.append({"job": current_job, **kwargs})
        return self.active_result

    async def apply_tombstone_source_state(
        self,
        current_job: KnowledgeIngestionJob,
        **kwargs: object,
    ) -> SourceStateApplyResult:
        self.tombstone_calls.append({"job": current_job, **kwargs})
        return self.tombstone_result

    async def schedule_deletion(self, **kwargs: object) -> str:
        self.deletion_calls.append(kwargs)
        return "01D00000000000000000000001"


class FakeSourceClient:
    def __init__(self, source: ListingKnowledgeSource) -> None:
        self.source = source
        self.calls: list[tuple[str, int]] = []

    async def fetch_listing(
        self,
        source_id: str,
        source_version: int,
    ) -> tuple[ListingKnowledgeSource, float]:
        self.calls.append((source_id, source_version))
        return self.source, 0.01


class FakeEmbeddingProvider:
    provider = "openai"
    model = "text-embedding-3-small"
    dimensions = 3

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    async def embed(
        self,
        texts: list[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        self.calls.append(tuple(texts))
        return EmbeddingBatchResult(
            vectors=tuple((1.0, 0.0, 0.0) for _ in texts),
            provider=self.provider,
            model=self.model,
            dimensions=self.dimensions,
            input_tokens=len(texts),
        )

    async def close(self) -> None:
        return None


class FakeWriter:
    def __init__(self) -> None:
        self.upserts: list[tuple[object, ...]] = []
        self.invalidations: list[tuple[object, ...]] = []
        self.source_deletes: list[tuple[object, ...]] = []
        self.chunk_deletes: list[tuple[str, ...]] = []

    async def upsert(self, documents: list[object] | tuple[object, ...], **_: object) -> object:
        self.upserts.append(tuple(documents))
        return object()

    async def invalidate_source(self, *args: object, **_: object) -> int:
        self.invalidations.append(args)
        return 1

    async def delete_source(self, *args: object, **_: object) -> int:
        self.source_deletes.append(args)
        return 1

    async def delete_chunks(self, chunk_ids: list[str], **_: object) -> object:
        self.chunk_deletes.append(tuple(chunk_ids))
        return object()


class ListingKnowledgeProcessorTest(unittest.IsolatedAsyncioTestCase):
    def processor(
        self,
        source: ListingKnowledgeSource,
        repository: FakeRepository | None = None,
    ) -> tuple[
        ListingKnowledgeProcessor,
        FakeRepository,
        FakeEmbeddingProvider,
        FakeWriter,
    ]:
        repository = repository or FakeRepository()
        embedding = FakeEmbeddingProvider()
        writer = FakeWriter()
        processor = ListingKnowledgeProcessor(
            repository=repository,  # type: ignore[arg-type]
            source_client=FakeSourceClient(source),  # type: ignore[arg-type]
            operations_repository=repository,  # type: ignore[arg-type]
            chunker=ListingKnowledgeChunker(
                model="text-embedding-3-small",
                maximum_tokens=128,
                overlap_tokens=16,
                maximum_chunks=8,
                encoding=FakeEncoding(),
            ),
            embedding_provider=embedding,
            writer=writer,  # type: ignore[arg-type]
            metrics=KnowledgeIngestionMetrics(CollectorRegistry()),
            clock=lambda: NOW,
        )
        return processor, repository, embedding, writer

    async def test_active_source_embeds_indexes_and_advances_state(self) -> None:
        processor, repository, embedding, writer = self.processor(
            active_source()
        )

        await processor(job())

        self.assertEqual(1, len(embedding.calls))
        self.assertEqual(1, len(writer.upserts))
        self.assertEqual([], writer.source_deletes)
        self.assertEqual(1, repository.deletion_calls[0]["source_version"])
        self.assertEqual(1, len(repository.active_calls))
        documents = writer.upserts[0]
        self.assertEqual(
            list(range(len(documents))),
            [document.ordinal for document in documents],
        )
        self.assertTrue(
            all(len(document.embedding) == 3 for document in documents)
        )

    async def test_hash_mismatch_fails_before_embedding_or_indexing(self) -> None:
        source = active_source().model_copy(
            update={"content_hash": "f" * 64}
        )
        processor, _, embedding, writer = self.processor(source)

        with self.assertRaises(KnowledgeProcessingError) as captured:
            await processor(job())

        self.assertEqual(
            "SOURCE_CONTENT_HASH_MISMATCH",
            captured.exception.code,
        )
        self.assertFalse(captured.exception.retryable)
        self.assertEqual([], embedding.calls)
        self.assertEqual([], writer.upserts)

    async def test_tombstone_never_embeds_and_schedules_exact_deletion(
        self,
    ) -> None:
        processor, repository, embedding, writer = self.processor(
            tombstone_source()
        )

        await processor(
            job(version=3, lifecycle="INVALIDATED", superseded=2)
        )

        self.assertEqual([], embedding.calls)
        self.assertEqual([], writer.upserts)
        self.assertEqual([], writer.source_deletes)
        self.assertEqual(2, repository.deletion_calls[0]["source_version"])
        self.assertEqual(1, len(repository.tombstone_calls))

    async def test_state_conflict_removes_only_new_exact_chunks(self) -> None:
        repository = FakeRepository()
        repository.active_result = SourceStateApplyResult.CONFLICT
        processor, _, _, writer = self.processor(
            active_source(),
            repository,
        )

        with self.assertRaises(KnowledgeProcessingError) as captured:
            await processor(job())

        self.assertEqual("SOURCE_VERSION_CONFLICT", captured.exception.code)
        self.assertEqual(
            tuple(document.chunk_id for document in writer.upserts[0]),
            writer.chunk_deletes[0],
        )


if __name__ == "__main__":
    unittest.main()
