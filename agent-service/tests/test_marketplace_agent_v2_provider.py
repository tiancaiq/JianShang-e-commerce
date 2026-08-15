from __future__ import annotations

import asyncio
import unittest
from types import SimpleNamespace
from typing import Any

from msb_agent_service.config import Settings
from msb_agent_service.marketplace_agent_v2.provider import OpenAIMarketplaceAgentV2Model
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext,
    MarketplaceAgentV2ContextualRefinement,
    ToolFacets,
    ToolObservation,
)


class _Responses:
    def __init__(self, *, events: list[object], output: list[object], text: str) -> None:
        self.events = events
        self.output = output
        self.text = text
        self.calls: list[dict[str, object]] = []
        self.closed = False

    def stream(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        owner = self

        class Stream:
            def __aiter__(self) -> object:
                return self

            async def __anext__(self) -> object:
                if not owner.events:
                    raise StopAsyncIteration
                return owner.events.pop(0)

            async def get_final_response(self) -> object:
                return SimpleNamespace(
                    status="completed", output=owner.output,
                    output_text=owner.text, usage=None,
                )

        class Manager:
            async def __aenter__(self) -> object:
                return Stream()

            async def __aexit__(self, *_: object) -> None:
                owner.closed = True

        return Manager()


class _Client:
    def __init__(self, responses: _Responses) -> None:
        self.responses = responses


TOOLS = (
    {"type": "function", "name": "check_availability", "strict": True, "parameters": {}},
    {"type": "function", "name": "search_listings", "strict": True, "parameters": {}},
    {"type": "function", "name": "get_listing", "strict": True, "parameters": {}},
    {"type": "function", "name": "request_confirmation", "strict": True, "parameters": {}},
    {"type": "function", "name": "collect_listing_information", "strict": True, "parameters": {}},
)


def _facet_observation() -> ToolObservation:
    return ToolObservation(
        tool="search_listings",
        status="SUCCEEDED",
        reason="RESULTS_AVAILABLE",
        normalizedQuery="chair",
        totalMatches=20,
        presentationHint="RESULTS_WITH_REFINEMENT",
        facets=ToolFacets(
            subtype=(
                {"value": "Dining Chair", "count": 12},
                {"value": "Gaming Chair", "count": 8},
            ),
        ),
    )


class MarketplaceAgentV2ProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_contextual_refinement_is_serialized_as_a_required_search_context(self) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="search_listings",
            call_id="general-refinement",
            arguments='{"query":"key organizer General","limit":5}',
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call],
            text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="General",
                contextualRefinement=MarketplaceAgentV2ContextualRefinement(
                    facet="SUBTYPE", value="General", activeQuery="key organizer",
                    searchQuery="key organizer General",
                ),
            ),
            tools=TOOLS,
            correlation_id="v2-contextual-refinement-provider",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("search_listings", decision.tool_proposal.tool)
        provider_input = responses.calls[0]["input"][0]["content"]
        self.assertIn(
            '"contextualRefinement":{"facet":"SUBTYPE","value":"General",'
            '"activeQuery":"key organizer","searchQuery":"key organizer General"}',
            provider_input,
        )
        self.assertIn("When contextualRefinement is supplied", responses.calls[0]["instructions"])

    async def test_results_first_text_streams_without_waiting_for_a_refinement(self) -> None:
        content = "I found several current chair listings to start with."
        responses = _Responses(
            events=[
                SimpleNamespace(type="response.output_text.delta", delta=content),
                SimpleNamespace(type="response.completed"),
            ],
            output=[], text=content,
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        deltas: list[str] = []

        async def on_delta(value: str) -> None:
            deltas.append(value)

        decision = await model.decide(
            context=AgentContext(
                currentMessage="chair",
                observations=(_facet_observation(),),
            ),
            tools=TOOLS,
            correlation_id="v2-grounded-facets",
            on_text_delta=on_delta,
            timeout_seconds=5,
        )

        self.assertEqual(content, decision.content)
        self.assertEqual([content], deltas)

    async def test_results_first_text_is_not_buffered_into_a_whole_response(self) -> None:
        first = "I found several "
        second = "current chair listings."
        content = first + second
        responses = _Responses(
            events=[
                SimpleNamespace(type="response.output_text.delta", delta=first),
                SimpleNamespace(type="response.output_text.delta", delta=second),
                SimpleNamespace(type="response.completed"),
            ],
            output=[], text=content,
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        deltas: list[str] = []

        async def on_delta(value: str) -> None:
            deltas.append(value)

        decision = await model.decide(
            context=AgentContext(
                currentMessage="chair",
                observations=(_facet_observation(),),
            ),
            tools=TOOLS,
            correlation_id="v2-results-first-stream",
            on_text_delta=on_delta,
            timeout_seconds=5,
        )

        self.assertEqual(content, decision.content)
        self.assertEqual([first, second], deltas)

    async def test_post_search_terminal_decision_omits_the_tool_registry(self) -> None:
        content = "I found two current listings matching your filters."
        responses = _Responses(
            events=[
                SimpleNamespace(type="response.output_text.delta", delta=content),
                SimpleNamespace(type="response.completed"),
            ],
            output=[], text=content,
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="under 50",
                observations=(_facet_observation(),),
            ),
            tools=(),
            correlation_id="v2-post-search-terminal-only",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual(content, decision.content)
        self.assertNotIn("tools", responses.calls[0])
        self.assertNotIn("tool_choice", responses.calls[0])
        self.assertNotIn("max_tool_calls", responses.calls[0])

    async def test_terminal_text_streams_while_private_reasoning_is_suppressed(self) -> None:
        responses = _Responses(
            events=[
                SimpleNamespace(type="response.reasoning_text.delta", delta="private"),
                SimpleNamespace(type="response.output_text.delta", delta="Hello "),
                SimpleNamespace(type="response.output_text.delta", delta="there."),
                SimpleNamespace(type="response.completed"),
            ],
            output=[],
            text="Hello there.",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        deltas: list[str] = []

        async def on_delta(delta: str) -> None:
            deltas.append(delta)

        decision = await model.decide(
            context=AgentContext(
                currentMessage="hi",
                scopeResult={
                    "scope": "CONVERSATIONAL",
                    "confidence": "HIGH",
                    "marketplaceContextUsed": False,
                    "reasonCode": "NATURAL_CONVERSATION",
                },
            ),
            tools=TOOLS,
            correlation_id="v2-provider-direct",
            on_text_delta=on_delta,
            timeout_seconds=5,
        )

        self.assertEqual("Hello there.", decision.content)
        self.assertEqual(["Hello ", "there."], deltas)
        self.assertNotIn("private", "".join(deltas))
        self.assertEqual("auto", responses.calls[0]["tool_choice"])
        self.assertEqual(1, responses.calls[0]["max_tool_calls"])
        self.assertFalse(responses.calls[0]["store"])
        instructions = str(responses.calls[0]["instructions"])
        self.assertIn('"office" after "chair" means "office chair"', instructions)
        self.assertIn("never repeat identical arguments", instructions)
        self.assertIn('For an explicit "check again" refresh', instructions)
        self.assertIn("Do not offer to create alerts or notifications", instructions)
        self.assertIn("may call search_listings immediately", instructions)
        self.assertIn('"laptop", "phone", "desk", "bicycle"', instructions)
        self.assertIn('ambiguous concept such as "apple"', instructions)
        self.assertIn("validated top listings first", instructions)
        self.assertIn("write one short declarative introduction with no question", instructions)
        self.assertIn("Product-grounded structured action area", instructions)
        self.assertIn("it never adds prose", instructions)
        self.assertIn("do not enumerate or repeat listing titles", instructions)
        self.assertIn("resultCount, exactMatchCount, and relatedMatchCount", instructions)
        self.assertIn("Never copy a listing identifier into answer text", instructions)
        self.assertIn("Candidate retrieval counts are not category inventory", instructions)
        self.assertIn("A CONSUMED confirmation is historical", instructions)
        self.assertIn("Never infer quality, reliability, longevity", instructions)
        self.assertIn(
            "Do not use speculative words such as likely, implies, suggests, possibly, may, might, or could",
            instructions,
        )
        self.assertIn("closest matches come first", instructions)
        self.assertIn('"gaming" refines the active chair search', instructions)
        self.assertIn('"Show current results for the X subtype."', instructions)
        self.assertIn("propose search_listings immediately", instructions)
        self.assertIn("Never ask the customer to answer yes/no", instructions)
        self.assertIn("It cannot open a photo gallery", instructions)
        self.assertIn("Do not offer unavailable actions as choices", instructions)
        self.assertIn("After any REJECTED or FAILED tool observation", instructions)
        self.assertIn("scopeResult is an application-owned marketplace boundary", instructions)
        self.assertIn("For CONVERSATIONAL messages, answer naturally without any tool", instructions)
        self.assertIn("Never reinterpret an unrelated request as a product query", instructions)
        self.assertNotIn("use check_availability before asking", instructions)
        provider_input = responses.calls[0]["input"][0]["content"]
        self.assertIn('"scopeResult":{"scope":"CONVERSATIONAL"', provider_input)

    async def test_output_text_done_normalizes_a_greeting_when_final_helper_is_empty(self) -> None:
        content = "Hi! How can I help today?"
        responses = _Responses(
            events=[
                SimpleNamespace(type="response.output_text.delta", delta=content),
                SimpleNamespace(type="response.output_text.done", text=content),
                SimpleNamespace(type="response.completed"),
            ],
            output=[], text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        deltas: list[str] = []

        async def on_delta(value: str) -> None:
            deltas.append(value)

        decision = await model.decide(
            context=AgentContext(currentMessage="hi"), tools=TOOLS,
            correlation_id="v2-provider-done-shape", on_text_delta=on_delta,
            timeout_seconds=5,
        )

        self.assertEqual(content, decision.content)
        self.assertEqual([content], deltas)

    async def test_one_tool_call_is_returned_without_streaming_private_content(self) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="search_listings",
            call_id="call-1",
            arguments='{"query":"chair","limit":5}',
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call],
            text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        decision = await model.decide(
            context=AgentContext(currentMessage="chair"),
            tools=TOOLS,
            correlation_id="v2-provider-tool",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertIsNone(decision.content)
        self.assertEqual("search_listings", decision.tool_proposal.tool)
        self.assertEqual({"query": "chair", "limit": 5}, decision.tool_proposal.arguments)

    async def test_explicit_pre_search_permission_request_forces_typed_confirmation(self) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="request_confirmation",
            call_id="confirm-search-1",
            arguments=(
                '{"query":"lamp under 30","maximumPrice":"30","currency":"USD",'
                '"type":"CONFIRM_ACTION","action":"RUN_REFINED_SEARCH"}'
            ),
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call], text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="Ask me before running a new search for lamps under $30.",
                referencedListingIds=("01ARZ3NDEKTSV4RRFFQ69G5FAV",),
            ),
            tools=TOOLS,
            correlation_id="v2-force-confirmation",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("request_confirmation", decision.tool_proposal.tool)
        self.assertEqual(
            {"type": "function", "name": "request_confirmation"},
            responses.calls[0]["tool_choice"],
        )

    async def test_availability_proposal_uses_the_registered_strict_tool(self) -> None:
        call = SimpleNamespace(
            type="function_call", name="check_availability", call_id="call-probe",
            arguments='{"category":"chair"}',
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")], output=[call], text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        decision = await model.decide(
            context=AgentContext(currentMessage="chair"), tools=TOOLS,
            correlation_id="v2-provider-probe", on_text_delta=None, timeout_seconds=5,
        )

        self.assertEqual("check_availability", decision.tool_proposal.tool)
        self.assertEqual({"category": "chair"}, decision.tool_proposal.arguments)

    async def test_cancellation_closes_the_provider_stream(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.output_text.delta", delta="Partial")],
            output=[],
            text="Partial",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        async def cancel(_: str) -> None:
            raise asyncio.CancelledError

        with self.assertRaises(asyncio.CancelledError):
            await model.decide(
                context=AgentContext(currentMessage="chair"),
                tools=TOOLS,
                correlation_id="v2-provider-cancel",
                on_text_delta=cancel,
                timeout_seconds=5,
            )
        self.assertTrue(responses.closed)


if __name__ == "__main__":
    unittest.main()
