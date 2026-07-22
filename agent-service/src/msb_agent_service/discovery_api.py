from __future__ import annotations

import asyncio
import time
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field

from .agent_persistence import (
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
    DiscoveryPreferenceState,
    DiscoveryRun,
    DiscoveryTurnResponse,
    MarketplaceDiscoveryOrchestrator,
    Ulid,
)


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
    resolution_type: Literal[
        "ANSWERED",
        "CLARIFY",
        "RECOMMEND",
        "NO_RESULTS",
        "REFUSED",
        "HANDOFF",
    ] | None = Field(default=None, alias="resolutionType")
    result: DiscoveryTurnResponse | None = None
    created_at: datetime = Field(alias="createdAt")


class DiscoveryHistoryPage(_StrictModel):
    data: tuple[DiscoveryHistoryMessage, ...]
    next_cursor: str | None = Field(default=None, alias="nextCursor")
    has_more: bool = Field(alias="hasMore")


class DiscoveryOrchestrator(Protocol):
    async def run(self, **kwargs: object) -> DiscoveryRun: ...


class MarketplaceDiscoveryService:
    """Coordinates actor-isolated discovery persistence and one ReAct turn."""

    def __init__(
        self,
        repository: DiscoveryPersistenceRepository,
        orchestrator: MarketplaceDiscoveryOrchestrator | None,
    ) -> None:
        self._repository = repository
        self._conversation = repository.conversation_repository
        self._orchestrator = orchestrator

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
        await self._owned_session(actor_user_id, session_id)
        page = await self._conversation.list_messages(
            session_id=session_id,
            actor_user_id=actor_user_id,
            limit=limit,
            after=cursor,
        )
        messages = tuple(_history_message(item) for item in page.messages)
        return DiscoveryHistoryPage(
            data=messages,
            nextCursor=(
                None
                if page.next_cursor is None
                else encode_cursor(page.next_cursor)
            ),
            hasMore=page.next_cursor is not None,
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
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT:
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.REQUEST_CONFLICT,
                    409,
                    "clientMessageId was already used for a different message.",
                ) from error
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

        started = time.perf_counter()
        try:
            if session.preference_version != expected_preference_version:
                await self._fail(
                    begin.invocation.invocation_id,
                    actor_user_id,
                    "DISCOVERY_PREFERENCE_VERSION_CONFLICT",
                    started,
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
                )
                raise DiscoveryApiError(
                    DiscoveryApiErrorCode.UNAVAILABLE,
                    503,
                    "Marketplace discovery is temporarily unavailable.",
                )
            history_messages = await self._repository.list_recent_messages(
                session_id=session_id,
                actor_user_id=actor_user_id,
                exclude_message_id=begin.user_message.message_id,
                limit=12,
            )
            history = tuple(
                (message.role.value, message.body)
                for message in history_messages
            )
            previous_recommendations = (
                await self._repository.latest_recommendations(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                )
            )
            exclusions = await self._repository.list_exclusions(
                session_id=session_id,
                actor_user_id=actor_user_id,
            )
            run = await self._orchestrator.run(
                actor_user_id=actor_user_id,
                session_id=session_id,
                question=body,
                preference_state=session.preference_state,
                clarification_turn_count=session.clarification_turn_count,
                clarification_question_count=session.clarification_question_count,
                history=history,
                correlation_id=correlation_id,
                previous_recommendations=previous_recommendations,
                excluded_listing_ids=tuple(
                    item.listing_id for item in exclusions
                ),
            )
            for audit in run.audits:
                await self._conversation.append_tool_call(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    sequence_number=audit.sequence_number,
                    tool_name=audit.tool_name,
                    argument_hash=audit.argument_hash,
                    result_hash=audit.result_hash,
                    source_refs=(),
                    result_status=AgentToolCallStatus.SUCCEEDED,
                    error_code=None,
                    latency_ms=audit.latency_ms,
                    now=datetime.now(UTC),
                )
            question_increment = (
                len(run.response.questions)
                if run.response.outcome == "ASK_CLARIFY"
                else 0
            )
            resolution = _resolution(run.response.outcome)
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
            return _send_response(
                begin.user_message,
                _stored_turn(assistant),
                updated.preference_version,
            )
        except asyncio.CancelledError:
            await asyncio.shield(
                self._fail(begin.invocation.invocation_id, actor_user_id, "CANCELLED", started)
            )
            raise
        except AgentPersistenceError as error:
            await self._fail(
                begin.invocation.invocation_id,
                actor_user_id,
                error.code.value,
                started,
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
                "DISCOVERY_EXECUTION_FAILED",
                started,
            )
            raise DiscoveryApiError(
                DiscoveryApiErrorCode.UNAVAILABLE,
                503,
                "Marketplace discovery is temporarily unavailable.",
            ) from error

    async def _fail(
        self,
        invocation_id: str,
        actor_user_id: str,
        error_code: str,
        started: float,
    ) -> None:
        await self._conversation.fail_invocation(
            invocation_id=invocation_id,
            actor_user_id=actor_user_id,
            error_code=error_code,
            input_tokens=0,
            output_tokens=0,
            latency_ms=max(0, round((time.perf_counter() - started) * 1_000)),
            estimated_cost=Decimal("0"),
            now=datetime.now(UTC),
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


def _history_message(message: AgentMessage) -> DiscoveryHistoryMessage:
    result = None if message.role == AgentMessageRole.USER else _stored_turn(message)
    return DiscoveryHistoryMessage(
        id=message.message_id,
        role=message.role.value,
        body=message.body,
        resolutionType=(
            None if message.resolution_type is None else message.resolution_type.value
        ),
        result=result,
        createdAt=message.created_at,
    )


def _resolution(outcome: str) -> AgentResolutionType:
    return {
        "ASK_CLARIFY": AgentResolutionType.CLARIFY,
        "RECOMMEND": AgentResolutionType.RECOMMEND,
        "COMPARE": AgentResolutionType.ANSWERED,
        "NO_RESULTS": AgentResolutionType.NO_RESULTS,
        "REFUSE": AgentResolutionType.REFUSED,
        "HANDOFF": AgentResolutionType.HANDOFF,
    }[outcome]
