"""REFINE-FIX-01A acceptance of executed-search state, not model planning."""

from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from msb_agent_service.agent_persistence import AgentMessage, AgentMessageRole
from msb_agent_service.marketplace_discovery import (
    DiscoverySearchFacets,
    DiscoverySearchPage,
    DiscoverySearchSummary,
)
from msb_agent_service.marketplace_agent_v2.context_builder import ContextBuilder
from msb_agent_service.marketplace_agent_v2.schemas import (
    SearchListingsArguments,
    ToolObservation,
)
from msb_agent_service.marketplace_agent_v2.service import _persistable_observation
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


class _EmptyProduct:
    def __init__(self) -> None:
        self.requests = []

    async def search_individual(self, **kwargs: object) -> DiscoverySearchPage:
        self.requests.append(kwargs["request"])
        return DiscoverySearchPage(
            data=(), page={"hasMore": False},
            summary=DiscoverySearchSummary(
                normalizedCategory="fixture search", totalMatches=0,
                relevantMatchCount=0, retrievalConfidence="LOW",
                reason="CATEGORY_UNAVAILABLE", facets=DiscoverySearchFacets(),
            ),
        )


class Refine01ASnapshotAcceptanceTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.product = _EmptyProduct()
        self.registry = MarketplaceAgentV2ToolRegistry(self.product)
        self.builder = ContextBuilder()

    async def _search_action(self, **values: object) -> dict[str, object]:
        observation = await self.registry.execute(
            tool="search_listings", arguments=SearchListingsArguments(**values),
            actor_user_id="actor-a", correlation_id="refine-01a-acceptance",
            activity=None,
        )
        self.assertEqual("SUCCEEDED", observation.status)
        self.assertIsNotNone(observation.applied_search)
        return {
            "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
            **_persistable_observation(observation).model_dump(
                mode="json", by_alias=True, exclude_none=True,
            ),
        }

    def _message(
        self, index: int, action: dict[str, object], *,
        actor: str = "actor-a", session: str = "session-a",
    ) -> AgentMessage:
        return AgentMessage(
            message_id=f"message-{index}", session_id=session,
            actor_user_id=actor, role=AgentMessageRole.ASSISTANT,
            body="Completed search", resolution_type=None,
            sources=(), actions=(action,),
            created_at=datetime.now(UTC) + timedelta(seconds=index),
        )

    def _turn(
        self, messages: tuple[AgentMessage, ...], *,
        actor: str = "actor-a", session: str = "session-a",
    ):
        current = AgentMessage(
            message_id="current", session_id=session, actor_user_id=actor,
            role=AgentMessageRole.USER, body="Refine", resolution_type=None,
            sources=(), actions=(), created_at=datetime.now(UTC) + timedelta(minutes=1),
        )
        return self.builder.build_turn(
            actor_user_id=actor, session_id=session, invocation_id="invocation",
            current_user_message=current, current_message="Refine",
            messages=messages, session_state={},
        )

    async def test_executed_filter_replacement_matrix_matches_product_request(self) -> None:
        # The model-selected proposals are explicit here: 01A records them but
        # does not decide how to compose a later customer correction.
        cases = (
            (
                "price",
                {"query": "laptop under $1000", "maximumPrice": "1000", "currency": "USD"},
                {"query": "laptop under $1500", "maximumPrice": "1500", "currency": "USD"},
                {"query": "laptop", "maximum_price": Decimal("1500"), "condition": None},
            ),
            (
                "condition carry-forward",
                {"query": "new laptops", "condition": "NEW"},
                {"query": "new laptops under $1000", "condition": "NEW",
                 "maximumPrice": "1000", "currency": "USD"},
                {"query": "new laptops", "maximum_price": Decimal("1000"),
                 "condition": "NEW"},
            ),
            (
                "RAM query replacement",
                {"query": "laptops with at least 16GB RAM"},
                {"query": "laptops with at least 32GB RAM"},
                {"query": "laptops with at least 32GB RAM", "maximum_price": None,
                 "condition": None},
            ),
            (
                "condition plus price correction",
                {"query": "new laptops under $1200", "condition": "NEW",
                 "maximumPrice": "1200", "currency": "USD"},
                {"query": "new laptops under $900", "condition": "NEW",
                 "maximumPrice": "900", "currency": "USD"},
                {"query": "new laptops", "maximum_price": Decimal("900"),
                 "condition": "NEW"},
            ),
            (
                "new keyboard product",
                {"query": "laptop under $1000", "maximumPrice": "1000", "currency": "USD"},
                {"query": "wireless keyboards"},
                {"query": "wireless keyboards", "maximum_price": None,
                 "condition": None},
            ),
            (
                "new monitor product",
                {"query": "keyboards under $100", "maximumPrice": "100", "currency": "USD"},
                {"query": "monitors"},
                {"query": "monitors", "maximum_price": None, "condition": None},
            ),
            (
                "location carry-forward",
                {"query": "desks under $300", "maximumPrice": "300", "currency": "USD",
                 "city": "Los Angeles"},
                {"query": "desks under $500", "maximumPrice": "500", "currency": "USD",
                 "city": "Los Angeles"},
                {"query": "desks", "maximum_price": Decimal("500"),
                 "condition": None, "city": "Los Angeles"},
            ),
        )
        for label, initial, corrected, expected in cases:
            with self.subTest(label=label):
                self.product.requests.clear()
                first = await self._search_action(**initial)
                second = await self._search_action(**corrected)
                turn = self._turn((self._message(1, first), self._message(2, second)))
                snapshot = turn.latest_search
                self.assertIsNotNone(snapshot)
                for field, value in expected.items():
                    self.assertEqual(value, getattr(snapshot, field))
                self.assertEqual(snapshot.query, self.product.requests[-1].q)
                self.assertEqual(snapshot.maximum_price, self.product.requests[-1].max_price)
                self.assertEqual(snapshot.condition, self.product.requests[-1].condition)
                self.assertEqual(snapshot.city, self.product.requests[-1].city)
                self.assertEqual(2, len(self.product.requests))
                if label == "RAM query replacement":
                    self.assertNotIn("16GB", snapshot.query)
                    self.assertIn("32GB", snapshot.query)

    async def test_failed_and_expired_search_do_not_project_old_state(self) -> None:
        successful = await self._search_action(
            query="laptop under $1000", maximumPrice="1000", currency="USD",
        )
        failure = ToolObservation(
            tool="search_listings", status="FAILED", reason="SEARCH_UNAVAILABLE",
            normalizedQuery="desk",
        )
        failed_action = {
            "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
            **failure.model_dump(mode="json", by_alias=True, exclude_none=True),
        }
        self.assertIsNone(self._turn((
            self._message(1, successful), self._message(2, failed_action),
        )).latest_search)

        old = dict(successful)
        old_snapshot = dict(old["appliedSearch"])
        old_snapshot["observedAt"] = (datetime.now(UTC) - timedelta(minutes=6)).isoformat()
        old_snapshot["expiresAt"] = (datetime.now(UTC) - timedelta(minutes=1)).isoformat()
        old["appliedSearch"] = old_snapshot
        self.assertIsNone(self._turn((self._message(1, old),)).latest_search)
        self.assertEqual("1000", successful["appliedSearch"]["maximumPrice"])

        legacy = {key: value for key, value in successful.items() if key != "appliedSearch"}
        legacy_turn = self._turn((self._message(1, legacy),))
        self.assertIsNone(legacy_turn.latest_search)
        self.assertEqual("SUCCEEDED", legacy_turn.prior_observations[0].status)

    async def test_actor_and_session_scoped_restoration(self) -> None:
        laptop = await self._search_action(
            query="laptop under $1000", maximumPrice="1000", currency="USD",
        )
        keyboard = await self._search_action(query="keyboards")
        a = self._message(1, laptop, actor="actor-a", session="session-a")
        b = self._message(1, keyboard, actor="actor-b", session="session-b")
        same_actor_other_session = self._message(
            1, keyboard, actor="actor-a", session="session-c",
        )
        self.assertEqual(Decimal("1000"), self._turn((a,)).latest_search.maximum_price)
        self.assertEqual("keyboards", self._turn(
            (b,), actor="actor-b", session="session-b",
        ).latest_search.query)
        self.assertIsNone(self._turn(
            (same_actor_other_session,), session="session-c",
        ).latest_search.maximum_price)
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            self._turn((a,), actor="actor-b", session="session-b")
        with self.assertRaisesRegex(ValueError, "ownership mismatch"):
            self._turn((a,), actor="actor-a", session="session-c")


if __name__ == "__main__":
    unittest.main()
