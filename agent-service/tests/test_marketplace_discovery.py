from __future__ import annotations

import unittest
import asyncio
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Sequence

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field, ValidationError

from msb_agent_service.config import (
    AgentPersistenceSettings,
    DiscoveryApiSettings,
    Settings,
)
from msb_agent_service.api import create_app
from msb_agent_service.customer_service_api import AgentApiError, AgentApiErrorCode
from msb_agent_service.discovery_api import (
    DiscoveryApiError,
    DiscoveryApiErrorCode,
    DiscoverySessionResponse,
)
from msb_agent_service.marketplace_discovery import (
    CheckedListing,
    DiscoveryRecommendation,
    DiscoveryPreferenceState,
    DiscoveryLimits,
    DiscoverySearchRequest,
    DiscoveryTurnResult,
    MarketplaceDiscoveryOrchestrator,
    PublicIndividualListing,
    PublicIndividualSearchPage,
    _PageMetadata,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
CATEGORY_1 = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
CATEGORY_2 = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
LISTINGS = (
    "01ARZ3NDEKTSV4RRFFQ69G5FB1",
    "01ARZ3NDEKTSV4RRFFQ69G5FB2",
    "01ARZ3NDEKTSV4RRFFQ69G5FB3",
)
NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)


class ScriptedChatModel(BaseChatModel):
    """Implements only the deterministic local tool-calling behavior under test."""

    responses: list[AIMessage] = Field(default_factory=list, exclude=True)
    invocation_count: int = 0
    bound_tool_names: tuple[str, ...] = ()

    @property
    def _llm_type(self) -> str:
        return "offline-scripted-discovery"

    def bind_tools(
        self,
        tools: Sequence[Any],
        *,
        tool_choice: str | None = None,
        **_: Any,
    ) -> BaseChatModel:
        self.bound_tool_names = tuple(
            getattr(tool, "name", str(tool)) for tool in tools
        )
        return self

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any | None = None,
        **_: Any,
    ) -> ChatResult:
        del messages
        del stop, run_manager
        if self.invocation_count >= len(self.responses):
            raise AssertionError("Unexpected model call")
        response = self.responses[self.invocation_count]
        self.invocation_count += 1
        return ChatResult(generations=[ChatGeneration(message=response)])


class FakeProductTool:
    def __init__(self, *, removed: set[str] | None = None) -> None:
        self.removed = removed or set()
        self.search_calls = 0
        self.detail_calls = 0
        self.listings = {
            listing_id: _listing(
                listing_id,
                category=CATEGORY_1 if index < 2 else CATEGORY_2,
                title=f"Comfort pillow {index + 1}",
            )
            for index, listing_id in enumerate(LISTINGS)
        }

    async def search_individual(self, **kwargs: Any) -> PublicIndividualSearchPage:
        self.search_calls += 1
        request = kwargs["request"]
        self.assert_request(request)
        return PublicIndividualSearchPage(
            data=tuple(self.listings.values()),
            page=_PageMetadata(nextCursor=None, hasMore=False),
        )

    async def get_listing(self, **kwargs: Any) -> CheckedListing | None:
        self.detail_calls += 1
        listing_id = kwargs["listing_id"]
        if listing_id in self.removed:
            return None
        listing = self.listings[listing_id]
        return CheckedListing(
            listing=listing,
            checkedAt=NOW,
            responseHash=f"{self.detail_calls:064x}",
        )

    @staticmethod
    def assert_request(request: DiscoverySearchRequest) -> None:
        if request.limit > 20 or request.city != "Irvine":
            raise AssertionError("Unexpected search contract")


class SlowProductTool(FakeProductTool):
    async def search_individual(self, **kwargs: Any) -> PublicIndividualSearchPage:
        await asyncio.sleep(1)
        return await super().search_individual(**kwargs)


class FakeIdentityClient:
    def __init__(self) -> None:
        self.calls = 0

    async def resolve(self, authorization: str | None, correlation_id: str) -> str:
        del correlation_id
        self.calls += 1
        if authorization == "Bearer discovery-user":
            return ACTOR
        raise AgentApiError(
            AgentApiErrorCode.UNAUTHORIZED,
            401,
            "Authentication is required.",
        )


class FakePersistenceRepository:
    def __init__(self) -> None:
        self.closed = False

    async def validate_schema(self) -> None:
        return None

    async def close(self) -> None:
        self.closed = True


class FakeDiscoveryService:
    def __init__(self) -> None:
        self.create_calls = 0
        self.actor_user_id: str | None = None

    async def create_session(
        self,
        *,
        actor_user_id: str,
        new_search: bool,
    ) -> DiscoverySessionResponse:
        self.create_calls += 1
        self.actor_user_id = actor_user_id
        return DiscoverySessionResponse(
            id=SESSION,
            sessionType="MARKETPLACE_DISCOVERY",
            status="OPEN",
            preferenceState=DiscoveryPreferenceState(),
            preferenceVersion=0,
            clarificationTurnCount=0,
            clarificationQuestionCount=0,
            createdAt=NOW,
            updatedAt=NOW,
        )

    async def get_session(self, **kwargs: Any) -> DiscoverySessionResponse:
        if kwargs["actor_user_id"] != ACTOR:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.SESSION_NOT_FOUND,
                404,
                "Discovery session was not found.",
            )
        return await self.create_session(
            actor_user_id=kwargs["actor_user_id"],
            new_search=False,
        )


def _listing(
    listing_id: str,
    *,
    category: str,
    title: str,
) -> PublicIndividualListing:
    return PublicIndividualListing.model_validate(
        {
            "id": listing_id,
            "sellerType": "INDIVIDUAL",
            "sellerDisplayName": "Marketplace seller",
            "sellerAvatarUrl": None,
            "storeId": None,
            "storeSlug": None,
            "storeName": None,
            "businessVerified": False,
            "categoryId": category,
            "categorySlug": "comfort",
            "categoryName": "Comfort",
            "title": title,
            "description": "A public listing for an ordinary comfort product.",
            "condition": "NEW",
            "conditionNotes": None,
            "priceAmount": "25.00",
            "currency": "USD",
            "negotiable": False,
            "quantity": 1,
            "publicCity": "Irvine",
            "publicRegion": "Orange",
            "publishedAt": NOW.isoformat(),
            "transactionNotice": "Payment and delivery are arranged directly.",
            "visitCount": 0,
            "likeCount": 0,
            "images": [],
        }
    )


def _script() -> list[AIMessage]:
    return [
        AIMessage(
            content="",
            tool_calls=[
                {
                    "name": "SEARCH_INDIVIDUAL",
                    "args": {
                        "q": "sleep pillow",
                        "city": "Irvine",
                        "limit": 20,
                    },
                    "id": "search-1",
                    "type": "tool_call",
                }
            ],
        ),
        AIMessage(
            content="",
            tool_calls=[
                {
                    "name": "GET_LISTING",
                    "args": {"listing_id": listing_id},
                    "id": f"detail-{index}",
                    "type": "tool_call",
                }
                for index, listing_id in enumerate(LISTINGS, 1)
            ],
        ),
        AIMessage(
            content="",
            tool_calls=[
                {
                    "name": "DiscoveryTurnResult",
                    "args": {
                        "outcome": "RECOMMEND",
                        "message": "These current listings match the selected filters.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Matches the public city and product type.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                    "id": "structured-1",
                    "type": "tool_call",
                }
            ],
        ),
    ]


class MarketplaceDiscoveryTest(unittest.IsolatedAsyncioTestCase):
    async def test_react_recommendations_are_detail_checked_and_grounded(self) -> None:
        model = ScriptedChatModel(responses=_script())
        product = FakeProductTool()
        orchestrator = MarketplaceDiscoveryOrchestrator(model, product)

        run = await orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Help me find something comfortable for sleep.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-test-1",
        )

        self.assertEqual(
            "RECOMMEND",
            run.response.outcome,
            run.response.model_dump(mode="json", by_alias=True),
        )
        self.assertEqual(3, len(run.response.recommendations))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)
        self.assertEqual(4, len(run.audits))
        self.assertEqual(3, model.invocation_count)
        self.assertIn("SEARCH_INDIVIDUAL", model.bound_tool_names)
        self.assertIn("GET_LISTING", model.bound_tool_names)
        for recommendation in run.response.recommendations:
            self.assertEqual("Irvine", recommendation.public_city)
            self.assertEqual(64, len(recommendation.provenance.response_hash))
        serialized = run.response.model_dump_json(by_alias=True)
        self.assertNotIn("reasoning", serialized.casefold())
        self.assertNotIn("chain-of-thought", serialized.casefold())

    async def test_removed_detail_is_rejected_before_recommendation(self) -> None:
        product = FakeProductTool(removed={LISTINGS[2]})
        run = await MarketplaceDiscoveryOrchestrator(
            ScriptedChatModel(responses=_script()),
            product,
        ).run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find sleep pillows in Irvine.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-test-2",
        )
        self.assertEqual("NO_RESULTS", run.response.outcome)
        self.assertEqual(0, len(run.response.recommendations))

    async def test_medical_claim_request_refuses_without_product_or_model(self) -> None:
        model = ScriptedChatModel(responses=[])
        product = FakeProductTool()
        run = await MarketplaceDiscoveryOrchestrator(model, product).run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a pillow that will diagnose and cure my insomnia.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-test-3",
        )
        self.assertEqual("REFUSE", run.response.outcome)
        self.assertEqual(0, model.invocation_count)
        self.assertEqual(0, product.search_calls)
        self.assertEqual(Decimal("0"), run.response.estimated_cost)

    async def test_open_ended_sleep_help_asks_observation_based_clarification(self) -> None:
        model = ScriptedChatModel(
            responses=[
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "DiscoveryTurnResult",
                            "args": {
                                "outcome": "ASK_CLARIFY",
                                "message": "I need one location preference.",
                                "clarificationQuestions": [
                                    "Which city or county should I search?"
                                ],
                                "observedAmbiguities": ["MISSING_LOCATION"],
                                "selections": [],
                            },
                            "id": "structured-clarify",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        )
        product = FakeProductTool()
        run = await MarketplaceDiscoveryOrchestrator(model, product).run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="I need something to make sleeping more comfortable.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-clarify-1",
        )
        self.assertEqual("ASK_CLARIFY", run.response.outcome)
        self.assertEqual(1, len(run.response.questions))
        self.assertEqual(0, product.search_calls)
        self.assertEqual(0, run.response.input_tokens)
        self.assertEqual(0, run.response.output_tokens)

    async def test_clarification_budget_exhaustion_converts_to_no_results(self) -> None:
        model = ScriptedChatModel(
            responses=[
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "DiscoveryTurnResult",
                            "args": {
                                "outcome": "ASK_CLARIFY",
                                "message": "Another question.",
                                "clarificationQuestions": ["What budget?"],
                                "observedAmbiguities": ["MISSING_BUDGET"],
                                "selections": [],
                            },
                            "id": "structured-exhausted",
                            "type": "tool_call",
                        }
                    ],
                )
            ]
        )
        run = await MarketplaceDiscoveryOrchestrator(
            model,
            FakeProductTool(),
        ).run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Please keep looking.",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=3,
            clarification_question_count=5,
            history=(),
            correlation_id="disc-clarify-2",
        )
        self.assertEqual("NO_RESULTS", run.response.outcome)
        self.assertEqual((), run.response.questions)

    async def test_tool_call_limit_stops_third_search(self) -> None:
        repeated_search = AIMessage(
            content="",
            tool_calls=[
                {
                    "name": "SEARCH_INDIVIDUAL",
                    "args": {"q": "pillow", "city": "Irvine", "limit": 20},
                    "id": "search-limit",
                    "type": "tool_call",
                }
            ],
        )
        model = ScriptedChatModel(
            responses=[repeated_search, repeated_search, repeated_search]
        )
        product = FakeProductTool()
        with self.assertRaises(Exception):
            await MarketplaceDiscoveryOrchestrator(model, product).run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Keep searching.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-limit-1",
            )
        self.assertLessEqual(product.search_calls, 2)

    async def test_model_call_limit_stops_before_third_model_call(self) -> None:
        responses = [
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "SEARCH_INDIVIDUAL",
                        "args": {
                            "q": "pillow",
                            "city": "Irvine",
                            "limit": 20,
                        },
                        "id": f"model-limit-{index}",
                        "type": "tool_call",
                    }
                ],
            )
            for index in range(3)
        ]
        model = ScriptedChatModel(responses=responses)
        with self.assertRaises(Exception):
            await MarketplaceDiscoveryOrchestrator(
                model,
                FakeProductTool(),
                limits=DiscoveryLimits(
                    maximum_model_calls=2,
                    maximum_tool_calls=6,
                    maximum_search_calls=6,
                ),
            ).run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Keep searching.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-model-limit-1",
            )
        self.assertLessEqual(model.invocation_count, 2)

    async def test_whole_turn_timeout_cancels_slow_product_tool(self) -> None:
        model = ScriptedChatModel(responses=[_script()[0]])
        with self.assertRaises(TimeoutError):
            await MarketplaceDiscoveryOrchestrator(
                model,
                SlowProductTool(),
                limits=DiscoveryLimits(whole_turn_timeout_seconds=0.01),
            ).run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a pillow in Irvine.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-timeout-1",
            )

    async def test_explicit_cancellation_propagates_without_a_result(self) -> None:
        task = asyncio.create_task(
            MarketplaceDiscoveryOrchestrator(
                ScriptedChatModel(responses=[_script()[0]]),
                SlowProductTool(),
            ).run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a pillow in Irvine.",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-cancel-1",
            )
        )
        await asyncio.sleep(0.01)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

    def test_strict_output_rejects_chain_of_thought_and_invalid_counts(self) -> None:
        with self.assertRaises(ValidationError):
            DiscoveryTurnResult.model_validate(
                {
                    "outcome": "NO_RESULTS",
                    "message": "No result.",
                    "chainOfThought": "private reasoning",
                }
            )
        with self.assertRaises(ValidationError):
            DiscoveryTurnResult.model_validate(
                {
                    "outcome": "RECOMMEND",
                    "message": "Too few.",
                    "selections": [
                        {
                            "listingId": LISTINGS[0],
                            "matchReason": "Only one.",
                        }
                    ],
                }
            )

    def test_recommendation_rejects_unknown_duplicate_or_mismatched_provenance(
        self,
    ) -> None:
        recommendation = {
            "listingId": LISTINGS[0],
            "title": "Current pillow",
            "categoryId": CATEGORY_1,
            "categoryName": "Home",
            "condition": "GOOD",
            "priceAmount": "25.00",
            "currency": "USD",
            "publicCity": "Irvine",
            "publicRegion": "Orange",
            "matchReason": "Matches the selected public filters.",
            "constraintCoverage": ["QUERY", "CITY"],
            "provenance": {
                "listingId": LISTINGS[0],
                "checkedAt": NOW.isoformat(),
                "responseHash": "a" * 64,
            },
        }
        DiscoveryRecommendation.model_validate(recommendation)
        for invalid_coverage in (
            ["QUERY", "PRIVATE_SIGNAL"],
            ["QUERY", "QUERY"],
        ):
            with self.subTest(invalid_coverage=invalid_coverage):
                with self.assertRaises(ValidationError):
                    DiscoveryRecommendation.model_validate(
                        {
                            **recommendation,
                            "constraintCoverage": invalid_coverage,
                        }
                    )
        with self.assertRaises(ValidationError):
            DiscoveryRecommendation.model_validate(
                {
                    **recommendation,
                    "provenance": {
                        **recommendation["provenance"],
                        "listingId": LISTINGS[1],
                    },
                }
            )

    def test_search_schema_rejects_radius_controls_and_inverted_price(self) -> None:
        with self.assertRaises(ValidationError):
            DiscoverySearchRequest.model_validate(
                {"q": "bike", "radiusMiles": 10}
            )
        with self.assertRaises(ValidationError):
            DiscoverySearchRequest.model_validate(
                {"q": "bike\u0000", "limit": 20}
            )
        with self.assertRaises(ValidationError):
            DiscoverySearchRequest.model_validate(
                {"minPrice": "100", "maxPrice": "50"}
            )

    def test_refinement_preserves_prior_constraints_not_repeated_by_tool(self) -> None:
        prior = DiscoveryPreferenceState(
            query="pillow",
            city="Irvine",
            maxPrice="50",
        )

        refined = prior.merged_with_search(
            DiscoverySearchRequest(
                q="cooling pillow",
                condition="NEW",
            )
        )

        self.assertEqual("cooling pillow", refined.query)
        self.assertEqual("Irvine", refined.city)
        self.assertEqual(Decimal("50"), refined.max_price)
        self.assertEqual("NEW", refined.condition)

    def test_all_discovery_flags_default_off_and_partial_enablement_fails(self) -> None:
        settings = Settings()
        self.assertFalse(settings.discovery_api.enabled)
        self.assertFalse(settings.discovery_api.generation_enabled)
        with self.assertRaises(ValueError):
            DiscoveryApiSettings(orchestration_enabled=True).validate(
                persistence_enabled=False
            )
        with self.assertRaises(ValueError):
            DiscoveryApiSettings(
                enabled=True,
                auth_service_url="http://auth-service:8085",
            ).validate(persistence_enabled=False)
        DiscoveryApiSettings(
            enabled=True,
            orchestration_enabled=True,
            product_tools_enabled=True,
            provider_enabled=True,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
        ).validate(
            persistence_enabled=AgentPersistenceSettings(
                enabled=True,
                mysql_password="secret",
            ).enabled
        )

    def test_v7_is_forward_only_subjectless_and_actor_unique(self) -> None:
        migration = (
            Path(__file__).parents[1]
            / "db"
            / "migration"
            / "V7__create_marketplace_discovery_sessions.sql"
        ).read_text(encoding="utf-8")
        self.assertIn("'MARKETPLACE_DISCOVERY'", migration)
        self.assertIn("uq_agent_discovery_open_actor", migration)
        self.assertIn("subject_listing_id", migration)
        self.assertIn("agent_discovery_recommendations", migration)
        self.assertNotIn("DROP TABLE", migration.upper())
        self.assertNotIn("TRUNCATE", migration.upper())


class ProductDiscoveryAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_adapter_uses_only_public_search_and_detail_routes(self) -> None:
        from msb_agent_service.marketplace_discovery import (
            ProductMarketplaceDiscoveryClient,
        )

        seen: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(request)
            if request.url.path.endswith("/search"):
                return httpx.Response(
                    200,
                    json={
                        "data": [
                            _listing(
                                LISTINGS[0],
                                category=CATEGORY_1,
                                title="Comfort pillow",
                            ).model_dump(mode="json", by_alias=True)
                        ],
                        "page": {"nextCursor": None, "hasMore": False},
                    },
                )
            return httpx.Response(
                200,
                json=_listing(
                    LISTINGS[0],
                    category=CATEGORY_1,
                    title="Comfort pillow",
                ).model_dump(mode="json", by_alias=True),
            )

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as client:
            adapter = ProductMarketplaceDiscoveryClient(
                "http://product-service:8091",
                client=client,
            )
            page = await adapter.search_individual(
                actor_user_id=ACTOR,
                request=DiscoverySearchRequest(q="pillow", limit=20),
                correlation_id="disc-product-1",
            )
            checked = await adapter.get_listing(
                actor_user_id=ACTOR,
                listing_id=LISTINGS[0],
                correlation_id="disc-product-1",
            )
        self.assertEqual(1, len(page.data))
        self.assertIsNotNone(checked)
        self.assertEqual(
            "/api/v1/public/marketplace/listings/search",
            seen[0].url.path,
        )
        self.assertEqual(
            f"/api/v1/public/listings/{LISTINGS[0]}",
            seen[1].url.path,
        )
        self.assertNotIn("actor", str(seen[0].url).casefold())


class MarketplaceDiscoveryApiTest(unittest.IsolatedAsyncioTestCase):
    async def test_default_off_returns_hidden_404_before_identity_or_service(self) -> None:
        identity = FakeIdentityClient()
        service = FakeDiscoveryService()
        app = create_app(
            Settings(openai_api_key=None),
            identity_client=identity,
            discovery_service_override=service,
        )
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        ) as client:
            response = await client.post(
                "/api/v1/agent/discovery/sessions",
                headers={"Authorization": "Bearer discovery-user"},
                json={
                    "sessionType": "MARKETPLACE_DISCOVERY",
                    "newSearch": False,
                },
            )
        self.assertEqual(404, response.status_code)
        self.assertEqual(
            "AGENT_DISCOVERY_FEATURE_DISABLED",
            response.json()["error"]["code"],
        )
        self.assertEqual(0, identity.calls)
        self.assertEqual(0, service.create_calls)

    async def test_enabled_create_derives_actor_and_rejects_client_identity(self) -> None:
        identity = FakeIdentityClient()
        service = FakeDiscoveryService()
        persistence = FakePersistenceRepository()

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        settings = Settings(
            openai_api_key=None,
            agent_persistence=AgentPersistenceSettings(
                enabled=True,
                mysql_password="database-secret",
            ),
            discovery_api=DiscoveryApiSettings(
                enabled=True,
                orchestration_enabled=True,
                product_tools_enabled=True,
                provider_enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
            ),
        )
        app = create_app(
            settings,
            persistence_repository_factory=persistence_factory,
            identity_client=identity,
            discovery_service_override=service,
        )
        lifespan = app.router.lifespan_context(app)
        await lifespan.__aenter__()
        self.addAsyncCleanup(lifespan.__aexit__, None, None, None)
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        ) as client:
            guest = await client.post(
                "/api/v1/agent/discovery/sessions",
                json={
                    "sessionType": "MARKETPLACE_DISCOVERY",
                    "newSearch": False,
                },
            )
            spoofed = await client.post(
                "/api/v1/agent/discovery/sessions",
                headers={"Authorization": "Bearer discovery-user"},
                json={
                    "sessionType": "MARKETPLACE_DISCOVERY",
                    "newSearch": False,
                    "actorUserId": "01ARZ3NDEKTSV4RRFFQ69G5FAZ",
                },
            )
            accepted = await client.post(
                "/api/v1/agent/discovery/sessions",
                headers={"Authorization": "Bearer discovery-user"},
                json={
                    "sessionType": "MARKETPLACE_DISCOVERY",
                    "newSearch": True,
                },
            )
        self.assertEqual(401, guest.status_code)
        self.assertEqual(400, spoofed.status_code)
        self.assertEqual(200, accepted.status_code)
        self.assertEqual(ACTOR, service.actor_user_id)
        self.assertEqual("MARKETPLACE_DISCOVERY", accepted.json()["sessionType"])


if __name__ == "__main__":
    unittest.main()
