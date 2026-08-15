from __future__ import annotations

import asyncio
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
    _safe_error_code,
    _source_refs_json,
)
from msb_agent_service.discovery_api import (
    DiscoveryExclusionReason,
    DiscoveryApiError,
    DiscoveryApiErrorCode,
    DiscoveryProgressStage,
    MarketplaceDiscoveryService,
    _history_message,
)
from msb_agent_service.discovery_persistence import (
    DiscoveryExclusion,
    DiscoveryExclusionCommandResult,
    DiscoveryExclusionPersistenceError,
    DiscoveryExclusionPersistenceErrorCode,
    DiscoverySession,
    _canonical_turn_result,
    _message,
    _session,
)
from msb_agent_service.marketplace_discovery import (
    DiscoveryFailureStage,
    DiscoveryGraphFailureKind,
    DiscoveryProviderFailureKind,
    DiscoveryToolFailureKind,
    DiscoveryPreferenceState,
    DiscoveryRecommendation,
    DiscoveryRun,
    DiscoverySearchOutcome,
    DiscoveryStageError,
    DiscoveryToolAudit,
    DiscoveryTurnResponse,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
OTHER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
CLIENT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
USER_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
ASSISTANT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
LISTING_1 = "81ARZ3NDEKTSV4RRFFQ69G5FAC"
NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)


async def _record_text(target: list[str], value: str) -> None:
    target.append(value)


class FakeConversationRepository:
    def __init__(self) -> None:
        self.maximum_failed_retries = 1
        self.begin_result = BeginInvocationResult.CREATED
        self.raise_hash_conflict = False
        self.failed = False
        self.failure_codes: list[str] = []
        self.failure_assistant_calls: list[dict[str, Any]] = []
        self.completed = False
        self.begin_calls = 0
        self.created_user_rows = 0
        self.failure_assistant: AgentMessage | None = None
        self.client_lookup_missing = False
        self.retry_lookup_invocation = replace(
            _invocation("disc-retry-lookup"),
            result_status=AgentInvocationStatus.FAILED,
            error_code=(
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_"
                "QUERY_EMBEDDING_TIMEOUT"
            ),
            completed_at=NOW,
        )
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
            client_message_id=CLIENT_MESSAGE,
        )
        self.assistant = _assistant_message()

    async def begin_invocation(self, **kwargs: Any) -> BeginInvocation:
        self.begin_calls += 1
        if self.raise_hash_conflict:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT
            )
        if self.begin_result == BeginInvocationResult.CREATED:
            self.created_user_rows += 1
            self.user = replace(self.user, body=kwargs["body"])
        return BeginInvocation(
            self.begin_result,
            _invocation(kwargs["correlation_id"]),
            self.user
            if self.begin_result
            in {BeginInvocationResult.CREATED, BeginInvocationResult.RETRY_STARTED}
            else None,
        )

    async def get_invocation_for_user_message(
        self,
        **_: Any,
    ) -> tuple[AgentInvocation, AgentMessage]:
        return self.retry_lookup_invocation, self.user

    async def get_invocation_for_client_message(
        self,
        **_: Any,
    ) -> tuple[AgentInvocation, AgentMessage]:
        if self.client_lookup_missing:
            raise AgentPersistenceError(AgentPersistenceErrorCode.INVOCATION_NOT_FOUND)
        return self.retry_lookup_invocation, self.user

    async def get_invocation_messages(
        self,
        **_: Any,
    ) -> tuple[AgentMessage, AgentMessage]:
        return self.user, self.assistant

    async def list_messages(self, **_: Any) -> MessagePage:
        return self.message_page

    async def append_tool_call(self, **kwargs: Any) -> None:
        _source_refs_json(kwargs["source_refs"])
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

    async def fail_invocation(self, **kwargs: Any) -> None:
        _safe_error_code(kwargs["error_code"])
        self.failed = True
        self.failure_codes.append(kwargs["error_code"])

    async def fail_invocation_with_assistant(self, **kwargs: Any) -> None:
        _safe_error_code(kwargs["error_code"])
        self.failure_assistant_calls.append(kwargs)
        self.failed = True
        self.failure_codes.append(kwargs["error_code"])
        self.failure_assistant = AgentMessage(
            message_id=ASSISTANT_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.ASSISTANT,
            body=kwargs["body"],
            resolution_type=kwargs.get(
                "resolution_type", AgentResolutionType.HANDOFF
            ),
            sources=tuple(kwargs["sources"]),
            actions=tuple(kwargs["actions"]),
            created_at=NOW,
        )


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
        self.raise_recent_messages = False
        self.recent_message_requests: list[dict[str, Any]] = []
        self.latest_recommendation_set: tuple[DiscoveryRecommendation, ...] = ()
        self.raise_latest_recommendations = False
        self.latest_recommendation_requests: list[dict[str, Any]] = []
        self.exclusions: tuple[DiscoveryExclusion, ...] = ()
        self.raise_exclusions = False
        self.exclusion_calls: list[dict[str, Any]] = []
        self.exclusion_error: DiscoveryExclusionPersistenceError | None = None
        self.completion_error = False

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
        if self.raise_recent_messages:
            raise RuntimeError("offline fake recent-message failure")
        self.recent_message_requests.append(kwargs)
        return self.recent_messages

    async def latest_recommendations(
        self,
        **kwargs: Any,
    ) -> tuple[DiscoveryRecommendation, ...]:
        if self.raise_latest_recommendations:
            raise RuntimeError("offline fake recommendation failure")
        self.latest_recommendation_requests.append(kwargs)
        return self.latest_recommendation_set

    async def list_exclusions(
        self,
        **_: Any,
    ) -> tuple[DiscoveryExclusion, ...]:
        if self.raise_exclusions:
            raise RuntimeError("offline fake exclusion failure")
        return self.exclusions

    async def apply_exclusion(
        self,
        **kwargs: Any,
    ) -> DiscoveryExclusionCommandResult:
        self.exclusion_calls.append(kwargs)
        if self.exclusion_error is not None:
            raise self.exclusion_error
        existing = next(
            (
                item
                for item in self.exclusions
                if item.listing_id == kwargs["listing_id"]
            ),
            None,
        )
        if existing is None:
            self.exclusions = (
                *self.exclusions,
                DiscoveryExclusion(
                    listing_id=kwargs["listing_id"],
                    reason_code=kwargs["reason_code"],
                    excluded_at=NOW,
                ),
            )
            self.session = replace(
                self.session,
                preference_version=self.session.preference_version + 1,
                updated_at=NOW,
            )
        return DiscoveryExclusionCommandResult(
            session_id=kwargs["session_id"],
            listing_id=kwargs["listing_id"],
            reason_code=(
                kwargs["reason_code"]
                if existing is None
                else existing.reason_code
            ),
            outcome="EXCLUDED" if existing is None else "ALREADY_EXCLUDED",
            preference_version=self.session.preference_version,
            excluded_count=len(self.exclusions),
            updated_at=NOW,
        )

    async def complete_turn(
        self,
        **kwargs: Any,
    ) -> tuple[DiscoverySession, AgentMessage]:
        if self.completion_error:
            raise RuntimeError("offline fake completion failure")
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
    def __init__(
        self,
        response: DiscoveryTurnResponse | None = None,
        audits: tuple[DiscoveryToolAudit, ...] = (),
    ) -> None:
        self.calls = 0
        self.last_request: dict[str, Any] | None = None
        self.audits = audits
        self.response = response or DiscoveryTurnResponse(
            outcome="ASK_CLARIFY",
            message="Which city or county should I use?",
            questions=("Which city or county should I use?",),
            preferenceState=DiscoveryPreferenceState(),
        )

    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        return DiscoveryRun(
            response=self.response,
            audits=self.audits,
        )


class FailingOrchestrator(FakeOrchestrator):
    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        raise RuntimeError("offline fake failure")


class BlockingOrchestrator(FakeOrchestrator):
    def __init__(self) -> None:
        super().__init__()
        self.entered = asyncio.Event()
        self.release = asyncio.Event()

    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        self.entered.set()
        await self.release.wait()
        return DiscoveryRun(response=self.response, audits=self.audits)


class NaturalStreamingOrchestrator(FakeOrchestrator):
    """Mimic one model decision whose output text reaches SSE before return."""

    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        callback = kwargs["text_delta"]
        message = self.response.message
        split = len(message) // 2
        await callback(message[:split])
        await callback(message[split:])
        return DiscoveryRun(
            response=self.response,
            audits=self.audits,
            streamed_during_run=True,
        )


class RejectedNaturalStreamingOrchestrator(FakeOrchestrator):
    async def run(self, **kwargs: Any) -> DiscoveryRun:
        await kwargs["text_delta"]("Safe partial answer")
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
        )


class ControlledFinalAnswerStreamer:
    def __init__(self) -> None:
        self.first_delta_sent = asyncio.Event()
        self.release = asyncio.Event()
        self.cancelled = False

    async def discovery_answer_stream(self, **kwargs: Any) -> Any:
        callback = kwargs["on_text_delta"]
        await callback("Which city ")
        self.first_delta_sent.set()
        try:
            await self.release.wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        await callback("should I search?")

        class Result:
            text = "Which city should I search?"

        return Result()


class StageFailingOrchestrator(FakeOrchestrator):
    def __init__(
        self,
        stage: DiscoveryFailureStage,
        kind: (
            DiscoveryToolFailureKind
            | DiscoveryProviderFailureKind
            | DiscoveryGraphFailureKind
            | None
        ) = None,
    ) -> None:
        super().__init__()
        self.stage = stage
        self.kind = kind

    async def run(self, **kwargs: Any) -> DiscoveryRun:
        self.calls += 1
        self.last_request = kwargs
        raise DiscoveryStageError(self.stage, kind=self.kind)


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


class DiscoveryPersistenceMappingTest(unittest.TestCase):
    def test_session_row_maps_to_discovery_session(self) -> None:
        session = _session(
            {
                "session_id": SESSION,
                "actor_user_id": ACTOR,
                "status": "OPEN",
                "preference_state_json": "{}",
                "preference_version": 0,
                "clarification_turn_count": 0,
                "clarification_question_count": 0,
                "created_at": NOW,
                "updated_at": NOW,
                "last_activity_at": NOW,
                "optimistic_version": 0,
            }
        )

        self.assertIsInstance(session, DiscoverySession)
        self.assertEqual(SESSION, session.session_id)
        self.assertEqual(ACTOR, session.actor_user_id)
        self.assertEqual(AgentSessionStatus.OPEN, session.status)

    def test_prior_user_message_decoder_accepts_nullable_metadata(self) -> None:
        message = _message(
            {
                "message_id": USER_MESSAGE,
                "session_id": SESSION,
                "actor_user_id": ACTOR,
                "role": "USER",
                "body": "Find a desk chair",
                "resolution_type": None,
                "sources_json": None,
                "actions_json": None,
                "created_at": NOW,
            }
        )

        self.assertEqual(AgentMessageRole.USER, message.role)
        self.assertIsNone(message.resolution_type)
        self.assertEqual((), message.sources)
        self.assertEqual((), message.actions)

    def test_prior_assistant_message_decoder_requires_structured_metadata(self) -> None:
        assistant = _message(
            {
                "message_id": ASSISTANT_MESSAGE,
                "session_id": SESSION,
                "actor_user_id": ACTOR,
                "role": "ASSISTANT",
                "body": "No verified matches yet.",
                "resolution_type": "NO_RESULTS",
                "sources_json": "[]",
                "actions_json": '[{"type":"DISCOVERY_RESULT","result":{}}]',
                "created_at": NOW,
            }
        )

        self.assertEqual(AgentResolutionType.NO_RESULTS, assistant.resolution_type)
        self.assertEqual("DISCOVERY_RESULT", assistant.actions[0]["type"])

        malformed_rows = (
            {
                "message_id": ASSISTANT_MESSAGE,
                "session_id": SESSION,
                "actor_user_id": ACTOR,
                "role": "ASSISTANT",
                "body": "Broken",
                "resolution_type": None,
                "sources_json": "[]",
                "actions_json": "[]",
                "created_at": NOW,
            },
            {
                "message_id": ASSISTANT_MESSAGE,
                "session_id": SESSION,
                "actor_user_id": ACTOR,
                "role": "ASSISTANT",
                "body": "Broken",
                "resolution_type": "NO_RESULTS",
                "sources_json": "[]",
                "actions_json": None,
                "created_at": NOW,
            },
        )
        for row in malformed_rows:
            with self.subTest(row=row), self.assertRaises(AgentPersistenceError):
                _message(row)

    def test_persisted_compare_result_matches_complete_live_wire_shape(self) -> None:
        turn = DiscoveryTurnResponse(
            outcome="COMPARE",
            message="Here is a current comparison.",
            recommendations=_recommendation_set()[:2],
            preferenceState=DiscoveryPreferenceState(query="desk chair"),
        )

        stored = _canonical_turn_result(turn)

        self.assertIn("selectedListingId", stored["preferenceState"])
        self.assertIsNone(stored["preferenceState"]["selectedListingId"])
        self.assertIn("thumbnailUrl", stored["recommendations"][0])
        self.assertIsNone(stored["recommendations"][0]["thumbnailUrl"])
        self.assertEqual(
            turn.model_dump(mode="json", by_alias=True, exclude_none=False),
            stored,
        )

    def test_sparse_legacy_compare_is_normalized_and_integrity_checked(self) -> None:
        turn = DiscoveryTurnResponse(
            outcome="COMPARE",
            message="Here is a current comparison.",
            recommendations=_recommendation_set()[:2],
            preferenceState=DiscoveryPreferenceState(query="desk chair"),
        )
        sparse = turn.model_dump(mode="json", by_alias=True, exclude_none=True)
        message = AgentMessage(
            message_id=ASSISTANT_MESSAGE,
            session_id=SESSION,
            actor_user_id=ACTOR,
            role=AgentMessageRole.ASSISTANT,
            body=turn.message,
            resolution_type=AgentResolutionType.ANSWERED,
            sources=(),
            actions=({"type": "DISCOVERY_RESULT", "result": sparse},),
            created_at=NOW,
        )

        history = _history_message(message)

        self.assertEqual("COMPARE", history.result.outcome)
        self.assertEqual(
            _canonical_turn_result(turn),
            history.result.model_dump(
                mode="json",
                by_alias=True,
                exclude_none=False,
            ),
        )
        with self.assertRaises(DiscoveryApiError) as raised:
            _history_message(replace(message, body="Different stored body."))
        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        with self.assertRaises(DiscoveryApiError):
            _history_message(
                replace(
                    message,
                    role=AgentMessageRole.USER,
                    resolution_type=AgentResolutionType.ANSWERED,
                )
            )


class MarketplaceDiscoveryServiceTest(unittest.IsolatedAsyncioTestCase):
    async def test_first_activity_precedes_blocked_private_orchestration_completion(self) -> None:
        conversation = FakeConversationRepository()
        orchestrator = BlockingOrchestrator()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), orchestrator
        )
        first_activity = asyncio.Event()
        stages: list[DiscoveryProgressStage] = []

        async def progress(stage: DiscoveryProgressStage) -> None:
            stages.append(stage)
            first_activity.set()

        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-early-activity",
            progress=progress,
        ))
        await first_activity.wait()
        await orchestrator.entered.wait()

        self.assertEqual(DiscoveryProgressStage.MESSAGE_ACCEPTED, stages[0])
        self.assertFalse(task.done())
        orchestrator.release.set()
        await task

    async def test_progress_starts_only_after_durable_user_acceptance(self) -> None:
        conversation = FakeConversationRepository()
        audit = DiscoveryToolAudit(
            sequence_number=1,
            tool_name="search_public_listings",
            result="SUCCEEDED",
            latency_ms=4,
            argument_hash="a" * 64,
            result_hash="b" * 64,
            source_refs=(),
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(audits=(audit,)),
        )
        stages: list[DiscoveryProgressStage] = []

        async def progress(stage: DiscoveryProgressStage) -> None:
            if stage == DiscoveryProgressStage.MESSAGE_ACCEPTED:
                self.assertEqual("Find a desk chair", conversation.user.body)
                self.assertFalse(conversation.completed)
            stages.append(stage)

        await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-progress-order",
            progress=progress,
        )

        self.assertEqual(
            [DiscoveryProgressStage.MESSAGE_ACCEPTED],
            stages,
        )
        self.assertTrue(conversation.completed)

    async def test_completed_replay_emits_acceptance_without_orchestrator_work(self) -> None:
        conversation = FakeConversationRepository()
        conversation.begin_result = BeginInvocationResult.DEDUPLICATED_SUCCEEDED
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
        )
        stages: list[DiscoveryProgressStage] = []

        async def progress(stage: DiscoveryProgressStage) -> None:
            stages.append(stage)

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body=conversation.user.body,
            correlation_id="disc-progress-replay",
            progress=progress,
        )

        self.assertEqual([DiscoveryProgressStage.MESSAGE_ACCEPTED], stages)
        self.assertEqual(0, orchestrator.calls)
        self.assertEqual(conversation.assistant.body, response.result.message)

    async def test_final_text_delta_arrives_before_provider_completion_and_persistence(self) -> None:
        conversation = FakeConversationRepository()

        class ControlledNaturalOrchestrator(FakeOrchestrator):
            def __init__(self) -> None:
                super().__init__(response=DiscoveryTurnResponse(
                    outcome="ANSWER",
                    message="Which city should I search?",
                    preferenceState=DiscoveryPreferenceState(),
                ))
                self.first_delta_sent = asyncio.Event()
                self.release = asyncio.Event()

            async def run(self, **kwargs: Any) -> DiscoveryRun:
                await kwargs["text_delta"]("Which city ")
                self.first_delta_sent.set()
                await self.release.wait()
                await kwargs["text_delta"]("should I search?")
                return DiscoveryRun(
                    response=self.response,
                    audits=(),
                    streamed_during_run=True,
                )

        orchestrator = ControlledNaturalOrchestrator()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
        )
        deltas: list[str] = []
        finalized: list[str] = []
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-true-stream-order",
            text_delta=lambda value: _record_text(deltas, value),
            finalized=lambda value: _record_text(finalized, value),
        ))

        await orchestrator.first_delta_sent.wait()
        self.assertEqual(["Which city "], deltas)
        self.assertFalse(conversation.completed)
        self.assertEqual([], finalized)
        orchestrator.release.set()
        response = await task

        self.assertEqual("Which city should I search?", response.result.message)
        self.assertTrue(conversation.completed)
        self.assertEqual([ASSISTANT_MESSAGE], finalized)

    async def test_direct_conversation_streams_canonical_text_without_provider(self) -> None:
        conversation = FakeConversationRepository()
        answer = "Hi! How can I help with the marketplace today?"
        orchestrator = FakeOrchestrator(response=DiscoveryTurnResponse(
            outcome="ANSWER",
            intent="GENERAL_CONVERSATION",
            message=answer,
            preferenceState=DiscoveryPreferenceState(),
        ))
        streamer = ControlledFinalAnswerStreamer()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
            streamer,
        )
        deltas: list[str] = []

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="hi",
            correlation_id="disc-direct-no-provider",
            text_delta=lambda value: _record_text(deltas, value),
        )

        self.assertEqual([answer], deltas)
        self.assertEqual(answer, response.result.message)
        self.assertFalse(streamer.first_delta_sent.is_set())
        self.assertEqual((), response.result.recommendations)

    async def test_natural_model_content_is_not_rewritten_or_replayed(self) -> None:
        conversation = FakeConversationRepository()
        answer = "I am the marketplace assistant. How can I help?"
        orchestrator = NaturalStreamingOrchestrator(response=DiscoveryTurnResponse(
            outcome="ANSWER",
            intent="GENERAL_CONVERSATION",
            message=answer,
            preferenceState=DiscoveryPreferenceState(),
        ))
        streamer = ControlledFinalAnswerStreamer()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), orchestrator, streamer,
        )
        deltas: list[str] = []

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="who are you",
            correlation_id="disc-natural-one-provider-response",
            text_delta=lambda value: _record_text(deltas, value),
        )

        self.assertEqual(answer, "".join(deltas))
        self.assertEqual(2, len(deltas))
        self.assertEqual(answer, response.result.message)
        self.assertFalse(streamer.first_delta_sent.is_set())
        self.assertTrue(conversation.completed)

    async def test_rejected_natural_stream_persists_exact_partial_for_retry(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            RejectedNaturalStreamingOrchestrator(),
        )
        deltas: list[str] = []

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body="help me",
                correlation_id="disc-rejected-natural-stream",
                text_delta=lambda value: _record_text(deltas, value),
            )

        self.assertEqual(DiscoveryApiErrorCode.STREAM_INTERRUPTED, raised.exception.code)
        self.assertEqual(["Safe partial answer"], deltas)
        self.assertEqual("Safe partial answer", conversation.failure_assistant.body)
        self.assertEqual(AgentResolutionType.PARTIAL, conversation.failure_assistant.resolution_type)

    async def test_category_unavailable_streams_authoritative_text_without_provider(self) -> None:
        """A proven zero count must not depend on another provider request."""

        conversation = FakeConversationRepository()
        answer = "I couldn't find any office chair listings right now."
        orchestrator = FakeOrchestrator(response=DiscoveryTurnResponse(
            outcome="NO_RESULTS",
            intent="MARKETPLACE_DISCOVERY",
            message=answer,
            preferenceState=DiscoveryPreferenceState(
                requestedCategory="office chair",
                categoryAvailability="UNAVAILABLE",
                categoryInventoryCount=0,
                lastSearchOutcome="CATEGORY_UNAVAILABLE",
            ),
            searchOutcome=DiscoverySearchOutcome(
                mode="AVAILABILITY_PROBE",
                searchExecuted=False,
                category="office chair",
                totalActiveCategoryInventory=0,
                exactMatchCount=None,
                appliedFilters=(),
                relaxableFilters=(),
                reason="CATEGORY_UNAVAILABLE",
                retryable=False,
            ),
        ))
        streamer = ControlledFinalAnswerStreamer()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
            streamer,
        )
        deltas: list[str] = []

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="under 200",
            correlation_id="disc-unavailable-no-provider",
            text_delta=lambda value: _record_text(deltas, value),
        )

        self.assertEqual([answer], deltas)
        self.assertEqual(answer, response.result.message)
        self.assertFalse(streamer.first_delta_sent.is_set())
        self.assertTrue(conversation.completed)

    async def test_finalization_failure_after_delta_persists_retryable_partial(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        repository.completion_error = True
        service = MarketplaceDiscoveryService(repository, FakeOrchestrator())
        deltas: list[str] = []

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body="Find a desk chair",
                correlation_id="disc-finalization-partial",
                text_delta=lambda value: _record_text(deltas, value),
            )

        self.assertEqual(DiscoveryApiErrorCode.STREAM_INTERRUPTED, raised.exception.code)
        self.assertEqual("".join(deltas), conversation.failure_assistant.body)
        self.assertEqual(
            AgentResolutionType.PARTIAL,
            conversation.failure_assistant.resolution_type,
        )
        self.assertIn(
            "DISCOVERY_FINAL_ANSWER_FINALIZATION_FAILED",
            conversation.failure_codes,
        )

    async def test_cancellation_during_final_stream_persists_exact_partial(self) -> None:
        conversation = FakeConversationRepository()

        class ControlledNaturalOrchestrator(FakeOrchestrator):
            def __init__(self) -> None:
                super().__init__()
                self.first_delta_sent = asyncio.Event()
                self.cancelled = False

            async def run(self, **kwargs: Any) -> DiscoveryRun:
                await kwargs["text_delta"]("Which city ")
                self.first_delta_sent.set()
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    self.cancelled = True
                    raise
                raise AssertionError("unreachable")

        orchestrator = ControlledNaturalOrchestrator()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
        )
        deltas: list[str] = []
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-true-stream-cancel",
            text_delta=lambda value: _record_text(deltas, value),
        ))
        await orchestrator.first_delta_sent.wait()
        task.cancel()

        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(orchestrator.cancelled)
        self.assertEqual(["Which city "], deltas)
        self.assertEqual("Which city ", conversation.failure_assistant.body)
        self.assertEqual(
            AgentResolutionType.PARTIAL,
            conversation.failure_assistant.resolution_type,
        )
        self.assertIn(
            "DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED",
            conversation.failure_codes,
        )

    async def test_post_accept_cancellation_before_text_persists_retryable_partial(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        entered = asyncio.Event()

        class BlockingOrchestrator(FakeOrchestrator):
            async def run(self, **kwargs: Any) -> DiscoveryRun:
                self.calls += 1
                self.last_request = kwargs
                entered.set()
                await asyncio.Event().wait()
                raise AssertionError("unreachable")

        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            BlockingOrchestrator(),
        )
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-stream-cancel",
            progress=lambda _stage: asyncio.sleep(0),
        ))
        await entered.wait()
        task.cancel()

        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(
            "Response stopped before the answer began.",
            conversation.failure_assistant.body,
        )
        self.assertEqual(
            AgentResolutionType.PARTIAL,
            conversation.failure_assistant.resolution_type,
        )
        self.assertIn(
            "DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED",
            conversation.failure_codes,
        )

    async def test_cancellation_while_acceptance_is_delivered_persists_retry_state(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        accepted = asyncio.Event()

        async def blocked_progress(stage: DiscoveryProgressStage) -> None:
            self.assertEqual(DiscoveryProgressStage.MESSAGE_ACCEPTED, stage)
            accepted.set()
            await asyncio.Event().wait()

        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair",
            correlation_id="disc-accept-cancel",
            progress=blocked_progress,
        ))
        await accepted.wait()
        task.cancel()

        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(
            "Response stopped before the answer began.",
            conversation.failure_assistant.body,
        )
        self.assertIn(
            "DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED",
            conversation.failure_codes,
        )

    async def test_get_session_returns_separate_bounded_exclusion_ledger(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        repository.exclusions = (
            DiscoveryExclusion(LISTING_1, "TOO_FAR", NOW),
        )
        service = MarketplaceDiscoveryService(repository, FakeOrchestrator())

        response = await service.get_session(
            actor_user_id=ACTOR,
            session_id=SESSION,
        )

        self.assertEqual(DiscoveryPreferenceState(), response.preference_state)
        self.assertEqual(1, len(response.exclusions))
        self.assertEqual(LISTING_1, response.exclusions[0].listing_id)
        self.assertNotIn(
            "excludedListingIds",
            response.preference_state.model_dump(by_alias=True),
        )

    async def test_exclusion_uses_separate_key_and_creates_no_chat_turn(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        service = MarketplaceDiscoveryService(repository, FakeOrchestrator())

        response = await service.exclude_listing(
            actor_user_id=ACTOR,
            session_id=SESSION,
            idempotency_key="exclude-listing-0001",
            expected_preference_version=0,
            listing_id=LISTING_1,
            reason_code=DiscoveryExclusionReason.NOT_RELEVANT,
        )

        self.assertEqual("EXCLUDED", response.outcome)
        self.assertEqual(1, response.preference_version)
        self.assertEqual(1, response.excluded_count)
        self.assertEqual([], repository.recent_message_requests)
        self.assertFalse(conversation.completed)

    async def test_new_key_for_existing_exclusion_keeps_version_and_reason(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        service = MarketplaceDiscoveryService(repository, None)
        first = await service.exclude_listing(
            actor_user_id=ACTOR,
            session_id=SESSION,
            idempotency_key="exclude-listing-0001",
            expected_preference_version=0,
            listing_id=LISTING_1,
            reason_code=DiscoveryExclusionReason.TOO_FAR,
        )
        second = await service.exclude_listing(
            actor_user_id=ACTOR,
            session_id=SESSION,
            idempotency_key="exclude-listing-0002",
            expected_preference_version=1,
            listing_id=LISTING_1,
            reason_code=DiscoveryExclusionReason.OTHER,
        )

        self.assertEqual("EXCLUDED", first.outcome)
        self.assertEqual("ALREADY_EXCLUDED", second.outcome)
        self.assertEqual(1, second.preference_version)
        self.assertEqual(DiscoveryExclusionReason.TOO_FAR, second.reason_code)
        self.assertFalse(conversation.completed)

    async def test_exclusion_maps_fixed_persistence_failures(self) -> None:
        cases = (
            (
                DiscoveryExclusionPersistenceErrorCode.EXCLUSION_NOT_AVAILABLE,
                DiscoveryApiErrorCode.EXCLUSION_NOT_AVAILABLE,
            ),
            (
                DiscoveryExclusionPersistenceErrorCode.EXCLUSION_LIMIT_REACHED,
                DiscoveryApiErrorCode.EXCLUSION_LIMIT_REACHED,
            ),
            (
                DiscoveryExclusionPersistenceErrorCode.REQUEST_CONFLICT,
                DiscoveryApiErrorCode.REQUEST_CONFLICT,
            ),
            (
                DiscoveryExclusionPersistenceErrorCode.PREFERENCE_VERSION_CONFLICT,
                DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
            ),
        )
        for persistence_code, api_code in cases:
            with self.subTest(code=persistence_code):
                repository = FakeDiscoveryRepository(FakeConversationRepository())
                repository.exclusion_error = DiscoveryExclusionPersistenceError(
                    persistence_code
                )
                service = MarketplaceDiscoveryService(repository, FakeOrchestrator())
                with self.assertRaises(DiscoveryApiError) as raised:
                    await service.exclude_listing(
                        actor_user_id=ACTOR,
                        session_id=SESSION,
                        idempotency_key="exclude-listing-0001",
                        expected_preference_version=0,
                        listing_id=LISTING_1,
                        reason_code=None,
                    )
                self.assertEqual(api_code, raised.exception.code)

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
        self.assertEqual(CLIENT_MESSAGE, page.data[0].client_message_id)

    async def test_history_rejects_user_without_canonical_client_message_id(self) -> None:
        conversation = FakeConversationRepository()
        conversation.message_page = MessagePage(
            messages=(
                replace(conversation.user, client_message_id="not-canonical"),
            ),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.list_messages(
                actor_user_id=ACTOR,
                session_id=SESSION,
                limit=1,
                cursor=None,
            )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)

    async def test_history_reconciles_legacy_timeout_with_response_retry(self) -> None:
        conversation = FakeConversationRepository()
        conversation.message_page = MessagePage(
            messages=(
                replace(
                    conversation.user,
                    invocation_id=INVOCATION,
                    invocation_status=AgentInvocationStatus.FAILED,
                    invocation_error_code=(
                        "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_"
                        "QUERY_EMBEDDING_TIMEOUT"
                    ),
                    invocation_retry_count=0,
                    invocation_assistant_message_id=None,
                    invocation_user_message_id=USER_MESSAGE,
                ),
            ),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )

        page = await service.list_messages(
            actor_user_id=ACTOR,
            session_id=SESSION,
            limit=50,
            cursor=None,
        )

        self.assertEqual("HANDOFF", page.data[0].response_failure.outcome)
        self.assertEqual(USER_MESSAGE, page.data[0].response_retry.user_message_id)
        self.assertEqual(INVOCATION, page.data[0].response_retry.invocation_id)

    async def test_history_reconciles_any_failed_orphan_with_safe_retry(self) -> None:
        """A legacy Product/provider failure cannot leave a committed USER invisible."""

        conversation = FakeConversationRepository()
        conversation.message_page = MessagePage(
            messages=(replace(
                conversation.user,
                invocation_id=INVOCATION,
                invocation_status=AgentInvocationStatus.FAILED,
                invocation_error_code=(
                    "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_"
                    "PRODUCT_HYBRID_HTTP"
                ),
                invocation_retry_count=0,
                invocation_assistant_message_id=None,
                invocation_user_message_id=USER_MESSAGE,
            ),),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        page = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION, limit=50, cursor=None
        )

        orphan = page.data[0]
        self.assertEqual("HANDOFF", orphan.response_failure.outcome)
        self.assertIn("retry this response", orphan.response_failure.message)
        self.assertEqual(USER_MESSAGE, orphan.response_retry.user_message_id)
        self.assertEqual(INVOCATION, orphan.response_retry.invocation_id)

    async def test_history_exposes_structuring_failure_response_retry(self) -> None:
        conversation = FakeConversationRepository()
        conversation.message_page = MessagePage(
            messages=(
                replace(
                    conversation.assistant,
                    invocation_id=INVOCATION,
                    invocation_status=AgentInvocationStatus.FAILED,
                    invocation_error_code=(
                        "DISCOVERY_ORCHESTRATOR_RUN_FAILED_"
                        "STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA"
                    ),
                    invocation_retry_count=0,
                    invocation_assistant_message_id=ASSISTANT_MESSAGE,
                    invocation_user_message_id=USER_MESSAGE,
                    resolution_type=AgentResolutionType.HANDOFF,
                    body=(
                        "I couldn't safely finish structuring the verified search "
                        "response. You can retry this response without sending your "
                        "message again."
                    ),
                    actions=(
                        {
                            "type": "DISCOVERY_RESULT",
                            "result": {
                                "outcome": "HANDOFF",
                                "message": (
                                    "I couldn't safely finish structuring the verified "
                                    "search response. You can retry this response without "
                                    "sending your message again."
                                ),
                                "questions": [],
                                "recommendations": [],
                                "preferenceState": {},
                            },
                        },
                    ),
                ),
            ),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )

        page = await service.list_messages(
            actor_user_id=ACTOR,
            session_id=SESSION,
            limit=50,
            cursor=None,
        )

        self.assertEqual("HANDOFF", page.data[0].result.outcome)
        self.assertEqual(USER_MESSAGE, page.data[0].response_retry.user_message_id)
        self.assertEqual(INVOCATION, page.data[0].response_retry.invocation_id)

    async def test_history_accepts_retryable_partial_assistant(self) -> None:
        conversation = FakeConversationRepository()
        partial = "A verified partial answer.\nRetry when ready."
        conversation.message_page = MessagePage(
            messages=(replace(
                conversation.assistant,
                invocation_id=INVOCATION,
                invocation_status=AgentInvocationStatus.FAILED,
                invocation_error_code="DISCOVERY_FINAL_ANSWER_FINALIZATION_FAILED",
                invocation_retry_count=0,
                invocation_assistant_message_id=ASSISTANT_MESSAGE,
                invocation_user_message_id=USER_MESSAGE,
                resolution_type=AgentResolutionType.PARTIAL,
                body=partial,
                actions=({
                    "type": "DISCOVERY_RESULT",
                    "result": {
                        "outcome": "HANDOFF",
                        "message": partial,
                        "questions": [],
                        "recommendations": [],
                        "preferenceState": {},
                    },
                },),
            ),),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        page = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION, limit=50, cursor=None
        )

        self.assertEqual("PARTIAL", page.data[0].resolution_type)
        self.assertEqual(partial, page.data[0].result.message)
        self.assertEqual(USER_MESSAGE, page.data[0].response_retry.user_message_id)

    async def test_history_accepts_conversational_customer_service_answer(self) -> None:
        conversation = FakeConversationRepository()
        answer = "Hi! How can I help with the marketplace today?"
        conversation.message_page = MessagePage(
            messages=(replace(
                conversation.assistant,
                resolution_type=AgentResolutionType.ANSWERED,
                body=answer,
                actions=({
                    "type": "DISCOVERY_RESULT",
                    "result": {
                        "outcome": "ANSWER",
                        "intent": "GENERAL_CONVERSATION",
                        "message": answer,
                        "questions": [],
                        "recommendations": [],
                        "preferenceState": {},
                    },
                },),
            ),),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        page = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION, limit=50, cursor=None
        )

        self.assertEqual("ANSWER", page.data[0].result.outcome)
        self.assertEqual(
            "GENERAL_CONVERSATION", page.data[0].result.intent.value
        )
        self.assertEqual((), page.data[0].result.recommendations)

    async def test_history_exposes_zero_text_cancellation_as_response_retry(self) -> None:
        conversation = FakeConversationRepository()
        stopped = "Response stopped before the answer began."
        conversation.message_page = MessagePage(
            messages=(replace(
                conversation.assistant,
                invocation_id=INVOCATION,
                invocation_status=AgentInvocationStatus.FAILED,
                invocation_error_code="DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED",
                invocation_retry_count=0,
                invocation_assistant_message_id=ASSISTANT_MESSAGE,
                invocation_user_message_id=USER_MESSAGE,
                resolution_type=AgentResolutionType.PARTIAL,
                body=stopped,
                actions=({
                    "type": "DISCOVERY_RESULT",
                    "result": {
                        "outcome": "HANDOFF",
                        "message": stopped,
                        "questions": [],
                        "recommendations": [],
                        "preferenceState": {},
                    },
                },),
            ),),
            next_cursor=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        page = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION, limit=50, cursor=None
        )

        self.assertEqual("PARTIAL", page.data[0].resolution_type)
        self.assertEqual(stopped, page.data[0].result.message)
        self.assertEqual(USER_MESSAGE, page.data[0].response_retry.user_message_id)

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

    async def test_success_response_preserves_exact_submitted_user_body(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FakeOrchestrator(),
        )
        prompt = "Find a desk chair under $100."

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body=prompt,
            correlation_id="disc-service-body-success",
        )

        self.assertEqual(prompt, response.user_message.body)
        self.assertEqual(prompt, conversation.user.body)

    async def test_product_retrieval_provenance_is_persisted_in_tool_audit(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        source_refs = (
            {
                "listingId": LISTING_1,
                "listingVersion": 7,
                "finalRank": 1,
                "mode": "HYBRID",
                "matchedBy": ["LEXICAL", "VECTOR"],
                "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
                "checkedAt": NOW.isoformat(),
            },
        )
        audit = DiscoveryToolAudit(
            sequence_number=1,
            tool_name="SEARCH_INDIVIDUAL",
            result="SUCCEEDED",
            latency_ms=4,
            argument_hash="a" * 64,
            result_hash="b" * 64,
            source_refs=source_refs,
        )
        service = MarketplaceDiscoveryService(
            repository,
            FakeOrchestrator(audits=(audit,)),
        )

        await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Find a desk chair under $100.",
            correlation_id="disc-service-source-refs",
        )

        self.assertEqual(1, len(conversation.tool_calls))
        self.assertEqual(source_refs, conversation.tool_calls[0]["source_refs"])

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
        self.assertEqual(
            {"session_id": SESSION, "actor_user_id": ACTOR},
            repository.latest_recommendation_requests[0],
        )

    async def test_orchestration_receives_actor_scoped_latest_recommendations(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        repository.latest_recommendation_set = _recommendation_set()
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Compare the first two options",
            correlation_id="disc-service-compare",
        )

        self.assertEqual(
            repository.latest_recommendation_set,
            orchestrator.last_request["previous_recommendations"],
        )

    async def test_compare_persists_as_answered_without_changing_preferences(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        recommendations = _recommendation_set()
        repository.latest_recommendation_set = recommendations
        comparison = DiscoveryTurnResponse(
            outcome="COMPARE",
            message="Compared two current listings.",
            recommendations=recommendations[:2],
            preferenceState=repository.session.preference_state,
        )
        service = MarketplaceDiscoveryService(
            repository,
            FakeOrchestrator(comparison),
        )

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
            expected_preference_version=0,
            body="Compare the first two options",
            correlation_id="disc-service-compare-result",
        )

        self.assertEqual("COMPARE", response.result.outcome)
        self.assertEqual(1, response.preference_version)
        self.assertEqual(
            DiscoveryPreferenceState(),
            repository.session.preference_state,
        )
        self.assertEqual(
            AgentResolutionType.ANSWERED,
            conversation.assistant.resolution_type,
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

    async def test_failed_invocation_preserves_exact_submitted_user_body(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            FailingOrchestrator(),
        )
        prompt = "Find a desk chair under $100."

        with self.assertRaises(DiscoveryApiError):
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body=prompt,
                correlation_id="disc-service-body-failure",
            )

        self.assertTrue(conversation.failed)
        self.assertEqual(prompt, conversation.user.body)
        self.assertEqual(
            ["DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_INVOKE_START"],
            conversation.failure_codes,
        )

    async def test_orchestrator_stage_failure_is_persisted_without_prompt_or_exception_text(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(DiscoveryFailureStage.PROVIDER_TIMEOUT),
        )
        prompt = "Find a desk chair under $100. SECRET-token-value"

        with self.assertLogs("msb_agent_service.discovery_api", level="WARNING") as logs:
            with self.assertRaises(DiscoveryApiError) as raised:
                await service.send_message(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    client_message_id=CLIENT_MESSAGE,
                    expected_preference_version=0,
                    body=prompt,
                    correlation_id="disc-service-stage-safe",
                )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertEqual(
            ["DISCOVERY_ORCHESTRATOR_RUN_FAILED_PROVIDER_TIMEOUT"],
            conversation.failure_codes,
        )
        joined_logs = "\n".join(logs.output)
        self.assertIn("AGENT_DISCOVERY_FAILURE_STAGE stage=provider_timeout", joined_logs)
        self.assertIn("correlationId=disc-service-stage-safe", joined_logs)
        self.assertNotIn("SECRET-token-value", joined_logs)
        self.assertNotIn(prompt, joined_logs)
        self.assertNotIn(ACTOR, joined_logs)
        self.assertNotIn(SESSION, joined_logs)

        self.assertIsNotNone(conversation.failure_assistant)
        assert conversation.failure_assistant is not None
        self.assertIn("retry this response", conversation.failure_assistant.body)
        self.assertNotIn("provider", conversation.failure_assistant.body.lower())

    async def test_tool_failure_kind_is_persisted_and_logged_without_sensitive_data(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(
                DiscoveryFailureStage.TOOL_EXECUTION,
                DiscoveryToolFailureKind.ARGUMENTS_SCHEMA_VALIDATION,
            ),
        )
        prompt = "Find a desk chair under $100. SECRET-query-value"

        with self.assertLogs("msb_agent_service.discovery_api", level="WARNING") as logs:
            with self.assertRaises(DiscoveryApiError) as raised:
                await service.send_message(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    client_message_id=CLIENT_MESSAGE,
                    expected_preference_version=0,
                    body=prompt,
                    correlation_id="disc-service-tool-kind-safe",
                )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertEqual(
            [
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_"
                "ARGUMENTS_SCHEMA_VALIDATION"
            ],
            conversation.failure_codes,
        )
        joined_logs = "\n".join(logs.output)
        self.assertIn(
            "AGENT_DISCOVERY_FAILURE_STAGE stage=tool_execution "
            "kind=arguments_schema_validation",
            joined_logs,
        )
        self.assertNotIn("SECRET-query-value", joined_logs)
        self.assertNotIn(prompt, joined_logs)
        self.assertNotIn(ACTOR, joined_logs)
        self.assertNotIn(SESSION, joined_logs)

    async def test_query_embedding_timeout_persists_guarded_terminal_assistant(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(
                DiscoveryFailureStage.TOOL_EXECUTION,
                DiscoveryToolFailureKind.QUERY_EMBEDDING_TIMEOUT,
            ),
        )

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body="Irvine, Orange County.",
                correlation_id="disc-query-timeout-terminal",
            )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertIsNotNone(conversation.failure_assistant)
        assert conversation.failure_assistant is not None
        stored = conversation.failure_assistant.actions[0]["result"]
        self.assertEqual("HANDOFF", stored["outcome"])
        self.assertEqual([], stored["recommendations"])
        self.assertNotIn("embedding", conversation.failure_assistant.body.lower())

    async def test_structuring_failure_persists_safe_retriable_assistant(self) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(
                DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
                DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
            ),
        )
        prompt = "Find a desk chair. SECRET-structure-value"

        with self.assertLogs("msb_agent_service.discovery_api", level="WARNING") as logs:
            with self.assertRaises(DiscoveryApiError):
                await service.send_message(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    client_message_id=CLIENT_MESSAGE,
                    expected_preference_version=0,
                    body=prompt,
                    correlation_id="disc-structure-terminal",
                )

        self.assertEqual(
            [
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_"
                "STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA"
            ],
            conversation.failure_codes,
        )
        self.assertIsNotNone(conversation.failure_assistant)
        assert conversation.failure_assistant is not None
        self.assertIn("retry this response", conversation.failure_assistant.body)
        joined_logs = "\n".join(logs.output)
        self.assertIn(
            "stage=structured_response_parse kind=provider_response_schema",
            joined_logs,
        )
        self.assertNotIn("SECRET-structure-value", joined_logs)
        self.assertNotIn(prompt, joined_logs)

    async def test_structuring_response_retry_reuses_committed_user_row(self) -> None:
        conversation = FakeConversationRepository()
        conversation.begin_result = BeginInvocationResult.RETRY_STARTED
        conversation.retry_lookup_invocation = replace(
            conversation.retry_lookup_invocation,
            error_code=(
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_"
                "STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA"
            ),
        )
        repository = FakeDiscoveryRepository(conversation)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        response = await service.retry_response(
            actor_user_id=ACTOR,
            session_id=SESSION,
            user_message_id=USER_MESSAGE,
            expected_preference_version=0,
            correlation_id="disc-structure-response-retry",
        )

        self.assertEqual(USER_MESSAGE, response.user_message.id)
        self.assertEqual(1, conversation.begin_calls)
        self.assertEqual(0, conversation.created_user_rows)
        self.assertEqual(1, orchestrator.calls)

    async def test_response_retry_reuses_committed_user_without_new_user_row(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        conversation.begin_result = BeginInvocationResult.RETRY_STARTED
        repository = FakeDiscoveryRepository(conversation)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        response = await service.retry_response(
            actor_user_id=ACTOR,
            session_id=SESSION,
            user_message_id=USER_MESSAGE,
            expected_preference_version=0,
            correlation_id="disc-explicit-response-retry",
        )

        self.assertEqual(USER_MESSAGE, response.user_message.id)
        self.assertEqual(1, conversation.begin_calls)
        self.assertEqual(0, conversation.created_user_rows)
        self.assertEqual(1, orchestrator.calls)
        self.assertEqual(1, repository.session.preference_version)

    async def test_orphan_response_retry_accepts_nonlegacy_failure_code(self) -> None:
        conversation = FakeConversationRepository()
        conversation.begin_result = BeginInvocationResult.RETRY_STARTED
        conversation.retry_lookup_invocation = replace(
            conversation.retry_lookup_invocation,
            error_code=(
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_"
                "PRODUCT_HYBRID_HTTP"
            ),
        )
        repository = FakeDiscoveryRepository(conversation)
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(repository, orchestrator)

        response = await service.retry_response(
            actor_user_id=ACTOR,
            session_id=SESSION,
            user_message_id=USER_MESSAGE,
            expected_preference_version=0,
            correlation_id="disc-orphan-response-retry",
        )

        self.assertEqual(USER_MESSAGE, response.user_message.id)
        self.assertEqual(0, conversation.created_user_rows)
        self.assertEqual(1, orchestrator.calls)

    async def test_response_retry_rejects_nonfailed_invocation_without_generation(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        conversation.retry_lookup_invocation = _invocation("disc-pending-retry")
        orchestrator = FakeOrchestrator()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            orchestrator,
        )

        with self.assertRaises(DiscoveryApiError) as raised:
            await service.retry_response(
                actor_user_id=ACTOR,
                session_id=SESSION,
                user_message_id=USER_MESSAGE,
                expected_preference_version=0,
                correlation_id="disc-retry-pending",
            )

        self.assertEqual(
            DiscoveryApiErrorCode.MESSAGE_IN_PROGRESS,
            raised.exception.code,
        )
        self.assertEqual(0, conversation.begin_calls)
        self.assertEqual(0, orchestrator.calls)

    async def test_provider_failure_kind_is_persisted_and_logged_without_sensitive_data(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(
                DiscoveryFailureStage.PROVIDER_REQUEST_START,
                DiscoveryProviderFailureKind.STATUS,
            ),
        )
        prompt = "Find a desk chair under $100. SECRET-provider-value"

        with self.assertLogs("msb_agent_service.discovery_api", level="WARNING") as logs:
            with self.assertRaises(DiscoveryApiError) as raised:
                await service.send_message(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    client_message_id=CLIENT_MESSAGE,
                    expected_preference_version=0,
                    body=prompt,
                    correlation_id="disc-service-provider-kind-safe",
                )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertEqual(
            [
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_PROVIDER_REQUEST_START_"
                "STATUS"
            ],
            conversation.failure_codes,
        )
        joined_logs = "\n".join(logs.output)
        self.assertIn(
            "AGENT_DISCOVERY_FAILURE_STAGE stage=provider_request_start "
            "kind=provider_status",
            joined_logs,
        )
        self.assertNotIn("SECRET-provider-value", joined_logs)
        self.assertNotIn(prompt, joined_logs)
        self.assertNotIn(ACTOR, joined_logs)
        self.assertNotIn(SESSION, joined_logs)

    async def test_graph_failure_kind_is_persisted_and_logged_without_sensitive_data(
        self,
    ) -> None:
        conversation = FakeConversationRepository()
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation),
            StageFailingOrchestrator(
                DiscoveryFailureStage.GRAPH_INVOKE_START,
                DiscoveryGraphFailureKind.RECURSION_LIMIT,
            ),
        )
        prompt = "Find a desk chair under $100. SECRET-graph-value"

        with self.assertLogs("msb_agent_service.discovery_api", level="WARNING") as logs:
            with self.assertRaises(DiscoveryApiError) as raised:
                await service.send_message(
                    actor_user_id=ACTOR,
                    session_id=SESSION,
                    client_message_id=CLIENT_MESSAGE,
                    expected_preference_version=0,
                    body=prompt,
                    correlation_id="disc-service-graph-kind-safe",
                )

        self.assertEqual(DiscoveryApiErrorCode.UNAVAILABLE, raised.exception.code)
        self.assertEqual(
            [
                "DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_INVOKE_START_"
                "RECURSION_LIMIT"
            ],
            conversation.failure_codes,
        )
        joined_logs = "\n".join(logs.output)
        self.assertIn(
            "AGENT_DISCOVERY_FAILURE_STAGE stage=graph_invoke_start "
            "kind=recursion_limit",
            joined_logs,
        )
        self.assertNotIn("SECRET-graph-value", joined_logs)
        self.assertNotIn(prompt, joined_logs)
        self.assertNotIn(ACTOR, joined_logs)
        self.assertNotIn(SESSION, joined_logs)

    async def test_bounded_failure_stages_are_persisted_without_exception_text(
        self,
    ) -> None:
        cases: tuple[tuple[str, str], ...] = (
            ("raise_recent_messages", "DISCOVERY_PRIOR_MESSAGE_LOAD_FAILED"),
            ("raise_latest_recommendations", "DISCOVERY_RECOMMENDATION_LOAD_FAILED"),
            ("raise_exclusions", "DISCOVERY_EXCLUSION_LOAD_FAILED"),
        )
        for attribute, expected_code in cases:
            with self.subTest(stage=expected_code):
                conversation = FakeConversationRepository()
                repository = FakeDiscoveryRepository(conversation)
                setattr(repository, attribute, True)
                service = MarketplaceDiscoveryService(repository, FakeOrchestrator())

                with self.assertRaises(DiscoveryApiError) as raised:
                    await service.send_message(
                        actor_user_id=ACTOR,
                        session_id=SESSION,
                        client_message_id=CLIENT_MESSAGE,
                        expected_preference_version=0,
                        body="Find a desk chair under $100.",
                        correlation_id="disc-service-stage",
                    )

                self.assertEqual(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    raised.exception.code,
                )
                self.assertEqual([expected_code], conversation.failure_codes)
                self.assertNotIn("offline fake", conversation.failure_codes[0])

    async def test_orchestrator_setup_stage_is_bounded_and_safe(self) -> None:
        conversation = FakeConversationRepository()
        repository = FakeDiscoveryRepository(conversation)
        repository.exclusions = (object(),)  # type: ignore[assignment]
        service = MarketplaceDiscoveryService(repository, FakeOrchestrator())

        with self.assertRaises(DiscoveryApiError):
            await service.send_message(
                actor_user_id=ACTOR,
                session_id=SESSION,
                client_message_id=CLIENT_MESSAGE,
                expected_preference_version=0,
                body="Find a desk chair under $100.",
                correlation_id="disc-service-setup-stage",
            )

        self.assertEqual(
            ["DISCOVERY_ORCHESTRATOR_SETUP_FAILED"],
            conversation.failure_codes,
        )

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
        self.assertEqual([], repository.latest_recommendation_requests)

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

    async def test_stop_marks_committed_pending_turn_retryable_without_new_user(self) -> None:
        conversation = FakeConversationRepository()
        conversation.retry_lookup_invocation = replace(
            conversation.retry_lookup_invocation,
            result_status=AgentInvocationStatus.PENDING,
            error_code=None,
            completed_at=None,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        response = await service.stop_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
        )

        self.assertEqual("STOPPED", response.outcome)
        self.assertEqual(0, conversation.created_user_rows)
        self.assertEqual(AgentResolutionType.PARTIAL, conversation.failure_assistant.resolution_type)

    async def test_stop_reconciles_graph_cancel_race_to_retryable_partial(self) -> None:
        conversation = FakeConversationRepository()
        conversation.retry_lookup_invocation = replace(
            conversation.retry_lookup_invocation,
            result_status=AgentInvocationStatus.FAILED,
            error_code="DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_CANCEL",
            assistant_message_id=None,
            completed_at=NOW,
        )
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        response = await service.stop_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
        )

        self.assertEqual("STOPPED", response.outcome)
        self.assertEqual(0, conversation.created_user_rows)
        self.assertEqual(
            "DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED",
            conversation.failure_assistant_calls[-1]["error_code"],
        )
        self.assertEqual(
            ("DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_CANCEL",),
            conversation.failure_assistant_calls[-1]["replace_failed_error_codes"],
        )
        self.assertEqual(
            AgentResolutionType.PARTIAL,
            conversation.failure_assistant.resolution_type,
        )

    async def test_stop_reports_not_committed_without_creating_history(self) -> None:
        conversation = FakeConversationRepository()
        conversation.client_lookup_missing = True
        service = MarketplaceDiscoveryService(
            FakeDiscoveryRepository(conversation), FakeOrchestrator()
        )

        response = await service.stop_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT_MESSAGE,
        )

        self.assertEqual("NOT_COMMITTED", response.outcome)
        self.assertIsNone(conversation.failure_assistant)
        self.assertEqual(0, conversation.created_user_rows)


def _recommendation_set() -> tuple[DiscoveryRecommendation, ...]:
    listing_ids = (
        "81ARZ3NDEKTSV4RRFFQ69G5FAC",
        "91ARZ3NDEKTSV4RRFFQ69G5FAC",
        "Z1ARZ3NDEKTSV4RRFFQ69G5FAC",
    )
    return tuple(
        DiscoveryRecommendation.model_validate(
            {
                "listingId": listing_id,
                "title": f"Current option {index}",
                "categoryId": "01C00000000000000000000001",
                "categoryName": "Furniture",
                "condition": "GOOD",
                "priceAmount": str(20 + index),
                "currency": "USD",
                "publicCity": "Irvine",
                "publicRegion": "Orange",
                "thumbnailUrl": None,
                "sellerType": "INDIVIDUAL",
                "matchReason": "Matches the current request.",
                "constraintCoverage": ["QUERY"],
                "provenance": {
                    "listingId": listing_id,
                    "checkedAt": NOW.isoformat(),
                    "responseHash": str(index).rjust(64, "0"),
                },
            }
        )
        for index, listing_id in enumerate(listing_ids, 1)
    )


if __name__ == "__main__":
    unittest.main()
