"""REFINE-FIX-01B: model-first, policy-checked read-only refinements."""

from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2OrchestrationFailure, MarketplaceAgentV2Orchestrator,
    _validate_terminal_response,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.provider import _required_tool_choice
from msb_agent_service.marketplace_agent_v2.refinement import (
    customer_search_refinement,
    explicit_search_confirmation_requested,
    matches_search_refinement,
)
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext,
    ExecutedSearchSnapshot,
    MarketplaceScopeResult,
    ModelDecision,
    SearchListingsArguments,
    ToolObservation,
    ToolProposal,
)


def _snapshot(**values: object) -> ExecutedSearchSnapshot:
    observed = datetime.now(UTC)
    for name in ("minimumPrice", "maximumPrice"):
        if name in values and values[name] is not None:
            values[name] = Decimal(str(values[name]))
    return ExecutedSearchSnapshot.model_validate({
        "query": "laptop", "limit": 5, "observedAt": observed,
        "expiresAt": observed + timedelta(minutes=5), **values,
    })


def _prior(snapshot: ExecutedSearchSnapshot) -> ToolObservation:
    return ToolObservation(
        tool="search_listings", status="SUCCEEDED", reason="CATEGORY_UNAVAILABLE",
        appliedSearch=snapshot, normalizedQuery=snapshot.query, resultCount=0,
    )


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[AgentContext] = []
        self.tool_sets: list[tuple[str, ...]] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        self.tool_sets.append(tuple(tool["name"] for tool in kwargs["tools"]))
        if not self.decisions:
            raise AssertionError("Unexpected extra decision")
        decision = self.decisions.pop(0)
        if decision.content is not None and kwargs["on_text_delta"] is not None:
            await kwargs["on_text_delta"](decision.content)
        return decision

    async def close(self) -> None:
        return None


class _Registry:
    names = ("search_listings",)

    def __init__(self) -> None:
        self.arguments: list[SearchListingsArguments] = []

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return ({"name": "search_listings"},)

    async def execute(self, **kwargs: Any) -> ToolObservation:
        self.arguments.append(kwargs["arguments"])
        return ToolObservation(
            tool="search_listings", status="SUCCEEDED",
            reason="CATEGORY_UNAVAILABLE", normalizedQuery=kwargs["arguments"].query,
            resultCount=0,
        )


class _ConfirmationRegistry(_Registry):
    names = ("search_listings", "request_confirmation")

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return ({"name": "search_listings"}, {"name": "request_confirmation"})

    async def execute(self, **kwargs: Any) -> ToolObservation:
        if kwargs["tool"] != "search_listings":
            raise AssertionError("Unrequested confirmation must not execute")
        return await super().execute(**kwargs)


_LISTING_SCOPE = MarketplaceScopeResult(
    scope="IN_SCOPE", confidence="HIGH", marketplaceContextUsed=True,
    reasonCode="CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
    requiredGrounding="LISTING_DATA",
)


class Refine01BValidationTest(unittest.TestCase):
    def test_only_short_explicit_edits_of_fresh_search_are_recognized(self) -> None:
        snapshot = _snapshot(maximumPrice="1000", currency="USD")
        edit = customer_search_refinement("Actually make it under $1500.", snapshot)
        self.assertEqual("MAX_PRICE", edit.kind)
        self.assertEqual(Decimal("1500"), edit.value)
        self.assertIsNone(customer_search_refinement("Show me monitors.", snapshot))
        self.assertIsNone(customer_search_refinement("Make it better.", snapshot))
        self.assertIsNone(customer_search_refinement(
            "Ask me before searching under $1500.", snapshot,
        ))
        stale = snapshot.model_copy(update={
            "expires_at": datetime.now(UTC) - timedelta(seconds=1),
        })
        self.assertIsNone(customer_search_refinement("Under $1500.", stale))

    def test_price_and_condition_carry_only_valid_proposed_filters(self) -> None:
        snapshot = _snapshot(
            query="laptop", condition="NEW", maximumPrice="1200",
            currency="USD", city="Los Angeles",
        )
        edit = customer_search_refinement("Actually under $900.", snapshot)
        accepted = SearchListingsArguments(
            query="laptop under $900", condition="NEW", maximumPrice="900",
            currency="USD", city="Los Angeles",
        )
        self.assertTrue(matches_search_refinement(accepted, snapshot, edit))
        self.assertFalse(matches_search_refinement(
            accepted.model_copy(update={"condition": None}), snapshot, edit,
        ))
        self.assertFalse(matches_search_refinement(
            accepted.model_copy(update={"city": None}), snapshot, edit,
        ))
        self.assertFalse(matches_search_refinement(
            accepted.model_copy(update={"maximum_price": Decimal("1200")}),
            snapshot, edit,
        ))

        fresh = _snapshot(condition="NEW")
        price = customer_search_refinement("Under $1000.", fresh)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="laptop", condition="NEW", maximumPrice="1000", currency="USD",
        ), fresh, price))

        condition_edit = customer_search_refinement(
            "Only new ones.", _snapshot(maximumPrice="1000", currency="USD")
        )
        self.assertEqual("CONDITION", condition_edit.kind)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="laptop", condition="NEW", maximumPrice="1000", currency="USD",
        ), _snapshot(maximumPrice="1000", currency="USD"), condition_edit))

    def test_ram_size_and_query_only_attributes_replace_without_typed_claim(self) -> None:
        cases = (
            ("laptop 16GB RAM", "Actually 32GB.", "laptop 32GB RAM", "laptop 16GB RAM 32GB RAM"),
            ("monitor 27 inch", "32 inch.", "monitor 32 inch", "monitor 27 inch 32 inch"),
            ("keyboard", "Wireless only.", "wireless keyboard", "wireless mouse"),
            ("wireless keyboard", "No RGB.", "wireless keyboard no RGB", "wireless mouse no RGB"),
        )
        for old, message, correct, wrong in cases:
            with self.subTest(message=message):
                snapshot = _snapshot(query=old, maximumPrice="100", currency="USD")
                edit = customer_search_refinement(message, snapshot)
                self.assertIsNotNone(edit)
                self.assertTrue(matches_search_refinement(SearchListingsArguments(
                    query=correct, maximumPrice="100", currency="USD",
                ), snapshot, edit))
                self.assertFalse(matches_search_refinement(SearchListingsArguments(
                    query=wrong, maximumPrice="100", currency="USD",
                ), snapshot, edit))

    def test_equivalent_word_order_and_attribute_phrasing_still_preserve_edits(self) -> None:
        keyboard = _snapshot(
            query="keyboard wireless", maximumPrice="100", currency="USD",
        )
        no_rgb = customer_search_refinement("No RGB.", keyboard)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="wireless keyboard without RGB", maximumPrice="100",
            currency="USD",
        ), keyboard, no_rgb))
        self.assertFalse(matches_search_refinement(SearchListingsArguments(
            query="keyboard without RGB", maximumPrice="100", currency="USD",
        ), keyboard, no_rgb))

        monitor = _snapshot(query="27 inch monitor")
        size = customer_search_refinement("32 inch.", monitor)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="monitor 32-inch", currency="USD",
        ), monitor, size))
        self.assertFalse(matches_search_refinement(SearchListingsArguments(
            query="monitor 32-inch", currency="EUR",
        ), monitor, size))

        priced_monitor = _snapshot(
            query="27 inch monitor", maximumPrice="300", currency="USD",
        )
        price = customer_search_refinement("Under $250.", priced_monitor)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="monitor 27-inch", maximumPrice="250", currency="USD",
        ), priced_monitor, price))

    def test_query_attribute_addition_preserves_executed_typed_filters(self) -> None:
        cases = (
            ("monitor", "27 inch.", "27 inch monitor", "300", None, None),
            ("laptop", "At least 16GB RAM.", "laptop 16GB RAM", "1000", None, None),
            ("keyboard", "Wireless only.", "wireless keyboard", "100", None, None),
            ("keyboard", "No RGB.", "keyboard no RGB", "100", None, None),
            ("monitor", "27 inch.", "27 inch monitor", "300", "NEW", None),
            ("monitor", "27 inch.", "27 inch monitor", "300", None, "San Jose"),
        )
        for query, message, proposed, price, condition, city in cases:
            with self.subTest(message=message, condition=condition, city=city):
                snapshot = _snapshot(
                    query=query, maximumPrice=price, currency="USD",
                    condition=condition, city=city,
                )
                edit = customer_search_refinement(message, snapshot)
                self.assertIsNotNone(edit)
                valid = SearchListingsArguments(
                    query=proposed, maximumPrice=price, currency="USD",
                    condition=condition, city=city,
                )
                self.assertTrue(matches_search_refinement(valid, snapshot, edit))
                self.assertFalse(matches_search_refinement(
                    valid.model_copy(update={"maximum_price": None}), snapshot, edit,
                ))

        size_first = _snapshot(query="27 inch monitor")
        price_edit = customer_search_refinement("Under $300.", size_first)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="27 inch monitor", maximumPrice=300, currency="USD",
        ), size_first, price_edit))
        replace = _snapshot(
            query="27 inch monitor", maximumPrice="300", currency="USD",
        )
        replace_edit = customer_search_refinement("Actually 32 inch.", replace)
        self.assertTrue(matches_search_refinement(SearchListingsArguments(
            query="32 inch monitor", maximumPrice=300, currency="USD",
        ), replace, replace_edit))
        self.assertFalse(matches_search_refinement(SearchListingsArguments(
            query="27 inch 32 inch monitor", maximumPrice=300, currency="USD",
        ), replace, replace_edit))

    def test_structured_repair_contains_only_bounded_canonical_facts(self) -> None:
        snapshot = _snapshot(
            query="monitor", maximumPrice="300", currency="USD",
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=snapshot,
            search_refinement=customer_search_refinement("27 inch.", snapshot),
        )
        for name, arguments, mismatch in (
            ("lost-price", {"query": "27 inch monitor"}, "PRICE_FILTER_CHANGED"),
            ("wrong-core", {"query": "27 inch television", "maximumPrice": 300,
                            "currency": "USD"}, "QUERY_TERMS_CHANGED"),
            ("missing-edit", {"query": "monitor", "maximumPrice": 300,
                              "currency": "USD"}, "EDIT_NOT_APPLIED"),
        ):
            with self.subTest(name=name):
                _, rejection = policy.validate(ToolProposal(
                    callId=name, tool="search_listings", arguments=arguments,
                ), step=1)
                self.assertEqual("SEARCH_REFINEMENT_MISMATCH", rejection.reason)
                repair = rejection.refinement_repair
                self.assertEqual(mismatch, repair.mismatch)
                self.assertEqual("monitor", repair.current_search.query)
                self.assertEqual(Decimal("300"), repair.current_search.maximum_price)
                self.assertEqual("QUERY_ATTRIBUTE", repair.requested_edit.field)
                self.assertEqual("ADD", repair.requested_edit.operation)
                self.assertEqual("27 inch", repair.requested_edit.value)
                self.assertTrue(repair.preserve_unchanged_filters)
                encoded = rejection.model_dump(mode="json", by_alias=True)
                self.assertEqual(
                    {"type", "mismatch", "currentSearch", "requestedEdit",
                     "preserveProductCore", "preserveUnchangedFilters",
                     "applyRequestedEdit"},
                    set(encoded["refinementRepair"]),
                )
                self.assertNotIn("arguments", str(encoded))
                self.assertNotIn("provider", str(encoded).casefold())

        legacy = ToolObservation.model_validate({
            "tool": "search_listings", "status": "REJECTED",
            "reason": "SEARCH_REFINEMENT_MISMATCH",
            "refinementRepair": "PRICE_FILTER_CHANGED",
        })
        self.assertEqual("PRICE_FILTER_CHANGED", legacy.refinement_repair)

    def test_policy_rejects_mismatch_and_unrequested_confirmation(self) -> None:
        snapshot = _snapshot(maximumPrice="1000", currency="USD")
        edit = customer_search_refinement("Actually under $1500.", snapshot)
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=snapshot,
            search_refinement=edit,
        )
        _, wrong = policy.validate(ToolProposal(
            callId="wrong", tool="search_listings",
            arguments={"query": "laptop", "maximumPrice": "1000", "currency": "USD"},
        ), step=1)
        self.assertEqual("SEARCH_REFINEMENT_MISMATCH", wrong.reason)
        self.assertEqual("EDIT_NOT_APPLIED", wrong.refinement_repair.mismatch)
        self.assertEqual(
            "EDIT_NOT_APPLIED",
            wrong.model_dump(by_alias=True)["refinementRepair"]["mismatch"],
        )

        grounded = _snapshot(query="27 inch monitor")
        grounded_policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=grounded,
            search_refinement=customer_search_refinement("Under $300.", grounded),
        )
        _, dropped_size = grounded_policy.validate(ToolProposal(
            callId="dropped-size", tool="search_listings",
            arguments={"query": "monitor", "maximumPrice": 300, "currency": "USD"},
        ), step=1)
        self.assertEqual("QUERY_TERMS_CHANGED", dropped_size.refinement_repair.mismatch)
        _, guessed_category = grounded_policy.validate(ToolProposal(
            callId="guessed-category", tool="search_listings",
            arguments={"query": "27 inch monitor", "categoryName": "Electronics",
                       "maximumPrice": 300, "currency": "USD"},
        ), step=2)
        self.assertEqual("UNCHANGED_FILTER_CHANGED", guessed_category.refinement_repair.mismatch)
        sized = _snapshot(
            query="27 inch monitor", maximumPrice="300", currency="USD",
        )
        sized_policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=sized,
            search_refinement=customer_search_refinement("32 inch.", sized),
        )
        _, lost_price = sized_policy.validate(ToolProposal(
            callId="lost-price", tool="search_listings",
            arguments={"query": "32 inch monitor", "currency": "USD"},
        ), step=1)
        self.assertEqual("PRICE_FILTER_CHANGED", lost_price.refinement_repair.mismatch)
        accepted, rejection = policy.validate(ToolProposal(
            callId="correct", tool="search_listings",
            arguments={"query": "laptop", "maximumPrice": "1500", "currency": "USD"},
        ), step=2)
        self.assertIsNone(rejection)
        self.assertEqual(Decimal("1500"), accepted.maximum_price)

        proposal = ToolProposal(
            callId="confirm", tool="request_confirmation",
            arguments={"query": "laptop", "maximumPrice": "1500", "currency": "USD",
                       "type": "CONFIRM_ACTION", "action": "RUN_REFINED_SEARCH"},
        )
        _, unrequested = policy.validate(proposal, step=3)
        self.assertEqual("CONFIRMATION_INTENT_REQUIRED", unrequested.reason)
        self.assertTrue(explicit_search_confirmation_requested(
            "Ask me before searching under $1500."
        ))
        self.assertFalse(explicit_search_confirmation_requested("Under $1500."))
        authorized, rejection = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), latest_search=snapshot,
            explicit_search_confirmation=True,
        ).validate(proposal, step=1)
        self.assertIsNone(rejection)
        self.assertEqual(Decimal("1500"), authorized.maximum_price)

    def test_first_model_choice_is_auto_then_rejection_requires_search(self) -> None:
        snapshot = _snapshot(maximumPrice="1000", currency="USD")
        first = AgentContext(currentMessage="Actually under $1500.", latestSearch=snapshot)
        self.assertEqual("auto", _required_tool_choice(first, ("search_listings",)))
        recovery = first.model_copy(update={
            "observations": (ToolObservation(
                tool="DIRECT_RESPONSE", status="REJECTED",
                reason="SEARCH_REFINEMENT_TOOL_REQUIRED",
            ),),
        })
        self.assertEqual(
            {"type": "function", "name": "search_listings"},
            _required_tool_choice(recovery, ("search_listings",)),
        )

    def test_query_only_attribute_cannot_be_claimed_verified(self) -> None:
        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure):
            _validate_terminal_response(
                current_message="Actually 32GB.",
                content="All these listings have verified 32GB RAM.",
                active_recommendations=(), current_attachments=(),
                observations=(), has_waiting_interaction=False,
                query_only_refinement=True,
            )
        _validate_terminal_response(
            current_message="Actually 32GB.",
            content="I searched for 32GB RAM, but found no current matches.",
            active_recommendations=(), current_attachments=(),
            observations=(), has_waiting_interaction=False,
            query_only_refinement=True,
        )


class Refine01BOrchestratorTest(unittest.IsolatedAsyncioTestCase):
    async def test_price_first_size_add_repairs_on_second_model_decision(self) -> None:
        snapshot = _snapshot(
            query="monitor", maximumPrice="300", currency="USD",
        )
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="lost-price", tool="search_listings",
                arguments={"query": "27 inch monitor"},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="corrected", tool="search_listings",
                arguments={"query": "27 inch monitor", "maximumPrice": 300,
                           "currency": "USD"},
            )),
            ModelDecision(content="No current matches were found."),
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="27 inch.", recent_messages=(),
            referenced_listings=(), prior_observations=(_prior(snapshot),),
            scope_result=_LISTING_SCOPE, correlation_id="refine-01b-1-repair",
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, len(registry.arguments))
        self.assertEqual(Decimal("300"), registry.arguments[0].maximum_price)
        self.assertEqual(("search_listings",), model.tool_sets[1])
        repair = model.contexts[1].observations[-1].refinement_repair
        self.assertEqual("PRICE_FILTER_CHANGED", repair.mismatch)
        self.assertEqual("monitor", repair.current_search.query)
        self.assertEqual("27 inch", repair.requested_edit.value)

    async def test_repeated_bad_proposals_never_execute_search(self) -> None:
        snapshot = _snapshot(
            query="monitor", maximumPrice="300", currency="USD",
        )
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId=f"bad-{index}", tool="search_listings",
                arguments={"query": "27 inch monitor"},
            )) for index in range(5)
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="27 inch.", recent_messages=(),
            referenced_listings=(), prior_observations=(_prior(snapshot),),
            scope_result=_LISTING_SCOPE, correlation_id="refine-01b-1-bounded",
        )
        self.assertEqual(5, result.decision_count)
        self.assertEqual(5, len(model.contexts))
        self.assertEqual(0, len(registry.arguments))
        self.assertTrue(all(
            context.observations[-1].reason == "SEARCH_REFINEMENT_MISMATCH"
            for context in model.contexts[1:]
        ))

    async def test_permission_prose_is_not_streamed_and_search_runs_once(self) -> None:
        snapshot = _snapshot(maximumPrice="1000", currency="USD")
        model = _Model([
            ModelDecision(content="Would you like me to search under $1500?"),
            ModelDecision(toolProposal=ToolProposal(
                callId="correct-price", tool="search_listings",
                arguments={
                    "query": "laptop", "maximumPrice": "1500", "currency": "USD",
                },
            )),
            ModelDecision(content="I couldn't find any current laptop listings."),
        ])
        registry = _Registry()
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Actually make it under $1500.",
            recent_messages=(), referenced_listings=(),
            prior_observations=(_prior(snapshot),), scope_result=_LISTING_SCOPE,
            correlation_id="refine-01b-price", text_delta=capture,
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, len(registry.arguments))
        self.assertEqual(Decimal("1500"), registry.arguments[0].maximum_price)
        self.assertEqual("SEARCH_REFINEMENT_TOOL_REQUIRED", model.contexts[1].observations[-1].reason)
        self.assertNotIn("Would you like", "".join(deltas))
        self.assertEqual(result.message.content, "".join(deltas))
        self.assertEqual(1, len(result.message.tool_activity))

    async def test_wrong_model_search_is_rejected_before_one_correct_execution(self) -> None:
        snapshot = _snapshot(condition="NEW", maximumPrice="1000", currency="USD")
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="dropped-condition", tool="search_listings",
                arguments={
                    "query": "laptop", "maximumPrice": "1500", "currency": "USD",
                },
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="correct-condition", tool="search_listings",
                arguments={
                    "query": "laptop", "condition": "NEW", "maximumPrice": "1500",
                    "currency": "USD",
                },
            )),
            ModelDecision(content="No current matches were found."),
        ])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Actually under $1500.", recent_messages=(),
            referenced_listings=(), prior_observations=(_prior(snapshot),),
            scope_result=_LISTING_SCOPE, correlation_id="refine-01b-filter-carry",
        )
        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, len(registry.arguments))
        self.assertEqual("NEW", registry.arguments[0].condition)
        self.assertEqual("SEARCH_REFINEMENT_MISMATCH", model.contexts[1].observations[-1].reason)

    async def test_unrequested_confirmation_is_rejected_before_search(self) -> None:
        snapshot = _snapshot(maximumPrice="1000", currency="USD")
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="unrequested-confirmation", tool="request_confirmation",
                arguments={
                    "query": "laptop", "maximumPrice": "1500", "currency": "USD",
                    "type": "CONFIRM_ACTION", "action": "RUN_REFINED_SEARCH",
                },
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="search-now", tool="search_listings",
                arguments={
                    "query": "laptop", "maximumPrice": "1500", "currency": "USD",
                },
            )),
            ModelDecision(content="I found no current matches."),
        ])
        registry = _ConfirmationRegistry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Actually under $1500.", recent_messages=(),
            referenced_listings=(), prior_observations=(_prior(snapshot),),
            scope_result=_LISTING_SCOPE, correlation_id="refine-01b-no-confirmation",
        )
        self.assertEqual(1, len(registry.arguments))
        self.assertIsNone(result.pending_interaction)
        self.assertEqual("CONFIRMATION_INTENT_REQUIRED", model.contexts[1].observations[-1].reason)

    async def test_ambiguous_reply_can_clarify_without_a_forced_search(self) -> None:
        model = _Model([ModelDecision(content="Which part should I improve?")])
        registry = _Registry()
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Make it better.", recent_messages=(),
            referenced_listings=(), prior_observations=(_prior(_snapshot()),),
            scope_result=_LISTING_SCOPE, correlation_id="refine-01b-ambiguous",
        )
        self.assertEqual(1, result.decision_count)
        self.assertEqual(0, len(registry.arguments))


if __name__ == "__main__":
    unittest.main()
