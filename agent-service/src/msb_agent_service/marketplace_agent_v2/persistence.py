from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime

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

    def __init__(self, repository: AgentPersistenceRepository) -> None:
        self._repository = repository
        self._pool = repository.pool

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
        return None if row is None else _session(row)

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
    ) -> MarketplaceAgentV2PendingInteraction | None:
        """Atomically consumes or cancels a WAITING interaction exactly once."""

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
        self, *, session_id: str, actor_user_id: str, now: datetime
    ) -> MarketplaceAgentV2PendingInteraction | None:
        return await self.consume_pending_interaction(
            session_id=session_id, actor_user_id=actor_user_id,
            accepted=False, now=now,
        )

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
                    if isinstance(current, dict) and current.get("id") == replacement.id:
                        await connection.commit()
                        return MarketplaceAgentV2PendingInteraction.model_validate_json(
                            json.dumps(current)
                        )
                    state["pendingInteraction"] = _pending_state(replacement)
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
