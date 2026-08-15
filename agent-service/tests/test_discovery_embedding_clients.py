from __future__ import annotations

import json
import math
import unittest
from datetime import UTC, datetime

import httpx

from msb_agent_service.discovery_embedding_clients import (
    DiscoveryEmbeddingClientError,
    DiscoveryEmbeddingClientErrorCode,
    ProductDiscoveryEmbeddingCallbackClient,
    ProductDiscoveryEmbeddingSourceClient,
)
from msb_agent_service.discovery_embedding_contract import (
    DiscoveryEmbeddingResult,
    EmbeddingIdentity,
)
from msb_agent_service.discovery_embedding_jobs import (
    DiscoveryEmbeddingJob,
    DiscoveryEmbeddingJobStatus,
)

EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"


def job() -> DiscoveryEmbeddingJob:
    return DiscoveryEmbeddingJob(
        job_id="01ARZ3NDEKTSV4RRFFQ69G5FAD",
        event_id=EVENT_ID,
        request_id=REQUEST_ID,
        listing_id=LISTING_ID,
        listing_version=7,
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
        event_occurred_at=datetime(2026, 7, 23, 10, tzinfo=UTC),
        correlation_id="correlation-04b",
        event_payload_hash="c" * 64,
        status=DiscoveryEmbeddingJobStatus.PROCESSING,
        attempt_count=1,
    )


def source_response(**updates: object) -> dict[str, object]:
    value: dict[str, object] = {
        "schemaVersion": "MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1",
        "requestId": REQUEST_ID,
        "listingId": LISTING_ID,
        "listingVersion": 7,
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
        "embeddingText": "TITLE\nDesk\nCATEGORY\nFurniture\nfurniture\nDESCRIPTION\nSafe",
    }
    value.update(updates)
    return value


def result() -> DiscoveryEmbeddingResult:
    return DiscoveryEmbeddingResult(
        listingVersion=7,
        documentSchemaVersion="MARKETPLACE_LISTING_DISCOVERY_V2",
        documentHash="a" * 64,
        embeddingInputSchemaVersion="MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
        embeddingInputHash="b" * 64,
        embeddingIdentity=EmbeddingIdentity(
            provider="openai",
            model="text-embedding-3-small",
            dimensions=1536,
        ),
        vector=[0.125] * 1536,
    )


class ProductDiscoveryEmbeddingSourceClientTests(
    unittest.IsolatedAsyncioTestCase
):
    async def test_fetches_exact_request_path_and_propagates_safe_headers(self) -> None:
        observed: httpx.Request | None = None

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal observed
            observed = request
            return httpx.Response(200, json=source_response(), request=request)

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = ProductDiscoveryEmbeddingSourceClient(
            base_url="http://product.test",
            service_token="internal-secret",
            timeout_seconds=1,
            client=http_client,
        )
        source, _ = await client.fetch(job())
        await http_client.aclose()

        self.assertEqual(REQUEST_ID, source.request_id)
        assert observed is not None
        self.assertEqual(
            f"/api/v1/internal/agent/discovery/embedding-requests/"
            f"{REQUEST_ID}/source",
            observed.url.path,
        )
        self.assertEqual(
            "internal-secret",
            observed.headers["X-Agent-Internal-Service-Token"],
        )
        self.assertEqual(
            "correlation-04b",
            observed.headers["X-Correlation-Id"],
        )

    async def test_source_identity_mismatch_stops_before_provider(self) -> None:
        http_client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json=source_response(documentHash="d" * 64),
                    request=request,
                )
            )
        )
        client = ProductDiscoveryEmbeddingSourceClient(
            base_url="http://product.test",
            service_token="internal-secret",
            timeout_seconds=1,
            client=http_client,
        )
        with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
            await client.fetch(job())
        await http_client.aclose()
        self.assertEqual(
            DiscoveryEmbeddingClientErrorCode.SOURCE_IDENTITY_MISMATCH,
            raised.exception.code,
        )
        self.assertFalse(raised.exception.retryable)

    async def test_source_statuses_separate_stale_from_retryable_outage(self) -> None:
        cases = (
            (
                404,
                {"error": {"code": "LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND"}},
                DiscoveryEmbeddingClientErrorCode.SOURCE_STALE,
                False,
            ),
            (
                404,
                {"error": {"code": "FEATURE_DISABLED"}},
                DiscoveryEmbeddingClientErrorCode.SOURCE_UNAVAILABLE,
                True,
            ),
            (
                503,
                {"upstream": "must-not-echo"},
                DiscoveryEmbeddingClientErrorCode.SOURCE_UNAVAILABLE,
                True,
            ),
        )
        for status, body, code, retryable in cases:
            with self.subTest(status=status, code=code):
                http_client = httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda request, selected_status=status, selected_body=body:
                            httpx.Response(
                                selected_status,
                                json=selected_body,
                                request=request,
                            )
                    )
                )
                client = ProductDiscoveryEmbeddingSourceClient(
                    base_url="http://product.test",
                    service_token="internal-secret",
                    timeout_seconds=1,
                    client=http_client,
                )
                with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
                    await client.fetch(job())
                await http_client.aclose()
                self.assertEqual(code, raised.exception.code)
                self.assertEqual(retryable, raised.exception.retryable)
                self.assertNotIn("must-not-echo", str(raised.exception))

    async def test_source_timeout_is_retryable_without_echoing_exception(self) -> None:
        def timeout(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("contains-private-source", request=request)

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(timeout))
        client = ProductDiscoveryEmbeddingSourceClient(
            base_url="http://product.test",
            service_token="internal-secret",
            timeout_seconds=1,
            client=http_client,
        )
        with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
            await client.fetch(job())
        await http_client.aclose()
        self.assertEqual(
            DiscoveryEmbeddingClientErrorCode.SOURCE_TIMEOUT,
            raised.exception.code,
        )
        self.assertTrue(raised.exception.retryable)
        self.assertNotIn("private-source", str(raised.exception))


class ProductDiscoveryEmbeddingCallbackClientTests(
    unittest.IsolatedAsyncioTestCase
):
    async def test_submits_strict_request_and_requires_exact_acknowledgement(self) -> None:
        observed: httpx.Request | None = None

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal observed
            observed = request
            return httpx.Response(
                200,
                json={
                    "schemaVersion": "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1",
                    "requestId": REQUEST_ID,
                    "outcome": "ACCEPTED",
                },
                request=request,
            )

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = ProductDiscoveryEmbeddingCallbackClient(
            base_url="http://product.test",
            service_token="internal-secret",
            timeout_seconds=1,
            client=http_client,
        )
        await client.submit(job=job(), result=result())
        await http_client.aclose()

        assert observed is not None
        self.assertEqual(
            f"/api/v1/internal/agent/discovery/embedding-requests/"
            f"{REQUEST_ID}/result",
            observed.url.path,
        )
        body = json.loads(observed.content)
        self.assertEqual(set(body), {
            "schemaVersion",
            "listingVersion",
            "documentSchemaVersion",
            "documentHash",
            "embeddingInputSchemaVersion",
            "embeddingInputHash",
            "embeddingIdentity",
            "vector",
        })
        self.assertEqual(1536, len(body["vector"]))
        self.assertTrue(all(math.isfinite(value) for value in body["vector"]))
        self.assertNotIn("embeddingText", body)
        self.assertNotIn("listingId", body)

    async def test_callback_timeout_and_503_are_retryable_unknown_outcomes(self) -> None:
        def timeout(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("vector-body-must-not-echo", request=request)

        transports = (
            httpx.MockTransport(timeout),
            httpx.MockTransport(
                lambda request: httpx.Response(503, json={"secret": "body"}, request=request)
            ),
        )
        expected = (
            DiscoveryEmbeddingClientErrorCode.CALLBACK_TIMEOUT,
            DiscoveryEmbeddingClientErrorCode.CALLBACK_UNAVAILABLE,
        )
        for transport, code in zip(transports, expected, strict=True):
            http_client = httpx.AsyncClient(transport=transport)
            client = ProductDiscoveryEmbeddingCallbackClient(
                base_url="http://product.test",
                service_token="internal-secret",
                timeout_seconds=1,
                client=http_client,
            )
            with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
                await client.submit(job=job(), result=result())
            await http_client.aclose()
            self.assertEqual(code, raised.exception.code)
            self.assertTrue(raised.exception.retryable)
            self.assertNotIn("secret", str(raised.exception))
            self.assertNotIn("vector-body", str(raised.exception))

    async def test_callback_409_codes_are_distinct_terminal_outcomes(self) -> None:
        mappings = {
            "LISTING_DISCOVERY_EMBEDDING_STALE":
                DiscoveryEmbeddingClientErrorCode.CALLBACK_STALE,
            "LISTING_DISCOVERY_EMBEDDING_IDENTITY_CONFLICT":
                DiscoveryEmbeddingClientErrorCode.CALLBACK_IDENTITY_CONFLICT,
            "LISTING_DISCOVERY_EMBEDDING_IDEMPOTENCY_CONFLICT":
                DiscoveryEmbeddingClientErrorCode.CALLBACK_IDEMPOTENCY_CONFLICT,
        }
        for public_code, expected in mappings.items():
            with self.subTest(public_code=public_code):
                http_client = httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda request, code=public_code: httpx.Response(
                            409,
                            json={"error": {"code": code}},
                            request=request,
                        )
                    )
                )
                client = ProductDiscoveryEmbeddingCallbackClient(
                    base_url="http://product.test",
                    service_token="internal-secret",
                    timeout_seconds=1,
                    client=http_client,
                )
                with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
                    await client.submit(job=job(), result=result())
                await http_client.aclose()
                self.assertEqual(expected, raised.exception.code)
                self.assertFalse(raised.exception.retryable)

    async def test_malformed_acknowledgement_is_terminal(self) -> None:
        http_client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={
                        "schemaVersion":
                            "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1",
                        "requestId": REQUEST_ID,
                        "outcome": "ACCEPTED",
                        "extra": "not-allowed",
                    },
                    request=request,
                )
            )
        )
        client = ProductDiscoveryEmbeddingCallbackClient(
            base_url="http://product.test",
            service_token="internal-secret",
            timeout_seconds=1,
            client=http_client,
        )
        with self.assertRaises(DiscoveryEmbeddingClientError) as raised:
            await client.submit(job=job(), result=result())
        await http_client.aclose()
        self.assertEqual(
            DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
            raised.exception.code,
        )
        self.assertFalse(raised.exception.retryable)


if __name__ == "__main__":
    unittest.main()
