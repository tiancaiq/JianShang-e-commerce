from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Protocol

import aiomysql

from .config import KnowledgeIngestionSettings
from .discovery_embedding_contract import (
    DiscoveryEmbeddingContractError,
    DiscoveryEmbeddingContractErrorCode,
    DiscoveryEmbeddingEvent,
)
from .knowledge_jobs import new_ulid

_REQUIRED_TABLE = "discovery_embedding_jobs"
_RECOVERY_TABLE = "discovery_embedding_recovery_commands"
_SAFE_ERROR_CODE_MAX_LENGTH = 64
_RECOVERY_KEY_MIN_LENGTH = 16
_RECOVERY_KEY_MAX_LENGTH = 128
_RECOVERY_EXPECTED_COUNT_MAX = 10_000
_IDENTITY_PREDICATE = """
  AND document_schema_version =
      'MARKETPLACE_LISTING_DISCOVERY_V2'
  AND embedding_input_schema_version =
      'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1'
  AND normalizer_version = 'NFKC_WHITESPACE_V1'
  AND redactor_version = 'PUBLIC_CONTACT_REDACTION_V1'
  AND language = 'und'
  AND embedding_provider = 'openai'
  AND embedding_model = 'text-embedding-3-small'
  AND embedding_dimensions = 1536
"""


class DiscoveryEmbeddingEnqueueResult(StrEnum):
    ACCEPTED = "ACCEPTED"
    DUPLICATE = "DUPLICATE"
    DISABLED = "DISABLED"


class DiscoveryEmbeddingJobStatus(StrEnum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    RETRY_WAIT = "RETRY_WAIT"
    SUCCEEDED = "SUCCEEDED"
    DEAD_LETTER = "DEAD_LETTER"


class DiscoveryEmbeddingRecoveryOutcome(StrEnum):
    RECOVERED = "RECOVERED"
    REPLAY = "REPLAY"
    COUNT_MISMATCH = "COUNT_MISMATCH"
    DISABLED = "DISABLED"


class DiscoveryEmbeddingRecoveryErrorCode(StrEnum):
    INVALID_RECOVERY_KEY = "INVALID_RECOVERY_KEY"
    INVALID_EXPECTED_COUNT = "INVALID_EXPECTED_COUNT"
    RECOVERY_KEY_CONFLICT = "RECOVERY_KEY_CONFLICT"
    RECOVERY_IN_PROGRESS = "RECOVERY_IN_PROGRESS"


class DiscoveryEmbeddingRecoveryMode(StrEnum):
    MAX_ATTEMPTS_EXHAUSTED = "MAX_ATTEMPTS_EXHAUSTED"
    PRODUCT_VERSION_ZERO_CALLBACK_CONTRACT_REPAIRED_V1 = (
        "PRODUCT_VERSION_ZERO_CALLBACK_CONTRACT_REPAIRED_V1"
    )


class DiscoveryEmbeddingRecoveryError(ValueError):
    def __init__(self, code: DiscoveryEmbeddingRecoveryErrorCode) -> None:
        super().__init__(code.value)
        self.code = code


@dataclass(frozen=True)
class DiscoveryEmbeddingJob:
    job_id: str
    event_id: str
    request_id: str
    listing_id: str
    listing_version: int
    document_schema_version: str
    document_hash: str
    embedding_input_schema_version: str
    embedding_input_hash: str
    normalizer_version: str
    redactor_version: str
    language: str
    embedding_provider: str
    embedding_model: str
    embedding_dimensions: int
    event_occurred_at: datetime
    correlation_id: str
    event_payload_hash: str
    status: DiscoveryEmbeddingJobStatus
    attempt_count: int


@dataclass(frozen=True)
class DiscoveryEmbeddingRecoveryResult:
    outcome: DiscoveryEmbeddingRecoveryOutcome
    expected_count: int
    recovered_count: int


class DiscoveryEmbeddingJobStore(Protocol):
    async def enqueue(
        self,
        event: DiscoveryEmbeddingEvent,
        accepted_at: datetime,
    ) -> DiscoveryEmbeddingEnqueueResult: ...

    async def claim(
        self,
        *,
        claim_owner: str,
        now: datetime,
        limit: int,
        claim_seconds: int,
        max_attempts: int,
    ) -> list[DiscoveryEmbeddingJob]: ...

    async def mark_succeeded(
        self,
        *,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
    ) -> bool: ...

    async def mark_terminal(
        self,
        *,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
        error_code: str,
    ) -> bool: ...

    async def schedule_retry(
        self,
        *,
        job_id: str,
        claim_owner: str,
        next_attempt_at: datetime,
        error_code: str,
    ) -> bool: ...


class DiscoveryEmbeddingJobRepository:
    """Owns a queue that is physically and logically separate from Agent RAG."""

    def __init__(self, pool: aiomysql.Pool, database_name: str) -> None:
        self._pool = pool
        self._database_name = database_name

    @classmethod
    async def create(
        cls,
        settings: KnowledgeIngestionSettings,
    ) -> "DiscoveryEmbeddingJobRepository":
        """Reuse Agent MySQL connection settings without sharing RAG tables."""

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
        return cls(pool, settings.mysql_database)

    async def close(self) -> None:
        self._pool.close()
        await self._pool.wait_closed()

    async def validate_schema(self) -> None:
        """Fail enabled startup until the forward-only Agent V9 migration exists."""

        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                      AND table_name = %s
                    """,
                    (self._database_name, _REQUIRED_TABLE),
                )
                row = await cursor.fetchone()
        if row is None or int(row[0]) != 1:
            raise RuntimeError("DISCOVERY_EMBEDDING_SCHEMA_NOT_MIGRATED")

    async def validate_recovery_schema(self) -> None:
        """Fail recovery commands until the forward-only Agent V10 migration exists."""

        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = %s
                      AND table_name IN (%s, %s)
                    """,
                    (self._database_name, _REQUIRED_TABLE, _RECOVERY_TABLE),
                )
                row = await cursor.fetchone()
        if row is None or int(row[0]) != 2:
            raise RuntimeError("DISCOVERY_EMBEDDING_RECOVERY_SCHEMA_NOT_MIGRATED")

    async def enqueue(
        self,
        event: DiscoveryEmbeddingEvent,
        accepted_at: datetime,
    ) -> DiscoveryEmbeddingEnqueueResult:
        """Atomically deduplicate both Product event and embedding request IDs."""

        accepted = _mysql_datetime(accepted_at)
        occurred = _mysql_datetime(event.occurred_at)
        payload = event.payload
        payload_hash = event.payload_hash()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        INSERT IGNORE INTO discovery_embedding_jobs (
                            job_id, event_id, request_id, listing_id, listing_version,
                            document_schema_version, document_hash,
                            embedding_input_schema_version, embedding_input_hash,
                            normalizer_version, redactor_version, language,
                            embedding_provider, embedding_model, embedding_dimensions,
                            event_occurred_at, correlation_id, event_payload_hash,
                            status, attempt_count, next_attempt_at,
                            claim_owner, claim_expires_at, last_error_code,
                            created_at, updated_at, completed_at
                        )
                        VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, %s, %s, %s,
                            'PENDING', 0, %s, NULL, NULL, NULL, %s, %s, NULL
                        )
                        """,
                        (
                            new_ulid(),
                            event.event_id,
                            payload.request_id,
                            payload.listing_id,
                            payload.listing_version,
                            payload.document_schema_version,
                            payload.document_hash,
                            payload.embedding_input_schema_version,
                            payload.embedding_input_hash,
                            payload.normalizer_version,
                            payload.redactor_version,
                            payload.language,
                            payload.embedding_identity.provider,
                            payload.embedding_identity.model,
                            payload.embedding_identity.dimensions,
                            occurred,
                            event.correlation_id,
                            payload_hash,
                            accepted,
                            accepted,
                            accepted,
                        ),
                    )
                    if cursor.rowcount == 1:
                        await connection.commit()
                        return DiscoveryEmbeddingEnqueueResult.ACCEPTED
                    await cursor.execute(
                        """
                        SELECT event_id, request_id, event_payload_hash
                        FROM discovery_embedding_jobs
                        WHERE event_id = %s OR request_id = %s
                        FOR UPDATE
                        """,
                        (event.event_id, payload.request_id),
                    )
                    rows = list(await cursor.fetchall())
                    if len(rows) != 1:
                        raise DiscoveryEmbeddingContractError(
                            DiscoveryEmbeddingContractErrorCode.REQUEST_ID_CONFLICT
                        )
                    row = rows[0]
                    if (
                        row["event_id"] == event.event_id
                        and row["request_id"] == payload.request_id
                        and row["event_payload_hash"] == payload_hash
                    ):
                        await connection.commit()
                        return DiscoveryEmbeddingEnqueueResult.DUPLICATE
                    code = (
                        DiscoveryEmbeddingContractErrorCode.EVENT_ID_CONFLICT
                        if row["event_id"] == event.event_id
                        else DiscoveryEmbeddingContractErrorCode.REQUEST_ID_CONFLICT
                    )
                    raise DiscoveryEmbeddingContractError(code)
            except BaseException:
                await connection.rollback()
                raise

    async def claim(
        self,
        *,
        claim_owner: str,
        now: datetime,
        limit: int,
        claim_seconds: int,
        max_attempts: int,
    ) -> list[DiscoveryEmbeddingJob]:
        """Claim due or lease-expired work with bounded multi-worker concurrency."""

        if not 1 <= limit <= 100:
            raise ValueError("claim limit must be between 1 and 100")
        claimed_at = _mysql_datetime(now)
        claim_expires = _mysql_datetime(now + timedelta(seconds=claim_seconds))
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        UPDATE discovery_embedding_jobs
                        SET status = 'DEAD_LETTER',
                            claim_owner = NULL,
                            claim_expires_at = NULL,
                            last_error_code = 'MAX_ATTEMPTS_EXHAUSTED',
                            completed_at = %s,
                            updated_at = %s
                        WHERE attempt_count >= %s
                          AND (
                            (
                                status IN ('PENDING', 'RETRY_WAIT')
                                AND next_attempt_at <= %s
                            )
                            OR (
                                status = 'PROCESSING'
                                AND claim_expires_at <= %s
                            )
                          )
                        """,
                        (
                            claimed_at,
                            claimed_at,
                            max_attempts,
                            claimed_at,
                            claimed_at,
                        ),
                    )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM discovery_embedding_jobs
                        WHERE attempt_count < %s
                          AND (
                            (
                                status IN ('PENDING', 'RETRY_WAIT')
                                AND next_attempt_at <= %s
                            )
                            OR (
                                status = 'PROCESSING'
                                AND claim_expires_at <= %s
                            )
                          )
                        ORDER BY created_at, job_id
                        LIMIT %s
                        FOR UPDATE SKIP LOCKED
                        """,
                        (max_attempts, claimed_at, claimed_at, limit),
                    )
                    rows = list(await cursor.fetchall())
                    if not rows:
                        await connection.commit()
                        return []
                    job_ids = [str(row["job_id"]) for row in rows]
                    placeholders = ",".join(["%s"] * len(job_ids))
                    await cursor.execute(
                        f"""
                        UPDATE discovery_embedding_jobs
                        SET status = 'PROCESSING',
                            attempt_count = attempt_count + 1,
                            claim_owner = %s,
                            claim_expires_at = %s,
                            last_error_code = NULL,
                            updated_at = %s
                        WHERE job_id IN ({placeholders})
                        """,
                        (claim_owner, claim_expires, claimed_at, *job_ids),
                    )
                await connection.commit()
            except BaseException:
                await connection.rollback()
                raise
        return [
            _job_from_row(row, attempt_count=int(row["attempt_count"]) + 1)
            for row in rows
        ]

    async def mark_succeeded(
        self,
        *,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
    ) -> bool:
        return await self._finish(
            job_id=job_id,
            claim_owner=claim_owner,
            completed_at=completed_at,
            status=DiscoveryEmbeddingJobStatus.SUCCEEDED,
            error_code=None,
        )

    async def mark_terminal(
        self,
        *,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
        error_code: str,
    ) -> bool:
        return await self._finish(
            job_id=job_id,
            claim_owner=claim_owner,
            completed_at=completed_at,
            status=DiscoveryEmbeddingJobStatus.DEAD_LETTER,
            error_code=_safe_error_code(error_code),
        )

    async def schedule_retry(
        self,
        *,
        job_id: str,
        claim_owner: str,
        next_attempt_at: datetime,
        error_code: str,
    ) -> bool:
        next_attempt = _mysql_datetime(next_attempt_at)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE discovery_embedding_jobs
                    SET status = 'RETRY_WAIT',
                        next_attempt_at = %s,
                        claim_owner = NULL,
                        claim_expires_at = NULL,
                        last_error_code = %s,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE job_id = %s
                      AND status = 'PROCESSING'
                      AND claim_owner = %s
                    """,
                    (
                        next_attempt,
                        _safe_error_code(error_code),
                        job_id,
                        claim_owner,
                    ),
                )
                updated = cursor.rowcount == 1
            await connection.commit()
        return updated

    async def recover_dead_lettered_max_attempts(
        self,
        *,
        recovery_key: str,
        expected_count: int,
        recovered_at: datetime,
    ) -> DiscoveryEmbeddingRecoveryResult:
        """Requeue only exact 04B jobs dead-lettered by exhausted attempts."""

        _validate_recovery_key(recovery_key)
        if not 1 <= expected_count <= _RECOVERY_EXPECTED_COUNT_MAX:
            raise DiscoveryEmbeddingRecoveryError(
                DiscoveryEmbeddingRecoveryErrorCode.INVALID_EXPECTED_COUNT
            )
        recovered = _mysql_datetime(recovered_at)
        key_hash = _sha256_text(recovery_key)
        command_hash = _recovery_command_hash(
            key_hash=key_hash,
            expected_count=expected_count,
        )
        recovery_id = new_ulid()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        INSERT IGNORE INTO discovery_embedding_recovery_commands (
                            recovery_id, recovery_key_hash, command_hash,
                            expected_count, recovered_count,
                            original_attempt_count_min,
                            original_attempt_count_max,
                            original_error_code, outcome, created_at, completed_at
                        )
                        VALUES (
                            %s, %s, %s, %s, 0, NULL, NULL, NULL,
                            'COUNT_MISMATCH', %s, %s
                        )
                        """,
                        (
                            recovery_id,
                            key_hash,
                            command_hash,
                            expected_count,
                            recovered,
                            recovered,
                        ),
                    )
                    inserted = cursor.rowcount == 1
                    if not inserted:
                        await cursor.execute(
                            """
                            SELECT command_hash, expected_count, recovered_count,
                                   outcome
                            FROM discovery_embedding_recovery_commands
                            WHERE recovery_key_hash = %s
                            FOR UPDATE
                            """,
                            (key_hash,),
                        )
                        row = await cursor.fetchone()
                        if row is None:
                            raise DiscoveryEmbeddingRecoveryError(
                                DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_IN_PROGRESS
                            )
                        if row["command_hash"] != command_hash:
                            raise DiscoveryEmbeddingRecoveryError(
                                DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_KEY_CONFLICT
                            )
                        outcome = DiscoveryEmbeddingRecoveryOutcome(str(row["outcome"]))
                        if outcome == DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH:
                            await connection.commit()
                            return DiscoveryEmbeddingRecoveryResult(
                                outcome=outcome,
                                expected_count=int(row["expected_count"]),
                                recovered_count=0,
                            )
                        await connection.commit()
                        return DiscoveryEmbeddingRecoveryResult(
                            outcome=DiscoveryEmbeddingRecoveryOutcome.REPLAY,
                            expected_count=int(row["expected_count"]),
                            recovered_count=int(row["recovered_count"]),
                        )

                    await cursor.execute(
                        """
                        SELECT COUNT(*) AS eligible_count,
                               MIN(attempt_count) AS min_attempts,
                               MAX(attempt_count) AS max_attempts
                        FROM discovery_embedding_jobs
                        WHERE status = 'DEAD_LETTER'
                          AND last_error_code = 'MAX_ATTEMPTS_EXHAUSTED'
                          AND claim_owner IS NULL
                          AND claim_expires_at IS NULL
                          AND completed_at IS NOT NULL
                          AND document_schema_version =
                              'MARKETPLACE_LISTING_DISCOVERY_V2'
                          AND embedding_input_schema_version =
                              'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1'
                          AND normalizer_version = 'NFKC_WHITESPACE_V1'
                          AND redactor_version = 'PUBLIC_CONTACT_REDACTION_V1'
                          AND language = 'und'
                          AND embedding_provider = 'openai'
                          AND embedding_model = 'text-embedding-3-small'
                          AND embedding_dimensions = 1536
                        FOR UPDATE
                        """
                    )
                    row = await cursor.fetchone()
                    eligible_count = int(row["eligible_count"]) if row else 0
                    if eligible_count != expected_count:
                        await connection.commit()
                        return DiscoveryEmbeddingRecoveryResult(
                            outcome=DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH,
                            expected_count=expected_count,
                            recovered_count=0,
                        )

                    await cursor.execute(
                        """
                        UPDATE discovery_embedding_jobs
                        SET status = 'RETRY_WAIT',
                            attempt_count = 0,
                            next_attempt_at = %s,
                            claim_owner = NULL,
                            claim_expires_at = NULL,
                            last_error_code =
                                'RECOVERED_MAX_ATTEMPTS_EXHAUSTED',
                            completed_at = NULL,
                            updated_at = %s
                        WHERE status = 'DEAD_LETTER'
                          AND last_error_code = 'MAX_ATTEMPTS_EXHAUSTED'
                          AND claim_owner IS NULL
                          AND claim_expires_at IS NULL
                          AND completed_at IS NOT NULL
                          AND document_schema_version =
                              'MARKETPLACE_LISTING_DISCOVERY_V2'
                          AND embedding_input_schema_version =
                              'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1'
                          AND normalizer_version = 'NFKC_WHITESPACE_V1'
                          AND redactor_version = 'PUBLIC_CONTACT_REDACTION_V1'
                          AND language = 'und'
                          AND embedding_provider = 'openai'
                          AND embedding_model = 'text-embedding-3-small'
                          AND embedding_dimensions = 1536
                        """,
                        (recovered, recovered),
                    )
                    if cursor.rowcount != expected_count:
                        raise DiscoveryEmbeddingRecoveryError(
                            DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_IN_PROGRESS
                        )
                    await cursor.execute(
                        """
                        UPDATE discovery_embedding_recovery_commands
                        SET recovered_count = %s,
                            original_attempt_count_min = %s,
                            original_attempt_count_max = %s,
                            original_error_code = 'MAX_ATTEMPTS_EXHAUSTED',
                            outcome = 'RECOVERED',
                            completed_at = %s
                        WHERE recovery_key_hash = %s
                        """,
                        (
                            expected_count,
                            int(row["min_attempts"]),
                            int(row["max_attempts"]),
                            recovered,
                            key_hash,
                        ),
                    )
                await connection.commit()
                return DiscoveryEmbeddingRecoveryResult(
                    outcome=DiscoveryEmbeddingRecoveryOutcome.RECOVERED,
                    expected_count=expected_count,
                    recovered_count=expected_count,
                )
            except BaseException:
                await connection.rollback()
                raise

    async def recover_product_version_zero_callback_contract(
        self,
        *,
        recovery_key: str,
        expected_count: int,
        recovered_at: datetime,
    ) -> DiscoveryEmbeddingRecoveryResult:
        """Requeue only version-0 jobs blocked by the repaired Product callback."""

        _validate_recovery_key(recovery_key)
        if not 1 <= expected_count <= _RECOVERY_EXPECTED_COUNT_MAX:
            raise DiscoveryEmbeddingRecoveryError(
                DiscoveryEmbeddingRecoveryErrorCode.INVALID_EXPECTED_COUNT
            )
        recovered = _mysql_datetime(recovered_at)
        key_hash = _sha256_text(recovery_key)
        mode = DiscoveryEmbeddingRecoveryMode.PRODUCT_VERSION_ZERO_CALLBACK_CONTRACT_REPAIRED_V1
        command_hash = _recovery_command_hash(
            key_hash=key_hash,
            expected_count=expected_count,
            mode=mode,
        )
        recovery_id = new_ulid()
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    replay = await self._insert_or_replay_recovery(
                        cursor,
                        recovery_id=recovery_id,
                        key_hash=key_hash,
                        command_hash=command_hash,
                        expected_count=expected_count,
                        recovered=recovered,
                    )
                    if replay is not None:
                        await connection.commit()
                        return replay

                    predicate = _version_zero_callback_contract_predicate()
                    stats = await self._eligible_recovery_stats(cursor, predicate)
                    eligible_count = int(stats["eligible_count"])
                    if eligible_count != expected_count:
                        await connection.commit()
                        return DiscoveryEmbeddingRecoveryResult(
                            outcome=DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH,
                            expected_count=expected_count,
                            recovered_count=0,
                        )

                    await cursor.execute(
                        f"""
                        UPDATE discovery_embedding_jobs
                        SET status = 'RETRY_WAIT',
                            next_attempt_at = %s,
                            claim_owner = NULL,
                            claim_expires_at = NULL,
                            last_error_code =
                                'RECOVERED_PRODUCT_VERSION_ZERO_CALLBACK',
                            completed_at = NULL,
                            updated_at = %s
                        WHERE {predicate}
                        """,
                        (recovered, recovered),
                    )
                    if cursor.rowcount != expected_count:
                        raise DiscoveryEmbeddingRecoveryError(
                            DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_IN_PROGRESS
                        )
                    await self._finish_recovery_command(
                        cursor,
                        key_hash=key_hash,
                        expected_count=expected_count,
                        original_attempt_count_min=int(stats["min_attempts"]),
                        original_attempt_count_max=int(stats["max_attempts"]),
                        original_error_code="CALLBACK_INVALID_RESPONSE",
                        recovered=recovered,
                    )
                await connection.commit()
                return DiscoveryEmbeddingRecoveryResult(
                    outcome=DiscoveryEmbeddingRecoveryOutcome.RECOVERED,
                    expected_count=expected_count,
                    recovered_count=expected_count,
                )
            except BaseException:
                await connection.rollback()
                raise

    async def _finish(
        self,
        *,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
        status: DiscoveryEmbeddingJobStatus,
        error_code: str | None,
    ) -> bool:
        completed = _mysql_datetime(completed_at)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE discovery_embedding_jobs
                    SET status = %s,
                        claim_owner = NULL,
                        claim_expires_at = NULL,
                        last_error_code = %s,
                        completed_at = %s,
                        updated_at = %s
                    WHERE job_id = %s
                      AND status = 'PROCESSING'
                      AND claim_owner = %s
                    """,
                    (
                        status.value,
                        error_code,
                        completed,
                        completed,
                        job_id,
                        claim_owner,
                    ),
                )
                updated = cursor.rowcount == 1
            await connection.commit()
        return updated

    async def _insert_or_replay_recovery(
        self,
        cursor,
        *,
        recovery_id: str,
        key_hash: str,
        command_hash: str,
        expected_count: int,
        recovered: datetime,
    ) -> DiscoveryEmbeddingRecoveryResult | None:
        await cursor.execute(
            """
            INSERT IGNORE INTO discovery_embedding_recovery_commands (
                recovery_id, recovery_key_hash, command_hash,
                expected_count, recovered_count,
                original_attempt_count_min,
                original_attempt_count_max,
                original_error_code, outcome, created_at, completed_at
            )
            VALUES (
                %s, %s, %s, %s, 0, NULL, NULL, NULL,
                'COUNT_MISMATCH', %s, %s
            )
            """,
            (
                recovery_id,
                key_hash,
                command_hash,
                expected_count,
                recovered,
                recovered,
            ),
        )
        if cursor.rowcount == 1:
            return None
        await cursor.execute(
            """
            SELECT command_hash, expected_count, recovered_count, outcome
            FROM discovery_embedding_recovery_commands
            WHERE recovery_key_hash = %s
            FOR UPDATE
            """,
            (key_hash,),
        )
        row = await cursor.fetchone()
        if row is None:
            raise DiscoveryEmbeddingRecoveryError(
                DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_IN_PROGRESS
            )
        if row["command_hash"] != command_hash:
            raise DiscoveryEmbeddingRecoveryError(
                DiscoveryEmbeddingRecoveryErrorCode.RECOVERY_KEY_CONFLICT
            )
        outcome = DiscoveryEmbeddingRecoveryOutcome(str(row["outcome"]))
        if outcome == DiscoveryEmbeddingRecoveryOutcome.COUNT_MISMATCH:
            return DiscoveryEmbeddingRecoveryResult(
                outcome=outcome,
                expected_count=int(row["expected_count"]),
                recovered_count=0,
            )
        return DiscoveryEmbeddingRecoveryResult(
            outcome=DiscoveryEmbeddingRecoveryOutcome.REPLAY,
            expected_count=int(row["expected_count"]),
            recovered_count=int(row["recovered_count"]),
        )

    async def _eligible_recovery_stats(
        self,
        cursor,
        predicate: str,
    ) -> dict[str, object]:
        await cursor.execute(
            f"""
            SELECT COUNT(*) AS eligible_count,
                   MIN(attempt_count) AS min_attempts,
                   MAX(attempt_count) AS max_attempts
            FROM discovery_embedding_jobs
            WHERE {predicate}
            FOR UPDATE
            """
        )
        row = await cursor.fetchone()
        return dict(row or {})

    async def _finish_recovery_command(
        self,
        cursor,
        *,
        key_hash: str,
        expected_count: int,
        original_attempt_count_min: int,
        original_attempt_count_max: int,
        original_error_code: str,
        recovered: datetime,
    ) -> None:
        await cursor.execute(
            """
            UPDATE discovery_embedding_recovery_commands
            SET recovered_count = %s,
                original_attempt_count_min = %s,
                original_attempt_count_max = %s,
                original_error_code = %s,
                outcome = 'RECOVERED',
                completed_at = %s
            WHERE recovery_key_hash = %s
            """,
            (
                expected_count,
                original_attempt_count_min,
                original_attempt_count_max,
                original_error_code,
                recovered,
                key_hash,
            ),
        )


def retry_delay_seconds(
    attempt_count: int,
    *,
    base_seconds: float,
    maximum_seconds: float,
) -> float:
    """Use deterministic capped exponential backoff for durable retries."""

    return min(maximum_seconds, base_seconds * (2 ** max(0, attempt_count - 1)))


def _job_from_row(
    row: dict[str, object],
    *,
    attempt_count: int | None = None,
) -> DiscoveryEmbeddingJob:
    return DiscoveryEmbeddingJob(
        job_id=str(row["job_id"]),
        event_id=str(row["event_id"]),
        request_id=str(row["request_id"]),
        listing_id=str(row["listing_id"]),
        listing_version=int(row["listing_version"]),
        document_schema_version=str(row["document_schema_version"]),
        document_hash=str(row["document_hash"]),
        embedding_input_schema_version=str(row["embedding_input_schema_version"]),
        embedding_input_hash=str(row["embedding_input_hash"]),
        normalizer_version=str(row["normalizer_version"]),
        redactor_version=str(row["redactor_version"]),
        language=str(row["language"]),
        embedding_provider=str(row["embedding_provider"]),
        embedding_model=str(row["embedding_model"]),
        embedding_dimensions=int(row["embedding_dimensions"]),
        event_occurred_at=_utc_datetime(row["event_occurred_at"]),
        correlation_id=str(row["correlation_id"]),
        event_payload_hash=str(row["event_payload_hash"]),
        status=DiscoveryEmbeddingJobStatus(str(row["status"])),
        attempt_count=(
            int(row["attempt_count"])
            if attempt_count is None
            else attempt_count
        ),
    )


def _safe_error_code(value: str) -> str:
    if (
        not value
        or len(value) > _SAFE_ERROR_CODE_MAX_LENGTH
        or any(not (character.isupper() or character.isdigit() or character == "_")
               for character in value)
    ):
        return "UNCLASSIFIED_FAILURE"
    return value


def _validate_recovery_key(value: str) -> None:
    if not (
        _RECOVERY_KEY_MIN_LENGTH <= len(value) <= _RECOVERY_KEY_MAX_LENGTH
        and all(0x21 <= ord(character) <= 0x7E for character in value)
    ):
        raise DiscoveryEmbeddingRecoveryError(
            DiscoveryEmbeddingRecoveryErrorCode.INVALID_RECOVERY_KEY
        )


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _dead_letter_max_attempts_predicate() -> str:
    return f"""
        status = 'DEAD_LETTER'
        AND last_error_code = 'MAX_ATTEMPTS_EXHAUSTED'
        AND claim_owner IS NULL
        AND claim_expires_at IS NULL
        AND completed_at IS NOT NULL
        {_IDENTITY_PREDICATE}
    """


def _version_zero_callback_contract_predicate() -> str:
    return f"""
        status = 'DEAD_LETTER'
        AND last_error_code = 'CALLBACK_INVALID_RESPONSE'
        AND listing_version = 0
        AND claim_owner IS NULL
        AND claim_expires_at IS NULL
        AND completed_at IS NOT NULL
        {_IDENTITY_PREDICATE}
    """


def _recovery_command_hash(
    *,
    key_hash: str,
    expected_count: int,
    mode: DiscoveryEmbeddingRecoveryMode = (
        DiscoveryEmbeddingRecoveryMode.MAX_ATTEMPTS_EXHAUSTED
    ),
) -> str:
    if mode == DiscoveryEmbeddingRecoveryMode.MAX_ATTEMPTS_EXHAUSTED:
        body = json.dumps(
            {
                "schema": "DISCOVERY_EMBEDDING_RECOVERY_COMMAND_V1",
                "keyHash": key_hash,
                "expectedCount": expected_count,
                "eligibleState": "DEAD_LETTER/MAX_ATTEMPTS_EXHAUSTED",
                "identity": "openai/text-embedding-3-small/1536",
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        return _sha256_text(body)
    body = json.dumps(
        {
            "schema": "DISCOVERY_EMBEDDING_RECOVERY_COMMAND_V1",
            "keyHash": key_hash,
            "expectedCount": expected_count,
            "mode": mode.value,
            "eligibleState": "DEAD_LETTER/CALLBACK_INVALID_RESPONSE/LISTING_VERSION_0",
            "identity": "openai/text-embedding-3-small/1536",
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    return _sha256_text(body)


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("database timestamps must include a UTC offset")
    return value.astimezone(UTC).replace(tzinfo=None)


def _utc_datetime(value: object) -> datetime:
    if not isinstance(value, datetime):
        raise ValueError("database timestamp is invalid")
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)
