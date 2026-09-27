from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Mapping, Sequence

import aiomysql

from msb_agent_service.agent_persistence import new_ulid


_ULID = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")
_SAFE_NAME = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")
_CAPABILITY = re.compile(r"^[a-z][a-z0-9_]{0,79}$")
_HASH = re.compile(r"^[0-9a-f]{64}$")


class ConfirmationState(StrEnum):
    PENDING = "PENDING"
    CONFIRMED = "CONFIRMED"
    CONSUMED = "CONSUMED"
    CANCELLED = "CANCELLED"
    EXPIRED = "EXPIRED"
    INVALIDATED = "INVALIDATED"


class ConfirmationDecisionCode(StrEnum):
    ALLOWED = "ALLOWED"
    NOT_FOUND = "NOT_FOUND"
    ALREADY_CONSUMED = "ALREADY_CONSUMED"
    CANCELLED = "CANCELLED"
    EXPIRED = "EXPIRED"
    INVALIDATED = "INVALIDATED"
    ACTION_MISMATCH = "ACTION_MISMATCH"
    ARGUMENT_MISMATCH = "ARGUMENT_MISMATCH"
    TARGET_MISMATCH = "TARGET_MISMATCH"
    FINANCIAL_FACT_MISMATCH = "FINANCIAL_FACT_MISMATCH"
    RESOURCE_VERSION_MISMATCH = "RESOURCE_VERSION_MISMATCH"
    POLICY_DENIED = "POLICY_DENIED"
    AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED"
    STORAGE_FAILURE = "STORAGE_FAILURE"


@dataclass(frozen=True)
class ConfirmationTarget:
    resource_type: str
    resource_id: str
    version_token: str | None = None
    snapshot_fingerprint: str | None = None

    def __post_init__(self) -> None:
        if not _SAFE_NAME.fullmatch(self.resource_type):
            raise ValueError("Invalid confirmation target resource type")
        if not self.resource_id or len(self.resource_id) > 160:
            raise ValueError("Invalid confirmation target resource ID")
        if self.version_token is not None and not 1 <= len(self.version_token) <= 160:
            raise ValueError("Invalid confirmation target version")
        if (
            self.snapshot_fingerprint is not None
            and not _HASH.fullmatch(self.snapshot_fingerprint)
        ):
            raise ValueError("Invalid confirmation target fingerprint")

    def payload(self) -> dict[str, object]:
        result: dict[str, object] = {
            "resourceType": self.resource_type,
            "resourceId": self.resource_id,
        }
        if self.version_token is not None:
            result["versionToken"] = self.version_token
        if self.snapshot_fingerprint is not None:
            result["snapshotFingerprint"] = self.snapshot_fingerprint
        return result


@dataclass(frozen=True)
class ConfirmationFinancialFact:
    name: str
    amount: Decimal
    currency: str

    def __post_init__(self) -> None:
        if not _SAFE_NAME.fullmatch(self.name):
            raise ValueError("Invalid financial fact name")
        if self.amount < 0:
            raise ValueError("Financial confirmation amounts cannot be negative")
        if not re.fullmatch(r"[A-Z]{3}", self.currency):
            raise ValueError("Invalid confirmation currency")

    def payload(self) -> dict[str, object]:
        return {
            "name": self.name,
            "amount": _decimal_text(self.amount),
            "currency": self.currency,
        }


@dataclass(frozen=True)
class PrepareConfirmation:
    actor_user_id: str
    session_id: str
    originating_invocation_id: str
    capability: str
    capability_version: str
    action_name: str
    normalized_arguments: Mapping[str, object]
    human_summary: str
    risk_level: int
    expires_at: datetime | None
    correlation_id: str
    workflow_id: str | None = None
    targets: tuple[ConfirmationTarget, ...] = ()
    financial_facts: tuple[ConfirmationFinancialFact, ...] = ()
    confirmation_id: str | None = None

    def __post_init__(self) -> None:
        for value in (
            self.actor_user_id,
            self.session_id,
            self.originating_invocation_id,
        ):
            if not _ULID.fullmatch(value):
                raise ValueError("Invalid confirmation identity")
        if self.confirmation_id is not None and not _ULID.fullmatch(
            self.confirmation_id
        ):
            raise ValueError("Invalid confirmation ID")
        if not _CAPABILITY.fullmatch(self.capability):
            raise ValueError("Invalid confirmation capability")
        if not 1 <= len(self.capability_version) <= 80:
            raise ValueError("Invalid confirmation capability version")
        if not _SAFE_NAME.fullmatch(self.action_name):
            raise ValueError("Invalid confirmation action")
        if not 1 <= len(self.human_summary.strip()) <= 500:
            raise ValueError("Invalid confirmation summary")
        if not 0 <= self.risk_level <= 5:
            raise ValueError("Invalid confirmation risk level")
        if self.risk_level >= 3 and self.expires_at is None:
            raise ValueError("Consequential confirmations require expiry")
        if self.expires_at is not None and self.expires_at.tzinfo is None:
            raise ValueError("Confirmation expiry must be timezone-aware")
        if not 1 <= len(self.correlation_id) <= 128:
            raise ValueError("Invalid confirmation correlation ID")
        if self.workflow_id is not None and not 1 <= len(self.workflow_id) <= 80:
            raise ValueError("Invalid confirmation workflow ID")
        if len(self.targets) > 10 or len(self.financial_facts) > 10:
            raise ValueError("Confirmation binding is too large")
        canonical_arguments(self.normalized_arguments)


@dataclass(frozen=True)
class ConfirmationExecution:
    confirmation_id: str
    actor_user_id: str
    session_id: str
    consuming_invocation_id: str
    capability: str
    capability_version: str
    action_name: str
    normalized_arguments: Mapping[str, object]
    targets: tuple[ConfirmationTarget, ...] = ()
    financial_facts: tuple[ConfirmationFinancialFact, ...] = ()
    current_resource_versions: Mapping[str, str] | None = None
    current_resource_snapshots: Mapping[str, str] | None = None
    policy_allowed: bool = True
    authorization_allowed: bool = True
    correlation_id: str = "confirmation-consume"

    def __post_init__(self) -> None:
        for value in (
            self.confirmation_id,
            self.actor_user_id,
            self.session_id,
            self.consuming_invocation_id,
        ):
            if not _ULID.fullmatch(value):
                raise ValueError("Invalid confirmation execution identity")


@dataclass(frozen=True)
class ConfirmationRecord:
    confirmation_id: str
    actor_user_id: str
    session_id: str
    originating_invocation_id: str
    workflow_id: str | None
    capability: str
    capability_version: str
    action_name: str
    normalized_arguments: dict[str, object]
    targets: tuple[ConfirmationTarget, ...]
    financial_facts: tuple[ConfirmationFinancialFact, ...]
    action_fingerprint: str
    human_summary: str
    risk_level: int
    state: ConfirmationState
    created_at: datetime
    expires_at: datetime | None
    action_key: str
    confirmed_at: datetime | None = None
    confirmed_by_invocation_id: str | None = None
    consumed_at: datetime | None = None
    consumed_by_invocation_id: str | None = None
    cancelled_at: datetime | None = None
    invalidated_at: datetime | None = None
    invalidated_reason: str | None = None
    result_category: str | None = None


@dataclass(frozen=True)
class ConfirmationDecision:
    allowed: bool
    code: ConfirmationDecisionCode
    confirmation: ConfirmationRecord | None = None


class ConfirmationPersistenceError(RuntimeError):
    pass


class ConsequentialConfirmationRepository:
    """Owns durable, actor-bound confirmation transitions and exact action claims."""

    def __init__(self, pool: aiomysql.Pool) -> None:
        self._pool = pool

    async def validate_schema(self) -> None:
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name IN (
                              'agent_confirmations',
                              'agent_confirmation_transitions'
                          )
                        """
                    )
                    count = int((await cursor.fetchone())[0])
            finally:
                await connection.rollback()
        if count != 2:
            raise ConfirmationPersistenceError(
                "Agent confirmation schema is not migrated"
            )

    async def prepare(
        self,
        request: PrepareConfirmation,
        *,
        pending_interaction: Mapping[str, object],
        now: datetime,
    ) -> ConfirmationRecord:
        occurred_at = _mysql_datetime(now)
        expires_at = (
            None if request.expires_at is None else _mysql_datetime(request.expires_at)
        )
        confirmation_id = request.confirmation_id or new_ulid()
        arguments, arguments_json = canonical_arguments(request.normalized_arguments)
        targets = _sorted_targets(request.targets)
        financial_facts = _sorted_financial_facts(request.financial_facts)
        fingerprint = action_fingerprint(
            capability=request.capability,
            capability_version=request.capability_version,
            action_name=request.action_name,
            normalized_arguments=arguments,
            targets=targets,
            financial_facts=financial_facts,
        )
        action_key = f"agent-action-{confirmation_id}"
        pending_payload = dict(pending_interaction)
        pending_payload["id"] = confirmation_id
        pending_payload["confirmationId"] = confirmation_id
        pending_payload["status"] = "WAITING"
        pending_payload["summary"] = request.human_summary.strip()
        if request.expires_at is not None:
            pending_payload["expiresAt"] = request.expires_at.astimezone(UTC).isoformat()

        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    state = await _lock_session(
                        cursor,
                        session_id=request.session_id,
                        actor_user_id=request.actor_user_id,
                    )
                    await _require_owned_invocation(
                        cursor,
                        invocation_id=request.originating_invocation_id,
                        session_id=request.session_id,
                        actor_user_id=request.actor_user_id,
                    )
                    await cursor.execute(
                        """
                        SELECT * FROM agent_confirmations
                        WHERE originating_invocation_id = %s
                        FOR UPDATE
                        """,
                        (request.originating_invocation_id,),
                    )
                    existing = await cursor.fetchone()
                    if existing is not None:
                        record = _record(existing)
                        if record.action_fingerprint != fingerprint:
                            raise ConfirmationPersistenceError(
                                "An invocation cannot prepare a different confirmation"
                            )
                        await connection.commit()
                        return record

                    await cursor.execute(
                        """
                        SELECT confirmation_id, state
                        FROM agent_confirmations
                        WHERE session_id = %s AND actor_user_id = %s
                          AND state IN ('PENDING', 'CONFIRMED')
                        FOR UPDATE
                        """,
                        (request.session_id, request.actor_user_id),
                    )
                    superseded = await cursor.fetchall()
                    for row in superseded:
                        await cursor.execute(
                            """
                            UPDATE agent_confirmations
                            SET state = 'INVALIDATED', invalidated_at = %s,
                                invalidated_reason = 'SUPERSEDED', updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE confirmation_id = %s
                              AND state IN ('PENDING', 'CONFIRMED')
                            """,
                            (occurred_at, occurred_at, row["confirmation_id"]),
                        )
                        await _audit_transition(
                            cursor,
                            confirmation_id=str(row["confirmation_id"]),
                            actor_user_id=request.actor_user_id,
                            session_id=request.session_id,
                            from_state=str(row["state"]),
                            to_state="INVALIDATED",
                            invocation_id=request.originating_invocation_id,
                            correlation_id=request.correlation_id,
                            reason="SUPERSEDED",
                            policy_result="NOT_APPLICABLE",
                            result_category=None,
                            now=occurred_at,
                        )

                    await cursor.execute(
                        """
                        INSERT INTO agent_confirmations (
                            confirmation_id, actor_user_id, session_id,
                            originating_invocation_id, workflow_id, capability,
                            capability_version, action_name, risk_level,
                            normalized_arguments_json, targets_json,
                            financial_facts_json, action_fingerprint,
                            human_summary, state, action_key, correlation_id,
                            created_at, expires_at, updated_at
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, 'PENDING', %s, %s, %s, %s, %s
                        )
                        """,
                        (
                            confirmation_id,
                            request.actor_user_id,
                            request.session_id,
                            request.originating_invocation_id,
                            request.workflow_id,
                            request.capability,
                            request.capability_version,
                            request.action_name,
                            request.risk_level,
                            arguments_json,
                            _canonical_json([item.payload() for item in targets]),
                            _canonical_json([item.payload() for item in financial_facts]),
                            fingerprint,
                            request.human_summary.strip(),
                            action_key,
                            request.correlation_id,
                            occurred_at,
                            expires_at,
                            occurred_at,
                        ),
                    )
                    state["pendingInteraction"] = pending_payload
                    await _write_session_state(
                        cursor,
                        state=state,
                        session_id=request.session_id,
                        actor_user_id=request.actor_user_id,
                        now=occurred_at,
                    )
                    await _audit_transition(
                        cursor,
                        confirmation_id=confirmation_id,
                        actor_user_id=request.actor_user_id,
                        session_id=request.session_id,
                        from_state=None,
                        to_state="PENDING",
                        invocation_id=request.originating_invocation_id,
                        correlation_id=request.correlation_id,
                        reason="PREPARED",
                        policy_result="ALLOWED",
                        result_category=None,
                        now=occurred_at,
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_confirmations WHERE confirmation_id = %s",
                        (confirmation_id,),
                    )
                    created = await cursor.fetchone()
                await connection.commit()
                return _record(created)
            except Exception:
                await connection.rollback()
                raise

    async def get_owned(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
    ) -> ConfirmationRecord | None:
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT * FROM agent_confirmations
                        WHERE confirmation_id = %s
                          AND actor_user_id = %s AND session_id = %s
                        """,
                        (confirmation_id, actor_user_id, session_id),
                    )
                    row = await cursor.fetchone()
            finally:
                await connection.rollback()
        return None if row is None else _record(row)

    async def confirm(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
        confirming_invocation_id: str,
        correlation_id: str,
        now: datetime,
    ) -> ConfirmationDecision:
        return await self._transition_owned(
            confirmation_id=confirmation_id,
            actor_user_id=actor_user_id,
            session_id=session_id,
            invocation_id=confirming_invocation_id,
            correlation_id=correlation_id,
            now=now,
            operation="CONFIRM",
        )

    async def cancel(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
        invocation_id: str | None,
        correlation_id: str,
        now: datetime,
    ) -> ConfirmationDecision:
        return await self._transition_owned(
            confirmation_id=confirmation_id,
            actor_user_id=actor_user_id,
            session_id=session_id,
            invocation_id=invocation_id,
            correlation_id=correlation_id,
            now=now,
            operation="CANCEL",
        )

    async def invalidate(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
        invocation_id: str | None,
        correlation_id: str,
        reason: str,
        now: datetime,
    ) -> ConfirmationDecision:
        if not _SAFE_NAME.fullmatch(reason):
            raise ValueError("Invalid confirmation invalidation reason")
        return await self._transition_owned(
            confirmation_id=confirmation_id,
            actor_user_id=actor_user_id,
            session_id=session_id,
            invocation_id=invocation_id,
            correlation_id=correlation_id,
            now=now,
            operation="INVALIDATE",
            reason=reason,
        )

    async def expire_if_due(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
        correlation_id: str,
        now: datetime,
    ) -> ConfirmationDecision:
        """Materialize expiry on an owned read without cancelling a live action."""

        return await self._transition_owned(
            confirmation_id=confirmation_id,
            actor_user_id=actor_user_id,
            session_id=session_id,
            invocation_id=None,
            correlation_id=correlation_id,
            now=now,
            operation="EXPIRE_IF_DUE",
        )

    async def consume(
        self,
        execution: ConfirmationExecution,
        *,
        now: datetime,
    ) -> ConfirmationDecision:
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    state = await _lock_session(
                        cursor,
                        session_id=execution.session_id,
                        actor_user_id=execution.actor_user_id,
                    )
                    await _require_owned_invocation(
                        cursor,
                        invocation_id=execution.consuming_invocation_id,
                        session_id=execution.session_id,
                        actor_user_id=execution.actor_user_id,
                    )
                    await cursor.execute(
                        """
                        SELECT * FROM agent_confirmations
                        WHERE confirmation_id = %s
                          AND actor_user_id = %s AND session_id = %s
                        FOR UPDATE
                        """,
                        (
                            execution.confirmation_id,
                            execution.actor_user_id,
                            execution.session_id,
                        ),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.NOT_FOUND
                        )
                    record = _record(row)
                    terminal = _terminal_decision(record)
                    if terminal is not None:
                        await connection.commit()
                        return terminal
                    if _expired(record, now):
                        updated = await _invalidate_locked(
                            cursor,
                            record=record,
                            state=state,
                            invocation_id=execution.consuming_invocation_id,
                            correlation_id=execution.correlation_id,
                            reason="EXPIRED",
                            to_state=ConfirmationState.EXPIRED,
                            now=occurred_at,
                        )
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.EXPIRED, updated
                        )
                    if not execution.policy_allowed:
                        updated = await _invalidate_locked(
                            cursor,
                            record=record,
                            state=state,
                            invocation_id=execution.consuming_invocation_id,
                            correlation_id=execution.correlation_id,
                            reason="POLICY_DENIED",
                            to_state=ConfirmationState.INVALIDATED,
                            now=occurred_at,
                        )
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.POLICY_DENIED, updated
                        )
                    if not execution.authorization_allowed:
                        updated = await _invalidate_locked(
                            cursor,
                            record=record,
                            state=state,
                            invocation_id=execution.consuming_invocation_id,
                            correlation_id=execution.correlation_id,
                            reason="AUTHORIZATION_DENIED",
                            to_state=ConfirmationState.INVALIDATED,
                            now=occurred_at,
                        )
                        await connection.commit()
                        return ConfirmationDecision(
                            False,
                            ConfirmationDecisionCode.AUTHORIZATION_DENIED,
                            updated,
                        )
                    mismatch = _execution_mismatch(record, execution)
                    if mismatch is not None:
                        updated = await _invalidate_locked(
                            cursor,
                            record=record,
                            state=state,
                            invocation_id=execution.consuming_invocation_id,
                            correlation_id=execution.correlation_id,
                            reason=mismatch.value,
                            to_state=ConfirmationState.INVALIDATED,
                            now=occurred_at,
                        )
                        await connection.commit()
                        return ConfirmationDecision(False, mismatch, updated)
                    if record.state != ConfirmationState.CONFIRMED:
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.INVALIDATED, record
                        )
                    await cursor.execute(
                        """
                        UPDATE agent_confirmations
                        SET state = 'CONSUMED', consumed_at = %s,
                            consumed_by_invocation_id = %s, updated_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE confirmation_id = %s AND state = 'CONFIRMED'
                        """,
                        (
                            occurred_at,
                            execution.consuming_invocation_id,
                            occurred_at,
                            record.confirmation_id,
                        ),
                    )
                    if cursor.rowcount != 1:
                        await connection.rollback()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.ALREADY_CONSUMED
                        )
                    await _set_session_confirmation_state(
                        cursor,
                        state=state,
                        confirmation_id=record.confirmation_id,
                        status="CONSUMED",
                        session_id=record.session_id,
                        actor_user_id=record.actor_user_id,
                        now=occurred_at,
                    )
                    await _audit_transition(
                        cursor,
                        confirmation_id=record.confirmation_id,
                        actor_user_id=record.actor_user_id,
                        session_id=record.session_id,
                        from_state="CONFIRMED",
                        to_state="CONSUMED",
                        invocation_id=execution.consuming_invocation_id,
                        correlation_id=execution.correlation_id,
                        reason="CLAIMED_FOR_EXACT_ACTION",
                        policy_result="ALLOWED",
                        result_category=None,
                        now=occurred_at,
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_confirmations WHERE confirmation_id = %s",
                        (record.confirmation_id,),
                    )
                    consumed = _record(await cursor.fetchone())
                await connection.commit()
                return ConfirmationDecision(
                    True, ConfirmationDecisionCode.ALLOWED, consumed
                )
            except Exception:
                await connection.rollback()
                raise

    async def mark_result(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        result_category: str,
        now: datetime,
    ) -> None:
        if not _SAFE_NAME.fullmatch(result_category):
            raise ValueError("Invalid confirmation result category")
        async with self._pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        UPDATE agent_confirmations
                        SET result_category = %s, updated_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE confirmation_id = %s AND actor_user_id = %s
                          AND state = 'CONSUMED'
                          AND result_category IS NULL
                        """,
                        (
                            result_category,
                            _mysql_datetime(now),
                            confirmation_id,
                            actor_user_id,
                        ),
                    )
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise

    async def _transition_owned(
        self,
        *,
        confirmation_id: str,
        actor_user_id: str,
        session_id: str,
        invocation_id: str | None,
        correlation_id: str,
        now: datetime,
        operation: str,
        reason: str | None = None,
    ) -> ConfirmationDecision:
        occurred_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    state = await _lock_session(
                        cursor, session_id=session_id, actor_user_id=actor_user_id
                    )
                    if invocation_id is not None:
                        await _require_owned_invocation(
                            cursor,
                            invocation_id=invocation_id,
                            session_id=session_id,
                            actor_user_id=actor_user_id,
                        )
                    await cursor.execute(
                        """
                        SELECT * FROM agent_confirmations
                        WHERE confirmation_id = %s
                          AND actor_user_id = %s AND session_id = %s
                        FOR UPDATE
                        """,
                        (confirmation_id, actor_user_id, session_id),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.NOT_FOUND
                        )
                    record = _record(row)
                    terminal = _terminal_decision(record)
                    if terminal is not None:
                        await connection.commit()
                        return terminal
                    if _expired(record, now):
                        updated = await _invalidate_locked(
                            cursor,
                            record=record,
                            state=state,
                            invocation_id=invocation_id,
                            correlation_id=correlation_id,
                            reason="EXPIRED",
                            to_state=ConfirmationState.EXPIRED,
                            now=occurred_at,
                        )
                        await connection.commit()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.EXPIRED, updated
                        )
                    if operation == "EXPIRE_IF_DUE":
                        await connection.commit()
                        return ConfirmationDecision(
                            True, ConfirmationDecisionCode.ALLOWED, record
                        )
                    if operation == "CONFIRM":
                        if record.state == ConfirmationState.CONFIRMED:
                            await connection.commit()
                            return ConfirmationDecision(
                                True, ConfirmationDecisionCode.ALLOWED, record
                            )
                        await cursor.execute(
                            """
                            UPDATE agent_confirmations
                            SET state = 'CONFIRMED', confirmed_at = %s,
                                confirmed_by_invocation_id = %s, updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE confirmation_id = %s AND state = 'PENDING'
                            """,
                            (occurred_at, invocation_id, occurred_at, confirmation_id),
                        )
                        status = "CONFIRMED"
                        to_state = ConfirmationState.CONFIRMED
                        code = ConfirmationDecisionCode.ALLOWED
                        allowed = True
                        transition_reason = "USER_CONFIRMED"
                    elif operation == "CANCEL":
                        await cursor.execute(
                            """
                            UPDATE agent_confirmations
                            SET state = 'CANCELLED', cancelled_at = %s,
                                updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE confirmation_id = %s
                              AND state IN ('PENDING', 'CONFIRMED')
                            """,
                            (occurred_at, occurred_at, confirmation_id),
                        )
                        status = "CANCELLED"
                        to_state = ConfirmationState.CANCELLED
                        code = ConfirmationDecisionCode.CANCELLED
                        allowed = False
                        transition_reason = "USER_CANCELLED"
                    else:
                        await cursor.execute(
                            """
                            UPDATE agent_confirmations
                            SET state = 'INVALIDATED', invalidated_at = %s,
                                invalidated_reason = %s, updated_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE confirmation_id = %s
                              AND state IN ('PENDING', 'CONFIRMED')
                            """,
                            (occurred_at, reason, occurred_at, confirmation_id),
                        )
                        status = "INVALIDATED"
                        to_state = ConfirmationState.INVALIDATED
                        code = ConfirmationDecisionCode.INVALIDATED
                        allowed = False
                        transition_reason = reason or "INVALIDATED"
                    if cursor.rowcount != 1:
                        await connection.rollback()
                        return ConfirmationDecision(
                            False, ConfirmationDecisionCode.ALREADY_CONSUMED
                        )
                    await _set_session_confirmation_state(
                        cursor,
                        state=state,
                        confirmation_id=confirmation_id,
                        status=status,
                        session_id=session_id,
                        actor_user_id=actor_user_id,
                        now=occurred_at,
                    )
                    await _audit_transition(
                        cursor,
                        confirmation_id=confirmation_id,
                        actor_user_id=actor_user_id,
                        session_id=session_id,
                        from_state=record.state.value,
                        to_state=to_state.value,
                        invocation_id=invocation_id,
                        correlation_id=correlation_id,
                        reason=transition_reason,
                        policy_result=("ALLOWED" if allowed else "NOT_APPLICABLE"),
                        result_category=None,
                        now=occurred_at,
                    )
                    await cursor.execute(
                        "SELECT * FROM agent_confirmations WHERE confirmation_id = %s",
                        (confirmation_id,),
                    )
                    updated = _record(await cursor.fetchone())
                await connection.commit()
                return ConfirmationDecision(allowed, code, updated)
            except Exception:
                await connection.rollback()
                raise


def canonical_arguments(
    arguments: Mapping[str, object],
) -> tuple[dict[str, object], str]:
    if not isinstance(arguments, Mapping) or len(arguments) > 20:
        raise ValueError("Confirmation arguments must be a bounded object")
    normalized = _normalize_value(dict(arguments))
    if not isinstance(normalized, dict):
        raise ValueError("Confirmation arguments must be an object")
    encoded = _canonical_json(normalized)
    if len(encoded.encode("utf-8")) > 16_384:
        raise ValueError("Confirmation arguments are too large")
    return normalized, encoded


def action_fingerprint(
    *,
    capability: str,
    capability_version: str,
    action_name: str,
    normalized_arguments: Mapping[str, object],
    targets: Sequence[ConfirmationTarget] = (),
    financial_facts: Sequence[ConfirmationFinancialFact] = (),
) -> str:
    arguments, _ = canonical_arguments(normalized_arguments)
    payload = {
        "contractVersion": "ai-conf-01-v1",
        "capability": capability,
        "capabilityVersion": capability_version,
        "action": action_name,
        "arguments": arguments,
        "targets": [item.payload() for item in _sorted_targets(targets)],
        "financialFacts": [
            item.payload() for item in _sorted_financial_facts(financial_facts)
        ],
    }
    return hashlib.sha256(_canonical_json(payload).encode("utf-8")).hexdigest()


def expiry_from_ttl(*, now: datetime, ttl_seconds: int) -> datetime:
    if now.tzinfo is None or not 60 <= ttl_seconds <= 3_600:
        raise ValueError("Invalid confirmation TTL")
    return now.astimezone(UTC) + timedelta(seconds=ttl_seconds)


def _execution_mismatch(
    record: ConfirmationRecord,
    execution: ConfirmationExecution,
) -> ConfirmationDecisionCode | None:
    if (
        record.capability != execution.capability
        or record.capability_version != execution.capability_version
        or record.action_name != execution.action_name
    ):
        return ConfirmationDecisionCode.ACTION_MISMATCH
    expected_arguments, _ = canonical_arguments(execution.normalized_arguments)
    if expected_arguments != record.normalized_arguments:
        return ConfirmationDecisionCode.ARGUMENT_MISMATCH
    if _sorted_targets(execution.targets) != record.targets:
        return ConfirmationDecisionCode.TARGET_MISMATCH
    if _sorted_financial_facts(execution.financial_facts) != record.financial_facts:
        return ConfirmationDecisionCode.FINANCIAL_FACT_MISMATCH
    current_versions = dict(execution.current_resource_versions or {})
    current_snapshots = dict(execution.current_resource_snapshots or {})
    for target in record.targets:
        key = f"{target.resource_type}:{target.resource_id}"
        if (
            target.version_token is not None
            and current_versions.get(key) != target.version_token
        ):
            return ConfirmationDecisionCode.RESOURCE_VERSION_MISMATCH
        if (
            target.snapshot_fingerprint is not None
            and current_snapshots.get(key) != target.snapshot_fingerprint
        ):
            return ConfirmationDecisionCode.RESOURCE_VERSION_MISMATCH
    return None


def _terminal_decision(record: ConfirmationRecord) -> ConfirmationDecision | None:
    mapping = {
        ConfirmationState.CONSUMED: ConfirmationDecisionCode.ALREADY_CONSUMED,
        ConfirmationState.CANCELLED: ConfirmationDecisionCode.CANCELLED,
        ConfirmationState.EXPIRED: ConfirmationDecisionCode.EXPIRED,
        ConfirmationState.INVALIDATED: ConfirmationDecisionCode.INVALIDATED,
    }
    code = mapping.get(record.state)
    return None if code is None else ConfirmationDecision(False, code, record)


def _expired(record: ConfirmationRecord, now: datetime) -> bool:
    return bool(record.expires_at is not None and now.astimezone(UTC) >= record.expires_at)


async def _lock_session(
    cursor: aiomysql.DictCursor,
    *,
    session_id: str,
    actor_user_id: str,
) -> dict[str, object]:
    await cursor.execute(
        """
        SELECT preference_state_json FROM agent_sessions
        WHERE session_id = %s AND actor_user_id = %s
          AND session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
        FOR UPDATE
        """,
        (session_id, actor_user_id),
    )
    row = await cursor.fetchone()
    if row is None:
        raise ConfirmationPersistenceError("Confirmation session is unavailable")
    return _json_object(row["preference_state_json"])


async def _require_owned_invocation(
    cursor: aiomysql.DictCursor,
    *,
    invocation_id: str,
    session_id: str,
    actor_user_id: str,
) -> None:
    await cursor.execute(
        """
        SELECT invocation_id FROM agent_invocations
        WHERE invocation_id = %s AND session_id = %s AND actor_user_id = %s
        """,
        (invocation_id, session_id, actor_user_id),
    )
    if await cursor.fetchone() is None:
        raise ConfirmationPersistenceError("Confirmation invocation is unavailable")


async def _write_session_state(
    cursor: aiomysql.DictCursor,
    *,
    state: dict[str, object],
    session_id: str,
    actor_user_id: str,
    now: datetime,
) -> None:
    await cursor.execute(
        """
        UPDATE agent_sessions
        SET preference_state_json = %s,
            preference_version = preference_version + 1,
            updated_at = %s, last_activity_at = %s,
            optimistic_version = optimistic_version + 1
        WHERE session_id = %s AND actor_user_id = %s
        """,
        (
            _canonical_json(state),
            now,
            now,
            session_id,
            actor_user_id,
        ),
    )


async def _set_session_confirmation_state(
    cursor: aiomysql.DictCursor,
    *,
    state: dict[str, object],
    confirmation_id: str,
    status: str,
    session_id: str,
    actor_user_id: str,
    now: datetime,
) -> None:
    pending = state.get("pendingInteraction")
    if isinstance(pending, dict) and pending.get("id") == confirmation_id:
        pending = dict(pending)
        pending["status"] = status
        state["pendingInteraction"] = pending
        await _write_session_state(
            cursor,
            state=state,
            session_id=session_id,
            actor_user_id=actor_user_id,
            now=now,
        )


async def _invalidate_locked(
    cursor: aiomysql.DictCursor,
    *,
    record: ConfirmationRecord,
    state: dict[str, object],
    invocation_id: str | None,
    correlation_id: str,
    reason: str,
    to_state: ConfirmationState,
    now: datetime,
) -> ConfirmationRecord:
    await cursor.execute(
        """
        UPDATE agent_confirmations
        SET state = %s, invalidated_at = %s, invalidated_reason = %s,
            updated_at = %s, optimistic_version = optimistic_version + 1
        WHERE confirmation_id = %s AND state IN ('PENDING', 'CONFIRMED')
        """,
        (to_state.value, now, reason, now, record.confirmation_id),
    )
    await _set_session_confirmation_state(
        cursor,
        state=state,
        confirmation_id=record.confirmation_id,
        status=to_state.value,
        session_id=record.session_id,
        actor_user_id=record.actor_user_id,
        now=now,
    )
    await _audit_transition(
        cursor,
        confirmation_id=record.confirmation_id,
        actor_user_id=record.actor_user_id,
        session_id=record.session_id,
        from_state=record.state.value,
        to_state=to_state.value,
        invocation_id=invocation_id,
        correlation_id=correlation_id,
        reason=reason,
        policy_result=("DENIED" if "DENIED" in reason else "NOT_APPLICABLE"),
        result_category=None,
        now=now,
    )
    await cursor.execute(
        "SELECT * FROM agent_confirmations WHERE confirmation_id = %s",
        (record.confirmation_id,),
    )
    return _record(await cursor.fetchone())


async def _audit_transition(
    cursor: aiomysql.DictCursor,
    *,
    confirmation_id: str,
    actor_user_id: str,
    session_id: str,
    from_state: str | None,
    to_state: str,
    invocation_id: str | None,
    correlation_id: str,
    reason: str,
    policy_result: str,
    result_category: str | None,
    now: datetime,
) -> None:
    await cursor.execute(
        """
        INSERT INTO agent_confirmation_transitions (
            transition_id, confirmation_id, actor_user_id, session_id,
            from_state, to_state, invocation_id, correlation_id,
            reason_code, policy_result, result_category, created_at
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            new_ulid(), confirmation_id, actor_user_id, session_id,
            from_state, to_state, invocation_id, correlation_id,
            reason, policy_result, result_category, now,
        ),
    )


def _record(row: Mapping[str, object]) -> ConfirmationRecord:
    arguments = _json_object(row["normalized_arguments_json"])
    targets_raw = _json_array(row["targets_json"])
    financial_raw = _json_array(row["financial_facts_json"])
    return ConfirmationRecord(
        confirmation_id=str(row["confirmation_id"]),
        actor_user_id=str(row["actor_user_id"]),
        session_id=str(row["session_id"]),
        originating_invocation_id=str(row["originating_invocation_id"]),
        workflow_id=(None if row.get("workflow_id") is None else str(row["workflow_id"])),
        capability=str(row["capability"]),
        capability_version=str(row["capability_version"]),
        action_name=str(row["action_name"]),
        normalized_arguments=arguments,
        targets=tuple(ConfirmationTarget(
            resource_type=str(item["resourceType"]),
            resource_id=str(item["resourceId"]),
            version_token=(None if item.get("versionToken") is None else str(item["versionToken"])),
            snapshot_fingerprint=(
                None if item.get("snapshotFingerprint") is None
                else str(item["snapshotFingerprint"])
            ),
        ) for item in targets_raw),
        financial_facts=tuple(ConfirmationFinancialFact(
            name=str(item["name"]),
            amount=Decimal(str(item["amount"])),
            currency=str(item["currency"]),
        ) for item in financial_raw),
        action_fingerprint=str(row["action_fingerprint"]),
        human_summary=str(row["human_summary"]),
        risk_level=int(row["risk_level"]),
        state=ConfirmationState(str(row["state"])),
        created_at=_utc(row["created_at"]),
        expires_at=_optional_utc(row.get("expires_at")),
        action_key=str(row["action_key"]),
        confirmed_at=_optional_utc(row.get("confirmed_at")),
        confirmed_by_invocation_id=(
            None if row.get("confirmed_by_invocation_id") is None
            else str(row["confirmed_by_invocation_id"])
        ),
        consumed_at=_optional_utc(row.get("consumed_at")),
        consumed_by_invocation_id=(
            None if row.get("consumed_by_invocation_id") is None
            else str(row["consumed_by_invocation_id"])
        ),
        cancelled_at=_optional_utc(row.get("cancelled_at")),
        invalidated_at=_optional_utc(row.get("invalidated_at")),
        invalidated_reason=(
            None if row.get("invalidated_reason") is None
            else str(row["invalidated_reason"])
        ),
        result_category=(
            None if row.get("result_category") is None
            else str(row["result_category"])
        ),
    )


def _sorted_targets(
    targets: Sequence[ConfirmationTarget],
) -> tuple[ConfirmationTarget, ...]:
    return tuple(sorted(targets, key=lambda item: (
        item.resource_type,
        item.resource_id,
        item.version_token or "",
        item.snapshot_fingerprint or "",
    )))


def _sorted_financial_facts(
    facts: Sequence[ConfirmationFinancialFact],
) -> tuple[ConfirmationFinancialFact, ...]:
    return tuple(sorted(facts, key=lambda item: (
        item.name, item.currency, _decimal_text(item.amount)
    )))


def _normalize_value(value: object) -> object:
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, Decimal):
        return _decimal_text(value)
    if isinstance(value, float):
        return _decimal_text(Decimal(str(value)))
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise ValueError("Confirmation datetime must be timezone-aware")
        return value.astimezone(UTC).isoformat()
    if isinstance(value, Mapping):
        return {
            str(key): _normalize_value(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple)):
        return [_normalize_value(item) for item in value]
    raise ValueError("Unsupported confirmation argument type")


def _canonical_json(value: object) -> str:
    return json.dumps(
        _normalize_value(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _decimal_text(value: Decimal) -> str:
    normalized = value.normalize()
    text = format(normalized, "f")
    return "0" if text in {"-0", ""} else text


def _json_object(value: object) -> dict[str, object]:
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, (str, bytes, bytearray)):
        parsed = json.loads(value)
        if isinstance(parsed, dict):
            return parsed
    raise ConfirmationPersistenceError("Stored confirmation object is invalid")


def _json_array(value: object) -> list[dict[str, object]]:
    if isinstance(value, list):
        parsed = value
    elif isinstance(value, (str, bytes, bytearray)):
        parsed = json.loads(value)
    else:
        raise ConfirmationPersistenceError("Stored confirmation array is invalid")
    if not isinstance(parsed, list) or not all(isinstance(item, dict) for item in parsed):
        raise ConfirmationPersistenceError("Stored confirmation array is invalid")
    return [dict(item) for item in parsed]


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("Confirmation timestamps must be timezone-aware")
    return value.astimezone(UTC).replace(tzinfo=None)


def _utc(value: object) -> datetime:
    assert isinstance(value, datetime)
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _optional_utc(value: object) -> datetime | None:
    return None if value is None else _utc(value)
