from __future__ import annotations

import json
import unittest
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from msb_agent_service.agent_persistence import AgentMessage, AgentMessageRole
from msb_agent_service.marketplace_agent_v2.context_builder import ContextBuilder
from msb_agent_service.marketplace_agent_v2.schemas import (
    ActiveSkill,
    AgentContext,
    ExecutedSearchSnapshot,
    ListingAttachment,
    MarketplaceScopeResult,
    SkillSummary,
    ToolObservation,
)


NOW = datetime(2026, 9, 29, tzinfo=UTC)
ACTOR = "actor-a"
SESSION = "session-a"
CURRENT = "current-user"
SCOPE = MarketplaceScopeResult(
    scope="IN_SCOPE", confidence="HIGH", marketplaceContextUsed=True,
    reasonCode="MARKETPLACE_REQUEST",
)


def _message(
    index: int, role: AgentMessageRole, *, body: str | None = None,
    sources: tuple[dict[str, object], ...] = (),
    actions: tuple[dict[str, object], ...] = (),
    invocation_user_message_id: str | None = None,
) -> AgentMessage:
    return AgentMessage(
        message_id=f"message-{index}", session_id=SESSION, actor_user_id=ACTOR,
        role=role, body=body or f"body-{index}", resolution_type=None,
        sources=sources, actions=actions,
        created_at=NOW + timedelta(seconds=index),
        invocation_user_message_id=invocation_user_message_id,
    )


def _current() -> AgentMessage:
    return replace(_message(1000, AgentMessageRole.USER), message_id=CURRENT)


def _listing(index: int) -> ListingAttachment:
    return ListingAttachment(
        listingId=f"{index:026d}", title=f"Chair {index}",
        categoryName="Furniture", condition="GOOD", priceAmount=Decimal("25"),
        currency="USD", checkedAt=NOW, responseHash="a" * 64,
    )


def _observation(index: int) -> ToolObservation:
    return ToolObservation(
        tool="get_listing", status="SUCCEEDED", reason="LISTING_VERIFIED",
        observedAt=NOW + timedelta(seconds=index),
    )


def _decision(builder: ContextBuilder, turn, **overrides):
    args = dict(
        turn=turn, scope_result=SCOPE, pending_interaction=None,
        active_workflow=None, observations=(), contextual_refinement=None,
        available_skills=(), active_skill=None, suppress_prior_listings=False,
        commerce_listing_ids=(), exposed_tool_names=("search_listings",),
    )
    args.update(overrides)
    return builder.for_decision(**args)


class ContextBuilderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.builder = ContextBuilder()

    def _turn(self, messages: tuple[AgentMessage, ...]):
        return self.builder.build_turn(
            actor_user_id=ACTOR, session_id=SESSION, invocation_id="invocation-a",
            current_user_message=_current(), current_message="Show me chairs",
            messages=messages, session_state={},
        )

    def test_empty_context_keeps_current_user_separate(self) -> None:
        turn = self._turn(())
        self.assertEqual((), turn.recent_messages)
        self.assertEqual((), turn.source_message_ids)
        self.assertEqual("Show me chairs", _decision(self.builder, turn).to_agent_context().current_message)
        self.assertEqual(CURRENT, turn.identity.current_user_message_id)

    def test_long_window_last_twelve_latest_cards_and_observations(self) -> None:
        old = _message(2, AgentMessageRole.ASSISTANT,
                       sources=(_listing(1).model_dump(mode="json", by_alias=True),))
        newer = _message(96, AgentMessageRole.ASSISTANT, sources=tuple(
            item.model_dump(mode="json", by_alias=True)
            for item in (_listing(3), _listing(2))
        ))
        messages = [_message(i, AgentMessageRole.USER) for i in range(100)]
        messages[2] = old
        messages[96] = newer
        for i in range(85, 100):
            messages[i] = replace(messages[i], actions=(
                {"type": "MARKETPLACE_AGENT_V2_OBSERVATION", **_observation(i).model_dump(
                    mode="json", by_alias=True
                )},
            ))
        turn = self._turn(tuple(messages))
        self.assertEqual(100, turn.considered_message_count)
        self.assertEqual("body-88", turn.recent_messages[0][1])
        self.assertEqual("body-99", turn.recent_messages[-1][1])
        self.assertEqual((f"{3:026d}", f"{2:026d}"), tuple(
            item.listing_id for item in turn.referenced_listings
        ))
        self.assertEqual("message-96", turn.result_source_message_id)
        self.assertEqual(12, len(turn.prior_observations))
        self.assertEqual(NOW + timedelta(seconds=88), turn.prior_observations[0].observed_at)

    def test_out_of_scope_pair_excluded_without_discarding_cards(self) -> None:
        listing = _listing(1)
        messages = (
            _message(1, AgentMessageRole.ASSISTANT, sources=(
                listing.model_dump(mode="json", by_alias=True),
            )),
            _message(2, AgentMessageRole.USER),
            _message(3, AgentMessageRole.ASSISTANT, actions=(
                {"type": "MARKETPLACE_AGENT_V2_SCOPE", "scope": "OUT_OF_SCOPE"},
            ), invocation_user_message_id="message-2"),
        )
        turn = self._turn(messages)
        self.assertEqual((("ASSISTANT", "body-1"),), turn.recent_messages)
        self.assertEqual(2, turn.excluded_out_of_scope_count)
        self.assertEqual((listing.listing_id,), tuple(
            item.listing_id for item in turn.referenced_listings
        ))

    def test_ownership_mismatch_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            self._turn((replace(_message(1, AgentMessageRole.USER), actor_user_id="actor-b"),))
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            self._turn((replace(_message(1, AgentMessageRole.USER), session_id="session-b"),))
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            self.builder.build_turn(
                actor_user_id=ACTOR, session_id=SESSION, invocation_id="i",
                current_user_message=replace(_current(), actor_user_id="actor-b"),
                current_message="hi", messages=(), session_state={},
            )

    def test_exact_legacy_context_json_and_ephemeral_trace(self) -> None:
        listing = _listing(4)
        turn = self._turn((_message(1, AgentMessageRole.USER),
            _message(2, AgentMessageRole.ASSISTANT, sources=(
                listing.model_dump(mode="json", by_alias=True),
            )),))
        skill = SkillSummary(
            name="find-listings", description="Find products", version=1,
            surface="customer",
        )
        observations = tuple(_observation(i) for i in range(7))
        packet = _decision(
            self.builder, turn, observations=observations,
            available_skills=(skill,), commerce_listing_ids=(listing.listing_id,),
        )
        legacy = AgentContext(
            currentMessage="Show me chairs",
            recentMessages=(
                {"role": "USER", "content": "body-1"},
                {"role": "ASSISTANT", "content": "body-2"},
            ),
            referencedListingIds=(listing.listing_id,),
            referencedListings=(listing,), observations=observations[-5:],
            scopeResult=SCOPE, availableSkills=(skill,),
        )
        def payload(context: AgentContext) -> str:
            return json.dumps(context.model_dump(
                mode="json", by_alias=True, exclude_none=True,
            ), separators=(",", ":"), ensure_ascii=False)
        self.assertEqual(payload(legacy), payload(packet.to_agent_context()))
        self.assertEqual(("find-listings",), packet.trace.eligible_skill_names)
        self.assertEqual(5, packet.trace.decision_observation_count)
        for forbidden in ("trace", "sourceMessageIds", "schemaVersion", ACTOR, SESSION):
            self.assertNotIn(forbidden, payload(packet.to_agent_context()))

    def test_second_decision_skill_and_search_suppression(self) -> None:
        turn = self._turn((_message(1, AgentMessageRole.ASSISTANT, sources=(
            _listing(1).model_dump(mode="json", by_alias=True),
        )),))
        skill = ActiveSkill(
            name="find-listings", description="Find products", version=1,
            surface="customer", allowedTools=("search_listings",),
            instructions="Search only permitted products.",
        )
        new_search = ToolObservation(
            tool="search_listings", status="SUCCEEDED", reason="RESULTS_AVAILABLE",
            observedAt=NOW, attachments=(_listing(2),),
            retrievalConfidence="HIGH", presentationHint="DIRECT_RESULTS",
        )
        packet = _decision(
            self.builder, turn, observations=(new_search,), active_skill=skill,
            suppress_prior_listings=True, exposed_tool_names=(),
        )
        context = packet.to_agent_context()
        self.assertEqual((), context.referenced_listing_ids)
        self.assertEqual((), context.referenced_listings)
        self.assertEqual((), context.available_skills)
        self.assertEqual(skill, context.active_skill)
        self.assertEqual((), context.observations[0].attachments)
        self.assertIsNone(context.observations[0].retrieval_confidence)
        self.assertIn("CURRENT_SEARCH_SUPERSEDES_PRIOR_LISTINGS", packet.trace.selection_reasons)
        self.assertEqual((), packet.trace.exposed_tool_names)

    def test_latest_executed_search_survives_observation_cap_and_projects_once(self) -> None:
        observed = datetime.now(UTC)
        search = ToolObservation(
            tool="search_listings", status="SUCCEEDED", reason="RESULTS_AVAILABLE",
            observedAt=observed, expiresAt=observed + timedelta(minutes=5),
            normalizedQuery="laptop 16GB RAM", filterCategories=("MAXIMUM_PRICE",),
            appliedSearch=ExecutedSearchSnapshot(
                query="laptop 16GB RAM", maximumPrice=Decimal("1000"),
                currency="USD", limit=5, observedAt=observed,
                expiresAt=observed + timedelta(minutes=5), resultsDisplayed=True,
            ),
        )
        messages = (_message(1, AgentMessageRole.ASSISTANT, actions=({
            "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
            **search.model_dump(mode="json", by_alias=True, exclude_none=True),
        },)),) + tuple(
            _message(index, AgentMessageRole.ASSISTANT, actions=({
                "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
                **_observation(index).model_dump(
                    mode="json", by_alias=True, exclude_none=True
                ),
            },)) for index in range(2, 17)
        )

        turn = self._turn(messages)
        self.assertEqual(12, len(turn.prior_observations))
        self.assertTrue(all(item.tool != "search_listings" for item in turn.prior_observations))
        context = _decision(self.builder, turn, observations=(search,)).to_agent_context()
        self.assertEqual(Decimal("1000"), context.latest_search.maximum_price)
        self.assertTrue(context.latest_search.results_displayed)
        self.assertIsNone(context.observations[0].applied_search)
        self.assertEqual(
            "laptop 16GB RAM", context.latest_search.query,
        )
        suppressed = _decision(
            self.builder, turn, suppress_prior_listings=True,
        ).to_agent_context()
        self.assertIsNone(suppressed.latest_search)

    def test_latest_search_replaces_prior_and_failure_or_expiry_clears_projection(self) -> None:
        observed = datetime.now(UTC)
        with self.assertRaisesRegex(ValueError, "bounded aware expiry"):
            ExecutedSearchSnapshot(
                query="desk", limit=5, observedAt=observed,
                expiresAt=observed + timedelta(minutes=6),
            )

        def action(index: int, *, query: str, maximum: str | None = None,
                   status: str = "SUCCEEDED", expired: bool = False):
            instant = observed - timedelta(minutes=10) if expired else observed
            snapshot = ExecutedSearchSnapshot(
                query=query, maximumPrice=(None if maximum is None else Decimal(maximum)),
                currency=(None if maximum is None else "USD"), limit=5,
                observedAt=instant, expiresAt=instant + timedelta(minutes=5),
            ) if status == "SUCCEEDED" else None
            search = ToolObservation(
                tool="search_listings", status=status,
                reason="SEARCH_UNAVAILABLE" if status == "FAILED" else "RESULTS_AVAILABLE",
                observedAt=instant, normalizedQuery=query, appliedSearch=snapshot,
            )
            return _message(index, AgentMessageRole.ASSISTANT, actions=({
                "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
                **search.model_dump(mode="json", by_alias=True, exclude_none=True),
            },))

        laptop = action(1, query="laptop", maximum="1000")
        corrected = action(2, query="laptop", maximum="1500")
        new_product = action(3, query="desk")
        self.assertEqual(
            Decimal("1500"), self._turn((laptop, corrected)).latest_search.maximum_price,
        )
        self.assertEqual("desk", self._turn((laptop, corrected, new_product)).latest_search.query)
        self.assertIsNone(self._turn((laptop, action(4, query="desk", status="FAILED"))).latest_search)
        self.assertIsNone(self._turn((laptop, action(5, query="desk", expired=True))).latest_search)
        self.assertIsNone(self._turn((laptop, _message(
            6, AgentMessageRole.ASSISTANT, actions=({
                "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
                **ToolObservation(
                    tool="search_listings", status="SUCCEEDED", reason="RESULTS_AVAILABLE",
                    normalizedQuery="legacy desk",
                ).model_dump(mode="json", by_alias=True, exclude_none=True),
            },),
        ))).latest_search)


if __name__ == "__main__":
    unittest.main()
