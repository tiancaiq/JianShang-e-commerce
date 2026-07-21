from __future__ import annotations

import unittest
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from msb_agent_service.agent_persistence import (
    AgentInvocation,
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentResolutionType,
    AgentSessionStatus,
    BeginInvocation,
    BeginInvocationResult,
    MessageCursor,
    MessagePage,
)
from msb_agent_service.discovery_api import (
    DiscoveryApiError,
    DiscoveryApiErrorCode,
    MarketplaceDiscoveryService,
)
from msb_agent_service.discovery_persistence import DiscoverySession
from msb_agent_service.marketplace_discovery import (
    DiscoveryPreferenceState,
    DiscoveryRun,
    DiscoveryTurnResponse,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
OTHER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
CLIENT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
USER_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
ASSISTANT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)


class FakeConversationRepository:
    def __init__(self) -> None:
        self.begin_result = BeginInvocationResult.CREATED
        self.raise_hash_conflict = False
        self.failed = False
        self.completed = False
        self.tool_calls: list[dict[str, Any]] = []
        self.message_page = MessagePage(messages=(), next_cursor=None)
        self.user = AgentMessage(
            message_id=USER_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.USER,
            body="What would work for a small room?",
            resolution_type=None,
            sources=(),
            actions=(),
            created_at=NOW,
        )
        self.assistant = _assistant_message()

    async def begin_invocation(self, **kwargs: Any) -> BeginInvocation:
        if self.raise_hash_conflict:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT
            )
        return BeginInvocation(
            self.begin_result,
            _invocation(kwargs["correlation_id"]),
            self.user if self.begin_result == BeginInvocationResult.CREATED else None,
        )

    async def get_invocation_messages(
        self,
        **_: Any,
    ) -> tuple[AgentMessage, AgentMessage]:
        return self.user, self.assistant

    async def list_messages(self, **_: Any) -> MessagePage:
        return self.message_page

    async def append_tool_call(self, **kwargs: Any) -> None:
        self.tool_calls.append(kwargs)

    async def complete_invocation(
        self,
        **kwargs: Any,
    ) -> tuple[AgentInvocation, AgentMessage]:
        self.completed = True
        self.assistant = AgentMessage(
            message_id=ASSISTANT_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.ASSISTANT,
            body=kwargs["body"],
            resolution_type=kwargs["resolution_type"],
            sources=tuple(kwargs["sources"]),
            actions=tuple(kwargs["actions"]),
            created_at=NOW,
        )
        return _invocation("disc-test"), self.assistant

    async def fail_invocation(self, **_: Any) -> None:
        self.failed = True


class FakeDiscoveryRepository:
    def __init__(self, conversation: FakeConversationRepository) -> None:
        self.conversation_repository = conversation
        self.session = DiscoverySession(
            session_id=SESSION,
            actor_user_id=ACTOR,
            status=AgentSessionStatus.OPEN,
            preference_state=DiscoveryPreferenceState(),
            preference_version=0,
            clarification_turn_count=0,
            clarification_question_count=0,
            created_at=NOW,
            updated_at=NOW,
            last_activity_at=NOW,
            optimistic_version=0,
        )
        self.recommendations_persisted = 0
        self.recent_messages: tuple[AgentMessage, ...] = ()
        self.recent_message_requests: list[dict[str, Any]] = []

    async def get(self, **kwargs: Any) -> DiscoverySession | None:
        if (
            kwargs["session_id"] != SESSION
            or kwargs["actor_user_id"] != ACTOR
        ):
            return None
        return self.session

    async def create_or_resume(self, **_: Any) -> tuple[DiscoverySession, bool]:
        return self.session, False

    async def list_recent_messages(
        self,
        **kwargs: Any,
    ) -> tuple[AgentMessage, ...]:
        self.recent_message_requests.append(kwargs)
        return self.recent_messages

    async def complete_turn(
        self,
        **kwargs: Any,
    ) -> tuple[DiscoverySession, AgentMessage]:
        if kwargs["expected_preference_version"] != self.session.preference_version:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
            )
        self.session = replace(
            self.session,
            preference_state=kwargs["response"].preference_state,
            preference_version=self.session.preference_version + 1,
            clarification_turn_count=(
                self.session.clarification_turn_count
                + kwargs["clarification_turn_increment"]
            ),
            clarification_question_count=(
                self.session.clarification_question_count
                + kwargs["clarification_question_increment"]
            ),
        )
        if kwargs["response"].recommendations:
            self.recommendations_persisted += len(
                kwargs["response"].recommendations
            )
        _, assistant = await self.conversation_repository.complete_invocation(
            body=kwargs["response"].message,
            resolution_type=kwargs["resolution_type"],
            sources=[
                item.model_dump(mode="json", by_alias=True, exclude_none=True)
                for item in kwargs["response"].recommendations
            ],
            actions=[
                {
                    "type": "DISCOVERY_RESULT",
                    "result": kwargs["response"].model_dump(
                        mode="json",
                        by_alias=True,
                        exclude_none=True,
                    ),
                }
            ],
        )
        return self.session, assistant


class FakeOrchestrator:
    def __init__(self) -> None:
        self.calls = 0
        self.last_request: dict[str, Any] | None = None

    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        return DiscoveryRun(
            response=DiscoveryTurnResponse(
                outcome="ASK_CLARIFY",
                message="Which city or county should I use?",
                questions=("Which city or county should I use?",),
                preferenceState=DiscoveryPreferenceState(),
            ),
            audits=(),
        )


def _invocation(correlation_id: str) -> AgentInvocation:
    return AgentInvocation(
        invocation_id=INVOCATION,
        session_id=SESSION,
        actor_user_id=ACTOR,
        user_message_id=USER_MESSAGE,
        assistant_message_id=None,
        client_message_id=CLIENT_MESSAGE,
        request_hash="a" * 64,
        result_status=AgentInvocationStatus.PENDING,
        error_code=None,
        prompt_version="marketplace-discovery-react-v1",
        model_provider="injected",
        model_name="injected",
        schema_version="discovery-turn-v1",
        tool_registry_version="marketplace-discovery-tools-v1",
        policy_version="ai-disc-01a",
        input_tokens=0,
        output_tokens=0,
        latency_ms=None,
        estimated_cost=Decimal("0"),
        retry_count=0,
        correlation_id=correlation_id,
        created_at=NOW,
        updated_at=NOW,
        completed_at=None,
        optimistic_version=0,
    )


def _assistant_message() -> AgentMessage:
    turn = DiscoveryTurnResponse(
        outcome="ASK_CLARIFY",
        message="Which city or county should I use?",
        questions=("Which city or county should I use?",),
        preferenceState=DiscoveryPreferenceState(),
    )
    return AgentMessage(
        message_id=ASSISTANT_MESSAGE,
        session_id=SESSION,
        actor_user_id=ACTOR,
        role=AgentMessageRole.ASSISTANT,
        body=turn.message,
        resolution_type=AgentResolutionType.CLARIFY,
        sources=(),
        actions=(
            {
                "type": "DISCOVERY_RESULT",
                "result": turn.model_dump(
                    mode="json",
                    by_alias=True,
                    exclude_none=True,
                ),
            },
        ),
        created_at=NOW,
    )


class MarketplaceDiscoveryServiceTest(unittest.IsolatedAsyncioTestCase):
    async def test_history_exposes_existing_bounded_cursor(self) -> None:
        conversation = FakeConversationRepository()
        conversation.message_page = MessagePage(
            messages=(conversation.user,),
            next_cursor=MessageCursor(
                created_at=NOW,
                message_id=USER_MESSAGE,
            ),
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )

        page = await service.list_messages(
            actor_user_id=ACTOR,
            session_id=SESSION,
            limit=1,
            cursor=None,
        )

        self.assertTrue(page.has_more)
        self.assertIsNotNone(page.next_cursor)
        self.assertEqual(1, len(page.data))

    async def test_success_updates_version_and_clarification_counts(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="What would work for a small room?",
            correlation_id="disc-service-1",
        )

        self.assertEqual("ASK_CLARIFY", response.result.outcome)
        self.assertEqual(1, response.preference_version)
        self.assertEqual(1, repository.session.clarification_turn_count)
        self.assertEqual(1, repository.session.clarification_question_count)
        self.assertTrue(conversation.completed)
        self.assertEqual(1, orchestrator.calls)

    async def test_orchestration_receives_only_recent_prior_messages(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        prior = replace(
            conversation.assistant,
            message_id="01ARZ3NDEKTSV4RRFFQ69G5FB2",
            body="Tell me which city you prefer.",
        )
        repository.recent_messages = (prior,)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body=conversation.user.body,
            correlation_id="disc-service-history",
        )

        self.assertEqual(
            (("ASSISTANT", prior.body),),
            orchestrator.last_request["history"],
        )
        self.assertEqual(
            USER_MESSAGE,
            repository.recent_message_requests[0]["exclude_message_id"],
        )

    async def test_unavailable_orchestrator_marks_invocation_failed(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            None,
        )

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body=conversation.user.body,
                correlation_id="disc-service-disabled",
            )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertTrue(conversation.failed)

    async def test_exact_replay_precedes_stale_preference_version(self) -> None:
        conversation = FakeConversationRepository()
        conversation.begin_result = BeginInvocationResult.DEDUPLICATED_SUCCEEDED
        repository = FakeDiscoveryRepository(conversation)
        repository.session = replace(repository.session, preference_version=1)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="What would work for a small room?",
            correlation_id="disc-service-2",
        )

        self.assertEqual("ASK_CLARIFY", response.result.outcome)
        self.assertEqual(1, response.preference_version)
        self.assertEqual(0, orchestrator.calls)

    async def test_hash_conflict_is_stable_and_zero_orchestration(self) -> None:
        conversation = FakeConversationRepository()
        conversation.raise_hash_conflict = True
        repository = FakeDiscoveryRepository(conversation)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body="Changed payload",
                correlation_id="disc-service-3",
            )
        self.assertEqual(
            DiscoveryApiErrorCode.REQUEST_CONFLICT,
            raised.exception.code,
        )
        self.assertEqual(0, orchestrator.calls)

    async def test_stale_preference_fails_before_tools_or_model(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        repository.session = replace(repository.session, preference_version=2)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=1,
                body="What would work for a small room?",
                correlation_id="disc-service-4",
            )
        self.assertEqual(
            DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
            raised.exception.code,
        )
        self.assertTrue(conversation.failed)
        self.assertEqual(0, orchestrator.calls)

    async def test_cross_actor_session_is_hidden(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )
        with self.assertRaises(DiscoveryApiError) as raised:
            await service.get_session(
                actor_user_id=OTHER,
                session_id=SESSION,
            )
        self.assertEqual(
            DiscoveryApiErrorCode.SESSION_NOT_FOUND,
            raised.exception.code,
        )


if __name__ == "__main__":
    unittest.main()
