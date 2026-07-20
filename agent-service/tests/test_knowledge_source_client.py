from __future__ import annotations

import unittest

import httpx

from msb_agent_service.config import KnowledgeIngestionSettings
from msb_agent_service.knowledge_source_client import (
    KnowledgeSourceClient,
    KnowledgeSourceError,
    KnowledgeSourceErrorCode,
)

LISTING_ID = "01L00000000000000000000001"


def settings() -> KnowledgeIngestionSettings:
    return KnowledgeIngestionSettings(
        enabled=True,
        mysql_password="database-secret",
        product_service_url="http://product-service:8091",
        product_service_token="source-secret",
    )


def active_source(**overrides: object) -> dict[str, object]:
    source: dict[str, object] = {
        "sourceType": "LISTING",
        "sourceId": LISTING_ID,
        "sourceVersion": "12",
        "supersedesVersion": "11",
        "lifecycle": "ACTIVE",
        "visibility": "PUBLIC",
        "language": "und",
        "effectiveFrom": "2026-07-19T01:00:00Z",
        "sourcePublishedAt": "2026-07-19T01:00:00Z",
        "contentHash": "a" * 64,
        "content": {
            "title": "Used bicycle",
            "description": "Approved public description.",
            "price": {"amount": "250.00", "currency": "USD"},
            "publicLocation": {"city": "Irvine", "region": "CA"},
        },
    }
    source.update(overrides)
    return source


class KnowledgeSourceClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_fetches_exact_version_with_dedicated_token(self) -> None:
        observed_request: httpx.Request | None = None

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal observed_request
            observed_request = request
            return httpx.Response(200, json=active_source())

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = KnowledgeSourceClient(settings(), http_client)
        try:
            source, duration = await client.fetch_listing(LISTING_ID, 12)
        finally:
            await http_client.aclose()

        self.assertEqual(LISTING_ID, source.source_id)
        self.assertEqual("Used bicycle", source.content.title)  # type: ignore[union-attr]
        self.assertGreaterEqual(duration, 0)
        self.assertIsNotNone(observed_request)
        self.assertEqual(
            "source-secret",
            observed_request.headers["X-Agent-Internal-Service-Token"],  # type: ignore[union-attr]
        )
        self.assertEqual(
            f"/api/v1/internal/agent/knowledge/listings/{LISTING_ID}/versions/12",
            observed_request.url.path,  # type: ignore[union-attr]
        )

    async def test_auth_and_missing_source_are_permanent(self) -> None:
        for status, expected in (
            (403, KnowledgeSourceErrorCode.UNAUTHORIZED),
            (404, KnowledgeSourceErrorCode.NOT_FOUND),
        ):
            with self.subTest(status=status):
                http_client = httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda request, response_status=status: httpx.Response(
                            response_status
                        )
                    )
                )
                client = KnowledgeSourceClient(settings(), http_client)
                try:
                    with self.assertRaises(KnowledgeSourceError) as captured:
                        await client.fetch_listing(LISTING_ID, 12)
                finally:
                    await http_client.aclose()
                self.assertEqual(expected, captured.exception.code)
                self.assertFalse(captured.exception.retryable)

    async def test_timeout_is_retryable_without_exposing_token(self) -> None:
        def timeout(request: httpx.Request) -> httpx.Response:
            raise httpx.ReadTimeout("timed out with source-secret", request=request)

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(timeout))
        client = KnowledgeSourceClient(settings(), http_client)
        try:
            with self.assertRaises(KnowledgeSourceError) as captured:
                await client.fetch_listing(LISTING_ID, 12)
        finally:
            await http_client.aclose()

        self.assertEqual(KnowledgeSourceErrorCode.TIMEOUT, captured.exception.code)
        self.assertTrue(captured.exception.retryable)
        self.assertNotIn("source-secret", str(captured.exception))

    async def test_unknown_response_field_fails_closed(self) -> None:
        response = active_source(privateSellerEmail="private@example.test")
        http_client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json=response)
            )
        )
        client = KnowledgeSourceClient(settings(), http_client)
        try:
            with self.assertRaises(KnowledgeSourceError) as captured:
                await client.fetch_listing(LISTING_ID, 12)
        finally:
            await http_client.aclose()

        self.assertEqual(
            KnowledgeSourceErrorCode.INVALID_RESPONSE,
            captured.exception.code,
        )
        self.assertFalse(captured.exception.retryable)

    async def test_export_uses_only_bounded_cursor_and_stable_page_shape(
        self,
    ) -> None:
        observed_request: httpx.Request | None = None

        def handler(request: httpx.Request) -> httpx.Response:
            nonlocal observed_request
            observed_request = request
            return httpx.Response(
                200,
                json={
                    "items": [active_source()],
                    "nextCursor": "opaque-next",
                    "hasMore": True,
                    "exportWatermark": "2026-07-19T01:00:00Z",
                },
            )

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        client = KnowledgeSourceClient(settings(), http_client)
        try:
            page, _ = await client.fetch_listing_export(
                cursor="opaque-current",
                limit=25,
            )
        finally:
            await http_client.aclose()

        self.assertTrue(page.has_more)
        self.assertEqual("opaque-next", page.next_cursor)
        self.assertIsNotNone(observed_request)
        self.assertEqual(
            "/api/v1/internal/agent/knowledge/listings/export",
            observed_request.url.path,  # type: ignore[union-attr]
        )
        self.assertEqual("opaque-current", observed_request.url.params["cursor"])  # type: ignore[union-attr]
        self.assertEqual("25", observed_request.url.params["limit"])  # type: ignore[union-attr]

    async def test_export_rejects_inconsistent_cursor_shape(self) -> None:
        http_client = httpx.AsyncClient(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={
                        "items": [],
                        "nextCursor": None,
                        "hasMore": True,
                        "exportWatermark": "2026-07-19T01:00:00Z",
                    },
                )
            )
        )
        client = KnowledgeSourceClient(settings(), http_client)
        try:
            with self.assertRaises(KnowledgeSourceError) as captured:
                await client.fetch_listing_export()
        finally:
            await http_client.aclose()
        self.assertEqual(
            KnowledgeSourceErrorCode.INVALID_RESPONSE,
            captured.exception.code,
        )


if __name__ == "__main__":
    unittest.main()
