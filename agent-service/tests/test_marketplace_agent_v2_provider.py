from __future__ import annotations

import asyncio
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import Any

from msb_agent_service.config import Settings
from msb_agent_service.marketplace_agent_v2.provider import (
    MarketplaceAgentV2ProviderFailure,
    OpenAIMarketplaceAgentV2Model,
    _required_tool_choice,
)
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext,
    CartItemSnapshot,
    CustomerCartSnapshot,
    MarketplaceAgentV2ContextualRefinement,
    MarketplaceAgentV2PendingInteraction,
    ToolFacets,
    ToolObservation,
)


class _Responses:
    def __init__(
        self,
        *,
        events: list[object],
        output: list[object],
        text: str,
        status: str = "completed",
        incomplete_reason: str | None = None,
    ) -> None:
        self.events = events
        self.output = output
        self.text = text
        self.status = status
        self.incomplete_reason = incomplete_reason
        self.calls: list[dict[str, object]] = []
        self.closed = False
        self.final_response_calls = 0
        if status == "incomplete":
            response = self._final_response()
            for event in self.events:
                if getattr(event, "type", None) == "response.incomplete":
                    event.response = response

    def _final_response(self) -> object:
        return SimpleNamespace(
            status=self.status,
            incomplete_details=(
                None if self.incomplete_reason is None
                else SimpleNamespace(reason=self.incomplete_reason)
            ),
            output=self.output,
            output_text=self.text,
            usage=None,
        )

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
                owner.final_response_calls += 1
                return owner._final_response()

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

ALL_TOOLS = TOOLS + (
    {"type": "function", "name": "get_my_cart", "strict": True, "parameters": {}},
    {"type": "function", "name": "list_my_orders", "strict": True, "parameters": {}},
    {"type": "function", "name": "get_my_order", "strict": True, "parameters": {}},
    {"type": "function", "name": "add_to_my_cart", "strict": True, "parameters": {}},
    {
        "type": "function", "name": "update_my_cart_quantity",
        "strict": True, "parameters": {},
    },
    {
        "type": "function", "name": "remove_from_my_cart",
        "strict": True, "parameters": {},
    },
    {
        "type": "function", "name": "prepare_my_checkout",
        "strict": True, "parameters": {},
    },
    {
        "type": "function", "name": "get_my_checkout",
        "strict": True, "parameters": {},
    },
    {
        "type": "function", "name": "submit_my_checkout",
        "strict": True, "parameters": {},
    },
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
    def test_authoritative_recovery_signals_constrain_only_the_required_tool(self) -> None:
        cases = {
            "LISTING_DETAIL_TOOL_REQUIRED": "get_listing",
            "AVAILABILITY_TOOL_REQUIRED": "check_availability",
            "CART_READ_TOOL_REQUIRED": "get_my_cart",
            "CART_MUTATION_TOOL_REQUIRED": "remove_from_my_cart",
            "CHECKOUT_READ_TOOL_REQUIRED": "get_my_checkout",
            "ORDER_DETAIL_TOOL_REQUIRED": "get_my_order",
            "SELLER_COLLECTION_TOOL_REQUIRED": "collect_listing_information",
        }
        available = tuple(item["name"] for item in ALL_TOOLS)

        for reason, tool_name in cases.items():
            with self.subTest(reason=reason):
                choice = _required_tool_choice(
                    AgentContext(
                        currentMessage="grounded operational follow-up",
                        observations=(ToolObservation(
                            tool="DIRECT_RESPONSE", status="REJECTED",
                            reason=reason,
                        ),),
                    ),
                    available,
                )
                self.assertEqual(
                    {"type": "function", "name": tool_name}, choice
                )

    def test_completed_recovery_read_does_not_force_it_again(self) -> None:
        cases = {
            "LISTING_DETAIL_TOOL_REQUIRED": "get_listing",
            "AVAILABILITY_TOOL_REQUIRED": "check_availability",
            "CHECKOUT_READ_TOOL_REQUIRED": "get_my_checkout",
            "ORDER_DETAIL_TOOL_REQUIRED": "get_my_order",
        }
        available = tuple(item["name"] for item in ALL_TOOLS)
        for reason, tool_name in cases.items():
            with self.subTest(tool=tool_name):
                context = AgentContext(
                    currentMessage="Tell me the current state",
                    observations=(
                        ToolObservation(
                            tool="DIRECT_RESPONSE", status="REJECTED",
                            reason=reason,
                        ),
                        ToolObservation(
                            tool=tool_name, status="SUCCEEDED",
                            reason="RESULTS_AVAILABLE",
                        ),
                        ToolObservation(
                            tool=tool_name, status="REJECTED",
                            reason="READ_ALREADY_SATISFIED",
                        ),
                    ),
                )
                self.assertEqual("auto", _required_tool_choice(context, available))

    async def test_output_limit_is_classified_without_leaking_provider_data(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.incomplete")],
            output=[], text="", status="incomplete",
            incomplete_reason="max_output_tokens",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        with self.assertRaises(MarketplaceAgentV2ProviderFailure) as caught:
            await model.decide(
                context=AgentContext(currentMessage="What's in my cart?"),
                tools=TOOLS,
                correlation_id="v2-output-limit",
                on_text_delta=None,
                timeout_seconds=5,
            )

        self.assertEqual("MODEL_OUTPUT_LIMIT_EXCEEDED", caught.exception.kind)
        self.assertNotIn("max_output_tokens", str(caught.exception))
        self.assertEqual(0, responses.final_response_calls)
        self.assertTrue(responses.closed)

    async def test_other_incomplete_response_remains_provider_unavailable(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.incomplete")],
            output=[], text="", status="incomplete",
            incomplete_reason="content_filter",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )

        with self.assertRaises(MarketplaceAgentV2ProviderFailure) as caught:
            await model.decide(
                context=AgentContext(currentMessage="Find a chair"),
                tools=TOOLS,
                correlation_id="v2-other-incomplete",
                on_text_delta=None,
                timeout_seconds=5,
            )

        self.assertEqual("MODEL_PROVIDER_UNAVAILABLE", caught.exception.kind)

    async def test_full_fourteen_tool_registry_has_a_viable_decision_budget(self) -> None:
        call = SimpleNamespace(
            type="function_call", name="get_my_cart", call_id="full-registry-cart",
            arguments="{}",
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call], text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in ALL_TOOLS),
        )

        decision = await model.decide(
            context=AgentContext(currentMessage="What's in my cart?"),
            tools=ALL_TOOLS,
            correlation_id="v2-full-registry-budget",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("get_my_cart", decision.tool_proposal.tool)
        self.assertEqual(14, len(responses.calls[0]["tools"]))
        self.assertEqual(4_096, responses.calls[0]["max_output_tokens"])

    async def test_whole_cart_checkout_prompt_prefers_the_supplied_grounding_tool(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[], text="Prepared.",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in ALL_TOOLS),
        )

        await model.decide(
            context=AgentContext(currentMessage="Buy everything in my cart."),
            tools=ALL_TOOLS,
            correlation_id="v2-whole-cart-checkout-prompt",
            on_text_delta=None,
            timeout_seconds=5,
        )

        instructions = str(responses.calls[0]["instructions"])
        self.assertIn('"Buy everything in my cart"', instructions)
        self.assertIn("propose prepare_my_checkout immediately", instructions)
        self.assertIn("Do not return the private-account abstention", instructions)
        self.assertIn("partial-cart requests", instructions)
        self.assertIn('"set it back to 1"', instructions)
        self.assertIn('"first order"', instructions)
        self.assertIn("purchase-time item title, quantity, and unit price", instructions)
        self.assertIn("propose get_my_cart first", instructions)
        self.assertIn("Never put a customer-facing item title into listingId", instructions)

    async def test_return_status_prompt_requires_the_owned_read_sequence(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[], text="I can check that.",
        )
        tools = ALL_TOOLS + (
            {"type": "function", "name": "get_my_return", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "prepare_my_return_request", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "submit_my_return_request", "strict": True,
             "parameters": {}},
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in tools),
        )

        await model.decide(
            context=AgentContext(
                currentMessage="What's happening with my return? Has my refund finished?"
            ),
            tools=tools,
            correlation_id="v2-return-status-prompt",
            on_text_delta=None,
            timeout_seconds=5,
        )

        instructions = str(responses.calls[0]["instructions"])
        self.assertIn("allowed customer read", instructions)
        self.assertIn('"Can I get a refund for it?"', instructions)
        self.assertIn("stay in the owned return workflow", instructions)
        self.assertIn("Do not use the private-account abstention", instructions)
        self.assertIn("propose list_my_orders first", instructions)
        self.assertIn("RETURN_STATUS_TOOL_REQUIRED", instructions)
        self.assertIn("RETURN_PREPARATION_REQUIRED", instructions)
        self.assertIn("never repeat list_my_orders", instructions)

    async def test_return_read_rejection_constrains_safe_recovery_tool(self) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="list_my_orders",
            call_id="recover-return-read",
            arguments='{"limit":10}',
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call], text="",
        )
        tools = ALL_TOOLS + (
            {"type": "function", "name": "get_my_return", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "prepare_my_return_request", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "submit_my_return_request", "strict": True,
             "parameters": {}},
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in tools),
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="Can I get a refund for it?",
                observations=(ToolObservation(
                    tool="DIRECT_RESPONSE",
                    status="REJECTED",
                    reason="RETURN_STATUS_TOOL_REQUIRED",
                ),),
            ),
            tools=tools,
            correlation_id="v2-return-read-recovery-tool",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("list_my_orders", decision.tool_proposal.tool)
        self.assertEqual(
            {"type": "function", "name": "list_my_orders"},
            responses.calls[0]["tool_choice"],
        )

    async def test_return_preparation_signal_requires_model_tool_choice_from_stage_subset(
        self,
    ) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="prepare_my_return_request",
            call_id="prepare-grounded-return",
            arguments=(
                '{"orderId":"01ARZ3NDEKTSV4RRFFQ69G5FAY",'
                '"listingId":"01ARZ3NDEKTSV4RRFFQ69G5FAZ",'
                '"reasonCode":"DAMAGED","comment":null}'
            ),
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call], text="",
        )
        tools = ALL_TOOLS + (
            {"type": "function", "name": "get_my_return", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "prepare_my_return_request", "strict": True,
             "parameters": {}},
            {"type": "function", "name": "submit_my_return_request", "strict": True,
             "parameters": {}},
        )
        prepare_only = tuple(
            item for item in tools
            if item["name"] == "prepare_my_return_request"
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in tools),
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage=(
                    "The mouse pad arrived damaged. I want to return it."
                ),
                observations=(ToolObservation(
                    tool="DIRECT_RESPONSE", status="REJECTED",
                    reason="RETURN_PREPARATION_REQUIRED",
                ),),
            ),
            tools=prepare_only,
            correlation_id="v2-return-preparation-required",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual(
            "prepare_my_return_request", decision.tool_proposal.tool
        )
        self.assertEqual("required", responses.calls[0]["tool_choice"])

    async def test_cart_control_metadata_is_not_sent_to_provider(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[], text="Your cart has one item.",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        observation = ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=CustomerCartSnapshot(
                version=7,
                expiresAt=datetime(2026, 10, 2, 11, 19, tzinfo=UTC),
                itemCount=1,
                totalQuantity=1,
                items=(CartItemSnapshot(
                    listingId="01ARZ3NDEKTSV4RRFFQ69G5FAX",
                    title="Wireless mouse", storeName="Input Store", quantity=1,
                    observedPrice=Decimal("49"), currency="USD",
                ),),
            ),
        )

        await model.decide(
            context=AgentContext(
                currentMessage="What's in my cart?", observations=(observation,),
            ),
            tools=TOOLS,
            correlation_id="v2-cart-control-metadata",
            on_text_delta=None,
            timeout_seconds=5,
        )

        provider_input = responses.calls[0]["input"][0]["content"]
        self.assertNotIn('"version":7', provider_input)
        self.assertNotIn('"expiresAt"', provider_input)
        self.assertIn('"title":"Wireless mouse"', provider_input)

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
        self.assertIn(
            '"Find Harbor business items" searches for "Harbor Business" immediately',
            instructions,
        )
        self.assertIn('Preserve meaningful product words', instructions)
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

    async def test_immediate_cart_pronoun_forces_the_bound_mutation_tool(self) -> None:
        call = SimpleNamespace(
            type="function_call",
            name="update_my_cart_quantity",
            call_id="update-bound-cart-item",
            arguments=(
                '{"listingId":"01ARZ3NDEKTSV4RRFFQ69G5FAV","quantity":1}'
            ),
        )
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[call],
            text="",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"),
            _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in ALL_TOOLS),
        )
        observation = ToolObservation(
            tool="update_my_cart_quantity",
            status="SUCCEEDED",
            reason="CART_QUANTITY_UPDATED",
            cartMutationReference={
                "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "operation": "UPDATE_QUANTITY",
                "requestedQuantity": 2,
            },
            cartItemReferences=(
                {
                    "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                    "title": "Harbor Business Mouse Pad",
                    "position": 1,
                },
                {
                    "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAW",
                    "title": "Harbor Business Desk Lamp",
                    "position": 2,
                },
            ),
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="Set it back to 1.",
                observations=(observation,),
            ),
            tools=ALL_TOOLS,
            correlation_id="v2-force-bound-cart-update",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("update_my_cart_quantity", decision.tool_proposal.tool)
        self.assertEqual(
            {"type": "function", "name": "update_my_cart_quantity"},
            responses.calls[0]["tool_choice"],
        )

    async def test_completed_cart_pronoun_update_does_not_force_a_duplicate(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[],
            text="The item quantity is now 1.",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"),
            _Client(responses),
            allowed_tool_names=tuple(item["name"] for item in ALL_TOOLS),
        )
        observation = ToolObservation(
            tool="update_my_cart_quantity",
            status="SUCCEEDED",
            reason="CART_QUANTITY_UPDATED",
            cartMutationReference={
                "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "operation": "UPDATE_QUANTITY",
                "requestedQuantity": 1,
            },
        )

        decision = await model.decide(
            context=AgentContext(
                currentMessage="Set it back to 1.",
                observations=(observation,),
            ),
            tools=ALL_TOOLS,
            correlation_id="v2-do-not-force-completed-cart-update",
            on_text_delta=None,
            timeout_seconds=5,
        )

        self.assertEqual("The item quantity is now 1.", decision.content)
        self.assertEqual("auto", responses.calls[0]["tool_choice"])

    async def test_pending_confirmation_exposes_only_display_safe_summary_to_model(self) -> None:
        responses = _Responses(
            events=[SimpleNamespace(type="response.completed")],
            output=[], text="Confirm?",
        )
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"), _Client(responses)
        )
        now = datetime.now(UTC)
        pending = MarketplaceAgentV2PendingInteraction(
            id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            confirmationId="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            type="CONFIRM_ACTION",
            action="RUN_REFINED_SEARCH",
            arguments={"query": "lamp under 30", "limit": 5},
            summary="Run the prepared marketplace search for lamps under 30 USD.",
            status="WAITING",
            createdAt=now,
            expiresAt=now + timedelta(minutes=15),
        )

        await model.decide(
            context=AgentContext(
                currentMessage="Ask me first.", pendingInteraction=pending
            ),
            tools=TOOLS,
            correlation_id="v2-safe-confirmation-context",
            on_text_delta=None,
            timeout_seconds=5,
        )

        provider_input = str(responses.calls[0]["input"][0]["content"])
        self.assertIn('"summary":"Run the prepared marketplace search', provider_input)
        self.assertIn('"status":"WAITING"', provider_input)
        self.assertNotIn("01ARZ3NDEKTSV4RRFFQ69G5FAV", provider_input)
        self.assertNotIn('"arguments"', provider_input)
        self.assertNotIn('"expiresAt"', provider_input)

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
