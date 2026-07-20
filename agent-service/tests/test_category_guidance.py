from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime

import httpx
from prometheus_client import CollectorRegistry

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.embedding_provider import EmbeddingBatchResult
from msb_agent_service.knowledge_chunker import CategoryGuidanceChunker
from msb_agent_service.knowledge_events import parse_category_guidance_event
from msb_agent_service.knowledge_ingestion import (
    CategoryGuidanceDocumentBuilder,
    CategoryGuidanceProcessor,
)
from msb_agent_service.knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from msb_agent_service.knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobStatus,
    SourceStateApplyResult,
)
from msb_agent_service.knowledge_retriever import (
    CategoryGuidanceRetrievalRequest,
    CategoryGuidanceVersionScope,
    OpenSearchCategoryGuidanceRetriever,
)
from msb_agent_service.knowledge_sanitizer import (
    canonical_category_guidance_source_hash,
    sanitize_category_guidance,
    verify_category_guidance_source_hash,
)
from msb_agent_service.knowledge_source_client import (
    CategoryGuidanceSource,
    KnowledgeSourceClient,
)

CATEGORY_ID = "01K00000000000000000000002"
EVENT_ID = "01E00000000000000000000002"
NOW = datetime(2026, 7, 19, 8, 0, tzinfo=UTC)


class FakeEncoding:
    def encode(self, text: str) -> list[int]:
        return list(text.encode("utf-8"))


class FakeEmbeddingProvider:
    provider = "openai"
    model = "text-embedding-3-small"
    dimensions = 3

    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    async def embed(self, texts, *, correlation_id):
        self.calls.append(tuple(texts))
        return EmbeddingBatchResult(
            vectors=tuple((1.0, 0.0, 0.0) for _ in texts),
            provider=self.provider,
            model=self.model,
            dimensions=self.dimensions,
            input_tokens=len(texts),
        )


class FakeRepository:
    def __init__(self) -> None:
        self.active_calls: list[dict[str, object]] = []
        self.tombstone_calls: list[dict[str, object]] = []
        self.deletion_calls: list[dict[str, object]] = []

    async def get_source_state(self, *args):
        return None

    async def apply_active_source_state(self, job, **kwargs):
        self.active_calls.append({"job": job, **kwargs})
        return SourceStateApplyResult.APPLIED

    async def apply_tombstone_source_state(self, job, **kwargs):
        self.tombstone_calls.append({"job": job, **kwargs})
        return SourceStateApplyResult.APPLIED

    async def schedule_deletion(self, **kwargs):
        self.deletion_calls.append(kwargs)
        return "01D00000000000000000000001"


class FakeWriter:
    def __init__(self) -> None:
        self.upserts: list[tuple[object, ...]] = []
        self.invalidations: list[tuple[object, ...]] = []

    async def upsert(self, documents, **kwargs):
        self.upserts.append(tuple(documents))

    async def invalidate_source(self, *args, **kwargs):
        self.invalidations.append(args)
        return 1

    async def delete_source(self, *args, **kwargs):
        return 0

    async def delete_chunks(self, *args, **kwargs):
        return None


def active_source() -> CategoryGuidanceSource:
    source = CategoryGuidanceSource.model_validate(
        {
            "sourceType": "CATEGORY_GUIDANCE",
            "sourceId": CATEGORY_ID,
            "sourceVersion": "1",
            "supersedesVersion": None,
            "lifecycle": "ACTIVE",
            "visibility": "PUBLIC",
            "language": "en",
            "effectiveFrom": "2026-07-19T08:00:00Z",
            "contentHash": "0" * 64,
            "content": {
                "categorySlug": "electronics",
                "categoryName": "Electronics",
                "title": "Buying used electronics",
                "body": "Check the model and visible condition.",
            },
        }
    )
    return source.model_copy(
        update={
            "content_hash": canonical_category_guidance_source_hash(source)
        }
    )


class CategoryGuidanceContractTest(unittest.IsolatedAsyncioTestCase):
    def test_event_requires_category_language_message_key(self) -> None:
        body = {
            "eventId": EVENT_ID,
            "eventType": "category-guidance.activated",
            "eventVersion": 1,
            "occurredAt": "2026-07-19T08:00:00Z",
            "producer": "product-service",
            "aggregateType": "category-guidance",
            "aggregateId": CATEGORY_ID,
            "correlationId": None,
            "payload": {
                "sourceType": "CATEGORY_GUIDANCE",
                "sourceId": CATEGORY_ID,
                "sourceVersion": "1",
                "knowledgeLifecycle": "ACTIVE",
                "supersedesVersion": None,
                "language": "en",
            },
        }

        event = parse_category_guidance_event(
            json.dumps(body).encode(),
            f"{CATEGORY_ID}:en".encode(),
        )

        self.assertEqual("CATEGORY_GUIDANCE", event.source_type)
        self.assertEqual(CATEGORY_ID, event.source_id)
        self.assertEqual(1, event.source_version)

    def test_hash_matches_product_service_cross_language_fixture(self) -> None:
        source = active_source()

        self.assertEqual(
            "39045412a4f44cd2d72eb803c7b4e65a"
            "c1ca9d0c205c58629b0ada8b77b6eee9",
            canonical_category_guidance_source_hash(source),
        )
        self.assertEqual(
            source.content_hash,
            verify_category_guidance_source_hash(source),
        )

    async def test_exact_source_client_uses_language_scoped_route(self) -> None:
        observed: httpx.Request | None = None

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal observed
            observed = request
            return httpx.Response(
                200,
                content=active_source().model_dump_json(
                    by_alias=True,
                    exclude_none=True,
                ),
            )

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = KnowledgeSourceClient(
            KnowledgeIngestionSettings(
                enabled=True,
                mysql_password="database-secret",
                product_service_url="http://product-service:8091",
                product_service_token="source-secret",
            ),
            http_client,
        )
        try:
            source, _ = await client.fetch_category_guidance(
                CATEGORY_ID,
                "en",
                1,
            )
        finally:
            await http_client.aclose()

        self.assertEqual(CATEGORY_ID, source.source_id)
        self.assertEqual(
            (
                "/api/v1/internal/agent/knowledge/category-guidance/"
                f"{CATEGORY_ID}/languages/en/versions/1"
            ),
            observed.url.path,  # type: ignore[union-attr]
        )

    async def test_exact_source_client_rejects_language_path_injection(self) -> None:
        http_client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(500, request=request)
            )
        )
        client = KnowledgeSourceClient(
            KnowledgeIngestionSettings(
                enabled=True,
                mysql_password="database-secret",
                product_service_url="http://product-service:8091",
                product_service_token="source-secret",
            ),
            http_client,
        )
        try:
            with self.assertRaisesRegex(ValueError, "normalized lowercase tag"):
                await client.fetch_category_guidance(
                    CATEGORY_ID,
                    "en/../../internal",
                    1,
                )
        finally:
            await http_client.aclose()

    async def test_builder_sanitizes_chunks_and_omits_listing_scope(self) -> None:
        source = active_source()
        sanitized = sanitize_category_guidance(source)
        self.assertEqual("Electronics", sanitized.category_name)
        builder = CategoryGuidanceDocumentBuilder(
            chunker=CategoryGuidanceChunker(
                model="text-embedding-3-small",
                maximum_tokens=180,
                overlap_tokens=16,
                maximum_chunks=8,
                encoding=FakeEncoding(),
            ),
            embedding_provider=FakeEmbeddingProvider(),  # type: ignore[arg-type]
            clock=lambda: NOW,
        )

        _, _, _, documents = await builder.build(
            source,
            correlation_id=EVENT_ID,
        )

        self.assertGreaterEqual(len(documents), 2)
        self.assertTrue(
            all(document.source_type == "CATEGORY_GUIDANCE" for document in documents)
        )
        self.assertTrue(all(document.listing_id is None for document in documents))
        self.assertEqual(len(documents), len({item.chunk_id for item in documents}))

    async def test_processor_indexes_active_category_and_advances_state(self) -> None:
        source = active_source()
        repository = FakeRepository()
        embedding = FakeEmbeddingProvider()
        writer = FakeWriter()

        class SourceClient:
            async def fetch_category_guidance(self, category_id, language, version):
                return source, 0.01

        processor = CategoryGuidanceProcessor(
            repository=repository,  # type: ignore[arg-type]
            source_client=SourceClient(),  # type: ignore[arg-type]
            operations_repository=repository,  # type: ignore[arg-type]
            chunker=CategoryGuidanceChunker(
                model="text-embedding-3-small",
                maximum_tokens=180,
                overlap_tokens=16,
                maximum_chunks=8,
                encoding=FakeEncoding(),
            ),
            embedding_provider=embedding,
            writer=writer,  # type: ignore[arg-type]
            metrics=KnowledgeIngestionMetrics(CollectorRegistry()),
            clock=lambda: NOW,
        )
        job = KnowledgeIngestionJob(
            job_id="01J00000000000000000000002",
            event_id=EVENT_ID,
            source_type="CATEGORY_GUIDANCE",
            source_id=CATEGORY_ID,
            source_version=1,
            language="en",
            lifecycle="ACTIVE",
            superseded_version=None,
            event_occurred_at=NOW,
            payload_hash="a" * 64,
            status=KnowledgeJobStatus.PROCESSING,
            attempt_count=1,
        )

        await processor(job)

        self.assertEqual(1, len(embedding.calls))
        self.assertEqual(1, len(writer.upserts))
        self.assertEqual(1, len(repository.active_calls))
        self.assertTrue(
            all(
                document.source_type == "CATEGORY_GUIDANCE"
                and document.listing_id is None
                for document in writer.upserts[0]
            )
        )

    def test_retriever_query_is_exactly_category_and_version_scoped(self) -> None:
        retriever = OpenSearchCategoryGuidanceRetriever(
            client=object(),  # type: ignore[arg-type]
            read_alias="knowledge-read",
            embedding_provider=FakeEmbeddingProvider(),  # type: ignore[arg-type]
            embedding_provider_name="openai",
            embedding_model="text-embedding-3-small",
            embedding_dimensions=3,
        )
        request = CategoryGuidanceRetrievalRequest(
            actorUserId="actor-1",
            categoryId=CATEGORY_ID,
            scopes=(
                CategoryGuidanceVersionScope(
                    language="en",
                    sourceVersion="1",
                ),
            ),
            query="What should I inspect?",
            effectiveAt=NOW,
        )

        body = retriever._search_body(request, (1.0, 0.0, 0.0))
        rendered = json.dumps(body, sort_keys=True)

        self.assertIn('"sourceType": ["CATEGORY_GUIDANCE"]', rendered)
        self.assertIn(f'"sourceId": "{CATEGORY_ID}"', rendered)
        self.assertIn('"sourceVersion": "1"', rendered)
        self.assertNotIn("listingId", rendered)


if __name__ == "__main__":
    unittest.main()
