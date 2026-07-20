from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Sequence

import aiomysql

from .agent_persistence import (
    AgentMessage,
    AgentMessageRole,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentPersistenceRepository,
    AgentResolutionType,
    AgentSessionStatus,
    new_ulid,
)
from .marketplace_discovery import (
    DiscoveryPreferenceState,
    DiscoveryTurnResponse,
)

_ID_PATTERN = re.compile(r"^[0-9A-Z]{26}$")


@dataclass(frozen=True)
class DiscoverySession:
    session_id: str
    actor_user_id: str
    status: AgentSessionStatus
    preference_state: DiscoveryPreferenceState
    preference_version: int
    clarification_turn_count: int
    clarification_question_count: int
    created_at: datetime
    updated_at: datetime
    last_activity_at: datetime
    optimistic_version: int


class DiscoveryPersistenceRepository:
    """Owns subjectless discovery state inside the existing Agent schema."""

    def __init__(self, repository: AgentPersistenceRepository) -> None:
        self._repository = repository
        self._pool = repository.pool

    @property
    def conversation_repository(self) -> AgentPersistenceRepository:
        return self._repository

    async def validate_schema(self) -> None:
        """Fail closed until the forward-only V7 session extension is present."""

        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_sessions'
                          AND column_name IN (
                              'preference_state_json',
                              'preference_version',
                              'clarification_turn_count',
                              'clarification_question_count',
                              'discovery_open_marker'
                          )
                        """
                    )
                    columns = {str(row[0]) for row in await cursor.fetchall()}
                    await cursor.execute(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_discovery_recommendations'
                        """
                    )
                    recommendation_table = int((await cursor.fetchone())[0])
            finally:
                await connection.rollback()
        if columns != {
            "preference_state_json",
            "preference_version",
            "clarification_turn_count",
            "clarification_question_count",
            "discovery_open_marker",
        } or recommendation_table != 1:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED
            )

    async def create_or_resume(
        self,
        *,
        actor_user_id: str,
        new_search: bool,
        now: datetime,
    ) -> tuple[DiscoverySession, bool]:
        """Create one actor session or resume it; New search closes it first."""

        actor = _fixed_id(actor_user_id)
        occurred_at = _mysql_datetime(now)
        session_id = new_ulid()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE actor_user_id = %s
                          AND session_type = 'MARKETPLACE_DISCOVERY'
                          AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (actor,),
                    )
                    existing = await cursor.fetchone()
                    if existing is not None and not new_search:
                        await connection.commit()
                        return _session(existing), False
                    if existing is not None:
                        await cursor.execute(
                            """
                            UPDATE agent_sessions
                            SET status = 'CLOSED',
                                closed_at = %s,
                                updated_at = %s,
                                last_activity_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE session_id = %s
                              AND actor_user_id = %s
                              AND status = 'OPEN'
                            """,
                            (
                                occurred_at,
                                occurred_at,
                                occurred_at,
                                existing["session_id"],
                                actor,
                            ),
                        )
                    await cursor.execute(
                        """
                        INSERT INTO agent_sessions (
                            session_id,
                            session_type,
                            actor_user_id,
                            subject_type,
                            subject_listing_id,
                            preference_state_json,
                            preference_version,
                            clarification_turn_count,
                            clarification_question_count,
                            status,
                            created_at,
                            updated_at,
                            last_activity_at
                        )
                        VALUES (
                            %s,
                            'MARKETPLACE_DISCOVERY',
                            %s,
                            NULL,
                            NULL,
                            JSON_OBJECT(),
                            0,
                            0,
                            0,
                            'OPEN',
                            %s,
                            %s,
                            %s
                        )
                        """,
                        (
                            session_id,
                            actor,
                            occurred_at,
                            occurred_at,
                            occurred_at,
                        ),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_sessions WHERE session_id = %s",
                        (session_id,),
                    )
                    created = await cursor.fetchone()
                await connection.commit()
                return _session(created), True
            except aiomysql.IntegrityError:
                await connection.rollback()
            except Exception:
                await connection.rollback()
                raise
        resumed = await self.get_open(actor_user_id=actor)
        if resumed is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
            )
        return resumed, False

    async def get(
        self,
        *,
        session_id: str,
        actor_user_id: str,
    ) -> DiscoverySession | None:
        """Hide missing and cross-actor discovery sessions identically."""

        session = _fixed_id(session_id)
        actor = _fixed_id(actor_user_id)
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_DISCOVERY'
                        """,
                        (session, actor),
                    )
                    row = await cursor.fetchone()
            finally:
                await connection.rollback()
        return None if row is None else _session(row)

    async def get_open(self, *, actor_user_id: str) -> DiscoverySession | None:
        actor = _fixed_id(actor_user_id)
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE actor_user_id = %s
                          AND session_type = 'MARKETPLACE_DISCOVERY'
                          AND status = 'OPEN'
                        """,
                        (actor,),
                    )
                    row = await cursor.fetchone()
            finally:
                await connection.rollback()
        return None if row is None else _session(row)

    async def list_recent_messages(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        exclude_message_id: str,
        limit: int = 12,
    ) -> tuple[AgentMessage, ...]:
        """Return bounded prior context without duplicating the current turn."""

        session = _fixed_id(session_id)
        actor = _fixed_id(actor_user_id)
        excluded = _fixed_id(exclude_message_id)
        if not 1 <= limit <= 12:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_messages
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND message_id <> %s
                        ORDER BY created_at DESC, message_id DESC
                        LIMIT %s
                        """,
                        (session, actor, excluded, limit),
                    )
                    rows = list(await cursor.fetchall())
            finally:
                await connection.rollback()
        rows.reverse()
        return tuple(_message(row) for row in rows)

    async def complete_turn(
        self,
        *,
        invocation_id: str,
        session_id: str,
        actor_user_id: str,
        expected_preference_version: int,
        response: DiscoveryTurnResponse,
        resolution_type: AgentResolutionType,
        clarification_turn_increment: int,
        clarification_question_increment: int,
        latency_ms: int,
        now: datetime,
    ) -> tuple[DiscoverySession, AgentMessage]:
        """Commit preference state, assistant result, and snapshots as one turn."""

        invocation = _fixed_id(invocation_id)
        session = _fixed_id(session_id)
        actor = _fixed_id(actor_user_id)
        if (
            not 0 <= expected_preference_version <= 9_223_372_036_854_775_807
            or clarification_turn_increment not in {0, 1}
            or not 0 <= clarification_question_increment <= 2
            or not 0 <= latency_ms <= 86_400_000
            or response.estimated_cost != 0
        ):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        occurred_at = _mysql_datetime(now)
        encoded_state = response.preference_state.model_dump_json(
            by_alias=True,
            exclude_none=True,
        )
        sources = [
            item.model_dump(mode="json", by_alias=True, exclude_none=True)
            for item in response.recommendations
        ]
        actions = [
            {
                "type": "DISCOVERY_RESULT",
                "result": response.model_dump(
                    mode="json",
                    by_alias=True,
                    exclude_none=True,
                ),
            }
        ]
        sources_json = _json_array(sources, maximum_bytes=32_000)
        actions_json = _json_array(actions, maximum_bytes=16_000)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_invocations
                        WHERE invocation_id = %s
                          AND actor_user_id = %s
                        FOR UPDATE
                        """,
                        (invocation, actor),
                    )
                    invocation_row = await cursor.fetchone()
                    if (
                        invocation_row is None
                        or invocation_row["session_id"] != session
                        or invocation_row["result_status"] != "PENDING"
                    ):
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
                        )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND session_type = 'MARKETPLACE_DISCOVERY'
                        FOR UPDATE
                        """,
                        (session, actor),
                    )
                    session_row = await cursor.fetchone()
                    if session_row is None:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_NOT_FOUND
                        )
                    if session_row["status"] != AgentSessionStatus.OPEN.value:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_NOT_OPEN
                        )
                    if (
                        int(session_row["preference_version"])
                        != expected_preference_version
                        or int(session_row["clarification_turn_count"])
                        + clarification_turn_increment
                        > 3
                        or int(session_row["clarification_question_count"])
                        + clarification_question_increment
                        > 5
                    ):
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
                        )
                    message_id = new_ulid()
                    await cursor.execute(
                        """
                        INSERT INTO agent_messages (
                            message_id,
                            session_id,
                            actor_user_id,
                            role,
                            body,
                            resolution_type,
                            sources_json,
                            actions_json,
                            created_at
                        )
                        VALUES (
                            %s, %s, %s, 'ASSISTANT', %s, %s, %s, %s, %s
                        )
                        """,
                        (
                            message_id,
                            session,
                            actor,
                            response.message,
                            resolution_type.value,
                            sources_json,
                            actions_json,
                            occurred_at,
                        ),
                    )
                    await cursor.execute(
                        """
                        UPDATE agent_invocations
                        SET assistant_message_id = %s,
                            result_status = 'SUCCEEDED',
                            input_tokens = %s,
                            output_tokens = %s,
                            latency_ms = %s,
                            estimated_cost = 0,
                            updated_at = %s,
                            completed_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE invocation_id = %s
                          AND actor_user_id = %s
                          AND result_status = 'PENDING'
                        """,
                        (
                            message_id,
                            response.input_tokens,
                            response.output_tokens,
                            latency_ms,
                            occurred_at,
                            occurred_at,
                            invocation,
                            actor,
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
                        )
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET preference_state_json = %s,
                            preference_version = preference_version + 1,
                            clarification_turn_count =
                                clarification_turn_count + %s,
                            clarification_question_count =
                                clarification_question_count + %s,
                            updated_at = %s,
                            last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND preference_version = %s
                        """,
                        (
                            encoded_state,
                            clarification_turn_increment,
                            clarification_question_increment,
                            occurred_at,
                            occurred_at,
                            session,
                            actor,
                            expected_preference_version,
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
                        )
                    for rank, recommendation in enumerate(
                        response.recommendations,
                        1,
                    ):
                        snapshot = recommendation.model_dump(
                            mode="json",
                            by_alias=True,
                            exclude_none=True,
                        )
                        await cursor.execute(
                            """
                            INSERT INTO agent_discovery_recommendations (
                                recommendation_id,
                                invocation_id,
                                session_id,
                                actor_user_id,
                                recommendation_rank,
                                listing_id,
                                checked_at,
                                response_hash,
                                match_reason,
                                constraint_coverage_json,
                                listing_snapshot_json,
                                created_at
                            )
                            VALUES (
                                %s, %s, %s, %s, %s, %s, %s, %s,
                                %s, %s, %s, %s
                            )
                            """,
                            (
                                new_ulid(),
                                invocation,
                                session,
                                actor,
                                rank,
                                recommendation.listing_id,
                                _mysql_datetime(
                                    recommendation.provenance.checked_at
                                ),
                                recommendation.provenance.response_hash,
                                recommendation.match_reason,
                                json.dumps(
                                    list(recommendation.constraint_coverage),
                                    separators=(",", ":"),
                                ),
                                json.dumps(
                                    snapshot,
                                    separators=(",", ":"),
                                    sort_keys=True,
                                ),
                                occurred_at,
                            ),
                        )
                    await cursor.execute(
                        "SELECT * FROM agent_sessions WHERE session_id = %s",
                        (session,),
                    )
                    updated_session = await cursor.fetchone()
                    await cursor.execute(
                        "SELECT * FROM agent_messages WHERE message_id = %s",
                        (message_id,),
                    )
                    message_row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        return _session(updated_session), _message(message_row)


def _session(row: dict[str, object]) -> DiscoverySession:
    raw_preferences = row["preference_state_json"]
    preferences = (
        json.loads(raw_preferences)
        if isinstance(raw_preferences, str)
        else raw_preferences
    )
    return DiscoverySession(
        session_id=str(row["session_id"]),
        actor_user_id=str(row["actor_user_id"]),
        status=AgentSessionStatus(str(row["status"])),
        preference_state=DiscoveryPreferenceState.model_validate(preferences),
        preference_version=int(row["preference_version"]),
        clarification_turn_count=int(row["clarification_turn_count"]),
        clarification_question_count=int(row["clarification_question_count"]),
        created_at=_utc(row["created_at"]),
        updated_at=_utc(row["updated_at"]),
        last_activity_at=_utc(row["last_activity_at"]),
        optimistic_version=int(row["optimistic_version"]),
    )


def _fixed_id(value: str) -> str:
    if not isinstance(value, str) or _ID_PATTERN.fullmatch(value) is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _json_array(
    value: Sequence[dict[str, object]],
    *,
    maximum_bytes: int,
) -> str:
    try:
        encoded = json.dumps(
            list(value),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError) as error:
        raise AgentPersistenceError(
            AgentPersistenceErrorCode.INVALID_ARGUMENT
        ) from error
    if len(encoded.encode("utf-8")) > maximum_bytes:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return encoded


def _message(row: dict[str, object]) -> AgentMessage:
    sources = row["sources_json"]
    actions = row["actions_json"]
    return AgentMessage(
        message_id=str(row["message_id"]),
        session_id=str(row["session_id"]),
        actor_user_id=str(row["actor_user_id"]),
        role=AgentMessageRole(str(row["role"])),
        body=str(row["body"]),
        resolution_type=AgentResolutionType(str(row["resolution_type"])),
        sources=tuple(json.loads(sources) if isinstance(sources, str) else sources),
        actions=tuple(json.loads(actions) if isinstance(actions, str) else actions),
        created_at=_utc(row["created_at"]),
    )


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value.astimezone(UTC).replace(tzinfo=None)


def _utc(value: object) -> datetime:
    if not isinstance(value, datetime):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
