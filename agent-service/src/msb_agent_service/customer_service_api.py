from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum
from typing import Protocol, Sequence

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .agent_persistence import (
    AgentInvocation,
    AgentMessage,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentPersistenceRepository,
    AgentResolutionType,
    AgentSession,
    AgentSessionStatus,
    BeginInvocation,
    BeginInvocationResult,
    MessageCursor,
)
from .config import AgentApiSettings
from .schemas import (
    AgentMessagePageResponse,
    AgentMessageResponse,
    AgentSessionResponse,
    AgentSubjectListingResponse,
    SendAgentMessageResponse,
)

LOGGER = logging.getLogger(__name__)
_UNAVAILABLE_NOTICE = (
    "Payment and delivery are arranged directly by participants. "
    "The listing is no longer available."
)


class AgentApiErrorCode(StrEnum):
    FEATURE_DISABLED = "AGENT_FEATURE_DISABLED"
    UNAUTHORIZED = "UNAUTHORIZED"
    SESSION_NOT_FOUND = "AGENT_SESSION_NOT_FOUND"
    LISTING_NOT_ELIGIBLE = "AGENT_LISTING_NOT_ELIGIBLE"
    SESSION_READ_ONLY = "AGENT_SESSION_READ_ONLY"
    INVALID_REQUEST = "VALIDATION_ERROR"
    REQUEST_CONFLICT = "AGENT_REQUEST_CONFLICT"
    MESSAGE_IN_PROGRESS = "AGENT_MESSAGE_IN_PROGRESS"
    RETRY_EXHAUSTED = "AGENT_RETRY_EXHAUSTED"
    DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
    ORCHESTRATION_UNAVAILABLE = "AGENT_ORCHESTRATION_UNAVAILABLE"


class AgentApiError(RuntimeError):
    """Carries a stable public API error without retaining request bodies."""

    def __init__(
        self,
        code: AgentApiErrorCode,
        status_code: int,
        message: str,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.status_code = status_code
        self.public_message = message


class _ExternalModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _CurrentUser(_ExternalModel):
    id: str
    status: str


class _CurrentUserEnvelope(_ExternalModel):
    data: _CurrentUser


class _ListingContext(_ExternalModel):
    listing_id: str = Field(alias="listingId")
    source_version: str = Field(alias="sourceVersion")
    eligible: bool
    seller_type: str = Field(alias="sellerType")
    title: str
    thumbnail_url: str | None = Field(default=None, alias="thumbnailUrl")
    transaction_notice: str = Field(alias="transactionNotice")


@dataclass(frozen=True)
class ListingContext:
    listing_id: str
    source_version: str
    title: str
    thumbnail_url: str | None
    transaction_notice: str


@dataclass(frozen=True)
class AnswerDraft:
    body: str
    resolution_type: AgentResolutionType
    sources: Sequence[dict[str, object]]
    actions: Sequence[dict[str, object]]
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    estimated_cost: Decimal = Decimal("0")


@dataclass(frozen=True)
class AnswererMetadata:
    """Identifies the bounded runtime contract persisted for each invocation."""

    prompt_version: str
    model_provider: str
    model_name: str
    schema_version: str = "agent-message-v1"
    tool_registry_version: str = "listing-read-tools-v1"
    policy_version: str = "listing-customer-service-v1"


class QuestionAnswerer(Protocol):
    metadata: AnswererMetadata

    async def answer(
        self,
        *,
        begin: BeginInvocation,
        actor_user_id: str,
        listing: ListingContext,
        correlation_id: str,
    ) -> AnswerDraft: ...


class DeferredQuestionAnswerer:
    """Fails closed until AI-CS-01C installs the bounded RAG orchestrator."""

    metadata = AnswererMetadata(
        prompt_version="deferred-to-ai-cs-01c",
        model_provider="deferred",
        model_name="deferred",
        policy_version="ai-cs-01b",
    )

    async def answer(self, **_: object) -> AnswerDraft:
        raise AgentApiError(
            AgentApiErrorCode.ORCHESTRATION_UNAVAILABLE,
            503,
            "The customer-service answer engine is not available.",
        )


class ActorIdentityClient:
    """Resolves the app-owned actor through Auth Service using the relayed token."""

    def __init__(self, settings: AgentApiSettings) -> None:
        self._base_url = str(settings.auth_service_url).rstrip("/")
        self._timeout = settings.dependency_timeout_seconds

    async def resolve(
        self,
        authorization: str | None,
        correlation_id: str,
    ) -> str:
        if (
            authorization is None
            or not authorization.startswith("Bearer ")
            or len(authorization) > 8_192
        ):
            raise AgentApiError(
                AgentApiErrorCode.UNAUTHORIZED,
                401,
                "Authentication is required.",
            )
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.get(
                    f"{self._base_url}/api/v1/users/me",
                    headers={
                        "Authorization": authorization,
                        "X-Correlation-Id": correlation_id,
                    },
                )
        except httpx.HTTPError as error:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Identity service is temporarily unavailable.",
            ) from error
        if response.status_code in {401, 403}:
            raise AgentApiError(
                AgentApiErrorCode.UNAUTHORIZED,
                401,
                "Authentication is required.",
            )
        if response.status_code >= 500:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Identity service is temporarily unavailable.",
            )
        try:
            user = _CurrentUserEnvelope.model_validate(response.json()).data
        except (ValueError, ValidationError) as error:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Identity service returned an invalid response.",
            ) from error
        if response.status_code != 200 or user.status != "ACTIVE":
            raise AgentApiError(
                AgentApiErrorCode.UNAUTHORIZED,
                401,
                "Authentication is required.",
            )
        return user.id


class ListingEligibilityClient:
    """Reads one strict, agent-safe listing context from Product Service."""

    def __init__(self, settings: AgentApiSettings) -> None:
        self._base_url = str(settings.product_service_url).rstrip("/")
        self._token = str(settings.product_service_token)
        self._timeout = settings.dependency_timeout_seconds

    async def get(
        self,
        listing_id: str,
        correlation_id: str,
    ) -> ListingContext | None:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.get(
                    f"{self._base_url}/api/v1/internal/agent/listings/"
                    f"{listing_id}/customer-service-context",
                    headers={
                        "X-Agent-Internal-Service-Token": self._token,
                        "X-Correlation-Id": correlation_id,
                    },
                )
        except httpx.HTTPError as error:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Listing service is temporarily unavailable.",
            ) from error
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Listing service is temporarily unavailable.",
            )
        try:
            context = _ListingContext.model_validate(response.json())
        except (ValueError, ValidationError) as error:
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Listing service returned an invalid response.",
            ) from error
        if not context.eligible or context.seller_type != "INDIVIDUAL":
            return None
        return ListingContext(
            listing_id=context.listing_id,
            source_version=context.source_version,
            title=context.title,
            thumbnail_url=context.thumbnail_url,
            transaction_notice=context.transaction_notice,
        )


class AgentCustomerService:
    """Coordinates authenticated API rules over the AI-CS-01A repository."""

    def __init__(
        self,
        repository: AgentPersistenceRepository,
        listing_client: ListingEligibilityClient,
        answerer: QuestionAnswerer | None = None,
    ) -> None:
        self._repository = repository
        self._listing_client = listing_client
        self._answerer = answerer or DeferredQuestionAnswerer()
        self._answerer_metadata = getattr(
            self._answerer,
            "metadata",
            DeferredQuestionAnswerer.metadata,
        )

    async def create_session(
        self,
        *,
        actor_user_id: str,
        listing_id: str,
        correlation_id: str,
    ) -> AgentSessionResponse:
        listing = await self._listing_client.get(listing_id, correlation_id)
        if listing is None:
            LOGGER.info(
                "Agent listing eligibility rejected",
                extra={
                    "event": "agent_listing_eligibility_rejected",
                    "correlation_id": correlation_id,
                },
            )
            raise AgentApiError(
                AgentApiErrorCode.LISTING_NOT_ELIGIBLE,
                404,
                "The listing is not available for customer-service assistance.",
            )
        session, _ = await self._repository.create_or_resume_session(
            actor_user_id=actor_user_id,
            subject_listing_id=listing_id,
            now=datetime.now(UTC),
            correlation_id=correlation_id,
        )
        return _session_response(session, listing)

    async def get_session(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        correlation_id: str,
    ) -> AgentSessionResponse:
        session = await self._owned_session(session_id, actor_user_id)
        listing = await self._listing_client.get(
            session.subject_listing_id,
            correlation_id,
        )
        if listing is None:
            session = await self._read_only(session, actor_user_id)
            listing = ListingContext(
                listing_id=session.subject_listing_id,
                source_version="unavailable",
                title="Listing unavailable",
                thumbnail_url=None,
                transaction_notice=_UNAVAILABLE_NOTICE,
            )
        return _session_response(session, listing)

    async def list_messages(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        limit: int,
        cursor: str | None,
    ) -> AgentMessagePageResponse:
        after = decode_cursor(cursor)
        page = await self._repository.list_messages(
            session_id=session_id,
            actor_user_id=actor_user_id,
            limit=limit,
            after=after,
        )
        return AgentMessagePageResponse(
            data=[_message_response(message) for message in page.messages],
            nextCursor=(
                None if page.next_cursor is None else encode_cursor(page.next_cursor)
            ),
            hasMore=page.next_cursor is not None,
        )

    async def send_message(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
        body: str,
        correlation_id: str,
    ) -> SendAgentMessageResponse:
        session = await self._owned_session(session_id, actor_user_id)
        listing = await self._listing_client.get(
            session.subject_listing_id,
            correlation_id,
        )
        if listing is None:
            await self._read_only(session, actor_user_id)
            raise AgentApiError(
                AgentApiErrorCode.SESSION_READ_ONLY,
                409,
                "The listing is no longer available and this session is read-only.",
            )
        begin = await self._repository.begin_invocation(
            session_id=session_id,
            actor_user_id=actor_user_id,
            client_message_id=client_message_id,
            body=body,
            prompt_version=self._answerer_metadata.prompt_version,
            model_provider=self._answerer_metadata.model_provider,
            model_name=self._answerer_metadata.model_name,
            schema_version=self._answerer_metadata.schema_version,
            tool_registry_version=self._answerer_metadata.tool_registry_version,
            policy_version=self._answerer_metadata.policy_version,
            correlation_id=correlation_id,
            now=datetime.now(UTC),
        )
        if begin.result == BeginInvocationResult.DEDUPLICATED_PENDING:
            raise AgentApiError(
                AgentApiErrorCode.MESSAGE_IN_PROGRESS,
                409,
                "This message is already being processed.",
            )
        if begin.result == BeginInvocationResult.RETRY_EXHAUSTED:
            raise AgentApiError(
                AgentApiErrorCode.RETRY_EXHAUSTED,
                409,
                "The retry limit for this message has been reached.",
            )
        if begin.result == BeginInvocationResult.DEDUPLICATED_SUCCEEDED:
            user, assistant = await self._repository.get_invocation_messages(
                invocation_id=begin.invocation.invocation_id,
                actor_user_id=actor_user_id,
            )
            if assistant is None:
                raise AgentApiError(
                    AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                    503,
                    "The stored agent response is temporarily unavailable.",
                )
            return _message_pair(user, assistant)

        started = time.perf_counter()
        try:
            draft = await self._answerer.answer(
                begin=begin,
                actor_user_id=actor_user_id,
                listing=listing,
                correlation_id=correlation_id,
            )
            _, assistant = await self._repository.complete_invocation(
                invocation_id=begin.invocation.invocation_id,
                actor_user_id=actor_user_id,
                body=draft.body,
                resolution_type=draft.resolution_type,
                sources=draft.sources,
                actions=draft.actions,
                input_tokens=draft.input_tokens,
                output_tokens=draft.output_tokens,
                latency_ms=draft.latency_ms,
                estimated_cost=draft.estimated_cost,
                now=datetime.now(UTC),
            )
            if begin.user_message is None:
                raise AgentApiError(
                    AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                    503,
                    "The stored user message is temporarily unavailable.",
                )
            return _message_pair(begin.user_message, assistant)
        except AgentApiError as error:
            await self._record_failure(
                begin.invocation,
                actor_user_id,
                error.code.value,
                started,
            )
            raise
        except asyncio.CancelledError:
            try:
                await asyncio.shield(
                    self._record_failure(
                        begin.invocation,
                        actor_user_id,
                        "AGENT_EXECUTION_CANCELLED",
                        started,
                    )
                )
            except Exception as cleanup_error:
                LOGGER.error(
                    "Cancelled Agent invocation cleanup failed "
                    "invocationId=%s correlationId=%s errorType=%s",
                    begin.invocation.invocation_id,
                    correlation_id,
                    type(cleanup_error).__name__,
                )
            raise
        except Exception as error:
            safe_error_detail: object = None
            errors = getattr(error, "errors", None)
            if callable(errors):
                try:
                    safe_error_detail = errors(include_input=False)
                except TypeError:
                    safe_error_detail = errors()
            LOGGER.warning(
                "Agent invocation failed before response "
                "invocationId=%s correlationId=%s errorType=%s detail=%s",
                begin.invocation.invocation_id,
                correlation_id,
                type(error).__name__,
                safe_error_detail,
            )
            await self._record_failure(
                begin.invocation,
                actor_user_id,
                "AGENT_EXECUTION_FAILED",
                started,
            )
            raise AgentApiError(
                AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
                503,
                "The customer-service agent is temporarily unavailable.",
            )

    async def _record_failure(
        self,
        invocation: AgentInvocation,
        actor_user_id: str,
        error_code: str,
        started: float,
    ) -> None:
        await self._repository.fail_invocation(
            invocation_id=invocation.invocation_id,
            actor_user_id=actor_user_id,
            error_code=error_code,
            input_tokens=0,
            output_tokens=0,
            latency_ms=max(0, int((time.perf_counter() - started) * 1_000)),
            estimated_cost=Decimal("0"),
            now=datetime.now(UTC),
        )

    async def _owned_session(
        self,
        session_id: str,
        actor_user_id: str,
    ) -> AgentSession:
        session = await self._repository.get_session(session_id, actor_user_id)
        if session is None:
            raise AgentApiError(
                AgentApiErrorCode.SESSION_NOT_FOUND,
                404,
                "Agent session was not found.",
            )
        return session

    async def _read_only(
        self,
        session: AgentSession,
        actor_user_id: str,
    ) -> AgentSession:
        current = session
        for _ in range(3):
            if current.status != AgentSessionStatus.OPEN:
                return current
            try:
                return await self._repository.mark_session_read_only(
                    session_id=current.session_id,
                    actor_user_id=actor_user_id,
                    expected_version=current.optimistic_version,
                    now=datetime.now(UTC),
                )
            except AgentPersistenceError as error:
                if error.code != AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT:
                    raise
                refreshed = await self._repository.get_session(
                    current.session_id,
                    actor_user_id,
                )
                if refreshed is None:
                    raise AgentApiError(
                        AgentApiErrorCode.SESSION_NOT_FOUND,
                        404,
                        "Agent session was not found.",
                    )
                current = refreshed
        raise AgentApiError(
            AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
            503,
            "Agent session state is temporarily unavailable.",
        )


def encode_cursor(cursor: MessageCursor) -> str:
    raw = json.dumps(
        {
            "v": 1,
            "createdAt": cursor.created_at.astimezone(UTC).isoformat(),
            "id": cursor.message_id,
        },
        separators=(",", ":"),
    ).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def decode_cursor(value: str | None) -> MessageCursor | None:
    if value is None:
        return None
    if not value or len(value) > 300:
        raise AgentApiError(
            AgentApiErrorCode.INVALID_REQUEST,
            400,
            "Message cursor is invalid.",
        )
    try:
        padded = value + "=" * (-len(value) % 4)
        data = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
        if set(data) != {"v", "createdAt", "id"} or data["v"] != 1:
            raise ValueError
        created_at = datetime.fromisoformat(data["createdAt"])
        if created_at.tzinfo is None:
            raise ValueError
        message_id = data["id"]
        if (
            not isinstance(message_id, str)
            or len(message_id) != 26
            or not message_id.isalnum()
        ):
            raise ValueError
        return MessageCursor(created_at=created_at, message_id=message_id)
    except (ValueError, TypeError, KeyError, json.JSONDecodeError):
        raise AgentApiError(
            AgentApiErrorCode.INVALID_REQUEST,
            400,
            "Message cursor is invalid.",
        )


def map_persistence_error(error: AgentPersistenceError) -> AgentApiError:
    if error.code in {
        AgentPersistenceErrorCode.SESSION_NOT_FOUND,
        AgentPersistenceErrorCode.INVOCATION_NOT_FOUND,
    }:
        return AgentApiError(
            AgentApiErrorCode.SESSION_NOT_FOUND,
            404,
            "Agent session was not found.",
        )
    if error.code == AgentPersistenceErrorCode.SESSION_NOT_OPEN:
        return AgentApiError(
            AgentApiErrorCode.SESSION_READ_ONLY,
            409,
            "This agent session is not open for new messages.",
        )
    if error.code == AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT:
        return AgentApiError(
            AgentApiErrorCode.REQUEST_CONFLICT,
            409,
            "clientMessageId was already used for a different message.",
        )
    if error.code in {
        AgentPersistenceErrorCode.INVALID_ARGUMENT,
    }:
        return AgentApiError(
            AgentApiErrorCode.INVALID_REQUEST,
            400,
            "The request is invalid.",
        )
    return AgentApiError(
        AgentApiErrorCode.DEPENDENCY_UNAVAILABLE,
        503,
        "Agent persistence is temporarily unavailable.",
    )


def _session_response(
    session: AgentSession,
    listing: ListingContext,
) -> AgentSessionResponse:
    return AgentSessionResponse(
        id=session.session_id,
        sessionType="LISTING_CUSTOMER_SERVICE",
        status=session.status.value,
        subjectListing=AgentSubjectListingResponse(
            id=listing.listing_id,
            version=listing.source_version,
            title=listing.title,
            thumbnailUrl=listing.thumbnail_url,
            transactionNotice=listing.transaction_notice,
        ),
        createdAt=session.created_at,
        updatedAt=session.updated_at,
    )


def _message_response(message: AgentMessage) -> AgentMessageResponse:
    return AgentMessageResponse(
        id=message.message_id,
        role=message.role.value,
        body=message.body,
        resolutionType=(
            None if message.resolution_type is None else message.resolution_type.value
        ),
        sources=list(message.sources),
        actions=list(message.actions),
        createdAt=message.created_at,
    )


def _message_pair(
    user: AgentMessage,
    assistant: AgentMessage,
) -> SendAgentMessageResponse:
    return SendAgentMessageResponse(
        userMessage=_message_response(user),
        assistantMessage=_message_response(assistant),
    )
