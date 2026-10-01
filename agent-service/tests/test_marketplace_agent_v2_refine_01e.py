from __future__ import annotations

import unittest
from typing import Any

from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MAX_AGENT_STEPS,
    MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.provider import _required_tool_choice
from msb_agent_service.marketplace_agent_v2.schemas import (
    ModelDecision,
    ToolObservation,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.scope import MarketplaceScopeClassifier


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
TITLE_WITH_STORE = "RC Delivered Return Fixture Harbor Cart Supply"
DOCUMENT_FALLBACK = (
    "I couldn't find an official marketplace document that answers that clearly."
)


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.calls: list[Any] = []
        self.tools: list[tuple[str, ...]] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.calls.append(kwargs["context"])
        self.tools.append(tuple(item["name"] for item in kwargs["tools"]))
        if not self.decisions:
            raise AssertionError("Unexpected extra model decision")
        decision = self.decisions.pop(0)
        if decision.content is not None and kwargs["on_text_delta"] is not None:
            await kwargs["on_text_delta"](decision.content)
        return decision

    async def close(self) -> None:
        return None


class _Registry:
    names = ("search_listings", "retrieve_help")

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return tuple({"name": name} for name in self.names)

    async def execute(self, **kwargs: Any) -> ToolObservation:
        self.calls.append(kwargs)
        return ToolObservation(
            tool="search_listings", status="SUCCEEDED",
            reason="CATEGORY_UNAVAILABLE", normalizedQuery=TITLE_WITH_STORE,
        )


class BareMarketplaceScopeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.classifier = MarketplaceScopeClassifier()

    def classify(self, message: str):
        return self.classifier.classify(
            current_message=message, recent_messages=(), referenced_listings=(),
            pending_interaction=None, preference_state={},
        )

    def test_bare_product_phrases_use_listing_grounding(self) -> None:
        for message in (
            "RC Delivered Return Fixture",
            TITLE_WITH_STORE,
            "red Logitech keyboard",
            "27 inch monitor",
            "monitor under $300",
            "Acme Blue Ceramic Coffee Mug",
        ):
            with self.subTest(message=message):
                scope = self.classify(message)
                self.assertEqual("IN_SCOPE", scope.scope)
                self.assertEqual("LISTING_DATA", scope.required_grounding)

    def test_help_out_of_scope_and_ambiguous_controls_do_not_become_searches(self) -> None:
        for message in (
            "How do returns work?",
            "How do I add an item to my cart?",
            "What does verified business mean?",
        ):
            with self.subTest(message=message):
                self.assertNotEqual("LISTING_DATA", self.classify(message).required_grounding)
        for message in ("What's the capital of France?", "Tell me a joke."):
            with self.subTest(message=message):
                self.assertEqual("OUT_OF_SCOPE", self.classify(message).scope)
        for message in ("returns", "seller", "shipping"):
            with self.subTest(message=message):
                scope = self.classify(message)
                self.assertEqual("AMBIGUOUS", scope.scope)
                self.assertEqual("NONE", scope.required_grounding)

    def test_bare_title_keeps_model_tool_choice_open(self) -> None:
        scope = self.classify(TITLE_WITH_STORE)
        self.assertEqual("LISTING_DATA", scope.required_grounding)
        from msb_agent_service.marketplace_agent_v2.schemas import AgentContext

        context = AgentContext(
            currentMessage=TITLE_WITH_STORE,
            recentMessages=(), referencedListingIds=(), observations=(),
            scopeResult=scope,
        )
        self.assertEqual(
            "auto", _required_tool_choice(context, ("search_listings", "retrieve_help")),
        )


class BareMarketplaceTerminalTest(unittest.IsolatedAsyncioTestCase):
    async def test_document_fallback_is_rejected_then_model_searches_once(self) -> None:
        model = _Model([
            ModelDecision(content=DOCUMENT_FALLBACK),
            ModelDecision(toolProposal=ToolProposal(
                callId="bare-search", tool="search_listings",
                arguments={"query": TITLE_WITH_STORE, "limit": 5},
            )),
            ModelDecision(content="No current listing matched that exact phrase."),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(value: str) -> None:
            deltas.append(value)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, current_message=TITLE_WITH_STORE,
            recent_messages=(), referenced_listings=(),
            correlation_id="refine-01e-bare-title", text_delta=capture,
        )

        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, len(registry.calls))
        self.assertEqual(TITLE_WITH_STORE, registry.calls[0]["arguments"].query)
        self.assertIn("search_listings", model.tools[0])
        self.assertNotIn(DOCUMENT_FALLBACK, "".join(deltas))
        self.assertIn("No current listing", result.message.content)
        self.assertEqual("GROUNDING_REQUIRED", model.calls[1].observations[-1].reason)

    async def test_repeated_bad_prose_stops_within_five_decisions(self) -> None:
        model = _Model([ModelDecision(content=DOCUMENT_FALLBACK)
                        for _ in range(MAX_AGENT_STEPS)])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, current_message=TITLE_WITH_STORE,
            recent_messages=(), referenced_listings=(),
            correlation_id="refine-01e-bounded-failure",
        )

        self.assertEqual(MAX_AGENT_STEPS, result.decision_count)
        self.assertEqual(0, len(registry.calls))
        self.assertNotIn("official marketplace document", result.message.content)
        self.assertIn("?", result.message.content)
