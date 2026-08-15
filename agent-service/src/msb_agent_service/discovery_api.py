from __future__ import annotations

import asyncio
import logging
import re
import time
from datetime import UTC, datetime
from enum import StrEnum
from typing import Awaitable, Callable, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .agent_persistence import (
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentResolutionType,
    AgentToolCallStatus,
    BeginInvocationResult,
)
from .customer_service_api import encode_cursor
from .discovery_persistence import (
    DiscoveryExclusion,
    DiscoveryExclusionCommandResult,
    DiscoveryExclusionPersistenceError,
    DiscoveryExclusionPersistenceErrorCode,
    DiscoveryPersistenceRepository,
    DiscoverySession,
)
from .marketplace_discovery import (
    DiscoveryFailureStage,
    DiscoveryGraphFailureKind,
    DiscoveryPreferenceState,
    DiscoveryProviderFailureKind,
    DiscoveryRun,
    DiscoveryStageError,
    DiscoveryToolFailureKind,
    DiscoveryTurnResponse,
    MarketplaceIntent,
    MarketplaceDiscoveryOrchestrator,
    Ulid,
)

LOGGER = logging.getLogger(__name__)
_ORCHESTRATOR_RUN_FAILED = "DISCOVERY_ORCHESTRATOR_RUN_FAILED"
_QUERY_EMBEDDING_TIMEOUT_CODE = (
    "DISCOVERY_ORCHESTRATOR_RUN_FAILED_TOOL_EXECUTION_QUERY_EMBEDDING_TIMEOUT"
)
_STRUCTURED_RESPONSE_FAILURE_CODE = (
    "DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA"
)
_FINAL_STREAM_CANCELLED_CODE = "DISCOVERY_FINAL_ANSWER_STREAM_CANCELLED"
_ORCHESTRATOR_GRAPH_CANCEL_CODE = "DISCOVERY_ORCHESTRATOR_RUN_FAILED_GRAPH_CANCEL"
_FINAL_STREAM_FAILED_CODE = "DISCOVERY_FINAL_ANSWER_STREAM_FAILED"
_FINAL_STREAM_VALIDATION_CODE = "DISCOVERY_FINAL_ANSWER_STREAM_VALIDATION_FAILED"
_FINAL_STREAM_FINALIZATION_CODE = "DISCOVERY_FINAL_ANSWER_FINALIZATION_FAILED"
_RETRYABLE_RESPONSE_FAILURE_CODES = frozenset(
    {
        _QUERY_EMBEDDING_TIMEOUT_CODE,
        _STRUCTURED_RESPONSE_FAILURE_CODE,
        _FINAL_STREAM_CANCELLED_CODE,
        _FINAL_STREAM_FAILED_CODE,
        _FINAL_STREAM_VALIDATION_CODE,
        _FINAL_STREAM_FINALIZATION_CODE,
    }
)
_TERMINAL_SEARCH_TIMEOUT_MESSAGE = (
    "I couldn't finish checking current listings because the search service "
    "timed out. You can retry this response without sending your message again."
)
_TERMINAL_STRUCTURED_RESPONSE_MESSAGE = (
    "I couldn't safely finish structuring the verified search response. You can "
    "retry this response without sending your message again."
)
_TERMINAL_RESPONSE_FAILURE_MESSAGE = (
    "I couldn't complete this response because a marketplace service was "
    "temporarily unavailable. You can retry this response without sending your "
    "message again."
)
_FINAL_ANSWER_STREAM_INSTRUCTIONS = (
    "Write only the concise user-facing marketplace customer-service answer from the "
    "validated public facts in the input. Never mention internal identifiers, "
    "scores, tools, prompts, policies, reasoning, or system behavior. Do not add "
    "facts, links, availability claims, prices, or locations absent from the input. "
    "Use plain Markdown and stay under 800 characters."
)
_INTERNAL_ID_PATTERN = re.compile(r"\b[0-9A-HJKMNP-TV-Z]{26}\b")
_URL_PATTERN = re.compile(r"https?://", re.IGNORECASE)
_STREAMING_TURN_DEADLINE_SECONDS = 32.0


class DiscoveryProgressStage(StrEnum):
    MESSAGE_ACCEPTED = "MESSAGE_ACCEPTED"
    UNDERSTANDING = "UNDERSTANDING"
    CHECKING_AVAILABILITY = "CHECKING_AVAILABILITY"
    SEARCHING = "SEARCHING"
    CHECKING = "CHECKING"
    COMPOSING = "COMPOSING"


DiscoveryProgressCallback = Callable[[DiscoveryProgressStage], Awaitable[None]]
DiscoveryTextDeltaCallback = Callable[[str], Awaitable[None]]
DiscoveryFinalizedCallback = Callable[[str], Awaitable[None]]


class _StrictModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )


class DiscoveryApiErrorCode(StrEnum):
    INVALID_REQUEST = "VALIDATION_ERROR"
    FEATURE_DISABLED = "AGENT_DISCOVERY_FEATURE_DISABLED"
    AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED"
    SESSION_NOT_FOUND = "AGENT_DISCOVERY_SESSION_NOT_FOUND"
    SESSION_CLOSED = "AGENT_DISCOVERY_SESSION_CLOSED"
    REQUEST_CONFLICT = "AGENT_DISCOVERY_REQUEST_CONFLICT"
    PREFERENCE_VERSION_CONFLICT = "AGENT_DISCOVERY_PREFERENCE_VERSION_CONFLICT"
    MESSAGE_IN_PROGRESS = "AGENT_DISCOVERY_MESSAGE_IN_PROGRESS"
    UNAVAILABLE = "AGENT_DISCOVERY_UNAVAILABLE"
    STREAM_INTERRUPTED = "AGENT_DISCOVERY_FINAL_STREAM_INTERRUPTED"
    EXCLUSION_NOT_AVAILABLE = "AGENT_DISCOVERY_EXCLUSION_NOT_AVAILABLE"
    EXCLUSION_LIMIT_REACHED = "AGENT_DISCOVERY_EXCLUSION_LIMIT_REACHED"


class DiscoveryApiError(RuntimeError):
    """Carries one stable discovery error without retaining submitted text."""

    def __init__(
        self,
        code: DiscoveryApiErrorCode,
        status_code: int,
        public_message: str,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.status_code = status_code
        self.public_message = public_message


class CreateDiscoverySessionRequest(_StrictModel):
    session_type: Literal["MARKETPLACE_DISCOVERY"] = Field(alias="sessionType")
    new_search: bool = Field(default=False, alias="newSearch")


class DiscoveryExclusionReason(StrEnum):
    NOT_RELEVANT = "NOT_RELEVANT"
    TOO_EXPENSIVE = "TOO_EXPENSIVE"
    TOO_FAR = "TOO_FAR"
    WRONG_CONDITION = "WRONG_CONDITION"
    ALREADY_HAVE = "ALREADY_HAVE"
    OTHER = "OTHER"


class CreateDiscoveryExclusionRequest(_StrictModel):
    expected_preference_version: int = Field(
        alias="expectedPreferenceVersion",
        ge=0,
    )
    listing_id: Ulid = Field(alias="listingId")
    reason_code: DiscoveryExclusionReason | None = Field(
        default=None,
        alias="reasonCode",
    )


class DiscoveryExclusionResponse(_StrictModel):
    listing_id: Ulid = Field(alias="listingId")
    reason_code: DiscoveryExclusionReason | None = Field(
        default=None,
        alias="reasonCode",
    )
    excluded_at: datetime = Field(alias="excludedAt")


class CreateDiscoveryExclusionResponse(_StrictModel):
    session_id: Ulid = Field(alias="sessionId")
    listing_id: Ulid = Field(alias="listingId")
    reason_code: DiscoveryExclusionReason | None = Field(
        default=None,
        alias="reasonCode",
    )
    outcome: Literal["EXCLUDED", "ALREADY_EXCLUDED"]
    preference_version: int = Field(alias="preferenceVersion", ge=0)
    excluded_count: int = Field(alias="excludedCount", ge=1, le=20)
    updated_at: datetime = Field(alias="updatedAt")


class DiscoverySessionResponse(_StrictModel):
    id: Ulid
    session_type: Literal["MARKETPLACE_DISCOVERY"] = Field(alias="sessionType")
    status: Literal["OPEN", "CLOSED"]
    preference_state: DiscoveryPreferenceState = Field(alias="preferenceState")
    preference_version: int = Field(alias="preferenceVersion", ge=0)
    clarification_turn_count: int = Field(alias="clarificationTurnCount", ge=0, le=3)
    clarification_question_count: int = Field(
        alias="clarificationQuestionCount",
        ge=0,
        le=5,
    )
    exclusions: tuple[DiscoveryExclusionResponse, ...] = Field(
        default=(),
        max_length=20,
    )
    created_at: datetime = Field(alias="createdAt")
    updated_at: datetime = Field(alias="updatedAt")


class SendDiscoveryMessageRequest(_StrictModel):
    client_message_id: Ulid = Field(alias="clientMessageId")
    expected_preference_version: int = Field(
        alias="expectedPreferenceVersion",
        ge=0,
    )
    body: str = Field(min_length=1, max_length=8_000)


class RetryDiscoveryResponseRequest(_StrictModel):
    expected_preference_version: int = Field(
        alias="expectedPreferenceVersion",
        ge=0,
    )


class StopDiscoveryResponse(_StrictModel):
    session_id: Ulid = Field(alias="sessionId")
    client_message_id: Ulid = Field(alias="clientMessageId")
    outcome: Literal["NOT_COMMITTED", "STOPPED", "COMPLETED", "TERMINAL"]


class DiscoveryUserMessageResponse(_StrictModel):
    id: Ulid
    role: Literal["USER"]
    body: str = Field(min_length=1, max_length=8_000)
    created_at: datetime = Field(alias="createdAt")


class SendDiscoveryMessageResponse(_StrictModel):
    user_message: DiscoveryUserMessageResponse = Field(alias="userMessage")
    result: DiscoveryTurnResponse
    preference_version: int = Field(alias="preferenceVersion", ge=0)


class DiscoveryHistoryMessage(_StrictModel):
    id: Ulid
    role: Literal["USER", "ASSISTANT"]
    body: str = Field(min_length=1, max_length=12_000)
    client_message_id: Ulid | None = Field(default=None, alias="clientMessageId")
    resolution_type: Literal[
        "ANSWERED",
        "CLARIFY",
        "RECOMMEND",
        "NO_RESULTS",
        "REFUSED",
        "PARTIAL",
        "HANDOFF",
    ] | None = Field(default=None, alias="resolutionType")
    result: DiscoveryTurnResponse | None = None
    response_failure: DiscoveryTurnResponse | None = Field(
        default=None,
        alias="responseFailure",
    )
    response_retry: "DiscoveryResponseRetry | None" = Field(
        default=None,
        alias="responseRetry",
    )
    created_at: datetime = Field(alias="createdAt")


class DiscoveryResponseRetry(_StrictModel):
    invocation_id: Ulid = Field(alias="invocationId")
    user_message_id: Ulid = Field(alias="userMessageId")


class DiscoveryHistoryPage(_StrictModel):
    data: tuple[DiscoveryHistoryMessage, ...]
    next_cursor: str | None = Field(default=None, alias="nextCursor")
    has_more: bool = Field(alias="hasMore")


class DiscoveryOrchestrator(Protocol):
    async def run(self, **kwargs: object) -> DiscoveryRun: ...


class DiscoveryFinalAnswerStreamer(Protocol):
    async def discovery_answer_stream(self, **kwargs: object) -> object: ...


class MarketplaceDiscoveryService:
    """Coordinates actor-isolated discovery persistence and one ReAct turn."""

    def __init__(
        self,
        repository: DiscoveryPersistenceRepository,
        orchestrator: MarketplaceDiscoveryOrchestrator | None,
        final_answer_streamer: DiscoveryFinalAnswerStreamer | None = None,
    ) -> None:
        self._repository = repository
        self._conversation = repository.conversation_repository
        self._orchestrator = orchestrator
        self._final_answer_streamer = final_answer_streamer

    async def create_session(
        self,
        *,
        actor_user_id: str,
        new_search: bool,
    ) -> DiscoverySessionResponse:
        session, _ = await self._repository.create_or_resume(
            actor_user_id=actor_user_id,
            new_search=new_search,
            now=datetime.now(UTC),
        )
        return _session_response(
            session,
            await self._repository.list_exclusions(
                session_id=session.session_id,
                actor_user_id=actor_user_id,
            ),
        )

    async def get_session(
        self,
        *,
        actor_user_id: str,
        session_id: str,
    ) -> DiscoverySessionResponse:
        session = await self._owned_session(actor_user_id, session_id)
        return _session_response(
            session,
            await self._repository.list_exclusions(
                session_id=session_id,
                actor_user_id=actor_user_id,
            ),
        )

    async def exclude_listing(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        idempotency_key: str,
        expected_preference_version: int,
        listing_id: str,
        reason_code: DiscoveryExclusionReason | None,
    ) -> CreateDiscoveryExclusionResponse:
        """Apply one actor/session exclusion without creating chat history."""

        try:
            result = await self._repository.apply_exclusion(
                session_id=session_id,
                actor_user_id=actor_user_id,
                idempotency_key=idempotency_key,
                expected_preference_version=expected_preference_version,
                listing_id=listing_id,
                reason_code=None if reason_code is None else reason_code.value,
                now=datetime.now(UTC),
            )
        except DiscoveryExclusionPersistenceError as error:
            raise _map_exclusion_error(error) from error
        return _exclusion_response(result)

    async def list_messages(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        limit: int,
        cursor: object | None,
    ) -> DiscoveryHistoryPage:
        session = await self._owned_session(actor_user_id, session_id)
        page = await self._conversation.list_messages(
            session_id=session_id,
            actor_user_id=actor_user_id,
            limit=limit,
            after=cursor,
        )
        try:
            messages = tuple(
                _history_message(
                    item,
                    session.preference_state,
                    self._conversation.maximum_failed_retries,
                )
                for item in page.messages
            )
        except ValidationError as error:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "The stored discovery response is temporarily unavailable.",
            ) from error
        return DiscoveryHistoryPage(
            data=messages,
            nextCursor=(
                None
                if page.next_cursor is None
                else encode_cursor(page.next_cursor)
            ),
            hasMore=page.next_cursor is not None,
        )

    async def retry_response(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        user_message_id: str,
        expected_preference_version: int,
        correlation_id: str,
        progress: DiscoveryProgressCallback | None = None,
        text_delta: DiscoveryTextDeltaCallback | None = None,
        finalized: DiscoveryFinalizedCallback | None = None,
    ) -> SendDiscoveryMessageResponse:
        """Regenerate one failed response while reusing its committed USER row.

        FAILED invocations without an assistant can come from older runtime
        failures. They are deliberately recoverable through this command; the
        original clientMessageId and USER row remain the idempotency boundary.
        """

        session = await self._owned_session(actor_user_id, session_id)
        if session.status.value != "OPEN":
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.SESSION_CLOSED,
                409,
                "This discovery session is closed. Start a New search.",
            )
        if session.preference_version != expected_preference_version:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
                409,
                "Discovery preferences changed. Refresh before trying again.",
            )
        try:
            invocation, user = await self._conversation.get_invocation_for_user_message(
                session_id=session_id,
                actor_user_id=actor_user_id,
                user_message_id=user_message_id,
            )
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.INVOCATION_NOT_FOUND:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.SESSION_NOT_FOUND,
                    404,
                    "The failed discovery response was not found.",
                ) from error
            raise
        if invocation.result_status == AgentInvocationStatus.PENDING:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.MESSAGE_IN_PROGRESS,
                409,
                "This discovery response is already being processed.",
            )
        if (
            invocation.result_status != AgentInvocationStatus.FAILED
            or invocation.error_code is None
            or invocation.client_message_id is None
            or invocation.retry_count >= self._conversation.maximum_failed_retries
        ):
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.REQUEST_CONFLICT,
                409,
                "This discovery response is not eligible for retry.",
            )
        return await self.send_message(
            actor_user_id=actor_user_id,
            session_id=session_id,
            client_message_id=invocation.client_message_id,
            expected_preference_version=expected_preference_version,
            body=user.body,
            correlation_id=correlation_id,
            progress=progress,
            text_delta=text_delta,
            finalized=finalized,
        )

    async def send_message(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
        expected_preference_version: int,
        body: str,
        correlation_id: str,
        progress: DiscoveryProgressCallback | None = None,
        text_delta: DiscoveryTextDeltaCallback | None = None,
        finalized: DiscoveryFinalizedCallback | None = None,
    ) -> SendDiscoveryMessageResponse:
        session = await self._owned_session(actor_user_id, session_id)
        if session.status.value != "OPEN":
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.SESSION_CLOSED,
                409,
                "This discovery session is closed. Start a New search.",
            )
        try:
            begin = await self._conversation.begin_invocation(
                session_id=session_id,
                actor_user_id=actor_user_id,
                client_message_id=client_message_id,
                body=body,
                prompt_version="marketplace-discovery-react-v2",
                model_provider="injected",
                model_name="injected",
                schema_version="discovery-turn-v2",
                tool_registry_version="marketplace-discovery-tools-v1",
                policy_version="ai-disc-02a",
                correlation_id=correlation_id,
                now=datetime.now(UTC),
            )
        except asyncio.CancelledError:
            await asyncio.shield(self._reconcile_cancelled_begin(
                actor_user_id=actor_user_id,
                session_id=session_id,
                client_message_id=client_message_id,
                preference_state=session.preference_state,
            ))
            raise
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.REQUEST_CONFLICT,
                    409,
                    "clientMessageId was already used for a different message.",
                ) from error
            raise
        started = time.perf_counter()
        streamed_text = ""
        final_stream_started = False
        if progress is not None:
            try:
                await progress(DiscoveryProgressStage.MESSAGE_ACCEPTED)
            except asyncio.CancelledError:
                if begin.result in {
                    BeginInvocationResult.CREATED,
                    BeginInvocationResult.RETRY_STARTED,
                } and begin.user_message is not None:
                    await asyncio.shield(self._persist_stream_interruption(
                        invocation_id=begin.invocation.invocation_id,
                        actor_user_id=actor_user_id,
                        preference_state=session.preference_state,
                        partial_text="",
                        error_code=_FINAL_STREAM_CANCELLED_CODE,
                        started=started,
                    ))
                raise
        if begin.result == BeginInvocationResult.DEDUPLICATED_PENDING:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.MESSAGE_IN_PROGRESS,
                409,
                "This discovery message is already being processed.",
            )
        if begin.result == BeginInvocationResult.DEDUPLICATED_SUCCEEDED:
            user, assistant = await self._conversation.get_invocation_messages(
                invocation_id=begin.invocation.invocation_id,
                actor_user_id=actor_user_id,
            )
            if assistant is None:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "The stored discovery response is temporarily unavailable.",
                )
            replay = _stored_turn(assistant)
            current = await self._owned_session(actor_user_id, session_id)
            if text_delta is not None:
                await text_delta(replay.message)
            if finalized is not None:
                await finalized(assistant.message_id)
            return _send_response(user, replay, current.preference_version)
        if begin.result == BeginInvocationResult.RETRY_EXHAUSTED:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.REQUEST_CONFLICT,
                409,
                "The retry limit for this discovery message was reached.",
            )
        if begin.user_message is None:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "The stored discovery message is temporarily unavailable.",
            )

        try:
            if session.preference_version != expected_preference_version:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_PREFERENCE_VERSION_CONFLICT",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
                    409,
                    "Discovery preferences changed. Refresh before trying again.",
                )
            if self._orchestrator is None:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_ORCHESTRATION_UNAVAILABLE",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                )
            try:
                history_messages = await self._repository.list_recent_messages(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                    exclude_message_id=begin.user_message.message_id,
                    limit=12,
                )
            except Exception as error:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_PRIOR_MESSAGE_LOAD_FAILED",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            history = tuple(
                (message.role.value, message.body)
                for message in history_messages
            )
            try:
                previous_recommendations = await self._repository.latest_recommendations(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                )
            except Exception as error:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_RECOMMENDATION_LOAD_FAILED",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            try:
                exclusions = await self._repository.list_exclusions(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                )
            except Exception as error:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_EXCLUSION_LOAD_FAILED",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            try:
                run_arguments = {
                    "actor_user_id": actor_user_id,
                    "session_id": session_id,
                    "question": body,
                    "preference_state": session.preference_state,
                    "clarification_turn_count": session.clarification_turn_count,
                    "clarification_question_count": (
                        session.clarification_question_count
                    ),
                    "history": history,
                    "correlation_id": correlation_id,
                    "previous_recommendations": previous_recommendations,
                    "excluded_listing_ids": tuple(
                        item.listing_id for item in exclusions
                    ),
                }
                if progress is not None:
                    async def orchestrator_activity(stage: str) -> None:
                        """Bridge only allowlisted real orchestrator milestones to SSE."""

                        await progress(DiscoveryProgressStage(stage))

                    run_arguments["activity"] = orchestrator_activity
                if text_delta is not None:
                    async def orchestrator_text_delta(delta: str) -> None:
                        """Forward natural terminal model text on its original stream."""

                        nonlocal streamed_text, final_stream_started
                        final_stream_started = True
                        streamed_text += delta
                        if len(streamed_text) > 800:
                            raise ValueError("Marketplace assistant answer exceeds its bound")
                        await text_delta(delta)

                    run_arguments["text_delta"] = orchestrator_text_delta
            except Exception as error:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_ORCHESTRATOR_SETUP_FAILED",
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            try:
                run = await self._orchestrator.run(**run_arguments)
            except DiscoveryStageError as error:
                error_code = _orchestrator_failure_code(error.stage, error.kind)
                _log_orchestrator_failure_stage(
                    error.stage, error.kind, correlation_id
                )
                if streamed_text:
                    await self._persist_stream_interruption(
                        invocation_id=begin.invocation.invocation_id,
                        actor_user_id=actor_user_id,
                        preference_state=session.preference_state,
                        partial_text=streamed_text,
                        error_code=_FINAL_STREAM_VALIDATION_CODE,
                        started=started,
                    )
                    raise DiscoveryApiError(
                        DiscoveryApiErrorCode.STREAM_INTERRUPTED,
                        503,
                        "The answer stream was interrupted. You can retry this response.",
                    ) from error
                if error_code in _RETRYABLE_RESPONSE_FAILURE_CODES:
                    failure = _terminal_response_failure(
                        error_code,
                        session.preference_state,
                    )
                    await self._conversation.fail_invocation_with_assistant(
                        invocation_id=begin.invocation.invocation_id,
                        actor_user_id=actor_user_id,
                        error_code=error_code,
                        body=failure.message,
                        sources=(),
                        actions=(
                            {
                                "type": "DISCOVERY_RESULT",
                                "result": failure.model_dump(
                                    mode="json",
                                    by_alias=True,
                                    exclude_none=True,
                                ),
                            },
                        ),
                        latency_ms=max(
                            0,
                            round((time.perf_counter() - started) * 1_000),
                        ),
                        now=datetime.now(UTC),
                    )
                else:
                    await self._fail(
                        begin.invocation.invocation_id,
                        actor_user_id,
                        error_code,
                        started,
                        session.preference_state,
                    )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            except Exception as error:
                stage = DiscoveryFailureStage.GRAPH_INVOKE_START
                _log_orchestrator_failure_stage(stage, correlation_id=correlation_id)
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    _orchestrator_failure_code(stage),
                    started,
                    session.preference_state,
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                ) from error
            if progress is not None and (
                run.response.intent == MarketplaceIntent.LISTING_QUESTION
                or (
                    run.response.search_outcome is not None
                    and run.response.search_outcome.mode == "FULL_DISCOVERY_SEARCH"
                )
            ):
                await progress(DiscoveryProgressStage.COMPOSING)
            for audit in run.audits:
                await self._conversation.append_tool_call(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    sequence_number=audit.sequence_number,
                    tool_name=audit.tool_name,
                    argument_hash=audit.argument_hash,
                    result_hash=audit.result_hash,
                    source_refs=audit.source_refs,
                    result_status=AgentToolCallStatus.SUCCEEDED,
                    error_code=None,
                    latency_ms=audit.latency_ms,
                    now=datetime.now(UTC),
                )
            if text_delta is not None:
                final_stream_started = True

                async def forward_delta(delta: str) -> None:
                    """Accumulate exactly the same designated text sent to the client."""

                    nonlocal streamed_text
                    streamed_text += delta
                    if len(streamed_text) > 800:
                        raise ValueError("Discovery final answer exceeds its bound")
                    await text_delta(delta)

                try:
                    if run.streamed_during_run:
                        if streamed_text != run.response.message:
                            raise ValueError("Marketplace assistant stream text mismatch")
                    else:
                        await forward_delta(run.response.message)
                    _validate_streamed_answer(streamed_text)
                    finalized_payload = run.response.model_dump(mode="python")
                    finalized_payload["message"] = streamed_text
                    run = DiscoveryRun(
                        response=DiscoveryTurnResponse.model_validate(finalized_payload),
                        audits=run.audits,
                        requires_final_generation=run.requires_final_generation,
                    )
                except asyncio.CancelledError:
                    raise
                except Exception as error:
                    await self._persist_stream_interruption(
                        invocation_id=begin.invocation.invocation_id,
                        actor_user_id=actor_user_id,
                        preference_state=session.preference_state,
                        partial_text=streamed_text,
                        error_code=(
                            _FINAL_STREAM_VALIDATION_CODE
                            if streamed_text
                            else _FINAL_STREAM_FAILED_CODE
                        ),
                        started=started,
                    )
                    LOGGER.warning(
                        "AGENT_DISCOVERY_FINAL_STREAM_FAILED code=%s",
                        _FINAL_STREAM_VALIDATION_CODE
                        if streamed_text
                        else _FINAL_STREAM_FAILED_CODE,
                    )
                    raise DiscoveryApiError(
                        DiscoveryApiErrorCode.STREAM_INTERRUPTED,
                        503,
                        "The answer stream was interrupted. You can retry this response.",
                    ) from error
            question_increment = (
                len(run.response.questions)
                if run.response.outcome in {"ASK_CLARIFY", "CLARIFY"}
                else 0
            )
            resolution = _resolution(run.response.outcome)
            try:
                updated, assistant = await self._repository.complete_turn(
                    invocation_id=begin.invocation.invocation_id,
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                    expected_preference_version=expected_preference_version,
                    response=run.response,
                    resolution_type=resolution,
                    clarification_turn_increment=1 if question_increment else 0,
                    clarification_question_increment=question_increment,
                    latency_ms=max(
                        0,
                        round((time.perf_counter() - started) * 1_000),
                    ),
                    now=datetime.now(UTC),
                )
            except Exception as error:
                if final_stream_started and streamed_text:
                    await self._persist_stream_interruption(
                        invocation_id=begin.invocation.invocation_id,
                        actor_user_id=actor_user_id,
                        preference_state=session.preference_state,
                        partial_text=streamed_text,
                        error_code=_FINAL_STREAM_FINALIZATION_CODE,
                        started=started,
                    )
                    raise DiscoveryApiError(
                        DiscoveryApiErrorCode.STREAM_INTERRUPTED,
                        503,
                        "The answer stream was interrupted. You can retry this response.",
                    ) from error
                raise
            if finalized is not None:
                await finalized(assistant.message_id)
            return _send_response(
                begin.user_message,
                _stored_turn(assistant),
                updated.preference_version,
            )
        except asyncio.CancelledError:
            await asyncio.shield(self._persist_stream_interruption(
                invocation_id=begin.invocation.invocation_id,
                actor_user_id=actor_user_id,
                preference_state=session.preference_state,
                partial_text=streamed_text,
                error_code=_FINAL_STREAM_CANCELLED_CODE,
                started=started,
            ))
            raise
        except AgentPersistenceError as error:
            await self._fail(
                begin.invocation.invocation_id,
                actor_user_id,
                error.code.value,
                started,
                session.preference_state,
            )
            if error.code == AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
                    409,
                    "Discovery preferences changed. Refresh before trying again.",
                ) from error
            raise
        except DiscoveryApiError:
            raise
        except Exception as error:
            await self._fail(
                begin.invocation.invocation_id,
                actor_user_id,
                "DISCOVERY_COMPLETION_FAILED",
                started,
                session.preference_state,
            )
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace discovery is temporarily unavailable.",
            ) from error

    async def stop_message(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
    ) -> StopDiscoveryResponse:
        """Return the authoritative committed state after an explicit stream stop."""

        await self._owned_session(actor_user_id, session_id)
        try:
            invocation, _ = await self._conversation.get_invocation_for_client_message(
                session_id=session_id,
                actor_user_id=actor_user_id,
                client_message_id=client_message_id,
            )
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.INVOCATION_NOT_FOUND:
                return StopDiscoveryResponse(
                    sessionId=session_id,
                    clientMessageId=client_message_id,
                    outcome="NOT_COMMITTED",
                )
            raise
        if invocation.result_status == AgentInvocationStatus.PENDING:
            await self._persist_stream_interruption(
                invocation_id=invocation.invocation_id,
                actor_user_id=actor_user_id,
                preference_state=(await self._owned_session(
                    actor_user_id, session_id
                )).preference_state,
                partial_text="",
                error_code=_FINAL_STREAM_CANCELLED_CODE,
                started=time.perf_counter(),
            )
            outcome = "STOPPED"
        elif invocation.result_status == AgentInvocationStatus.SUCCEEDED:
            outcome = "COMPLETED"
        elif invocation.error_code == _FINAL_STREAM_CANCELLED_CODE:
            outcome = "STOPPED"
        elif invocation.error_code == _ORCHESTRATOR_GRAPH_CANCEL_CODE:
            await self._persist_stream_interruption(
                invocation_id=invocation.invocation_id,
                actor_user_id=actor_user_id,
                preference_state=(await self._owned_session(
                    actor_user_id, session_id
                )).preference_state,
                partial_text="",
                error_code=_FINAL_STREAM_CANCELLED_CODE,
                started=time.perf_counter(),
                replace_failed_error_codes=(_ORCHESTRATOR_GRAPH_CANCEL_CODE,),
            )
            outcome = "STOPPED"
        else:
            outcome = "TERMINAL"
        return StopDiscoveryResponse(
            sessionId=session_id,
            clientMessageId=client_message_id,
            outcome=outcome,
        )

    async def _reconcile_cancelled_begin(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
        preference_state: DiscoveryPreferenceState,
    ) -> None:
        """Close a begin/commit cancellation race without leaving an orphan USER row."""

        try:
            invocation, _ = await self._conversation.get_invocation_for_client_message(
                session_id=session_id,
                actor_user_id=actor_user_id,
                client_message_id=client_message_id,
            )
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.INVOCATION_NOT_FOUND:
                return
            raise
        if invocation.result_status == AgentInvocationStatus.PENDING:
            await self._persist_stream_interruption(
                invocation_id=invocation.invocation_id,
                actor_user_id=actor_user_id,
                preference_state=preference_state,
                partial_text="",
                error_code=_FINAL_STREAM_CANCELLED_CODE,
                started=time.perf_counter(),
            )

    async def _fail(
        self,
        invocation_id: str,
        actor_user_id: str,
        error_code: str,
        started: float,
        preference_state: DiscoveryPreferenceState,
    ) -> None:
        """Terminalize every committed turn with guarded copy and response retry."""

        failure = _terminal_response_failure(error_code, preference_state)
        await self._conversation.fail_invocation_with_assistant(
            invocation_id=invocation_id,
            actor_user_id=actor_user_id,
            error_code=error_code,
            body=failure.message,
            sources=(),
            actions=({
                "type": "DISCOVERY_RESULT",
                "result": failure.model_dump(
                    mode="json", by_alias=True, exclude_none=True
                ),
            },),
            latency_ms=max(0, round((time.perf_counter() - started) * 1_000)),
            now=datetime.now(UTC),
        )

    async def _persist_stream_interruption(
        self,
        *,
        invocation_id: str,
        actor_user_id: str,
        preference_state: DiscoveryPreferenceState,
        partial_text: str,
        error_code: str,
        started: float,
        replace_failed_error_codes: tuple[str, ...] = (),
    ) -> None:
        """Persist exact visible partial text and retain response-only retryability."""

        message = partial_text or "Response stopped before the answer began."
        failure = DiscoveryTurnResponse(
            outcome="HANDOFF",
            message=message,
            preferenceState=preference_state,
        )
        await self._conversation.fail_invocation_with_assistant(
            invocation_id=invocation_id,
            actor_user_id=actor_user_id,
            error_code=error_code,
            body=message,
            sources=(),
            actions=({
                "type": "DISCOVERY_RESULT",
                "result": failure.model_dump(
                    mode="json", by_alias=True, exclude_none=True
                ),
            },),
            latency_ms=max(0, round((time.perf_counter() - started) * 1_000)),
            now=datetime.now(UTC),
            resolution_type=AgentResolutionType.PARTIAL,
            replace_failed_error_codes=replace_failed_error_codes,
        )

    async def _owned_session(
        self,
        actor_user_id: str,
        session_id: str,
    ) -> DiscoverySession:
        session = await self._repository.get(
            session_id=session_id,
            actor_user_id=actor_user_id,
        )
        if session is None:
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.SESSION_NOT_FOUND,
                404,
                "Discovery session was not found.",
            )
        return session


def _orchestrator_failure_code(
    stage: DiscoveryFailureStage,
    kind: (
        DiscoveryToolFailureKind
        | DiscoveryProviderFailureKind
        | DiscoveryGraphFailureKind
        | None
    ) = None,
) -> str:
    """Persist only fixed allowlisted stage/kind values on the stable code."""

    code = f"{_ORCHESTRATOR_RUN_FAILED}_{stage.name}"
    if kind is not None:
        code = f"{code}_{kind.name}"
    return code


def _validate_streamed_answer(text: str) -> None:
    """Reject bounded final text that exposes internal identifiers or links."""

    if (
        not text.strip()
        or len(text) > 800
        or any(ord(character) < 32 and character not in "\n\t" for character in text)
        or _INTERNAL_ID_PATTERN.search(text)
        or _URL_PATTERN.search(text)
    ):
        raise ValueError("Discovery final answer failed its public-text contract")


def _log_orchestrator_failure_stage(
    stage: DiscoveryFailureStage,
    kind: (
        DiscoveryToolFailureKind
        | DiscoveryProviderFailureKind
        | DiscoveryGraphFailureKind
        | None
    ) = None,
    correlation_id: str | None = None,
) -> None:
    """Emit bounded failure telemetry correlated without request or actor data."""

    if kind is None:
        LOGGER.warning(
            "AGENT_DISCOVERY_FAILURE_STAGE stage=%s correlationId=%s",
            stage.value,
            correlation_id,
        )
        return
    LOGGER.warning(
        "AGENT_DISCOVERY_FAILURE_STAGE stage=%s kind=%s correlationId=%s",
        stage.value,
        kind.value,
        correlation_id,
    )


def _session_response(
    session: DiscoverySession,
    exclusions: tuple[DiscoveryExclusion, ...],
) -> DiscoverySessionResponse:
    return DiscoverySessionResponse(
        id=session.session_id,
        sessionType="MARKETPLACE_DISCOVERY",
        status=session.status.value,
        preferenceState=session.preference_state,
        preferenceVersion=session.preference_version,
        clarificationTurnCount=session.clarification_turn_count,
        clarificationQuestionCount=session.clarification_question_count,
        exclusions=tuple(
            DiscoveryExclusionResponse(
                listingId=item.listing_id,
                reasonCode=item.reason_code,
                excludedAt=item.excluded_at,
            )
            for item in exclusions
        ),
        createdAt=session.created_at,
        updatedAt=session.updated_at,
    )


def _exclusion_response(
    result: DiscoveryExclusionCommandResult,
) -> CreateDiscoveryExclusionResponse:
    return CreateDiscoveryExclusionResponse(
        sessionId=result.session_id,
        listingId=result.listing_id,
        reasonCode=result.reason_code,
        outcome=result.outcome,
        preferenceVersion=result.preference_version,
        excludedCount=result.excluded_count,
        updatedAt=result.updated_at,
    )


def _map_exclusion_error(
    error: DiscoveryExclusionPersistenceError,
) -> DiscoveryApiError:
    """Map internal exclusion failures to the approved stable public envelope."""

    mapping = {
        DiscoveryExclusionPersistenceErrorCode.SESSION_NOT_FOUND: (
            DiscoveryApiErrorCode.SESSION_NOT_FOUND,
            404,
            "Discovery session was not found.",
        ),
        DiscoveryExclusionPersistenceErrorCode.SESSION_CLOSED: (
            DiscoveryApiErrorCode.SESSION_CLOSED,
            409,
            "This discovery session is closed. Start a New search.",
        ),
        DiscoveryExclusionPersistenceErrorCode.REQUEST_CONFLICT: (
            DiscoveryApiErrorCode.REQUEST_CONFLICT,
            409,
            "Idempotency-Key was already used for a different exclusion.",
        ),
        DiscoveryExclusionPersistenceErrorCode.PREFERENCE_VERSION_CONFLICT: (
            DiscoveryApiErrorCode.PREFERENCE_VERSION_CONFLICT,
            409,
            "Discovery preferences changed. Refresh before trying again.",
        ),
        DiscoveryExclusionPersistenceErrorCode.EXCLUSION_NOT_AVAILABLE: (
            DiscoveryApiErrorCode.EXCLUSION_NOT_AVAILABLE,
            409,
            "The listing is not available in the latest recommendation set.",
        ),
        DiscoveryExclusionPersistenceErrorCode.EXCLUSION_LIMIT_REACHED: (
            DiscoveryApiErrorCode.EXCLUSION_LIMIT_REACHED,
            409,
            "This discovery session reached its exclusion limit.",
        ),
    }
    code, status, message = mapping[error.code]
    return DiscoveryApiError(code, status, message)


def _send_response(
    user: AgentMessage,
    result: DiscoveryTurnResponse,
    preference_version: int,
) -> SendDiscoveryMessageResponse:
    return SendDiscoveryMessageResponse(
        userMessage=DiscoveryUserMessageResponse(
            id=user.message_id,
            role="USER",
            body=user.body,
            createdAt=user.created_at,
        ),
        result=result,
        preferenceVersion=preference_version,
    )


def _stored_turn(message: AgentMessage) -> DiscoveryTurnResponse:
    if message.role != AgentMessageRole.ASSISTANT or len(message.actions) != 1:
        raise DiscoveryApiError(
            DiscoveryApiErrorCode.UNAVAILABLE,
            503,
            "The stored discovery response is temporarily unavailable.",
        )
    action = message.actions[0]
    if set(action) != {"type", "result"} or action["type"] != "DISCOVERY_RESULT":
        raise DiscoveryApiError(
            DiscoveryApiErrorCode.UNAVAILABLE,
            503,
            "The stored discovery response is temporarily unavailable.",
        )
    return DiscoveryTurnResponse.model_validate(action["result"])


def _history_message(
    message: AgentMessage,
    preference_state: DiscoveryPreferenceState | None = None,
    maximum_failed_retries: int = 1,
) -> DiscoveryHistoryMessage:
    if message.role == AgentMessageRole.USER and (
        message.resolution_type is not None
        or message.actions
        or message.client_message_id is None
    ):
        raise DiscoveryApiError(
            DiscoveryApiErrorCode.UNAVAILABLE,
            503,
            "The stored discovery response is temporarily unavailable.",
        )
    result = None if message.role == AgentMessageRole.USER else _stored_turn(message)
    if result is not None and (
        message.body != result.message
        or not _stored_resolution_matches(message.resolution_type, result.outcome)
    ):
        raise DiscoveryApiError(
            DiscoveryApiErrorCode.UNAVAILABLE,
            503,
            "The stored discovery response is temporarily unavailable.",
        )
    # A FAILED invocation is always a committed turn. Even legacy failures that
    # predate guarded assistant persistence must remain recoverable exactly once.
    retryable_failure = bool(
        message.invocation_status == AgentInvocationStatus.FAILED
        and message.invocation_error_code is not None
        and message.invocation_id is not None
        and message.invocation_retry_count is not None
        and message.invocation_retry_count < maximum_failed_retries
    )
    legacy_failure = bool(
        retryable_failure
        and message.role == AgentMessageRole.USER
        and message.invocation_assistant_message_id is None
    )
    retry = None
    if retryable_failure and (
        legacy_failure or message.role == AgentMessageRole.ASSISTANT
    ):
        retry = DiscoveryResponseRetry(
            invocationId=message.invocation_id,
            userMessageId=(
                message.message_id
                if message.role == AgentMessageRole.USER
                else _required_user_message_id(message)
            ),
        )
    return DiscoveryHistoryMessage(
        id=message.message_id,
        role=message.role.value,
        body=message.body,
        clientMessageId=(
            message.client_message_id
            if message.role == AgentMessageRole.USER
            else None
        ),
        resolutionType=(
            None if message.resolution_type is None else message.resolution_type.value
        ),
        result=result,
        responseFailure=(
            _terminal_response_failure(
                message.invocation_error_code,
                preference_state or DiscoveryPreferenceState(),
            )
            if legacy_failure
            else None
        ),
        responseRetry=retry,
        createdAt=message.created_at,
    )


def _stored_resolution_matches(
    resolution: AgentResolutionType | None,
    outcome: str,
) -> bool:
    """Allow guarded interrupted output to retain PARTIAL persistence semantics."""

    return (
        resolution == AgentResolutionType.PARTIAL
        and outcome == "HANDOFF"
    ) or resolution == _resolution(outcome)


def _required_user_message_id(message: AgentMessage) -> str:
    """Resolve an assistant's owning USER ID from its retry metadata join."""

    # The history join exposes the invocation but AgentMessage intentionally does
    # not duplicate every foreign key; retryable assistant rows require a lookup
    # field populated from the invocation user ID.
    value = getattr(message, "invocation_user_message_id", None)
    if not isinstance(value, str):
        raise DiscoveryApiError(
            DiscoveryApiErrorCode.UNAVAILABLE,
            503,
            "The stored discovery response is temporarily unavailable.",
        )
    return value


def _terminal_search_timeout(
    preference_state: DiscoveryPreferenceState,
) -> DiscoveryTurnResponse:
    """Build the deterministic privacy-safe response for exhausted query timeout."""

    return DiscoveryTurnResponse(
        outcome="HANDOFF",
        message=_TERMINAL_SEARCH_TIMEOUT_MESSAGE,
        preferenceState=preference_state,
    )


def _terminal_response_failure(
    error_code: str | None,
    preference_state: DiscoveryPreferenceState,
) -> DiscoveryTurnResponse:
    """Map only allowlisted retriable failures to guarded assistant copy."""

    if error_code == _QUERY_EMBEDDING_TIMEOUT_CODE:
        return _terminal_search_timeout(preference_state)
    if error_code == _STRUCTURED_RESPONSE_FAILURE_CODE:
        return DiscoveryTurnResponse(
            outcome="HANDOFF",
            message=_TERMINAL_STRUCTURED_RESPONSE_MESSAGE,
            preferenceState=preference_state,
        )
    return DiscoveryTurnResponse(
        outcome="HANDOFF",
        message=_TERMINAL_RESPONSE_FAILURE_MESSAGE,
        preferenceState=preference_state,
    )


def _resolution(outcome: str) -> AgentResolutionType:
    return {
        "ANSWER": AgentResolutionType.ANSWERED,
        "CLARIFY": AgentResolutionType.CLARIFY,
        "SEARCH": AgentResolutionType.RECOMMEND,
        "ACTION_REQUIRED": AgentResolutionType.ANSWERED,
        "ASK_CLARIFY": AgentResolutionType.CLARIFY,
        "RECOMMEND": AgentResolutionType.RECOMMEND,
        "COMPARE": AgentResolutionType.ANSWERED,
        "DETAIL": AgentResolutionType.ANSWERED,
        "NO_RESULTS": AgentResolutionType.NO_RESULTS,
        "REFUSE": AgentResolutionType.REFUSED,
        "REFUSED": AgentResolutionType.REFUSED,
        "HANDOFF": AgentResolutionType.HANDOFF,
    }[outcome]
