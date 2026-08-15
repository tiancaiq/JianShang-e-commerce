from __future__ import annotations

import asyncio
import unittest
import json
import time
from datetime import UTC, datetime
from typing import Any
from unittest.mock import patch

import httpx
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from msb_agent_service.api import create_app
from msb_agent_service.config import (
    AgentApiSettings,
    AgentPersistenceSettings,
    DiscoveryApiSettings,
    Settings,
)
from msb_agent_service.errors import LlmProviderError, ProviderErrorCode
from msb_agent_service.errors import ProviderFailureKind
from msb_agent_service.embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProviderError,
)
from msb_agent_service.marketplace_discovery import (
    CheckedListing,
    DiscoveryFailureStage,
    DiscoveryGraphFailureKind,
    DiscoveryToolFailureKind,
    DiscoveryLimits,
    DiscoveryPreferenceState,
    DiscoveryProviderFailureKind,
    DiscoverySearchCandidate,
    DiscoverySearchPage,
    DiscoverySearchRequest,
    DiscoveryStageError,
    MarketplaceDiscoveryOrchestrator,
    PublicIndividualListing,
    _PageMetadata,
    _discovery_graph_recursion_limit,
)
from msb_agent_service.marketplace_discovery_runtime import (
    MarketplaceDiscoveryRuntime,
    OpenAIDiscoveryChatModel,
    _safe_terminal_tool_call,
    build_marketplace_discovery_runtime,
)
from msb_agent_service.provider import (
    DiscoveryProviderResult,
    DiscoveryProviderToolCall,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
CATEGORY = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
LISTINGS = (
    "01ARZ3NDEKTSV4RRFFQ69G5FB1",
    "01ARZ3NDEKTSV4RRFFQ69G5FB2",
    "01ARZ3NDEKTSV4RRFFQ69G5FB3",
)
NOW = datetime(2026, 7, 21, 12, 0, tzinfo=UTC)


class FakeDiscoveryProvider:
    """Returns deterministic tool calls while recording the production boundary."""

    model_name = "offline-discovery-model"

    def __init__(self, *_: Any, **__: Any) -> None:
        self.calls: list[dict[str, Any]] = []
        self.closed = 0

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="search-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={"q": "sleep pillow", "city": "Irvine", "limit": 20},
                ),
            )
        elif 2 <= call_number <= 4:
            index = call_number - 1
            calls = (DiscoveryProviderToolCall(
                call_id=f"detail-{index}",
                name="GET_LISTING",
                arguments={"listing_id": LISTINGS[index - 1]},
            ),)
        elif call_number == 5:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="structured-1",
                    name="DiscoveryTurnResult",
                    arguments={
                        "outcome": "RECOMMEND",
                        "message": "These verified listings match your request.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Matches the requested product and city.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                ),
            )
        else:
            raise AssertionError("Unexpected provider call")
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=calls,
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )

    async def close(self) -> None:
        self.closed += 1


class DollarBudgetDiscoveryProvider(FakeDiscoveryProvider):
    """Omits currency on the first tool call to mirror model output for "$100"."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="search-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={"q": "desk chair", "maxPrice": 100, "limit": 20},
                ),
            )
        elif 2 <= call_number <= 4:
            index = call_number - 1
            calls = (DiscoveryProviderToolCall(
                call_id=f"detail-{index}",
                name="GET_LISTING",
                arguments={"listing_id": LISTINGS[index - 1]},
            ),)
        elif call_number == 5:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="structured-1",
                    name="DiscoveryTurnResult",
                    arguments={
                        "outcome": "RECOMMEND",
                        "message": "These verified listings match your request.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Fits the desk-chair budget.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                ),
            )
        else:
            raise AssertionError("Unexpected provider call")
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=calls,
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class MessageOnlyFinalDiscoveryProvider(FakeDiscoveryProvider):
    """Returns application-tool calls, then natural customer-facing final content."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        if len(self.calls) < 2:
            return await super().discovery_chat(**kwargs)
        self.calls.append(kwargs)
        return DiscoveryProviderResult(
            content="I verified one current option for your request.",
            tool_calls=(),
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class ExtraArgumentDiscoveryProvider(FakeDiscoveryProvider):
    """Returns a malformed search tool call before any dependency should run."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=(
                DiscoveryProviderToolCall(
                    call_id="search-invalid-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={
                        "q": "desk chair",
                        "limit": 20,
                        "unsupportedRadiusMiles": 5,
                    },
                ),
            ),
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class FakeProductTool:
    def __init__(self) -> None:
        self.search_calls = 0
        self.detail_calls = 0
        self.listings = {item: _listing(item, index) for index, item in enumerate(LISTINGS)}

    async def search_individual(self, **kwargs: Any) -> DiscoverySearchPage:
        self.search_calls += 1
        request = kwargs["request"]
        if not isinstance(request, DiscoverySearchRequest):
            raise AssertionError("Search arguments were not schema validated")
        return DiscoverySearchPage(
            data=tuple(
                DiscoverySearchCandidate(listingId=item)
                for item in self.listings
            ),
            page=_PageMetadata(nextCursor=None, hasMore=False),
        )

    async def get_listing(self, **kwargs: Any) -> CheckedListing | None:
        self.detail_calls += 1
        listing = self.listings.get(kwargs["listing_id"])
        if listing is None:
            return None
        return CheckedListing(
            listing=listing,
            checkedAt=NOW,
            responseHash="a" * 64,
        )


class FakeQueryEmbeddingProvider:
    def __init__(
        self,
        *_: Any,
        error: EmbeddingProviderError | None = None,
        delay_seconds: float = 0.0,
        delay_sequence: tuple[float, ...] = (),
        **__: Any,
    ) -> None:
        self.calls: list[tuple[tuple[str, ...], str | None]] = []
        self.closed = 0
        self.error = error
        self.delay_seconds = delay_seconds
        self.delay_sequence = delay_sequence

    async def embed(
        self,
        texts: list[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        self.calls.append((tuple(texts), correlation_id))
        delay = (
            self.delay_sequence[len(self.calls) - 1]
            if len(self.calls) <= len(self.delay_sequence)
            else self.delay_seconds
        )
        if delay:
            await asyncio.sleep(delay)
        if self.error is not None:
            raise self.error
        return EmbeddingBatchResult(
            vectors=(tuple(0.01 for _ in range(1536)),),
            provider="openai",
            model="text-embedding-3-small",
            dimensions=1536,
            input_tokens=5,
        )

    async def close(self) -> None:
        self.closed += 1


class UnavailableDiscoveryProvider(FakeDiscoveryProvider):
    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        raise LlmProviderError(
            ProviderErrorCode.UNAVAILABLE,
            "Offline fake provider unavailable",
            retryable=True,
            status_code=503,
        )


class DelayedDiscoveryProvider(FakeDiscoveryProvider):
    def __init__(self, *, delay_seconds: float = 0.03) -> None:
        super().__init__()
        self.delay_seconds = delay_seconds

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        await asyncio.sleep(self.delay_seconds)
        return await super().discovery_chat(**kwargs)


class DelayedFirstDiscoveryProvider(FakeDiscoveryProvider):
    """Delays only the first model step so tests can exercise configured budgets."""

    def __init__(self, *, delay_seconds: float) -> None:
        super().__init__()
        self.delay_seconds = delay_seconds

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        if not self.calls:
            await asyncio.sleep(self.delay_seconds)
        return await super().discovery_chat(**kwargs)


class HangingDiscoveryProvider(FakeDiscoveryProvider):
    def __init__(self) -> None:
        super().__init__()
        self.cancelled = False
        self.completed = False

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        self.completed = True
        raise AssertionError("Hanging provider unexpectedly resumed")


class CancellingDiscoveryProvider(FakeDiscoveryProvider):
    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        raise asyncio.CancelledError()


class StrictHybridReActProvider(FakeDiscoveryProvider):
    """Validates the real second-call function output sequence before finalizing."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            return DiscoveryProviderResult(
                content="private intermediate text that must be discarded",
                tool_calls=(
                    DiscoveryProviderToolCall(
                        call_id="search-1",
                        name="SEARCH_INDIVIDUAL",
                        arguments={"q": "desk chair", "maxPrice": 100, "limit": 20},
                    ),
                ),
                input_tokens=10,
                output_tokens=5,
                latency_ms=1,
            )
        if call_number == 2:
            items = kwargs["input_items"]
            function_calls = [
                item for item in items if item.get("type") == "function_call"
            ]
            function_outputs = [
                item
                for item in items
                if item.get("type") == "function_call_output"
            ]
            if len(function_calls) != 1 or len(function_outputs) != 1:
                raise AssertionError("Expected one search call and one tool result")
            function_call = function_calls[0]
            function_output = function_outputs[0]
            self._assert_search_call(function_call)
            if function_output.get("call_id") != "search-1":
                raise AssertionError("Tool output did not preserve tool_call_id")
            output = json.loads(str(function_output.get("output")))
            if output.get("count") != 3 or len(output.get("listingIds", [])) != 3:
                raise AssertionError("Search tool output was not forwarded safely")
            return DiscoveryProviderResult(
                content="private intermediate text that must be discarded",
                tool_calls=(DiscoveryProviderToolCall(
                    call_id="detail-1",
                    name="GET_LISTING",
                    arguments={"listing_id": LISTINGS[0]},
                ),),
                input_tokens=10,
                output_tokens=5,
                latency_ms=1,
            )
        if call_number in {3, 4}:
            previous = call_number - 2
            items = kwargs["input_items"]
            if not any(
                item.get("type") == "function_call_output"
                and item.get("call_id") == f"detail-{previous}"
                for item in items
            ):
                raise AssertionError("Sequential detail output was not forwarded")
            return DiscoveryProviderResult(
                content="private intermediate text that must be discarded",
                tool_calls=(DiscoveryProviderToolCall(
                    call_id=f"detail-{previous + 1}",
                    name="GET_LISTING",
                    arguments={"listing_id": LISTINGS[previous]},
                ),),
                input_tokens=10,
                output_tokens=5,
                latency_ms=1,
            )
        if call_number == 5:
            items = kwargs["input_items"]
            detail_outputs = [
                item
                for item in items
                if item.get("type") == "function_call_output"
                and str(item.get("call_id", "")).startswith("detail-")
            ]
            if len(detail_outputs) != 3:
                raise AssertionError("Detail tool outputs were not forwarded")
            return DiscoveryProviderResult(
                content="private final prose that must be discarded",
                tool_calls=(
                    DiscoveryProviderToolCall(
                        call_id="structured-1",
                        name="DiscoveryTurnResult",
                        arguments={
                            "outcome": "RECOMMEND",
                            "message": "These verified listings match your request.",
                            "clarificationQuestions": [],
                            "observedAmbiguities": [],
                            "selections": [
                                {
                                    "listingId": listing_id,
                                    "matchReason": "Fits the desk-chair budget.",
                                }
                                for listing_id in LISTINGS
                            ],
                        },
                    ),
                ),
                input_tokens=10,
                output_tokens=5,
                latency_ms=1,
            )
        raise AssertionError("Unexpected provider call")

    def _assert_search_call(self, item: dict[str, object]) -> None:
        if item.get("call_id") != "search-1":
            raise AssertionError("Search function_call lost call_id")
        if item.get("name") != "SEARCH_INDIVIDUAL":
            raise AssertionError("Search function_call lost name")
        arguments = item.get("arguments")
        if not isinstance(arguments, str):
            raise AssertionError("Search function_call arguments must be JSON text")
        parsed = json.loads(arguments)
        if parsed.get("q") != "desk chair":
            raise AssertionError("Search function_call arguments changed")


class MaximumRoundDiscoveryProvider(FakeDiscoveryProvider):
    """Uses the highest approved number of sequential tool rounds for one turn."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="search-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={"q": "desk chair", "maxPrice": 100, "limit": 20},
                ),
            )
        elif 2 <= call_number <= 4:
            listing_id = LISTINGS[call_number - 2]
            calls = (
                DiscoveryProviderToolCall(
                    call_id=f"detail-{call_number - 1}",
                    name="GET_LISTING",
                    arguments={"listing_id": listing_id},
                ),
            )
        elif call_number == 5:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="structured-1",
                    name="DiscoveryTurnResult",
                    arguments={
                        "outcome": "RECOMMEND",
                        "message": "These verified listings match your request.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Fits the desk-chair budget.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                ),
            )
        else:
            raise AssertionError("Unexpected provider call")
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=calls,
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class OneExtraRoundDiscoveryProvider(MaximumRoundDiscoveryProvider):
    """Attempts one more model/tool loop after the approved model-call ceiling."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        if len(self.calls) < 4:
            return await super().discovery_chat(**kwargs)
        self.calls.append(kwargs)
        return DiscoveryProviderResult(
            content="private loop text that must be discarded",
            tool_calls=(
                DiscoveryProviderToolCall(
                    call_id="detail-extra",
                    name="GET_LISTING",
                    arguments={"listing_id": LISTINGS[0]},
                ),
            ),
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class SixSequentialToolRoundDiscoveryProvider(FakeDiscoveryProvider):
    """Uses SEARCH plus five sequential GET_LISTING rounds before finalizing."""

    detail_sequence = (
        LISTINGS[0],
        LISTINGS[1],
        LISTINGS[2],
        LISTINGS[0],
        LISTINGS[1],
    )

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="search-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={"q": "desk chair", "maxPrice": 100, "limit": 20},
                ),
            )
        elif 2 <= call_number <= 6:
            calls = (
                DiscoveryProviderToolCall(
                    call_id=f"detail-{call_number - 1}",
                    name="GET_LISTING",
                    arguments={
                        "listing_id": self.detail_sequence[call_number - 2],
                    },
                ),
            )
        elif call_number == 7:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="structured-1",
                    name="DiscoveryTurnResult",
                    arguments={
                        "outcome": "RECOMMEND",
                        "message": "These verified listings match your request.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Fits the desk-chair budget.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                ),
            )
        else:
            raise AssertionError("Unexpected provider call")
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=calls,
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class SevenToolRoundDiscoveryProvider(SixSequentialToolRoundDiscoveryProvider):
    """Attempts a seventh application tool call after six allowed tool rounds."""

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        if len(self.calls) < 6:
            return await super().discovery_chat(**kwargs)
        self.calls.append(kwargs)
        return DiscoveryProviderResult(
            content="private extra loop text that must be discarded",
            tool_calls=(
                DiscoveryProviderToolCall(
                    call_id="detail-extra",
                    name="GET_LISTING",
                    arguments={"listing_id": LISTINGS[2]},
                ),
            ),
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )


class ProviderErrorDiscoveryProvider(FakeDiscoveryProvider):
    def __init__(
        self,
        code: ProviderErrorCode,
        failure_kind: ProviderFailureKind,
    ) -> None:
        super().__init__()
        self.code = code
        self.failure_kind = failure_kind

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        raise LlmProviderError(
            self.code,
            "SECRET provider failure must not leak",
            retryable=True,
            status_code=503,
            failure_kind=self.failure_kind,
        )


class MalformedResultDiscoveryProvider(FakeDiscoveryProvider):
    def __init__(self, result: DiscoveryProviderResult) -> None:
        super().__init__()
        self.result = result

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        return self.result


class FakePersistenceRepository:
    def __init__(self) -> None:
        self.pool = object()
        self.validated = 0
        self.closed = 0

    async def validate_schema(self) -> None:
        self.validated += 1

    async def close(self) -> None:
        self.closed += 1


class FakeDiscoveryRepository:
    def __init__(self, repository: FakePersistenceRepository) -> None:
        self.conversation_repository = repository
        self.validated = 0

    async def validate_schema(self) -> None:
        self.validated += 1


class DeferredAnswerer:
    async def answer(self, **_: Any) -> Any:
        raise AssertionError("Customer-service answerer must not run at startup")


def _request_scoped_model(
    provider: FakeDiscoveryProvider,
    *,
    provider_timeout_seconds: float = 1.0,
) -> OpenAIDiscoveryChatModel:
    return OpenAIDiscoveryChatModel(
        provider=provider,
        provider_model_name=provider.model_name,
        provider_timeout_seconds=provider_timeout_seconds,
    ).model_copy(
        update={
            "correlation_id": "disc-runtime-stage",
            "bound_tools": (
                {
                    "type": "function",
                    "name": "DiscoveryTurnResult",
                    "parameters": {"type": "object"},
                    "strict": True,
                },
            ),
        }
    )


def _hybrid_response_payload() -> dict[str, object]:
    return {
        "schemaVersion": "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1",
        "data": [
            {
                "listingId": listing_id,
                "listingVersion": index - 1,
                "title": f"Desk chair {index}",
                "categoryId": CATEGORY,
                "categorySlug": "office-chairs",
                "categoryName": "Office chairs",
                "condition": "GOOD",
                "priceAmount": 85,
                "currency": "USD",
                "publicCity": "Irvine",
                "publicRegion": "Orange County",
                "available": True,
                "primaryImageUrl": None,
                "publishedAt": NOW.isoformat(),
                "transactionNotice": "Payment and delivery are arranged directly.",
                "provenance": {
                    "finalRank": index,
                    "mode": "HYBRID",
                    "matchedBy": ["LEXICAL", "VECTOR"],
                    "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
                },
            }
            for index, listing_id in enumerate(LISTINGS, 1)
        ],
        "meta": {
            "retrievalMode": "HYBRID",
            "degraded": False,
            "checkedAt": NOW.isoformat(),
        },
    }


class MarketplaceDiscoveryRuntimeTest(unittest.IsolatedAsyncioTestCase):
    def _provider_messages(self) -> list[SystemMessage | HumanMessage]:
        return [
            SystemMessage(content="Use only allowlisted discovery tools."),
            HumanMessage(content="Find a desk chair"),
        ]

    def test_message_only_completion_uses_trusted_zero_result_fallback(self) -> None:
        call = _safe_terminal_tool_call(
            [
                ToolMessage(
                    content='{"count":0,"listingIds":[]}',
                    tool_call_id="search-zero-1",
                    name="SEARCH_INDIVIDUAL",
                )
            ]
        )

        self.assertEqual("DiscoveryTurnResult", call["name"])
        self.assertEqual("NO_RESULTS", call["args"]["outcome"])
        self.assertNotIn("provider", call["args"]["message"].casefold())

    def test_message_only_completion_uses_revalidated_candidate_facts(self) -> None:
        call = _safe_terminal_tool_call(
            [
                ToolMessage(
                    content=json.dumps(
                        {
                            "listingId": LISTINGS[0],
                            "eligible": True,
                            "title": "SECRET provider prose must not be logged",
                        }
                    ),
                    tool_call_id="detail-safe-1",
                    name="GET_LISTING",
                )
            ]
        )

        self.assertEqual("RECOMMEND", call["args"]["outcome"])
        self.assertEqual(LISTINGS[0], call["args"]["selections"][0]["listingId"])

    def test_message_only_completion_without_authoritative_evidence_fails_stably(
        self,
    ) -> None:
        with self.assertRaises(DiscoveryStageError) as raised:
            _safe_terminal_tool_call(
                [
                    ToolMessage(
                        content='{"count":3,"listingIds":[]}',
                        tool_call_id="search-ambiguous-1",
                        name="SEARCH_INDIVIDUAL",
                    )
                ]
            )

        self.assertEqual(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            raised.exception.stage,
        )
        self.assertEqual(
            DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
            raised.exception.kind,
        )

    async def test_malformed_final_structure_recovers_from_zero_search_evidence(
        self,
    ) -> None:
        provider = MalformedResultDiscoveryProvider(
            DiscoveryProviderResult(
                content="private provider prose",
                tool_calls=(
                    DiscoveryProviderToolCall(
                        call_id="invalid-final-zero-1",
                        name="DiscoveryTurnResult",
                        arguments={"outcome": "RECOMMEND", "message": "invalid"},
                    ),
                ),
                input_tokens=1,
                output_tokens=1,
                latency_ms=1,
            )
        )
        model = _request_scoped_model(provider)

        result = await model._agenerate(
            self._provider_messages()
            + [
                ToolMessage(
                    content='{"count":0,"listingIds":[]}',
                    tool_call_id="search-zero-invalid-final-1",
                    name="SEARCH_INDIVIDUAL",
                )
            ]
        )

        call = result.generations[0].message.tool_calls[0]
        self.assertEqual("NO_RESULTS", call["args"]["outcome"])
        self.assertEqual([], call["args"]["selections"])

    async def test_malformed_final_structure_recovers_revalidated_candidates(
        self,
    ) -> None:
        provider = MalformedResultDiscoveryProvider(
            DiscoveryProviderResult(
                content="private provider prose",
                tool_calls=(
                    DiscoveryProviderToolCall(
                        call_id="invalid-final-candidates-1",
                        name="DiscoveryTurnResult",
                        arguments={"outcome": "RECOMMEND", "message": "invalid"},
                    ),
                ),
                input_tokens=1,
                output_tokens=1,
                latency_ms=1,
            )
        )
        model = _request_scoped_model(provider)

        result = await model._agenerate(
            self._provider_messages()
            + [
                ToolMessage(
                    content=json.dumps(
                        {"listingId": LISTINGS[0], "eligible": True}
                    ),
                    tool_call_id="detail-invalid-final-1",
                    name="GET_LISTING",
                )
            ]
        )

        call = result.generations[0].message.tool_calls[0]
        self.assertEqual("RECOMMEND", call["args"]["outcome"])
        self.assertEqual(LISTINGS[0], call["args"]["selections"][0]["listingId"])

    async def test_provider_timeout_stage_cancels_provider_without_product_call(
        self,
    ) -> None:
        provider = HangingDiscoveryProvider()
        model = _request_scoped_model(provider, provider_timeout_seconds=0.01)
        started = time.monotonic()

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())
        elapsed = time.monotonic() - started

        self.assertEqual(DiscoveryFailureStage.PROVIDER_TIMEOUT, raised.exception.stage)
        self.assertEqual(1, len(provider.calls))
        self.assertLess(elapsed, 0.5)
        self.assertEqual(0.01, provider.calls[0]["timeout_seconds"])
        self.assertTrue(provider.cancelled)
        await asyncio.sleep(0.01)
        self.assertFalse(provider.completed)

    async def test_whole_turn_deadline_clips_provider_wait(self) -> None:
        provider = HangingDiscoveryProvider()
        model = _request_scoped_model(provider, provider_timeout_seconds=1.0)
        model = model.with_turn_deadline(time.monotonic() + 0.01)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(DiscoveryFailureStage.GRAPH_TIMEOUT, raised.exception.stage)
        # Under a loaded full suite the deadline may expire before the provider
        # starts; otherwise the in-flight call must receive the clipped budget.
        self.assertLessEqual(len(provider.calls), 1)
        if provider.calls:
            self.assertLess(provider.calls[0]["timeout_seconds"], 1.0)
            self.assertTrue(provider.cancelled)

    async def test_provider_preflight_stage_is_bounded(self) -> None:
        provider = FakeDiscoveryProvider()
        model = OpenAIDiscoveryChatModel(
            provider=provider,
            provider_model_name=provider.model_name,
        )

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate([HumanMessage(content="Find a desk chair")])

        self.assertEqual(DiscoveryFailureStage.PROVIDER_PREFLIGHT, raised.exception.stage)
        self.assertEqual([], provider.calls)

    async def test_vague_product_first_step_exposes_only_availability_action(self) -> None:
        class AvailabilityActionProvider(FakeDiscoveryProvider):
            async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
                self.calls.append(kwargs)
                return DiscoveryProviderResult(
                    content="",
                    tool_calls=(DiscoveryProviderToolCall(
                        call_id="availability-chair-1",
                        name="CHECK_AVAILABILITY",
                        arguments={"category": "chair"},
                    ),),
                    input_tokens=8,
                    output_tokens=3,
                    latency_ms=1,
                )

        provider = AvailabilityActionProvider()
        model = OpenAIDiscoveryChatModel(
            provider=provider,
            provider_model_name=provider.model_name,
        ).model_copy(update={
            "correlation_id": "disc-required-availability",
            "bound_tools": (
                {
                    "type": "function",
                    "name": "CHECK_AVAILABILITY",
                    "parameters": {"type": "object"},
                    "strict": True,
                },
                {
                    "type": "function",
                    "name": "DiscoveryTurnResult",
                    "parameters": {"type": "object"},
                    "strict": True,
                },
            ),
        })

        result = await model._agenerate(self._provider_messages())

        self.assertEqual(
            ["CHECK_AVAILABILITY", "DiscoveryTurnResult"],
            [item["name"] for item in provider.calls[0]["tools"]],
        )
        self.assertEqual(
            "CHECK_AVAILABILITY",
            result.generations[0].message.tool_calls[0]["name"],
        )

    async def test_provider_cancel_stage_is_bounded(self) -> None:
        provider = CancellingDiscoveryProvider()
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(DiscoveryFailureStage.PROVIDER_CANCEL, raised.exception.stage)
        self.assertEqual(
            DiscoveryProviderFailureKind.CANCEL,
            raised.exception.kind,
        )
        self.assertEqual(1, len(provider.calls))

    async def test_provider_status_failure_kind_is_bounded(self) -> None:
        provider = ProviderErrorDiscoveryProvider(
            ProviderErrorCode.UNAVAILABLE,
            ProviderFailureKind.STATUS,
        )
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(
            DiscoveryFailureStage.PROVIDER_REQUEST_START,
            raised.exception.stage,
        )
        self.assertEqual(DiscoveryProviderFailureKind.STATUS, raised.exception.kind)
        self.assertNotIn("SECRET", str(raised.exception))

    async def test_provider_transport_failure_kind_is_bounded(self) -> None:
        provider = ProviderErrorDiscoveryProvider(
            ProviderErrorCode.UNAVAILABLE,
            ProviderFailureKind.TRANSPORT,
        )
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(
            DiscoveryFailureStage.PROVIDER_REQUEST_START,
            raised.exception.stage,
        )
        self.assertEqual(DiscoveryProviderFailureKind.TRANSPORT, raised.exception.kind)
        self.assertNotIn("SECRET", str(raised.exception))

    async def test_provider_invalid_response_kind_is_bounded(self) -> None:
        provider = ProviderErrorDiscoveryProvider(
            ProviderErrorCode.INVALID_RESPONSE,
            ProviderFailureKind.RESPONSE_SCHEMA,
        )
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            raised.exception.stage,
        )
        self.assertEqual(
            DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
            raised.exception.kind,
        )
        self.assertNotIn("SECRET", str(raised.exception))

    async def test_message_serialization_failure_kind_is_bounded(self) -> None:
        provider = FakeDiscoveryProvider()
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(
                [
                    SystemMessage(content="Use tools."),
                    HumanMessage(
                        content=[
                            {
                                "type": "image",
                                "url": "SECRET-image-url",
                            }
                        ]
                    ),
                ]
            )

        self.assertEqual(DiscoveryFailureStage.PROVIDER_PREFLIGHT, raised.exception.stage)
        self.assertEqual(
            DiscoveryProviderFailureKind.MESSAGE_SERIALIZATION,
            raised.exception.kind,
        )
        self.assertEqual([], provider.calls)
        self.assertNotIn("SECRET", str(raised.exception))

    async def test_tool_message_conversion_failure_kind_is_bounded(self) -> None:
        provider = FakeDiscoveryProvider()
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(
                [
                    SystemMessage(content="Use tools."),
                    HumanMessage(content="Find a chair"),
                    ToolMessage(
                        content=[
                            {
                                "type": "image",
                                "url": "SECRET-tool-output",
                            }
                        ],
                        tool_call_id="search-1",
                        name="SEARCH_INDIVIDUAL",
                    ),
                ]
            )

        self.assertEqual(DiscoveryFailureStage.PROVIDER_PREFLIGHT, raised.exception.stage)
        self.assertEqual(
            DiscoveryProviderFailureKind.TOOL_MESSAGE_CONVERSION,
            raised.exception.kind,
        )
        self.assertEqual([], provider.calls)
        self.assertNotIn("SECRET", str(raised.exception))

    async def test_provider_tool_call_parse_failure_kind_is_bounded(self) -> None:
        provider = MalformedResultDiscoveryProvider(
            DiscoveryProviderResult(
                content="safe",
                tool_calls=(object(),),  # type: ignore[arg-type]
                input_tokens=1,
                output_tokens=1,
                latency_ms=1,
            )
        )
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            raised.exception.stage,
        )
        self.assertEqual(
            DiscoveryProviderFailureKind.TOOL_CALL_PARSING,
            raised.exception.kind,
        )

    async def test_provider_response_content_failure_kind_is_bounded(self) -> None:
        provider = MalformedResultDiscoveryProvider(
            DiscoveryProviderResult(
                content="",  # type: ignore[arg-type]
                tool_calls=(),
                input_tokens=1,
                output_tokens=1,
                latency_ms=1,
            )
        )
        model = _request_scoped_model(provider)

        with self.assertRaises(DiscoveryStageError) as raised:
            await model._agenerate(self._provider_messages())

        self.assertEqual(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            raised.exception.stage,
        )
        self.assertEqual(
            DiscoveryProviderFailureKind.RESPONSE_CONTENT,
            raised.exception.kind,
        )

    async def test_strict_second_provider_call_consumes_tool_messages(self) -> None:
        provider = StrictHybridReActProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider()
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200, json=_hybrid_response_payload())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-strict-second",
            )
            await runtime.close()

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(3, len(run.response.recommendations))
        self.assertEqual(5, len(provider.calls))
        self.assertEqual([(("desk chair",), "disc-runtime-strict-second")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertEqual(3, product.detail_calls)

    async def test_production_composition_runs_langchain_with_fake_transports(self) -> None:
        provider = FakeDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=product,
        )

        run = await runtime.orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a sleep pillow in Irvine",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-runtime-1",
        )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(3, len(run.response.recommendations))
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)
        self.assertNotIn(
            "private intermediate text",
            run.response.model_dump_json(by_alias=True),
        )
        self.assertTrue(all(call["correlation_id"] == "disc-runtime-1" for call in provider.calls))
        self.assertTrue(all(call["maximum_output_tokens"] == 800 for call in provider.calls))
        self.assertTrue(all(call["maximum_tool_calls"] == 1 for call in provider.calls))
        self.assertTrue(all(call["timeout_seconds"] == 8.0 for call in provider.calls))
        tool_names = {item["name"] for item in provider.calls[0]["tools"]}
        self.assertEqual(
            {
                "CHECK_AVAILABILITY",
                "SEARCH_INDIVIDUAL",
                "GET_LISTING",
            },
            tool_names,
        )
        await runtime.close()
        self.assertEqual(1, provider.closed)

    async def test_message_only_final_step_composes_guarded_verified_matches(self) -> None:
        provider = MessageOnlyFinalDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=product,
        )

        run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a sleep pillow in Irvine",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-message-only-final",
        )

        self.assertEqual("DETAIL", run.response.outcome)
        self.assertEqual(1, len(run.response.recommendations))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(1, product.detail_calls)
        serialized = run.response.model_dump_json(by_alias=True)
        self.assertIn("I verified one current option", serialized)

    async def test_production_model_call_budget_is_discovery_specific(self) -> None:
        provider = FakeDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(
                openai_timeout_seconds=30.0,
                discovery_model_call_timeout_seconds=10.0,
            ),
            provider=provider,
            product=product,
        )

        model = runtime.orchestrator._model
        limits = runtime.orchestrator._limits

        self.assertEqual(10.0, limits.provider_timeout_seconds)
        self.assertEqual(8.0, limits.query_embedding_timeout_seconds)
        self.assertEqual(10.0, model.provider_timeout_seconds)
        self.assertEqual(30.0, limits.whole_turn_timeout_seconds)
        self.assertEqual(0, len(provider.calls))
        await runtime.close()

    async def test_slow_first_model_call_within_configured_budget_completes(
        self,
    ) -> None:
        provider = DelayedFirstDiscoveryProvider(delay_seconds=0.11)
        product = FakeProductTool()
        model = OpenAIDiscoveryChatModel(
            provider=provider,
            provider_model_name=provider.model_name,
            provider_timeout_seconds=0.2,
        )
        orchestrator = MarketplaceDiscoveryOrchestrator(
            model,
            product,
            limits=DiscoveryLimits(
                provider_timeout_seconds=0.2,
                query_embedding_timeout_seconds=0.08,
                product_timeout_seconds=0.02,
                whole_turn_timeout_seconds=2.0,
            ),
        )

        run = await orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a sleep pillow in Irvine",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-runtime-model-call-budget",
        )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(0.2, provider.calls[0]["timeout_seconds"])
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_model_call_timeout_stops_before_tool_or_audit_work(self) -> None:
        provider = HangingDiscoveryProvider()
        product = FakeProductTool()
        model = OpenAIDiscoveryChatModel(
            provider=provider,
            provider_model_name=provider.model_name,
            provider_timeout_seconds=0.02,
        )
        orchestrator = MarketplaceDiscoveryOrchestrator(
            model,
            product,
            limits=DiscoveryLimits(
                provider_timeout_seconds=0.02,
                product_timeout_seconds=0.02,
                whole_turn_timeout_seconds=1.0,
            ),
        )

        with self.assertRaises(DiscoveryStageError) as raised:
            await orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a sleep pillow in Irvine",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-model-call-timeout",
            )

        self.assertEqual(DiscoveryFailureStage.PROVIDER_TIMEOUT, raised.exception.stage)
        self.assertEqual(DiscoveryProviderFailureKind.TIMEOUT, raised.exception.kind)
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(0.02, provider.calls[0]["timeout_seconds"])
        self.assertTrue(provider.cancelled)
        await asyncio.sleep(0.01)
        self.assertFalse(provider.completed)
        self.assertEqual(0, product.search_calls)
        self.assertEqual(0, product.detail_calls)

    async def test_multistep_react_flow_completes_under_new_relative_budget(
        self,
    ) -> None:
        provider = DelayedDiscoveryProvider(delay_seconds=0.06)
        product = FakeProductTool()
        model = OpenAIDiscoveryChatModel(
            provider=provider,
            provider_model_name=provider.model_name,
            provider_timeout_seconds=0.1,
        )
        orchestrator = MarketplaceDiscoveryOrchestrator(
            model,
            product,
            limits=DiscoveryLimits(
                provider_timeout_seconds=0.1,
                product_timeout_seconds=0.05,
                whole_turn_timeout_seconds=1.0,
            ),
        )

        run = await orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a sleep pillow in Irvine",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-runtime-scaled-budget",
        )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertGreater(provider.delay_seconds * 3, 0.1 * 1.5)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_runtime_passes_derived_graph_recursion_limit(self) -> None:
        limits = DiscoveryLimits(maximum_model_calls=5)
        self.assertEqual(41, _discovery_graph_recursion_limit(limits))

    async def test_maximum_allowed_tool_rounds_complete_before_recursion_limit(
        self,
    ) -> None:
        provider = MaximumRoundDiscoveryProvider()
        product = FakeProductTool()
        orchestrator = MarketplaceDiscoveryOrchestrator(
            _request_scoped_model(provider),
            product,
            limits=DiscoveryLimits(
                maximum_model_calls=5,
            ),
        )

        run = await orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a desk chair under $100.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-runtime-max-rounds",
        )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_more_than_five_decision_steps_terminates_safely(self) -> None:
        provider = SixSequentialToolRoundDiscoveryProvider()
        product = FakeProductTool()
        orchestrator = MarketplaceDiscoveryOrchestrator(
            _request_scoped_model(provider),
            product,
            limits=DiscoveryLimits(),
        )

        run = await orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-six-sequential-tools",
            )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertTrue(all(call["maximum_tool_calls"] == 1 for call in provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_seventh_tool_call_fails_at_tool_guard_without_recursion(self) -> None:
        provider = SevenToolRoundDiscoveryProvider()
        product = FakeProductTool()
        orchestrator = MarketplaceDiscoveryOrchestrator(
            _request_scoped_model(provider),
            product,
            limits=DiscoveryLimits(),
        )

        run = await orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-seventh-tool",
            )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_one_additional_loop_fails_at_model_round_limit(self) -> None:
        provider = OneExtraRoundDiscoveryProvider()
        product = FakeProductTool()
        orchestrator = MarketplaceDiscoveryOrchestrator(
            _request_scoped_model(provider),
            product,
            limits=DiscoveryLimits(
                maximum_model_calls=5,
            ),
        )

        run = await orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-one-extra-round",
            )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)

    async def test_hybrid_composition_calls_product_not_agent_opensearch(self) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider()
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(
                200,
                json={
                    "schemaVersion": "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1",
                    "data": [
                        {
                            "listingId": listing_id,
                            "listingVersion": index - 1,
                            "title": f"Comfort pillow {index}",
                            "categoryId": CATEGORY,
                            "categorySlug": "comfort",
                            "categoryName": "Comfort",
                            "condition": "NEW",
                            "priceAmount": 25,
                            "currency": "USD",
                            "publicCity": "Irvine",
                            "publicRegion": "Orange",
                            "available": True,
                            "primaryImageUrl": None,
                            "publishedAt": NOW.isoformat(),
                            "transactionNotice": (
                                "Payment and delivery are arranged directly."
                            ),
                            "provenance": {
                                "finalRank": index,
                                "mode": "HYBRID",
                                "matchedBy": ["LEXICAL", "VECTOR"],
                                "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
                            },
                        }
                        for index, listing_id in enumerate(LISTINGS, 1)
                    ],
                    "meta": {
                        "retrievalMode": "HYBRID",
                        "degraded": False,
                        "checkedAt": NOW.isoformat(),
                    },
                },
            )

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-hybrid-runtime-1",
            )
            await runtime.close()

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual([(("desk chair",), "disc-hybrid-runtime-1")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertEqual(
            "/api/v1/internal/agent/marketplace/listings/hybrid-search",
            product_requests[0].url.path,
        )
        request_body = json.loads(product_requests[0].content)
        self.assertEqual(100, request_body["filters"]["maxPrice"])
        self.assertEqual("USD", request_body["filters"]["currency"])
        self.assertEqual(3, product.detail_calls)
        source_refs = run.audits[0].source_refs
        self.assertEqual([1, 2, 3], [item["finalRank"] for item in source_refs])
        self.assertEqual([0, 1, 2], [item["listingVersion"] for item in source_refs])
        self.assertNotIn("embedding", json.dumps(source_refs).casefold())
        self.assertEqual(1, embedding.closed)

    async def test_specific_product_budget_searches_without_location_clarification(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider(delay_seconds=0.15)
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200, json=_hybrid_response_payload())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(
                    hybrid=True,
                    openai_timeout_seconds=0.3,
                    discovery_dependency_timeout_seconds=0.1,
                ),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-hybrid-split-budget",
            )
            await runtime.close()

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual([(("desk chair",), "disc-hybrid-split-budget")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertEqual(3, product.detail_calls)

    async def test_hybrid_query_embedding_timeout_stops_before_product_window(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider(delay_seconds=0.15)
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200, json=_hybrid_response_payload())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(
                    hybrid=True,
                    openai_timeout_seconds=0.1,
                    discovery_dependency_timeout_seconds=0.1,
                ),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            with self.assertRaises(DiscoveryStageError) as raised:
                await runtime.orchestrator.run(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    question="Find a desk chair under $100.",
                    preference_state=DiscoveryPreferenceState(),
                    clarification_turn_count=0,
                    clarification_question_count=0,
                    history=(),
                    correlation_id="disc-hybrid-query-timeout",
                )
            await runtime.close()

        self.assertEqual(DiscoveryFailureStage.TOOL_EXECUTION, raised.exception.stage)
        self.assertEqual(
            DiscoveryToolFailureKind.QUERY_EMBEDDING_TIMEOUT,
            raised.exception.kind,
        )
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(
            [
                (("desk chair",), "disc-hybrid-query-timeout"),
                (("desk chair",), "disc-hybrid-query-timeout"),
            ],
            embedding.calls,
        )
        self.assertEqual([], product_requests)

    async def test_hybrid_query_embedding_timeout_retries_once_then_searches(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider(delay_sequence=(0.15, 0.0))
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200, json=_hybrid_response_payload())

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(
                    hybrid=True,
                    openai_timeout_seconds=0.1,
                    discovery_dependency_timeout_seconds=0.1,
                ),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-hybrid-query-timeout-retry",
            )
            await runtime.close()

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(2, len(embedding.calls))
        self.assertEqual(1, len(product_requests))

    async def test_hybrid_product_timeout_starts_after_successful_embedding(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider(delay_seconds=0.02)
        product_requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            await asyncio.sleep(0.02)
            raise httpx.ReadTimeout("secret product timeout")

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(
                    hybrid=True,
                    openai_timeout_seconds=0.3,
                    discovery_dependency_timeout_seconds=0.1,
                ),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            with self.assertRaises(DiscoveryStageError) as raised:
                await runtime.orchestrator.run(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    question="Find a desk chair under $100.",
                    preference_state=DiscoveryPreferenceState(),
                    clarification_turn_count=0,
                    clarification_question_count=0,
                    history=(),
                    correlation_id="disc-hybrid-product-timeout",
                )
            await runtime.close()

        self.assertEqual(DiscoveryFailureStage.TOOL_EXECUTION, raised.exception.stage)
        self.assertEqual(
            DiscoveryToolFailureKind.PRODUCT_HYBRID_TIMEOUT,
            raised.exception.kind,
        )
        self.assertEqual(1, len(provider.calls))
        self.assertEqual([(("desk chair",), "disc-hybrid-product-timeout")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertNotIn("secret", str(raised.exception))

    async def test_hybrid_search_extra_tool_arguments_stop_before_dependencies(
        self,
    ) -> None:
        provider = ExtraArgumentDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider()
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200)

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a desk chair under $100.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-hybrid-invalid-args",
            )
            await runtime.close()

        self.assertEqual("ANSWER", run.response.outcome)
        self.assertEqual(5, len(provider.calls))
        self.assertEqual([], embedding.calls)
        self.assertEqual([], product_requests)
        self.assertEqual(0, product.search_calls)
        self.assertEqual(0, product.detail_calls)

    async def test_hybrid_product_http_failure_is_classified_after_embedding(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider()
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(503, content=b'{"secret":"hidden"}')

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            with self.assertRaises(DiscoveryStageError) as raised:
                await runtime.orchestrator.run(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    question="Find a desk chair under $100.",
                    preference_state=DiscoveryPreferenceState(),
                    clarification_turn_count=0,
                    clarification_question_count=0,
                    history=(),
                    correlation_id="disc-hybrid-product-503",
                )
            await runtime.close()

        self.assertEqual(DiscoveryFailureStage.TOOL_EXECUTION, raised.exception.stage)
        self.assertEqual(
            DiscoveryToolFailureKind.PRODUCT_HYBRID_HTTP,
            raised.exception.kind,
        )
        self.assertEqual(1, len(provider.calls))
        self.assertEqual([(("desk chair",), "disc-hybrid-product-503")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertNotIn("secret", str(raised.exception))

    async def test_hybrid_query_embedding_timeout_is_classified_before_product(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider(
            error=EmbeddingProviderError("OPENAI_TIMED_OUT", retryable=True)
        )
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(200)

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            with self.assertRaises(DiscoveryStageError) as raised:
                await runtime.orchestrator.run(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    question="Find a desk chair under $100.",
                    preference_state=DiscoveryPreferenceState(),
                    clarification_turn_count=0,
                    clarification_question_count=0,
                    history=(),
                    correlation_id="disc-hybrid-embedding-timeout",
                )
            await runtime.close()

        self.assertEqual(DiscoveryFailureStage.TOOL_EXECUTION, raised.exception.stage)
        self.assertEqual(
            DiscoveryToolFailureKind.QUERY_EMBEDDING_TIMEOUT,
            raised.exception.kind,
        )
        self.assertEqual([(("desk chair",), "disc-hybrid-embedding-timeout")], embedding.calls)
        self.assertEqual([], product_requests)

    async def test_resumed_failed_only_history_reaches_provider_and_hybrid_tools(
        self,
    ) -> None:
        provider = DollarBudgetDiscoveryProvider()
        product = FakeProductTool()
        embedding = FakeQueryEmbeddingProvider()
        product_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            product_requests.append(request)
            return httpx.Response(
                200,
                json={
                    "schemaVersion": "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1",
                    "data": [
                        {
                            "listingId": listing_id,
                            "listingVersion": 0,
                            "title": f"Desk chair {index}",
                            "categoryId": CATEGORY,
                            "categorySlug": "office-chairs",
                            "categoryName": "Office chairs",
                            "condition": "GOOD",
                            "priceAmount": 85,
                            "currency": "USD",
                            "publicCity": "Irvine",
                            "publicRegion": "Orange County",
                            "available": True,
                            "primaryImageUrl": None,
                            "publishedAt": NOW.isoformat(),
                            "transactionNotice": (
                                "Payment and delivery are arranged directly."
                            ),
                            "provenance": {
                                "finalRank": index,
                                "mode": "HYBRID",
                                "matchedBy": ["LEXICAL", "VECTOR"],
                                "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
                            },
                        }
                        for index, listing_id in enumerate(LISTINGS, 1)
                    ],
                    "meta": {
                        "retrievalMode": "HYBRID",
                        "degraded": False,
                        "checkedAt": NOW.isoformat(),
                    },
                },
            )

        history = (
            ("USER", "2313"),
            ("USER", "Find a lamp under $50."),
            ("USER", "Need a chair near Irvine."),
        )
        prompt = "Find a desk chair under $100."
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as product_http:
            runtime = build_marketplace_discovery_runtime(
                _fully_enabled_settings(hybrid=True),
                provider=provider,
                product=product,
                embedding_provider=embedding,
                product_http_client=product_http,
            )
            run = await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question=prompt,
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=history,
                correlation_id="disc-hybrid-resume-1",
            )
            await runtime.close()

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(3, len(run.response.recommendations))
        self.assertEqual(5, len(provider.calls))
        first_input = provider.calls[0]["input_items"][0]["content"]
        self.assertIn('"currentQuestion":"Find a desk chair under $100."', first_input)
        self.assertIn('"content":"2313"', first_input)
        self.assertIn('"content":"Find a lamp under $50."', first_input)
        self.assertIn('"content":"Need a chair near Irvine."', first_input)
        self.assertEqual([(("desk chair",), "disc-hybrid-resume-1")], embedding.calls)
        self.assertEqual(1, len(product_requests))
        self.assertEqual(3, product.detail_calls)

    async def test_provider_outage_fails_before_any_product_tool_call(self) -> None:
        provider = UnavailableDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=product,
        )

        with self.assertRaises(DiscoveryStageError) as raised:
            await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a sleep pillow in Irvine",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-outage-1",
            )

        self.assertEqual(
            DiscoveryFailureStage.PROVIDER_REQUEST_START,
            raised.exception.stage,
        )
        self.assertEqual(1, len(provider.calls))
        self.assertEqual(0, product.search_calls)
        self.assertEqual(0, product.detail_calls)
        await runtime.close()

    async def test_default_off_startup_never_builds_discovery_runtime(self) -> None:
        factory_calls = 0

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("Default-off startup must not compose Discovery")

        app = create_app(Settings(), discovery_runtime_factory=runtime_factory)
        async with app.router.lifespan_context(app):
            pass

        self.assertEqual(0, factory_calls)

    async def test_kill_switch_precedes_runtime_composition(self) -> None:
        persistence = FakePersistenceRepository()
        factory_calls = 0

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("The kill switch must stop runtime composition")

        settings = Settings(
            openai_api_key=None,
            agent_persistence=AgentPersistenceSettings(
                enabled=True,
                mysql_password="offline-database-placeholder",
            ),
            discovery_api=DiscoveryApiSettings(
                enabled=True,
                kill_switch_enabled=True,
                orchestration_enabled=True,
                product_tools_enabled=True,
                provider_enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
            ),
        )
        with patch(
            "msb_agent_service.api.DiscoveryPersistenceRepository",
            FakeDiscoveryRepository,
        ):
            app = create_app(
                settings,
                persistence_repository_factory=persistence_factory,
                discovery_runtime_factory=runtime_factory,
            )
            async with app.router.lifespan_context(app):
                pass

        self.assertEqual(0, factory_calls)
        self.assertEqual(1, persistence.closed)

    async def test_enabled_startup_composes_discovery_and_keeps_customer_service(self) -> None:
        persistence = FakePersistenceRepository()
        provider = FakeDiscoveryProvider()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=FakeProductTool(),
        )
        factory_calls = 0

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            return runtime

        settings = _fully_enabled_settings(customer_service=True)
        with patch(
            "msb_agent_service.api.DiscoveryPersistenceRepository",
            FakeDiscoveryRepository,
        ):
            app = create_app(
                settings,
                persistence_repository_factory=persistence_factory,
                question_answerer=DeferredAnswerer(),
                discovery_runtime_factory=runtime_factory,
            )
            async with app.router.lifespan_context(app):
                self.assertEqual(1, factory_calls)
                self.assertEqual(0, len(provider.calls))

        self.assertEqual(1, provider.closed)
        self.assertEqual(1, persistence.closed)

    async def test_enabled_hybrid_startup_uses_real_factory_without_agent_opensearch(
        self,
    ) -> None:
        persistence = FakePersistenceRepository()
        provider = FakeDiscoveryProvider()
        embedding = FakeQueryEmbeddingProvider()

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        settings = _fully_enabled_settings(customer_service=True, hybrid=True)
        with (
            patch(
                "msb_agent_service.api.DiscoveryPersistenceRepository",
                FakeDiscoveryRepository,
            ),
            patch(
                "msb_agent_service.api.create_open_search_client",
                side_effect=AssertionError(
                    "Discovery startup must not construct an Agent OpenSearch client"
                ),
            ),
            patch(
                "msb_agent_service.marketplace_discovery_runtime.OpenAIProvider",
                return_value=provider,
            ),
            patch(
                "msb_agent_service.marketplace_discovery_runtime.OpenAIEmbeddingProvider",
                return_value=embedding,
            ),
        ):
            app = create_app(
                settings,
                persistence_repository_factory=persistence_factory,
                question_answerer=DeferredAnswerer(),
            )
            async with app.router.lifespan_context(app):
                transport = httpx.ASGITransport(app=app)
                async with httpx.AsyncClient(
                    transport=transport,
                    base_url="http://test",
                ) as client:
                    response = await client.get("/ready")

        self.assertEqual(200, response.status_code)
        readiness = response.json()
        self.assertEqual("READY", readiness["status"])
        self.assertEqual("READY", readiness["marketplaceDiscoveryApi"])
        self.assertEqual("READY", readiness["customerServiceApi"])
        self.assertEqual(0, len(provider.calls))
        self.assertEqual([], embedding.calls)
        self.assertEqual(1, provider.closed)
        self.assertEqual(1, embedding.closed)
        self.assertEqual(1, persistence.closed)

    def test_generation_configuration_fails_closed_without_provider_config(self) -> None:
        settings = _fully_enabled_settings()
        settings = Settings(
            openai_api_key=None,
            agent_persistence=settings.agent_persistence,
            discovery_api=settings.discovery_api,
        )

        with self.assertRaisesRegex(RuntimeError, "configured provider"):
            build_marketplace_discovery_runtime(settings)


def _fully_enabled_settings(
    *,
    customer_service: bool = False,
    hybrid: bool = False,
    openai_timeout_seconds: float = 8.0,
    discovery_dependency_timeout_seconds: float = 2.0,
    discovery_model_call_timeout_seconds: float = 10.0,
) -> Settings:
    return Settings(
        openai_api_key="offline-test-placeholder",
        openai_timeout_seconds=openai_timeout_seconds,
        agent_persistence=AgentPersistenceSettings(
            enabled=True,
            mysql_password="offline-database-placeholder",
        ),
        agent_api=(
            AgentApiSettings(
                enabled=True,
                orchestration_enabled=True,
                retrieval_enabled=True,
                provider_enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
                product_service_token="offline-service-placeholder",
            )
            if customer_service
            else AgentApiSettings()
        ),
        discovery_api=DiscoveryApiSettings(
            enabled=True,
            orchestration_enabled=True,
            product_tools_enabled=True,
            hybrid_retrieval_enabled=hybrid,
            query_embedding_enabled=hybrid,
            provider_enabled=True,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
            product_service_token=(
                "offline-product-token"
                if hybrid
                else None
            ),
            dependency_timeout_seconds=discovery_dependency_timeout_seconds,
            model_call_timeout_seconds=discovery_model_call_timeout_seconds,
        ),
    )


def _listing(listing_id: str, index: int) -> PublicIndividualListing:
    return PublicIndividualListing.model_validate(
        {
            "id": listing_id,
            "sellerType": "INDIVIDUAL",
            "sellerDisplayName": "Marketplace seller",
            "sellerAvatarUrl": None,
            "storeId": None,
            "storeSlug": None,
            "storeName": None,
            "businessVerified": False,
            "categoryId": CATEGORY,
            "categorySlug": "comfort",
            "categoryName": "Comfort",
            "title": f"Comfort pillow {index + 1}",
            "description": (
                "A public ordinary comfort listing with desk chair, bicycle, "
                "and beginner camera fixture terms."
            ),
            "condition": "NEW",
            "conditionNotes": None,
            "priceAmount": "25.00",
            "currency": "USD",
            "negotiable": False,
            "quantity": 1,
            "publicCity": "Irvine",
            "publicRegion": "Orange",
            "publishedAt": NOW.isoformat(),
            "transactionNotice": "Payment and delivery are arranged directly.",
            "visitCount": 0,
            "likeCount": 0,
            "images": [],
        }
    )


if __name__ == "__main__":
    unittest.main()
