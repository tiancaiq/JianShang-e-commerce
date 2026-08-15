from __future__ import annotations

import unittest
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import ConfigDict, Field

from msb_agent_service.marketplace_discovery import (
    DiscoveryAvailabilityProbe,
    DiscoveryPreferenceState,
    MarketplaceDiscoveryOrchestrator,
)


class _DecisionModel(BaseChatModel):
    """Provide deterministic agent decisions while recording the live registry."""

    model_config = ConfigDict(arbitrary_types_allowed=True)
    decisions: list[AIMessage] = Field(default_factory=list)
    calls: list[list[BaseMessage]] = Field(default_factory=list)
    tool_names: tuple[str, ...] = ()

    @property
    def _llm_type(self) -> str:
        return "model-first-test"

    def bind_tools(self, tools: Any, **_: Any) -> BaseChatModel:
        self.tool_names = tuple(tool.name for tool in tools)
        return self

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any | None = None,
        **_: Any,
    ) -> ChatResult:
        del stop, run_manager
        self.calls.append(messages)
        if not self.decisions:
            raise AssertionError("Unexpected sixth model decision")
        return ChatResult(
            generations=[ChatGeneration(message=self.decisions.pop(0))]
        )


class _Product:
    def __init__(self, inventory: int = 3) -> None:
        self.inventory = inventory
        self.probes = 0
        self.searches = 0
        self.details = 0

    async def probe_availability(self, **kwargs: Any) -> DiscoveryAvailabilityProbe:
        self.probes += 1
        return DiscoveryAvailabilityProbe(
            schemaVersion="MARKETPLACE_AVAILABILITY_PROBE_V1",
            mode="AVAILABILITY_PROBE",
            searchExecuted=True,
            category=kwargs["category"],
            totalActiveCategoryInventory=self.inventory,
            relatedCategoryMatches=0,
            failureReason=None,
            retryable=False,
        )

    async def search_individual(self, **_: Any) -> None:
        self.searches += 1
        raise AssertionError("Search was not expected")

    async def get_listing(self, **_: Any) -> None:
        self.details += 1
        raise AssertionError("Detail lookup was not expected")


def _tool_call(call_id: str = "call-1") -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[
            {"id": call_id, "name": "CHECK_AVAILABILITY", "args": {"category": "chair"}}
        ],
    )


class MarketplaceAgentModelFirstTest(unittest.IsolatedAsyncioTestCase):
    async def _run(
        self,
        model: _DecisionModel,
        product: _Product,
        *,
        question: str = "hi",
        preferences: DiscoveryPreferenceState | None = None,
    ):
        return await MarketplaceDiscoveryOrchestrator(model, product).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            session_id="01ARZ3NDEKTSV4RRFFQ69G5FAW",
            question=question,
            preference_state=preferences or DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="model-first-test",
        )

    async def test_natural_content_is_one_model_call_with_no_tool(self) -> None:
        model = _DecisionModel(
            decisions=[AIMessage(content="Hi! How can I help with the marketplace today?")]
        )
        product = _Product()
        stages: list[str] = []

        async def activity(stage: str) -> None:
            stages.append(stage)

        run = await MarketplaceDiscoveryOrchestrator(model, product).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            session_id="01ARZ3NDEKTSV4RRFFQ69G5FAW",
            question="hi",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="model-first-direct-test",
            activity=activity,  # type: ignore[arg-type]
        )

        self.assertEqual("ANSWER", run.response.outcome)
        self.assertEqual(1, len(model.calls))
        self.assertEqual(0, product.probes + product.searches + product.details)
        self.assertFalse(run.requires_final_generation)
        self.assertEqual([], stages)
        self.assertEqual(
            ("CHECK_AVAILABILITY", "SEARCH_INDIVIDUAL", "GET_LISTING"),
            model.tool_names,
        )

    async def test_model_selected_tool_is_validated_then_observed(self) -> None:
        model = _DecisionModel(
            decisions=[
                _tool_call(),
                AIMessage(content="Current chair listings are available. What style do you prefer?"),
            ]
        )
        product = _Product(inventory=4)
        stages: list[str] = []

        async def activity(stage: str) -> None:
            stages.append(stage)

        run = await MarketplaceDiscoveryOrchestrator(model, product).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            session_id="01ARZ3NDEKTSV4RRFFQ69G5FAW",
            question="chair",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="model-first-tool-test",
            activity=activity,  # type: ignore[arg-type]
        )

        self.assertEqual(1, product.probes)
        self.assertEqual("MARKETPLACE_DISCOVERY", run.response.intent)
        self.assertEqual("CHECKING_AVAILABILITY", stages[0])
        self.assertTrue(any(isinstance(item, ToolMessage) for item in model.calls[1]))

    async def test_duplicate_tool_proposal_is_rejected_without_duplicate_work(self) -> None:
        model = _DecisionModel(
            decisions=[
                _tool_call("call-1"),
                _tool_call("call-2"),
                AIMessage(content="I already checked that category once in this response."),
            ]
        )
        product = _Product()

        await self._run(model, product, question="chair")

        self.assertEqual(1, product.probes)
        rejection = model.calls[2][-1]
        self.assertIsInstance(rejection, ToolMessage)
        self.assertIn("DUPLICATE_TOOL_CALL", str(rejection.content))

    async def test_cached_zero_is_context_not_a_terminal_routing_gate(self) -> None:
        model = _DecisionModel(
            decisions=[AIMessage(content="I can check again or help you try a different chair request.")]
        )
        product = _Product(inventory=0)
        state = DiscoveryPreferenceState(
            query="chair",
            status="NO_INVENTORY",
            requestedCategory="chair",
            categoryAvailability="UNAVAILABLE",
            categoryInventoryCount=0,
            lastSearchOutcome="CATEGORY_UNAVAILABLE",
            activeGoal="FIND_PRODUCT",
            activeCategory="chair",
            workflowStatus="COMPLETE",
        )

        run = await self._run(model, product, question="check again", preferences=state)

        self.assertEqual(1, len(model.calls))
        self.assertEqual(0, product.probes)
        self.assertEqual("ANSWER", run.response.outcome)

    async def test_five_decisions_never_start_a_sixth_model_call(self) -> None:
        model = _DecisionModel(
            decisions=[_tool_call(f"call-{index}") for index in range(1, 6)]
        )
        product = _Product()

        run = await self._run(model, product, question="chair")

        self.assertEqual(5, len(model.calls))
        self.assertEqual(1, product.probes)
        self.assertIn("Current listings exist", run.response.message)
