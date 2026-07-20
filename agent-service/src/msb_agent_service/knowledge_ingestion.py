from __future__ import annotations

from datetime import UTC, datetime
from typing import Callable, Protocol, Sequence

from .embedding_provider import (
    EmbeddingBatchResult,
    EmbeddingProvider,
    EmbeddingProviderError,
)
from .knowledge_chunker import (
    CATEGORY_GUIDANCE_CHUNKER_VERSION,
    LISTING_CHUNKER_VERSION,
    CategoryGuidanceChunker,
    ListingKnowledgeChunker,
)
from .knowledge_index import KnowledgeIndexWriter
from .knowledge_index_client import KnowledgeIndexError
from .knowledge_index_documents import KnowledgeChunkDocument
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics
from .knowledge_jobs import (
    KnowledgeIngestionJob,
    KnowledgeJobRepository,
    KnowledgeSourceState,
    SourceStateApplyResult,
)
from .knowledge_operations import KnowledgeOperationsRepository
from .knowledge_sanitizer import (
    KnowledgeContentError,
    sanitize_category_guidance,
    sanitize_listing,
    verify_category_guidance_source_hash,
    verify_listing_source_hash,
)
from .knowledge_source_client import (
    CategoryGuidanceSource,
    KnowledgeSourceClient,
    KnowledgeSourceError,
    ListingKnowledgeSource,
)


class KnowledgeProcessingError(RuntimeError):
    """Carries one safe processing result into bounded job retry policy."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class KnowledgeWriter(Protocol):
    async def upsert(
        self,
        documents: Sequence[KnowledgeChunkDocument],
        *,
        refresh: bool = False,
    ) -> object: ...

    async def invalidate_source(
        self,
        source_type: str,
        source_id: str,
        source_version: str,
        invalidated_at: datetime,
        *,
        refresh: bool = False,
    ) -> int: ...

    async def delete_source(
        self,
        source_type: str,
        source_id: str,
        source_version: str,
        *,
        refresh: bool = False,
    ) -> int: ...

    async def delete_chunks(
        self,
        chunk_ids: Sequence[str],
        *,
        refresh: bool = False,
    ) -> object: ...


class ListingKnowledgeDocumentBuilder:
    """Builds the same deterministic listing documents for live and rebuild paths."""

    def __init__(
        self,
        *,
        chunker: ListingKnowledgeChunker,
        embedding_provider: EmbeddingProvider,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._chunker = chunker
        self._embedding_provider = embedding_provider
        self._clock = clock or (lambda: datetime.now(UTC))

    async def build(
        self,
        source: ListingKnowledgeSource,
        *,
        correlation_id: str,
    ) -> tuple[
        str,
        EmbeddingBatchResult,
        datetime,
        tuple[KnowledgeChunkDocument, ...],
    ]:
        """Verify, sanitize, chunk, embed, and validate one active source."""

        content_hash = verify_listing_source_hash(source)
        if source.lifecycle != "ACTIVE" or source.effective_from is None:
            raise KnowledgeProcessingError(
                "SOURCE_REBUILD_LIFECYCLE_INVALID",
                retryable=False,
            )
        sanitized = sanitize_listing(source)
        chunks = self._chunker.chunk(
            sanitized,
            source_id=source.source_id,
            source_version=int(source.source_version),
            language=source.language,
            content_hash=content_hash,
        )
        embedding = await self._embedding_provider.embed(
            [chunk.text for chunk in chunks],
            correlation_id=correlation_id,
        )
        indexed_at = self._clock()
        documents = tuple(
            KnowledgeChunkDocument(
                chunkId=chunk.chunk_id,
                sourceType="LISTING",
                sourceId=source.source_id,
                sourceVersion=source.source_version,
                contentHash=content_hash,
                listingId=source.source_id,
                visibility="PUBLIC",
                language=source.language,
                effectiveFrom=source.effective_from,
                indexedAt=indexed_at,
                ordinal=chunk.ordinal,
                sectionLabel=chunk.section_label,
                text=chunk.text,
                embedding=list(embedding.vectors[index]),
            )
            for index, chunk in enumerate(chunks)
        )
        return content_hash, embedding, indexed_at, documents


class CategoryGuidanceDocumentBuilder:
    """Builds deterministic category documents for live and rebuild paths."""

    def __init__(
        self,
        *,
        chunker: CategoryGuidanceChunker,
        embedding_provider: EmbeddingProvider,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._chunker = chunker
        self._embedding_provider = embedding_provider
        self._clock = clock or (lambda: datetime.now(UTC))

    async def build(
        self,
        source: CategoryGuidanceSource,
        *,
        correlation_id: str,
    ) -> tuple[
        str,
        EmbeddingBatchResult,
        datetime,
        tuple[KnowledgeChunkDocument, ...],
    ]:
        """Verify, sanitize, chunk, embed, and validate one active source."""

        content_hash = verify_category_guidance_source_hash(source)
        if source.lifecycle != "ACTIVE" or source.effective_from is None:
            raise KnowledgeProcessingError(
                "SOURCE_REBUILD_LIFECYCLE_INVALID",
                retryable=False,
            )
        sanitized = sanitize_category_guidance(source)
        chunks = self._chunker.chunk(
            sanitized,
            source_id=source.source_id,
            source_version=int(source.source_version),
            language=source.language,
            content_hash=content_hash,
        )
        embedding = await self._embedding_provider.embed(
            [chunk.text for chunk in chunks],
            correlation_id=correlation_id,
        )
        indexed_at = self._clock()
        documents = tuple(
            KnowledgeChunkDocument(
                chunkId=chunk.chunk_id,
                sourceType="CATEGORY_GUIDANCE",
                sourceId=source.source_id,
                sourceVersion=source.source_version,
                contentHash=content_hash,
                visibility="PUBLIC",
                language=source.language,
                effectiveFrom=source.effective_from,
                indexedAt=indexed_at,
                ordinal=chunk.ordinal,
                sectionLabel=chunk.section_label,
                text=chunk.text,
                embedding=list(embedding.vectors[index]),
            )
            for index, chunk in enumerate(chunks)
        )
        return content_hash, embedding, indexed_at, documents


class ListingKnowledgeProcessor:
    """Turns one durable listing job into monotonic derived knowledge state."""

    def __init__(
        self,
        *,
        repository: KnowledgeJobRepository,
        source_client: KnowledgeSourceClient,
        operations_repository: KnowledgeOperationsRepository,
        chunker: ListingKnowledgeChunker,
        embedding_provider: EmbeddingProvider,
        writer: KnowledgeIndexWriter,
        metrics: KnowledgeIngestionMetrics,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._repository = repository
        self._source_client = source_client
        self._operations_repository = operations_repository
        self._chunker = chunker
        self._embedding_provider = embedding_provider
        self._writer: KnowledgeWriter = writer
        self._metrics = metrics
        self._clock = clock or (lambda: datetime.now(UTC))
        self._builder = ListingKnowledgeDocumentBuilder(
            chunker=chunker,
            embedding_provider=embedding_provider,
            clock=self._clock,
        )

    async def __call__(self, job: KnowledgeIngestionJob) -> None:
        if job.source_type != "LISTING":
            raise KnowledgeProcessingError(
                "SOURCE_TYPE_UNSUPPORTED",
                retryable=False,
            )
        try:
            source, duration = await self._source_client.fetch_listing(
                job.source_id,
                job.source_version,
            )
            self._metrics.record_source_fetch("success", duration)
            self._validate_source_reference(job, source)
            if source.lifecycle == "INVALIDATED":
                await self._process_tombstone(job, source)
            else:
                await self._process_active(job, source)
        except KnowledgeProcessingError:
            raise
        except KnowledgeSourceError as error:
            self._metrics.record_source_fetch(error.code.value)
            raise
        except KnowledgeContentError as error:
            raise KnowledgeProcessingError(
                error.code,
                retryable=False,
            ) from error
        except EmbeddingProviderError as error:
            raise KnowledgeProcessingError(
                error.code,
                retryable=error.retryable,
            ) from error
        except KnowledgeIndexError as error:
            raise KnowledgeProcessingError(
                error.code.value,
                retryable=error.retryable,
            ) from error

    async def _process_active(
        self,
        job: KnowledgeIngestionJob,
        source: ListingKnowledgeSource,
    ) -> None:
        content_hash = verify_listing_source_hash(source)
        state = await self._repository.get_source_state(
            job.source_type,
            job.source_id,
            job.language,
        )
        if await self._can_skip_active(job, state, content_hash):
            return

        built_hash, embedding, indexed_at, documents = await self._builder.build(
            source,
            correlation_id=job.event_id,
        )
        if built_hash != content_hash:
            raise KnowledgeProcessingError(
                "SOURCE_CONTENT_HASH_MISMATCH",
                retryable=False,
            )
        await self._writer.upsert(documents)
        if job.superseded_version is not None:
            await self._invalidate_and_schedule_version(
                job,
                job.superseded_version,
                indexed_at,
            )
        decision = await self._repository.apply_active_source_state(
            job,
            content_hash=content_hash,
            chunker_version=LISTING_CHUNKER_VERSION,
            embedding_provider=embedding.provider,
            embedding_model=embedding.model,
            embedding_dimensions=embedding.dimensions,
            indexed_at=indexed_at,
        )
        if decision == SourceStateApplyResult.CONFLICT:
            await self._writer.delete_chunks(
                [document.chunk_id for document in documents]
            )
            raise KnowledgeProcessingError(
                "SOURCE_VERSION_CONFLICT",
                retryable=False,
            )
        if decision == SourceStateApplyResult.STALE:
            await self._writer.delete_source(
                "LISTING",
                job.source_id,
                str(job.source_version),
            )

    async def _process_tombstone(
        self,
        job: KnowledgeIngestionJob,
        source: ListingKnowledgeSource,
    ) -> None:
        if source.invalidated_at is None or job.superseded_version is None:
            raise KnowledgeProcessingError(
                "SOURCE_TOMBSTONE_INVALID",
                retryable=False,
            )
        await self._invalidate_and_schedule_version(
            job,
            job.superseded_version,
            source.invalidated_at,
        )
        decision = await self._repository.apply_tombstone_source_state(
            job,
            invalidated_at=source.invalidated_at,
        )
        if decision == SourceStateApplyResult.CONFLICT:
            raise KnowledgeProcessingError(
                "SOURCE_VERSION_CONFLICT",
                retryable=False,
            )

    async def _can_skip_active(
        self,
        job: KnowledgeIngestionJob,
        state: KnowledgeSourceState | None,
        content_hash: str,
    ) -> bool:
        if state is None:
            return False
        if state.latest_observed_version > job.source_version:
            await self._writer.delete_source(
                "LISTING",
                job.source_id,
                str(job.source_version),
            )
            return True
        if state.latest_observed_version < job.source_version:
            return False
        if (
            state.state == "ACTIVE"
            and state.latest_indexed_version == job.source_version
            and state.latest_content_hash == content_hash
            and state.chunker_version == LISTING_CHUNKER_VERSION
            and state.embedding_provider == self._embedding_provider.provider
            and state.embedding_model == self._embedding_provider.model
            and state.embedding_dimensions == self._embedding_provider.dimensions
        ):
            return True
        raise KnowledgeProcessingError(
            "SOURCE_VERSION_CONFLICT",
            retryable=False,
        )

    async def _invalidate_and_schedule_version(
        self,
        job: KnowledgeIngestionJob,
        source_version: int,
        invalidated_at: datetime,
    ) -> None:
        await self._writer.invalidate_source(
            "LISTING",
            job.source_id,
            str(source_version),
            invalidated_at,
        )
        await self._operations_repository.schedule_deletion(
            source_type="LISTING",
            source_id=job.source_id,
            source_version=source_version,
            language=job.language,
            invalidated_at=invalidated_at,
            now=self._clock(),
        )

    def _validate_source_reference(
        self,
        job: KnowledgeIngestionJob,
        source: ListingKnowledgeSource,
    ) -> None:
        if (
            source.source_type != job.source_type
            or source.source_id != job.source_id
            or int(source.source_version) != job.source_version
            or source.language != job.language
            or source.lifecycle != job.lifecycle
            or (
                None
                if source.supersedes_version is None
                else int(source.supersedes_version)
            )
            != job.superseded_version
        ):
            raise KnowledgeProcessingError(
                "SOURCE_REFERENCE_MISMATCH",
                retryable=False,
            )


class CategoryGuidanceProcessor:
    """Turns one durable category job into monotonic derived knowledge state."""

    def __init__(
        self,
        *,
        repository: KnowledgeJobRepository,
        source_client: KnowledgeSourceClient,
        operations_repository: KnowledgeOperationsRepository,
        chunker: CategoryGuidanceChunker,
        embedding_provider: EmbeddingProvider,
        writer: KnowledgeIndexWriter,
        metrics: KnowledgeIngestionMetrics,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._repository = repository
        self._source_client = source_client
        self._operations_repository = operations_repository
        self._embedding_provider = embedding_provider
        self._writer: KnowledgeWriter = writer
        self._metrics = metrics
        self._clock = clock or (lambda: datetime.now(UTC))
        self._builder = CategoryGuidanceDocumentBuilder(
            chunker=chunker,
            embedding_provider=embedding_provider,
            clock=self._clock,
        )

    async def __call__(self, job: KnowledgeIngestionJob) -> None:
        if job.source_type != "CATEGORY_GUIDANCE":
            raise KnowledgeProcessingError(
                "SOURCE_TYPE_UNSUPPORTED",
                retryable=False,
            )
        try:
            source, duration = await self._source_client.fetch_category_guidance(
                job.source_id,
                job.language,
                job.source_version,
            )
            self._metrics.record_source_fetch("success", duration)
            self._validate_source_reference(job, source)
            if source.lifecycle == "INVALIDATED":
                await self._process_tombstone(job, source)
            else:
                await self._process_active(job, source)
        except KnowledgeProcessingError:
            raise
        except KnowledgeSourceError as error:
            self._metrics.record_source_fetch(error.code.value)
            raise
        except KnowledgeContentError as error:
            raise KnowledgeProcessingError(
                error.code,
                retryable=False,
            ) from error
        except EmbeddingProviderError as error:
            raise KnowledgeProcessingError(
                error.code,
                retryable=error.retryable,
            ) from error
        except KnowledgeIndexError as error:
            raise KnowledgeProcessingError(
                error.code.value,
                retryable=error.retryable,
            ) from error

    async def _process_active(
        self,
        job: KnowledgeIngestionJob,
        source: CategoryGuidanceSource,
    ) -> None:
        content_hash = verify_category_guidance_source_hash(source)
        state = await self._repository.get_source_state(
            job.source_type,
            job.source_id,
            job.language,
        )
        if await self._can_skip_active(job, state, content_hash):
            return
        built_hash, embedding, indexed_at, documents = await self._builder.build(
            source,
            correlation_id=job.event_id,
        )
        if built_hash != content_hash:
            raise KnowledgeProcessingError(
                "SOURCE_CONTENT_HASH_MISMATCH",
                retryable=False,
            )
        await self._writer.upsert(documents)
        if job.superseded_version is not None:
            await self._invalidate_and_schedule_version(
                job,
                job.superseded_version,
                indexed_at,
            )
        decision = await self._repository.apply_active_source_state(
            job,
            content_hash=content_hash,
            chunker_version=CATEGORY_GUIDANCE_CHUNKER_VERSION,
            embedding_provider=embedding.provider,
            embedding_model=embedding.model,
            embedding_dimensions=embedding.dimensions,
            indexed_at=indexed_at,
        )
        if decision == SourceStateApplyResult.CONFLICT:
            await self._writer.delete_chunks(
                [document.chunk_id for document in documents]
            )
            raise KnowledgeProcessingError(
                "SOURCE_VERSION_CONFLICT",
                retryable=False,
            )
        if decision == SourceStateApplyResult.STALE:
            await self._writer.delete_source(
                "CATEGORY_GUIDANCE",
                job.source_id,
                str(job.source_version),
            )

    async def _process_tombstone(
        self,
        job: KnowledgeIngestionJob,
        source: CategoryGuidanceSource,
    ) -> None:
        if source.invalidated_at is None or job.superseded_version is None:
            raise KnowledgeProcessingError(
                "SOURCE_TOMBSTONE_INVALID",
                retryable=False,
            )
        await self._invalidate_and_schedule_version(
            job,
            job.superseded_version,
            source.invalidated_at,
        )
        decision = await self._repository.apply_tombstone_source_state(
            job,
            invalidated_at=source.invalidated_at,
        )
        if decision == SourceStateApplyResult.CONFLICT:
            raise KnowledgeProcessingError(
                "SOURCE_VERSION_CONFLICT",
                retryable=False,
            )

    async def _can_skip_active(
        self,
        job: KnowledgeIngestionJob,
        state: KnowledgeSourceState | None,
        content_hash: str,
    ) -> bool:
        if state is None:
            return False
        if state.latest_observed_version > job.source_version:
            await self._writer.delete_source(
                "CATEGORY_GUIDANCE",
                job.source_id,
                str(job.source_version),
            )
            return True
        if state.latest_observed_version < job.source_version:
            return False
        if (
            state.state == "ACTIVE"
            and state.latest_indexed_version == job.source_version
            and state.latest_content_hash == content_hash
            and state.chunker_version == CATEGORY_GUIDANCE_CHUNKER_VERSION
            and state.embedding_provider == self._embedding_provider.provider
            and state.embedding_model == self._embedding_provider.model
            and state.embedding_dimensions == self._embedding_provider.dimensions
        ):
            return True
        raise KnowledgeProcessingError(
            "SOURCE_VERSION_CONFLICT",
            retryable=False,
        )

    async def _invalidate_and_schedule_version(
        self,
        job: KnowledgeIngestionJob,
        source_version: int,
        invalidated_at: datetime,
    ) -> None:
        await self._writer.invalidate_source(
            "CATEGORY_GUIDANCE",
            job.source_id,
            str(source_version),
            invalidated_at,
        )
        await self._operations_repository.schedule_deletion(
            source_type="CATEGORY_GUIDANCE",
            source_id=job.source_id,
            source_version=source_version,
            language=job.language,
            invalidated_at=invalidated_at,
            now=self._clock(),
        )

    def _validate_source_reference(
        self,
        job: KnowledgeIngestionJob,
        source: CategoryGuidanceSource,
    ) -> None:
        if (
            source.source_type != job.source_type
            or source.source_id != job.source_id
            or int(source.source_version) != job.source_version
            or source.language != job.language
            or source.lifecycle != job.lifecycle
            or (
                None
                if source.supersedes_version is None
                else int(source.supersedes_version)
            )
            != job.superseded_version
        ):
            raise KnowledgeProcessingError(
                "SOURCE_REFERENCE_MISMATCH",
                retryable=False,
            )


class KnowledgeProcessorDispatcher:
    """Routes durable jobs only to explicitly enabled source processors."""

    def __init__(
        self,
        listing: ListingKnowledgeProcessor,
        category_guidance: CategoryGuidanceProcessor | None = None,
    ) -> None:
        self._listing = listing
        self._category_guidance = category_guidance

    async def __call__(self, job: KnowledgeIngestionJob) -> None:
        if job.source_type == "LISTING":
            await self._listing(job)
            return
        if job.source_type == "CATEGORY_GUIDANCE" and self._category_guidance:
            await self._category_guidance(job)
            return
        raise KnowledgeProcessingError(
            "SOURCE_TYPE_UNSUPPORTED",
            retryable=False,
        )


if __name__ == "__main__":
    from .knowledge_rebuild import main

    main()
