from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import httpx

from msb_agent_service.embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProviderError,
)
from msb_agent_service.marketplace_discovery import (
    DiscoveryAvailabilityProbe,
    DiscoverySearchRequest,
)
from msb_agent_service.marketplace_listing_retrieval import (
    HybridMarketplaceDiscoveryClient,
    MarketplaceRetrievalError,
    normalize_hybrid_query,
)

ACTOR = "01D00000000000000000000001"
LISTING = "01D00000000000000000000101"
CATEGORY = "01D00000000000000000000201"


class FakeProductDetail:
    def __init__(self) -> None:
        self.probe_calls = 0

    async def probe_availability(self, **kwargs: object) -> DiscoveryAvailabilityProbe:
        self.probe_calls += 1
        return DiscoveryAvailabilityProbe(
            schemaVersion="MARKETPLACE_AVAILABILITY_PROBE_V1",
            mode="AVAILABILITY_PROBE",
            searchExecuted=True,
            category=kwargs["category"],
            totalActiveCategoryInventory=2,
            relatedCategoryMatches=0,
            failureReason=None,
            retryable=False,
        )

    async def search_individual(self, **_: object) -> object:
        raise AssertionError("Hybrid mode must not call the public search route")

    async def get_listing(self, **_: object) -> None:
        return None


class FakeEmbeddingProvider:
    def __init__(
        self,
        *,
        result: EmbeddingBatchResult | None = None,
        error: EmbeddingProviderError | None = None,
    ) -> None:
        self.result = result or EmbeddingBatchResult(
            vectors=(tuple(0.01 for _ in range(1536)),),
            provider="openai",
            model="text-embedding-3-small",
            dimensions=1536,
            input_tokens=8,
        )
        self.error = error
        self.calls: list[tuple[tuple[str, ...], str | None]] = []

    async def embed(
        self,
        texts: list[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        self.calls.append((tuple(texts), correlation_id))
        if self.error is not None:
            raise self.error
        return self.result

    async def close(self) -> None:
        return None


def hybrid_response(
    *,
    extra: bool = False,
    listing_version: int = 4,
    listing_id: str = LISTING,
) -> dict[str, object]:
    result: dict[str, object] = {
        "listingId": listing_id,
        "listingVersion": listing_version,
        "title": "Ergonomic desk chair",
        "categoryId": CATEGORY,
        "categorySlug": "office-chairs",
        "categoryName": "Office chairs",
        "condition": "GOOD",
        "priceAmount": 85.00,
        "currency": "USD",
        "publicCity": "Irvine",
        "publicRegion": "Orange County",
        "available": True,
        "primaryImageUrl": f"/api/v1/public/listing-media/{listing_id}",
        "publishedAt": "2026-07-23T10:00:00Z",
        "transactionNotice": "Arrange payment and delivery directly.",
        "provenance": {
            "finalRank": 1,
            "mode": "HYBRID",
            "matchedBy": ["LEXICAL", "VECTOR"],
            "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
        },
    }
    if extra:
        result["ownerId"] = ACTOR
    return {
        "schemaVersion": "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3",
        "data": [result],
        "meta": {
            "retrievalMode": "HYBRID",
            "degraded": False,
            "checkedAt": "2026-07-23T10:00:01Z",
        },
        "discovery": {
            "normalizedCategory": "desk chair",
            "totalMatches": 1,
            "relevantMatchCount": 1,
            "exactMatchCount": 1,
            "relatedMatchCount": 0,
            "retrievalConfidence": "HIGH",
            "reason": "RESULTS_AVAILABLE",
            "facets": {
                "subtype": [{"value": "Office Chair", "count": 1}],
                "condition": [{"value": "Good", "count": 1}],
                "priceBand": [{"value": "50-99 USD", "count": 1}],
                "location": [{"value": "Irvine", "count": 1}],
            },
        },
    }


def concept_hybrid_response() -> dict[str, object]:
    response = hybrid_response()
    response["schemaVersion"] = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4"
    data = response["data"]
    assert isinstance(data, list)
    data[0]["conceptMatch"] = {
        "relevance": "HIGH",
        "completeConceptMatch": True,
        "productTypeCompatible": True,
        "matchedConcepts": ["chair"],
    }
    discovery = response["discovery"]
    assert isinstance(discovery, dict)
    discovery["rerankContext"] = {
        "coreConcepts": ["chair"],
        "candidateProductTypes": ["chair"],
        "excludedBroadTypes": [],
    }
    discovery["rejectedCandidateCount"] = 0
    return response


class ProductHybridClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_v4_accepts_only_product_constrained_exact_and_related_evidence(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=concept_hybrid_response())

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                response_schema_version="MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
                client=transport,
            )
            result = await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(q="chair"),
                correlation_id="hybrid-v4",
            )

        payload = json.loads(requests[0].content)
        self.assertEqual(
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
            payload["responseSchemaVersion"],
        )
        self.assertEqual("EXACT", result.data[0].match_quality)
        self.assertEqual(0, result.summary.rejected_candidate_count)

    async def test_v4_rejects_invented_or_incomplete_concept_evidence(self) -> None:
        response = concept_hybrid_response()
        data = response["data"]
        assert isinstance(data, list)
        data[0]["conceptMatch"]["matchedConcepts"] = ["invented"]

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response))
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                response_schema_version="MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError):
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="chair"),
                    correlation_id="hybrid-v4-invalid",
                )

    async def test_availability_probe_delegates_without_query_embedding(self) -> None:
        embedding = FakeEmbeddingProvider()
        product = FakeProductDetail()
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda _: None)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=product,
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            result = await client.probe_availability(
                actor_user_id=ACTOR,
                category="laptop",
                correlation_id="availability-no-embedding",
            )

        self.assertEqual(2, result.total_active_category_inventory)
        self.assertEqual(1, product.probe_calls)
        self.assertEqual([], embedding.calls)

    async def test_one_embedding_and_one_strict_product_call(self) -> None:
        embedding = FakeEmbeddingProvider()
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            return httpx.Response(200, json=hybrid_response())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            result = await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(
                    q="Desk chair under $100",
                    condition="GOOD",
                    maxPrice=Decimal("100"),
                    currency="USD",
                    city="Irvine",
                    limit=5,
                ),
                correlation_id="hybrid-query-1",
            )

        self.assertEqual([(("Desk chair under $100",), "hybrid-query-1")], embedding.calls)
        self.assertEqual(1, len(requests))
        self.assertEqual(
            "/api/v1/internal/agent/marketplace/listings/hybrid-search",
            requests[0].url.path,
        )
        payload = json.loads(requests[0].content)
        self.assertEqual("MARKETPLACE_HYBRID_SEARCH_V1", payload["schemaVersion"])
        self.assertEqual("Desk chair under $100", payload["query"])
        self.assertEqual(1536, len(payload["embedding"]))
        self.assertEqual(100, payload["filters"]["maxPrice"])
        self.assertEqual("USD", payload["filters"]["currency"])
        self.assertEqual("RELEVANCE", payload["sort"])
        self.assertEqual(
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3",
            payload["responseSchemaVersion"],
        )
        self.assertNotIn("actor", json.dumps(payload).casefold())
        self.assertEqual((LISTING,), tuple(item.listing_id for item in result.data))
        self.assertEqual(1, result.summary.total_matches)
        self.assertEqual(1, result.summary.relevant_match_count)
        self.assertEqual(1, result.summary.exact_match_count)
        self.assertEqual(0, result.summary.related_match_count)
        self.assertEqual("HIGH", result.summary.retrieval_confidence)
        self.assertEqual("Office Chair", result.summary.facets.subtype[0].value)
        provenance = result.data[0].retrieval
        assert provenance is not None
        self.assertEqual(4, provenance.listing_version)
        self.assertEqual(1, provenance.final_rank)
        self.assertEqual(("LEXICAL", "VECTOR"), provenance.matched_by)

    async def test_accepts_product_zero_listing_version(self) -> None:
        embedding = FakeEmbeddingProvider()

        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=hybrid_response(listing_version=0))

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            result = await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(q="desk chair"),
                correlation_id="hybrid-query-zero-version",
            )

        self.assertEqual(0, result.data[0].retrieval.listing_version)

    async def test_product_listing_identifiers_are_opaque_crockford_values(
        self,
    ) -> None:
        product_ids = (
            "01ARZ3NDEKTSV4RRFFQ69G5FAC",
            "81ARZ3NDEKTSV4RRFFQ69G5FAC",
            "Z1ARZ3NDEKTSV4RRFFQ69G5FAC",
        )

        for listing_id in product_ids:
            with self.subTest(listing_id=listing_id):
                async with httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _: httpx.Response(
                            200,
                            json=hybrid_response(listing_id=listing_id),
                        )
                    )
                ) as transport:
                    client = HybridMarketplaceDiscoveryClient(
                        product=FakeProductDetail(),
                        product_base_url="http://product-service:8091",
                        product_service_token="internal-test-token",
                        embedding_provider=FakeEmbeddingProvider(),
                        client=transport,
                    )
                    result = await client.search_individual(
                        actor_user_id=ACTOR,
                        request=DiscoverySearchRequest(q="desk chair"),
                        correlation_id="hybrid-product-id-contract",
                    )
                self.assertEqual(listing_id, result.data[0].listing_id)

    async def test_invalid_product_listing_identifiers_are_rejected(self) -> None:
        invalid_ids = (
            "01ARZ3NDEKTSV4RRFFQ69G5Fac",
            "01ARZ3NDEKTSV4RRFFQ69G5FIC",
            "01ARZ3NDEKTSV4RRFFQ69G5F",
            "01ARZ3NDEKTSV4RRFFQ69G5FAC ",
        )

        for listing_id in invalid_ids:
            with self.subTest(listing_id=listing_id):
                async with httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _: httpx.Response(
                            200,
                            json=hybrid_response(listing_id=listing_id),
                        )
                    )
                ) as transport:
                    client = HybridMarketplaceDiscoveryClient(
                        product=FakeProductDetail(),
                        product_base_url="http://product-service:8091",
                        product_service_token="internal-test-token",
                        embedding_provider=FakeEmbeddingProvider(),
                        client=transport,
                    )
                    with self.assertRaises(MarketplaceRetrievalError) as raised:
                        await client.search_individual(
                            actor_user_id=ACTOR,
                            request=DiscoverySearchRequest(q="desk chair"),
                            correlation_id="hybrid-invalid-product-id",
                        )
                self.assertEqual(
                    "MARKETPLACE_HYBRID_RESPONSE_INVALID",
                    raised.exception.code,
                )

    async def test_provider_failure_stops_before_product(self) -> None:
        embedding = FakeEmbeddingProvider(
            error=EmbeddingProviderError("RATE_LIMITED", retryable=True)
        )
        product_calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal product_calls
            product_calls += 1
            return httpx.Response(500)

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-query-2",
                )

        self.assertEqual("MARKETPLACE_HYBRID_QUERY_EMBEDDING_FAILED", raised.exception.code)
        self.assertTrue(raised.exception.retryable)
        self.assertEqual(0, product_calls)

    async def test_missing_query_or_price_currency_stops_before_provider(self) -> None:
        embedding = FakeEmbeddingProvider()
        client = HybridMarketplaceDiscoveryClient(
            product=FakeProductDetail(),
            product_base_url="http://product-service:8091",
            product_service_token="internal-test-token",
            embedding_provider=embedding,
            client=httpx.AsyncClient(transport=httpx.MockTransport(lambda _: None)),
        )
        with self.assertRaises(MarketplaceRetrievalError) as missing:
            await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(),
                correlation_id="hybrid-query-3",
            )
        with self.assertRaises(MarketplaceRetrievalError) as currency:
            await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(q="chair", maxPrice="100"),
                correlation_id="hybrid-query-4",
            )
        await client._client.aclose()  # type: ignore[union-attr]
        self.assertEqual("MARKETPLACE_HYBRID_QUERY_REQUIRED", missing.exception.code)
        self.assertEqual("MARKETPLACE_HYBRID_CURRENCY_REQUIRED", currency.exception.code)
        self.assertEqual([], embedding.calls)

    async def test_malformed_or_private_product_response_is_rejected(self) -> None:
        embedding = FakeEmbeddingProvider()

        def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=hybrid_response(extra=True))

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-query-5",
                )
        self.assertEqual("MARKETPLACE_HYBRID_RESPONSE_INVALID", raised.exception.code)

    async def test_inconsistent_product_facet_counts_are_rejected(self) -> None:
        payload = hybrid_response()
        payload["discovery"]["facets"]["subtype"][0]["count"] = 2

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(200, json=payload)
            )
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-facet-count-invalid",
                )

        self.assertEqual("MARKETPLACE_HYBRID_RESPONSE_INVALID", raised.exception.code)

    async def test_inconsistent_relevance_evidence_is_rejected(self) -> None:
        payload = hybrid_response()
        payload["discovery"]["relevantMatchCount"] = 2

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(200, json=payload)
            )
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-relevance-invalid",
                )

        self.assertEqual("MARKETPLACE_HYBRID_RESPONSE_INVALID", raised.exception.code)

    async def test_results_first_client_rejects_an_older_response_shape(self) -> None:
        payload = hybrid_response()
        payload["schemaVersion"] = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2"
        payload["discovery"].pop("relevantMatchCount")
        payload["discovery"].pop("retrievalConfidence")

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(200, json=payload)
            )
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-version-mismatch",
                )

        self.assertEqual("MARKETPLACE_HYBRID_RESPONSE_INVALID", raised.exception.code)

    async def test_legacy_v2_client_accepts_v1_without_inventing_summary(self) -> None:
        payload = hybrid_response()
        payload["schemaVersion"] = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1"
        payload.pop("discovery")

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda _: httpx.Response(200, json=payload)
            )
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=FakeEmbeddingProvider(),
                response_schema_version="MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2",
                client=transport,
            )
            result = await client.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(q="desk chair"),
                correlation_id="hybrid-legacy-v1-response",
            )

        self.assertEqual((LISTING,), tuple(item.listing_id for item in result.data))
        self.assertIsNone(result.summary)

    async def test_product_statuses_map_without_reading_upstream_body(self) -> None:
        cases = {
            400: "MARKETPLACE_HYBRID_PRODUCT_INVALID_REQUEST",
            403: "MARKETPLACE_HYBRID_PRODUCT_AUTHENTICATION_REQUIRED",
            404: "MARKETPLACE_HYBRID_PRODUCT_DISABLED",
            409: "MARKETPLACE_HYBRID_PRODUCT_IDENTITY_MISMATCH",
            503: "MARKETPLACE_HYBRID_PRODUCT_UNAVAILABLE",
        }
        for status, expected in cases.items():
            with self.subTest(status=status):
                async with httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _: httpx.Response(
                            status,
                            content=b'{"secret":"must-not-be-parsed"}',
                        )
                    )
                ) as transport:
                    client = HybridMarketplaceDiscoveryClient(
                        product=FakeProductDetail(),
                        product_base_url="http://product-service:8091",
                        product_service_token="internal-test-token",
                        embedding_provider=FakeEmbeddingProvider(),
                        client=transport,
                    )
                    with self.assertRaises(MarketplaceRetrievalError) as raised:
                        await client.search_individual(
                            actor_user_id=ACTOR,
                            request=DiscoverySearchRequest(q="desk chair"),
                            correlation_id=f"hybrid-status-{status}",
                        )
                self.assertEqual(expected, raised.exception.code)
                self.assertNotIn("secret", str(raised.exception))

    async def test_product_timeout_is_distinct_from_http_status(self) -> None:
        embedding = FakeEmbeddingProvider()

        def handler(_: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("secret upstream timeout")

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-product-timeout",
                )

        self.assertEqual("MARKETPLACE_HYBRID_PRODUCT_TIMEOUT", raised.exception.code)
        self.assertTrue(raised.exception.retryable)
        self.assertNotIn("secret", str(raised.exception))

    async def test_float32_overflow_is_rejected_before_product(self) -> None:
        embedding = FakeEmbeddingProvider(
            result=EmbeddingBatchResult(
                vectors=(tuple([1e100] + [0.0] * 1535),),
                provider="openai",
                model="text-embedding-3-small",
                dimensions=1536,
                input_tokens=1,
            )
        )
        product_calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal product_calls
            product_calls += 1
            return httpx.Response(200, json=hybrid_response())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as transport:
            client = HybridMarketplaceDiscoveryClient(
                product=FakeProductDetail(),
                product_base_url="http://product-service:8091",
                product_service_token="internal-test-token",
                embedding_provider=embedding,
                client=transport,
            )
            with self.assertRaises(MarketplaceRetrievalError) as raised:
                await client.search_individual(
                    actor_user_id=ACTOR,
                    request=DiscoverySearchRequest(q="desk chair"),
                    correlation_id="hybrid-query-6",
                )
        self.assertEqual("MARKETPLACE_HYBRID_QUERY_EMBEDDING_INVALID", raised.exception.code)
        self.assertEqual(0, product_calls)

    def test_query_normalization_is_deterministic_and_bounded(self) -> None:
        self.assertEqual(
            "used bicycle Irvine",
            normalize_hybrid_query("  used\tbicycle\u202e Irvine  "),
        )
        with self.assertRaises(MarketplaceRetrievalError):
            normalize_hybrid_query("\u202e\u0000")
        with self.assertRaises(MarketplaceRetrievalError):
            normalize_hybrid_query("x" * 201)


if __name__ == "__main__":
    unittest.main()
