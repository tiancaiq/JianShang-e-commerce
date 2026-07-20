from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import unittest

import httpx
from prometheus_client import generate_latest
from pydantic import ValidationError

from msb_agent_service.listing_content_proposal import (
    ListingProposalError,
    ListingProposalErrorCode,
)
from msb_agent_service.listing_media_tool import (
    ProductListingDraftMediaTool,
    ProductListingMediaToolMetrics,
    ProductListingMediaToolSettings,
)

ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"
OTHER_LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD"
MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE"
MEDIA_ID_2 = "01ARZ3NDEKTSV4RRFFQ69G5FAF"
PNG = b"\x89PNG\r\n\x1a\nsafe-offline-image"


def response_payload(
    *,
    listing_id: str = LISTING_ID,
    media_ids: tuple[str, ...] = (MEDIA_ID,),
    content: bytes = PNG,
) -> dict[str, object]:
    return {
        "schemaVersion": "ai-list-owned-draft-media-v1",
        "listingId": listing_id,
        "listingVersion": "12",
        "eligibility": "OWNED_DRAFT",
        "media": [
            {
                "mediaId": media_id,
                "contentType": "image/png",
                "byteSize": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
                "sourceVersion": str(index + 7),
                "contentBase64": base64.b64encode(content).decode("ascii"),
            }
            for index, media_id in enumerate(media_ids)
        ],
    }


def enabled_settings(**overrides: object) -> ProductListingMediaToolSettings:
    values: dict[str, object] = {
        "enabled": True,
        "product_service_url": "http://product.test",
        "internal_service_token": "offline-test-token",
        "timeout_seconds": 0.2,
    }
    values.update(overrides)
    return ProductListingMediaToolSettings(**values)


class ProductListingMediaToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_success_preserves_trusted_order_and_sends_no_extra_identity(self) -> None:
        calls: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(request)
            body = json.loads(request.content)
            self.assertEqual(
                body,
                {
                    "schemaVersion": "ai-list-owned-draft-media-v1",
                    "actorUserId": ACTOR_ID,
                    "mediaIds": [MEDIA_ID_2, MEDIA_ID],
                },
            )
            self.assertEqual(
                request.headers["X-Agent-Internal-Service-Token"],
                "offline-test-token",
            )
            self.assertEqual(request.headers["X-Correlation-Id"], "corr-media")
            return httpx.Response(
                200,
                json=response_payload(media_ids=(MEDIA_ID_2, MEDIA_ID)),
            )

        metrics = ProductListingMediaToolMetrics()
        tool = ProductListingDraftMediaTool(
            enabled_settings(),
            client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
            metrics=metrics,
        )
        context = await tool.read_owned_draft_media(
            actor_user_id=ACTOR_ID,
            listing_id=LISTING_ID,
            media_ids=(MEDIA_ID_2, MEDIA_ID),
            correlation_id="corr-media",
        )

        self.assertEqual(context.listing_id, LISTING_ID)
        self.assertEqual(
            tuple(item.media_id for item in context.media),
            (MEDIA_ID_2, MEDIA_ID),
        )
        self.assertTrue(all(item.content == PNG for item in context.media))
        self.assertEqual(len(calls), 1)
        metric_text = generate_latest(metrics.registry).decode()
        self.assertIn('result="success"', metric_text)
        self.assertNotIn(ACTOR_ID, metric_text)
        self.assertNotIn(LISTING_ID, metric_text)
        await tool.close()

    async def test_disabled_adapter_performs_zero_product_calls(self) -> None:
        calls = 0

        def handler(_: httpx.Request) -> httpx.Response:
            nonlocal calls
            calls += 1
            return httpx.Response(500)

        tool = ProductListingDraftMediaTool(
            ProductListingMediaToolSettings(),
            client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        with self.assertRaises(ListingProposalError) as captured:
            await tool.read_owned_draft_media(
                actor_user_id=ACTOR_ID,
                listing_id=LISTING_ID,
                media_ids=(MEDIA_ID,),
                correlation_id="corr-disabled",
            )
        self.assertEqual(captured.exception.code, ListingProposalErrorCode.DISABLED)
        self.assertEqual(calls, 0)
        await tool.close()

    def test_enabled_configuration_requires_internal_url_and_secret(self) -> None:
        with self.assertRaises(ValidationError):
            ProductListingMediaToolSettings(enabled=True)
        self.assertNotIn("offline-test-token", repr(enabled_settings()))

    async def test_hidden_product_denial_is_non_retryable_unavailable(self) -> None:
        async def assert_status(status: int) -> None:
            tool = ProductListingDraftMediaTool(
                enabled_settings(),
                client=httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _: httpx.Response(status, json={"safe": True})
                    )
                ),
            )
            with self.assertRaises(ListingProposalError) as captured:
                await tool.read_owned_draft_media(
                    actor_user_id=ACTOR_ID,
                    listing_id=LISTING_ID,
                    media_ids=(MEDIA_ID,),
                    correlation_id="corr-denied",
                )
            self.assertEqual(
                captured.exception.code,
                ListingProposalErrorCode.UNAVAILABLE,
            )
            self.assertFalse(captured.exception.retryable)
            await tool.close()

        await assert_status(403)
        await assert_status(404)

    async def test_redirect_is_rejected_without_following_target(self) -> None:
        calls: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            calls.append(str(request.url))
            return httpx.Response(
                302,
                headers={"Location": "http://attacker.test/image"},
            )

        tool = ProductListingDraftMediaTool(
            enabled_settings(),
            client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        )
        with self.assertRaises(ListingProposalError) as captured:
            await tool.read_owned_draft_media(
                actor_user_id=ACTOR_ID,
                listing_id=LISTING_ID,
                media_ids=(MEDIA_ID,),
                correlation_id="corr-redirect",
            )
        self.assertEqual(captured.exception.code, ListingProposalErrorCode.UNAVAILABLE)
        self.assertEqual(
            calls,
            [
                f"http://product.test/api/v1/internal/agent/listings/"
                f"{LISTING_ID}/draft-media"
            ],
        )
        await tool.close()

    async def test_response_listing_or_media_order_mismatch_fails_closed(self) -> None:
        for payload in (
            response_payload(listing_id=OTHER_LISTING_ID),
            response_payload(media_ids=(MEDIA_ID_2,)),
        ):
            tool = ProductListingDraftMediaTool(
                enabled_settings(),
                client=httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _, value=payload: httpx.Response(200, json=value)
                    )
                ),
            )
            with self.assertRaises(ListingProposalError) as captured:
                await tool.read_owned_draft_media(
                    actor_user_id=ACTOR_ID,
                    listing_id=LISTING_ID,
                    media_ids=(MEDIA_ID,),
                    correlation_id="corr-mismatch",
                )
            self.assertEqual(
                captured.exception.code,
                ListingProposalErrorCode.UNAVAILABLE,
            )
            await tool.close()

    async def test_invalid_base64_digest_and_extra_fields_are_rejected(self) -> None:
        invalid_base64 = response_payload()
        invalid_base64["media"][0]["contentBase64"] = "***"
        invalid_digest = response_payload()
        invalid_digest["media"][0]["sha256"] = "0" * 64
        invalid_mime = response_payload()
        invalid_mime["media"][0]["contentType"] = "image/jpeg"
        extra_field = response_payload()
        extra_field["storageKey"] = "must-not-be-accepted"

        for payload in (invalid_base64, invalid_digest, invalid_mime, extra_field):
            tool = ProductListingDraftMediaTool(
                enabled_settings(),
                client=httpx.AsyncClient(
                    transport=httpx.MockTransport(
                        lambda _, value=payload: httpx.Response(200, json=value)
                    )
                ),
            )
            with self.assertRaises(ListingProposalError) as captured:
                await tool.read_owned_draft_media(
                    actor_user_id=ACTOR_ID,
                    listing_id=LISTING_ID,
                    media_ids=(MEDIA_ID,),
                    correlation_id="corr-invalid",
                )
            self.assertEqual(
                captured.exception.code,
                ListingProposalErrorCode.UNAVAILABLE,
            )
            await tool.close()

    async def test_timeout_and_cancellation_remain_distinct(self) -> None:
        async def slow_handler(_: httpx.Request) -> httpx.Response:
            await asyncio.sleep(1)
            return httpx.Response(200, json=response_payload())

        timeout_tool = ProductListingDraftMediaTool(
            enabled_settings(timeout_seconds=0.01),
            client=httpx.AsyncClient(transport=httpx.MockTransport(slow_handler)),
        )
        with self.assertRaises(ListingProposalError) as captured:
            await timeout_tool.read_owned_draft_media(
                actor_user_id=ACTOR_ID,
                listing_id=LISTING_ID,
                media_ids=(MEDIA_ID,),
                correlation_id="corr-timeout",
            )
        self.assertEqual(captured.exception.code, ListingProposalErrorCode.TIMED_OUT)
        self.assertTrue(captured.exception.retryable)
        await timeout_tool.close()

        cancellation_tool = ProductListingDraftMediaTool(
            enabled_settings(),
            client=httpx.AsyncClient(transport=httpx.MockTransport(slow_handler)),
        )
        task = asyncio.create_task(
            cancellation_tool.read_owned_draft_media(
                actor_user_id=ACTOR_ID,
                listing_id=LISTING_ID,
                media_ids=(MEDIA_ID,),
                correlation_id="corr-cancel",
            )
        )
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        await cancellation_tool.close()

    async def test_safe_logs_contain_hashes_not_identity_secret_or_content(
        self,
    ) -> None:
        tool = ProductListingDraftMediaTool(
            enabled_settings(),
            client=httpx.AsyncClient(
                transport=httpx.MockTransport(
                    lambda _: httpx.Response(200, json=response_payload())
                )
            ),
        )
        with self.assertLogs(
            "msb_agent_service.listing_media_tool",
            level=logging.INFO,
        ) as captured:
            await tool.read_owned_draft_media(
                actor_user_id=ACTOR_ID,
                listing_id=LISTING_ID,
                media_ids=(MEDIA_ID,),
                correlation_id="corr-private",
            )
        logs = "\n".join(captured.output)
        self.assertNotIn(ACTOR_ID, logs)
        self.assertNotIn(LISTING_ID, logs)
        self.assertNotIn("offline-test-token", logs)
        self.assertNotIn("safe-offline-image", logs)
        self.assertIn(hashlib.sha256(ACTOR_ID.encode()).hexdigest(), logs)
        await tool.close()
