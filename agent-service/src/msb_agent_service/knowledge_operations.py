from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum

import aiomysql

from .config import KnowledgeIngestionSettings
from .knowledge_jobs import new_ulid


class RebuildStatus(StrEnum):
    RUNNING = "RUNNING"
    FAILED = "FAILED"
    READY_TO_PROMOTE = "READY_TO_PROMOTE"
    PROMOTED = "PROMOTED"
    ROLLED_BACK = "ROLLED_BACK"


class DeletionStatus(StrEnum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    RETRY_WAIT = "RETRY_WAIT"
    SUCCEEDED = "SUCCEEDED"
    DEAD_LETTER = "DEAD_LETTER"


@dataclass(frozen=True)
class KnowledgeRebuildRun:
    run_id: str
    target_generation: str
    previous_read_generation: str
    export_cursor: str | None
    export_watermark: datetime | None
    export_complete: bool
    expected_count: int
    processed_count: int
    failed_count: int
    skipped_count: int
    tombstoned_count: int
    status: RebuildStatus
    initiated_by: str
    source_type: str = "LISTING"


@dataclass(frozen=True)
class KnowledgeRebuildSource:
    run_id: str
    source_type: str
    sanitizer_version: str
    chunker_version: str
    export_cursor: str | None
    export_watermark: datetime | None
    export_complete: bool
    expected_count: int
    processed_count: int
    failed_count: int
    skipped_count: int
    tombstoned_count: int


@dataclass(frozen=True)
class KnowledgeDeletionJob:
    deletion_id: str
    source_type: str
    source_id: str
    source_version: int
    language: str
    invalidated_at: datetime
    attempt_count: int


class KnowledgeOperationsRepository:
    """Owns rebuild checkpoints and exact retryable physical deletions."""

    def __init__(
        self,
        pool: aiomysql.Pool,
        settings: KnowledgeIngestionSettings,
    ) -> None:
        self._pool = pool
        self._settings = settings

    @classmethod
    async def create(
        cls,
        settings: KnowledgeIngestionSettings,
    ) -> "KnowledgeOperationsRepository":
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

    async def create_rebuild(
        self,
        *,
        target_generation: str,
        previous_read_generation: str,
        embedding_provider: str,
        embedding_model: str,
        embedding_dimensions: int,
        chunker_version: str,
        initiated_by: str,
        now: datetime,
        source_type: str = "LISTING",
        rebuild_sources: tuple[tuple[str, str, str], ...] = (),
    ) -> KnowledgeRebuildRun:
        """Create one resumable rebuild after the write alias moves."""

        if source_type not in {"LISTING", "PUBLIC_KNOWLEDGE"}:
            raise ValueError("unsupported rebuild source type")
        if source_type == "PUBLIC_KNOWLEDGE" and {
            item[0] for item in rebuild_sources
        } != {"LISTING", "CATEGORY_GUIDANCE"}:
            raise ValueError("public knowledge rebuild requires the complete source set")
        run_id = new_ulid()
        timestamp = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT INTO knowledge_rebuild_runs (
                        run_id, source_type, target_generation,
                        previous_read_generation, embedding_provider,
                        embedding_model, embedding_dimensions, chunker_version,
                        export_cursor, export_watermark, export_complete,
                        expected_count, processed_count, failed_count,
                        skipped_count, tombstoned_count, status, last_error_code,
                        initiated_by, started_at, updated_at, completed_at,
                        promoted_at, rolled_back_at, optimistic_version
                    )
                    VALUES (
                        %s, %s, %s, %s, %s, %s, %s, %s,
                        NULL, NULL, FALSE, 0, 0, 0, 0, 0, 'RUNNING', NULL,
                        %s, %s, %s, NULL, NULL, NULL, 0
                    )
                    """,
                    (
                        run_id,
                        source_type,
                        target_generation,
                        previous_read_generation,
                        embedding_provider,
                        embedding_model,
                        embedding_dimensions,
                        chunker_version,
                        initiated_by,
                        timestamp,
                        timestamp,
                    ),
                )
                for child_source_type, sanitizer_version, source_chunker in (
                    rebuild_sources
                ):
                    await cursor.execute(
                        """
                        INSERT INTO knowledge_rebuild_sources (
                            run_id, source_type, sanitizer_version,
                            chunker_version, export_cursor, export_watermark,
                            export_complete, expected_count, processed_count,
                            failed_count, skipped_count, tombstoned_count,
                            last_error_code, created_at, updated_at
                        )
                        VALUES (
                            %s, %s, %s, %s, NULL, NULL, FALSE,
                            0, 0, 0, 0, 0, NULL, %s, %s
                        )
                        """,
                        (
                            run_id,
                            child_source_type,
                            sanitizer_version,
                            source_chunker,
                            timestamp,
                            timestamp,
                        ),
                    )
            await connection.commit()
        run = await self.get_rebuild(run_id)
        if run is None:
            raise RuntimeError("REBUILD_CHECKPOINT_MISSING")
        return run

    async def get_rebuild(self, run_id: str) -> KnowledgeRebuildRun | None:
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT run_id, source_type, target_generation,
                           previous_read_generation,
                           export_cursor, export_watermark, export_complete,
                           expected_count, processed_count, failed_count,
                           skipped_count, tombstoned_count, status, initiated_by
                    FROM knowledge_rebuild_runs
                    WHERE run_id = %s
                    """,
                    (run_id,),
                )
                row = await cursor.fetchone()
        return None if row is None else _rebuild_from_row(row)

    async def get_rebuild_by_target(
        self,
        target_generation: str,
    ) -> KnowledgeRebuildRun | None:
        """Resolve an alias-move crash to its durable run, when present."""

        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT run_id, source_type, target_generation,
                           previous_read_generation,
                           export_cursor, export_watermark, export_complete,
                           expected_count, processed_count, failed_count,
                           skipped_count, tombstoned_count, status, initiated_by
                    FROM knowledge_rebuild_runs
                    WHERE target_generation = %s
                    """,
                    (target_generation,),
                )
                row = await cursor.fetchone()
        return None if row is None else _rebuild_from_row(row)

    async def checkpoint_rebuild_page(
        self,
        run_id: str,
        *,
        next_cursor: str | None,
        export_watermark: datetime,
        expected_delta: int,
        processed_delta: int,
        skipped_delta: int,
        tombstoned_delta: int,
        complete: bool,
        now: datetime,
    ) -> None:
        """Advance a page checkpoint only after its exact index writes finish."""

        completed_at = _mysql_datetime(now) if complete else None
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_rebuild_runs
                    SET export_cursor = %s,
                        export_watermark = %s,
                        export_complete = %s,
                        expected_count = expected_count + %s,
                        processed_count = processed_count + %s,
                        skipped_count = skipped_count + %s,
                        tombstoned_count = tombstoned_count + %s,
                        updated_at = %s,
                        completed_at = %s,
                        optimistic_version = optimistic_version + 1
                    WHERE run_id = %s
                      AND status = 'RUNNING'
                    """,
                    (
                        next_cursor,
                        _mysql_datetime(export_watermark),
                        complete,
                        expected_delta,
                        processed_delta,
                        skipped_delta,
                        tombstoned_delta,
                        _mysql_datetime(now),
                        completed_at,
                        run_id,
                    ),
                )
                if cursor.rowcount != 1:
                    raise RuntimeError("REBUILD_NOT_RUNNING")
            await connection.commit()

    async def get_rebuild_sources(
        self,
        run_id: str,
    ) -> list[KnowledgeRebuildSource]:
        """Return deterministic per-source checkpoints for a composite run."""

        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT run_id, source_type, sanitizer_version,
                           chunker_version, export_cursor, export_watermark,
                           export_complete, expected_count, processed_count,
                           failed_count, skipped_count, tombstoned_count
                    FROM knowledge_rebuild_sources
                    WHERE run_id = %s
                    ORDER BY source_type
                    """,
                    (run_id,),
                )
                rows = list(await cursor.fetchall())
        return [_rebuild_source_from_row(row) for row in rows]

    async def checkpoint_rebuild_source_page(
        self,
        run_id: str,
        source_type: str,
        *,
        next_cursor: str | None,
        export_watermark: datetime,
        expected_delta: int,
        processed_delta: int,
        skipped_delta: int,
        tombstoned_delta: int,
        complete: bool,
        now: datetime,
    ) -> None:
        """Checkpoint one source and atomically refresh parent aggregates."""

        if source_type not in {"LISTING", "CATEGORY_GUIDANCE"}:
            raise ValueError("unsupported rebuild source type")
        timestamp = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        UPDATE knowledge_rebuild_sources
                        SET export_cursor = %s,
                            export_watermark = %s,
                            export_complete = %s,
                            expected_count = expected_count + %s,
                            processed_count = processed_count + %s,
                            skipped_count = skipped_count + %s,
                            tombstoned_count = tombstoned_count + %s,
                            updated_at = %s
                        WHERE run_id = %s
                          AND source_type = %s
                          AND export_complete = FALSE
                        """,
                        (
                            next_cursor,
                            _mysql_datetime(export_watermark),
                            complete,
                            expected_delta,
                            processed_delta,
                            skipped_delta,
                            tombstoned_delta,
                            timestamp,
                            run_id,
                            source_type,
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise RuntimeError("REBUILD_SOURCE_NOT_RUNNING")
                    await cursor.execute(
                        """
                        SELECT COUNT(*)
                        FROM knowledge_rebuild_sources
                        WHERE run_id = %s AND export_complete = FALSE
                        """,
                        (run_id,),
                    )
                    incomplete = int((await cursor.fetchone())[0])
                    await cursor.execute(
                        """
                        UPDATE knowledge_rebuild_runs
                        SET expected_count = expected_count + %s,
                            processed_count = processed_count + %s,
                            skipped_count = skipped_count + %s,
                            tombstoned_count = tombstoned_count + %s,
                            export_complete = %s,
                            export_cursor = NULL,
                            updated_at = %s,
                            completed_at = CASE WHEN %s THEN %s ELSE NULL END,
                            optimistic_version = optimistic_version + 1
                        WHERE run_id = %s
                          AND source_type = 'PUBLIC_KNOWLEDGE'
                          AND status = 'RUNNING'
                        """,
                        (
                            expected_delta,
                            processed_delta,
                            skipped_delta,
                            tombstoned_delta,
                            incomplete == 0,
                            timestamp,
                            incomplete == 0,
                            timestamp,
                            run_id,
                        ),
                    )
                    if cursor.rowcount != 1:
                        raise RuntimeError("REBUILD_NOT_RUNNING")
                await connection.commit()
            except BaseException:
                await connection.rollback()
                raise

    async def mark_rebuild_failed(
        self,
        run_id: str,
        error_code: str,
        now: datetime,
    ) -> None:
        await self._transition_rebuild(
            run_id,
            from_status=RebuildStatus.RUNNING,
            to_status=RebuildStatus.FAILED,
            now=now,
            error_code=error_code,
            increment_failed=True,
        )

    async def mark_ready_to_promote(self, run_id: str, now: datetime) -> None:
        await self._transition_rebuild(
            run_id,
            from_status=RebuildStatus.RUNNING,
            to_status=RebuildStatus.READY_TO_PROMOTE,
            now=now,
        )

    async def mark_promoted(self, run_id: str, now: datetime) -> None:
        await self._transition_rebuild(
            run_id,
            from_status=RebuildStatus.READY_TO_PROMOTE,
            to_status=RebuildStatus.PROMOTED,
            now=now,
            promoted=True,
        )

    async def mark_rolled_back(self, run_id: str, now: datetime) -> None:
        await self._transition_rebuild(
            run_id,
            from_status=RebuildStatus.PROMOTED,
            to_status=RebuildStatus.ROLLED_BACK,
            now=now,
            rolled_back=True,
        )

    async def list_rebuilds(self, limit: int = 10) -> list[KnowledgeRebuildRun]:
        async with self._pool.acquire() as connection:
            async with connection.cursor(aiomysql.DictCursor) as cursor:
                await cursor.execute(
                    """
                    SELECT run_id, source_type, target_generation,
                           previous_read_generation,
                           export_cursor, export_watermark, export_complete,
                           expected_count, processed_count, failed_count,
                           skipped_count, tombstoned_count, status, initiated_by
                    FROM knowledge_rebuild_runs
                    ORDER BY started_at DESC, run_id DESC
                    LIMIT %s
                    """,
                    (limit,),
                )
                rows = list(await cursor.fetchall())
        return [_rebuild_from_row(row) for row in rows]

    async def schedule_deletion(
        self,
        *,
        source_type: str,
        source_id: str,
        source_version: int,
        language: str,
        invalidated_at: datetime,
        now: datetime,
    ) -> str:
        """Persist one idempotent exact-version deletion before state advances."""

        deletion_id = new_ulid()
        timestamp = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    INSERT INTO knowledge_deletion_jobs (
                        deletion_id, source_type, source_id, source_version,
                        language, invalidated_at, status, attempt_count,
                        next_attempt_at, claim_owner, claim_expires_at,
                        last_error_code, created_at, updated_at, completed_at
                    )
                    VALUES (
                        %s, %s, %s, %s, %s, %s, 'PENDING', 0,
                        %s, NULL, NULL, NULL, %s, %s, NULL
                    )
                    ON DUPLICATE KEY UPDATE
                        invalidated_at = LEAST(invalidated_at, VALUES(invalidated_at))
                    """,
                    (
                        deletion_id,
                        source_type,
                        source_id,
                        source_version,
                        language,
                        _mysql_datetime(invalidated_at),
                        timestamp,
                        timestamp,
                        timestamp,
                    ),
                )
            await connection.commit()
        return deletion_id

    async def claim_deletions(
        self,
        claim_owner: str,
        now: datetime,
        limit: int = 10,
    ) -> list[KnowledgeDeletionJob]:
        """Claim exact deletion work with the same bounded lease discipline."""

        claimed_at = _mysql_datetime(now)
        claim_expires = _mysql_datetime(
            now + timedelta(seconds=self._settings.job_claim_seconds)
        )
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        UPDATE knowledge_deletion_jobs
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
                            self._settings.job_max_attempts,
                            claimed_at,
                            claimed_at,
                        ),
                    )
                    await cursor.execute(
                        """
                        SELECT deletion_id, source_type, source_id,
                               source_version, language, invalidated_at,
                               attempt_count
                        FROM knowledge_deletion_jobs
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
                        ORDER BY created_at, deletion_id
                        LIMIT %s
                        FOR UPDATE SKIP LOCKED
                        """,
                        (
                            self._settings.job_max_attempts,
                            claimed_at,
                            claimed_at,
                            limit,
                        ),
                    )
                    rows = list(await cursor.fetchall())
                    for row in rows:
                        await cursor.execute(
                            """
                            UPDATE knowledge_deletion_jobs
                            SET status = 'PROCESSING',
                                attempt_count = attempt_count + 1,
                                claim_owner = %s,
                                claim_expires_at = %s,
                                last_error_code = NULL,
                                updated_at = %s
                            WHERE deletion_id = %s
                            """,
                            (
                                claim_owner,
                                claim_expires,
                                claimed_at,
                                row["deletion_id"],
                            ),
                        )
                await connection.commit()
            except BaseException:
                await connection.rollback()
                raise
        return [_deletion_from_row(row) for row in rows]

    async def finish_deletion(
        self,
        deletion_id: str,
        claim_owner: str,
        *,
        succeeded: bool,
        now: datetime,
        error_code: str | None = None,
        retry_at: datetime | None = None,
    ) -> bool:
        """Finish or retry one exact claimed deletion without changing identity."""

        if succeeded:
            status = DeletionStatus.SUCCEEDED.value
            completed_at = _mysql_datetime(now)
            next_attempt_at = _mysql_datetime(now)
        elif retry_at is not None:
            status = DeletionStatus.RETRY_WAIT.value
            completed_at = None
            next_attempt_at = _mysql_datetime(retry_at)
        else:
            status = DeletionStatus.DEAD_LETTER.value
            completed_at = _mysql_datetime(now)
            next_attempt_at = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_deletion_jobs
                    SET status = %s,
                        next_attempt_at = %s,
                        claim_owner = NULL,
                        claim_expires_at = NULL,
                        last_error_code = %s,
                        updated_at = %s,
                        completed_at = %s
                    WHERE deletion_id = %s
                      AND status = 'PROCESSING'
                      AND claim_owner = %s
                    """,
                    (
                        status,
                        next_attempt_at,
                        error_code,
                        _mysql_datetime(now),
                        completed_at,
                        deletion_id,
                        claim_owner,
                    ),
                )
                changed = cursor.rowcount == 1
            await connection.commit()
        return changed

    async def deletion_counts(self) -> dict[str, int]:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT status, COUNT(*)
                    FROM knowledge_deletion_jobs
                    GROUP BY status
                    """
                )
                rows = await cursor.fetchall()
        return {str(status): int(count) for status, count in rows}

    async def rebuild_counts(self) -> dict[str, int]:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT status, COUNT(*)
                    FROM knowledge_rebuild_runs
                    GROUP BY status
                    """
                )
                rows = await cursor.fetchall()
        return {str(status): int(count) for status, count in rows}

    async def retry_ingestion_job(self, job_id: str, now: datetime) -> bool:
        """Move one exact non-processing failed job back to pending."""

        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_ingestion_jobs
                    SET status = 'PENDING',
                        attempt_count = 0,
                        next_attempt_at = %s,
                        last_error_code = NULL,
                        completed_at = NULL,
                        updated_at = %s
                    WHERE job_id = %s
                      AND status IN ('RETRY_WAIT', 'DEAD_LETTER')
                    """,
                    (_mysql_datetime(now), _mysql_datetime(now), job_id),
                )
                changed = cursor.rowcount == 1
            await connection.commit()
        return changed

    async def _transition_rebuild(
        self,
        run_id: str,
        *,
        from_status: RebuildStatus,
        to_status: RebuildStatus,
        now: datetime,
        error_code: str | None = None,
        increment_failed: bool = False,
        promoted: bool = False,
        rolled_back: bool = False,
    ) -> None:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    UPDATE knowledge_rebuild_runs
                    SET status = %s,
                        failed_count = failed_count + %s,
                        last_error_code = %s,
                        updated_at = %s,
                        promoted_at = CASE WHEN %s THEN %s ELSE promoted_at END,
                        rolled_back_at = CASE
                            WHEN %s THEN %s ELSE rolled_back_at
                        END,
                        optimistic_version = optimistic_version + 1
                    WHERE run_id = %s
                      AND status = %s
                    """,
                    (
                        to_status.value,
                        1 if increment_failed else 0,
                        error_code,
                        _mysql_datetime(now),
                        promoted,
                        _mysql_datetime(now),
                        rolled_back,
                        _mysql_datetime(now),
                        run_id,
                        from_status.value,
                    ),
                )
                if cursor.rowcount != 1:
                    raise RuntimeError("REBUILD_STATE_CONFLICT")
            await connection.commit()


def _rebuild_from_row(row: dict[str, object]) -> KnowledgeRebuildRun:
    watermark = row["export_watermark"]
    if watermark is not None and not isinstance(watermark, datetime):
        raise TypeError("export_watermark must be a datetime")
    return KnowledgeRebuildRun(
        run_id=str(row["run_id"]),
        target_generation=str(row["target_generation"]),
        previous_read_generation=str(row["previous_read_generation"]),
        export_cursor=(
            None if row["export_cursor"] is None else str(row["export_cursor"])
        ),
        export_watermark=(
            None if watermark is None else watermark.replace(tzinfo=UTC)
        ),
        export_complete=bool(row["export_complete"]),
        expected_count=int(row["expected_count"]),
        processed_count=int(row["processed_count"]),
        failed_count=int(row["failed_count"]),
        skipped_count=int(row["skipped_count"]),
        tombstoned_count=int(row["tombstoned_count"]),
        status=RebuildStatus(str(row["status"])),
        initiated_by=str(row["initiated_by"]),
        source_type=str(row.get("source_type", "LISTING")),
    )


def _rebuild_source_from_row(
    row: dict[str, object],
) -> KnowledgeRebuildSource:
    watermark = row["export_watermark"]
    if watermark is not None and not isinstance(watermark, datetime):
        raise TypeError("export_watermark must be a datetime")
    return KnowledgeRebuildSource(
        run_id=str(row["run_id"]),
        source_type=str(row["source_type"]),
        sanitizer_version=str(row["sanitizer_version"]),
        chunker_version=str(row["chunker_version"]),
        export_cursor=(
            None if row["export_cursor"] is None else str(row["export_cursor"])
        ),
        export_watermark=(
            None if watermark is None else watermark.replace(tzinfo=UTC)
        ),
        export_complete=bool(row["export_complete"]),
        expected_count=int(row["expected_count"]),
        processed_count=int(row["processed_count"]),
        failed_count=int(row["failed_count"]),
        skipped_count=int(row["skipped_count"]),
        tombstoned_count=int(row["tombstoned_count"]),
    )


def _deletion_from_row(row: dict[str, object]) -> KnowledgeDeletionJob:
    invalidated_at = row["invalidated_at"]
    if not isinstance(invalidated_at, datetime):
        raise TypeError("invalidated_at must be a datetime")
    return KnowledgeDeletionJob(
        deletion_id=str(row["deletion_id"]),
        source_type=str(row["source_type"]),
        source_id=str(row["source_id"]),
        source_version=int(row["source_version"]),
        language=str(row["language"]),
        invalidated_at=invalidated_at.replace(tzinfo=UTC),
        attempt_count=int(row["attempt_count"]) + 1,
    )


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("database timestamps must include a UTC offset")
    return value.astimezone(UTC).replace(tzinfo=None)
