from __future__ import annotations

import os
import time
import unittest
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
from opensearchpy.exceptions import RequestError
from prometheus_client import CollectorRegistry
from testcontainers.core.container import DockerContainer

from msb_agent_service.config import KnowledgeIndexSettings
from msb_agent_service.embedding_provider import EmbeddingBatchResult
from msb_agent_service.knowledge_chunker import ListingKnowledgeChunker
from msb_agent_service.knowledge_index import (
    KnowledgeIndexAdmin,
    KnowledgeIndexError,
    KnowledgeIndexErrorCode,
    KnowledgeIndexWriter,
    KnowledgeReadinessStatus,
    close_open_search_client,
)
from msb_agent_service.knowledge_index_client import create_open_search_client
from msb_agent_service.knowledge_index_documents import (
    KnowledgeChunkDocument,
    fixed_metadata_filters,
)
from msb_agent_service.knowledge_index_metrics import KnowledgeIndexMetrics
from msb_agent_service.knowledge_ingestion import ListingKnowledgeProcessor
from msb_agent_service.knowledge_ingestion_metrics import (
    KnowledgeIngestionMetrics,
)
from msb_agent_service.knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobStatus,
    SourceStateApplyResult,
)
from msb_agent_service.knowledge_retriever import (
    CategoryGuidanceRetrievalRequest,
    CategoryGuidanceVersionScope,
    KnowledgeRetrievalMetrics,
    ListingKnowledgeRetrievalRequest,
    OpenSearchCategoryGuidanceRetriever,
    OpenSearchListingKnowledgeRetriever,
)
from msb_agent_service.knowledge_sanitizer import (
    canonical_listing_source_hash,
)
from msb_agent_service.knowledge_source_client import ListingKnowledgeSource

RUN_INTEGRATION = os.getenv("RUN_OPENSEARCH_INTEGRATION") == "1"
LISTING_ID = "01L00000000000000000000001"
CATEGORY_ID = "01K00000000000000000000002"


class FakeEncoding:
    def encode(self, text: str) -> list[int]:
        return list(text.encode("utf-8"))


def document(
    chunk_id: str,
    *,
    vector: list[float],
    source_id: str = "listing-1",
    listing_id: str = "listing-1",
    source_version: str = "1",
    ordinal: int | None = None,
    content_hash: str | None = None,
) -> KnowledgeChunkDocument:
    return KnowledgeChunkDocument.model_validate(
        {
            "chunkId": chunk_id,
            "sourceType": "LISTING",
            "sourceId": source_id,
            "sourceVersion": source_version,
            "contentHash": content_hash
            or ("a" if chunk_id == "chunk-1" else "b") * 64,
            "listingId": listing_id,
            "visibility": "PUBLIC",
            "language": "en",
            "effectiveFrom": "2026-01-01T00:00:00Z",
            "indexedAt": "2026-07-18T00:00:00Z",
            "ordinal": (
                ordinal
                if ordinal is not None
                else (0 if chunk_id == "chunk-1" else 1)
            ),
            "sectionLabel": "Description",
            "text": f"Synthetic description for {chunk_id}.",
            "embedding": vector,
        }
    )


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_OPENSEARCH_INTEGRATION=1 to run OpenSearch integration tests",
)
class KnowledgeIndexIntegrationTest(unittest.IsolatedAsyncioTestCase):
    container: DockerContainer | None
    base_url: str

    @classmethod
    def setUpClass(cls) -> None:
        external_url = os.getenv("OPENSEARCH_TEST_URL", "").strip()
        cls.container = None
        if external_url:
            cls.base_url = external_url.rstrip("/")
        else:
            cls.container = (
                DockerContainer("opensearchproject/opensearch:2.15.0")
                .with_env("discovery.type", "single-node")
                .with_env("plugins.security.disabled", "true")
                .with_env("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .with_env("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .with_exposed_ports(9200)
            )
            cls.container.start()
            host = cls.container.get_container_host_ip()
            port = cls.container.get_exposed_port(9200)
            cls.base_url = f"http://{host}:{port}"
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                response = httpx.get(cls.base_url, timeout=2)
                if response.status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(1)
        if cls.container is not None:
            cls.container.stop()
        raise RuntimeError("OpenSearch test container did not become ready")

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.container is not None:
            cls.container.stop()

    async def test_synthetic_processor_indexes_strict_documents_end_to_end(
        self,
    ) -> None:
        registry = CollectorRegistry()
        index_metrics = KnowledgeIndexMetrics(registry)
        ingestion_metrics = KnowledgeIngestionMetrics(registry)
        settings = KnowledgeIndexSettings(
            enabled=True,
            url=self.base_url,
            embedding_provider="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
            index_prefix="msb-agent-knowledge-processor-integration",
            read_alias="msb-agent-knowledge-processor-integration-read",
            write_alias="msb-agent-knowledge-processor-integration-write",
            bulk_max_documents=10,
        )
        client = create_open_search_client(settings)
        admin = KnowledgeIndexAdmin(client, settings, index_metrics)
        writer = KnowledgeIndexWriter(client, settings, index_metrics)
        source = ListingKnowledgeSource.model_validate(
            {
                "sourceType": "LISTING",
                "sourceId": LISTING_ID,
                "sourceVersion": "1",
                "supersedesVersion": None,
                "lifecycle": "ACTIVE",
                "visibility": "PUBLIC",
                "language": "und",
                "effectiveFrom": "2026-07-19T00:00:00Z",
                "invalidatedAt": None,
                "sourcePublishedAt": "2026-07-19T00:00:00Z",
                "contentHash": "0" * 64,
                "content": {
                    "title": "Synthetic walnut desk",
                    "description": "Adjustable desk in excellent condition.",
                    "price": {"amount": "250.00", "currency": "USD"},
                    "publicLocation": {"city": "Irvine", "region": "CA"},
                },
            }
        )
        source = source.model_copy(
            update={"content_hash": canonical_listing_source_hash(source)}
        )
        repository = SimpleNamespace(
            get_source_state=AsyncMock(return_value=None),
            apply_active_source_state=AsyncMock(
                return_value=SourceStateApplyResult.APPLIED
            ),
        )
        source_client = SimpleNamespace(
            fetch_listing=AsyncMock(return_value=(source, 0.01))
        )

        async def embed(
            texts: list[str],
            *,
            correlation_id: str | None,
        ) -> EmbeddingBatchResult:
            return EmbeddingBatchResult(
                vectors=tuple((1.0, 0.0, 0.0) for _ in texts),
                provider="synthetic",
                model="integration-test",
                dimensions=3,
                input_tokens=len(texts),
            )

        embedding_provider = SimpleNamespace(
            provider="synthetic",
            model="integration-test",
            dimensions=3,
            embed=embed,
        )
        processor = ListingKnowledgeProcessor(
            repository=repository,
            source_client=source_client,
            operations_repository=SimpleNamespace(
                schedule_deletion=AsyncMock()
            ),
            chunker=ListingKnowledgeChunker(
                model="integration-test",
                maximum_tokens=256,
                overlap_tokens=16,
                maximum_chunks=8,
                encoding=FakeEncoding(),
            ),
            embedding_provider=embedding_provider,
            writer=writer,
            metrics=ingestion_metrics,
            clock=lambda: datetime(2026, 7, 19, 1, 0, tzinfo=UTC),
        )
        job = KnowledgeIngestionJob(
            job_id="01J00000000000000000000001",
            event_id="01E00000000000000000000001",
            source_type="LISTING",
            source_id=LISTING_ID,
            source_version=1,
            language="und",
            lifecycle="ACTIVE",
            superseded_version=None,
            event_occurred_at=datetime(2026, 7, 19, 0, 0, tzinfo=UTC),
            payload_hash="a" * 64,
            status=KnowledgeJobStatus.PROCESSING,
            attempt_count=1,
        )
        try:
            await client.indices.delete(
                index=f"{settings.index_prefix}-*",
                ignore_unavailable=True,
            )
            await admin.bootstrap()
            await processor(job)
            await client.indices.refresh(index=settings.write_alias)
            result = await client.search(
                index=settings.read_alias,
                body={
                    "size": 10,
                    "_source": {"excludes": ["embedding"]},
                    "query": {"term": {"sourceId": LISTING_ID}},
                    "sort": [{"ordinal": "asc"}],
                },
            )

            hits = result["hits"]["hits"]
            self.assertEqual(2, len(hits))
            self.assertEqual(
                ["Listing summary", "Description 1"],
                [hit["_source"]["sectionLabel"] for hit in hits],
            )
            self.assertTrue(
                all("embedding" not in hit["_source"] for hit in hits)
            )
            repository.apply_active_source_state.assert_awaited_once()
        finally:
            await close_open_search_client(client)

    async def test_category_retriever_enforces_category_version_isolation(
        self,
    ) -> None:
        settings = KnowledgeIndexSettings(
            enabled=True,
            url=self.base_url,
            embedding_provider="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
            index_prefix="msb-agent-category-retriever-integration",
            read_alias="msb-agent-category-retriever-integration-read",
            write_alias="msb-agent-category-retriever-integration-write",
            bulk_max_documents=10,
        )
        client = create_open_search_client(settings)
        admin = KnowledgeIndexAdmin(client, settings)
        writer = KnowledgeIndexWriter(client, settings)

        async def embed(texts, *, correlation_id):
            return EmbeddingBatchResult(
                vectors=((1.0, 0.0, 0.0),),
                provider="synthetic",
                model="integration-test",
                dimensions=3,
                input_tokens=2,
            )

        retriever = OpenSearchCategoryGuidanceRetriever(
            client=client,
            read_alias=settings.read_alias,
            embedding_provider=SimpleNamespace(embed=embed),
            embedding_provider_name="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
        )

        def category_document(
            chunk_id: str,
            category_id: str,
        ) -> KnowledgeChunkDocument:
            return KnowledgeChunkDocument.model_validate(
                {
                    "chunkId": chunk_id,
                    "sourceType": "CATEGORY_GUIDANCE",
                    "sourceId": category_id,
                    "sourceVersion": "1",
                    "contentHash": "c" * 64,
                    "visibility": "PUBLIC",
                    "language": "en",
                    "effectiveFrom": "2026-01-01T00:00:00Z",
                    "indexedAt": "2026-07-19T00:00:00Z",
                    "ordinal": 0,
                    "sectionLabel": "Category guidance",
                    "text": "Inspect visible condition and the model number.",
                    "embedding": [1.0, 0.0, 0.0],
                }
            )

        try:
            await client.indices.delete(
                index=f"{settings.index_prefix}-*",
                ignore_unavailable=True,
            )
            await admin.bootstrap()
            await writer.upsert(
                [
                    category_document("category-match", CATEGORY_ID),
                    category_document(
                        "category-other",
                        "01K00000000000000000000003",
                    ),
                ],
                refresh=True,
            )
            result = await retriever.retrieve(
                CategoryGuidanceRetrievalRequest(
                    actorUserId="actor-1",
                    categoryId=CATEGORY_ID,
                    scopes=(
                        CategoryGuidanceVersionScope(
                            language="en",
                            sourceVersion="1",
                        ),
                    ),
                    query="What should I inspect?",
                    effectiveAt=datetime(2026, 7, 19, tzinfo=UTC),
                )
            )

            self.assertEqual(1, len(result.passages))
            self.assertEqual(CATEGORY_ID, result.passages[0].source_id)
            self.assertIsNone(result.passages[0].listing_id)
        finally:
            await close_open_search_client(client)

    async def test_vector_mapping_document_operations_and_alias_lifecycle(
        self,
    ) -> None:
        settings = KnowledgeIndexSettings(
            enabled=True,
            url=self.base_url,
            embedding_provider="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
            index_prefix="msb-agent-knowledge-integration",
            read_alias="msb-agent-knowledge-integration-read",
            write_alias="msb-agent-knowledge-integration-write",
            bulk_max_documents=10,
        )
        client = create_open_search_client(settings)
        admin = KnowledgeIndexAdmin(client, settings)
        writer = KnowledgeIndexWriter(client, settings)
        try:
            await client.indices.delete(
                index=f"{settings.index_prefix}-*",
                ignore_unavailable=True,
            )
            initial = await admin.bootstrap()
            replay = await admin.bootstrap()
            self.assertEqual(KnowledgeReadinessStatus.READY, initial.status)
            self.assertEqual(initial.read_index, replay.read_index)
            self.assertEqual(initial.read_index, initial.write_index)

            mapping = await client.indices.get_mapping(index=initial.read_index)
            self.assertEqual(
                "strict", mapping[initial.read_index]["mappings"]["dynamic"]
            )
            self.assertEqual(
                3,
                mapping[initial.read_index]["mappings"]["properties"]["embedding"][
                    "dimension"
                ],
            )

            first = document("chunk-1", vector=[1.0, 0.0, 0.0])
            second = document(
                "chunk-2",
                vector=[0.0, 1.0, 0.0],
                source_id="listing-2",
                listing_id="listing-2",
            )
            result = await writer.upsert([first, second], refresh=True)
            replay_result = await writer.upsert([first], refresh=True)
            self.assertEqual(2, result.succeeded)
            self.assertEqual(1, replay_result.succeeded)

            query = {
                "size": 2,
                "_source": {"excludes": ["embedding"]},
                "query": {
                    "knn": {
                        "embedding": {
                            "vector": [1.0, 0.0, 0.0],
                            "k": 2,
                            "filter": {
                                "bool": {
                                    "filter": fixed_metadata_filters(
                                        source_types=["LISTING"],
                                        listing_id="listing-1",
                                        language="en",
                                        effective_at=datetime.now(UTC),
                                    )
                                }
                            },
                        }
                    }
                },
            }
            search = await client.search(index=settings.read_alias, body=query)
            hits = search["hits"]["hits"]
            self.assertEqual(["chunk-1"], [hit["_id"] for hit in hits])
            self.assertNotIn("embedding", hits[0]["_source"])

            invalidated = await writer.invalidate_source(
                "LISTING",
                "listing-1",
                "1",
                datetime.now(UTC),
                refresh=True,
            )
            self.assertEqual(1, invalidated)
            search = await client.search(index=settings.read_alias, body=query)
            self.assertEqual([], search["hits"]["hits"])

            deleted = await writer.delete_source(
                "LISTING", "listing-1", "1", refresh=True
            )
            self.assertEqual(1, deleted)
            deleted_again = await writer.delete_source(
                "LISTING", "listing-1", "1", refresh=True
            )
            self.assertEqual(0, deleted_again)

            delete_result = await writer.delete_chunks(["chunk-2"], refresh=True)
            delete_replay = await writer.delete_chunks(["chunk-2"], refresh=True)
            self.assertEqual(1, delete_result.succeeded)
            self.assertEqual(1, delete_replay.succeeded)

            with self.assertRaises(KnowledgeIndexError) as dimension_error:
                await writer.upsert(
                    [document("wrong-dimension", vector=[1.0, 0.0])],
                    refresh=True,
                )
            self.assertEqual(
                KnowledgeIndexErrorCode.DOCUMENT_INVALID,
                dimension_error.exception.code,
            )

            invalid_source = first.open_search_source()
            invalid_source["unexpected"] = "rejected"
            with self.assertRaises(RequestError):
                await client.index(
                    index=settings.write_alias,
                    id="strict-mapping-rejection",
                    body=invalid_source,
                    refresh=True,
                )

            second_generation = await admin.create_generation()
            before_promote = await admin.status()
            self.assertEqual(initial.read_index, before_promote.read_index)
            self.assertEqual(second_generation, before_promote.write_index)

            mirrored_writer = KnowledgeIndexWriter(
                client,
                settings,
                mirror_live_generations=True,
            )
            live = document(
                "chunk-live",
                vector=[0.0, 0.0, 1.0],
                source_id="listing-live",
                listing_id="listing-live",
            )
            await mirrored_writer.upsert([live], refresh=True)
            for generation in (initial.read_index, second_generation):
                count = await client.count(
                    index=generation,
                    body={"query": {"term": {"sourceId": "listing-live"}}},
                )
                self.assertEqual(1, count["count"])
            mirrored_deleted = await mirrored_writer.delete_source(
                "LISTING",
                "listing-live",
                "1",
                refresh=True,
            )
            self.assertEqual(2, mirrored_deleted)

            promoted = await admin.promote(second_generation)
            self.assertEqual(second_generation, promoted.read_index)
            self.assertEqual(second_generation, promoted.write_index)

            rolled_back = await admin.rollback(initial.read_index)
            self.assertEqual(initial.read_index, rolled_back.read_index)
            self.assertEqual(second_generation, rolled_back.write_index)
        finally:
            await close_open_search_client(client)

    async def test_listing_retriever_enforces_relevance_version_and_isolation(
        self,
    ) -> None:
        registry = CollectorRegistry()
        settings = KnowledgeIndexSettings(
            enabled=True,
            url=self.base_url,
            embedding_provider="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
            index_prefix="msb-agent-knowledge-retriever-integration",
            read_alias="msb-agent-knowledge-retriever-integration-read",
            write_alias="msb-agent-knowledge-retriever-integration-write",
            bulk_max_documents=10,
        )
        client = create_open_search_client(settings)
        admin = KnowledgeIndexAdmin(client, settings)
        writer = KnowledgeIndexWriter(client, settings)

        async def embed(
            texts: list[str],
            *,
            correlation_id: str | None,
        ) -> EmbeddingBatchResult:
            return EmbeddingBatchResult(
                vectors=((1.0, 0.0, 0.0),),
                provider="synthetic",
                model="integration-test",
                dimensions=3,
                input_tokens=3,
            )

        retriever = OpenSearchListingKnowledgeRetriever(
            client=client,
            read_alias=settings.read_alias,
            embedding_provider=SimpleNamespace(embed=embed, close=AsyncMock()),
            embedding_provider_name="synthetic",
            embedding_model="integration-test",
            embedding_dimensions=3,
            metrics=KnowledgeRetrievalMetrics(registry),
        )
        try:
            await client.indices.delete(
                index=f"{settings.index_prefix}-*",
                ignore_unavailable=True,
            )
            await admin.bootstrap()
            await writer.upsert(
                [
                    document("chunk-1", vector=[1.0, 0.0, 0.0], ordinal=0),
                    document(
                        "chunk-less-relevant",
                        vector=[0.0, 1.0, 0.0],
                        ordinal=1,
                        content_hash="a" * 64,
                    ),
                    document(
                        "chunk-other-listing",
                        vector=[1.0, 0.0, 0.0],
                        source_id="listing-2",
                        listing_id="listing-2",
                        ordinal=0,
                        content_hash="b" * 64,
                    ),
                    document(
                        "chunk-new-version",
                        vector=[1.0, 0.0, 0.0],
                        source_version="2",
                        ordinal=0,
                        content_hash="c" * 64,
                    ),
                ],
                refresh=True,
            )
            base_request = {
                "actorUserId": "actor-1",
                "listingId": "listing-1",
                "listingVersion": "1",
                "query": "closest synthetic passage",
                "sourceTypes": ["LISTING"],
                "language": "en",
                "effectiveAt": "2026-07-19T12:00:00Z",
                "topK": 1,
            }

            result = await retriever.retrieve(
                ListingKnowledgeRetrievalRequest.model_validate(base_request)
            )
            stale = await retriever.retrieve(
                ListingKnowledgeRetrievalRequest.model_validate(
                    {**base_request, "listingVersion": "3"}
                )
            )

            self.assertEqual(
                ["chunk-1"],
                [passage.chunk_id for passage in result.passages],
            )
            self.assertTrue(
                all(
                    passage.listing_id == "listing-1"
                    and passage.source_version == "1"
                    for passage in result.passages
                )
            )
            self.assertEqual((), stale.passages)
        finally:
            await close_open_search_client(client)


if __name__ == "__main__":
    unittest.main()
    OpenSearchCategoryGuidanceRetriever,
