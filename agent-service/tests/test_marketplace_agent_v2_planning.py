from __future__ import annotations

import json
import unittest
from pathlib import Path
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import ConfigDict, Field

from msb_agent_service.marketplace_discovery import (
    DiscoveryPreferenceState,
    MarketplaceDiscoveryOrchestrator,
    MarketplaceIntent,
    classify_marketplace_intent,
)


ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "evals"
    / "marketplace_agent_v2_evaluation_v1.json"
)


class _TerminalModel(BaseChatModel):
    """Characterize the legacy envelope without executing Product work."""

    model_config = ConfigDict(arbitrary_types_allowed=True)
    calls: int = 0
    bound_tool_names: tuple[str, ...] = ()
    content: str = Field(default="Hello from the marketplace assistant.")

    @property
    def _llm_type(self) -> str:
        return "legacy-characterization"

    def bind_tools(self, tools: Any, **_: Any) -> BaseChatModel:
        self.bound_tool_names = tuple(tool.name for tool in tools)
        return self

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any | None = None,
        **_: Any,
    ) -> ChatResult:
        del messages, stop, run_manager
        self.calls += 1
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage(content=self.content))]
        )


class _NoProductWork:
    async def probe_availability(self, **_: Any) -> None:
        raise AssertionError("Legacy characterization must not call Product")

    async def search_individual(self, **_: Any) -> None:
        raise AssertionError("Legacy characterization must not call Product")

    async def get_listing(self, **_: Any) -> None:
        raise AssertionError("Legacy characterization must not call Product")


class LegacyMarketplaceAgentCharacterizationTest(unittest.IsolatedAsyncioTestCase):
    def test_regex_classifier_still_labels_short_product_text_as_discovery(self) -> None:
        state = DiscoveryPreferenceState()

        self.assertEqual(
            MarketplaceIntent.MARKETPLACE_DISCOVERY,
            classify_marketplace_intent("chair", state, ()),
        )
        self.assertEqual(
            MarketplaceIntent.MARKETPLACE_DISCOVERY,
            classify_marketplace_intent("give me chair", state, ()),
        )
        self.assertEqual(
            MarketplaceIntent.GENERAL_CONVERSATION,
            classify_marketplace_intent("who are you", state, ()),
        )

    async def test_natural_content_is_forced_through_discovery_compatibility_shape(
        self,
    ) -> None:
        model = _TerminalModel()

        run = await MarketplaceDiscoveryOrchestrator(model, _NoProductWork()).run(
            actor_user_id=ACTOR_ID,
            session_id=SESSION_ID,
            question="who are you",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="legacy-v2-plan-characterization",
        )

        self.assertEqual(1, model.calls)
        self.assertEqual(
            ("CHECK_AVAILABILITY", "SEARCH_INDIVIDUAL", "GET_LISTING"),
            model.bound_tool_names,
        )
        self.assertEqual("ANSWER", run.response.outcome)
        self.assertEqual([], list(run.response.recommendations))
        self.assertEqual([], list(run.response.questions))
        self.assertIsNone(run.response.search_outcome)
        self.assertEqual("GENERAL_CONVERSATION", run.response.intent)


class MarketplaceAgentV2EvaluationFixtureTest(unittest.TestCase):
    def test_fixture_freezes_the_approved_nine_turn_evaluation_set(self) -> None:
        fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

        self.assertEqual("MARKETPLACE_AGENT_V2_EVALUATION_V1", fixture["schemaVersion"])
        self.assertEqual(5, fixture["maximumModelDecisions"])
        self.assertEqual(1, fixture["maximumToolCallsPerDecision"])
        self.assertEqual(
            ["check_availability", "search_listings", "get_listing"],
            fixture["registeredTools"],
        )
        self.assertEqual(
            [
                "hi",
                "who are you",
                "chair",
                "give me chair",
                "office",
                "under 200",
                "what about the second one",
                "never mind",
                "check again",
            ],
            [case["turn"] for case in fixture["cases"]],
        )

    def test_fixture_never_advertises_an_unapproved_v2_tool(self) -> None:
        fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
        registered = set(fixture["registeredTools"])

        for case in fixture["cases"]:
            expected = case["expected"]
            self.assertTrue(set(expected["allowedTools"]).issubset(registered))
            self.assertNotIn("CHECK_AVAILABILITY", expected["allowedTools"])


if __name__ == "__main__":
    unittest.main()
