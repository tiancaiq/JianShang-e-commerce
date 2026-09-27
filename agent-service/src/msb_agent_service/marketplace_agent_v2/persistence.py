from __future__ import annotations

import json
import re
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal

import aiomysql

from msb_agent_service.agent_persistence import (
    AgentMessage,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentPersistenceRepository,
    AgentSessionStatus,
    BeginInvocation,
    MessageCursor,
    MessagePage,
    new_ulid,
)
from .schemas import (
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction,
    CancelOrderConfirmationArguments,
    SubmitCheckoutConfirmationArguments,
    SubmitReturnConfirmationArguments,
)
from .confirmations import (
    ConfirmationExecution,
    ConfirmationFinancialFact,
    ConfirmationRecord,
    ConfirmationState,
    ConfirmationTarget,
    ConsequentialConfirmationRepository,
    PrepareConfirmation,
    expiry_from_ttl,
)
from .seller_workflow import (
    SellerReplyResolution,
    apply_field_resolution,
    cancel_create_listing_workflow,
    restore_rejected_item_pending,
    resolve_pending_field_reply,
    response_for_replayed_field,
)


_ULID = re.compile(r"[0-9A-HJKMNP-TV-Z]{26}")


@dataclass(frozen=True)
class MarketplaceAgentV2Session:
    session_id: str
    actor_user_id: str
    status: AgentSessionStatus
    created_at: datetime
    updated_at: datetime
    last_activity_at: datetime
    preference_state: dict[str, object]


@dataclass(frozen=True)
class SellerFieldResolution:
    workflow: MarketplaceAgentV2ActiveWorkflow
    pending_interaction: MarketplaceAgentV2PendingInteraction | None
    response: str
    replayed: bool
    resolution: SellerReplyResolution | None = None


class MarketplaceAgentV2Persistence:
    """Isolates V2 sessions while reusing invocation and message infrastructure."""

    def __init__(
        self,
        repository: AgentPersistenceRepository,
        *,
        confirmation_ttl_seconds: int = 900,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._repository = repository
        self._pool = repository.pool
        self._confirmations = ConsequentialConfirmationRepository(self._pool)
        self._confirmation_ttl_seconds = confirmation_ttl_seconds
        self._clock = clock or (lambda: datetime.now(UTC))

    @property
    def conversation(self) -> AgentPersistenceRepository:
        return self._repository

    async def validate_schema(self) -> None:
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_sessions'
                          AND column_name = 'marketplace_agent_v2_open_marker'
                        """
                    )
                    ready = int((await cursor.fetchone())[0]) == 1
            finally:
                await connection.rollback()
        if not ready:
            raise AgentPersistenceError(AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED)
        try:
            await self._confirmations.validate_schema()
        except Exception as error:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED
            ) from error

    async def create_or_resume(
        self,
        *,
        actor_user_id: str,
        new_conversation: bool,
        now: datetime,
    ) -> tuple[MarketplaceAgentV2Session, bool]:
        actor = _id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        session_id = new_ulid()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT * FROM agent_sessions
                        WHERE actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2'
                          AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (actor,),
                    )
                    existing = await cursor.fetchone()
                    if existing is not None and not new_conversation:
                        await connection.commit()
                        return _session(existing), False
                    if existing is not None:
                        await cursor.execute(
                            """
                            UPDATE agent_sessions
                            SET status = 'CLOSED', closed_at = %s, updated_at = %s,
                                last_activity_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE session_id = %s AND actor_user_id = %s AND status = 'OPEN'
                            """,
                            (occurred_at, occurred_at, occurred_at, existing["session_id"], actor),
                        )
                    await cursor.execute(
                        """
                        INSERT INTO agent_sessions (
                            session_id, session_type, actor_user_id, subject_type,
                            subject_listing_id, preference_state_json, preference_version,
                            clarification_turn_count, clarification_question_count, status,
                            created_at, updated_at, last_activity_at
                        ) VALUES (
                            %s, 'MARKETPLACE_AGENT_V2', %s, NULL, NULL, JSON_OBJECT(),
                            0, 0, 0, 'OPEN', %s, %s, %s
                        )
                        """,
                        (session_id, actor, occurred_at, occurred_at, occurred_at),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_sessions WHERE session_id = %s",
                        (session_id,),
                    )
                    created = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        return _session(created), True

    async def get(
        self, *, session_id: str, actor_user_id: str
    ) -> MarketplaceAgentV2Session | None:
        session = _id(session_id)
        actor = _id(actor_user_id)
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT * FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2'
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
            finally:
                await connection.rollback()
        if row is None:
            return None
        loaded = _session(row)
        pending = _pending_from_state(loaded.preference_state)
        now = self._clock()
        if (
            pending is None
            or pending.type != "CONFIRM_ACTION"
            or pending.status not in {"WAITING", "CONFIRMED"}
            or pending.expires_at is None
            or now < pending.expires_at
        ):
            return loaded
        decision = await self._confirmations.expire_if_due(
            confirmation_id=pending.confirmation_id or pending.id,
            actor_user_id=actor,
            session_id=session,
            correlation_id="marketplace-agent-v2-expiry-read",
            now=now,
        )
        if decision.confirmation is None:
            return loaded
        state = dict(loaded.preference_state)
        state["pendingInteraction"] = _pending_state(
            _pending_from_confirmation(decision.confirmation, pending)
        )
        return replace(loaded, preference_state=state)

    async def begin(self, **kwargs: object) -> BeginInvocation:
        return await self._repository.begin_invocation(**kwargs)

    async def set_pending_interaction(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        interaction: MarketplaceAgentV2PendingInteraction,
        now: datetime,
    ) -> MarketplaceAgentV2PendingInteraction:
        """Persists the one active interaction before its terminal response is exposed."""

        return await self._write_pending_interaction(
            session_id=session_id, actor_user_id=actor_user_id,
            replacement=interaction, now=now,
        )

    async def prepare_confirmation(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        originating_invocation_id: str,
        interaction: MarketplaceAgentV2PendingInteraction,
        correlation_id: str,
        now: datetime,
    ) -> MarketplaceAgentV2PendingInteraction:
        """Durably binds a display interaction to one immutable backend action."""

        if interaction.type != "CONFIRM_ACTION" or interaction.summary is None:
            raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
        expires_at = expiry_from_ttl(
            now=now, ttl_seconds=self._confirmation_ttl_seconds
        )
        if interaction.action == "RUN_REFINED_SEARCH":
            request = PrepareConfirmation(
                confirmation_id=interaction.confirmation_id or interaction.id,
                actor_user_id=actor_user_id,
                session_id=session_id,
                originating_invocation_id=originating_invocation_id,
                workflow_id="RUN_REFINED_SEARCH",
                capability="search_listings",
                capability_version="marketplace-search-v4",
                action_name="RUN_REFINED_SEARCH",
                normalized_arguments=interaction.arguments,
                human_summary=interaction.summary,
                risk_level=2,
                expires_at=expires_at,
                correlation_id=correlation_id,
            )
        elif interaction.action == "SUBMIT_CHECKOUT":
            try:
                binding = SubmitCheckoutConfirmationArguments.model_validate(
                    interaction.arguments
                )
            except Exception as error:
                raise AgentPersistenceError(
                    AgentPersistenceErrorCode.INVALID_ARGUMENT
                ) from error
            request = PrepareConfirmation(
                confirmation_id=interaction.confirmation_id or interaction.id,
                actor_user_id=actor_user_id,
                session_id=session_id,
                originating_invocation_id=originating_invocation_id,
                workflow_id="CUSTOMER_CHECKOUT",
                capability="submit_my_checkout",
                capability_version="customer-checkout-v1",
                action_name="SUBMIT_CHECKOUT",
                normalized_arguments=binding.model_dump(mode="json", by_alias=True),
                human_summary=interaction.summary,
                risk_level=3,
                expires_at=expires_at,
                targets=(
                    ConfirmationTarget(
                        resource_type="CHECKOUT",
                        resource_id=binding.checkout_id,
                        version_token=str(binding.cart_version),
                        snapshot_fingerprint=binding.checkout_fingerprint,
                    ),
                    ConfirmationTarget(
                        resource_type="CART",
                        resource_id="CURRENT",
                        version_token=str(binding.cart_version),
                        snapshot_fingerprint=binding.cart_fingerprint,
                    ),
                ),
                financial_facts=(ConfirmationFinancialFact(
                    name="TOTAL",
                    amount=Decimal(binding.total),
                    currency=binding.currency,
                ),),
                correlation_id=correlation_id,
            )
        elif interaction.action == "CANCEL_ORDER":
            try:
                binding = CancelOrderConfirmationArguments.model_validate(
                    interaction.arguments
                )
            except Exception as error:
                raise AgentPersistenceError(
                    AgentPersistenceErrorCode.INVALID_ARGUMENT
                ) from error
            request = PrepareConfirmation(
                confirmation_id=interaction.confirmation_id or interaction.id,
                actor_user_id=actor_user_id,
                session_id=session_id,
                originating_invocation_id=originating_invocation_id,
                workflow_id="CUSTOMER_ORDER_CANCELLATION",
                capability="cancel_my_order",
                capability_version="customer-order-cancellation-v1",
                action_name="CANCEL_ORDER",
                normalized_arguments=binding.model_dump(
                    mode="json", by_alias=True
                ),
                human_summary=interaction.summary,
                risk_level=3,
                expires_at=expires_at,
                targets=(ConfirmationTarget(
                    resource_type="ORDER",
                    resource_id=binding.order_id,
                    version_token=str(binding.order_version),
                    snapshot_fingerprint=binding.order_fingerprint,
                ),),
                financial_facts=(ConfirmationFinancialFact(
                    name="ORDER_TOTAL",
                    amount=Decimal(binding.total),
                    currency=binding.currency,
                ),),
                correlation_id=correlation_id,
            )
        elif interaction.action == "SUBMIT_RETURN_REQUEST":
            try:
                binding = SubmitReturnConfirmationArguments.model_validate(
                    interaction.arguments
                )
            except Exception as error:
                raise AgentPersistenceError(
                    AgentPersistenceErrorCode.INVALID_ARGUMENT
                ) from error
            request = PrepareConfirmation(
                confirmation_id=interaction.confirmation_id or interaction.id,
                actor_user_id=actor_user_id,
                session_id=session_id,
                originating_invocation_id=originating_invocation_id,
                workflow_id="CUSTOMER_RETURN_REQUEST",
                capability="submit_my_return_request",
                capability_version="customer-return-request-v1",
                action_name="SUBMIT_RETURN_REQUEST",
                normalized_arguments=binding.model_dump(
                    mode="json", by_alias=True
                ),
                human_summary=interaction.summary,
                risk_level=3,
                expires_at=expires_at,
                targets=(ConfirmationTarget(
                    resource_type="BUSINESS_ORDER_GROUP",
                    resource_id=binding.business_order_id,
                    version_token=str(binding.group_version),
                    snapshot_fingerprint=binding.group_fingerprint,
                ),),
                correlation_id=correlation_id,
            )
        else:
            raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
        record = await self._confirmations.prepare(
            request,
            pending_interaction=_pending_state(interaction),
            now=now,
        )
        return _pending_from_confirmation(record, interaction)

    async def set_workflow_state(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        workflow: MarketplaceAgentV2ActiveWorkflow,
        pending_interaction: MarketplaceAgentV2PendingInteraction | None,
        now: datetime,
    ) -> None:
        """Persists an active seller workflow and its one current question atomically."""

        session = _id(session_id)
        actor = _id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT preference_state_json FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
                    state = _json_object(row["preference_state_json"])
                    state["activeWorkflow"] = workflow.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    )
                    if pending_interaction is None:
                        state.pop("pendingInteraction", None)
                    else:
                        state["pendingInteraction"] = _pending_state(
                            pending_interaction
                        )
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s,
                            preference_version = preference_version + 1,
                            updated_at = %s, last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (json.dumps(state), occurred_at, occurred_at, session, actor),
                    )
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise

    async def resolve_seller_field_answer(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        user_message_id: str,
        answer: str,
        now: datetime,
    ) -> SellerFieldResolution | None:
        """Consumes one WAITING seller field exactly once before scope classification."""

        session = _id(session_id)
        actor = _id(actor_user_id)
        user_message = _id(user_message_id)
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT preference_state_json FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
                    state = _json_object(row["preference_state_json"])
                    workflow = _workflow_from_state(state)
                    pending = _pending_from_state(state)
                    if workflow is None:
                        await connection.commit()
                        return None
                    workflow, pending = restore_rejected_item_pending(
                        workflow, pending, now=now
                    )
                    if workflow.last_resolved_user_message_id == user_message:
                        await connection.commit()
                        return SellerFieldResolution(
                            workflow=workflow,
                            pending_interaction=pending,
                            response=response_for_replayed_field(workflow, pending),
                            replayed=True,
                            resolution=workflow.last_reply_resolution,
                        )
                    if (
                        pending is None
                        or pending.type != "ANSWER_FIELD"
                        or pending.status != "WAITING"
                    ):
                        await connection.commit()
                        return None
                    semantic = resolve_pending_field_reply(
                        pending=pending, answer=answer,
                    )
                    if semantic.resolution == "UNRELATED_OR_NEW_INTENT":
                        await connection.commit()
                        return None
                    updated, next_pending, response = apply_field_resolution(
                        workflow=workflow,
                        pending=pending,
                        user_message_id=user_message,
                        resolution=semantic,
                        now=now,
                    )
                    state["activeWorkflow"] = updated.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    )
                    if next_pending is None:
                        state.pop("pendingInteraction", None)
                    else:
                        state["pendingInteraction"] = _pending_state(next_pending)
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s,
                            preference_version = preference_version + 1,
                            updated_at = %s, last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (json.dumps(state), occurred_at, occurred_at, session, actor),
                    )
                await connection.commit()
                return SellerFieldResolution(
                    workflow=updated,
                    pending_interaction=next_pending,
                    response=response,
                    replayed=False,
                    resolution=semantic.resolution,
                )
            except Exception:
                await connection.rollback()
                raise

    async def cancel_seller_workflow(
        self, *, session_id: str, actor_user_id: str, now: datetime
    ) -> MarketplaceAgentV2ActiveWorkflow | None:
        """Cancels seller collection and its pending question in one state update."""

        session = _id(session_id)
        actor = _id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT preference_state_json FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
                    state = _json_object(row["preference_state_json"])
                    workflow = _workflow_from_state(state)
                    if workflow is None or workflow.status == "CANCELLED":
                        await connection.commit()
                        return workflow
                    cancelled = cancel_create_listing_workflow(workflow, now=now)
                    state["activeWorkflow"] = cancelled.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    )
                    state.pop("pendingInteraction", None)
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s,
                            preference_version = preference_version + 1,
                            updated_at = %s, last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (json.dumps(state), occurred_at, occurred_at, session, actor),
                    )
                await connection.commit()
                return cancelled
            except Exception:
                await connection.rollback()
                raise

    async def consume_pending_interaction(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        accepted: bool,
        now: datetime,
        invocation_id: str | None = None,
        correlation_id: str = "marketplace-agent-v2-confirmation",
        policy_allowed: bool = True,
        authorization_allowed: bool = True,
        current_resource_versions: dict[str, str] | None = None,
        current_resource_snapshots: dict[str, str] | None = None,
    ) -> MarketplaceAgentV2PendingInteraction | None:
        """Atomically consumes or cancels a WAITING interaction exactly once."""

        current = await self.get(
            session_id=session_id, actor_user_id=actor_user_id
        )
        pending = None if current is None else _pending_from_state(
            current.preference_state
        )
        if pending is None or pending.status not in {"WAITING", "CONFIRMED"}:
            return None
        if pending.type == "CONFIRM_ACTION":
            if invocation_id is None:
                raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
            confirmation_id = pending.confirmation_id or pending.id
            if not accepted:
                decision = await self._confirmations.cancel(
                    confirmation_id=confirmation_id,
                    actor_user_id=actor_user_id,
                    session_id=session_id,
                    invocation_id=invocation_id,
                    correlation_id=correlation_id,
                    now=now,
                )
                if decision.confirmation is None:
                    return await self._invalidate_legacy_confirmation(
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        pending=pending,
                        now=now,
                    )
                return _pending_from_confirmation(decision.confirmation, pending)
            confirmed = await self._confirmations.confirm(
                confirmation_id=confirmation_id,
                actor_user_id=actor_user_id,
                session_id=session_id,
                confirming_invocation_id=invocation_id,
                correlation_id=correlation_id,
                now=now,
            )
            if confirmed.confirmation is None:
                return await self._invalidate_legacy_confirmation(
                    session_id=session_id,
                    actor_user_id=actor_user_id,
                    pending=pending,
                    now=now,
                )
            if not confirmed.allowed:
                return _pending_from_confirmation(confirmed.confirmation, pending)
            record = confirmed.confirmation
            consumed = await self._confirmations.consume(
                ConfirmationExecution(
                    confirmation_id=record.confirmation_id,
                    actor_user_id=actor_user_id,
                    session_id=session_id,
                    consuming_invocation_id=invocation_id,
                    capability=record.capability,
                    capability_version=record.capability_version,
                    action_name=record.action_name,
                    normalized_arguments=record.normalized_arguments,
                    targets=record.targets,
                    financial_facts=record.financial_facts,
                    policy_allowed=policy_allowed,
                    authorization_allowed=authorization_allowed,
                    current_resource_versions=current_resource_versions,
                    current_resource_snapshots=current_resource_snapshots,
                    correlation_id=correlation_id,
                ),
                now=now,
            )
            if consumed.confirmation is None:
                return None
            return _pending_from_confirmation(consumed.confirmation, pending)

        session = _id(session_id)
        actor = _id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT preference_state_json FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
                    state = _json_object(row["preference_state_json"])
                    raw = state.get("pendingInteraction")
                    if not isinstance(raw, dict):
                        await connection.commit()
                        return None
                    pending = MarketplaceAgentV2PendingInteraction.model_validate_json(
                        json.dumps(raw)
                    )
                    if pending.status != "WAITING":
                        await connection.commit()
                        return None
                    updated = pending.model_copy(update={
                        "status": "CONSUMED" if accepted else "CANCELLED"
                    })
                    state["pendingInteraction"] = _pending_state(updated)
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s, preference_version = preference_version + 1,
                            updated_at = %s, last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (json.dumps(state), occurred_at, occurred_at, session, actor),
                    )
                await connection.commit()
                return updated
            except Exception:
                await connection.rollback()
                raise

    async def cancel_pending_interaction(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        now: datetime,
        invocation_id: str | None = None,
        correlation_id: str = "marketplace-agent-v2-confirmation-cancel",
    ) -> MarketplaceAgentV2PendingInteraction | None:
        return await self.consume_pending_interaction(
            session_id=session_id, actor_user_id=actor_user_id,
            accepted=False, now=now, invocation_id=invocation_id,
            correlation_id=correlation_id,
        )

    async def invalidate_pending_interaction(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        now: datetime,
        invocation_id: str | None,
        correlation_id: str,
        reason: str,
    ) -> MarketplaceAgentV2PendingInteraction | None:
        current = await self.get(
            session_id=session_id, actor_user_id=actor_user_id
        )
        pending = None if current is None else _pending_from_state(
            current.preference_state
        )
        if pending is None or pending.status not in {"WAITING", "CONFIRMED"}:
            return None
        if pending.type != "CONFIRM_ACTION":
            return await self.cancel_pending_interaction(
                session_id=session_id,
                actor_user_id=actor_user_id,
                now=now,
                invocation_id=invocation_id,
                correlation_id=correlation_id,
            )
        decision = await self._confirmations.invalidate(
            confirmation_id=pending.confirmation_id or pending.id,
            actor_user_id=actor_user_id,
            session_id=session_id,
            invocation_id=invocation_id,
            correlation_id=correlation_id,
            reason=reason,
            now=now,
        )
        if decision.confirmation is None:
            return await self._invalidate_legacy_confirmation(
                session_id=session_id,
                actor_user_id=actor_user_id,
                pending=pending,
                now=now,
            )
        return _pending_from_confirmation(decision.confirmation, pending)

    async def _invalidate_legacy_confirmation(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        pending: MarketplaceAgentV2PendingInteraction,
        now: datetime,
    ) -> MarketplaceAgentV2PendingInteraction:
        """Fail closed for a pre-V21 WAITING row lacking durable action binding."""

        updated = pending.model_copy(update={"status": "INVALIDATED"})
        await self._write_pending_interaction(
            session_id=session_id,
            actor_user_id=actor_user_id,
            replacement=updated,
            now=now,
        )
        return updated

    async def _write_pending_interaction(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        replacement: MarketplaceAgentV2PendingInteraction,
        now: datetime,
    ) -> MarketplaceAgentV2PendingInteraction:
        session = _id(session_id)
        actor = _id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT preference_state_json FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
                    state = _json_object(row["preference_state_json"])
                    current = state.get("pendingInteraction")
                    replacement_state = _pending_state(replacement)
                    if current == replacement_state:
                        await connection.commit()
                        return replacement
                    state["pendingInteraction"] = replacement_state
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s, preference_version = preference_version + 1,
                            updated_at = %s, last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (json.dumps(state), occurred_at, occurred_at, session, actor),
                    )
                await connection.commit()
                return replacement
            except Exception:
                await connection.rollback()
                raise

    async def list_messages(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        limit: int,
        after: MessageCursor | None = None,
    ) -> MessagePage:
        if await self.get(session_id=session_id, actor_user_id=actor_user_id) is None:
            raise AgentPersistenceError(AgentPersistenceErrorCode.SESSION_NOT_FOUND)
        return await self._repository.list_messages(
            session_id=session_id,
            actor_user_id=actor_user_id,
            limit=limit,
            after=after,
        )


def _id(value: str) -> str:
    if not isinstance(value, str) or _ULID.fullmatch(value) is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value.astimezone(UTC).replace(tzinfo=None)


def _utc(value: object) -> datetime:
    assert isinstance(value, datetime)
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _session(row: dict[str, object]) -> MarketplaceAgentV2Session:
    return MarketplaceAgentV2Session(
        session_id=str(row["session_id"]),
        actor_user_id=str(row["actor_user_id"]),
        status=AgentSessionStatus(str(row["status"])),
        created_at=_utc(row["created_at"]),
        updated_at=_utc(row["updated_at"]),
        last_activity_at=_utc(row["last_activity_at"]),
        preference_state=_json_object(row["preference_state_json"]),
    )


def _pending_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2PendingInteraction | None:
    raw = state.get("pendingInteraction")
    if not isinstance(raw, dict):
        return None
    return MarketplaceAgentV2PendingInteraction.model_validate_json(json.dumps(raw))


def _pending_state(
    pending: MarketplaceAgentV2PendingInteraction,
) -> dict[str, object]:
    """Persists private replacement semantics without widening the public message DTO."""

    payload = pending.model_dump(mode="json", by_alias=True, exclude_none=True)
    if pending.accepts_replacement:
        payload["acceptsReplacement"] = True
    return payload


def _pending_from_confirmation(
    record: ConfirmationRecord,
    template: MarketplaceAgentV2PendingInteraction,
) -> MarketplaceAgentV2PendingInteraction:
    status = {
        ConfirmationState.PENDING: "WAITING",
        ConfirmationState.CONFIRMED: "CONFIRMED",
        ConfirmationState.CONSUMED: "CONSUMED",
        ConfirmationState.CANCELLED: "CANCELLED",
        ConfirmationState.EXPIRED: "EXPIRED",
        ConfirmationState.INVALIDATED: "INVALIDATED",
    }[record.state]
    return template.model_copy(update={
        "id": record.confirmation_id,
        "confirmation_id": record.confirmation_id,
        "arguments": record.normalized_arguments,
        "summary": record.human_summary,
        "status": status,
        "created_at": record.created_at,
        "expires_at": record.expires_at,
    })


def _workflow_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2ActiveWorkflow | None:
    raw = state.get("activeWorkflow")
    if not isinstance(raw, dict):
        return None
    return MarketplaceAgentV2ActiveWorkflow.model_validate_json(json.dumps(raw))


def _json_object(value: object) -> dict[str, object]:
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, (str, bytes, bytearray)):
        parsed = json.loads(value)
        if isinstance(parsed, dict):
            return parsed
    return {}
