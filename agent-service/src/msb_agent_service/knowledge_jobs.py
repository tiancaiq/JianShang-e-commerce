from __future__ import annotations

import secrets
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Protocol, Sequence

import aiomysql

from .config import KnowledgeIngestionSettings
from .knowledge_events import (
    FIXED_ID_PATTERN,
    KnowledgeEventEnvelope,
    KnowledgeEventErrorCode,
    KnowledgeEventValidationError,
)

_CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_REQUIRED_TABLES = {
    "processed_events",
    "knowledge_ingestion_jobs",
    "knowledge_source_state",
    "knowledge_rebuild_runs",
    "knowledge_deletion_jobs",
    "knowledge_rebuild_sources",
}


class EnqueueResult(StrEnum):
    ACCEPTED = "ACCEPTED"
    DUPLICATE = "DUPLICATE"


class KnowledgeJobStatus(StrEnum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    RETRY_WAIT = "RETRY_WAIT"
    SUCCEEDED = "SUCCEEDED"
    DEAD_LETTER = "DEAD_LETTER"


class SourceStateApplyResult(StrEnum):
    APPLIED = "APPLIED"
    IDEMPOTENT = "IDEMPOTENT"
    STALE = "STALE"
    CONFLICT = "CONFLICT"


@dataclass(frozen=True)
class KnowledgeIngestionJob:
    job_id: str
    event_id: str
    source_type: str
    source_id: str
    source_version: int
    language: str
    lifecycle: str
    superseded_version: int | None
    event_occurred_at: datetime
    payload_hash: str
    status: KnowledgeJobStatus
    attempt_count: int


@dataclass(frozen=True)
class KnowledgeSourceState:
    source_type: str
    source_id: str
    language: str
    latest_observed_version: int
    latest_indexed_version: int | None
    latest_content_hash: str | None
    last_superseded_version: int | None
    state: str
    chunker_version: str | None
    embedding_provider: str | None
    embedding_model: str | None
    embedding_dimensions: int | None
    source_event_occurred_at: datetime
    indexed_at: datetime | None
    invalidated_at: datetime | None
    last_event_id: str
    optimistic_version: int


class KnowledgeJobStore(Protocol):
    async def enqueue_event(
        self,
        consumer_name: str,
        event: KnowledgeEventEnvelope,
        accepted_at: datetime,
    ) -> EnqueueResult: ...

    async def validate_schema(self) -> None: ...


class KnowledgeJobRepository:
    """Owns transactional deduplication, enqueue, claim, and terminal states."""

    def __init__(
        self,
        pool: aiomysql.Pool,
        settings: KnowledgeIngestionSettings,
    ) -> None:
        self._pool = pool
        self._settings = settings

    @classmethod
    async def create(
        cls, settings: KnowledgeIngestionSettings
    ) -> "KnowledgeJobRepository":
        """Create a bounded pool without creating or modifying database schema."""

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
        return cls(pool, settings)

    async def close(self) -> None:
        self._pool.close()
        await self._pool.wait_closed()

    async def validate_schema(self) -> None:
        """Fail enabled startup when the explicit Flyway job has not run."""

        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = %s
                      AND table_name IN (%s, %s, %s, %s, %s, %s)
                    """,
                    (
                        self._settings.mysql_database,
                        "processed_events",
                        "knowledge_ingestion_jobs",
                        "knowledge_source_state",
                        "knowledge_rebuild_runs",
                        "knowledge_deletion_jobs",
                        "knowledge_rebuild_sources",
                    ),
                )
                found = {row[0] for row in await cursor.fetchall()}
        if found != _REQUIRED_TABLES:
            raise RuntimeError("KNOWLEDGE_INGESTION_SCHEMA_NOT_MIGRATED")

    async def enqueue_event(
        self,
        consumer_name: str,
        event: KnowledgeEventEnvelope,
        accepted_at: datetime,
    ) -> EnqueueResult:
        """Atomically deduplicate the Kafka event and create one durable job."""

        payload_hash = event.payload_hash()
        accepted = _mysql_datetime(accepted_at)
        occurred = _mysql_datetime(event.occurred_at)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        f"""
                        INSERT IGNORE INTO processed_events (
                            consumer_name,
                            event_id,
                            event_type,
                            event_version,
                            aggregate_type,
                            aggregate_id,
                            payload_hash,
                            accepted_at,
                            correlation_id
                        )
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            consumer_name,
                            event.event_id,
                            event.event_type,
                            event.event_version,
                            event.aggregate_type,
                            event.aggregate_id,
                            payload_hash,
                            accepted,
                            event.correlation_id,
                        ),
                    )
                    if cursor.rowcount == 0:
                        await cursor.execute(
                            """
                            SELECT payload_hash
                            FROM processed_events
                            WHERE consumer_name = %s AND event_id = %s
                            """,
                            (consumer_name, event.event_id),
                        )
                        existing = await cursor.fetchone()
                        if existing is None or existing["payload_hash"] != payload_hash:
                            raise KnowledgeEventValidationError(
                                KnowledgeEventErrorCode.EVENT_ID_CONFLICT
                            )
                        await connection.commit()
                        return EnqueueResult.DUPLICATE

                    job_id = new_ulid()
                    await cursor.execute(
                        """
                        INSERT INTO knowledge_ingestion_jobs (
                            job_id,
                            event_id,
                            source_type,
                            source_id,
                            source_version,
                            language,
                            lifecycle,
                            superseded_version,
                            event_occurred_at,
                            payload_hash,
                            status,
                            attempt_count,
                            next_attempt_at,
                            claim_owner,
                            claim_expires_at,
                            last_error_code,
                            created_at,
                            updated_at,
                            completed_at
                        )
                        VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            'PENDING', 0, %s, NULL, NULL, NULL, %s, %s, NULL
                        )
                        """,
                        (
                            job_id,
                            event.event_id,
                            event.source_type,
                            event.source_id,
                            event.source_version,
                            event.language,
                            event.lifecycle,
                            event.superseded_version,
                            occurred,
                            payload_hash,
                            accepted,
                            accepted,
                            accepted,
                        ),
                    )
                await connection.commit()
                return EnqueueResult.ACCEPTED
            except BaseException:
                await connection.rollback()
                raise

    async def claim_jobs(
        self,
        claim_owner: str,
        now: datetime,
        limit: int | None = None,
        source_types: tuple[str, ...] = ("LISTING",),
    ) -> list[KnowledgeIngestionJob]:
        """Claim a bounded batch with expiring ownership for multi-worker safety."""

        claim_limit = limit or self._settings.job_claim_batch_size
        if (
            not source_types
            or len(source_types) > 5
            or any(
                value not in {"LISTING", "CATEGORY_GUIDANCE"}
                for value in source_types
            )
        ):
            raise ValueError("source_types contains an unsupported knowledge source")
        source_placeholders = ",".join(["%s"] * len(source_types))
        claimed_at = _mysql_datetime(now)
        claim_expires = _mysql_datetime(
            now + timedelta(seconds=self._settings.job_claim_seconds)
        )
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        f"""
                        UPDATE knowledge_ingestion_jobs
                        SET status = 'DEAD_LETTER',
                            claim_owner = NULL,
                            claim_expires_at = NULL,
                            last_error_code = 'MAX_ATTEMPTS_EXHAUSTED',
                            completed_at = %s,
                            updated_at = %s
                        WHERE source_type IN ({source_placeholders})
                          AND attempt_count >= %s
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
                            *source_types,
                            self._settings.job_max_attempts,
                            claimed_at,
                            claimed_at,
                        ),
                    )
                    await cursor.execute(
                        f"""
                        SELECT
                            job_id,
                            event_id,
                            source_type,
                            source_id,
                            source_version,
                            language,
                            lifecycle,
                            superseded_version,
                            event_occurred_at,
                            payload_hash,
                            status,
                            attempt_count
                        FROM knowledge_ingestion_jobs
                        WHERE source_type IN ({source_placeholders})
                          AND attempt_count < %s
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
                        (
                            *source_types,
                            self._settings.job_max_attempts,
                            claimed_at,
                            claimed_at,
                            claim_limit,
                        ),
                    )
                    rows = list(await cursor.fetchall())
                    if not rows:
                        await connection.commit()
                        return []
                    job_ids = [row["job_id"] for row in rows]
                    placeholders = ",".join(["%s"] * len(job_ids))
                    await cursor.execute(
                        f"""
                        UPDATE knowledge_ingestion_jobs
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
        self, job_id: str, claim_owner: str, completed_at: datetime
    ) -> bool:
        return await self._finish_claim(
            job_id,
            claim_owner,
            KnowledgeJobStatus.SUCCEEDED,
            completed_at,
            None,
        )

    async def mark_dead_letter(
        self,
        job_id: str,
        claim_owner: str,
        completed_at: datetime,
        error_code: str,
    ) -> bool:
        return await self._finish_claim(
            job_id,
            claim_owner,
            KnowledgeJobStatus.DEAD_LETTER,
            completed_at,
            error_code,
        )

    async def schedule_retry(
        self,
        job_id: str,
        claim_owner: str,
        next_attempt_at: datetime,
        error_code: str,
    ) -> bool:
        updated = _mysql_datetime(datetime.now(UTC))
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_ingestion_jobs
                    SET status = 'RETRY_WAIT',
                        next_attempt_at = %s,
                        claim_owner = NULL,
                        claim_expires_at = NULL,
                        last_error_code = %s,
                        updated_at = %s
                    WHERE job_id = %s
                      AND status = 'PROCESSING'
                      AND claim_owner = %s
                    """,
                    (
                        _mysql_datetime(next_attempt_at),
                        error_code,
                        updated,
                        job_id,
                        claim_owner,
                    ),
                )
                changed = cursor.rowcount == 1
            await connection.commit()
        return changed

    async def get_source_state(
        self,
        source_type: str,
        source_id: str,
        language: str,
    ) -> KnowledgeSourceState | None:
        """Read the current monotonic projection checkpoint for one source."""

        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT
                        source_type,
                        source_id,
                        language,
                        latest_observed_version,
                        latest_indexed_version,
                        latest_content_hash,
                        last_superseded_version,
                        state,
                        chunker_version,
                        embedding_provider,
                        embedding_model,
                        embedding_dimensions,
                        source_event_occurred_at,
                        indexed_at,
                        invalidated_at,
                        last_event_id,
                        optimistic_version
                    FROM knowledge_source_state
                    WHERE source_type = %s
                      AND source_id = %s
                      AND language = %s
                    """,
                    (source_type, source_id, language),
                )
                row = await cursor.fetchone()
        return None if row is None else _source_state_from_row(row)

    async def active_category_guidance_states(
        self,
        category_id: str,
        languages: tuple[str, ...],
    ) -> list[KnowledgeSourceState]:
        """Resolve only fully indexed active category scopes for retrieval."""

        if (
            FIXED_ID_PATTERN.fullmatch(category_id) is None
            or not 1 <= len(languages) <= 3
            or any(
                not language
                or language != language.lower()
                or any(character.isspace() for character in language)
                for language in languages
            )
        ):
            raise ValueError("category guidance scope is invalid")
        placeholders = ",".join(["%s"] * len(languages))
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    f"""
                    SELECT source_type, source_id, language,
                           latest_observed_version, latest_indexed_version,
                           latest_content_hash, last_superseded_version,
                           state, chunker_version, embedding_provider,
                           embedding_model, embedding_dimensions,
                           source_event_occurred_at, indexed_at,
                           invalidated_at, last_event_id, optimistic_version
                    FROM knowledge_source_state
                    WHERE source_type = 'CATEGORY_GUIDANCE'
                      AND source_id = %s
                      AND language IN ({placeholders})
                      AND state = 'ACTIVE'
                      AND latest_indexed_version = latest_observed_version
                    """,
                    (category_id, *languages),
                )
                rows = list(await cursor.fetchall())
        return [_source_state_from_row(row) for row in rows]

    async def apply_active_source_state(
        self,
        job: KnowledgeIngestionJob,
        *,
        content_hash: str,
        chunker_version: str,
        embedding_provider: str,
        embedding_model: str,
        embedding_dimensions: int,
        indexed_at: datetime,
    ) -> SourceStateApplyResult:
        """Advance one active source checkpoint without permitting downgrade."""

        updated = _mysql_datetime(indexed_at)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    existing = await self._locked_source_state(cursor, job)
                    decision = _active_state_decision(
                        existing,
                        job,
                        content_hash=content_hash,
                        chunker_version=chunker_version,
                        embedding_provider=embedding_provider,
                        embedding_model=embedding_model,
                        embedding_dimensions=embedding_dimensions,
                    )
                    if decision != SourceStateApplyResult.APPLIED:
                        await connection.commit()
                        return decision
                    await self._upsert_source_state(
                        cursor,
                        job=job,
                        latest_indexed_version=job.source_version,
                        latest_content_hash=content_hash,
                        state="ACTIVE",
                        chunker_version=chunker_version,
                        embedding_provider=embedding_provider,
                        embedding_model=embedding_model,
                        embedding_dimensions=embedding_dimensions,
                        indexed_at=updated,
                        invalidated_at=None,
                        updated_at=updated,
                    )
                await connection.commit()
                return SourceStateApplyResult.APPLIED
            except BaseException:
                await connection.rollback()
                raise

    async def apply_tombstone_source_state(
        self,
        job: KnowledgeIngestionJob,
        *,
        invalidated_at: datetime,
    ) -> SourceStateApplyResult:
        """Advance one tombstone while retaining the last indexed audit identity."""

        updated = _mysql_datetime(invalidated_at)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    existing = await self._locked_source_state(cursor, job)
                    decision = _tombstone_state_decision(existing, job)
                    if decision != SourceStateApplyResult.APPLIED:
                        await connection.commit()
                        return decision
                    await self._upsert_source_state(
                        cursor,
                        job=job,
                        latest_indexed_version=(
                            None
                            if existing is None
                            else existing.latest_indexed_version
                        ),
                        latest_content_hash=(
                            None
                            if existing is None
                            else existing.latest_content_hash
                        ),
                        state="TOMBSTONED",
                        chunker_version=(
                            None if existing is None else existing.chunker_version
                        ),
                        embedding_provider=(
                            None
                            if existing is None
                            else existing.embedding_provider
                        ),
                        embedding_model=(
                            None if existing is None else existing.embedding_model
                        ),
                        embedding_dimensions=(
                            None
                            if existing is None
                            else existing.embedding_dimensions
                        ),
                        indexed_at=(
                            None if existing is None else existing.indexed_at
                        ),
                        invalidated_at=updated,
                        updated_at=updated,
                    )
                await connection.commit()
                return SourceStateApplyResult.APPLIED
            except BaseException:
                await connection.rollback()
                raise

    async def job_counts(self) -> dict[str, int]:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT status, COUNT(*)
                    FROM knowledge_ingestion_jobs
                    GROUP BY status
                    """
                )
                return {str(status): int(count) for status, count in await cursor.fetchall()}

    async def oldest_pending_age_seconds(self, now: datetime) -> float:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT MIN(created_at)
                    FROM knowledge_ingestion_jobs
                    WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING')
                    """
                )
                row = await cursor.fetchone()
        if row is None or row[0] is None:
            return 0.0
        created_at = row[0].replace(tzinfo=UTC)
        return max(0.0, (now.astimezone(UTC) - created_at).total_seconds())

    async def _finish_claim(
        self,
        job_id: str,
        claim_owner: str,
        status: KnowledgeJobStatus,
        completed_at: datetime,
        error_code: str | None,
    ) -> bool:
        completed = _mysql_datetime(completed_at)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_ingestion_jobs
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
                changed = cursor.rowcount == 1
            await connection.commit()
        return changed

    async def _locked_source_state(
        self,
        cursor: aiomysql.DictCursor,
        job: KnowledgeIngestionJob,
    ) -> KnowledgeSourceState | None:
        await cursor.execute(
            """
            SELECT
                source_type,
                source_id,
                language,
                latest_observed_version,
                latest_indexed_version,
                latest_content_hash,
                last_superseded_version,
                state,
                chunker_version,
                embedding_provider,
                embedding_model,
                embedding_dimensions,
                source_event_occurred_at,
                indexed_at,
                invalidated_at,
                last_event_id,
                optimistic_version
            FROM knowledge_source_state
            WHERE source_type = %s
              AND source_id = %s
              AND language = %s
            FOR UPDATE
            """,
            (job.source_type, job.source_id, job.language),
        )
        row = await cursor.fetchone()
        return None if row is None else _source_state_from_row(row)

    async def _upsert_source_state(
        self,
        cursor: aiomysql.DictCursor,
        *,
        job: KnowledgeIngestionJob,
        latest_indexed_version: int | None,
        latest_content_hash: str | None,
        state: str,
        chunker_version: str | None,
        embedding_provider: str | None,
        embedding_model: str | None,
        embedding_dimensions: int | None,
        indexed_at: datetime | None,
        invalidated_at: datetime | None,
        updated_at: datetime,
    ) -> None:
        await cursor.execute(
            """
            INSERT INTO knowledge_source_state (
                source_type,
                source_id,
                language,
                latest_observed_version,
                latest_indexed_version,
                latest_content_hash,
                last_superseded_version,
                state,
                chunker_version,
                embedding_provider,
                embedding_model,
                embedding_dimensions,
                source_event_occurred_at,
                indexed_at,
                invalidated_at,
                updated_at,
                last_event_id,
                last_failure_code,
                optimistic_version
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, %s, NULL, 0
            )
            ON DUPLICATE KEY UPDATE
                latest_observed_version = VALUES(latest_observed_version),
                latest_indexed_version = VALUES(latest_indexed_version),
                latest_content_hash = VALUES(latest_content_hash),
                last_superseded_version = VALUES(last_superseded_version),
                state = VALUES(state),
                chunker_version = VALUES(chunker_version),
                embedding_provider = VALUES(embedding_provider),
                embedding_model = VALUES(embedding_model),
                embedding_dimensions = VALUES(embedding_dimensions),
                source_event_occurred_at = VALUES(source_event_occurred_at),
                indexed_at = VALUES(indexed_at),
                invalidated_at = VALUES(invalidated_at),
                updated_at = VALUES(updated_at),
                last_event_id = VALUES(last_event_id),
                last_failure_code = NULL,
                optimistic_version = optimistic_version + 1
            """,
            (
                job.source_type,
                job.source_id,
                job.language,
                job.source_version,
                latest_indexed_version,
                latest_content_hash,
                job.superseded_version,
                state,
                chunker_version,
                embedding_provider,
                embedding_model,
                embedding_dimensions,
                _mysql_datetime(job.event_occurred_at),
                indexed_at,
                invalidated_at,
                updated_at,
                job.event_id,
            ),
        )


def new_ulid(now_ms: int | None = None) -> str:
    """Generate a canonical 26-character ULID for durable job identity."""

    timestamp_ms = int(time.time() * 1000) if now_ms is None else now_ms
    if not 0 <= timestamp_ms < 2**48:
        raise ValueError("ULID timestamp is outside the 48-bit range")
    value = (timestamp_ms << 80) | int.from_bytes(secrets.token_bytes(10), "big")
    encoded = ["0"] * 26
    for index in range(25, -1, -1):
        encoded[index] = _CROCKFORD_BASE32[value & 31]
        value >>= 5
    return "".join(encoded)


def retry_delay_seconds(
    attempt_count: int,
    base_seconds: float,
    max_seconds: float,
    jitter_fraction: float,
) -> float:
    """Return bounded exponential retry delay with caller-supplied jitter."""

    if not 0.0 <= jitter_fraction < 1.0:
        raise ValueError("jitter_fraction must be between zero and one")
    exponential = min(max_seconds, base_seconds * (2 ** max(0, attempt_count - 1)))
    return min(max_seconds, exponential * (1.0 + jitter_fraction * 0.25))


def _job_from_row(
    row: dict[str, object],
    *,
    attempt_count: int | None = None,
) -> KnowledgeIngestionJob:
    occurred_at = row["event_occurred_at"]
    if not isinstance(occurred_at, datetime):
        raise TypeError("event_occurred_at must be a datetime")
    return KnowledgeIngestionJob(
        job_id=str(row["job_id"]),
        event_id=str(row["event_id"]),
        source_type=str(row["source_type"]),
        source_id=str(row["source_id"]),
        source_version=int(row["source_version"]),
        language=str(row["language"]),
        lifecycle=str(row["lifecycle"]),
        superseded_version=(
            None
            if row["superseded_version"] is None
            else int(row["superseded_version"])
        ),
        event_occurred_at=occurred_at.replace(tzinfo=UTC),
        payload_hash=str(row["payload_hash"]),
        status=KnowledgeJobStatus.PROCESSING,
        attempt_count=(
            int(row["attempt_count"])
            if attempt_count is None
            else attempt_count
        ),
    )


def _source_state_from_row(row: dict[str, object]) -> KnowledgeSourceState:
    occurred_at = row["source_event_occurred_at"]
    indexed_at = row["indexed_at"]
    invalidated_at = row["invalidated_at"]
    if not isinstance(occurred_at, datetime):
        raise TypeError("source_event_occurred_at must be a datetime")
    if indexed_at is not None and not isinstance(indexed_at, datetime):
        raise TypeError("indexed_at must be a datetime")
    if invalidated_at is not None and not isinstance(invalidated_at, datetime):
        raise TypeError("invalidated_at must be a datetime")
    return KnowledgeSourceState(
        source_type=str(row["source_type"]),
        source_id=str(row["source_id"]),
        language=str(row["language"]),
        latest_observed_version=int(row["latest_observed_version"]),
        latest_indexed_version=(
            None
            if row["latest_indexed_version"] is None
            else int(row["latest_indexed_version"])
        ),
        latest_content_hash=(
            None
            if row["latest_content_hash"] is None
            else str(row["latest_content_hash"])
        ),
        last_superseded_version=(
            None
            if row["last_superseded_version"] is None
            else int(row["last_superseded_version"])
        ),
        state=str(row["state"]),
        chunker_version=(
            None
            if row["chunker_version"] is None
            else str(row["chunker_version"])
        ),
        embedding_provider=(
            None
            if row["embedding_provider"] is None
            else str(row["embedding_provider"])
        ),
        embedding_model=(
            None
            if row["embedding_model"] is None
            else str(row["embedding_model"])
        ),
        embedding_dimensions=(
            None
            if row["embedding_dimensions"] is None
            else int(row["embedding_dimensions"])
        ),
        source_event_occurred_at=occurred_at.replace(tzinfo=UTC),
        indexed_at=(
            None if indexed_at is None else indexed_at.replace(tzinfo=UTC)
        ),
        invalidated_at=(
            None
            if invalidated_at is None
            else invalidated_at.replace(tzinfo=UTC)
        ),
        last_event_id=str(row["last_event_id"]),
        optimistic_version=int(row["optimistic_version"]),
    )


def _active_state_decision(
    existing: KnowledgeSourceState | None,
    job: KnowledgeIngestionJob,
    *,
    content_hash: str,
    chunker_version: str,
    embedding_provider: str,
    embedding_model: str,
    embedding_dimensions: int,
) -> SourceStateApplyResult:
    if existing is None:
        return SourceStateApplyResult.APPLIED
    if existing.latest_observed_version > job.source_version:
        return SourceStateApplyResult.STALE
    if existing.latest_observed_version < job.source_version:
        return SourceStateApplyResult.APPLIED
    if (
        existing.state == "ACTIVE"
        and existing.latest_indexed_version == job.source_version
        and existing.latest_content_hash == content_hash
        and existing.chunker_version == chunker_version
        and existing.embedding_provider == embedding_provider
        and existing.embedding_model == embedding_model
        and existing.embedding_dimensions == embedding_dimensions
    ):
        return SourceStateApplyResult.IDEMPOTENT
    return SourceStateApplyResult.CONFLICT


def _tombstone_state_decision(
    existing: KnowledgeSourceState | None,
    job: KnowledgeIngestionJob,
) -> SourceStateApplyResult:
    if existing is None:
        return SourceStateApplyResult.APPLIED
    if existing.latest_observed_version > job.source_version:
        return SourceStateApplyResult.STALE
    if existing.latest_observed_version < job.source_version:
        return SourceStateApplyResult.APPLIED
    if existing.state == "TOMBSTONED":
        return SourceStateApplyResult.IDEMPOTENT
    return SourceStateApplyResult.CONFLICT


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("database timestamps must include a UTC offset")
    return value.astimezone(UTC).replace(tzinfo=None)


def claimed_job_ids(jobs: Sequence[KnowledgeIngestionJob]) -> tuple[str, ...]:
    """Expose immutable exact IDs for bounded worker logging and tests."""

    return tuple(job.job_id for job in jobs)
