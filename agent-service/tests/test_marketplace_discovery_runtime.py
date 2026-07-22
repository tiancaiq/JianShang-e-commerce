from __future__ import annotations

import unittest
from datetime import UTC, datetime
from typing import Any
from unittest.mock import patch

from msb_agent_service.api import create_app
from msb_agent_service.config import (
    AgentApiSettings,
    AgentPersistenceSettings,
    DiscoveryApiSettings,
    Settings,
)
from msb_agent_service.errors import LlmProviderError, ProviderErrorCode
from msb_agent_service.marketplace_discovery import (
    CheckedListing,
    DiscoveryPreferenceState,
    DiscoverySearchRequest,
    PublicIndividualListing,
    PublicIndividualSearchPage,
    _PageMetadata,
)
from msb_agent_service.marketplace_discovery_runtime import (
    MarketplaceDiscoveryRuntime,
    build_marketplace_discovery_runtime,
)
from msb_agent_service.provider import (
    DiscoveryProviderResult,
    DiscoveryProviderToolCall,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
CATEGORY = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
LISTINGS = (
    "01ARZ3NDEKTSV4RRFFQ69G5FB1",
    "01ARZ3NDEKTSV4RRFFQ69G5FB2",
    "01ARZ3NDEKTSV4RRFFQ69G5FB3",
)
NOW = datetime(2026, 7, 21, 12, 0, tzinfo=UTC)


class FakeDiscoveryProvider:
    """Returns deterministic tool calls while recording the production boundary."""

    model_name = "offline-discovery-model"

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.closed = 0

    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        call_number = len(self.calls)
        if call_number == 1:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="search-1",
                    name="SEARCH_INDIVIDUAL",
                    arguments={"q": "sleep pillow", "city": "Irvine", "limit": 20},
                ),
            )
        elif call_number == 2:
            calls = tuple(
                DiscoveryProviderToolCall(
                    call_id=f"detail-{index}",
                    name="GET_LISTING",
                    arguments={"listing_id": listing_id},
                )
                for index, listing_id in enumerate(LISTINGS, 1)
            )
        elif call_number == 3:
            calls = (
                DiscoveryProviderToolCall(
                    call_id="structured-1",
                    name="DiscoveryTurnResult",
                    arguments={
                        "outcome": "RECOMMEND",
                        "message": "These verified listings match your request.",
                        "clarificationQuestions": [],
                        "observedAmbiguities": [],
                        "selections": [
                            {
                                "listingId": listing_id,
                                "matchReason": "Matches the requested product and city.",
                            }
                            for listing_id in LISTINGS
                        ],
                    },
                ),
            )
        else:
            raise AssertionError("Unexpected provider call")
        return DiscoveryProviderResult(
            content="private intermediate text that must be discarded",
            tool_calls=calls,
            input_tokens=10,
            output_tokens=5,
            latency_ms=1,
        )

    async def close(self) -> None:
        self.closed += 1


class FakeProductTool:
    def __init__(self) -> None:
        self.search_calls = 0
        self.detail_calls = 0
        self.listings = {item: _listing(item, index) for index, item in enumerate(LISTINGS)}

    async def search_individual(self, **kwargs: Any) -> PublicIndividualSearchPage:
        self.search_calls += 1
        request = kwargs["request"]
        if not isinstance(request, DiscoverySearchRequest):
            raise AssertionError("Search arguments were not schema validated")
        return PublicIndividualSearchPage(
            data=tuple(self.listings.values()),
            page=_PageMetadata(nextCursor=None, hasMore=False),
        )

    async def get_listing(self, **kwargs: Any) -> CheckedListing | None:
        self.detail_calls += 1
        listing = self.listings.get(kwargs["listing_id"])
        if listing is None:
            return None
        return CheckedListing(
            listing=listing,
            checkedAt=NOW,
            responseHash="a" * 64,
        )


class UnavailableDiscoveryProvider(FakeDiscoveryProvider):
    async def discovery_chat(self, **kwargs: Any) -> DiscoveryProviderResult:
        self.calls.append(kwargs)
        raise LlmProviderError(
            ProviderErrorCode.UNAVAILABLE,
            "Offline fake provider unavailable",
            retryable=True,
            status_code=503,
        )


class FakePersistenceRepository:
    def __init__(self) -> None:
        self.pool = object()
        self.validated = 0
        self.closed = 0

    async def validate_schema(self) -> None:
        self.validated += 1

    async def close(self) -> None:
        self.closed += 1


class FakeDiscoveryRepository:
    def __init__(self, repository: FakePersistenceRepository) -> None:
        self.conversation_repository = repository
        self.validated = 0

    async def validate_schema(self) -> None:
        self.validated += 1


class DeferredAnswerer:
    async def answer(self, **_: Any) -> Any:
        raise AssertionError("Customer-service answerer must not run at startup")


class MarketplaceDiscoveryRuntimeTest(unittest.IsolatedAsyncioTestCase):
    async def test_production_composition_runs_langchain_with_fake_transports(self) -> None:
        provider = FakeDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=product,
        )

        run = await runtime.orchestrator.run(
            actor_user_id=ACTOR,
            session_id=SESSION,
            question="Find a sleep pillow in Irvine",
            preference_state=DiscoveryPreferenceState(),
            clarification_turn_count=0,
            clarification_question_count=0,
            history=(),
            correlation_id="disc-runtime-1",
        )

        self.assertEqual("RECOMMEND", run.response.outcome)
        self.assertEqual(3, len(run.response.recommendations))
        self.assertEqual(3, len(provider.calls))
        self.assertEqual(1, product.search_calls)
        self.assertEqual(3, product.detail_calls)
        self.assertNotIn(
            "private intermediate text",
            run.response.model_dump_json(by_alias=True),
        )
        self.assertTrue(all(call["correlation_id"] == "disc-runtime-1" for call in provider.calls))
        self.assertTrue(all(call["maximum_output_tokens"] == 800 for call in provider.calls))
        self.assertTrue(all(call["maximum_tool_calls"] == 6 for call in provider.calls))
        tool_names = {item["name"] for item in provider.calls[0]["tools"]}
        self.assertEqual(
            {"SEARCH_INDIVIDUAL", "GET_LISTING", "DiscoveryTurnResult"},
            tool_names,
        )
        await runtime.close()
        self.assertEqual(1, provider.closed)

    async def test_provider_outage_fails_before_any_product_tool_call(self) -> None:
        provider = UnavailableDiscoveryProvider()
        product = FakeProductTool()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=product,
        )

        with self.assertRaises(LlmProviderError):
            await runtime.orchestrator.run(
                actor_user_id=ACTOR,
                session_id=SESSION,
                question="Find a sleep pillow in Irvine",
                preference_state=DiscoveryPreferenceState(),
                clarification_turn_count=0,
                clarification_question_count=0,
                history=(),
                correlation_id="disc-runtime-outage-1",
            )

        self.assertEqual(1, len(provider.calls))
        self.assertEqual(0, product.search_calls)
        self.assertEqual(0, product.detail_calls)
        await runtime.close()

    async def test_default_off_startup_never_builds_discovery_runtime(self) -> None:
        factory_calls = 0

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("Default-off startup must not compose Discovery")

        app = create_app(Settings(), discovery_runtime_factory=runtime_factory)
        async with app.router.lifespan_context(app):
            pass

        self.assertEqual(0, factory_calls)

    async def test_kill_switch_precedes_runtime_composition(self) -> None:
        persistence = FakePersistenceRepository()
        factory_calls = 0

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("The kill switch must stop runtime composition")

        settings = Settings(
            openai_api_key=None,
            agent_persistence=AgentPersistenceSettings(
                enabled=True,
                mysql_password="offline-database-placeholder",
            ),
            discovery_api=DiscoveryApiSettings(
                enabled=True,
                kill_switch_enabled=True,
                orchestration_enabled=True,
                product_tools_enabled=True,
                provider_enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
            ),
        )
        with patch(
            "msb_agent_service.api.DiscoveryPersistenceRepository",
            FakeDiscoveryRepository,
        ):
            app = create_app(
                settings,
                persistence_repository_factory=persistence_factory,
                discovery_runtime_factory=runtime_factory,
            )
            async with app.router.lifespan_context(app):
                pass

        self.assertEqual(0, factory_calls)
        self.assertEqual(1, persistence.closed)

    async def test_enabled_startup_composes_discovery_and_keeps_customer_service(self) -> None:
        persistence = FakePersistenceRepository()
        provider = FakeDiscoveryProvider()
        runtime = build_marketplace_discovery_runtime(
            _fully_enabled_settings(),
            provider=provider,
            product=FakeProductTool(),
        )
        factory_calls = 0

        async def persistence_factory(*_: Any) -> FakePersistenceRepository:
            return persistence

        def runtime_factory(_: Settings) -> MarketplaceDiscoveryRuntime:
            nonlocal factory_calls
            factory_calls += 1
            return runtime

        settings = _fully_enabled_settings(customer_service=True)
        with patch(
            "msb_agent_service.api.DiscoveryPersistenceRepository",
            FakeDiscoveryRepository,
        ):
            app = create_app(
                settings,
                persistence_repository_factory=persistence_factory,
                question_answerer=DeferredAnswerer(),
                discovery_runtime_factory=runtime_factory,
            )
            async with app.router.lifespan_context(app):
                self.assertEqual(1, factory_calls)
                self.assertEqual(0, len(provider.calls))

        self.assertEqual(1, provider.closed)
        self.assertEqual(1, persistence.closed)

    def test_generation_configuration_fails_closed_without_provider_config(self) -> None:
        settings = _fully_enabled_settings()
        settings = Settings(
            openai_api_key=None,
            agent_persistence=settings.agent_persistence,
            discovery_api=settings.discovery_api,
        )

        with self.assertRaisesRegex(RuntimeError, "configured provider"):
            build_marketplace_discovery_runtime(settings)


def _fully_enabled_settings(*, customer_service: bool = False) -> Settings:
    return Settings(
        openai_api_key="offline-test-placeholder",
        openai_timeout_seconds=8.0,
        agent_persistence=AgentPersistenceSettings(
            enabled=True,
            mysql_password="offline-database-placeholder",
        ),
        agent_api=(
            AgentApiSettings(
                enabled=True,
                orchestration_enabled=True,
                retrieval_enabled=True,
                provider_enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
                product_service_token="offline-service-placeholder",
            )
            if customer_service
            else AgentApiSettings()
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


def _listing(listing_id: str, index: int) -> PublicIndividualListing:
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
            "categoryId": CATEGORY,
            "categorySlug": "comfort",
            "categoryName": "Comfort",
            "title": f"Comfort pillow {index + 1}",
            "description": "A public ordinary comfort listing.",
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


if __name__ == "__main__":
    unittest.main()
