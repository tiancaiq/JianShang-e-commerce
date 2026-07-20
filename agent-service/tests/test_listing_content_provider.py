from __future__ import annotations

import asyncio
import hashlib
import unittest
from types import SimpleNamespace
from typing import Any

from prometheus_client import generate_latest

from msb_agent_service.errors import LlmProviderError, ProviderErrorCode
from msb_agent_service.listing_content_proposal import (
    CategoryCandidate,
    ListingContentProposalOrchestrator,
    ListingMediaContent,
    ListingProposalCommand,
    ListingProposalError,
    ListingProposalErrorCode,
    ListingVisionCandidate,
    ListingVisionProviderResult,
    ListingVisionRequest,
    OwnedDraftMediaContext,
    ProposalAuditEvent,
    ProposalEvidence,
    SuggestedText,
    UnknownField,
    VisionMediaInput,
)
from msb_agent_service.listing_content_provider import (
    LISTING_PROPOSAL_PROVIDER_INSTRUCTIONS,
    ListingContentProviderLimits,
    ListingContentProviderMetrics,
    ResponsesListingVisionAdapter,
    build_responses_listing_content_proposal_orchestrator,
)
from msb_agent_service.provider import OpenAIProvider, StructuredProviderResult

ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"
MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE"
MEDIA_ID_2 = "01ARZ3NDEKTSV4RRFFQ69G5FAF"
REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAL"
PNG = b"\x89PNG\r\n\x1a\n"


def media_bytes(
    media_id: str = MEDIA_ID,
    content: bytes = PNG + b"offline-image",
) -> ListingMediaContent:
    return ListingMediaContent(
        media_id=media_id,
        content_type="image/png",
        byte_size=len(content),
        sha256=hashlib.sha256(content).hexdigest(),
        source_version="7",
        content=content,
    )


def vision_input(
    media_id: str = MEDIA_ID,
    content: bytes = PNG + b"offline-image",
) -> VisionMediaInput:
    return VisionMediaInput(
        media_id=media_id,
        content_type="image/png",
        sha256=hashlib.sha256(content).hexdigest(),
        content=content,
    )


def candidate() -> ListingVisionCandidate:
    return ListingVisionCandidate(
        suggested_title=SuggestedText(
            value="Wooden writing desk",
            confidence=0.91,
            evidence_ids=("E1",),
        ),
        suggested_description=SuggestedText(
            value="Wooden writing desk with three visible drawers.",
            confidence=0.86,
            evidence_ids=("E1",),
        ),
        category_candidates=(
            CategoryCandidate(
                label="Furniture",
                confidence=0.94,
                evidence_ids=("E1",),
            ),
        ),
        evidence=(
            ProposalEvidence(
                evidence_id="E1",
                media_id=MEDIA_ID,
                observation="A wooden desk and three drawers are visible.",
            ),
        ),
    )


def command() -> ListingProposalCommand:
    return ListingProposalCommand(
        actor_user_id=ACTOR_ID,
        listing_id=LISTING_ID,
        media_ids=(MEDIA_ID,),
        client_request_id=REQUEST_ID,
        correlation_id="corr-ai-list-provider",
    )


class FakeResponses:
    def __init__(self, value: ListingVisionCandidate) -> None:
        self.value = value
        self.calls: list[dict[str, Any]] = []

    async def parse(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        return SimpleNamespace(
            status="completed",
            output_parsed=self.value,
            usage=SimpleNamespace(input_tokens=31, output_tokens=17, total_tokens=48),
        )


class FakeOpenAIClient:
    def __init__(self, responses: FakeResponses) -> None:
        self.responses = responses


class FakeProviderSettings:
    openai_configured = True
    openai_model = "offline-fake-model"


class FakeStructuredProvider:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.value = candidate()
        self.error: Exception | None = None
        self.delay = 0.0

    async def customer_service_answer(self, **kwargs: object) -> StructuredProviderResult[ListingVisionCandidate]:
        self.calls.append(kwargs)
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return StructuredProviderResult(
            value=self.value,
            input_tokens=31,
            output_tokens=17,
            latency_ms=24,
        )


class FakeMediaTool:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    async def read_owned_draft_media(self, **kwargs: object) -> OwnedDraftMediaContext:
        self.calls.append(kwargs)
        return OwnedDraftMediaContext(
            listing_id=LISTING_ID,
            listing_version="12",
            eligibility="OWNED_DRAFT",
            media=(media_bytes(),),
        )


class FakeAuditSink:
    def __init__(self) -> None:
        self.events: list[ProposalAuditEvent] = []

    async def record(self, event: ProposalAuditEvent) -> None:
        self.events.append(event)


class ResponsesListingVisionAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_existing_provider_carries_typed_multimodal_without_tools_or_storage(self) -> None:
        responses = FakeResponses(candidate())
        provider = OpenAIProvider(
            FakeProviderSettings(),  # type: ignore[arg-type]
            FakeOpenAIClient(responses),
        )
        adapter = ResponsesListingVisionAdapter(provider)

        result = await adapter.propose_listing_content(
            ListingVisionRequest(untrusted_media=(vision_input(),)),
            correlation_id="corr-offline-provider",
        )

        self.assertEqual(candidate(), result.candidate)
        call = responses.calls[0]
        self.assertFalse(call["store"])
        self.assertNotIn("tools", call)
        self.assertIs(ListingVisionCandidate, call["text_format"])
        self.assertEqual(400, call["max_output_tokens"])
        self.assertEqual(LISTING_PROPOSAL_PROVIDER_INSTRUCTIONS, call["instructions"])
        self.assertNotIn(MEDIA_ID, call["instructions"])
        content = call["input"][0]["content"]
        self.assertEqual("input_text", content[0]["type"])
        self.assertEqual("input_text", content[1]["type"])
        self.assertEqual("input_image", content[2]["type"])
        self.assertTrue(content[2]["image_url"].startswith("data:image/png;base64,"))

    async def test_multiple_images_preserve_manifest_ids_and_hashes(self) -> None:
        provider = FakeStructuredProvider()
        adapter = ResponsesListingVisionAdapter(provider)
        second_content = PNG + b"second-image"
        second = vision_input(MEDIA_ID_2, second_content)

        await adapter.propose_listing_content(
            ListingVisionRequest(untrusted_media=(vision_input(), second)),
            correlation_id="corr-multi",
        )

        input_items = provider.calls[0]["input_items"]
        serialized = str(input_items)
        self.assertIn(MEDIA_ID, serialized)
        self.assertIn(MEDIA_ID_2, serialized)
        self.assertIn(
            hashlib.sha256(PNG + b"offline-image").hexdigest(),
            serialized,
        )
        self.assertIn(hashlib.sha256(second_content).hexdigest(), serialized)
        self.assertNotIn(ACTOR_ID, serialized)
        self.assertNotIn(LISTING_ID, serialized)

    async def test_digest_mime_size_and_context_budgets_fail_before_provider(self) -> None:
        provider = FakeStructuredProvider()
        metrics = ListingContentProviderMetrics()
        adapter = ResponsesListingVisionAdapter(
            provider,
            limits=ListingContentProviderLimits(
                maximum_media_bytes=8,
                maximum_total_media_bytes=8,
                maximum_serialized_input_bytes=1_024,
                maximum_context_tokens=256,
            ),
            metrics=metrics,
        )
        bad_digest = VisionMediaInput.model_construct(
            media_id=MEDIA_ID,
            content_type="image/png",
            sha256="0" * 64,
            content=PNG + b"image",
        )
        for request in (
            ListingVisionRequest.model_construct(
                instruction_version="listing-proposal-vision-v1",
                untrusted_media=(bad_digest,),
            ),
            ListingVisionRequest(
                untrusted_media=(vision_input(content=PNG + b"0123456789"),)
            ),
            ListingVisionRequest.model_construct(
                instruction_version="listing-proposal-vision-v1",
                untrusted_media=(
                    VisionMediaInput.model_construct(
                        media_id=MEDIA_ID,
                        content_type="image/gif",
                        sha256=hashlib.sha256(b"gif").hexdigest(),
                        content=b"gif",
                    ),
                )
            ),
        ):
            with self.assertRaises(ListingProposalError) as raised:
                await adapter.propose_listing_content(request, correlation_id="corr-reject")
            self.assertEqual(ListingProposalErrorCode.REJECTED, raised.exception.code)
        self.assertEqual([], provider.calls)

        context_adapter = ResponsesListingVisionAdapter(
            provider,
            limits=ListingContentProviderLimits(
                maximum_media_bytes=1_024,
                maximum_total_media_bytes=1_024,
                maximum_serialized_input_bytes=1_024,
                maximum_context_tokens=256,
            ),
            metrics=metrics,
        )
        with self.assertRaises(ListingProposalError) as context_error:
            await context_adapter.propose_listing_content(
                ListingVisionRequest(
                    untrusted_media=(vision_input(content=PNG + b"x" * 900),)
                ),
                correlation_id="corr-context-budget",
            )
        self.assertEqual(ListingProposalErrorCode.REJECTED, context_error.exception.code)
        self.assertEqual([], provider.calls)

    async def test_provider_failure_mapping_is_stable_and_safe(self) -> None:
        provider = FakeStructuredProvider()
        adapter = ResponsesListingVisionAdapter(provider)
        mappings = (
            (
                LlmProviderError(
                    ProviderErrorCode.RATE_LIMITED,
                    "fake rate limit detail",
                    retryable=True,
                    status_code=503,
                ),
                ListingProposalErrorCode.PROVIDER_UNAVAILABLE,
                True,
            ),
            (
                LlmProviderError(
                    ProviderErrorCode.INVALID_RESPONSE,
                    "fake malformed body",
                    retryable=False,
                    status_code=502,
                ),
                ListingProposalErrorCode.REJECTED,
                False,
            ),
            (
                LlmProviderError(
                    ProviderErrorCode.TIMED_OUT,
                    "fake timeout detail",
                    retryable=True,
                    status_code=504,
                ),
                ListingProposalErrorCode.TIMED_OUT,
                True,
            ),
        )
        for error, expected_code, retryable in mappings:
            provider.error = error
            with self.assertRaises(ListingProposalError) as raised:
                await adapter.propose_listing_content(
                    ListingVisionRequest(untrusted_media=(vision_input(),)),
                    correlation_id="corr-errors",
                )
            self.assertEqual(expected_code, raised.exception.code)
            self.assertEqual(retryable, raised.exception.retryable)
            self.assertNotIn("fake", str(raised.exception))

    async def test_invalid_usage_metadata_is_rejected_before_orchestrator(self) -> None:
        provider = FakeStructuredProvider()
        provider_result = StructuredProviderResult(
            value=candidate(),
            input_tokens=-1,
            output_tokens=0,
            latency_ms=0,
        )

        async def invalid_usage(**kwargs: object) -> StructuredProviderResult[ListingVisionCandidate]:
            provider.calls.append(kwargs)
            return provider_result

        provider.customer_service_answer = invalid_usage  # type: ignore[method-assign]
        adapter = ResponsesListingVisionAdapter(provider)

        with self.assertRaises(ListingProposalError) as raised:
            await adapter.propose_listing_content(
                ListingVisionRequest(untrusted_media=(vision_input(),)),
                correlation_id="corr-invalid-usage",
            )

        self.assertEqual(ListingProposalErrorCode.REJECTED, raised.exception.code)

    async def test_deadline_and_cancellation_are_bounded_and_recorded(self) -> None:
        provider = FakeStructuredProvider()
        provider.delay = 0.05
        metrics = ListingContentProviderMetrics()
        adapter = ResponsesListingVisionAdapter(
            provider,
            limits=ListingContentProviderLimits(deadline_seconds=0.01),
            metrics=metrics,
        )
        with self.assertRaises(ListingProposalError) as raised:
            await adapter.propose_listing_content(
                ListingVisionRequest(untrusted_media=(vision_input(),)),
                correlation_id="corr-deadline",
            )
        self.assertEqual(ListingProposalErrorCode.TIMED_OUT, raised.exception.code)

        provider.delay = 1.0
        task = asyncio.create_task(
            adapter.propose_listing_content(
                ListingVisionRequest(untrusted_media=(vision_input(),)),
                correlation_id="corr-cancel",
            )
        )
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        text = generate_latest(metrics.registry).decode("utf-8")
        self.assertIn('result="timed_out"', text)
        self.assertIn('result="cancelled"', text)

    async def test_default_off_factory_stops_before_media_and_provider(self) -> None:
        media_tool = FakeMediaTool()
        provider = FakeStructuredProvider()
        audit = FakeAuditSink()
        orchestrator = build_responses_listing_content_proposal_orchestrator(
            media_tool=media_tool,
            provider=provider,
            audit_sink=audit,
        )

        with self.assertRaises(ListingProposalError) as raised:
            await orchestrator.propose(command())

        self.assertEqual(ListingProposalErrorCode.DISABLED, raised.exception.code)
        self.assertEqual([], media_tool.calls)
        self.assertEqual([], provider.calls)
        self.assertEqual("DISABLED", audit.events[-1].result)

    async def test_bound_orchestrator_replay_calls_provider_once(self) -> None:
        media_tool = FakeMediaTool()
        provider = FakeStructuredProvider()
        orchestrator = build_responses_listing_content_proposal_orchestrator(
            media_tool=media_tool,
            provider=provider,
            enabled=True,
        )

        first = await orchestrator.propose(command())
        second = await orchestrator.propose(command())

        self.assertEqual(first, second)
        self.assertEqual(1, len(media_tool.calls))
        self.assertEqual(1, len(provider.calls))

    async def test_cancellation_is_hashed_audited_and_same_request_can_retry(self) -> None:
        media_tool = FakeMediaTool()
        provider = FakeStructuredProvider()
        provider.delay = 1.0
        audit = FakeAuditSink()
        orchestrator = build_responses_listing_content_proposal_orchestrator(
            media_tool=media_tool,
            provider=provider,
            enabled=True,
            audit_sink=audit,
        )
        task = asyncio.create_task(orchestrator.propose(command()))
        await asyncio.sleep(0.01)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual("CANCELLED", audit.events[-1].result)
        audit_json = audit.events[-1].model_dump_json()
        self.assertNotIn(ACTOR_ID, audit_json)
        self.assertNotIn(LISTING_ID, audit_json)

        provider.delay = 0
        proposal = await orchestrator.propose(command())
        self.assertEqual("Wooden writing desk", proposal.suggested_title.value)
        self.assertEqual(2, len(provider.calls))

    async def test_bound_adapter_preserves_privacy_injection_and_unknown_rules(self) -> None:
        media_tool = FakeMediaTool()
        provider = FakeStructuredProvider()
        provider.value = candidate().model_copy(
            update={
                "suggested_description": SuggestedText(
                    value="Desk; email owner@example.com for details.",
                    confidence=0.7,
                    evidence_ids=("E1",),
                )
            }
        )
        orchestrator = build_responses_listing_content_proposal_orchestrator(
            media_tool=media_tool,
            provider=provider,
            enabled=True,
        )
        proposal = await orchestrator.propose(command())
        self.assertNotIn("owner@example.com", proposal.model_dump_json())
        self.assertIn(UnknownField.PRICE, proposal.unknown_fields)
        self.assertIn(UnknownField.CONDITION, proposal.unknown_fields)

        provider.value = ListingVisionCandidate(
            prompt_injection_detected=True,
            unknown_fields=tuple(UnknownField),
        )
        second = command().model_copy(
            update={"client_request_id": "01ARZ3NDEKTSV4RRFFQ69G5FAM"}
        )
        with self.assertRaises(ListingProposalError) as raised:
            await orchestrator.propose(second)
        self.assertEqual(ListingProposalErrorCode.REJECTED, raised.exception.code)

    async def test_bound_result_matches_offline_01a_proposal(self) -> None:
        baseline_media = FakeMediaTool()

        class BaselineVision:
            async def propose_listing_content(self, request: object, *, correlation_id: str) -> ListingVisionProviderResult:
                return ListingVisionProviderResult(candidate=candidate())

        baseline = ListingContentProposalOrchestrator(
            media_tool=baseline_media,
            vision_provider=BaselineVision(),
            enabled=True,
        )
        bound_media = FakeMediaTool()
        provider = FakeStructuredProvider()
        bound = build_responses_listing_content_proposal_orchestrator(
            media_tool=bound_media,
            provider=provider,
            enabled=True,
        )

        baseline_result = await baseline.propose(command())
        bound_result = await bound.propose(command())

        self.assertEqual(baseline_result, bound_result)
        self.assertTrue(bound_result.proposal_only)
        self.assertTrue(bound_result.requires_seller_confirmation)


if __name__ == "__main__":
    unittest.main()
