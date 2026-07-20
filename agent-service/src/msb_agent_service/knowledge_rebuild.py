from __future__ import annotations

import argparse
import asyncio
import json
import re
from dataclasses import asdict
from datetime import UTC, datetime
from typing import Awaitable, Callable

from aiokafka import AIOKafkaConsumer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient

from .config import KnowledgeIngestionSettings, Settings
from .embedding_provider import OpenAIEmbeddingProvider
from .knowledge_chunker import (
    CATEGORY_GUIDANCE_CHUNKER_VERSION,
    LISTING_CHUNKER_VERSION,
    CategoryGuidanceChunker,
    ListingKnowledgeChunker,
)
from .knowledge_index import (
    KnowledgeIndexAdmin,
    KnowledgeIndexStatus,
    KnowledgeIndexWriter,
    KnowledgeReadinessStatus,
    close_open_search_client,
)
from .knowledge_index_client import (
    KnowledgeIndexError,
    create_open_search_client,
)
from .knowledge_index_metrics import KnowledgeIndexMetrics
from .knowledge_ingestion import (
    CategoryGuidanceDocumentBuilder,
    ListingKnowledgeDocumentBuilder,
)
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from .knowledge_jobs import KnowledgeJobRepository
from .knowledge_operations import (
    KnowledgeOperationsRepository,
    KnowledgeRebuildRun,
    RebuildStatus,
)
from .knowledge_source_client import KnowledgeSourceClient
from .knowledge_sanitizer import (
    CATEGORY_GUIDANCE_SANITIZER_VERSION,
    LISTING_SANITIZER_VERSION,
)

SAFE_OPERATOR_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{1,99}$")
FIXED_ID_PATTERN = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")


class RebuildGateError(RuntimeError):
    """Reports a bounded promotion gate without exposing source content."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class KafkaLagInspector:
    """Reads committed and end offsets without subscribing to production work."""

    def __init__(
        self,
        settings: KnowledgeIngestionSettings,
        *,
        topic: str | None = None,
        group_id: str | None = None,
    ) -> None:
        self._settings = settings
        self._topic = topic or settings.kafka_topic
        self._group_id = group_id or settings.kafka_group_id

    async def lag(self) -> int:
        common: dict[str, object] = {
            "bootstrap_servers": self._settings.kafka_bootstrap_servers,
            "security_protocol": self._settings.kafka_security_protocol,
        }
        if self._settings.kafka_security_protocol.startswith("SASL_"):
            common["sasl_mechanism"] = "PLAIN"
            common["sasl_plain_username"] = self._settings.kafka_username
            common["sasl_plain_password"] = self._settings.kafka_password
        admin = AIOKafkaAdminClient(
            **common,
            client_id=f"{self._settings.kafka_client_id}-rebuild-admin",
        )
        consumer = AIOKafkaConsumer(
            **common,
            client_id=f"{self._settings.kafka_client_id}-rebuild-end-offsets",
            enable_auto_commit=False,
        )
        await admin.start()
        try:
            await consumer.start()
            try:
                descriptions = await admin.describe_topics(
                    [self._topic]
                )
                description = next(
                    (
                        item
                        for item in descriptions
                        if item.get("topic") == self._topic
                    ),
                    None,
                )
                if (
                    description is None
                    or description.get("error_code", 0) != 0
                ):
                    raise RebuildGateError("KAFKA_TOPIC_UNAVAILABLE")
                topic_partitions = [
                    TopicPartition(
                        self._topic,
                        int(partition["partition"]),
                    )
                    for partition in description.get("partitions", [])
                ]
                if not topic_partitions:
                    raise RebuildGateError("KAFKA_TOPIC_UNAVAILABLE")
                end_offsets = await consumer.end_offsets(topic_partitions)
                committed_offsets = await admin.list_consumer_group_offsets(
                    self._group_id,
                    partitions=topic_partitions,
                )
                lag = 0
                for partition in topic_partitions:
                    committed = committed_offsets.get(partition)
                    committed_value = (
                        0
                        if committed is None or committed.offset < 0
                        else committed.offset
                    )
                    lag += max(
                        0,
                        end_offsets[partition] - committed_value,
                    )
                return lag
            finally:
                await consumer.stop()
        finally:
            await admin.close()


class KnowledgeRebuildService:
    """Coordinates resumable listing rebuilds and explicit promotion gates."""

    def __init__(
        self,
        *,
        settings: Settings,
        jobs: KnowledgeJobRepository,
        operations: KnowledgeOperationsRepository,
        source_client: KnowledgeSourceClient,
        builder: ListingKnowledgeDocumentBuilder,
        index_admin: KnowledgeIndexAdmin,
        exact_writer_factory: Callable[[str], KnowledgeIndexWriter],
        lag_reader: Callable[[], Awaitable[int]],
        category_builder: CategoryGuidanceDocumentBuilder | None = None,
        category_lag_reader: Callable[[], Awaitable[int]] | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._settings = settings
        self._jobs = jobs
        self._operations = operations
        self._source_client = source_client
        self._builder = builder
        self._index_admin = index_admin
        self._exact_writer_factory = exact_writer_factory
        self._lag_reader = lag_reader
        self._category_builder = category_builder
        self._category_lag_reader = category_lag_reader
        self._clock = clock or (lambda: datetime.now(UTC))

    async def start_or_resume(
        self,
        *,
        operator: str,
        run_id: str | None = None,
        page_limit: int = 100,
        include_category: bool = False,
    ) -> KnowledgeRebuildRun:
        """Create or resume one rebuild, checkpointing only completed pages."""

        _validate_operator(operator)
        if not 1 <= page_limit <= 200:
            raise ValueError("page_limit must be between 1 and 200")
        if run_id is not None:
            _validate_fixed_id(run_id, "run_id")
            run = await self._operations.get_rebuild(run_id)
            if run is None or run.status != RebuildStatus.RUNNING:
                raise RebuildGateError("REBUILD_NOT_RESUMABLE")
        else:
            run = await self._start_or_recover_run(operator, include_category)
        writer = self._exact_writer_factory(run.target_generation)
        if run.source_type == "PUBLIC_KNOWLEDGE":
            return await self._resume_public_knowledge(
                run,
                writer,
                page_limit,
            )
        cursor = run.export_cursor
        watermark = run.export_watermark
        while not run.export_complete:
            try:
                page, _ = await self._source_client.fetch_listing_export(
                    cursor=cursor,
                    limit=page_limit,
                )
                if watermark is not None and page.export_watermark != watermark:
                    raise RebuildGateError("REBUILD_WATERMARK_CHANGED")
                watermark = page.export_watermark
                processed = 0
                skipped = 0
                tombstoned = 0
                for source in page.items:
                    outcome = await self._load_source(run, source, writer)
                    if outcome == "PROCESSED":
                        processed += 1
                    elif outcome == "TOMBSTONED":
                        tombstoned += 1
                    else:
                        skipped += 1
                complete = not page.has_more
                await self._operations.checkpoint_rebuild_page(
                    run.run_id,
                    next_cursor=page.next_cursor,
                    export_watermark=page.export_watermark,
                    expected_delta=len(page.items),
                    processed_delta=processed,
                    skipped_delta=skipped,
                    tombstoned_delta=tombstoned,
                    complete=complete,
                    now=self._clock(),
                )
                cursor = page.next_cursor
                run = await self._require_run(run.run_id)
            except Exception as error:
                if not bool(getattr(error, "retryable", False)):
                    await self._operations.mark_rebuild_failed(
                        run.run_id,
                        _safe_error_code(error),
                        self._clock(),
                    )
                raise
        return run

    async def validate(self, run_id: str) -> dict[str, object]:
        """Apply all count, mapping, queue, deletion, watermark, and lag gates."""

        _validate_fixed_id(run_id, "run_id")
        run = await self._require_run(run_id)
        if run.status not in {
            RebuildStatus.RUNNING,
            RebuildStatus.READY_TO_PROMOTE,
        }:
            raise RebuildGateError("REBUILD_NOT_VALIDATABLE")
        if not run.export_complete or (
            run.source_type == "LISTING" and run.export_watermark is None
        ):
            raise RebuildGateError("REBUILD_EXPORT_INCOMPLETE")
        if run.source_type == "PUBLIC_KNOWLEDGE":
            children = await self._operations.get_rebuild_sources(run.run_id)
            if (
                {child.source_type for child in children}
                != {"LISTING", "CATEGORY_GUIDANCE"}
                or any(
                    not child.export_complete
                    or child.export_watermark is None
                    or child.failed_count != 0
                    for child in children
                )
            ):
                raise RebuildGateError("REBUILD_SOURCE_SET_INCOMPLETE")
        elif self._settings.knowledge_ingestion.category_guidance_retrieval_enabled:
            raise RebuildGateError("REBUILD_SOURCE_SET_INCOMPLETE")
        if run.failed_count != 0:
            raise RebuildGateError("REBUILD_HAS_FAILURES")
        if (
            run.expected_count
            != run.processed_count + run.skipped_count + run.tombstoned_count
        ):
            raise RebuildGateError("REBUILD_COUNT_CHECKPOINT_MISMATCH")
        status = await self._index_admin.status()
        self._validate_index_status(status, run)
        writer = self._exact_writer_factory(run.target_generation)
        await writer.refresh()
        source_count = await writer.source_count()
        document_count = await writer.count()
        if source_count != run.processed_count:
            raise RebuildGateError("REBUILD_SOURCE_COUNT_MISMATCH")
        if run.processed_count > 0 and document_count < run.processed_count:
            raise RebuildGateError("REBUILD_DOCUMENT_COUNT_MISMATCH")
        job_counts = await self._jobs.job_counts()
        nonterminal = sum(
            job_counts.get(state, 0)
            for state in ("PENDING", "PROCESSING", "RETRY_WAIT")
        )
        if nonterminal != 0 or job_counts.get("DEAD_LETTER", 0) != 0:
            raise RebuildGateError("INGESTION_JOBS_NOT_DRAINED")
        if await self._jobs.oldest_pending_age_seconds(self._clock()) != 0:
            raise RebuildGateError("INGESTION_PENDING_AGE_NONZERO")
        deletion_counts = await self._operations.deletion_counts()
        deletion_backlog = sum(
            deletion_counts.get(state, 0)
            for state in ("PENDING", "PROCESSING", "RETRY_WAIT", "DEAD_LETTER")
        )
        if deletion_backlog != 0:
            raise RebuildGateError("DELETION_JOBS_NOT_DRAINED")
        lag = await self._lag_reader()
        if lag != 0:
            raise RebuildGateError("KAFKA_LAG_NONZERO")
        category_lag = 0
        if run.source_type == "PUBLIC_KNOWLEDGE":
            if self._category_lag_reader is None:
                raise RebuildGateError("CATEGORY_KAFKA_LAG_UNAVAILABLE")
            category_lag = await self._category_lag_reader()
            if category_lag != 0:
                raise RebuildGateError("CATEGORY_KAFKA_LAG_NONZERO")
        if run.status == RebuildStatus.RUNNING:
            await self._operations.mark_ready_to_promote(
                run.run_id,
                self._clock(),
            )
        return {
            "runId": run.run_id,
            "status": RebuildStatus.READY_TO_PROMOTE.value,
            "targetGeneration": run.target_generation,
            "sourceCount": source_count,
            "documentCount": document_count,
            "kafkaLag": lag,
            "categoryKafkaLag": category_lag,
            "nonterminalJobs": nonterminal,
            "deletionBacklog": deletion_backlog,
        }

    async def promote(self, run_id: str) -> KnowledgeIndexStatus:
        """Re-run gates, atomically move reads, then record promotion."""

        await self.validate(run_id)
        run = await self._require_run(run_id)
        if run.status != RebuildStatus.READY_TO_PROMOTE:
            raise RebuildGateError("REBUILD_NOT_READY_TO_PROMOTE")
        current = await self._index_admin.status()
        if current.read_index == run.target_generation:
            result = current
        else:
            result = await self._index_admin.promote(run.target_generation)
        await self._operations.mark_promoted(run.run_id, self._clock())
        return result

    async def rollback(self, run_id: str) -> KnowledgeIndexStatus:
        """Move reads to the recorded prior generation and retain mirroring."""

        run = await self._require_run(run_id)
        if run.status != RebuildStatus.PROMOTED:
            raise RebuildGateError("REBUILD_NOT_PROMOTED")
        result = await self._index_admin.rollback(
            run.previous_read_generation
        )
        await self._operations.mark_rolled_back(run.run_id, self._clock())
        return result

    async def status(self) -> dict[str, object]:
        index = await self._index_admin.status()
        return {
            "index": asdict(index),
            "jobs": await self._jobs.job_counts(),
            "deletions": await self._operations.deletion_counts(),
            "rebuilds": [
                asdict(run) for run in await self._operations.list_rebuilds()
            ],
        }

    async def _start_or_recover_run(
        self,
        operator: str,
        include_category: bool,
    ) -> KnowledgeRebuildRun:
        status = await self._index_admin.status()
        if (
            status.status != KnowledgeReadinessStatus.READY
            or status.read_index is None
            or status.write_index is None
        ):
            raise RebuildGateError("KNOWLEDGE_INDEX_NOT_READY")
        if status.read_index != status.write_index:
            existing = await self._operations.get_rebuild_by_target(
                status.write_index
            )
            if existing is not None:
                if existing.status != RebuildStatus.RUNNING:
                    raise RebuildGateError("DIVERGENT_ALIAS_RUN_NOT_RUNNING")
                return existing
            target = status.write_index
        else:
            target = await self._index_admin.create_generation()
        return await self._operations.create_rebuild(
            target_generation=target,
            previous_read_generation=status.read_index,
            embedding_provider=self._settings.knowledge.embedding_provider or "",
            embedding_model=self._settings.knowledge.embedding_model or "",
            embedding_dimensions=(
                self._settings.knowledge.embedding_dimensions or 0
            ),
            chunker_version=(
                "public-knowledge-pipeline-v1"
                if include_category
                else LISTING_CHUNKER_VERSION
            ),
            initiated_by=operator,
            now=self._clock(),
            source_type=(
                "PUBLIC_KNOWLEDGE" if include_category else "LISTING"
            ),
            rebuild_sources=(
                (
                    (
                        "LISTING",
                        LISTING_SANITIZER_VERSION,
                        LISTING_CHUNKER_VERSION,
                    ),
                    (
                        "CATEGORY_GUIDANCE",
                        CATEGORY_GUIDANCE_SANITIZER_VERSION,
                        CATEGORY_GUIDANCE_CHUNKER_VERSION,
                    ),
                )
                if include_category
                else ()
            ),
        )

    async def _resume_public_knowledge(
        self,
        run: KnowledgeRebuildRun,
        writer: KnowledgeIndexWriter,
        page_limit: int,
    ) -> KnowledgeRebuildRun:
        """Resume each authoritative export under one target generation."""

        if self._category_builder is None:
            raise RebuildGateError("CATEGORY_BUILDER_UNAVAILABLE")
        for source_type in ("LISTING", "CATEGORY_GUIDANCE"):
            children = await self._operations.get_rebuild_sources(run.run_id)
            child = next(
                (item for item in children if item.source_type == source_type),
                None,
            )
            if child is None:
                raise RebuildGateError("REBUILD_SOURCE_SET_INCOMPLETE")
            cursor = child.export_cursor
            watermark = child.export_watermark
            while not child.export_complete:
                try:
                    if source_type == "LISTING":
                        page, _ = await self._source_client.fetch_listing_export(
                            cursor=cursor,
                            limit=page_limit,
                        )
                        builder = self._builder
                    else:
                        page, _ = (
                            await self._source_client.fetch_category_guidance_export(
                                cursor=cursor,
                                limit=page_limit,
                            )
                        )
                        builder = self._category_builder
                    if watermark is not None and page.export_watermark != watermark:
                        raise RebuildGateError("REBUILD_WATERMARK_CHANGED")
                    watermark = page.export_watermark
                    processed = 0
                    skipped = 0
                    tombstoned = 0
                    for source in page.items:
                        outcome = await self._load_source_for_type(
                            run,
                            source,
                            writer,
                            source_type=source_type,
                            builder=builder,
                        )
                        if outcome == "PROCESSED":
                            processed += 1
                        elif outcome == "TOMBSTONED":
                            tombstoned += 1
                        else:
                            skipped += 1
                    await self._operations.checkpoint_rebuild_source_page(
                        run.run_id,
                        source_type,
                        next_cursor=page.next_cursor,
                        export_watermark=page.export_watermark,
                        expected_delta=len(page.items),
                        processed_delta=processed,
                        skipped_delta=skipped,
                        tombstoned_delta=tombstoned,
                        complete=not page.has_more,
                        now=self._clock(),
                    )
                    cursor = page.next_cursor
                    children = await self._operations.get_rebuild_sources(
                        run.run_id
                    )
                    child = next(
                        item
                        for item in children
                        if item.source_type == source_type
                    )
                    run = await self._require_run(run.run_id)
                except Exception as error:
                    if not bool(getattr(error, "retryable", False)):
                        await self._operations.mark_rebuild_failed(
                            run.run_id,
                            _safe_error_code(error),
                            self._clock(),
                        )
                    raise
        return run

    async def _load_source(
        self,
        run: KnowledgeRebuildRun,
        source: object,
        writer: KnowledgeIndexWriter,
    ) -> str:
        return await self._load_source_for_type(
            run,
            source,
            writer,
            source_type="LISTING",
            builder=self._builder,
        )

    async def _load_source_for_type(
        self,
        run: KnowledgeRebuildRun,
        source: object,
        writer: KnowledgeIndexWriter,
        *,
        source_type: str,
        builder: ListingKnowledgeDocumentBuilder | CategoryGuidanceDocumentBuilder,
    ) -> str:
        """Load one source with monotonic checks before and after its write."""

        source_id = str(getattr(source, "source_id"))
        source_version = int(str(getattr(source, "source_version")))
        language = str(getattr(source, "language"))
        before = await self._jobs.get_source_state(
            source_type,
            source_id,
            language,
        )
        stale = _state_supersedes(before, source_version)
        if stale is not None:
            return stale
        _, _, _, documents = await builder.build(
            source,  # type: ignore[arg-type]
            correlation_id=run.run_id,
        )
        await writer.upsert(documents)
        after = await self._jobs.get_source_state(
            source_type,
            source_id,
            language,
        )
        stale = _state_supersedes(after, source_version)
        if stale is not None:
            await writer.delete_source(
                source_type,
                source_id,
                str(source_version),
            )
            return stale
        return "PROCESSED"

    async def _require_run(self, run_id: str) -> KnowledgeRebuildRun:
        run = await self._operations.get_rebuild(run_id)
        if run is None:
            raise RebuildGateError("REBUILD_NOT_FOUND")
        return run

    def _validate_index_status(
        self,
        status: KnowledgeIndexStatus,
        run: KnowledgeRebuildRun,
    ) -> None:
        if status.status != KnowledgeReadinessStatus.READY:
            raise RebuildGateError("KNOWLEDGE_INDEX_NOT_READY")
        if status.write_index != run.target_generation:
            raise RebuildGateError("REBUILD_TARGET_NOT_WRITE_GENERATION")


async def _run_cli(arguments: argparse.Namespace) -> int:
    resources = await _create_resources()
    service, close = resources
    try:
        if arguments.command == "status":
            result: object = await service.status()
        elif arguments.command == "retry":
            _validate_fixed_id(arguments.job_id, "job_id")
            changed = await service._operations.retry_ingestion_job(
                arguments.job_id,
                datetime.now(UTC),
            )
            result = {"jobId": arguments.job_id, "status": "REQUEUED" if changed else "UNCHANGED"}
        elif arguments.command == "rebuild-listings":
            run = await service.start_or_resume(
                operator=arguments.operator,
                run_id=arguments.run_id,
                page_limit=arguments.page_limit,
            )
            result = asdict(run)
        elif arguments.command == "rebuild-public-knowledge":
            run = await service.start_or_resume(
                operator=arguments.operator,
                run_id=arguments.run_id,
                page_limit=arguments.page_limit,
                include_category=True,
            )
            result = asdict(run)
        elif arguments.command == "validate-rebuild":
            result = await service.validate(arguments.run_id)
        elif arguments.command == "promote":
            result = asdict(await service.promote(arguments.run_id))
        elif arguments.command == "rollback":
            result = asdict(await service.rollback(arguments.run_id))
        else:
            raise ValueError("unsupported command")
        print(json.dumps(result, default=str, sort_keys=True))
        return 0
    except BaseException as error:
        print(
            json.dumps(
                {"status": "FAILED", "errorCode": _safe_error_code(error)},
                sort_keys=True,
            )
        )
        return 2
    finally:
        await close()


async def _create_resources() -> tuple[
    KnowledgeRebuildService,
    Callable[[], Awaitable[None]],
]:
    settings = Settings.from_env()
    if (
        not settings.knowledge.enabled
        or not settings.knowledge_ingestion.enabled
        or settings.openai_api_key is None
        or settings.knowledge.embedding_model is None
        or settings.knowledge.embedding_dimensions is None
    ):
        raise ValueError("REBUILD_CONFIGURATION_INCOMPLETE")
    jobs = await KnowledgeJobRepository.create(settings.knowledge_ingestion)
    operations = await KnowledgeOperationsRepository.create(
        settings.knowledge_ingestion
    )
    source_client = KnowledgeSourceClient(settings.knowledge_ingestion)
    open_search_client = create_open_search_client(settings.knowledge)
    index_metrics = KnowledgeIndexMetrics()
    ingestion_metrics = KnowledgeIngestionMetrics(index_metrics.registry)
    embedding = OpenAIEmbeddingProvider(
        api_key=settings.openai_api_key,
        model=settings.knowledge.embedding_model,
        dimensions=settings.knowledge.embedding_dimensions,
        timeout_seconds=settings.openai_timeout_seconds,
        max_retries=settings.openai_max_retries,
        maximum_inputs=settings.knowledge_ingestion.embedding_max_inputs,
        maximum_input_tokens=(
            settings.knowledge_ingestion.embedding_max_input_tokens
        ),
        maximum_total_tokens=(
            settings.knowledge_ingestion.embedding_max_total_tokens
        ),
        metrics=ingestion_metrics,
    )
    chunker = ListingKnowledgeChunker(
        model=settings.knowledge.embedding_model,
        maximum_tokens=settings.knowledge_ingestion.chunk_max_tokens,
        overlap_tokens=settings.knowledge_ingestion.chunk_overlap_tokens,
        maximum_chunks=settings.knowledge_ingestion.chunk_max_count,
    )
    builder = ListingKnowledgeDocumentBuilder(
        chunker=chunker,
        embedding_provider=embedding,
    )
    category_builder = CategoryGuidanceDocumentBuilder(
        chunker=CategoryGuidanceChunker(
            model=settings.knowledge.embedding_model,
            maximum_tokens=settings.knowledge_ingestion.chunk_max_tokens,
            overlap_tokens=settings.knowledge_ingestion.chunk_overlap_tokens,
            maximum_chunks=settings.knowledge_ingestion.chunk_max_count,
        ),
        embedding_provider=embedding,
    )
    index_admin = KnowledgeIndexAdmin(
        open_search_client,
        settings.knowledge,
        index_metrics,
    )
    lag_inspector = KafkaLagInspector(settings.knowledge_ingestion)
    category_lag_inspector = KafkaLagInspector(
        settings.knowledge_ingestion,
        topic=settings.knowledge_ingestion.category_guidance_kafka_topic,
        group_id=settings.knowledge_ingestion.category_guidance_kafka_group_id,
    )
    service = KnowledgeRebuildService(
        settings=settings,
        jobs=jobs,
        operations=operations,
        source_client=source_client,
        builder=builder,
        index_admin=index_admin,
        exact_writer_factory=lambda target: KnowledgeIndexWriter(
            open_search_client,
            settings.knowledge,
            index_metrics,
            target_index=target,
        ),
        lag_reader=lag_inspector.lag,
        category_builder=category_builder,
        category_lag_reader=category_lag_inspector.lag,
    )

    async def close() -> None:
        await embedding.close()
        await close_open_search_client(open_search_client)
        await source_client.close()
        await operations.close()
        await jobs.close()

    return service, close


def _state_supersedes(state: object | None, source_version: int) -> str | None:
    if state is None:
        return None
    latest = int(getattr(state, "latest_observed_version"))
    current_state = str(getattr(state, "state"))
    if latest > source_version:
        return "TOMBSTONED" if current_state == "TOMBSTONED" else "SKIPPED"
    if latest == source_version and current_state == "TOMBSTONED":
        return "TOMBSTONED"
    return None


def _validate_operator(value: str) -> None:
    if not SAFE_OPERATOR_PATTERN.fullmatch(value):
        raise ValueError("operator identity is invalid")


def _validate_fixed_id(value: str, name: str) -> None:
    if not FIXED_ID_PATTERN.fullmatch(value):
        raise ValueError(f"{name} must be a canonical 26-character ID")


def _safe_error_code(error: BaseException) -> str:
    code = getattr(error, "code", None)
    if code is not None:
        return str(getattr(code, "value", code))
    if isinstance(error, RebuildGateError):
        return error.code
    if isinstance(error, ValueError):
        return "REBUILD_INPUT_INVALID"
    if isinstance(error, KnowledgeIndexError):
        return error.code.value
    return "REBUILD_INTERNAL_ERROR"


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Operate knowledge ingestion")
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("status")
    retry = commands.add_parser("retry")
    retry.add_argument("--job-id", required=True)
    rebuild = commands.add_parser("rebuild-listings")
    rebuild.add_argument("--operator", required=True)
    rebuild.add_argument("--run-id")
    rebuild.add_argument("--page-limit", type=int, default=100)
    public_rebuild = commands.add_parser("rebuild-public-knowledge")
    public_rebuild.add_argument("--operator", required=True)
    public_rebuild.add_argument("--run-id")
    public_rebuild.add_argument("--page-limit", type=int, default=100)
    for command in ("validate-rebuild", "promote", "rollback"):
        item = commands.add_parser(command)
        item.add_argument("--run-id", required=True)
    return result


def main() -> None:
    raise SystemExit(asyncio.run(_run_cli(parser().parse_args())))
