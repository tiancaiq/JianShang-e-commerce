"""REFINE-FIX-01C: rejected terminal prose never escapes before validation."""

from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2OrchestrationFailure, MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.provider import _required_tool_choice
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext, ExecutedSearchSnapshot, ListingAttachment, MarketplaceScopeResult,
    ModelDecision, ToolObservation, ToolProposal,
)


_ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
_SCOPE = MarketplaceScopeResult(
    scope="IN_SCOPE", confidence="HIGH", marketplaceContextUsed=True,
    reasonCode="CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
    requiredGrounding="LISTING_DATA",
)


def _prior_search() -> tuple[ToolObservation, ListingAttachment]:
    now = datetime.now(UTC)
    snapshot = ExecutedSearchSnapshot(
        query="monitor", maximumPrice=Decimal("300"), currency="USD",
        limit=5, observedAt=now, expiresAt=now + timedelta(minutes=5),
    )
    listing = ListingAttachment(
        listingId="01ARZ3NDEKTSV4RRFFQ69G5FAW", title="Monitor",
        categoryName="Electronics", condition="GOOD",
        priceAmount=Decimal("250"), currency="USD", checkedAt=now,
        responseHash="a" * 64,
    )
    return ToolObservation(
        tool="search_listings", status="SUCCEEDED", reason="RESULTS_AVAILABLE",
        appliedSearch=snapshot, normalizedQuery="monitor", resultCount=1,
        attachments=(listing,),
    ), listing


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[object] = []
        self.tool_sets: list[tuple[str, ...]] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        self.tool_sets.append(tuple(tool["name"] for tool in kwargs["tools"]))
        if not self.decisions:
            raise AssertionError("Unexpected model decision after repair")
        decision = self.decisions.pop(0)
        if decision.content is not None and kwargs["on_text_delta"] is not None:
            await kwargs["on_text_delta"](decision.content)
        return decision

    async def close(self) -> None:
        return None


class _Registry:
    names = ("search_listings", "get_listing", "add_to_my_cart")

    def __init__(self) -> None:
        self.executions: list[str] = []

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return tuple({"name": name} for name in self.names)

    async def execute(self, **kwargs: Any) -> ToolObservation:
        self.executions.append(kwargs["tool"])
        if kwargs["tool"] != "search_listings":
            raise AssertionError("Terminal repair must not run another tool")
        return ToolObservation(
            tool="search_listings", status="SUCCEEDED",
            reason="CATEGORY_UNAVAILABLE", resultCount=0,
            normalizedQuery=kwargs["arguments"].query,
        )


class Refine01CTerminalRecoveryTest(unittest.IsolatedAsyncioTestCase):
    def test_terminal_repair_keeps_model_choice_open_for_clarification(self) -> None:
        prior, _ = _prior_search()
        context = AgentContext(
            currentMessage="Make it better.", latestSearch=prior.applied_search,
            observations=(ToolObservation(
                tool="DIRECT_RESPONSE", status="REJECTED",
                reason="SEARCH_PERMISSION_UNREQUESTED",
            ),),
        )
        self.assertEqual(
            "auto", _required_tool_choice(context, ("search_listings",)),
        )

    async def test_ungrounded_result_claim_is_buffered_then_model_searches(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            ModelDecision(content="I found current monitor listings."),
            ModelDecision(toolProposal=ToolProposal(
                callId="current-search", tool="search_listings",
                arguments={"query": "monitor", "maximumPrice": 300,
                           "currency": "USD"},
            )),
            ModelDecision(content="I couldn't find current monitor listings."),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=_ACTOR, current_message="Show me those again.",
            recent_messages=(), referenced_listings=(listing,),
            prior_observations=(prior,), scope_result=_SCOPE,
            correlation_id="refine-01c-result-claim", text_delta=capture,
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(["search_listings"], registry.executions)
        self.assertEqual(("search_listings",), model.tool_sets[1])
        self.assertEqual(
            "SEARCH_RESULT_CLAIM_UNGROUNDED",
            model.contexts[1].observations[-1].reason,
        )
        self.assertEqual(result.message.content, "".join(deltas))
        self.assertNotIn("I found current monitor listings.", "".join(deltas))
        self.assertIsNone(result.pending_interaction)

    async def test_unrequested_permission_is_buffered_then_model_can_clarify(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            ModelDecision(content="Would you like me to search again?"),
            ModelDecision(content="Which monitor size do you mean?"),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=_ACTOR, current_message="Make it better.",
            recent_messages=(), referenced_listings=(listing,),
            prior_observations=(prior,), scope_result=_SCOPE,
            correlation_id="refine-01c-permission", text_delta=capture,
        )
        self.assertEqual(2, result.decision_count)
        self.assertEqual([], registry.executions)
        self.assertEqual(
            "SEARCH_PERMISSION_UNREQUESTED",
            model.contexts[1].observations[-1].reason,
        )
        self.assertEqual(("search_listings",), model.tool_sets[1])
        self.assertEqual(["Which monitor size do you mean?"], deltas)

    async def test_missing_current_grounding_gets_one_search_repair(self) -> None:
        prior, _ = _prior_search()
        model = _Model([
            ModelDecision(content="I can show current monitor matches."),
            ModelDecision(toolProposal=ToolProposal(
                callId="grounding-search", tool="search_listings",
                arguments={"query": "monitor", "maximumPrice": 300,
                           "currency": "USD"},
            )),
            ModelDecision(content="I couldn't find current monitor listings."),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=_ACTOR, current_message="Show me those again.",
            recent_messages=(), referenced_listings=(),
            prior_observations=(prior,), scope_result=_SCOPE,
            correlation_id="refine-01c-grounding", text_delta=capture,
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(
            "SEARCH_TERMINAL_GROUNDING_REQUIRED",
            model.contexts[1].observations[-1].reason,
        )
        self.assertEqual(["search_listings"], registry.executions)
        self.assertNotIn("I can show", "".join(deltas))

    async def test_post_search_proceed_question_uses_grounded_fallback(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="refreshed-search", tool="search_listings",
                arguments={"query": "monitor", "maximumPrice": 300,
                           "currency": "USD"},
            )),
            ModelDecision(content=(
                "I'll re-run the current monitor search with that constraint. "
                "Proceed?"
            )),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=_ACTOR, current_message="Show me those again.",
            recent_messages=(), referenced_listings=(listing,),
            prior_observations=(prior,), scope_result=_SCOPE,
            correlation_id="refine-01c-post-search-permission", text_delta=capture,
        )
        self.assertEqual(2, result.decision_count)
        self.assertEqual(["search_listings"], registry.executions)
        self.assertNotIn("Proceed?", result.message.content)
        self.assertNotIn("Proceed?", "".join(deltas))
        self.assertEqual(result.message.content, "".join(deltas))

    async def test_post_search_updated_search_permission_uses_grounded_fallback(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="updated-search", tool="search_listings",
                arguments={"query": "monitor 32 inch", "maximumPrice": 300,
                           "currency": "USD"},
            )),
            ModelDecision(content=(
                'I will keep the under $300 filter. Do you want me to run '
                'that updated search now?'
            )),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=_ACTOR, current_message="Actually 32 inch.",
            recent_messages=(), referenced_listings=(listing,),
            prior_observations=(prior,), scope_result=_SCOPE,
            correlation_id="refine-01c-updated-search-permission", text_delta=capture,
        )
        self.assertEqual(2, result.decision_count)
        self.assertEqual(["search_listings"], registry.executions)
        self.assertNotIn("run that updated search", result.message.content)
        self.assertNotIn("run that updated search", "".join(deltas))
        self.assertEqual(result.message.content, "".join(deltas))

    async def test_fifth_decision_does_not_start_sixth_repair(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            *(ModelDecision(toolProposal=ToolProposal(
                callId=f"unknown-{index}", tool="unknown_tool", arguments={},
            )) for index in range(4)),
            ModelDecision(content="I found current monitor listings."),
        ])
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure):
            await MarketplaceAgentV2Orchestrator(model, _Registry()).run(
                actor_user_id=_ACTOR, current_message="Show me those again.",
                recent_messages=(), referenced_listings=(listing,),
                prior_observations=(prior,), scope_result=_SCOPE,
                correlation_id="refine-01c-step-five", text_delta=capture,
            )
        self.assertEqual(5, len(model.contexts))
        self.assertEqual([], deltas)

    async def test_repeated_bad_text_has_one_repair_and_no_leaked_delta(self) -> None:
        prior, listing = _prior_search()
        model = _Model([
            ModelDecision(content="I found current monitor listings."),
            ModelDecision(content="I found current monitor listings."),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure) as caught:
            await MarketplaceAgentV2Orchestrator(model, registry).run(
                actor_user_id=_ACTOR, current_message="Show me those again.",
                recent_messages=(), referenced_listings=(listing,),
                prior_observations=(prior,), scope_result=_SCOPE,
                correlation_id="refine-01c-repeat", text_delta=capture,
            )
        self.assertEqual("MODEL_RESPONSE_UNSUPPORTED", caught.exception.kind)
        self.assertEqual(2, len(model.contexts))
        self.assertEqual([], registry.executions)
        self.assertEqual([], deltas)

    async def test_internal_enum_refusal_is_not_repaired_or_streamed(self) -> None:
        prior, listing = _prior_search()
        model = _Model([ModelDecision(content="The monitor is NEW.")])
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure):
            await MarketplaceAgentV2Orchestrator(model, _Registry()).run(
                actor_user_id=_ACTOR, current_message="Show me those again.",
                recent_messages=(), referenced_listings=(listing,),
                prior_observations=(prior,), scope_result=_SCOPE,
                correlation_id="refine-01c-internal-enum", text_delta=capture,
            )
        self.assertEqual(1, len(model.contexts))
        self.assertEqual([], deltas)


if __name__ == "__main__":
    unittest.main()
