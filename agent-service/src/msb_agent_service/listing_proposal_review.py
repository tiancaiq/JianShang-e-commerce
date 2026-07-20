from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import secrets
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Literal, Protocol

import aiomysql
from prometheus_client import CollectorRegistry, Counter, Histogram
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .agent_persistence import AgentPersistenceRepository, new_ulid
from .config import ListingProposalApiSettings
from .listing_content_proposal import (
    InMemoryProposalReplayStore,
    ListingContentProposal,
    ListingContentProposalOrchestrator,
    ListingDraftMediaTool,
    ListingProposalCommand,
    ListingProposalError,
    ListingProposalLimits,
    ListingVisionProvider,
    ListingVisionProviderResult,
    OwnedDraftMediaContext,
    PROPOSAL_SCHEMA_VERSION,
    VISION_INSTRUCTION_VERSION,
)
from .schemas import Ulid

LOGGER = logging.getLogger(__name__)

EXTERNAL_SCHEMA_VERSION = "LISTING_PROPOSAL_V1"
CONTENT_TTL = timedelta(hours=24)
TOMBSTONE_TTL = timedelta(days=90)
CLAIM_TTL = timedelta(minutes=2)
_CLIENT_REQUEST_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{16,64}$")
_IDEMPOTENCY_KEY_PATTERN = re.compile(r"^[\x21-\x7e]{8,128}$")
_SAFE_RESULT_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")
_REQUIRED_TABLES = {
    "agent_listing_proposals",
    "agent_listing_proposal_claims",
    "agent_listing_proposal_dismissals",
}


class ProposalReviewModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class CreateListingProposalRequest(ProposalReviewModel):
    schema_version: Literal["LISTING_PROPOSAL_V1"] = Field(alias="schemaVersion")
    listing_id: Ulid = Field(alias="listingId")
    expected_listing_version: int = Field(
        alias="expectedListingVersion",
        ge=1,
        le=2_147_483_647,
    )
    media_ids: tuple[Ulid, ...] = Field(alias="mediaIds", min_length=1, max_length=4)
    client_request_id: str = Field(
        alias="clientRequestId",
        min_length=16,
        max_length=64,
        pattern=r"^[A-Za-z0-9._:-]+$",
    )

    @model_validator(mode="after")
    def validate_unique_media(self) -> "CreateListingProposalRequest":
        if len(self.media_ids) != len(set(self.media_ids)):
            raise ValueError("mediaIds must contain unique canonical ULIDs")
        return self

    @property
    def canonical_media_ids(self) -> tuple[str, ...]:
        return tuple(sorted(self.media_ids))

    @property
    def canonical_request_hash(self) -> str:
        return _canonical_hash(
            {
                "schemaVersion": self.schema_version,
                "listingId": self.listing_id,
                "expectedListingVersion": self.expected_listing_version,
                "mediaIds": self.canonical_media_ids,
            }
        )

    @property
    def media_fingerprint(self) -> str:
        return _canonical_hash({"mediaIds": self.canonical_media_ids})


class SourceMediaEvidence(ProposalReviewModel):
    media_id: Ulid = Field(alias="mediaId")
    evidence_id: str = Field(alias="evidenceId", pattern=r"^E[1-4]$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    actual_mime: Literal["image/jpeg", "image/png", "image/webp"] = Field(
        alias="actualMime"
    )
    byte_size: int = Field(alias="byteSize", ge=1, le=10 * 1024 * 1024)


class ListingProposalResultMetadata(ProposalReviewModel):
    instruction_version: str = Field(
        alias="instructionVersion",
        min_length=1,
        max_length=80,
    )
    schema_version: Literal["ai-list-proposal-v1"] = Field(alias="schemaVersion")
    provider_mode: Literal["FAKE", "LIVE"] = Field(alias="providerMode")
    result_code: str = Field(
        alias="resultCode",
        min_length=1,
        max_length=80,
        pattern=r"^[A-Z][A-Z0-9_]*$",
    )
    latency_ms: int = Field(alias="latencyMs", ge=0, le=3_600_000)
    input_tokens: int = Field(alias="inputTokens", ge=0, le=1_000_000)
    output_tokens: int = Field(alias="outputTokens", ge=0, le=1_000_000)


class ListingProposalStatus(StrEnum):
    READY = "READY"
    DISMISSED = "DISMISSED"
    EXPIRED = "EXPIRED"


class ListingProposalResponse(ProposalReviewModel):
    proposal_id: Ulid = Field(alias="proposalId")
    status: Literal["READY", "DISMISSED", "EXPIRED"]
    proposal_version: int = Field(alias="proposalVersion", ge=1)
    schema_version: Literal["LISTING_PROPOSAL_V1"] = Field(alias="schemaVersion")
    listing_id: Ulid = Field(alias="listingId")
    source_listing_version: int = Field(alias="sourceListingVersion", ge=1)
    source_media_evidence: tuple[SourceMediaEvidence, ...] | None = Field(
        default=None,
        alias="sourceMediaEvidence",
    )
    proposal: ListingContentProposal | None = None
    proposal_only: Literal[True] | None = Field(default=None, alias="proposalOnly")
    requires_seller_confirmation: Literal[True] | None = Field(
        default=None,
        alias="requiresSellerConfirmation",
    )
    created_at: datetime = Field(alias="createdAt")
    expires_at: datetime = Field(alias="expiresAt")
    dismissed_at: datetime | None = Field(default=None, alias="dismissedAt")
    content_purged_at: datetime | None = Field(default=None, alias="contentPurgedAt")
    result_metadata: ListingProposalResultMetadata | None = Field(
        default=None,
        alias="resultMetadata",
    )


class ListingProposalApiErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED"
    NOT_FOUND = "AGENT_LISTING_PROPOSAL_NOT_FOUND"
    FEATURE_DISABLED = "FEATURE_DISABLED"
    IDEMPOTENCY_CONFLICT = "AI_PROPOSAL_IDEMPOTENCY_CONFLICT"
    SOURCE_VERSION_CONFLICT = "AI_PROPOSAL_SOURCE_VERSION_CONFLICT"
    STATE_CONFLICT = "AI_PROPOSAL_STATE_CONFLICT"
    EXPIRED = "AI_PROPOSAL_EXPIRED"
    PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    UNAVAILABLE = "AI_PROPOSAL_UNAVAILABLE"


class ListingProposalApiError(RuntimeError):
    """Carries a stable public proposal error without raw content or upstream data."""

    def __init__(
        self,
        code: ListingProposalApiErrorCode,
        status_code: int,
        message: str,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.status_code = status_code
        self.public_message = message


class ProposalReservationResult(StrEnum):
    OWNER = "OWNER"
    REPLAY = "REPLAY"
    IN_PROGRESS = "IN_PROGRESS"
    CONFLICT = "CONFLICT"


@dataclass(frozen=True)
class ProposalReservation:
    result: ProposalReservationResult
    claim_token: str | None = None
    proposal: ListingProposalResponse | None = None


class ListingProposalGenerator(Protocol):
    async def generate(
        self,
        *,
        actor_user_id: str,
        listing_id: str,
        expected_listing_version: int,
        media_ids: tuple[str, ...],
        client_request_id: str,
        correlation_id: str,
    ) -> "GeneratedListingProposal": ...


class GeneratedListingProposal(ProposalReviewModel):
    source_listing_version: int = Field(alias="sourceListingVersion", ge=1)
    source_media_evidence: tuple[SourceMediaEvidence, ...] = Field(
        alias="sourceMediaEvidence",
        min_length=1,
        max_length=4,
    )
    proposal: ListingContentProposal
    result_metadata: ListingProposalResultMetadata = Field(alias="resultMetadata")


class _CapturingMediaTool:
    def __init__(self, delegate: ListingDraftMediaTool) -> None:
        self._delegate = delegate
        self.context: OwnedDraftMediaContext | None = None

    async def read_owned_draft_media(self, **kwargs) -> OwnedDraftMediaContext:
        self.context = await self._delegate.read_owned_draft_media(**kwargs)
        return self.context


class _CapturingVisionProvider:
    def __init__(self, delegate: ListingVisionProvider) -> None:
        self._delegate = delegate
        self.result: ListingVisionProviderResult | None = None

    async def propose_listing_content(
        self,
        request,
        *,
        correlation_id: str,
    ) -> ListingVisionProviderResult:
        self.result = await self._delegate.propose_listing_content(
            request,
            correlation_id=correlation_id,
        )
        return self.result


class OwnedMediaListingProposalGenerator:
    """Adapts the existing owner-scoped media and vision boundaries to review state."""

    def __init__(
        self,
        *,
        media_tool: ListingDraftMediaTool,
        vision_provider: ListingVisionProvider,
        provider_mode: Literal["FAKE", "LIVE"],
        limits: ListingProposalLimits | None = None,
    ) -> None:
        self._media_tool = media_tool
        self._vision_provider = vision_provider
        self._provider_mode = provider_mode
        self._limits = limits

    async def generate(
        self,
        *,
        actor_user_id: str,
        listing_id: str,
        expected_listing_version: int,
        media_ids: tuple[str, ...],
        client_request_id: str,
        correlation_id: str,
    ) -> GeneratedListingProposal:
        """Run existing read-only orchestration and discard media bytes after use."""

        media = _CapturingMediaTool(self._media_tool)
        provider = _CapturingVisionProvider(self._vision_provider)
        orchestrator = ListingContentProposalOrchestrator(
            media_tool=media,
            vision_provider=provider,
            enabled=True,
            limits=self._limits,
            replay_store=InMemoryProposalReplayStore(),
        )
        proposal = await orchestrator.propose(
            ListingProposalCommand(
                actor_user_id=actor_user_id,
                listing_id=listing_id,
                media_ids=media_ids,
                client_request_id=client_request_id,
                correlation_id=correlation_id,
            )
        )
        if media.context is None or provider.result is None:
            raise _unavailable()
        try:
            source_version = int(media.context.listing_version)
        except ValueError as error:
            raise _unavailable() from error
        if not 1 <= source_version <= 2_147_483_647:
            raise _unavailable()
        evidence = tuple(
            SourceMediaEvidence(
                mediaId=item.media_id,
                evidenceId=f"E{index}",
                sha256=item.sha256,
                actualMime=item.content_type,
                byteSize=item.byte_size,
            )
            for index, item in enumerate(media.context.media, 1)
        )
        return GeneratedListingProposal(
            sourceListingVersion=source_version,
            sourceMediaEvidence=evidence,
            proposal=proposal,
            resultMetadata=ListingProposalResultMetadata(
                instructionVersion=VISION_INSTRUCTION_VERSION,
                schemaVersion=PROPOSAL_SCHEMA_VERSION,
                providerMode=self._provider_mode,
                resultCode="SUCCEEDED",
                latencyMs=provider.result.latency_ms,
                inputTokens=provider.result.input_tokens,
                outputTokens=provider.result.output_tokens,
            ),
        )


class ListingProposalRepositoryProtocol(Protocol):
    async def validate_schema(self) -> None: ...

    async def reserve_create(
        self,
        *,
        actor_user_id: str,
        request: CreateListingProposalRequest,
        now: datetime,
    ) -> ProposalReservation: ...

    async def find_by_key(
        self,
        *,
        actor_user_id: str,
        client_request_id: str,
        request_hash: str,
        now: datetime,
    ) -> ListingProposalResponse | None: ...

    async def complete_create(
        self,
        *,
        actor_user_id: str,
        request: CreateListingProposalRequest,
        claim_token: str,
        generated: GeneratedListingProposal,
        now: datetime,
    ) -> ListingProposalResponse: ...

    async def release_claim(
        self,
        *,
        actor_user_id: str,
        client_request_id: str,
        claim_token: str,
    ) -> None: ...

    async def get_owned(
        self,
        *,
        proposal_id: str,
        actor_user_id: str,
        now: datetime,
    ) -> ListingProposalResponse | None: ...

    async def dismiss(
        self,
        *,
        proposal_id: str,
        actor_user_id: str,
        idempotency_key: str,
        now: datetime,
    ) -> ListingProposalResponse | None: ...

    async def apply_retention(self, *, now: datetime, batch_size: int = 100) -> tuple[int, int, int]: ...


class ListingProposalMetrics:
    """Records bounded proposal API and retention outcomes without identity labels."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self.requests = Counter(
            "agent_listing_proposal_review_requests_total",
            "Listing proposal review operations by bounded result.",
            ("operation", "result"),
            registry=registry,
        )
        self.duration = Histogram(
            "agent_listing_proposal_review_duration_seconds",
            "Listing proposal review latency by bounded operation.",
            ("operation",),
            registry=registry,
        )
        self.retention = Counter(
            "agent_listing_proposal_review_retention_rows_total",
            "Listing proposal rows affected by bounded retention action.",
            ("action",),
            registry=registry,
        )

    def record(self, operation: str, result: str, started: float) -> None:
        self.requests.labels(operation=operation, result=result).inc()
        self.duration.labels(operation=operation).observe(
            max(0.0, time.perf_counter() - started)
        )


class ListingProposalRepository:
    """Persists bounded review state and DB-coordinated generation claims."""

    def __init__(
        self,
        pool: aiomysql.Pool,
        metrics: ListingProposalMetrics,
    ) -> None:
        self._pool = pool
        self._metrics = metrics

    @classmethod
    async def from_agent_repository(
        cls,
        repository: AgentPersistenceRepository,
        metrics: ListingProposalMetrics,
    ) -> "ListingProposalRepository":
        return cls(repository.pool, metrics)

    async def validate_schema(self) -> None:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (%s, %s, %s)
                    """,
                    tuple(sorted(_REQUIRED_TABLES)),
                )
                found = {str(row[0]) for row in await cursor.fetchall()}
        if found != _REQUIRED_TABLES:
            raise RuntimeError("AI-LIST-02A persistence schema is not migrated")

    async def reserve_create(
        self,
        *,
        actor_user_id: str,
        request: CreateListingProposalRequest,
        now: datetime,
    ) -> ProposalReservation:
        """Claim one actor/client key before any Product or provider execution."""

        occurred_at = _mysql_datetime(now)
        for _ in range(3):
            token = secrets.token_hex(32)
            async with self._pool.acquire() as connection:
                try:
                    await connection.begin()
                    async with connection.cursor(aiomysql.DictCursor) as cursor:
                        existing = await self._select_by_key(
                            cursor,
                            actor_user_id,
                            request.client_request_id,
                            for_update=True,
                        )
                        if existing is not None:
                            existing = await self._expire_locked(
                                cursor,
                                existing,
                                occurred_at,
                            )
                            await connection.commit()
                            if existing["request_hash"] != request.canonical_request_hash:
                                return ProposalReservation(
                                    ProposalReservationResult.CONFLICT
                                )
                            return ProposalReservation(
                                ProposalReservationResult.REPLAY,
                                proposal=_response_from_row(existing),
                            )
                        await cursor.execute(
                            """
                            SELECT *
                            FROM agent_listing_proposal_claims
                            WHERE actor_user_id = %s AND client_request_id = %s
                            FOR UPDATE
                            """,
                            (actor_user_id, request.client_request_id),
                        )
                        claim = await cursor.fetchone()
                        if claim is not None:
                            if claim["request_hash"] != request.canonical_request_hash:
                                await connection.commit()
                                return ProposalReservation(
                                    ProposalReservationResult.CONFLICT
                                )
                            if _utc_datetime(claim["claim_expires_at"]) > _utc_datetime(
                                occurred_at
                            ):
                                await connection.commit()
                                return ProposalReservation(
                                    ProposalReservationResult.IN_PROGRESS
                                )
                            await cursor.execute(
                                """
                                DELETE FROM agent_listing_proposal_claims
                                WHERE actor_user_id = %s AND client_request_id = %s
                                """,
                                (actor_user_id, request.client_request_id),
                            )
                        await cursor.execute(
                            """
                            INSERT INTO agent_listing_proposal_claims (
                                actor_user_id, client_request_id, request_hash,
                                claim_token, claimed_at, claim_expires_at
                            )
                            VALUES (%s, %s, %s, %s, %s, %s)
                            """,
                            (
                                actor_user_id,
                                request.client_request_id,
                                request.canonical_request_hash,
                                token,
                                occurred_at,
                                occurred_at + CLAIM_TTL,
                            ),
                        )
                    await connection.commit()
                    return ProposalReservation(
                        ProposalReservationResult.OWNER,
                        claim_token=token,
                    )
                except aiomysql.IntegrityError:
                    await connection.rollback()
                except Exception:
                    await connection.rollback()
                    raise
        return ProposalReservation(ProposalReservationResult.IN_PROGRESS)

    async def find_by_key(
        self,
        *,
        actor_user_id: str,
        client_request_id: str,
        request_hash: str,
        now: datetime,
    ) -> ListingProposalResponse | None:
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    row = await self._select_by_key(
                        cursor,
                        actor_user_id,
                        client_request_id,
                        for_update=True,
                    )
                    if row is not None:
                        row = await self._expire_locked(
                            cursor,
                            row,
                            _mysql_datetime(now),
                        )
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        if row is None:
            return None
        if row["request_hash"] != request_hash:
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.IDEMPOTENCY_CONFLICT,
                409,
                "clientRequestId was already used for a different request.",
            )
        return _response_from_row(row)

    async def complete_create(
        self,
        *,
        actor_user_id: str,
        request: CreateListingProposalRequest,
        claim_token: str,
        generated: GeneratedListingProposal,
        now: datetime,
    ) -> ListingProposalResponse:
        """Commit a READY proposal only after strict generation succeeds."""

        created_at = _mysql_datetime(now)
        proposal_id = new_ulid()
        evidence_json = _bounded_json(
            generated.source_media_evidence,
            maximum_bytes=8_192,
            by_alias=True,
        )
        proposal_json = _bounded_json(
            generated.proposal,
            maximum_bytes=16_384,
            by_alias=False,
        )
        metadata_json = _bounded_json(
            generated.result_metadata,
            maximum_bytes=2_048,
            by_alias=True,
        )
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_listing_proposal_claims
                        WHERE actor_user_id = %s
                          AND client_request_id = %s
                        FOR UPDATE
                        """,
                        (actor_user_id, request.client_request_id),
                    )
                    claim = await cursor.fetchone()
                    if (
                        claim is None
                        or claim["claim_token"] != claim_token
                        or claim["request_hash"] != request.canonical_request_hash
                    ):
                        raise ListingProposalApiError(
                            ListingProposalApiErrorCode.STATE_CONFLICT,
                            409,
                            "The proposal generation claim is no longer current.",
                        )
                    await cursor.execute(
                        """
                        INSERT INTO agent_listing_proposals (
                            proposal_id, actor_user_id, listing_id,
                            source_listing_version, client_request_id,
                            request_hash, media_fingerprint, status,
                            schema_version, source_media_evidence_json,
                            proposal_json, result_metadata_json,
                            created_at, expires_at, tombstone_expires_at
                        )
                        VALUES (
                            %s, %s, %s, %s, %s, %s, %s, 'READY',
                            %s, %s, %s, %s, %s, %s, %s
                        )
                        """,
                        (
                            proposal_id,
                            actor_user_id,
                            request.listing_id,
                            generated.source_listing_version,
                            request.client_request_id,
                            request.canonical_request_hash,
                            request.media_fingerprint,
                            EXTERNAL_SCHEMA_VERSION,
                            evidence_json,
                            proposal_json,
                            metadata_json,
                            created_at,
                            created_at + CONTENT_TTL,
                            created_at + TOMBSTONE_TTL,
                        ),
                    )
                    await cursor.execute(
                        """
                        DELETE FROM agent_listing_proposal_claims
                        WHERE actor_user_id = %s
                          AND client_request_id = %s
                          AND claim_token = %s
                        """,
                        (actor_user_id, request.client_request_id, claim_token),
                    )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_listing_proposals
                        WHERE proposal_id = %s
                        """,
                        (proposal_id,),
                    )
                    row = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        return _response_from_row(row)

    async def release_claim(
        self,
        *,
        actor_user_id: str,
        client_request_id: str,
        claim_token: str,
    ) -> None:
        async with self._pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    DELETE FROM agent_listing_proposal_claims
                    WHERE actor_user_id = %s
                      AND client_request_id = %s
                      AND claim_token = %s
                    """,
                    (actor_user_id, client_request_id, claim_token),
                )
            await connection.commit()

    async def get_owned(
        self,
        *,
        proposal_id: str,
        actor_user_id: str,
        now: datetime,
    ) -> ListingProposalResponse | None:
        """Read only the trusted actor's row and lazily purge expired content."""

        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_listing_proposals
                        WHERE proposal_id = %s AND actor_user_id = %s
                        FOR UPDATE
                        """,
                        (proposal_id, actor_user_id),
                    )
                    row = await cursor.fetchone()
                    if row is not None:
                        row = await self._expire_locked(
                            cursor,
                            row,
                            _mysql_datetime(now),
                        )
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        return None if row is None else _response_from_row(row)

    async def dismiss(
        self,
        *,
        proposal_id: str,
        actor_user_id: str,
        idempotency_key: str,
        now: datetime,
    ) -> ListingProposalResponse | None:
        """Monotonically dismiss an owned READY proposal and purge content."""

        changed_at = _mysql_datetime(now)
        fixed_hash = _canonical_hash(
            {"operation": "DISMISS", "proposalId": proposal_id}
        )
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor(aiomysql.DictCursor) as cursor:
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_listing_proposals
                        WHERE proposal_id = %s AND actor_user_id = %s
                        FOR UPDATE
                        """,
                        (proposal_id, actor_user_id),
                    )
                    row = await cursor.fetchone()
                    if row is None:
                        await connection.commit()
                        return None
                    row = await self._expire_locked(cursor, row, changed_at)
                    await cursor.execute(
                        """
                        SELECT request_hash
                        FROM agent_listing_proposal_dismissals
                        WHERE actor_user_id = %s
                          AND proposal_id = %s
                          AND idempotency_key = %s
                        FOR UPDATE
                        """,
                        (actor_user_id, proposal_id, idempotency_key),
                    )
                    prior = await cursor.fetchone()
                    if prior is not None and prior["request_hash"] != fixed_hash:
                        raise ListingProposalApiError(
                            ListingProposalApiErrorCode.IDEMPOTENCY_CONFLICT,
                            409,
                            "Idempotency-Key was reused for a different request.",
                        )
                    if prior is None:
                        await cursor.execute(
                            """
                            INSERT INTO agent_listing_proposal_dismissals (
                                actor_user_id, proposal_id, idempotency_key,
                                request_hash, created_at
                            )
                            VALUES (%s, %s, %s, %s, %s)
                            """,
                            (
                                actor_user_id,
                                proposal_id,
                                idempotency_key,
                                fixed_hash,
                                changed_at,
                            ),
                        )
                    if row["status"] == ListingProposalStatus.READY.value:
                        await cursor.execute(
                            """
                            UPDATE agent_listing_proposals
                            SET status = 'DISMISSED',
                                proposal_version = proposal_version + 1,
                                source_media_evidence_json = NULL,
                                proposal_json = NULL,
                                result_metadata_json = NULL,
                                dismissed_at = %s,
                                content_purged_at = %s,
                                optimistic_version = optimistic_version + 1
                            WHERE proposal_id = %s
                              AND actor_user_id = %s
                              AND status = 'READY'
                            """,
                            (changed_at, changed_at, proposal_id, actor_user_id),
                        )
                    await cursor.execute(
                        """
                        SELECT *
                        FROM agent_listing_proposals
                        WHERE proposal_id = %s AND actor_user_id = %s
                        """,
                        (proposal_id, actor_user_id),
                    )
                    updated = await cursor.fetchone()
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        return _response_from_row(updated)

    async def apply_retention(
        self,
        *,
        now: datetime,
        batch_size: int = 100,
    ) -> tuple[int, int, int]:
        """Bound expiry purging, stale-claim cleanup, and 90-day hard deletion."""

        if not 1 <= batch_size <= 1_000:
            raise ValueError("batch_size must be between 1 and 1000")
        current = _mysql_datetime(now)
        async with self._pool.acquire() as connection:
            try:
                await connection.begin()
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        UPDATE agent_listing_proposals
                        SET status = 'EXPIRED',
                            proposal_version = proposal_version + 1,
                            source_media_evidence_json = NULL,
                            proposal_json = NULL,
                            result_metadata_json = NULL,
                            content_purged_at = %s,
                            optimistic_version = optimistic_version + 1
                        WHERE status = 'READY'
                          AND expires_at <= %s
                        ORDER BY expires_at, proposal_id
                        LIMIT %s
                        """,
                        (current, current, batch_size),
                    )
                    expired = cursor.rowcount
                    await cursor.execute(
                        """
                        DELETE FROM agent_listing_proposal_claims
                        WHERE claim_expires_at <= %s
                        ORDER BY claim_expires_at, actor_user_id, client_request_id
                        LIMIT %s
                        """,
                        (current, batch_size),
                    )
                    claims = cursor.rowcount
                    await cursor.execute(
                        """
                        DELETE FROM agent_listing_proposals
                        WHERE tombstone_expires_at <= %s
                        ORDER BY tombstone_expires_at, proposal_id
                        LIMIT %s
                        """,
                        (current, batch_size),
                    )
                    deleted = cursor.rowcount
                await connection.commit()
            except Exception:
                await connection.rollback()
                raise
        for action, count in (
            ("content_expired", expired),
            ("claims_deleted", claims),
            ("tombstones_deleted", deleted),
        ):
            if count:
                self._metrics.retention.labels(action=action).inc(count)
        return expired, claims, deleted

    async def _select_by_key(
        self,
        cursor: aiomysql.DictCursor,
        actor_user_id: str,
        client_request_id: str,
        *,
        for_update: bool,
    ) -> dict[str, object] | None:
        suffix = " FOR UPDATE" if for_update else ""
        await cursor.execute(
            f"""
            SELECT *
            FROM agent_listing_proposals
            WHERE actor_user_id = %s AND client_request_id = %s{suffix}
            """,
            (actor_user_id, client_request_id),
        )
        return await cursor.fetchone()

    async def _expire_locked(
        self,
        cursor: aiomysql.DictCursor,
        row: dict[str, object],
        now: datetime,
    ) -> dict[str, object]:
        if (
            row["status"] == ListingProposalStatus.READY.value
            and _utc_datetime(row["expires_at"]) <= _utc_datetime(now)
        ):
            await cursor.execute(
                """
                UPDATE agent_listing_proposals
                SET status = 'EXPIRED',
                    proposal_version = proposal_version + 1,
                    source_media_evidence_json = NULL,
                    proposal_json = NULL,
                    result_metadata_json = NULL,
                    content_purged_at = %s,
                    optimistic_version = optimistic_version + 1
                WHERE proposal_id = %s AND status = 'READY'
                """,
                (now, row["proposal_id"]),
            )
            await cursor.execute(
                "SELECT * FROM agent_listing_proposals WHERE proposal_id = %s",
                (row["proposal_id"],),
            )
            return await cursor.fetchone()
        return row


class ListingProposalReviewService:
    """Coordinates actor-scoped replay, gating, generation, and durable review."""

    def __init__(
        self,
        repository: ListingProposalRepositoryProtocol,
        settings: ListingProposalApiSettings,
        metrics: ListingProposalMetrics,
        generator: ListingProposalGenerator | None = None,
    ) -> None:
        self._repository = repository
        self._settings = settings
        self._metrics = metrics
        self._generator = generator

    async def create(
        self,
        *,
        actor_user_id: str,
        request: CreateListingProposalRequest,
        correlation_id: str,
    ) -> tuple[ListingProposalResponse, bool]:
        """Replay before dependency gates, then persist only strict successful output."""

        started = time.perf_counter()
        reservation = await self._repository.reserve_create(
            actor_user_id=actor_user_id,
            request=request,
            now=datetime.now(UTC),
        )
        if reservation.result == ProposalReservationResult.CONFLICT:
            self._metrics.record("create", "idempotency_conflict", started)
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.IDEMPOTENCY_CONFLICT,
                409,
                "clientRequestId was already used for a different request.",
            )
        if reservation.result == ProposalReservationResult.REPLAY:
            assert reservation.proposal is not None
            self._metrics.record("create", "replayed", started)
            return reservation.proposal, False
        if reservation.result == ProposalReservationResult.IN_PROGRESS:
            for _ in range(100):
                await asyncio.sleep(0.05)
                proposal = await self._repository.find_by_key(
                    actor_user_id=actor_user_id,
                    client_request_id=request.client_request_id,
                    request_hash=request.canonical_request_hash,
                    now=datetime.now(UTC),
                )
                if proposal is not None:
                    self._metrics.record("create", "replayed", started)
                    return proposal, False
            self._metrics.record("create", "unavailable", started)
            raise _unavailable()

        assert reservation.claim_token is not None
        try:
            self._require_generation_available()
            assert self._generator is not None
            generated = await self._generator.generate(
                actor_user_id=actor_user_id,
                listing_id=request.listing_id,
                expected_listing_version=request.expected_listing_version,
                media_ids=request.canonical_media_ids,
                client_request_id=request.client_request_id,
                correlation_id=correlation_id,
            )
            self._validate_generated(request, generated)
            proposal = await self._repository.complete_create(
                actor_user_id=actor_user_id,
                request=request,
                claim_token=reservation.claim_token,
                generated=generated,
                now=datetime.now(UTC),
            )
            LOGGER.info(
                "listing_proposal_review_created actor_hash=%s listing_hash=%s "
                "proposal_hash=%s correlation_hash=%s media_count=%d",
                _hash_text(actor_user_id),
                _hash_text(request.listing_id),
                _hash_text(proposal.proposal_id),
                _hash_text(correlation_id),
                len(request.media_ids),
            )
            self._metrics.record("create", "created", started)
            return proposal, True
        except asyncio.CancelledError:
            await asyncio.shield(
                self._repository.release_claim(
                    actor_user_id=actor_user_id,
                    client_request_id=request.client_request_id,
                    claim_token=reservation.claim_token,
                )
            )
            self._metrics.record("create", "cancelled", started)
            raise
        except ListingProposalApiError:
            await self._repository.release_claim(
                actor_user_id=actor_user_id,
                client_request_id=request.client_request_id,
                claim_token=reservation.claim_token,
            )
            raise
        except ListingProposalError as error:
            await self._repository.release_claim(
                actor_user_id=actor_user_id,
                client_request_id=request.client_request_id,
                claim_token=reservation.claim_token,
            )
            self._metrics.record("create", "unavailable", started)
            raise _unavailable() from error
        except Exception as error:
            await self._repository.release_claim(
                actor_user_id=actor_user_id,
                client_request_id=request.client_request_id,
                claim_token=reservation.claim_token,
            )
            self._metrics.record("create", "unavailable", started)
            raise _unavailable() from error

    async def get(
        self,
        *,
        actor_user_id: str,
        proposal_id: str,
    ) -> ListingProposalResponse:
        started = time.perf_counter()
        proposal = await self._repository.get_owned(
            proposal_id=proposal_id,
            actor_user_id=actor_user_id,
            now=datetime.now(UTC),
        )
        if proposal is None:
            self._metrics.record("get", "not_found", started)
            raise _not_found()
        if proposal.status == ListingProposalStatus.EXPIRED.value:
            self._metrics.record("get", "expired", started)
            raise _expired()
        self._metrics.record("get", "succeeded", started)
        return proposal

    async def dismiss(
        self,
        *,
        actor_user_id: str,
        proposal_id: str,
        idempotency_key: str,
    ) -> ListingProposalResponse:
        started = time.perf_counter()
        if not _IDEMPOTENCY_KEY_PATTERN.fullmatch(idempotency_key):
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.INVALID_REQUEST,
                400,
                "Idempotency-Key must be 8 to 128 visible ASCII characters.",
            )
        proposal = await self._repository.dismiss(
            proposal_id=proposal_id,
            actor_user_id=actor_user_id,
            idempotency_key=idempotency_key,
            now=datetime.now(UTC),
        )
        if proposal is None:
            self._metrics.record("dismiss", "not_found", started)
            raise _not_found()
        if proposal.status == ListingProposalStatus.EXPIRED.value:
            self._metrics.record("dismiss", "expired", started)
            raise _expired()
        self._metrics.record("dismiss", "succeeded", started)
        return proposal

    def _require_generation_available(self) -> None:
        if (
            self._settings.kill_switch_enabled
            or not self._settings.orchestration_enabled
            or not self._settings.media_tool_enabled
            or not self._settings.multimodal_provider_enabled
            or not self._settings.multimodal_provider_configured
            or self._generator is None
        ):
            raise _unavailable()

    @staticmethod
    def _validate_generated(
        request: CreateListingProposalRequest,
        generated: GeneratedListingProposal,
    ) -> None:
        if generated.source_listing_version != request.expected_listing_version:
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.SOURCE_VERSION_CONFLICT,
                409,
                "The listing or selected media version changed.",
            )
        evidence_media = tuple(
            sorted(item.media_id for item in generated.source_media_evidence)
        )
        if evidence_media != request.canonical_media_ids:
            raise _unavailable()
        proposal_media = {
            item.media_id for item in generated.proposal.evidence
        }
        if not proposal_media.issubset(set(request.canonical_media_ids)):
            raise _unavailable()
        evidence_ids_by_media = {
            item.media_id: item.evidence_id
            for item in generated.source_media_evidence
        }
        if any(
            evidence_ids_by_media.get(item.media_id) != item.evidence_id
            for item in generated.proposal.evidence
        ):
            raise _unavailable()
        metadata = generated.result_metadata
        if (
            metadata.schema_version != PROPOSAL_SCHEMA_VERSION
            or metadata.instruction_version != VISION_INSTRUCTION_VERSION
            or not _SAFE_RESULT_PATTERN.fullmatch(metadata.result_code)
        ):
            raise _unavailable()


async def listing_proposal_retention_loop(
    repository: ListingProposalRepositoryProtocol,
) -> None:
    """Run the bounded purge immediately and then hourly without blocking shutdown."""

    while True:
        try:
            await repository.apply_retention(now=datetime.now(UTC), batch_size=100)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            LOGGER.error(
                "listing_proposal_retention_failed errorType=%s",
                type(error).__name__,
            )
        await asyncio.sleep(3600)


def _response_from_row(row: dict[str, object]) -> ListingProposalResponse:
    status = ListingProposalStatus(str(row["status"]))
    purged = status != ListingProposalStatus.READY
    evidence = (
        None
        if purged
        else tuple(
            SourceMediaEvidence.model_validate(item)
            for item in _json_value(row["source_media_evidence_json"])
        )
    )
    proposal = (
        None
        if purged
        else ListingContentProposal.model_validate_json(
            _json_text(row["proposal_json"])
        )
    )
    metadata = (
        None
        if purged
        else ListingProposalResultMetadata.model_validate(
            _json_value(row["result_metadata_json"])
        )
    )
    return ListingProposalResponse(
        proposalId=str(row["proposal_id"]),
        status=status.value,
        proposalVersion=int(row["proposal_version"]),
        schemaVersion=str(row["schema_version"]),
        listingId=str(row["listing_id"]),
        sourceListingVersion=int(row["source_listing_version"]),
        sourceMediaEvidence=evidence,
        proposal=proposal,
        proposalOnly=None if purged else True,
        requiresSellerConfirmation=None if purged else True,
        createdAt=_utc_datetime(row["created_at"]),
        expiresAt=_utc_datetime(row["expires_at"]),
        dismissedAt=(
            None
            if row.get("dismissed_at") is None
            else _utc_datetime(row["dismissed_at"])
        ),
        contentPurgedAt=(
            None
            if row.get("content_purged_at") is None
            else _utc_datetime(row["content_purged_at"])
        ),
        resultMetadata=metadata,
    )


def _bounded_json(value: object, *, maximum_bytes: int, by_alias: bool) -> str:
    if isinstance(value, BaseModel):
        serializable = value.model_dump(mode="json", by_alias=by_alias)
    elif isinstance(value, tuple):
        serializable = [
            item.model_dump(mode="json", by_alias=by_alias)
            if isinstance(item, BaseModel)
            else item
            for item in value
        ]
    else:
        serializable = value
    encoded = json.dumps(
        serializable,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    if len(encoded.encode("utf-8")) > maximum_bytes:
        raise _unavailable()
    return encoded


def _json_value(value: object) -> object:
    if isinstance(value, (dict, list)):
        return value
    if isinstance(value, (bytes, bytearray)):
        return json.loads(value.decode("utf-8"))
    return json.loads(str(value))


def _json_text(value: object) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8")
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _canonical_hash(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return hashlib.sha256(encoded).hexdigest()


def _hash_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _mysql_datetime(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("timestamp must be timezone-aware")
    return value.astimezone(UTC).replace(tzinfo=None)


def _utc_datetime(value: object) -> datetime:
    if not isinstance(value, datetime):
        raise ValueError("stored timestamp is invalid")
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def _not_found() -> ListingProposalApiError:
    return ListingProposalApiError(
        ListingProposalApiErrorCode.NOT_FOUND,
        404,
        "Listing proposal was not found.",
    )


def _expired() -> ListingProposalApiError:
    return ListingProposalApiError(
        ListingProposalApiErrorCode.EXPIRED,
        410,
        "Listing proposal content has expired.",
    )


def _unavailable() -> ListingProposalApiError:
    return ListingProposalApiError(
        ListingProposalApiErrorCode.UNAVAILABLE,
        503,
        "Listing proposal generation is temporarily unavailable.",
    )
