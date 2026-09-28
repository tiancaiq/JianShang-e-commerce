from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
from datetime import UTC, datetime
from decimal import Decimal
from typing import Awaitable, Callable, Literal

from pydantic import Field

from msb_agent_service.agent_persistence import (
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    AgentResolutionType,
    AgentToolCallStatus,
    BeginInvocationResult,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
)

from .orchestrator import (
    MarketplaceAgentV2OrchestrationFailure,
    MarketplaceAgentV2Orchestrator,
    _ambiguous_confirmation_answer,
    _confirmation_answer,
    _explicit_seller_comparison_request,
)
from .persistence import MarketplaceAgentV2Persistence, MarketplaceAgentV2Session
from .schemas import (
    ListingAttachment,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceAgentV2Refinement,
    MarketplaceAgentV2Message,
    MarketplaceScopeResult,
    OrchestrationResult,
    StrictModel,
    ToolActivity,
    ToolName,
    ToolObservation,
)
from .scope import MarketplaceScopeClassifier, hard_safety_response
from .seller_workflow import can_resolve_seller_field
from .tools import ActivityCallback


class CreateMarketplaceAgentV2SessionRequest(StrictModel):
    session_type: Literal["MARKETPLACE_AGENT_V2"] = "MARKETPLACE_AGENT_V2"
    new_conversation: bool = False


class MarketplaceAgentV2SessionResponse(StrictModel):
    session_id: str
    session_type: Literal["MARKETPLACE_AGENT_V2"] = "MARKETPLACE_AGENT_V2"
    status: Literal["OPEN", "READ_ONLY", "CLOSED"]
    created_at: datetime
    updated_at: datetime


class SendMarketplaceAgentV2MessageRequest(StrictModel):
    client_message_id: str = Field(min_length=26, max_length=26)
    body: str = Field(min_length=1, max_length=8_000)


class RetryMarketplaceAgentV2ResponseRequest(StrictModel):
    client_message_id: str = Field(min_length=26, max_length=26)


class MarketplaceAgentV2UserMessage(StrictModel):
    id: str
    role: Literal["USER"] = "USER"
    body: str
    created_at: datetime


class SendMarketplaceAgentV2MessageResponse(StrictModel):
    session_id: str
    user_message: MarketplaceAgentV2UserMessage
    assistant_message_id: str
    message: MarketplaceAgentV2Message
    decision_count: int = Field(ge=0, le=5)


class MarketplaceAgentV2HistoryMessage(StrictModel):
    id: str
    role: Literal["USER", "ASSISTANT"]
    body: str
    client_message_id: str | None = None
    message: MarketplaceAgentV2Message | None = None
    retryable: bool = False
    response_retry_user_message_id: str | None = None
    created_at: datetime


class MarketplaceAgentV2HistoryPage(StrictModel):
    data: tuple[MarketplaceAgentV2HistoryMessage, ...]
    has_more: bool


class StopMarketplaceAgentV2Response(StrictModel):
    session_id: str
    client_message_id: str
    outcome: Literal["NOT_COMMITTED", "STOPPED", "COMPLETED", "TERMINAL"]


TextDelta = Callable[[str], Awaitable[None]]
Finalized = Callable[[str], Awaitable[None]]
Accepted = Callable[[MarketplaceAgentV2UserMessage], Awaitable[None]]
ToolCompleted = Callable[[object], Awaitable[None]]
log = logging.getLogger(__name__)


class MarketplaceAgentV2Service:
    """Owns the durable V2 turn lifecycle around the isolated orchestrator."""

    def __init__(
        self,
        persistence: MarketplaceAgentV2Persistence,
        orchestrator: MarketplaceAgentV2Orchestrator,
        *,
        provider_name: str,
        model_name: str,
        scope_classifier: MarketplaceScopeClassifier | None = None,
    ) -> None:
        self._persistence = persistence
        self._conversation = persistence.conversation
        self._orchestrator = orchestrator
        self._provider_name = provider_name
        self._model_name = model_name
        self._scope_classifier = scope_classifier or MarketplaceScopeClassifier()

    async def create_session(
        self, *, actor_user_id: str, new_conversation: bool
    ) -> MarketplaceAgentV2SessionResponse:
        session, _ = await self._persistence.create_or_resume(
            actor_user_id=actor_user_id,
            new_conversation=new_conversation,
            now=datetime.now(UTC),
        )
        return _session_response(session)

    async def list_messages(
        self, *, actor_user_id: str, session_id: str, limit: int = 100
    ) -> MarketplaceAgentV2HistoryPage:
        session = await self._persistence.get(
            actor_user_id=actor_user_id, session_id=session_id
        )
        if session is None:
            raise ValueError("Marketplace Agent V2 session is unavailable")
        page = await self._persistence.list_messages(
            actor_user_id=actor_user_id,
            session_id=session_id,
            limit=limit,
        )
        return MarketplaceAgentV2HistoryPage(
            data=tuple(
                _history_message(
                    item, self._conversation.maximum_failed_retries,
                    _pending_from_state(session.preference_state),
                )
                for item in page.messages
            ),
            hasMore=page.next_cursor is not None,
        )

    async def send_message(
        self,
        *,
        actor_user_id: str,
        actor_authorization: str | None = None,
        session_id: str,
        client_message_id: str,
        body: str,
        correlation_id: str,
        activity: ActivityCallback | None = None,
        accepted: Accepted | None = None,
        tool_completed: ToolCompleted | None = None,
        text_delta: TextDelta | None = None,
        finalized: Finalized | None = None,
    ) -> SendMarketplaceAgentV2MessageResponse:
        session = await self._persistence.get(
            actor_user_id=actor_user_id, session_id=session_id
        )
        if session is None or session.status.value != "OPEN":
            raise ValueError("Marketplace Agent V2 session is unavailable")
        started = time.monotonic()
        try:
            begin = await self._persistence.begin(
                session_id=session_id,
                actor_user_id=actor_user_id,
                client_message_id=client_message_id,
                body=body,
                prompt_version="marketplace-agent-v2-prompt-v10",
                model_provider=self._provider_name,
                model_name=self._model_name,
                schema_version="MARKETPLACE_AGENT_V2_MESSAGE_V2",
                tool_registry_version="marketplace-agent-v2-tools-v7",
                policy_version="marketplace-agent-v2-policy-v8",
                correlation_id=correlation_id,
                now=datetime.now(UTC),
            )
        except asyncio.CancelledError:
            await asyncio.shield(self._reconcile_cancelled_begin(
                actor_user_id=actor_user_id,
                session_id=session_id,
                client_message_id=client_message_id,
            ))
            raise
        if begin.user_message is None:
            raise RuntimeError("Marketplace Agent V2 durable user message missing")
        if accepted is not None:
            await accepted(_user_message(begin.user_message))
        if begin.result == BeginInvocationResult.DEDUPLICATED_PENDING:
            raise RuntimeError("Marketplace Agent V2 response is already in progress")
        if begin.result == BeginInvocationResult.RETRY_EXHAUSTED:
            raise RuntimeError("Marketplace Agent V2 response retry limit reached")
        if begin.result == BeginInvocationResult.DEDUPLICATED_SUCCEEDED:
            return await self._replayed_response(
                actor_user_id=actor_user_id,
                session_id=session_id,
                user_message=begin.user_message,
                assistant_message_id=begin.invocation.assistant_message_id,
            )

        completed = False
        prepared_confirmation_id: str | None = None
        checkout_release_verified: bool | None = None
        streamed_parts: list[str] = []
        failure_stage = "HISTORY_LOAD"

        async def capture_delta(delta: str) -> None:
            streamed_parts.append(delta)
            if text_delta is not None:
                await text_delta(delta)

        try:
            current_pending = _pending_from_state(session.preference_state)
            current_workflow = _workflow_from_state(session.preference_state)
            history_page = await self._persistence.list_messages(
                actor_user_id=actor_user_id,
                session_id=session_id,
                limit=100,
            )
            prior = _marketplace_context_messages(
                history_page.messages,
                current_user_message_id=begin.user_message.message_id,
            )[-12:]
            referenced = _referenced_listings(history_page.messages)
            prior_observations = _recent_observations(history_page.messages)
            seller_resolution = None
            confirmed_interaction: MarketplaceAgentV2PendingInteraction | None = None
            unsafe = hard_safety_response(body)
            if unsafe is not None:
                scope_result = self._scope_classifier.classify(
                    current_message=body,
                    recent_messages=prior,
                    referenced_listings=referenced,
                    pending_interaction=current_pending,
                    preference_state=session.preference_state,
                    prior_observations=prior_observations,
                )
                pending_workflow_cancelled = False
                if current_workflow is not None and current_workflow.status != "CANCELLED":
                    current_workflow = await self._persistence.cancel_seller_workflow(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        now=datetime.now(UTC),
                    )
                    current_pending = None
                    pending_workflow_cancelled = True
                elif (
                    current_pending is not None
                    and current_pending.status in {"WAITING", "CONFIRMED"}
                ):
                    await self._cancel_checkout_confirmation(
                        interaction=current_pending,
                        actor_authorization=actor_authorization,
                        correlation_id=correlation_id,
                    )
                    current_pending = await self._persistence.invalidate_pending_interaction(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        now=datetime.now(UTC),
                        invocation_id=begin.invocation.invocation_id,
                        correlation_id=correlation_id,
                        reason="UNSAFE_TURN",
                    )
                    pending_workflow_cancelled = True
                scope_result = scope_result.model_copy(update={
                    "pending_workflow_cancelled": pending_workflow_cancelled
                })
            else:
                if (
                    current_workflow is not None
                    and current_workflow.type == "CREATE_LISTING"
                    and can_resolve_seller_field(current_workflow, current_pending)
                    and not _seller_workflow_command(body)
                ):
                    seller_resolution = await self._persistence.resolve_seller_field_answer(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        user_message_id=begin.user_message.message_id,
                        answer=body,
                        now=datetime.now(UTC),
                    )
                    if seller_resolution is not None:
                        current_workflow = seller_resolution.workflow
                        current_pending = seller_resolution.pending_interaction
                confirmation = _confirmation_answer(body)
                ambiguous_confirmation = _ambiguous_confirmation_answer(body)
                if seller_resolution is not None:
                    scope_result = MarketplaceScopeResult(
                        scope="IN_SCOPE",
                        requiredGrounding="NONE",
                        confidence="HIGH",
                        marketplaceContextUsed=True,
                        reasonCode="SELLER_WORKFLOW_FIELD_ANSWER",
                    )
                    await capture_delta(seller_resolution.response)
                    result = OrchestrationResult(
                        message=MarketplaceAgentV2Message(
                            content=seller_resolution.response,
                            pendingInteraction=current_pending,
                        ),
                        decisionCount=0,
                        pendingInteraction=current_pending,
                        activeWorkflow=current_workflow,
                        scopeResult=scope_result,
                    )
                elif (
                    confirmation is not None
                    and current_pending is not None
                    and current_pending.type == "CONFIRM_ACTION"
                    and current_pending.status in {
                        "CONSUMED", "CANCELLED", "EXPIRED", "INVALIDATED",
                    }
                ):
                    # Preserve the durable terminal projection so the orchestrator
                    # can explain replay without invoking a model or tool.
                    if (
                        current_pending.action == "SUBMIT_CHECKOUT"
                        and current_pending.status
                        in {"CANCELLED", "EXPIRED", "INVALIDATED"}
                    ):
                        checkout_release_verified = (
                            await self._cancel_checkout_confirmation(
                                interaction=current_pending,
                                actor_authorization=actor_authorization,
                                correlation_id=correlation_id,
                            )
                        )
                    scope_result = self._scope_classifier.classify(
                        current_message=body,
                        recent_messages=prior,
                        referenced_listings=referenced,
                        pending_interaction=current_pending,
                        preference_state=session.preference_state,
                        prior_observations=prior_observations,
                    )
                elif confirmation is not None:
                    scope_result = self._scope_classifier.classify(
                        current_message=body,
                        recent_messages=prior,
                        referenced_listings=referenced,
                        pending_interaction=current_pending,
                        preference_state=session.preference_state,
                        prior_observations=prior_observations,
                    )
                    if (
                        not confirmation
                        and current_pending is not None
                        and current_pending.action == "SUBMIT_CHECKOUT"
                    ):
                        checkout_release_verified = (
                            await self._cancel_checkout_confirmation(
                                interaction=current_pending,
                                actor_authorization=actor_authorization,
                                correlation_id=correlation_id,
                            )
                        )
                    current_versions = None
                    current_snapshots = None
                    authorization_allowed = True
                    policy_allowed = (
                        getattr(
                            self._orchestrator,
                            "confirmation_policy_allows",
                            lambda _: True,
                        )(current_pending)
                        if current_pending is not None
                        else False
                    )
                    if (
                        confirmation
                        and current_pending is not None
                        and current_pending.action in {
                            "SUBMIT_CHECKOUT", "CANCEL_ORDER",
                            "SUBMIT_RETURN_REQUEST",
                        }
                    ):
                        action_state = await self._orchestrator.revalidate_confirmation(
                            interaction=current_pending,
                            actor_authorization=actor_authorization,
                            correlation_id=correlation_id,
                        )
                        if action_state is None:
                            policy_allowed = False
                        else:
                            current_versions = action_state.current_versions
                            current_snapshots = action_state.current_snapshots
                            authorization_allowed = (
                                action_state.reason != "AUTHENTICATION_REQUIRED"
                            )
                            if not action_state.valid:
                                if current_snapshots:
                                    first_key = next(iter(current_snapshots))
                                    current_snapshots = dict(current_snapshots)
                                    current_snapshots[first_key] = "0" * 64
                                elif action_state.reason in {
                                    "CHECKOUT_UPSTREAM_UNAVAILABLE",
                                    "ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE",
                                    "RETURN_UPSTREAM_UNAVAILABLE",
                                }:
                                    policy_allowed = False
                                prior_observations = tuple(prior_observations) + (
                                    ToolObservation(
                                        tool=(
                                            "submit_my_checkout"
                                            if current_pending.action == "SUBMIT_CHECKOUT"
                                            else "cancel_my_order"
                                            if current_pending.action == "CANCEL_ORDER"
                                            else "submit_my_return_request"
                                        ),
                                        status=(
                                            "FAILED"
                                            if action_state.reason in {
                                                "CHECKOUT_UPSTREAM_UNAVAILABLE",
                                                "ORDER_CANCELLATION_UPSTREAM_UNAVAILABLE",
                                                "RETURN_UPSTREAM_UNAVAILABLE",
                                            }
                                            else "REJECTED"
                                        ),
                                        reason=action_state.reason,
                                    ),
                                )
                    confirmed_interaction = await self._persistence.consume_pending_interaction(
                        session_id=session_id, actor_user_id=actor_user_id,
                        accepted=confirmation, now=datetime.now(UTC),
                        invocation_id=begin.invocation.invocation_id,
                        correlation_id=correlation_id,
                        policy_allowed=policy_allowed,
                        authorization_allowed=authorization_allowed,
                        current_resource_versions=current_versions,
                        current_resource_snapshots=current_snapshots,
                    )
                    if (
                        confirmed_interaction is not None
                        and confirmed_interaction.action == "SUBMIT_CHECKOUT"
                        and confirmed_interaction.status
                        in {"CANCELLED", "EXPIRED", "INVALIDATED"}
                        and checkout_release_verified is None
                    ):
                        checkout_release_verified = (
                            await self._cancel_checkout_confirmation(
                                interaction=confirmed_interaction,
                                actor_authorization=actor_authorization,
                                correlation_id=correlation_id,
                            )
                        )
                    current_pending = None
                else:
                    scope_result = self._scope_classifier.classify(
                        current_message=body,
                        recent_messages=prior,
                        referenced_listings=referenced,
                        pending_interaction=current_pending,
                        preference_state=session.preference_state,
                        prior_observations=prior_observations,
                    )
                    if (
                        current_pending is not None
                        and current_pending.status in {"WAITING", "CONFIRMED"}
                        and current_pending.type != "ANSWER_FIELD"
                        and not (
                            current_pending.type == "CONFIRM_ACTION"
                            and ambiguous_confirmation
                        )
                        and not _checkout_confirmation_inspection(
                            body, current_pending
                        )
                        and (
                            (
                                scope_result.scope == "IN_SCOPE"
                                and scope_result.reason_code != "AGENT_CAPABILITIES"
                            )
                            or scope_result.reason_code == "CONVERSATION_CANCELLED"
                        )
                    ):
                        await self._cancel_checkout_confirmation(
                            interaction=current_pending,
                            actor_authorization=actor_authorization,
                            correlation_id=correlation_id,
                        )
                        await self._persistence.cancel_pending_interaction(
                            session_id=session_id, actor_user_id=actor_user_id,
                            now=datetime.now(UTC),
                            invocation_id=begin.invocation.invocation_id,
                            correlation_id=correlation_id,
                        )
                        current_pending = None
            log.info(
                "eventCode=MARKETPLACE_AGENT_V2_SCOPE_CLASSIFIED scope=%s "
                "requiredGrounding=%s confidence=%s contextUsed=%s reasonCode=%s",
                scope_result.scope,
                scope_result.required_grounding,
                scope_result.confidence,
                scope_result.marketplace_context_used,
                scope_result.reason_code,
            )
            if seller_resolution is None:
                failure_stage = "ORCHESTRATOR_RUN"

                async def prepare_confirmation(
                    interaction: MarketplaceAgentV2PendingInteraction,
                ) -> MarketplaceAgentV2PendingInteraction:
                    nonlocal prepared_confirmation_id
                    prepared = await self._persistence.prepare_confirmation(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        originating_invocation_id=begin.invocation.invocation_id,
                        interaction=interaction,
                        correlation_id=correlation_id,
                        now=datetime.now(UTC),
                    )
                    prepared_confirmation_id = prepared.id
                    return prepared

                result = await self._orchestrator.run(
                    actor_user_id=actor_user_id,
                    actor_authorization=actor_authorization,
                    current_message=body,
                    recent_messages=prior,
                    referenced_listings=referenced,
                    prior_observations=prior_observations,
                    pending_interaction=current_pending,
                    active_workflow=current_workflow,
                    confirmed_interaction=confirmed_interaction,
                    correlation_id=correlation_id,
                    activity=activity,
                    tool_completed=tool_completed,
                    text_delta=capture_delta,
                    scope_result=scope_result,
                    invocation_id=begin.invocation.invocation_id,
                    confirmation_prepared=prepare_confirmation,
                    checkout_release_verified=checkout_release_verified,
                )
            failure_stage = "TOOL_AUDIT_PERSIST"
            for sequence, tool_activity in enumerate(result.message.tool_activity, 1):
                await self._conversation.append_tool_call(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    sequence_number=sequence,
                    tool_name=tool_activity.tool,
                    argument_hash=_hash(_tool_audit_arguments(
                        tool_activity,
                        result.observations,
                        invocation_id=begin.invocation.invocation_id,
                        sequence=sequence,
                        confirmation_id=(
                            confirmed_interaction.id
                            if confirmed_interaction is not None
                            and tool_activity.tool in {
                                "submit_my_checkout", "cancel_my_order",
                                "submit_my_return_request",
                            }
                            else None
                        ),
                        business_order_id=(
                            str(confirmed_interaction.arguments.get("businessOrderId"))
                            if confirmed_interaction is not None
                            and tool_activity.tool == "submit_my_return_request"
                            and confirmed_interaction.arguments.get("businessOrderId")
                            else None
                        ),
                        checkout_id=(
                            str(confirmed_interaction.arguments.get("checkoutId"))
                            if confirmed_interaction is not None
                            and tool_activity.tool == "submit_my_checkout"
                            and confirmed_interaction.arguments.get("checkoutId")
                            else None
                        ),
                        order_id=(
                            str(confirmed_interaction.arguments.get("orderId"))
                            if confirmed_interaction is not None
                            and tool_activity.tool == "cancel_my_order"
                            and confirmed_interaction.arguments.get("orderId")
                            else None
                        ),
                    )),
                    result_hash=(
                        _hash(tool_activity.model_dump(mode="json"))
                        if tool_activity.status == "SUCCEEDED"
                        else None
                    ),
                    source_refs=_tool_audit_source_refs(
                        tool_activity, result.observations
                    ),
                    result_status=(
                        AgentToolCallStatus.SUCCEEDED
                        if tool_activity.status == "SUCCEEDED"
                        else AgentToolCallStatus.FAILED
                    ),
                    error_code=(
                        None if tool_activity.status == "SUCCEEDED" else tool_activity.reason
                    ),
                    latency_ms=_tool_observation_latency(
                        tool_activity, result.observations
                    ),
                    now=tool_activity.observed_at,
                )
            failure_stage = "ASSISTANT_COMPLETE"
            message_actions: list[dict[str, object]] = [{
                "type": "MARKETPLACE_AGENT_V2_SCOPE",
                **result.scope_result.model_dump(
                    mode="json", by_alias=True, exclude_none=True
                ),
            }]
            message_actions.extend(
                item.model_dump(mode="json", by_alias=True, exclude_none=True)
                for item in result.message.tool_activity
            )
            if result.message.refinement is not None:
                message_actions.append({
                    "type": "MARKETPLACE_AGENT_V2_REFINEMENT",
                    **result.message.refinement.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    ),
                })
            if result.message.citations:
                message_actions.append({
                    "type": "MARKETPLACE_AGENT_V2_CITATIONS",
                    "citations": list(result.message.citations),
                })
            if result.pending_interaction is not None:
                message_actions.append({
                    "type": "MARKETPLACE_AGENT_V2_PENDING_INTERACTION",
                    "interaction": _public_pending(result.pending_interaction).model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    ),
                })
            if result.active_workflow is not None:
                message_actions.append({
                    "type": "MARKETPLACE_AGENT_V2_ACTIVE_WORKFLOW",
                    "workflow": result.active_workflow.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    ),
                })
            message_actions.extend(
                {
                    "type": "MARKETPLACE_AGENT_V2_OBSERVATION",
                    **_persistable_observation(item).model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    ),
                }
                for item in result.observations
                if item.status == "SUCCEEDED"
            )
            message_actions.extend(
                {
                    "type": "MARKETPLACE_AGENT_V2_POLICY_OBSERVATION",
                    "surface": "MARKETPLACE_CUSTOMER",
                    "tool": item.tool,
                    "decision": "DENIED",
                    "reason": item.reason,
                    "observedAt": item.observed_at.isoformat(),
                }
                for item in result.observations
                if item.status == "REJECTED" and item.reason in {
                    "UNKNOWN_TOOL",
                    "CAPABILITY_DISABLED",
                    "SURFACE_MISMATCH",
                    "FORBIDDEN",
                    "MESSAGE_OUT_OF_MARKETPLACE_SCOPE",
                    "TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW",
                }
            )
            message_actions.extend({
                "type": "MARKETPLACE_AGENT_V2_EVIDENCE",
                **item.model_dump(mode="json", by_alias=True),
            } for item in result.evidence)
            if result.active_workflow is not None and seller_resolution is None:
                failure_stage = "WORKFLOW_STATE_PERSIST"
                await self._persistence.set_workflow_state(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                    workflow=result.active_workflow,
                    pending_interaction=result.pending_interaction,
                    now=datetime.now(UTC),
                )
            failure_stage = "ASSISTANT_COMPLETE"
            _, assistant = await self._conversation.complete_invocation(
                invocation_id=begin.invocation.invocation_id,
                actor_user_id=actor_user_id,
                body=result.message.content,
                resolution_type=AgentResolutionType.ANSWERED,
                sources=[
                    item.model_dump(mode="json", by_alias=True, exclude_none=True)
                    for item in result.message.attachments
                ],
                actions=message_actions,
                input_tokens=result.message.input_tokens,
                output_tokens=result.message.output_tokens,
                latency_ms=min(86_400_000, round((time.monotonic() - started) * 1_000)),
                estimated_cost=Decimal("0"),
                now=datetime.now(UTC),
            )
            completed = True
            if (
                result.active_workflow is None
                and result.pending_interaction is not None
                and seller_resolution is None
            ):
                await self._persistence.set_pending_interaction(
                    session_id=session_id, actor_user_id=actor_user_id,
                    interaction=result.pending_interaction, now=datetime.now(UTC),
                )
            if finalized is not None:
                await finalized(assistant.message_id)
            public_message = result.message.model_copy(update={
                "pending_interaction": _public_pending(
                    result.message.pending_interaction
                )
            })
            return SendMarketplaceAgentV2MessageResponse(
                sessionId=session_id,
                userMessage=_user_message(begin.user_message),
                assistantMessageId=assistant.message_id,
                message=public_message,
                decisionCount=result.decision_count,
            )
        except asyncio.CancelledError:
            if not completed:
                if prepared_confirmation_id is not None:
                    await asyncio.shield(
                        self._persistence.invalidate_pending_interaction(
                            session_id=session_id,
                            actor_user_id=actor_user_id,
                            now=datetime.now(UTC),
                            invocation_id=begin.invocation.invocation_id,
                            correlation_id=correlation_id,
                            reason="ORIGINATING_INVOCATION_CANCELLED",
                        )
                    )
                await asyncio.shield(self._persist_terminal_failure(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    error_code="MARKETPLACE_AGENT_V2_STREAM_CANCELLED",
                    body=(
                        "".join(streamed_parts)
                        or "Response stopped before the answer began."
                    ),
                    started=started,
                ))
            raise
        except MarketplaceAgentV2OrchestrationFailure as error:
            error_code = f"MARKETPLACE_AGENT_V2_{error.kind}"
            log.warning(
                "eventCode=MARKETPLACE_AGENT_V2_TURN_FAILED failureKind=%s",
                error.kind,
            )
            if not completed:
                if prepared_confirmation_id is not None:
                    await self._persistence.invalidate_pending_interaction(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        now=datetime.now(UTC),
                        invocation_id=begin.invocation.invocation_id,
                        correlation_id=correlation_id,
                        reason="ORIGINATING_INVOCATION_FAILED",
                    )
                await self._persist_terminal_failure(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    error_code=error_code,
                    body=_guarded_failure_message(error.kind),
                    started=started,
                )
            raise
        except Exception as error:
            failure_kind = (
                "VALIDATION"
                if isinstance(error, ValueError)
                else "PERSISTENCE"
                if isinstance(error, AgentPersistenceError)
                else "UNAVAILABLE"
            )
            log.warning(
                "eventCode=MARKETPLACE_AGENT_V2_TURN_FAILED failureStage=%s failureKind=%s",
                failure_stage,
                failure_kind,
            )
            # Once canonical completion commits, a downstream notification failure
            # must not rewrite the successful invocation as a terminal failure.
            if not completed:
                if prepared_confirmation_id is not None:
                    await self._persistence.invalidate_pending_interaction(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        now=datetime.now(UTC),
                        invocation_id=begin.invocation.invocation_id,
                        correlation_id=correlation_id,
                        reason="ORIGINATING_INVOCATION_FAILED",
                    )
                await self._persist_terminal_failure(
                    invocation_id=begin.invocation.invocation_id,
                    actor_user_id=actor_user_id,
                    error_code="MARKETPLACE_AGENT_V2_TEMPORARY_FAILURE",
                    body=_guarded_failure_message(failure_kind),
                    started=started,
                )
            raise

    async def _cancel_checkout_confirmation(
        self,
        *,
        interaction: MarketplaceAgentV2PendingInteraction,
        actor_authorization: str | None,
        correlation_id: str,
    ) -> bool | None:
        """Release only the checkout bound to an application-owned confirmation."""

        if interaction.action != "SUBMIT_CHECKOUT":
            return None
        cancel = getattr(self._orchestrator, "cancel_checkout_confirmation", None)
        if cancel is None:
            return False
        return bool(await cancel(
            interaction=interaction,
            actor_authorization=actor_authorization,
            correlation_id=correlation_id,
        ))

    async def retry_response(
        self,
        *,
        actor_user_id: str,
        actor_authorization: str | None = None,
        session_id: str,
        user_message_id: str,
        client_message_id: str,
        correlation_id: str,
        **callbacks: object,
    ) -> SendMarketplaceAgentV2MessageResponse:
        """Retry only the response for one owned committed V2 USER row."""

        invocation, user = await self._conversation.get_invocation_for_user_message(
            session_id=session_id,
            actor_user_id=actor_user_id,
            user_message_id=user_message_id,
        )
        if (
            invocation.result_status != AgentInvocationStatus.FAILED
            or invocation.client_message_id is None
            or invocation.client_message_id != client_message_id
            or invocation.retry_count >= self._conversation.maximum_failed_retries
        ):
            raise RuntimeError("Marketplace Agent V2 response is not retryable")
        return await self.send_message(
            actor_user_id=actor_user_id,
            actor_authorization=actor_authorization,
            session_id=session_id,
            client_message_id=invocation.client_message_id,
            body=user.body,
            correlation_id=correlation_id,
            **callbacks,
        )

    async def stop_message(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        client_message_id: str,
    ) -> StopMarketplaceAgentV2Response:
        """Reconcile Stop against durable invocation state, including begin races."""

        try:
            invocation, _ = await self._conversation.get_invocation_for_client_message(
                session_id=session_id,
                actor_user_id=actor_user_id,
                client_message_id=client_message_id,
            )
        except AgentPersistenceError as error:
            if error.code == AgentPersistenceErrorCode.INVOCATION_NOT_FOUND:
                return StopMarketplaceAgentV2Response(
                    sessionId=session_id,
                    clientMessageId=client_message_id,
                    outcome="NOT_COMMITTED",
                )
            raise
        if invocation.result_status == AgentInvocationStatus.PENDING:
            await self._persist_terminal_failure(
                invocation_id=invocation.invocation_id,
                actor_user_id=actor_user_id,
                error_code="MARKETPLACE_AGENT_V2_STREAM_CANCELLED",
                body="Response stopped before the answer began.",
                started=time.monotonic(),
            )
            outcome = "STOPPED"
        elif invocation.result_status == AgentInvocationStatus.SUCCEEDED:
            outcome = "COMPLETED"
        elif invocation.error_code == "MARKETPLACE_AGENT_V2_STREAM_CANCELLED":
            outcome = "STOPPED"
        else:
            outcome = "TERMINAL"
        return StopMarketplaceAgentV2Response(
            sessionId=session_id,
            clientMessageId=client_message_id,
            outcome=outcome,
        )

    async def _reconcile_cancelled_begin(
        self, *, actor_user_id: str, session_id: str, client_message_id: str
    ) -> None:
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
            await self._persist_terminal_failure(
                invocation_id=invocation.invocation_id,
                actor_user_id=actor_user_id,
                error_code="MARKETPLACE_AGENT_V2_STREAM_CANCELLED",
                body="Response stopped before the answer began.",
                started=time.monotonic(),
            )

    async def _persist_terminal_failure(
        self, *, invocation_id: str, actor_user_id: str, error_code: str,
        body: str, started: float,
    ) -> None:
        await self._conversation.fail_invocation_with_assistant(
            invocation_id=invocation_id,
            actor_user_id=actor_user_id,
            error_code=error_code,
            body=body,
            sources=(),
            actions=({"type": "MARKETPLACE_AGENT_V2_FAILURE", "retryable": True},),
            latency_ms=min(86_400_000, round((time.monotonic() - started) * 1_000)),
            now=datetime.now(UTC),
            resolution_type=AgentResolutionType.PARTIAL,
        )

    async def _replayed_response(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        user_message: AgentMessage,
        assistant_message_id: str | None,
    ) -> SendMarketplaceAgentV2MessageResponse:
        if assistant_message_id is None:
            raise RuntimeError("Marketplace Agent V2 replay identity missing")
        page = await self._persistence.list_messages(
            actor_user_id=actor_user_id, session_id=session_id, limit=100
        )
        assistant = next(
            (item for item in page.messages if item.message_id == assistant_message_id), None
        )
        if assistant is None:
            raise RuntimeError("Marketplace Agent V2 replay message missing")
        parsed = _assistant_message(assistant)
        if parsed.pending_interaction is not None:
            session = await self._persistence.get(
                session_id=session_id, actor_user_id=actor_user_id
            )
            current = None if session is None else _pending_from_state(
                session.preference_state
            )
            if current is not None and current.id == parsed.pending_interaction.id:
                parsed = parsed.model_copy(update={
                    "pending_interaction": _public_pending(current)
                })
            elif current is None:
                await self._persistence.set_pending_interaction(
                    session_id=session_id, actor_user_id=actor_user_id,
                    interaction=parsed.pending_interaction, now=datetime.now(UTC),
                )
            else:
                parsed = parsed.model_copy(update={
                    "pending_interaction": parsed.pending_interaction.model_copy(
                        update={"status": "CANCELLED"}
                    )
                })
        return SendMarketplaceAgentV2MessageResponse(
            sessionId=session_id,
            userMessage=_user_message(user_message),
            assistantMessageId=assistant.message_id,
            message=parsed,
            decisionCount=1,
        )


def _session_response(session: MarketplaceAgentV2Session) -> MarketplaceAgentV2SessionResponse:
    return MarketplaceAgentV2SessionResponse(
        sessionId=session.session_id,
        status=session.status.value,
        createdAt=session.created_at,
        updatedAt=session.updated_at,
    )


def _user_message(message: AgentMessage) -> MarketplaceAgentV2UserMessage:
    return MarketplaceAgentV2UserMessage(
        id=message.message_id, body=message.body, createdAt=message.created_at
    )


def _history_message(
    message: AgentMessage, maximum_failed_retries: int,
    active_pending: MarketplaceAgentV2PendingInteraction | None = None,
) -> MarketplaceAgentV2HistoryMessage:
    retryable = bool(
        message.invocation_status == AgentInvocationStatus.FAILED
        and message.invocation_retry_count is not None
        and message.invocation_retry_count < maximum_failed_retries
    )
    if message.role == AgentMessageRole.USER:
        return MarketplaceAgentV2HistoryMessage(
            id=message.message_id,
            role="USER",
            body=message.body,
            clientMessageId=message.client_message_id,
            retryable=retryable and message.invocation_assistant_message_id is None,
            responseRetryUserMessageId=(
                message.message_id
                if retryable and message.invocation_assistant_message_id is None
                else None
            ),
            createdAt=message.created_at,
        )
    parsed = _assistant_message(message)
    if parsed.pending_interaction is not None:
        if active_pending is not None and active_pending.id == parsed.pending_interaction.id:
            parsed = parsed.model_copy(update={
                "pending_interaction": _public_pending(active_pending)
            })
        elif parsed.pending_interaction.status == "WAITING":
            parsed = parsed.model_copy(update={
                "pending_interaction": parsed.pending_interaction.model_copy(
                    update={"status": "CANCELLED"}
                )
            })
    return MarketplaceAgentV2HistoryMessage(
        id=message.message_id,
        role="ASSISTANT",
        body=message.body,
        message=parsed,
        retryable=retryable,
        responseRetryUserMessageId=(
            message.invocation_user_message_id if retryable else None
        ),
        createdAt=message.created_at,
    )


def _assistant_message(message: AgentMessage) -> MarketplaceAgentV2Message:
    # Persistence returns JSON-shaped dictionaries. Validate them through Pydantic's
    # JSON path so strict datetime fields accept their canonical ISO representation.
    attachments = tuple(
        ListingAttachment.model_validate_json(json.dumps(item))
        for item in message.sources
    )
    activities = tuple(
        ToolActivity.model_validate_json(json.dumps(item))
        for item in message.actions
        if set(item) == {"tool", "status", "reason", "observedAt"}
    )
    refinement = next(
        (
            MarketplaceAgentV2Refinement.model_validate_json(json.dumps({
                key: value for key, value in item.items() if key != "type"
            }))
            for item in message.actions
            if item.get("type") == "MARKETPLACE_AGENT_V2_REFINEMENT"
        ),
        None,
    )
    pending = next(
        (
            MarketplaceAgentV2PendingInteraction.model_validate_json(
                json.dumps(item["interaction"])
            )
            for item in message.actions
            if item.get("type") == "MARKETPLACE_AGENT_V2_PENDING_INTERACTION"
            and isinstance(item.get("interaction"), dict)
        ),
        None,
    )
    citations = _persisted_citations(message.actions)
    return MarketplaceAgentV2Message(
        content=message.body,
        attachments=attachments,
        refinement=refinement,
        pendingInteraction=pending,
        citations=citations,
        toolActivity=activities,
    )


def _persisted_citations(
    actions: tuple[dict[str, object], ...],
) -> tuple[str, ...]:
    """Restore only bounded public citations written by this Agent version."""

    for action in actions:
        if action.get("type") != "MARKETPLACE_AGENT_V2_CITATIONS":
            continue
        raw = action.get("citations")
        if not isinstance(raw, list) or len(raw) > 10:
            return ()
        result: list[str] = []
        for item in raw:
            if (
                not isinstance(item, str)
                or item != item.strip()
                or not 1 <= len(item) <= 500
            ):
                return ()
            if item not in result:
                result.append(item)
        return tuple(result)
    return ()


def _referenced_listings(messages: tuple[AgentMessage, ...]) -> tuple[ListingAttachment, ...]:
    # Only the most recent result-bearing assistant message is the active ordered
    # recommendation set. Earlier cards remain history, not ordinal context.
    for message in reversed(messages):
        result: list[ListingAttachment] = []
        for source in message.sources:
            try:
                attachment = ListingAttachment.model_validate_json(json.dumps(source))
            except Exception:
                continue
            result.append(attachment)
        if result:
            return tuple(result[:20])
    return ()


def _marketplace_context_messages(
    messages: tuple[AgentMessage, ...],
    *,
    current_user_message_id: str,
) -> tuple[tuple[str, str], ...]:
    """Exclude prior out-of-scope pairs so they cannot alter later tool arguments."""

    excluded: set[str] = {current_user_message_id}
    for message in messages:
        if message.role != AgentMessageRole.ASSISTANT:
            continue
        scope_action = next(
            (
                action for action in message.actions
                if action.get("type") == "MARKETPLACE_AGENT_V2_SCOPE"
            ),
            None,
        )
        if scope_action is None or scope_action.get("scope") != "OUT_OF_SCOPE":
            continue
        excluded.add(message.message_id)
        if message.invocation_user_message_id is not None:
            excluded.add(message.invocation_user_message_id)
    return tuple(
        (message.role.value, message.body)
        for message in messages
        if message.message_id not in excluded
    )


def _pending_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2PendingInteraction | None:
    raw = state.get("pendingInteraction")
    if not isinstance(raw, dict):
        return None
    try:
        return MarketplaceAgentV2PendingInteraction.model_validate_json(json.dumps(raw))
    except Exception:
        return None


def _public_pending(
    pending: MarketplaceAgentV2PendingInteraction | None,
) -> MarketplaceAgentV2PendingInteraction | None:
    """Keeps exact Level-3 bindings server-side while preserving UI lifecycle."""

    if pending is None or pending.action not in {
        "SUBMIT_CHECKOUT", "CANCEL_ORDER", "SUBMIT_RETURN_REQUEST",
    }:
        return pending
    return pending.model_copy(update={"arguments": {}})


def _workflow_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2ActiveWorkflow | None:
    raw = state.get("activeWorkflow")
    if not isinstance(raw, dict):
        return None
    try:
        return MarketplaceAgentV2ActiveWorkflow.model_validate_json(json.dumps(raw))
    except Exception:
        return None


def _seller_workflow_command(value: str) -> bool:
    """Leaves explicit comparison/publish commands for normal model-first planning."""

    normalized = " ".join(value.casefold().split())
    return _explicit_seller_comparison_request(value) or any(
        phrase in normalized
        for phrase in (
            "publish it", "publish the listing", "create the listing",
            "post the listing", "make it live",
        )
    )


def _checkout_confirmation_inspection(
    value: str,
    pending: MarketplaceAgentV2PendingInteraction,
) -> bool:
    """Keep a prepared checkout pending during an explicit read-only inspection."""

    if not (
        pending.type == "CONFIRM_ACTION"
        and pending.action == "SUBMIT_CHECKOUT"
        and pending.status == "WAITING"
    ):
        return False
    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:what(?:'s| is)\s+in|show(?:\s+me)?|open|review)\b.{0,45}"
        r"\b(?:this|that|my|the)\s+checkout\b|"
        r"\bwhat(?:'s| is)\s+the\s+(?:checkout\s+)?total\s+again\b|"
        r"\bis\s+(?:this|that|my|the)\s+checkout\s+still\s+active\b",
        normalized,
    ))


def _recent_observations(messages: tuple[AgentMessage, ...]) -> tuple[ToolObservation, ...]:
    """Restores only bounded safe V2 observations; display activities remain unchanged."""

    result: list[ToolObservation] = []
    for message in messages:
        for action in message.actions:
            if action.get("type") != "MARKETPLACE_AGENT_V2_OBSERVATION":
                continue
            payload = {key: value for key, value in action.items() if key != "type"}
            try:
                result.append(ToolObservation.model_validate_json(json.dumps(payload)))
            except Exception:
                continue
    # Keep bounded actor-owned identities across a short commerce conversation;
    # persisted observations below contain references, never stale cart/order facts.
    return tuple(result[-12:])


def _persistable_observation(observation: ToolObservation) -> ToolObservation:
    """Retains commerce references, never stale cart/order facts, between turns."""

    if observation.tool not in {
        "get_my_cart", "list_my_orders", "get_my_order", "add_to_my_cart",
        "update_my_cart_quantity", "remove_from_my_cart",
        "preview_my_order_cancellation", "cancel_my_order",
        "get_my_return", "prepare_my_return_request", "submit_my_return_request",
    }:
        return observation
    return observation.model_copy(update={
        "cart": None,
        "orders": (),
        "order": None,
        "return_request": None,
        "result_count": None,
    })


def _matching_tool_observation(
    activity: ToolActivity,
    observations: tuple[ToolObservation, ...],
) -> ToolObservation | None:
    return next((
        item for item in observations
        if item.tool == activity.tool
        and item.status == activity.status
        and item.reason == activity.reason
        and item.observed_at == activity.observed_at
    ), None)


def _tool_audit_arguments(
    activity: ToolActivity,
    observations: tuple[ToolObservation, ...],
    *,
    invocation_id: str,
    sequence: int,
    confirmation_id: str | None = None,
    checkout_id: str | None = None,
    order_id: str | None = None,
    business_order_id: str | None = None,
) -> dict[str, object]:
    """Hashes bounded cart intent metadata without storing credentials or bodies."""

    result: dict[str, object] = {"tool": activity.tool, "sequence": sequence}
    observation = _matching_tool_observation(activity, observations)
    if activity.tool == "submit_my_checkout":
        result.update({
            "confirmationReference": (
                None if confirmation_id is None
                else _hash({"confirmationId": confirmation_id})
            ),
            "checkoutReference": (
                None if checkout_id is None
                else _hash({"checkoutId": checkout_id})
            ),
            "actionReference": (
                None if confirmation_id is None
                else _hash({"actionKey": f"agent-action-{confirmation_id}"})
            ),
        })
        return result
    if activity.tool == "cancel_my_order":
        result.update({
            "confirmationReference": (
                None if confirmation_id is None
                else _hash({"confirmationId": confirmation_id})
            ),
            "orderReference": (
                None if order_id is None
                else _hash({"orderId": order_id})
            ),
            "actionReference": (
                None if confirmation_id is None
                else _hash({
                    "actionKey": f"agent-action-{confirmation_id}"
                })
            ),
        })
        return result
    if activity.tool == "submit_my_return_request":
        result.update({
            "confirmationReference": (
                None if confirmation_id is None
                else _hash({"confirmationId": confirmation_id})
            ),
            "orderGroupReference": (
                None if business_order_id is None
                else _hash({"businessOrderId": business_order_id})
            ),
            "actionReference": (
                None if confirmation_id is None
                else _hash({"actionKey": f"agent-action-{confirmation_id}"})
            ),
        })
        return result
    if (
        activity.tool == "preview_my_order_cancellation"
        and observation is not None
        and observation.order_references
    ):
        result["orderReference"] = _hash({
            "orderId": observation.order_references[0].order_id
        })
        result["actionReference"] = _hash({"invocationId": invocation_id})
        return result
    if (
        activity.tool in {"get_my_return", "prepare_my_return_request"}
        and observation is not None
        and observation.order_references
    ):
        result["orderReference"] = _hash({
            "orderId": observation.order_references[0].order_id
        })
        if activity.tool == "prepare_my_return_request":
            result["actionReference"] = _hash({"invocationId": invocation_id})
        return result
    if observation is None or observation.cart_mutation_reference is None:
        return result
    mutation = observation.cart_mutation_reference
    result.update({
        "listingId": mutation.listing_id,
        "operation": mutation.operation,
        "requestedQuantity": mutation.requested_quantity,
        "actionReference": _hash({"invocationId": invocation_id}),
    })
    return result


def _tool_observation_latency(
    activity: ToolActivity,
    observations: tuple[ToolObservation, ...],
) -> int:
    observation = _matching_tool_observation(activity, observations)
    return 0 if observation is None or observation.latency_ms is None else observation.latency_ms


def _tool_audit_source_refs(
    activity: ToolActivity,
    observations: tuple[ToolObservation, ...],
) -> tuple[dict[str, object], ...]:
    """Persist exact public-help identities and versions for retrieval audits."""

    observation = _matching_tool_observation(activity, observations)
    if observation is None or activity.tool != "retrieve_help":
        return ()
    return tuple({
        "sourceType": "KNOWLEDGE_DOCUMENT",
        "sourceId": passage.article_id,
        "sourceVersion": passage.version,
    } for passage in observation.knowledge_passages)


def _hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")
    ).hexdigest()


def _guarded_failure_message(kind: str) -> str:
    """Map a private orchestration category to one honest retryable customer message."""

    if kind in {"MODEL_RESPONSE_UNSUPPORTED", "MODEL_DECISION_INVALID", "VALIDATION"}:
        return (
            "I couldn't safely complete this response. "
            "You can retry without sending your message again."
        )
    if kind == "MODEL_OUTPUT_LIMIT_EXCEEDED":
        return (
            "I couldn't complete this response because the model reached its "
            "response limit. You can retry without sending your message again."
        )
    return (
        "I couldn't complete this response because a marketplace service was "
        "temporarily unavailable. You can retry without sending your message again."
    )
