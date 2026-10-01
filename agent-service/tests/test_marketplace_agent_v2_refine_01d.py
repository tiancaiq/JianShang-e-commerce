"""REFINE-FIX-01D: explicit replacement removes omitted free-text terms."""

from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

from msb_agent_service.agent_persistence import AgentMessage, AgentMessageRole
from msb_agent_service.marketplace_agent_v2.context_builder import ContextBuilder
from msb_agent_service.marketplace_agent_v2.orchestrator import MarketplaceAgentV2Orchestrator
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.refinement import (
    customer_search_refinement, matches_search_refinement,
)
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext, ExecutedSearchSnapshot, MarketplaceScopeResult, ModelDecision,
    SearchListingsArguments, ToolObservation, ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.service import _persistable_observation


def _snapshot(query: str, **filters: object) -> ExecutedSearchSnapshot:
    now = datetime.now(UTC)
    for key in ("minimumPrice", "maximumPrice"):
        if key in filters and filters[key] is not None:
            filters[key] = Decimal(str(filters[key]))
    return ExecutedSearchSnapshot.model_validate({
        "query": query, "limit": 5, "observedAt": now,
        "expiresAt": now + timedelta(minutes=5), **filters,
    })


class ExplicitQueryReplacementTest(unittest.TestCase):
    def test_command_wrappers_keep_only_self_contained_replacement(self) -> None:
        old = "RC Delivered Return Fixture Harbor Cart Supply"
        expected = "RC Delivered Return Fixture"
        for message in (
            "Search just RC Delivered Return Fixture",
            "Search only RC Delivered Return Fixture",
            "Search for RC Delivered Return Fixture",
            "Just search RC Delivered Return Fixture",
            "Search RC Delivered Return Fixture instead",
        ):
            with self.subTest(message=message):
                edit = customer_search_refinement(message, _snapshot(old))
                self.assertIsNotNone(edit)
                self.assertEqual("QUERY_REPLACEMENT", edit.kind)
                self.assertEqual(expected, edit.value)
        for old, message, expected in (
            ("red Logitech keyboard", "Use red keyboard instead", "red keyboard"),
            ("wireless mouse Harbor Cart Supply", "Replace that with wireless mouse", "wireless mouse"),
        ):
            with self.subTest(message=message):
                edit = customer_search_refinement(message, _snapshot(old))
                self.assertIsNotNone(edit)
                self.assertEqual("QUERY_REPLACEMENT", edit.kind)
                self.assertEqual(expected, edit.value)

    def test_command_replacement_preserves_typed_filters(self) -> None:
        snapshot = _snapshot(
            "Dell monitor", maximumPrice="300", currency="USD", condition="NEW",
        )
        for message in ("Search just monitor", "Use monitor instead"):
            with self.subTest(message=message):
                edit = customer_search_refinement(message, snapshot)
                self.assertIsNotNone(edit)
                self.assertEqual("QUERY_REPLACEMENT", edit.kind)
                self.assertEqual("monitor", edit.value)
                self.assertTrue(matches_search_refinement(
                    SearchListingsArguments(
                        query="monitor", maximumPrice=Decimal("300"),
                        currency="USD", condition="NEW",
                    ), snapshot, edit,
                ))
                self.assertFalse(matches_search_refinement(
                    SearchListingsArguments(query="Dell monitor", maximumPrice=Decimal("300"),
                        currency="USD", condition="NEW"), snapshot, edit,
                ))
                self.assertFalse(matches_search_refinement(
                    SearchListingsArguments(query="monitor"), snapshot, edit,
                ))

    def test_replacements_remove_only_omitted_query_terms(self) -> None:
        cases = (
            ("RC Delivered Return Fixture Harbor Cart Supply", "RC Delivered Return Fixture"),
            ("red Logitech keyboard", "red keyboard"),
            ("wireless mouse Harbor Cart Supply", "wireless mouse"),
            ("Dell monitor", "monitor"),
        )
        for old, replacement in cases:
            with self.subTest(old=old):
                snapshot = _snapshot(old)
                edit = customer_search_refinement(replacement, snapshot)
                self.assertIsNotNone(edit)
                self.assertEqual("QUERY_REPLACEMENT", edit.kind)
                self.assertTrue(matches_search_refinement(
                    SearchListingsArguments(query=replacement), snapshot, edit,
                ))
                self.assertFalse(matches_search_refinement(
                    SearchListingsArguments(query=old), snapshot, edit,
                ))

    def test_replacement_preserves_every_independent_typed_filter(self) -> None:
        snapshot = _snapshot(
            "Dell monitor", maximumPrice="300", minimumPrice="50",
            currency="USD", condition="NEW", city="San Jose", county="Santa Clara",
            categoryId="01ARZ3NDEKTSV4RRFFQ69G5FAV", categoryName="Electronics",
        )
        edit = customer_search_refinement("monitor", snapshot)
        proposed = SearchListingsArguments(
            query="monitor", maximumPrice=Decimal("300"), minimumPrice=Decimal("50"),
            currency="USD", condition="NEW", city="San Jose", county="Santa Clara",
            categoryId="01ARZ3NDEKTSV4RRFFQ69G5FAV", categoryName="Electronics",
        )
        self.assertTrue(matches_search_refinement(proposed, snapshot, edit))
        for field in (
            "maximum_price", "minimum_price", "currency", "condition", "city",
            "county", "category_id", "category_name",
        ):
            with self.subTest(field=field):
                self.assertFalse(matches_search_refinement(
                    proposed.model_copy(update={field: None}), snapshot, edit,
                ))

    def test_short_edits_new_product_and_ambiguous_are_not_replacements(self) -> None:
        snapshot = _snapshot("monitor", maximumPrice="350", currency="USD")
        for message in ("27 inch", "Under $300", "wireless only", "no RGB", "Actually 32 inch"):
            with self.subTest(message=message):
                edit = customer_search_refinement(message, snapshot)
                self.assertIsNotNone(edit)
                self.assertNotEqual("QUERY_REPLACEMENT", edit.kind)
        self.assertIsNone(customer_search_refinement("make it better", snapshot))
        for message in ("another option", "cheaper", "something else"):
            self.assertIsNone(customer_search_refinement(message, snapshot))
        for message in ("Search just 27 inch", "Search only under $300"):
            self.assertIsNone(customer_search_refinement(message, snapshot))
        self.assertIsNone(customer_search_refinement(
            "Show me keyboards", _snapshot("laptop", maximumPrice="1000", currency="USD"),
        ))
        self.assertIsNone(customer_search_refinement(
            "Search for keyboards", _snapshot("laptop", maximumPrice="1000", currency="USD"),
        ))

    def test_additive_and_attribute_edits_remain_anchored(self) -> None:
        cases = (
            (_snapshot("monitor"), "27 inch", "SIZE"),
            (_snapshot("27 inch monitor"), "under $300", "MAX_PRICE"),
            (_snapshot("monitor", maximumPrice="300", currency="USD"), "27 inch", "SIZE"),
            (_snapshot("27 inch monitor", maximumPrice="300", currency="USD"),
             "actually 32 inch", "SIZE"),
            (_snapshot("laptop", maximumPrice="1000", currency="USD"), "16GB RAM", "RAM"),
            (_snapshot("keyboard", maximumPrice="100", currency="USD"),
             "wireless only", "WIRELESS"),
            (_snapshot("keyboard", maximumPrice="100", currency="USD"),
             "no RGB", "NO_RGB"),
        )
        for snapshot, message, expected_kind in cases:
            with self.subTest(query=snapshot.query, message=message):
                edit = customer_search_refinement(message, snapshot)
                self.assertIsNotNone(edit)
                self.assertEqual(expected_kind, edit.kind)

    def test_repair_signal_identifies_replacement_without_tool_arguments(self) -> None:
        snapshot = _snapshot("RC Delivered Return Fixture Harbor Cart Supply")
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=snapshot,
            search_refinement=customer_search_refinement("RC Delivered Return Fixture", snapshot),
        )
        _, rejected = policy.validate(ToolProposal(
            callId="stale", tool="search_listings",
            arguments={"query": snapshot.query},
        ), step=1)
        self.assertEqual("SEARCH_REFINEMENT_MISMATCH", rejected.reason)
        signal = rejected.refinement_repair
        self.assertEqual("QUERY_TERMS_CHANGED", signal.mismatch)
        self.assertEqual("QUERY", signal.requested_edit.field)
        self.assertEqual("REPLACE", signal.requested_edit.operation)
        self.assertEqual("RC Delivered Return Fixture", signal.requested_edit.value)
        self.assertFalse(signal.preserve_product_core)
        self.assertTrue(signal.preserve_unchanged_filters)
        self.assertNotIn("arguments", str(rejected.model_dump(mode="json", by_alias=True)))


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[AgentContext] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        return self.decisions.pop(0)


class _Registry:
    names = ("search_listings",)

    def __init__(self) -> None:
        self.arguments: list[SearchListingsArguments] = []

    def provider_schemas(self) -> tuple[dict[str, str], ...]:
        return ({"name": "search_listings"},)

    async def execute(self, **kwargs: Any) -> ToolObservation:
        self.arguments.append(kwargs["arguments"])
        return ToolObservation(
            tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
            normalizedQuery=kwargs["arguments"].query, resultCount=0,
        )


class ExplicitQueryReplacementOrchestrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_search_just_rejects_stale_proposal_before_one_execution(self) -> None:
        old = "RC Delivered Return Fixture Harbor Cart Supply"
        new = "RC Delivered Return Fixture"
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="stale", tool="search_listings", arguments={"query": old},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="clean", tool="search_listings", arguments={"query": new},
            )),
            ModelDecision(content="No current matches were found."),
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Search just RC Delivered Return Fixture",
            recent_messages=(), referenced_listings=(),
            prior_observations=(ToolObservation(
                tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
                appliedSearch=_snapshot(old), normalizedQuery=old, resultCount=0,
            ),), correlation_id="refine-01d1-repair",
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual([new], [arguments.query for arguments in registry.arguments])
        rejection = model.contexts[1].observations[-1]
        self.assertEqual("SEARCH_REFINEMENT_MISMATCH", rejection.reason)
        self.assertEqual("QUERY_TERMS_CHANGED", rejection.refinement_repair.mismatch)
        self.assertIsNone(result.pending_interaction)

    async def test_replacement_title_with_return_word_keeps_listing_grounding(self) -> None:
        old = "RC Delivered Return Fixture Harbor Cart Supply"
        new = "RC Delivered Return Fixture"
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="clean", tool="search_listings", arguments={"query": new},
            )),
            ModelDecision(content="No current matches were found."),
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV", current_message=new,
            recent_messages=(("user", "Search for " + old),), referenced_listings=(),
            prior_observations=(ToolObservation(
                tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
                appliedSearch=_snapshot(old), normalizedQuery=old, resultCount=0,
            ),), correlation_id="refine-01d-title-scope",
        )
        self.assertEqual("LISTING_DATA", result.scope_result.required_grounding)
        self.assertEqual(new, registry.arguments[0].query)

    async def test_stale_proposal_repairs_then_executes_once(self) -> None:
        old = "RC Delivered Return Fixture Harbor Cart Supply"
        new = "RC Delivered Return Fixture"
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="stale", tool="search_listings", arguments={"query": old},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="clean", tool="search_listings", arguments={"query": new},
            )),
            ModelDecision(content="No current matches were found."),
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV", current_message=new,
            recent_messages=(), referenced_listings=(),
            prior_observations=(ToolObservation(
                tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
                appliedSearch=_snapshot(old), normalizedQuery=old, resultCount=0,
            ),),
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", confidence="HIGH", marketplaceContextUsed=True,
                reasonCode="CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
                requiredGrounding="LISTING_DATA",
            ), correlation_id="refine-01d-repair",
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, len(registry.arguments))
        self.assertEqual(new, registry.arguments[0].query)
        self.assertEqual("SEARCH_REFINEMENT_MISMATCH", model.contexts[1].observations[-1].reason)
        self.assertIsNone(result.pending_interaction)

    async def test_persisted_replacement_reloads_for_one_actor_session_only(self) -> None:
        old = "RC Delivered Return Fixture Harbor Cart Supply"
        new = "RC Delivered Return Fixture"
        def message(index: int, actor: str, session: str, query: str) -> AgentMessage:
            snapshot = _snapshot(query)
            observation = ToolObservation(
                tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
                appliedSearch=snapshot, normalizedQuery=query, resultCount=0,
            )
            action = {
                "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
                **_persistable_observation(observation).model_dump(
                    mode="json", by_alias=True, exclude_none=True,
                ),
            }
            return AgentMessage(
                message_id=f"message-{index}", session_id=session,
                actor_user_id=actor, role=AgentMessageRole.ASSISTANT,
                body="Completed search", resolution_type=None, sources=(),
                actions=(action,), created_at=datetime.now(UTC) + timedelta(seconds=index),
            )

        def reload(actor: str, session: str, prior: tuple[AgentMessage, ...]):
            current = AgentMessage(
                message_id="current", session_id=session, actor_user_id=actor,
                role=AgentMessageRole.USER, body=new, resolution_type=None,
                sources=(), actions=(), created_at=datetime.now(UTC) + timedelta(minutes=1),
            )
            return ContextBuilder().build_turn(
                actor_user_id=actor, session_id=session, invocation_id="reload",
                current_user_message=current, current_message=new,
                messages=prior, session_state={},
            )

        before = reload("actor-a", "session-a", (message(1, "actor-a", "session-a", old),))
        self.assertEqual(old, before.latest_search.query)
        self.assertEqual("QUERY_REPLACEMENT", customer_search_refinement(
            new, before.latest_search,
        ).kind)
        restored_edit = customer_search_refinement(
            "Search just RC Delivered Return Fixture", before.latest_search,
        )
        self.assertEqual("QUERY_REPLACEMENT", restored_edit.kind)
        self.assertEqual(new, restored_edit.value)
        after = reload("actor-a", "session-a", (
            message(1, "actor-a", "session-a", old),
            message(2, "actor-a", "session-a", new),
        ))
        self.assertEqual(new, after.latest_search.query)
        other_actor = reload("actor-b", "session-b", (
            message(1, "actor-b", "session-b", "monitor"),
        ))
        self.assertEqual("monitor", other_actor.latest_search.query)
        other_session = reload("actor-a", "session-c", (
            message(1, "actor-a", "session-c", "keyboard"),
        ))
        self.assertEqual("keyboard", other_session.latest_search.query)
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            reload("actor-b", "session-b", (message(1, "actor-a", "session-a", old),))


if __name__ == "__main__":
    unittest.main()
