from __future__ import annotations

import hashlib
import json
import logging
import re
import secrets
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Sequence

import aiomysql

from .agent_persistence_metrics import AgentPersistenceMetrics
from .config import AgentPersistenceSettings

LOGGER = logging.getLogger(__name__)
_CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_ID_PATTERN = re.compile(r"^[0-9A-Z]{26}$")
_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_SAFE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")
_REQUIRED_TABLES = {
    "agent_sessions",
    "agent_messages",
    "agent_invocations",
    "agent_tool_calls",
}
_ALLOWED_TOOLS = {
    "getListing",
    "retrieveKnowledge",
    "SEARCH_INDIVIDUAL",
    "GET_LISTING",
}
_ALLOWED_SOURCE_TYPES = {
    "LISTING",
    "MARKETPLACE_POLICY",
    "SAFETY_GUIDANCE",
    "MARKETPLACE_FAQ",
    "CATEGORY_GUIDANCE",
}


class AgentSessionStatus(StrEnum):
    OPEN = "OPEN"
    READ_ONLY = "READ_ONLY"
    CLOSED = "CLOSED"


class AgentMessageRole(StrEnum):
    USER = "USER"
    ASSISTANT = "ASSISTANT"


class AgentResolutionType(StrEnum):
    ANSWERED = "ANSWERED"
    PARTIAL = "PARTIAL"
    UNKNOWN = "UNKNOWN"
    CONTACT_SELLER = "CONTACT_SELLER"
    REFUSED = "REFUSED"
    CLARIFY = "CLARIFY"
    RECOMMEND = "RECOMMEND"
    NO_RESULTS = "NO_RESULTS"
    HANDOFF = "HANDOFF"


class AgentInvocationStatus(StrEnum):
    PENDING = "PENDING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class AgentToolCallStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class BeginInvocationResult(StrEnum):
    CREATED = "CREATED"
    DEDUPLICATED_PENDING = "DEDUPLICATED_PENDING"
    DEDUPLICATED_SUCCEEDED = "DEDUPLICATED_SUCCEEDED"
    RETRY_STARTED = "RETRY_STARTED"
    RETRY_EXHAUSTED = "RETRY_EXHAUSTED"


class AgentPersistenceErrorCode(StrEnum):
    INVALID_ARGUMENT = "AGENT_PERSISTENCE_INVALID_ARGUMENT"
    SCHEMA_NOT_MIGRATED = "AGENT_PERSISTENCE_SCHEMA_NOT_MIGRATED"
    SESSION_NOT_FOUND = "AGENT_SESSION_NOT_FOUND"
    SESSION_NOT_OPEN = "AGENT_SESSION_NOT_OPEN"
    SESSION_VERSION_CONFLICT = "AGENT_SESSION_VERSION_CONFLICT"
    REQUEST_HASH_CONFLICT = "AGENT_REQUEST_HASH_CONFLICT"
    INVOCATION_NOT_FOUND = "AGENT_INVOCATION_NOT_FOUND"
    INVOCATION_STATE_CONFLICT = "AGENT_INVOCATION_STATE_CONFLICT"
    TOOL_CALL_CONFLICT = "AGENT_TOOL_CALL_CONFLICT"


class AgentPersistenceError(RuntimeError):
    """Carries a stable persistence error without retaining message content."""

    def __init__(self, code: AgentPersistenceErrorCode) -> None:
        super().__init__(code.value)
        self.code = code


@dataclass(frozen=True)
class AgentSession:
    session_id: str
    session_type: str
    actor_user_id: str
    subject_type: str | None
    subject_listing_id: str | None
    status: AgentSessionStatus
    created_at: datetime
    updated_at: datetime
    last_activity_at: datetime
    closed_at: datetime | None
    content_purged_at: datetime | None
    optimistic_version: int


@dataclass(frozen=True)
class AgentMessage:
    message_id: str
    session_id: str
    actor_user_id: str
    role: AgentMessageRole
    body: str
    resolution_type: AgentResolutionType | None
    sources: tuple[dict[str, object], ...]
    actions: tuple[dict[str, object], ...]
    created_at: datetime


@dataclass(frozen=True)
class AgentInvocation:
    invocation_id: str
    session_id: str
    actor_user_id: str
    user_message_id: str | None
    assistant_message_id: str | None
    client_message_id: str | None
    request_hash: str | None
    result_status: AgentInvocationStatus
    error_code: str | None
    prompt_version: str
    model_provider: str
    model_name: str
    schema_version: str
    tool_registry_version: str
    policy_version: str
    input_tokens: int
    output_tokens: int
    latency_ms: int | None
    estimated_cost: Decimal
    retry_count: int
    correlation_id: str | None
    created_at: datetime
    updated_at: datetime
    completed_at: datetime | None
    optimistic_version: int


@dataclass(frozen=True)
class BeginInvocation:
    result: BeginInvocationResult
    invocation: AgentInvocation
    user_message: AgentMessage | None


@dataclass(frozen=True)
class AgentToolCall:
    tool_call_id: str
    invocation_id: str
    sequence_number: int
    tool_name: str
    argument_hash: str
    result_hash: str | None
    source_refs: tuple[dict[str, object], ...]
    result_status: AgentToolCallStatus
    error_code: str | None
    latency_ms: int | None
    created_at: datetime
    completed_at: datetime


@dataclass(frozen=True)
class MessageCursor:
    created_at: datetime
    message_id: str


@dataclass(frozen=True)
class MessagePage:
    messages: tuple[AgentMessage, ...]
    next_cursor: MessageCursor | None


@dataclass(frozen=True)
class RetentionResult:
    sessions_purged: int
    messages_deleted: int
    discovery_recommendations_deleted: int
    deduplication_keys_redacted: int
    audit_invocations_deleted: int


class AgentPersistenceRepository:
    """Owns actor-isolated session, message, invocation, and audit persistence."""

    def __init__(
        self,
        pool: aiomysql.Pool,
        settings: AgentPersistenceSettings,
        metrics: AgentPersistenceMetrics | None = None,
    ) -> None:
        self._pool = pool
        self._settings = settings
        self._metrics = metrics or AgentPersistenceMetrics()

    @property
    def pool(self) -> aiomysql.Pool:
        """Share the Agent-owned pool with independently gated same-schema repositories."""

        return self._pool

    @classmethod
    async def create(
        cls,
        settings: AgentPersistenceSettings,
        metrics: AgentPersistenceMetrics | None = None,
    ) -> "AgentPersistenceRepository":
        """Create a bounded pool without creating or modifying schema."""

        settings.validate()
        if not settings.enabled:
            raise ValueError("AGENT_PERSISTENCE_ENABLED must be true")
        pool = await aiomysql.create_pool(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_username,
            password=settings.mysql_password,
            db=settings.mysql_database,
            minsize=settings.mysql_pool_min_size,
            maxsize=settings.mysql_pool_max_size,
            autocommit=False,
            charset="utf8mb4",
            pool_recycle=1800,
        )
        return cls(pool, settings, metrics)

    async def close(self) -> None:
        self._pool.close()
        await self._pool.wait_closed()

    async def validate_schema(self) -> None:
        """Fail when the forward-only V4 tables or V5 correlation width are absent."""

        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = %s
                          AND table_name IN (%s, %s, %s, %s)
                        """,
                        (
                            self._settings.mysql_database,
                            "agent_sessions",
                            "agent_messages",
                            "agent_invocations",
                            "agent_tool_calls",
                        ),
                    )
                    found = {row[0] for row in await cursor.fetchall()}
                    await cursor.execute(
                        """
                        SELECT character_maximum_length
                        FROM information_schema.columns
                        WHERE table_schema = %s
                          AND table_name = 'agent_invocations'
                          AND column_name = 'correlation_id'
                        """,
                        (self._settings.mysql_database,),
                    )
                    correlation_column = await cursor.fetchone()
            finally:
                await connection.rollback()
        if (
            found != _REQUIRED_TABLES
            or correlation_column is None
            or correlation_column[0] is None
            or int(correlation_column[0]) < 128
        ):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED
            )

    async def create_or_resume_session(
        self,
        *,
        actor_user_id: str,
        subject_listing_id: str,
        now: datetime,
        correlation_id: str | None = None,
    ) -> tuple[AgentSession, bool]:
        """Atomically create or resume the one open actor/listing session."""

        actor = _fixed_id("actor_user_id", actor_user_id)
        listing = _fixed_id("subject_listing_id", subject_listing_id)
        occurred_at = _mysql_datetime(now)
        started = time.perf_counter()
        for _ in range(3):
            session_id = new_ulid()
            async with self._pool.acquire() as connection:
                try:
                    await connection.begin()
                    async with connection.cursor(aiomysql.DictCursor) as cursor:
                        await cursor.execute(
                            """
                            INSERT INTO agent_sessions (
                                session_id,
                                session_type,
                                actor_user_id,
                                subject_type,
                                subject_listing_id,
                                status,
                                created_at,
                                updated_at,
                                last_activity_at
                            )
                            VALUES (
                                %s,
                                'LISTING_CUSTOMER_SERVICE',
                                %s,
                                'LISTING',
                                %s,
                                'OPEN',
                                %s,
                                %s,
                                %s
                            )
                            """,
                            (
                                session_id,
                                actor,
                                listing,
                                occurred_at,
                                occurred_at,
                                occurred_at,
                            ),
                        )
                        await cursor.execute(
                            "SELECT * FROM agent_sessions WHERE session_id = %s",
                            (session_id,),
                        )
                        row = await cursor.fetchone()
                    await connection.commit()
                    session = _session_from_row(row)
                    self._metrics.record_session("create_or_resume", "CREATED")
                    LOGGER.info(
                        "Agent session created sessionId=%s actorUserId=%s "
                        "subjectListingId=%s correlationId=%s",
                        session.session_id,
                        actor,
                        listing,
                        correlation_id,
                    )
                    self._metrics.observe_duration(
                        "create_or_resume",
                        time.perf_counter() - started,
                    )
                    return session, True
                except aiomysql.IntegrityError:
                    await connection.rollback()
                except Exception:
                    await connection.rollback()
                    self._metrics.record_session("create_or_resume", "FAILED")
                    raise

            existing = await self._resume_open_session(
                actor,
                listing,
                occurred_at,
            )
            if existing is not None:
                self._metrics.record_session("create_or_resume", "RESUMED")
                LOGGER.info(
                    "Agent session resumed sessionId=%s actorUserId=%s "
                    "subjectListingId=%s correlationId=%s",
                    existing.session_id,
                    actor,
                    listing,
                    correlation_id,
                )
                self._metrics.observe_duration(
                    "create_or_resume",
                    time.perf_counter() - started,
                )
                return existing, False
        self._metrics.record_session("create_or_resume", "CONFLICT")
        raise AgentPersistenceError(
            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
        )

    async def _resume_open_session(
        self,
        actor_user_id: str,
        subject_listing_id: str,
        now: datetime,
    ) -> AgentSession | None:
        """Lock and refresh an existing open session after a unique-key race."""

        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE actor_user_id = %s
                          AND session_type = 'LISTING_CUSTOMER_SERVICE'
                          AND subject_type = 'LISTING'
                          AND subject_listing_id = %s
                          AND status = 'OPEN'
                        FOR UPDATE
                        """,
                        (actor_user_id, subject_listing_id),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        await connection.rollback()
                        return None
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET updated_at = %s,
                            last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s
                        """,
                        (now, now, row["session_id"]),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_sessions WHERE session_id = %s",
                        (row["session_id"],),
                    )
                    updated = await cursor.fetchone()
                await connection.commit()
                return _session_from_row(updated)
            except Exception:
                await connection.rollback()
                raise

    async def get_session(
        self,
        session_id: str,
        actor_user_id: str,
    ) -> AgentSession | None:
        """Return a session only when the trusted actor owns it."""

        session = _fixed_id("session_id", session_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT *
                    FROM agent_sessions
                    WHERE session_id = %s AND actor_user_id = %s
                    """,
                    (session, actor),
                )
                row = await cursor.fetchone()
        return None if row is None else _session_from_row(row)

    async def mark_session_read_only(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        expected_version: int,
        now: datetime,
    ) -> AgentSession:
        """Transition an owned open session when its listing loses eligibility."""

        return await self._transition_session(
            session_id=session_id,
            actor_user_id=actor_user_id,
            expected_version=expected_version,
            from_statuses=(AgentSessionStatus.OPEN,),
            to_status=AgentSessionStatus.READ_ONLY,
            now=now,
        )

    async def close_session(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        expected_version: int,
        now: datetime,
    ) -> AgentSession:
        """Close an owned open or read-only session with optimistic locking."""

        return await self._transition_session(
            session_id=session_id,
            actor_user_id=actor_user_id,
            expected_version=expected_version,
            from_statuses=(
                AgentSessionStatus.OPEN,
                AgentSessionStatus.READ_ONLY,
            ),
            to_status=AgentSessionStatus.CLOSED,
            now=now,
        )

    async def _transition_session(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        expected_version: int,
        from_statuses: tuple[AgentSessionStatus, ...],
        to_status: AgentSessionStatus,
        now: datetime,
    ) -> AgentSession:
        session = _fixed_id("session_id", session_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        if expected_version < 0:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        changed_at = _mysql_datetime(now)
        placeholders = ",".join(["%s"] * len(from_statuses))
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        f"""
                        UPDATE agent_sessions
                        SET status = %s,
                            closed_at = %s,
                            updated_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND optimistic_version = %s
                          AND status IN ({placeholders})
                        """,
                        (
                            to_status.value,
                            changed_at
                            if to_status == AgentSessionStatus.CLOSED
                            else None,
                            changed_at,
                            session,
                            actor,
                            expected_version,
                            *(status.value for status in from_statuses),
                        ),
                    )
                    if cursor.rowcount != 1:
                        await cursor.execute(
                            """
                            SELECT session_id
                            FROM agent_sessions
                            WHERE session_id = %s AND actor_user_id = %s
                            """,
                            (session, actor),
                        )
                        if await cursor.fetchone() is None:
                            raise AgentPersistenceError(
                                AgentPersistenceErrorCode.SESSION_NOT_FOUND
                            )
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT
                        )
                    await cursor.execute(
                        "SELECT * FROM agent_sessions WHERE session_id = %s",
                        (session,),
                    )
                    row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                self._metrics.record_session("transition", "FAILED")
                raise
        self._metrics.record_session("transition", to_status.value)
        return _session_from_row(row)

    async def begin_invocation(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        client_message_id: str,
        body: str,
        prompt_version: str,
        model_provider: str,
        model_name: str,
        schema_version: str,
        tool_registry_version: str,
        policy_version: str,
        correlation_id: str | None,
        now: datetime,
    ) -> BeginInvocation:
        """Store one user message and one PENDING invocation before external work."""

        session = _fixed_id("session_id", session_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        client_message = _fixed_id("client_message_id", client_message_id)
        normalized_body = _message_body(
            body,
            self._settings.question_max_characters,
        )
        request_hash = hashlib.sha256(normalized_body.encode("utf-8")).hexdigest()
        versions = tuple(
            _safe_metadata(name, value, maximum)
            for name, value, maximum in (
                ("prompt_version", prompt_version, 80),
                ("model_provider", model_provider, 80),
                ("model_name", model_name, 160),
                ("schema_version", schema_version, 80),
                ("tool_registry_version", tool_registry_version, 80),
                ("policy_version", policy_version, 80),
            )
        )
        correlation = _optional_safe_text(
            "correlation_id",
            correlation_id,
            128,
        )
        created_at = _mysql_datetime(now)
        started = time.perf_counter()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
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
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_invocations
                        WHERE session_id = %s
                          AND actor_user_id = %s
                          AND client_message_id = %s
                        FOR UPDATE
                        """,
                        (session, actor, client_message),
                    )
                    existing = await cursor.fetchone()
                    if existing is not None:
                        result = await self._deduplicate_invocation(
                            cursor,
                            existing,
                            request_hash,
                            correlation,
                            created_at,
                        )
                        await connection.commit()
                        self._metrics.record_invocation(
                            "begin",
                            result.result.value,
                        )
                        if result.result != BeginInvocationResult.DEDUPLICATED_PENDING:
                            LOGGER.info(
                                "Agent invocation retry resolved "
                                "sessionId=%s actorUserId=%s invocationId=%s "
                                "result=%s correlationId=%s",
                                session,
                                actor,
                                result.invocation.invocation_id,
                                result.result.value,
                                correlation,
                            )
                        return result

                    message_id = new_ulid()
                    invocation_id = new_ulid()
                    await cursor.execute(
                        """
                        INSERT INTO agent_messages (
                            message_id,
                            session_id,
                            actor_user_id,
                            role,
                            body,
                            created_at
                        )
                        VALUES (%s, %s, %s, 'USER', %s, %s)
                        """,
                        (
                            message_id,
                            session,
                            actor,
                            normalized_body,
                            created_at,
                        ),
                    )
                    await cursor.execute(
                        """
                        INSERT INTO agent_invocations (
                            invocation_id,
                            session_id,
                            actor_user_id,
                            user_message_id,
                            client_message_id,
                            request_hash,
                            result_status,
                            prompt_version,
                            model_provider,
                            model_name,
                            schema_version,
                            tool_registry_version,
                            policy_version,
                            correlation_id,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            %s, %s, %s, %s, %s, %s, 'PENDING',
                            %s, %s, %s, %s, %s, %s, %s, %s, %s
                        )
                        """,
                        (
                            invocation_id,
                            session,
                            actor,
                            message_id,
                            client_message,
                            request_hash,
                            *versions,
                            correlation,
                            created_at,
                            created_at,
                        ),
                    )
                    await cursor.execute(
                        """
                        UPDATE agent_sessions
                        SET updated_at = %s,
                            last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (created_at, created_at, session, actor),
                    )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_invocations
                        WHERE invocation_id = %s
                        """,
                        (invocation_id,),
                    )
                    invocation_row = await cursor.fetchone()
                    await cursor.execute(
                        "SELECT * FROM agent_messages WHERE message_id = %s",
                        (message_id,),
                    )
                    message_row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                self._metrics.record_invocation("begin", "FAILED")
                raise
            finally:
                self._metrics.observe_duration(
                    "begin_invocation",
                    time.perf_counter() - started,
                )
        self._metrics.record_invocation("begin", "CREATED")
        LOGGER.info(
            "Agent invocation created sessionId=%s actorUserId=%s "
            "invocationId=%s correlationId=%s",
            session,
            actor,
            invocation_id,
            correlation,
        )
        return BeginInvocation(
            result=BeginInvocationResult.CREATED,
            invocation=_invocation_from_row(invocation_row),
            user_message=_message_from_row(message_row),
        )

    async def _deduplicate_invocation(
        self,
        cursor: aiomysql.DictCursor,
        existing: dict[str, object],
        request_hash: str,
        correlation_id: str | None,
        now: datetime,
    ) -> BeginInvocation:
        """Resolve exact retries without creating another user message."""

        if existing["request_hash"] != request_hash:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT
            )
        status = AgentInvocationStatus(str(existing["result_status"]))
        result = {
            AgentInvocationStatus.PENDING: BeginInvocationResult.DEDUPLICATED_PENDING,
            AgentInvocationStatus.SUCCEEDED: BeginInvocationResult.DEDUPLICATED_SUCCEEDED,
        }.get(status)
        if status == AgentInvocationStatus.FAILED:
            if int(existing["retry_count"]) >= self._settings.maximum_failed_retries:
                result = BeginInvocationResult.RETRY_EXHAUSTED
            else:
                await cursor.execute(
                    """
                    UPDATE agent_invocations
                    SET result_status = 'PENDING',
                        error_code = NULL,
                        input_tokens = 0,
                        output_tokens = 0,
                        latency_ms = NULL,
                        estimated_cost = 0,
                        retry_count = retry_count + 1,
                        correlation_id = %s,
                        updated_at = %s,
                        completed_at = NULL,
                        optimistic_version = optimistic_version + 1
                    WHERE invocation_id = %s
                      AND result_status = 'FAILED'
                    """,
                    (correlation_id, now, existing["invocation_id"]),
                )
                result = BeginInvocationResult.RETRY_STARTED
        if result is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
            )
        await cursor.execute(
            "SELECT * FROM agent_invocations WHERE invocation_id = %s",
            (existing["invocation_id"],),
        )
        invocation_row = await cursor.fetchone()
        message_row = None
        if invocation_row["user_message_id"] is not None:
            await cursor.execute(
                "SELECT * FROM agent_messages WHERE message_id = %s",
                (invocation_row["user_message_id"],),
            )
            message_row = await cursor.fetchone()
        return BeginInvocation(
            result=result,
            invocation=_invocation_from_row(invocation_row),
            user_message=(
                None if message_row is None else _message_from_row(message_row)
            ),
        )

    async def complete_invocation(
        self,
        *,
        invocation_id: str,
        actor_user_id: str,
        body: str,
        resolution_type: AgentResolutionType,
        sources: Sequence[dict[str, object]],
        actions: Sequence[dict[str, object]],
        input_tokens: int,
        output_tokens: int,
        latency_ms: int,
        estimated_cost: Decimal,
        now: datetime,
    ) -> tuple[AgentInvocation, AgentMessage]:
        """Persist one validated assistant message and terminal success atomically."""

        invocation = _fixed_id("invocation_id", invocation_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        answer = _message_body(body, self._settings.assistant_max_characters)
        if not isinstance(resolution_type, AgentResolutionType):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        sources_json = _json_array("sources", sources, maximum_bytes=32_000)
        actions_json = _json_array("actions", actions, maximum_bytes=16_000)
        usage = _usage(input_tokens, output_tokens, latency_ms, estimated_cost)
        completed_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    row = await self._lock_invocation_for_actor(
                        cursor,
                        invocation,
                        actor,
                    )
                    if row["result_status"] == AgentInvocationStatus.SUCCEEDED.value:
                        existing_message = await self._assistant_message(
                            cursor,
                            row["assistant_message_id"],
                        )
                        await connection.commit()
                        self._metrics.record_invocation(
                            "complete",
                            "IDEMPOTENT",
                        )
                        return _invocation_from_row(row), existing_message
                    if row["result_status"] != AgentInvocationStatus.PENDING.value:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
                        )
                    await cursor.execute(
                        """
                        SELECT status
                        FROM agent_sessions
                        WHERE session_id = %s AND actor_user_id = %s
                        FOR UPDATE
                        """,
                        (row["session_id"], actor),
                    )
                    session_row = await cursor.fetchone()
                    if (
                        session_row is None
                        or session_row["status"] != AgentSessionStatus.OPEN.value
                    ):
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.SESSION_NOT_OPEN
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
                            row["session_id"],
                            actor,
                            answer,
                            resolution_type.value,
                            sources_json,
                            actions_json,
                            completed_at,
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
                            estimated_cost = %s,
                            updated_at = %s,
                            completed_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE invocation_id = %s
                          AND actor_user_id = %s
                          AND result_status = 'PENDING'
                        """,
                        (
                            message_id,
                            *usage,
                            completed_at,
                            completed_at,
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
                        SET updated_at = %s,
                            last_activity_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE session_id = %s AND actor_user_id = %s
                        """,
                        (
                            completed_at,
                            completed_at,
                            row["session_id"],
                            actor,
                        ),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_invocations WHERE invocation_id = %s",
                        (invocation,),
                    )
                    invocation_row = await cursor.fetchone()
                    await cursor.execute(
                        "SELECT * FROM agent_messages WHERE message_id = %s",
                        (message_id,),
                    )
                    message_row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                self._metrics.record_invocation("complete", "FAILED")
                raise
        self._metrics.record_invocation("complete", "SUCCEEDED")
        LOGGER.info(
            "Agent invocation succeeded sessionId=%s actorUserId=%s "
            "invocationId=%s",
            invocation_row["session_id"],
            actor,
            invocation,
        )
        return (
            _invocation_from_row(invocation_row),
            _message_from_row(message_row),
        )

    async def fail_invocation(
        self,
        *,
        invocation_id: str,
        actor_user_id: str,
        error_code: str,
        input_tokens: int,
        output_tokens: int,
        latency_ms: int,
        estimated_cost: Decimal,
        now: datetime,
    ) -> AgentInvocation:
        """Mark one owned pending invocation failed without an assistant message."""

        invocation = _fixed_id("invocation_id", invocation_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        safe_error = _safe_error_code(error_code)
        usage = _usage(input_tokens, output_tokens, latency_ms, estimated_cost)
        completed_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    row = await self._lock_invocation_for_actor(
                        cursor,
                        invocation,
                        actor,
                    )
                    if row["result_status"] == AgentInvocationStatus.FAILED.value:
                        await connection.commit()
                        self._metrics.record_invocation("fail", "IDEMPOTENT")
                        return _invocation_from_row(row)
                    if row["result_status"] != AgentInvocationStatus.PENDING.value:
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
                        )
                    await cursor.execute(
                        """
                        UPDATE agent_invocations
                        SET result_status = 'FAILED',
                            error_code = %s,
                            input_tokens = %s,
                            output_tokens = %s,
                            latency_ms = %s,
                            estimated_cost = %s,
                            updated_at = %s,
                            completed_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE invocation_id = %s
                          AND actor_user_id = %s
                          AND result_status = 'PENDING'
                        """,
                        (
                            safe_error,
                            *usage,
                            completed_at,
                            completed_at,
                            invocation,
                            actor,
                        ),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_invocations WHERE invocation_id = %s",
                        (invocation,),
                    )
                    updated = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                self._metrics.record_invocation("fail", "FAILED")
                raise
        self._metrics.record_invocation("fail", "RECORDED")
        LOGGER.warning(
            "Agent invocation failed sessionId=%s actorUserId=%s "
            "invocationId=%s errorCode=%s",
            updated["session_id"],
            actor,
            invocation,
            safe_error,
        )
        return _invocation_from_row(updated)

    async def append_tool_call(
        self,
        *,
        invocation_id: str,
        actor_user_id: str,
        sequence_number: int,
        tool_name: str,
        argument_hash: str,
        result_hash: str | None,
        source_refs: Sequence[dict[str, object]],
        result_status: AgentToolCallStatus,
        error_code: str | None,
        latency_ms: int,
        now: datetime,
    ) -> AgentToolCall:
        """Append one final allowlisted tool audit row without raw payloads."""

        invocation = _fixed_id("invocation_id", invocation_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        if not 1 <= sequence_number <= 65_535 or tool_name not in _ALLOWED_TOOLS:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        arguments = _hash("argument_hash", argument_hash)
        result = None if result_hash is None else _hash("result_hash", result_hash)
        refs_json = _source_refs_json(source_refs)
        if not isinstance(result_status, AgentToolCallStatus):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        if result_status == AgentToolCallStatus.SUCCEEDED:
            if result is None or error_code is not None:
                raise AgentPersistenceError(
                    AgentPersistenceErrorCode.INVALID_ARGUMENT
                )
            safe_error = None
        else:
            if error_code is None:
                raise AgentPersistenceError(
                    AgentPersistenceErrorCode.INVALID_ARGUMENT
                )
            safe_error = _safe_error_code(error_code)
        if not 0 <= latency_ms <= 86_400_000:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        completed_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    invocation_row = await self._lock_invocation_for_actor(
                        cursor,
                        invocation,
                        actor,
                    )
                    if (
                        invocation_row["result_status"]
                        != AgentInvocationStatus.PENDING.value
                    ):
                        raise AgentPersistenceError(
                            AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
                        )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_tool_calls
                        WHERE invocation_id = %s AND sequence_number = %s
                        FOR UPDATE
                        """,
                        (invocation, sequence_number),
                    )
                    existing = await cursor.fetchone()
                    if existing is not None:
                        if (
                            existing["tool_name"] != tool_name
                            or existing["argument_hash"] != arguments
                            or existing["result_hash"] != result
                            or _json_tuple(existing["source_refs_json"])
                            != tuple(json.loads(refs_json))
                            or existing["result_status"] != result_status.value
                            or existing["error_code"] != safe_error
                            or int(existing["latency_ms"]) != latency_ms
                        ):
                            raise AgentPersistenceError(
                                AgentPersistenceErrorCode.TOOL_CALL_CONFLICT
                            )
                        await connection.commit()
                        self._metrics.record_tool_call("append", "IDEMPOTENT")
                        return _tool_call_from_row(existing)
                    tool_call_id = new_ulid()
                    await cursor.execute(
                        """
                        INSERT INTO agent_tool_calls (
                            tool_call_id,
                            invocation_id,
                            sequence_number,
                            tool_name,
                            argument_hash,
                            result_hash,
                            source_refs_json,
                            result_status,
                            error_code,
                            latency_ms,
                            created_at,
                            completed_at
                        )
                        VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                        )
                        """,
                        (
                            tool_call_id,
                            invocation,
                            sequence_number,
                            tool_name,
                            arguments,
                            result,
                            refs_json,
                            result_status.value,
                            safe_error,
                            latency_ms,
                            completed_at,
                            completed_at,
                        ),
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_tool_calls WHERE tool_call_id = %s",
                        (tool_call_id,),
                    )
                    row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                self._metrics.record_tool_call("append", "FAILED")
                raise
        self._metrics.record_tool_call("append", result_status.value)
        return _tool_call_from_row(row)

    async def list_messages(
        self,
        *,
        session_id: str,
        actor_user_id: str,
        limit: int,
        after: MessageCursor | None = None,
    ) -> MessagePage:
        """Read one actor-owned message page using stable keyset ordering."""

        session = _fixed_id("session_id", session_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        if not 1 <= limit <= 100:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        parameters: list[object] = [session, actor]
        cursor_clause = ""
        if after is not None:
            cursor_time = _mysql_datetime(after.created_at)
            cursor_id = _fixed_id("cursor.message_id", after.message_id)
            cursor_clause = """
                AND (
                    created_at > %s
                    OR (created_at = %s AND message_id > %s)
                )
            """
            parameters.extend((cursor_time, cursor_time, cursor_id))
        parameters.append(limit + 1)
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT session_id
                    FROM agent_sessions
                    WHERE session_id = %s AND actor_user_id = %s
                    """,
                    (session, actor),
                )
                if await cursor.fetchone() is None:
                    raise AgentPersistenceError(
                        AgentPersistenceErrorCode.SESSION_NOT_FOUND
                    )
                await cursor.execute(
                    f"""
                    SELECT *
                    FROM agent_messages
                    WHERE session_id = %s
                      AND actor_user_id = %s
                      {cursor_clause}
                    ORDER BY created_at, message_id
                    LIMIT %s
                    """,
                    tuple(parameters),
                )
                rows = list(await cursor.fetchall())
        has_more = len(rows) > limit
        visible = rows[:limit]
        next_cursor = None
        if has_more and visible:
            last = visible[-1]
            next_cursor = MessageCursor(
                created_at=_utc_datetime(last["created_at"]),
                message_id=str(last["message_id"]),
            )
        return MessagePage(
            messages=tuple(_message_from_row(row) for row in visible),
            next_cursor=next_cursor,
        )

    async def get_invocation_messages(
        self,
        *,
        invocation_id: str,
        actor_user_id: str,
    ) -> tuple[AgentMessage, AgentMessage | None]:
        """Return the owned user/assistant pair needed for an idempotent API retry."""

        invocation = _fixed_id("invocation_id", invocation_id)
        actor = _fixed_id("actor_user_id", actor_user_id)
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT user_message_id, assistant_message_id
                    FROM agent_invocations
                    WHERE invocation_id = %s AND actor_user_id = %s
                    """,
                    (invocation, actor),
                )
                row = await cursor.fetchone()
                if row is None or row["user_message_id"] is None:
                    raise AgentPersistenceError(
                        AgentPersistenceErrorCode.INVOCATION_NOT_FOUND
                    )
                await cursor.execute(
                    """
                    SELECT *
                    FROM agent_messages
                    WHERE actor_user_id = %s
                      AND message_id IN (%s, %s)
                    """,
                    (
                        actor,
                        row["user_message_id"],
                        row["assistant_message_id"] or row["user_message_id"],
                    ),
                )
                messages = {
                    message["message_id"]: _message_from_row(message)
                    for message in await cursor.fetchall()
                }
        user_message = messages.get(row["user_message_id"])
        if user_message is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVOCATION_NOT_FOUND
            )
        assistant_message = (
            None
            if row["assistant_message_id"] is None
            else messages.get(row["assistant_message_id"])
        )
        return user_message, assistant_message

    async def apply_retention(
        self,
        *,
        now: datetime,
        session_batch_size: int = 100,
        audit_batch_size: int = 1_000,
    ) -> RetentionResult:
        """Close stale sessions, purge content, and later remove audit metadata."""

        if not 1 <= session_batch_size <= 1_000:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        if not 1 <= audit_batch_size <= 10_000:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        current = _aware_datetime(now)
        message_cutoff = _mysql_datetime(
            current - timedelta(days=self._settings.message_retention_days)
        )
        audit_cutoff = _mysql_datetime(
            current - timedelta(days=self._settings.audit_retention_days)
        )
        purged_sessions = 0
        deleted_messages = 0
        deleted_discovery_recommendations = 0
        redacted_keys = 0
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT session_id
                        FROM agent_sessions
                        WHERE content_purged_at IS NULL
                          AND last_activity_at < %s
                        ORDER BY last_activity_at, session_id
                        LIMIT %s
                        FOR UPDATE SKIP LOCKED
                        """,
                        (message_cutoff, session_batch_size),
                    )
                    session_ids = [
                        str(row["session_id"]) for row in await cursor.fetchall()
                    ]
                    for session_id in session_ids:
                        await cursor.execute(
                            """
                            UPDATE agent_invocations
                            SET user_message_id = NULL,
                                assistant_message_id = NULL,
                                client_message_id = NULL,
                                request_hash = NULL,
                                result_status = CASE
                                    WHEN result_status = 'PENDING'
                                    THEN 'FAILED'
                                    ELSE result_status
                                END,
                                error_code = CASE
                                    WHEN completed_at IS NULL
                                    THEN 'RETENTION_EXPIRED'
                                    ELSE error_code
                                END,
                                completed_at = COALESCE(completed_at, %s),
                                updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE session_id = %s
                              AND (
                                  user_message_id IS NOT NULL
                                  OR assistant_message_id IS NOT NULL
                                  OR client_message_id IS NOT NULL
                                  OR request_hash IS NOT NULL
                              )
                            """,
                            (
                                _mysql_datetime(current),
                                _mysql_datetime(current),
                                session_id,
                            ),
                        )
                        redacted_keys += cursor.rowcount
                        await cursor.execute(
                            """
                            DELETE FROM agent_discovery_recommendations
                            WHERE session_id = %s
                            """,
                            (session_id,),
                        )
                        deleted_discovery_recommendations += cursor.rowcount
                        await cursor.execute(
                            """
                            DELETE FROM agent_messages
                            WHERE session_id = %s
                            """,
                            (session_id,),
                        )
                        deleted_messages += cursor.rowcount
                        await cursor.execute(
                            """
                            UPDATE agent_sessions
                            SET status = 'CLOSED',
                                closed_at = COALESCE(closed_at, %s),
                                content_purged_at = %s,
                                updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE session_id = %s
                              AND content_purged_at IS NULL
                            """,
                            (
                                _mysql_datetime(current),
                                _mysql_datetime(current),
                                _mysql_datetime(current),
                                session_id,
                            ),
                        )
                        purged_sessions += cursor.rowcount
                    await cursor.execute(
                        """
                        DELETE FROM agent_invocations
                        WHERE created_at < %s
                        ORDER BY created_at, invocation_id
                        LIMIT %s
                        """,
                        (audit_cutoff, audit_batch_size),
                    )
                    deleted_audit = cursor.rowcount
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        self._metrics.record_retention("sessions_purged", purged_sessions)
        self._metrics.record_retention("messages_deleted", deleted_messages)
        self._metrics.record_retention(
            "discovery_recommendations_deleted",
            deleted_discovery_recommendations,
        )
        self._metrics.record_retention("deduplication_keys_redacted", redacted_keys)
        self._metrics.record_retention(
            "audit_invocations_deleted",
            deleted_audit,
        )
        if purged_sessions or deleted_audit:
            LOGGER.info(
                "Agent retention applied sessionsPurged=%s messagesDeleted=%s "
                "discoveryRecommendationsDeleted=%s "
                "deduplicationKeysRedacted=%s auditInvocationsDeleted=%s",
                purged_sessions,
                deleted_messages,
                deleted_discovery_recommendations,
                redacted_keys,
                deleted_audit,
            )
        return RetentionResult(
            sessions_purged=purged_sessions,
            messages_deleted=deleted_messages,
            discovery_recommendations_deleted=deleted_discovery_recommendations,
            deduplication_keys_redacted=redacted_keys,
            audit_invocations_deleted=deleted_audit,
        )

    async def _lock_invocation_for_actor(
        self,
        cursor: aiomysql.DictCursor,
        invocation_id: str,
        actor_user_id: str,
    ) -> dict[str, object]:
        """Hide cross-actor invocations behind the same not-found result."""

        await cursor.execute(
            """
            SELECT *
            FROM agent_invocations
            WHERE invocation_id = %s AND actor_user_id = %s
            FOR UPDATE
            """,
            (invocation_id, actor_user_id),
        )
        row = await cursor.fetchone()
        if row is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVOCATION_NOT_FOUND
            )
        return row

    async def _assistant_message(
        self,
        cursor: aiomysql.DictCursor,
        message_id: object,
    ) -> AgentMessage:
        if message_id is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
            )
        await cursor.execute(
            "SELECT * FROM agent_messages WHERE message_id = %s",
            (message_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVOCATION_STATE_CONFLICT
            )
        return _message_from_row(row)


def normalize_question_body(body: str, maximum_characters: int = 8_000) -> str:
    """Normalize a user question before hashing and durable deduplication."""

    return _message_body(body, maximum_characters)


def request_hash(body: str, maximum_characters: int = 8_000) -> str:
    """Return the canonical retry hash without logging or duplicating the body."""

    normalized = normalize_question_body(body, maximum_characters)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def new_ulid() -> str:
    """Generate a sortable fixed-width ID without exposing database sequences."""

    timestamp_ms = int(time.time() * 1000)
    value = (timestamp_ms << 80) | secrets.randbits(80)
    encoded = []
    for _ in range(26):
        encoded.append(_CROCKFORD_BASE32[value & 31])
        value >>= 5
    return "".join(reversed(encoded))


def _session_from_row(row: dict[str, object]) -> AgentSession:
    return AgentSession(
        session_id=str(row["session_id"]),
        session_type=str(row["session_type"]),
        actor_user_id=str(row["actor_user_id"]),
        subject_type=(
            None if row["subject_type"] is None else str(row["subject_type"])
        ),
        subject_listing_id=(
            None
            if row["subject_listing_id"] is None
            else str(row["subject_listing_id"])
        ),
        status=AgentSessionStatus(str(row["status"])),
        created_at=_utc_datetime(row["created_at"]),
        updated_at=_utc_datetime(row["updated_at"]),
        last_activity_at=_utc_datetime(row["last_activity_at"]),
        closed_at=_optional_utc_datetime(row["closed_at"]),
        content_purged_at=_optional_utc_datetime(row["content_purged_at"]),
        optimistic_version=int(row["optimistic_version"]),
    )


def _message_from_row(row: dict[str, object]) -> AgentMessage:
    role = AgentMessageRole(str(row["role"]))
    return AgentMessage(
        message_id=str(row["message_id"]),
        session_id=str(row["session_id"]),
        actor_user_id=str(row["actor_user_id"]),
        role=role,
        body=str(row["body"]),
        resolution_type=(
            None
            if row["resolution_type"] is None
            else AgentResolutionType(str(row["resolution_type"]))
        ),
        sources=_json_tuple(row["sources_json"]),
        actions=_json_tuple(row["actions_json"]),
        created_at=_utc_datetime(row["created_at"]),
    )


def _invocation_from_row(row: dict[str, object]) -> AgentInvocation:
    return AgentInvocation(
        invocation_id=str(row["invocation_id"]),
        session_id=str(row["session_id"]),
        actor_user_id=str(row["actor_user_id"]),
        user_message_id=(
            None
            if row["user_message_id"] is None
            else str(row["user_message_id"])
        ),
        assistant_message_id=(
            None
            if row["assistant_message_id"] is None
            else str(row["assistant_message_id"])
        ),
        client_message_id=(
            None
            if row["client_message_id"] is None
            else str(row["client_message_id"])
        ),
        request_hash=(
            None if row["request_hash"] is None else str(row["request_hash"])
        ),
        result_status=AgentInvocationStatus(str(row["result_status"])),
        error_code=(
            None if row["error_code"] is None else str(row["error_code"])
        ),
        prompt_version=str(row["prompt_version"]),
        model_provider=str(row["model_provider"]),
        model_name=str(row["model_name"]),
        schema_version=str(row["schema_version"]),
        tool_registry_version=str(row["tool_registry_version"]),
        policy_version=str(row["policy_version"]),
        input_tokens=int(row["input_tokens"]),
        output_tokens=int(row["output_tokens"]),
        latency_ms=(
            None if row["latency_ms"] is None else int(row["latency_ms"])
        ),
        estimated_cost=Decimal(str(row["estimated_cost"])),
        retry_count=int(row["retry_count"]),
        correlation_id=(
            None
            if row["correlation_id"] is None
            else str(row["correlation_id"])
        ),
        created_at=_utc_datetime(row["created_at"]),
        updated_at=_utc_datetime(row["updated_at"]),
        completed_at=_optional_utc_datetime(row["completed_at"]),
        optimistic_version=int(row["optimistic_version"]),
    )


def _tool_call_from_row(row: dict[str, object]) -> AgentToolCall:
    return AgentToolCall(
        tool_call_id=str(row["tool_call_id"]),
        invocation_id=str(row["invocation_id"]),
        sequence_number=int(row["sequence_number"]),
        tool_name=str(row["tool_name"]),
        argument_hash=str(row["argument_hash"]),
        result_hash=(
            None if row["result_hash"] is None else str(row["result_hash"])
        ),
        source_refs=_json_tuple(row["source_refs_json"]),
        result_status=AgentToolCallStatus(str(row["result_status"])),
        error_code=(
            None if row["error_code"] is None else str(row["error_code"])
        ),
        latency_ms=(
            None if row["latency_ms"] is None else int(row["latency_ms"])
        ),
        created_at=_utc_datetime(row["created_at"]),
        completed_at=_utc_datetime(row["completed_at"]),
    )


def _fixed_id(name: str, value: str) -> str:
    if not isinstance(value, str) or _ID_PATTERN.fullmatch(value) is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _message_body(value: str, maximum_characters: int) -> str:
    if not isinstance(value, str):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    normalized = value.strip()
    if (
        not normalized
        or len(normalized) > maximum_characters
        or any(
            ord(character) < 32 and character not in "\n\r\t"
            for character in normalized
        )
    ):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return normalized


def _safe_metadata(name: str, value: str, maximum_length: int) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum_length
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _optional_safe_text(
    name: str,
    value: str | None,
    maximum_length: int,
) -> str | None:
    if value is None:
        return None
    return _safe_metadata(name, value, maximum_length)


def _safe_error_code(value: str) -> str:
    if not isinstance(value, str) or _SAFE_CODE_PATTERN.fullmatch(value) is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _hash(name: str, value: str) -> str:
    if not isinstance(value, str) or _HASH_PATTERN.fullmatch(value) is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value


def _json_array(
    name: str,
    value: Sequence[dict[str, object]],
    *,
    maximum_bytes: int,
) -> str:
    if isinstance(value, (str, bytes, bytearray)):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    try:
        normalized = list(value)
        if any(not isinstance(item, dict) for item in normalized):
            raise TypeError
        encoded = json.dumps(
            normalized,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError) as exc:
        raise AgentPersistenceError(
            AgentPersistenceErrorCode.INVALID_ARGUMENT
        ) from exc
    if len(encoded.encode("utf-8")) > maximum_bytes:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return encoded


def _json_tuple(value: object) -> tuple[dict[str, object], ...]:
    if value is None:
        return ()
    parsed = json.loads(value) if isinstance(value, str) else value
    if not isinstance(parsed, list) or any(
        not isinstance(item, dict) for item in parsed
    ):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return tuple(parsed)


def _source_refs_json(value: Sequence[dict[str, object]]) -> str:
    """Persist only bounded source identity/version metadata for tool audits."""

    if isinstance(value, (str, bytes, bytearray)) or len(value) > 20:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    normalized: list[dict[str, str]] = []
    for item in value:
        if not isinstance(item, dict) or set(item) != {
            "sourceType",
            "sourceId",
            "sourceVersion",
        }:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        source_type = item["sourceType"]
        source_id = item["sourceId"]
        source_version = item["sourceVersion"]
        if source_type not in _ALLOWED_SOURCE_TYPES:
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        if (
            not isinstance(source_id, str)
            or not 1 <= len(source_id) <= 160
            or source_id != source_id.strip()
            or any(
                character.isspace()
                or ord(character) < 32
                or ord(character) == 127
                for character in source_id
            )
        ):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        if (
            not isinstance(source_version, str)
            or re.fullmatch(r"[1-9][0-9]{0,19}", source_version) is None
        ):
            raise AgentPersistenceError(
                AgentPersistenceErrorCode.INVALID_ARGUMENT
            )
        normalized.append(
            {
                "sourceType": source_type,
                "sourceId": source_id,
                "sourceVersion": source_version,
            }
        )
    return _json_array("source_refs", normalized, maximum_bytes=32_000)


def _usage(
    input_tokens: int,
    output_tokens: int,
    latency_ms: int,
    estimated_cost: Decimal,
) -> tuple[int, int, int, Decimal]:
    if (
        isinstance(input_tokens, bool)
        or not isinstance(input_tokens, int)
        or not 0 <= input_tokens <= 1_000_000
        or isinstance(output_tokens, bool)
        or not isinstance(output_tokens, int)
        or not 0 <= output_tokens <= 1_000_000
        or isinstance(latency_ms, bool)
        or not isinstance(latency_ms, int)
        or not 0 <= latency_ms <= 86_400_000
    ):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    cost = Decimal(estimated_cost)
    if not cost.is_finite() or cost < 0 or cost > Decimal("1000000"):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return input_tokens, output_tokens, latency_ms, cost


def _aware_datetime(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    return value.astimezone(UTC)


def _mysql_datetime(value: datetime) -> datetime:
    return _aware_datetime(value).replace(tzinfo=None)


def _utc_datetime(value: object) -> datetime:
    if not isinstance(value, datetime):
        raise AgentPersistenceError(AgentPersistenceErrorCode.INVALID_ARGUMENT)
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def _optional_utc_datetime(value: object) -> datetime | None:
    return None if value is None else _utc_datetime(value)
