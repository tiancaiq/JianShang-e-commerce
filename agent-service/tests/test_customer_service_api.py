import asyncio
import logging
import unittest
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal

import httpx

from msb_agent_service.agent_persistence import (
    AgentInvocation,
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    AgentResolutionType,
    AgentSession,
    AgentSessionStatus,
    BeginInvocation,
    BeginInvocationResult,
    MessagePage,
)
from msb_agent_service.api import create_app
from msb_agent_service.config import (
    AgentApiSettings,
    AgentPersistenceSettings,
    Settings,
)
from msb_agent_service.customer_service_api import (
    AgentCustomerService,
    AnswerDraft,
    AnswererMetadata,
    ListingContext,
)
from msb_agent_service.customer_service_langchain import (
    LangChainQuestionAnswerer,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
OTHER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
CLIENT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
USER_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
ASSISTANT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FB2"
NOW = datetime(2026, 7, 19, 12, 0, tzinfo=UTC)


class FakeIdentityClient:
    async def resolve(self, authorization, correlation_id):
        if authorization == "Bearer actor-token":
            return ACTOR
        if authorization == "Bearer other-token":
            return OTHER
        from msb_agent_service.customer_service_api import (
            AgentApiError,
            AgentApiErrorCode,
        )

        raise AgentApiError(
            AgentApiErrorCode.UNAUTHORIZED,
            401,
            "Authentication is required.",
        )


class FakeListingClient:
    def __init__(self, eligible=True):
        self.eligible = eligible
        self.calls = 0

    async def get(self, listing_id, correlation_id):
        self.calls += 1
        if not self.eligible:
            return None
        return ListingContext(
            listing_id=listing_id,
            source_version="12",
            title="Used bicycle",
            thumbnail_url="/api/v1/public/listing-media/image",
            transaction_notice="Payment and delivery are arranged directly.",
        )


class FakeAnswerer:
    metadata = AnswererMetadata(
        prompt_version="listing-customer-service-v1",
        model_provider="mock",
        model_name="mock-listing-model",
        policy_version="listing-only-grounding-v1",
    )

    async def answer(self, **kwargs):
        return AnswerDraft(
            body="Only the seller can confirm that.",
            resolution_type=AgentResolutionType.CONTACT_SELLER,
            sources=[
                {
                    "sourceType": "LISTING",
                    "sourceId": LISTING,
                    "sourceVersion": "12",
                    "label": "Current listing",
                }
            ],
            actions=[{"type": "MESSAGE_SELLER", "listingId": LISTING}],
        )


class CancelledAnswerer:
    metadata = FakeAnswerer.metadata

    async def answer(self, **kwargs):
        raise asyncio.CancelledError


class FakeRepository:
    def __init__(self):
        self.failed = False
        self.failure_kwargs = None
        self.closed = False
        self.begin_kwargs = None
        self.session = AgentSession(
            session_id=SESSION,
            session_type="LISTING_CUSTOMER_SERVICE",
            actor_user_id=ACTOR,
            subject_type="LISTING",
            subject_listing_id=LISTING,
            status=AgentSessionStatus.OPEN,
            created_at=NOW,
            updated_at=NOW,
            last_activity_at=NOW,
            closed_at=None,
            content_purged_at=None,
            optimistic_version=0,
        )
        self.user = AgentMessage(
            message_id=USER_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.USER,
            body="Can this be held?",
            resolution_type=None,
            sources=(),
            actions=(),
            created_at=NOW,
        )
        self.assistant = AgentMessage(
            message_id=ASSISTANT_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.ASSISTANT,
            body="Only the seller can confirm that.",
            resolution_type=AgentResolutionType.CONTACT_SELLER,
            sources=(
                {
                    "sourceType": "LISTING",
                    "sourceId": LISTING,
                    "sourceVersion": "12",
                    "label": "Current listing",
                },
            ),
            actions=({"type": "MESSAGE_SELLER", "listingId": LISTING},),
            created_at=NOW,
        )

    async def validate_schema(self):
        return None

    async def close(self):
        self.closed = True

    async def create_or_resume_session(self, **kwargs):
        return self.session, True

    async def get_session(self, session_id, actor_user_id):
        if session_id != SESSION or actor_user_id != ACTOR:
            return None
        return self.session

    async def list_messages(self, **kwargs):
        if kwargs["actor_user_id"] != ACTOR:
            from msb_agent_service.agent_persistence import (
                AgentPersistenceError,
                AgentPersistenceErrorCode,
            )

            raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
        return MessagePage(messages=(self.user,), next_cursor=None)

    async def mark_session_read_only(self, **kwargs):
        self.session = AgentSession(
            **{
                **self.session.__dict__,
                "status": AgentSessionStatus.READ_ONLY,
                "optimistic_version": self.session.optimistic_version + 1,
            }
        )
        return self.session

    async def begin_invocation(self, **kwargs):
        self.begin_kwargs = kwargs
        invocation = AgentInvocation(
            invocation_id=INVOCATION,
            session_id=SESSION,
            actor_user_id=ACTOR,
            user_message_id=USER_MESSAGE,
            assistant_message_id=None,
            client_message_id=CLIENT_MESSAGE,
            request_hash="a" * 64,
            result_status=AgentInvocationStatus.PENDING,
            error_code=None,
            prompt_version="deferred-to-ai-cs-01c",
            model_provider="deferred",
            model_name="deferred",
            schema_version="agent-message-v1",
            tool_registry_version="listing-read-tools-v1",
            policy_version="ai-cs-01b",
            input_tokens=0,
            output_tokens=0,
            latency_ms=None,
            estimated_cost=Decimal("0"),
            retry_count=0,
            correlation_id=kwargs["correlation_id"],
            created_at=NOW,
            updated_at=NOW,
            completed_at=None,
            optimistic_version=0,
        )
        return BeginInvocation(
            BeginInvocationResult.CREATED,
            invocation,
            self.user,
        )

    async def complete_invocation(self, **kwargs):
        return None, self.assistant

    async def fail_invocation(self, **kwargs):
        self.failed = True
        self.failure_kwargs = kwargs
        return None


def enabled_settings(*, generation_enabled=True):
    return Settings(
        openai_api_key="configured-for-test",
        agent_persistence=AgentPersistenceSettings(
            enabled=True,
            mysql_password="database-secret",
        ),
        agent_api=AgentApiSettings(
            enabled=True,
            orchestration_enabled=generation_enabled,
            retrieval_enabled=generation_enabled,
            provider_enabled=generation_enabled,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
            product_service_token="internal-secret",
        ),
    )


class CustomerServiceApiTest(unittest.IsolatedAsyncioTestCase):
    async def client(
        self,
        repository,
        *,
        listing=None,
        answerer=None,
        settings=None,
    ):
        async def factory(settings, metrics):
            return repository

        app = create_app(
            settings
            or enabled_settings(generation_enabled=answerer is not None),
            persistence_repository_factory=factory,
            identity_client=FakeIdentityClient(),
            listing_client=listing or FakeListingClient(),
            question_answerer=answerer,
        )
        lifespan = app.router.lifespan_context(app)
        await lifespan.__aenter__()
        self.addAsyncCleanup(lifespan.__aexit__, None, None, None)
        return httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        )

    async def test_create_rejects_guest_and_client_actor_fields(self):
        repository = FakeRepository()
        async with await self.client(repository, answerer=FakeAnswerer()) as client:
            guest = await client.post(
                "/api/v1/agent/sessions",
                json={
                    "sessionType": "LISTING_CUSTOMER_SERVICE",
                    "subject": {"type": "LISTING", "id": LISTING},
                },
            )
            spoofed = await client.post(
                "/api/v1/agent/sessions",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "sessionType": "LISTING_CUSTOMER_SERVICE",
                    "subject": {"type": "LISTING", "id": LISTING},
                    "actorUserId": OTHER,
                },
            )
        self.assertEqual(401, guest.status_code)
        self.assertEqual("UNAUTHORIZED", guest.json()["error"]["code"])
        self.assertEqual(400, spoofed.status_code)
        self.assertEqual("VALIDATION_ERROR", spoofed.json()["error"]["code"])
        self.assertEqual(
            guest.headers["X-Correlation-Id"],
            guest.json()["error"]["correlationId"],
        )

    async def test_create_returns_owned_listing_session(self):
        repository = FakeRepository()
        async with await self.client(repository, answerer=FakeAnswerer()) as client:
            response = await client.post(
                "/api/v1/agent/sessions",
                headers={
                    "Authorization": "Bearer actor-token",
                    "X-Correlation-Id": "agent-api-test-1",
                },
                json={
                    "sessionType": "LISTING_CUSTOMER_SERVICE",
                    "subject": {"type": "LISTING", "id": LISTING},
                },
            )
        self.assertEqual(200, response.status_code)
        self.assertEqual(SESSION, response.json()["id"])
        self.assertEqual("12", response.json()["subjectListing"]["version"])
        self.assertEqual("agent-api-test-1", response.headers["X-Correlation-Id"])

    async def test_ineligible_listing_log_omits_actor_and_listing_identifiers(self):
        repository = FakeRepository()
        with self.assertLogs(
            "msb_agent_service.customer_service_api",
            level=logging.INFO,
        ) as captured:
            async with await self.client(
                repository,
                listing=FakeListingClient(eligible=False),
                answerer=FakeAnswerer(),
            ) as client:
                response = await client.post(
                    "/api/v1/agent/sessions",
                    headers={
                        "Authorization": "Bearer actor-token",
                        "X-Correlation-Id": "agent-api-safe-log-1",
                    },
                    json={
                        "sessionType": "LISTING_CUSTOMER_SERVICE",
                        "subject": {"type": "LISTING", "id": LISTING},
                    },
                )

        self.assertEqual(404, response.status_code)
        combined = "\n".join(captured.output)
        self.assertNotIn(ACTOR, combined)
        self.assertNotIn(LISTING, combined)
        self.assertIn("Agent listing eligibility rejected", combined)

    async def test_cross_user_session_is_hidden(self):
        repository = FakeRepository()
        async with await self.client(repository, answerer=FakeAnswerer()) as client:
            response = await client.get(
                f"/api/v1/agent/sessions/{SESSION}",
                headers={"Authorization": "Bearer other-token"},
            )
        self.assertEqual(404, response.status_code)
        self.assertEqual("AGENT_SESSION_NOT_FOUND", response.json()["error"]["code"])

    async def test_message_succeeds_only_through_injected_answerer(self):
        repository = FakeRepository()
        async with await self.client(repository, answerer=FakeAnswerer()) as client:
            response = await client.post(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "clientMessageId": CLIENT_MESSAGE,
                    "body": "Can this be held?",
                },
            )
        self.assertEqual(200, response.status_code)
        self.assertEqual(
            "CONTACT_SELLER",
            response.json()["assistantMessage"]["resolutionType"],
        )
        self.assertEqual(
            "listing-customer-service-v1",
            repository.begin_kwargs["prompt_version"],
        )
        self.assertEqual("mock", repository.begin_kwargs["model_provider"])
        self.assertEqual(
            "listing-only-grounding-v1",
            repository.begin_kwargs["policy_version"],
        )

    async def test_langchain_adapter_preserves_create_send_and_history_contract(
        self,
    ):
        repository = FakeRepository()
        answerer = LangChainQuestionAnswerer(FakeAnswerer())
        async with await self.client(repository, answerer=answerer) as client:
            created = await client.post(
                "/api/v1/agent/sessions",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "sessionType": "LISTING_CUSTOMER_SERVICE",
                    "subject": {"type": "LISTING", "id": LISTING},
                },
            )
            sent = await client.post(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "clientMessageId": CLIENT_MESSAGE,
                    "body": "Can this be held?",
                },
            )
            history = await client.get(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
            )

        self.assertEqual(200, created.status_code)
        self.assertEqual(200, sent.status_code)
        self.assertEqual(200, history.status_code)
        self.assertEqual("USER", history.json()["data"][0]["role"])
        self.assertEqual(
            "CONTACT_SELLER",
            sent.json()["assistantMessage"]["resolutionType"],
        )

    async def test_ineligible_listing_locks_session_before_message_persistence(self):
        repository = FakeRepository()
        async with await self.client(
            repository,
            listing=FakeListingClient(eligible=False),
            answerer=FakeAnswerer(),
        ) as client:
            response = await client.post(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "clientMessageId": CLIENT_MESSAGE,
                    "body": "Can this be held?",
                },
            )
        self.assertEqual(409, response.status_code)
        self.assertEqual("AGENT_SESSION_READ_ONLY", response.json()["error"]["code"])
        self.assertEqual(AgentSessionStatus.READ_ONLY, repository.session.status)

    async def test_disabled_generation_fails_before_product_or_persistence(self):
        repository = FakeRepository()
        listing_client = FakeListingClient()
        async with await self.client(repository, listing=listing_client) as client:
            response = await client.post(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "clientMessageId": CLIENT_MESSAGE,
                    "body": "Can this be held?",
                },
            )
        self.assertEqual(503, response.status_code)
        self.assertEqual(
            "AGENT_ORCHESTRATION_UNAVAILABLE",
            response.json()["error"]["code"],
        )
        self.assertFalse(repository.failed)
        self.assertIsNone(repository.begin_kwargs)
        self.assertEqual(0, listing_client.calls)

    async def test_kill_switch_precedes_product_and_persistence(self):
        repository = FakeRepository()
        listing_client = FakeListingClient()
        base = enabled_settings()
        settings = replace(
            base,
            agent_api=replace(
                base.agent_api,
                kill_switch_enabled=True,
            ),
        )
        async with await self.client(
            repository,
            listing=listing_client,
            answerer=LangChainQuestionAnswerer(FakeAnswerer()),
            settings=settings,
        ) as client:
            response = await client.post(
                f"/api/v1/agent/sessions/{SESSION}/messages",
                headers={"Authorization": "Bearer actor-token"},
                json={
                    "clientMessageId": CLIENT_MESSAGE,
                    "body": "Can this be held?",
                },
            )

        self.assertEqual(503, response.status_code)
        self.assertEqual(
            "AGENT_ORCHESTRATION_UNAVAILABLE",
            response.json()["error"]["code"],
        )
        self.assertEqual(0, listing_client.calls)
        self.assertIsNone(repository.begin_kwargs)

    async def test_cancelled_answerer_marks_invocation_failed_before_propagating(
        self,
    ):
        repository = FakeRepository()
        service = AgentCustomerService(
            repository,
            FakeListingClient(),
            CancelledAnswerer(),
        )

        with self.assertRaises(asyncio.CancelledError):
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                body="What does the listing say?",
                correlation_id="agent-cancelled-1",
            )

        self.assertTrue(repository.failed)
        self.assertEqual(
            "AGENT_EXECUTION_CANCELLED",
            repository.failure_kwargs["error_code"],
        )


if __name__ == "__main__":
    unittest.main()
