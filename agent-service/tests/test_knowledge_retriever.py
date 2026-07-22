from __future__ import annotations

import unittest
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock

from opensearchpy.exceptions import ConnectionTimeout
from prometheus_client import CollectorRegistry
from pydantic import ValidationError

from msb_agent_service.embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProviderError,
)
from msb_agent_service.knowledge_retriever import (
    CategoryGuidanceVersionScope,
    KnowledgeRetrievalError,
    KnowledgeRetrievalErrorCode,
    KnowledgeRetrievalLimits,
    KnowledgeRetrievalMetrics,
    ListingKnowledgeRetrievalRequest,
    OpenSearchListingKnowledgeRetriever,
)


def request(**overrides: object) -> ListingKnowledgeRetrievalRequest:
    values: dict[str, object] = {
        "actorUserId": "actor-1",
        "listingId": "listing-1",
        "listingVersion": "7",
        "query": "Is this desk made from walnut?",
        "sourceTypes": ["LISTING"],
        "language": "en",
        "effectiveAt": "2026-07-19T12:00:00Z",
        "topK": 3,
        "correlationId": "correlation-1",
    }
    values.update(overrides)
    return ListingKnowledgeRetrievalRequest.model_validate(values)


def hit(
    *,
    chunk_id: str = "chunk-1",
    listing_id: str = "listing-1",
    source_version: str = "7",
    ordinal: int = 0,
    language: str = "en",
    text: str = "Walnut veneer adjustable desk.",
    content_hash: str = "a" * 64,
    invalidated_at: str | None = None,
) -> dict[str, object]:
    result = {
        "_id": chunk_id,
        "_score": 1.5,
        "_source": {
            "chunkId": chunk_id,
            "sourceType": "LISTING",
            "sourceId": listing_id,
            "sourceVersion": source_version,
            "contentHash": content_hash,
            "listingId": listing_id,
            "visibility": "PUBLIC",
            "language": language,
            "effectiveFrom": "2026-01-01T00:00:00Z",
            "ordinal": ordinal,
            "sectionLabel": "Description",
            "text": text,
        },
    }
    if invalidated_at is not None:
        result["_source"]["invalidatedAt"] = invalidated_at
    return result


class FakeEmbeddingProvider:
    def __init__(self) -> None:
        self.calls: list[tuple[list[str], str | None]] = []
        self.failure: EmbeddingProviderError | None = None

    async def embed(
        self,
        texts: list[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        self.calls.append((texts, correlation_id))
        if self.failure is not None:
            raise self.failure
        return EmbeddingBatchResult(
            vectors=((1.0, 0.0, 0.0),),
            provider="synthetic",
            model="test-model",
            dimensions=3,
            input_tokens=7,
        )

    async def close(self) -> None:
        return None


class ListingKnowledgeRetrievalRequestTest(unittest.TestCase):
    def test_normalizes_query_and_language(self) -> None:
        parsed = request(query="  desk details  ", language="EN")

        self.assertEqual("desk details", parsed.query)
        self.assertEqual("en", parsed.language)

    def test_accepts_zero_listing_version_from_product_snapshots(self) -> None:
        parsed = request(listingVersion="0")

        self.assertEqual("0", parsed.listing_version)

    def test_category_guidance_versions_stay_positive(self) -> None:
        with self.assertRaises(ValidationError):
            CategoryGuidanceVersionScope.model_validate(
                {"language": "en", "sourceVersion": "0"}
            )

    def test_rejects_non_listing_sources_and_untrusted_shapes(self) -> None:
        with self.assertRaises(ValidationError):
            request(sourceTypes=["MARKETPLACE_FAQ"])
        with self.assertRaises(ValidationError):
            request(listingVersion="latest")
        with self.assertRaises(ValidationError):
            request(effectiveAt="2026-07-19T12:00:00")
        with self.assertRaises(ValidationError):
            request(actorUserId=" actor-1")


class OpenSearchListingKnowledgeRetrieverTest(
    unittest.IsolatedAsyncioTestCase
):
    def retriever(
        self,
        client: AsyncMock,
        provider: FakeEmbeddingProvider | None = None,
        *,
        limits: KnowledgeRetrievalLimits | None = None,
    ) -> OpenSearchListingKnowledgeRetriever:
        return OpenSearchListingKnowledgeRetriever(
            client=client,
            read_alias="msb-agent-knowledge-read",
            embedding_provider=provider or FakeEmbeddingProvider(),
            embedding_provider_name="synthetic",
            embedding_model="test-model",
            embedding_dimensions=3,
            limits=limits,
            metrics=KnowledgeRetrievalMetrics(CollectorRegistry()),
        )

    async def test_query_uses_only_fixed_listing_filters(self) -> None:
        client = AsyncMock()
        client.search.return_value = {"hits": {"hits": [hit()]}}
        provider = FakeEmbeddingProvider()

        result = await self.retriever(client, provider).retrieve(request())

        self.assertEqual(["chunk-1"], [item.chunk_id for item in result.passages])
        self.assertEqual(7, result.embedded_input_tokens)
        self.assertEqual(
            [(["Is this desk made from walnut?"], "correlation-1")],
            provider.calls,
        )
        call = client.search.await_args.kwargs
        self.assertEqual("msb-agent-knowledge-read", call["index"])
        body = call["body"]
        self.assertFalse(body["track_total_hits"])
        knn = body["query"]["knn"]["embedding"]
        self.assertEqual([1.0, 0.0, 0.0], knn["vector"])
        filters = knn["filter"]["bool"]["filter"]
        self.assertIn({"term": {"visibility": "PUBLIC"}}, filters)
        self.assertIn({"terms": {"sourceType": ["LISTING"]}}, filters)
        self.assertIn({"term": {"sourceId": "listing-1"}}, filters)
        self.assertIn({"term": {"sourceVersion": "7"}}, filters)
        self.assertIn({"term": {"listingId": "listing-1"}}, filters)
        self.assertIn({"terms": {"language": ["en", "und"]}}, filters)
        self.assertNotIn("actor-1", str(body))
        self.assertNotIn("Is this desk", str(filters))

    async def test_discards_cross_listing_stale_and_wrong_language_hits(
        self,
    ) -> None:
        client = AsyncMock()
        client.search.return_value = {
            "hits": {
                "hits": [
                    hit(chunk_id="other", listing_id="listing-2"),
                    hit(chunk_id="stale", source_version="6"),
                    hit(chunk_id="wrong-language", language="fr"),
                    hit(chunk_id="current"),
                ]
            }
        }

        result = await self.retriever(client).retrieve(request())

        self.assertEqual(["current"], [item.chunk_id for item in result.passages])
        self.assertEqual(3, result.discarded_hits)

    async def test_returns_empty_when_only_stale_results_exist(self) -> None:
        client = AsyncMock()
        client.search.return_value = {
            "hits": {
                "hits": [
                    hit(source_version="6"),
                    hit(
                        chunk_id="invalidated",
                        invalidated_at="2026-07-19T11:00:00Z",
                    ),
                ]
            }
        }

        result = await self.retriever(client).retrieve(request())

        self.assertEqual((), result.passages)
        self.assertEqual(2, result.discarded_hits)

    async def test_applies_passage_context_and_top_k_bounds(self) -> None:
        client = AsyncMock()
        client.search.return_value = {
            "hits": {
                "hits": [
                    hit(
                        chunk_id=f"chunk-{index}",
                        ordinal=index,
                        text="x" * 20,
                    )
                    for index in range(3)
                ]
            }
        }
        limits = KnowledgeRetrievalLimits(
            maximum_top_k=3,
            maximum_candidates=6,
            candidate_multiplier=2,
            maximum_passage_characters=10,
            maximum_context_characters=20,
        )

        result = await self.retriever(client, limits=limits).retrieve(
            request(topK=3)
        )

        self.assertEqual(2, len(result.passages))
        self.assertEqual([10, 10], [len(item.text) for item in result.passages])
        self.assertEqual(20, result.context_characters)

    async def test_conflicting_ordinal_fails_closed(self) -> None:
        client = AsyncMock()
        client.search.return_value = {
            "hits": {
                "hits": [
                    hit(chunk_id="chunk-1", ordinal=0),
                    hit(
                        chunk_id="chunk-2",
                        ordinal=0,
                        content_hash="b" * 64,
                    ),
                ]
            }
        }

        with self.assertRaises(KnowledgeRetrievalError) as captured:
            await self.retriever(client).retrieve(request())

        self.assertEqual(
            KnowledgeRetrievalErrorCode.CONFLICTING_RESULTS,
            captured.exception.code,
        )
        self.assertFalse(captured.exception.retryable)

    async def test_bounds_fail_before_provider_or_index_call(self) -> None:
        client = AsyncMock()
        provider = FakeEmbeddingProvider()
        limits = KnowledgeRetrievalLimits(maximum_query_characters=4)

        with self.assertRaises(KnowledgeRetrievalError) as captured:
            await self.retriever(client, provider, limits=limits).retrieve(
                request(query="long query")
            )

        self.assertEqual(
            KnowledgeRetrievalErrorCode.REQUEST_INVALID,
            captured.exception.code,
        )
        self.assertEqual([], provider.calls)
        client.search.assert_not_awaited()

    async def test_provider_and_index_failures_are_safe_and_typed(self) -> None:
        provider = FakeEmbeddingProvider()
        provider.failure = EmbeddingProviderError(
            "provider detail",
            retryable=True,
        )
        with self.assertRaises(KnowledgeRetrievalError) as embedding_failure:
            await self.retriever(AsyncMock(), provider).retrieve(request())
        self.assertEqual(
            KnowledgeRetrievalErrorCode.EMBEDDING_FAILED,
            embedding_failure.exception.code,
        )
        self.assertTrue(embedding_failure.exception.retryable)
        self.assertNotIn("provider detail", str(embedding_failure.exception))

        client = AsyncMock()
        client.search.side_effect = ConnectionTimeout("private endpoint")
        with self.assertRaises(KnowledgeRetrievalError) as index_failure:
            await self.retriever(client).retrieve(request())
        self.assertEqual(
            KnowledgeRetrievalErrorCode.INDEX_UNAVAILABLE,
            index_failure.exception.code,
        )
        self.assertTrue(index_failure.exception.retryable)
        self.assertNotIn("private endpoint", str(index_failure.exception))

    async def test_invalid_response_shape_fails_closed(self) -> None:
        client = AsyncMock()
        invalid = hit()
        invalid["_source"]["privateContact"] = "must-not-pass"
        client.search.return_value = {"hits": {"hits": [invalid]}}

        with self.assertRaises(KnowledgeRetrievalError) as captured:
            await self.retriever(client).retrieve(request())

        self.assertEqual(
            KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
            captured.exception.code,
        )


if __name__ == "__main__":
    unittest.main()
