from __future__ import annotations

import asyncio
import hashlib
import json
import re
import time
from collections.abc import Callable, Coroutine
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Literal, Protocol

from prometheus_client import CollectorRegistry, Counter, Histogram
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .customer_service_orchestration import redact_customer_service_text
from .schemas import Ulid

MAX_MEDIA_COUNT = 4
MAX_MEDIA_BYTES = 10 * 1024 * 1024
MAX_TOTAL_MEDIA_BYTES = 20 * 1024 * 1024
ALLOWED_MEDIA_TYPES = frozenset({"image/jpeg", "image/png", "image/webp"})
PROPOSAL_SCHEMA_VERSION = "ai-list-proposal-v1"
TOOL_REGISTRY_VERSION = "owned-listing-media-read-v1"
VISION_INSTRUCTION_VERSION = "listing-proposal-vision-v1"

_INJECTION_PATTERN = re.compile(
    r"(?i)\b(?:ignore|override|disregard)\b.{0,48}\b(?:instruction|prompt|policy)"
    r"|\b(?:system\s+prompt|developer\s+message|call\s+(?:a\s+)?tool)\b"
)
_FORBIDDEN_CLAIM_PATTERN = re.compile(
    r"(?i)\b(?:exact\s+address|home\s+address|seller\s+identity|seller\s+name|"
    r"price|quantity|negotiable|authentic(?:ity)?|guaranteed\s+safe|"
    r"policy[- ]approved|complies\s+with\s+policy|condition|like\s+new|"
    r"mint\s+condition)\b"
)


def matches_media_signature(content: bytes, content_type: str) -> bool:
    """Verify that bounded media bytes match the declared allowlisted image type."""

    if content_type == "image/jpeg":
        return len(content) >= 3 and content[:3] == b"\xff\xd8\xff"
    if content_type == "image/png":
        return len(content) >= 8 and content[:8] == b"\x89PNG\r\n\x1a\n"
    if content_type == "image/webp":
        return (
            len(content) >= 16
            and content[:4] == b"RIFF"
            and content[8:12] == b"WEBP"
            and content[12:16] in {b"VP8 ", b"VP8L", b"VP8X"}
        )
    return False


class ProposalModel(BaseModel):
    """Provides a strict internal schema without exposing framework types externally."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class UnknownField(StrEnum):
    TITLE = "TITLE"
    DESCRIPTION = "DESCRIPTION"
    CATEGORY = "CATEGORY"
    SELLER_IDENTITY = "SELLER_IDENTITY"
    PRICE = "PRICE"
    EXACT_LOCATION = "EXACT_LOCATION"
    QUANTITY = "QUANTITY"
    CONDITION = "CONDITION"
    NEGOTIABILITY = "NEGOTIABILITY"
    POLICY_CLAIMS = "POLICY_CLAIMS"
    CONTACT_DATA = "CONTACT_DATA"
    BRAND = "BRAND"
    MODEL = "MODEL"
    AUTHENTICITY = "AUTHENTICITY"
    SAFETY = "SAFETY"


_ALWAYS_UNKNOWN = frozenset(
    {
        UnknownField.SELLER_IDENTITY,
        UnknownField.PRICE,
        UnknownField.EXACT_LOCATION,
        UnknownField.QUANTITY,
        UnknownField.CONDITION,
        UnknownField.NEGOTIABILITY,
        UnknownField.POLICY_CLAIMS,
        UnknownField.CONTACT_DATA,
        UnknownField.BRAND,
        UnknownField.MODEL,
        UnknownField.AUTHENTICITY,
        UnknownField.SAFETY,
    }
)


class ProposalEvidence(ProposalModel):
    evidence_id: str = Field(pattern=r"^E[1-4]$")
    media_id: Ulid
    observation: str = Field(min_length=1, max_length=240)


class SuggestedText(ProposalModel):
    value: str = Field(min_length=1, max_length=2_000)
    confidence: float = Field(ge=0.0, le=1.0)
    evidence_ids: tuple[str, ...] = Field(min_length=1, max_length=4)


class CategoryCandidate(ProposalModel):
    label: str = Field(min_length=1, max_length=80)
    confidence: float = Field(ge=0.0, le=1.0)
    evidence_ids: tuple[str, ...] = Field(min_length=1, max_length=4)


class ListingContentProposal(ProposalModel):
    schema_version: Literal["ai-list-proposal-v1"] = PROPOSAL_SCHEMA_VERSION
    suggested_title: SuggestedText | None = None
    suggested_description: SuggestedText | None = None
    category_candidates: tuple[CategoryCandidate, ...] = Field(
        default=(),
        max_length=3,
    )
    evidence: tuple[ProposalEvidence, ...] = Field(default=(), max_length=4)
    unknown_fields: tuple[UnknownField, ...]
    proposal_only: Literal[True] = True
    requires_seller_confirmation: Literal[True] = True

    @model_validator(mode="after")
    def validate_grounding_and_unknowns(self) -> "ListingContentProposal":
        evidence_ids = [item.evidence_id for item in self.evidence]
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("evidence IDs must be unique")
        allowed_evidence = set(evidence_ids)
        suggestions: tuple[SuggestedText | CategoryCandidate, ...] = (
            *((self.suggested_title,) if self.suggested_title else ()),
            *((self.suggested_description,) if self.suggested_description else ()),
            *self.category_candidates,
        )
        if any(
            len(item.evidence_ids) != len(set(item.evidence_ids))
            or not set(item.evidence_ids).issubset(allowed_evidence)
            for item in suggestions
        ):
            raise ValueError("suggestions must cite unique supplied evidence")
        labels = [item.label.casefold() for item in self.category_candidates]
        if len(labels) != len(set(labels)):
            raise ValueError("category candidates must be unique")
        if len(self.unknown_fields) != len(set(self.unknown_fields)):
            raise ValueError("unknown fields must be unique")
        if not _ALWAYS_UNKNOWN.issubset(self.unknown_fields):
            raise ValueError("protected fields must remain explicitly unknown")
        if self.suggested_title is not None and UnknownField.TITLE in self.unknown_fields:
            raise ValueError("suggested title cannot also be unknown")
        if (
            self.suggested_description is not None
            and UnknownField.DESCRIPTION in self.unknown_fields
        ):
            raise ValueError("suggested description cannot also be unknown")
        if self.category_candidates and UnknownField.CATEGORY in self.unknown_fields:
            raise ValueError("suggested category cannot also be unknown")
        if self.suggested_title is None and UnknownField.TITLE not in self.unknown_fields:
            raise ValueError("missing title must be explicit")
        if (
            self.suggested_description is None
            and UnknownField.DESCRIPTION not in self.unknown_fields
        ):
            raise ValueError("missing description must be explicit")
        if (
            not self.category_candidates
            and UnknownField.CATEGORY not in self.unknown_fields
        ):
            raise ValueError("missing category must be explicit")
        return self


class ListingMediaContent(ProposalModel):
    media_id: Ulid
    content_type: Literal["image/jpeg", "image/png", "image/webp"]
    byte_size: int = Field(ge=1, le=MAX_MEDIA_BYTES)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_version: str = Field(min_length=1, max_length=80)
    content: bytes = Field(min_length=1, max_length=MAX_MEDIA_BYTES, repr=False)

    @model_validator(mode="after")
    def validate_content(self) -> "ListingMediaContent":
        if len(self.content) != self.byte_size:
            raise ValueError("media byte size does not match content")
        if hashlib.sha256(self.content).hexdigest() != self.sha256:
            raise ValueError("media digest does not match content")
        if not matches_media_signature(self.content, self.content_type):
            raise ValueError("media signature does not match content type")
        return self


class OwnedDraftMediaContext(ProposalModel):
    listing_id: Ulid
    listing_version: str = Field(min_length=1, max_length=80)
    eligibility: Literal["OWNED_DRAFT"]
    media: tuple[ListingMediaContent, ...] = Field(
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )


class ListingProposalCommand(ProposalModel):
    actor_user_id: Ulid
    listing_id: Ulid
    media_ids: tuple[Ulid, ...] = Field(
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )
    client_request_id: str = Field(
        min_length=16,
        max_length=64,
        pattern=r"^[A-Za-z0-9._:-]+$",
    )
    correlation_id: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_media_ids(self) -> "ListingProposalCommand":
        if len(self.media_ids) != len(set(self.media_ids)):
            raise ValueError("media IDs must be unique")
        if (
            self.correlation_id != self.correlation_id.strip()
            or any(ord(character) < 32 for character in self.correlation_id)
        ):
            raise ValueError("correlation ID must be a bounded visible value")
        return self


class VisionMediaInput(ProposalModel):
    media_id: Ulid
    content_type: Literal["image/jpeg", "image/png", "image/webp"]
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    content: bytes = Field(min_length=1, max_length=MAX_MEDIA_BYTES, repr=False)

    @model_validator(mode="after")
    def validate_content(self) -> "VisionMediaInput":
        if hashlib.sha256(self.content).hexdigest() != self.sha256:
            raise ValueError("vision media digest does not match content")
        if not matches_media_signature(self.content, self.content_type):
            raise ValueError("vision media signature does not match content type")
        return self


class ListingVisionRequest(ProposalModel):
    instruction_version: Literal[
        "listing-proposal-vision-v1"
    ] = VISION_INSTRUCTION_VERSION
    untrusted_media: tuple[VisionMediaInput, ...] = Field(
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )


class ListingVisionCandidate(ProposalModel):
    suggested_title: SuggestedText | None = None
    suggested_description: SuggestedText | None = None
    category_candidates: tuple[CategoryCandidate, ...] = Field(
        default=(),
        max_length=3,
    )
    evidence: tuple[ProposalEvidence, ...] = Field(default=(), max_length=4)
    unknown_fields: tuple[UnknownField, ...] = ()
    prompt_injection_detected: bool = False


@dataclass(frozen=True)
class ListingVisionProviderResult:
    """Carries one fake/offline vision result plus bounded zero-cost metadata."""

    candidate: ListingVisionCandidate
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    estimated_cost: float = 0.0


class ListingDraftMediaTool(Protocol):
    async def read_owned_draft_media(
        self,
        *,
        actor_user_id: str,
        listing_id: str,
        media_ids: tuple[str, ...],
        correlation_id: str,
    ) -> OwnedDraftMediaContext: ...


class ListingVisionProvider(Protocol):
    async def propose_listing_content(
        self,
        request: ListingVisionRequest,
        *,
        correlation_id: str,
    ) -> ListingVisionProviderResult: ...


class ProposalAuditSink(Protocol):
    async def record(self, event: "ProposalAuditEvent") -> None: ...


class ProposalReplayStore(Protocol):
    async def execute(
        self,
        *,
        key: tuple[str, str, str],
        fingerprint: str,
        operation: Callable[
            [], Coroutine[Any, Any, ListingContentProposal]
        ],
    ) -> tuple[ListingContentProposal, bool]: ...


class ProposalAuditEvent(ProposalModel):
    operation: Literal["LISTING_CONTENT_PROPOSAL"]
    result: Literal[
        "SUCCEEDED",
        "REPLAYED",
        "DISABLED",
        "UNAVAILABLE",
        "UNSUPPORTED_MEDIA",
        "REJECTED",
        "TIMED_OUT",
        "PROVIDER_UNAVAILABLE",
        "CANCELLED",
        "IDEMPOTENCY_CONFLICT",
    ]
    actor_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    listing_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    correlation_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    media_count: int = Field(ge=0, le=MAX_MEDIA_COUNT)
    tool_registry_version: Literal[
        "owned-listing-media-read-v1"
    ] = TOOL_REGISTRY_VERSION
    instruction_version: Literal[
        "listing-proposal-vision-v1"
    ] = VISION_INSTRUCTION_VERSION
    latency_ms: int = Field(ge=0, le=3_600_000)
    input_tokens: int = Field(ge=0, le=1_000_000)
    output_tokens: int = Field(ge=0, le=1_000_000)
    estimated_cost: Literal[0.0] = 0.0


class ListingProposalErrorCode(StrEnum):
    DISABLED = "LISTING_PROPOSAL_DISABLED"
    UNAVAILABLE = "LISTING_DRAFT_MEDIA_UNAVAILABLE"
    UNSUPPORTED_MEDIA = "LISTING_PROPOSAL_UNSUPPORTED_MEDIA"
    REJECTED = "LISTING_PROPOSAL_REJECTED"
    TIMED_OUT = "LISTING_PROPOSAL_TIMED_OUT"
    PROVIDER_UNAVAILABLE = "LISTING_PROPOSAL_PROVIDER_UNAVAILABLE"
    IDEMPOTENCY_CONFLICT = "LISTING_PROPOSAL_IDEMPOTENCY_CONFLICT"


class ListingProposalError(RuntimeError):
    """Returns a stable failure classification without content or identity."""

    def __init__(
        self,
        code: ListingProposalErrorCode,
        message: str,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class ListingProposalLimits(ProposalModel):
    maximum_media_count: int = Field(
        default=MAX_MEDIA_COUNT,
        ge=1,
        le=MAX_MEDIA_COUNT,
    )
    maximum_media_bytes: int = Field(
        default=MAX_MEDIA_BYTES,
        ge=1,
        le=MAX_MEDIA_BYTES,
    )
    maximum_total_media_bytes: int = Field(
        default=MAX_TOTAL_MEDIA_BYTES,
        ge=1,
        le=MAX_TOTAL_MEDIA_BYTES,
    )
    media_tool_timeout_seconds: float = Field(default=2.0, ge=0.01, le=30.0)
    provider_timeout_seconds: float = Field(default=5.0, ge=0.01, le=60.0)


class ListingProposalMetrics:
    """Records only bounded proposal results, never actor, listing, or content labels."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.requests = Counter(
            "agent_listing_proposal_requests_total",
            "Offline listing proposal requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.duration = Histogram(
            "agent_listing_proposal_duration_seconds",
            "Offline listing proposal orchestration latency.",
            registry=self.registry,
        )
        self.tool_calls = Counter(
            "agent_listing_proposal_media_tool_total",
            "Owned listing-media tool calls by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.provider_requests = Counter(
            "agent_listing_proposal_provider_total",
            "Injected vision transport calls by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.guardrails = Counter(
            "agent_listing_proposal_guardrail_total",
            "Listing proposal guardrail decisions by bounded rule.",
            ("rule",),
            registry=self.registry,
        )


class NullProposalAuditSink:
    async def record(self, event: ProposalAuditEvent) -> None:
        """Keep the offline adapter composable without adding persistence."""


@dataclass
class _ReplayEntry:
    fingerprint: str
    task: asyncio.Task[ListingContentProposal]


class InMemoryProposalReplayStore:
    """Deduplicates concurrent offline proposals without adding durable state."""

    def __init__(self) -> None:
        self._entries: dict[tuple[str, str, str], _ReplayEntry] = {}
        self._lock = asyncio.Lock()

    async def execute(
        self,
        *,
        key: tuple[str, str, str],
        fingerprint: str,
        operation: Callable[
            [], Coroutine[Any, Any, ListingContentProposal]
        ],
    ) -> tuple[ListingContentProposal, bool]:
        async with self._lock:
            entry = self._entries.get(key)
            if entry is not None:
                if entry.fingerprint != fingerprint:
                    raise ListingProposalError(
                        ListingProposalErrorCode.IDEMPOTENCY_CONFLICT,
                        "The proposal request ID was reused with different media",
                        retryable=False,
                    )
                task = entry.task
                replayed = True
            else:
                task = asyncio.create_task(operation())
                self._entries[key] = _ReplayEntry(
                    fingerprint=fingerprint,
                    task=task,
                )
                replayed = False
        try:
            return await task, replayed
        except BaseException:
            async with self._lock:
                current = self._entries.get(key)
                if current is not None and current.task is task:
                    self._entries.pop(key, None)
            raise


class ListingContentProposalOrchestrator:
    """Runs one default-off, read-only seller listing proposal boundary."""

    def __init__(
        self,
        *,
        media_tool: ListingDraftMediaTool,
        vision_provider: ListingVisionProvider,
        enabled: bool = False,
        limits: ListingProposalLimits | None = None,
        replay_store: ProposalReplayStore | None = None,
        audit_sink: ProposalAuditSink | None = None,
        metrics: ListingProposalMetrics | None = None,
    ) -> None:
        self._media_tool = media_tool
        self._vision_provider = vision_provider
        self._enabled = enabled
        self._limits = limits or ListingProposalLimits()
        self._replay_store = replay_store or InMemoryProposalReplayStore()
        self._audit_sink = audit_sink or NullProposalAuditSink()
        self._metrics = metrics or ListingProposalMetrics()

    async def propose(
        self,
        command: ListingProposalCommand,
    ) -> ListingContentProposal:
        """Return a review-only proposal or a stable disabled/failure result."""

        started = time.monotonic()
        if not self._enabled:
            self._metrics.requests.labels(result="disabled").inc()
            await self._audit(command, "DISABLED", started)
            raise ListingProposalError(
                ListingProposalErrorCode.DISABLED,
                "Listing content proposals are disabled",
                retryable=False,
            )

        fingerprint = _payload_hash(
            {
                "listing_id": command.listing_id,
                "media_ids": command.media_ids,
                "schema_version": PROPOSAL_SCHEMA_VERSION,
            }
        )
        try:
            proposal, replayed = await self._replay_store.execute(
                key=(
                    command.actor_user_id,
                    command.listing_id,
                    command.client_request_id,
                ),
                fingerprint=fingerprint,
                operation=lambda: self._run_once(command, started),
            )
            if replayed:
                self._metrics.requests.labels(result="replayed").inc()
                await self._audit(command, "REPLAYED", started)
            return proposal
        except ListingProposalError as error:
            if error.code == ListingProposalErrorCode.IDEMPOTENCY_CONFLICT:
                self._metrics.requests.labels(result="idempotency_conflict").inc()
                await self._audit(command, "IDEMPOTENCY_CONFLICT", started)
            raise

    async def _run_once(
        self,
        command: ListingProposalCommand,
        started: float,
    ) -> ListingContentProposal:
        try:
            context = await asyncio.wait_for(
                self._media_tool.read_owned_draft_media(
                    actor_user_id=command.actor_user_id,
                    listing_id=command.listing_id,
                    media_ids=command.media_ids,
                    correlation_id=command.correlation_id,
                ),
                timeout=self._limits.media_tool_timeout_seconds,
            )
            self._metrics.tool_calls.labels(result="succeeded").inc()
            self._validate_media_context(command, context)
        except asyncio.CancelledError:
            self._metrics.tool_calls.labels(result="cancelled").inc()
            self._metrics.requests.labels(result="cancelled").inc()
            await self._audit(command, "CANCELLED", started)
            raise
        except ListingProposalError as error:
            result = _audit_result(error.code)
            self._metrics.tool_calls.labels(result=_metric_result(error.code)).inc()
            self._metrics.requests.labels(result=_metric_result(error.code)).inc()
            await self._audit(command, result, started)
            raise
        except (TimeoutError, asyncio.TimeoutError) as error:
            self._metrics.tool_calls.labels(result="timed_out").inc()
            self._metrics.requests.labels(result="timed_out").inc()
            await self._audit(command, "TIMED_OUT", started)
            raise ListingProposalError(
                ListingProposalErrorCode.TIMED_OUT,
                "The listing media check timed out",
                retryable=True,
            ) from error
        except Exception as error:
            self._metrics.tool_calls.labels(result="unavailable").inc()
            self._metrics.requests.labels(result="unavailable").inc()
            await self._audit(command, "UNAVAILABLE", started)
            raise ListingProposalError(
                ListingProposalErrorCode.UNAVAILABLE,
                "The owned listing draft or selected media is unavailable",
                retryable=False,
            ) from error

        request = ListingVisionRequest(
            untrusted_media=tuple(
                VisionMediaInput(
                    media_id=media.media_id,
                    content_type=media.content_type,
                    sha256=media.sha256,
                    content=media.content,
                )
                for media in context.media
            )
        )
        try:
            provider_result = await asyncio.wait_for(
                self._vision_provider.propose_listing_content(
                    request,
                    correlation_id=command.correlation_id,
                ),
                timeout=self._limits.provider_timeout_seconds,
            )
            _validate_provider_metadata(provider_result)
            proposal = _sanitize_candidate(
                provider_result.candidate,
                allowed_media_ids=set(command.media_ids),
                metrics=self._metrics,
            )
        except asyncio.CancelledError:
            self._metrics.provider_requests.labels(result="cancelled").inc()
            self._metrics.requests.labels(result="cancelled").inc()
            await self._audit(command, "CANCELLED", started)
            raise
        except ListingProposalError as error:
            self._metrics.provider_requests.labels(
                result=_metric_result(error.code)
            ).inc()
            self._metrics.requests.labels(result=_metric_result(error.code)).inc()
            await self._audit(command, _audit_result(error.code), started)
            raise
        except (TimeoutError, asyncio.TimeoutError) as error:
            self._metrics.provider_requests.labels(result="timed_out").inc()
            self._metrics.requests.labels(result="timed_out").inc()
            await self._audit(command, "TIMED_OUT", started)
            raise ListingProposalError(
                ListingProposalErrorCode.TIMED_OUT,
                "The vision proposal timed out",
                retryable=True,
            ) from error
        except Exception as error:
            self._metrics.provider_requests.labels(result="unavailable").inc()
            self._metrics.requests.labels(result="provider_unavailable").inc()
            await self._audit(command, "PROVIDER_UNAVAILABLE", started)
            raise ListingProposalError(
                ListingProposalErrorCode.PROVIDER_UNAVAILABLE,
                "The vision proposal provider is unavailable",
                retryable=True,
            ) from error

        self._metrics.provider_requests.labels(result="succeeded").inc()
        self._metrics.requests.labels(result="succeeded").inc()
        self._metrics.duration.observe(time.monotonic() - started)
        await self._audit(
            command,
            "SUCCEEDED",
            started,
            input_tokens=provider_result.input_tokens,
            output_tokens=provider_result.output_tokens,
        )
        return proposal

    def _validate_media_context(
        self,
        command: ListingProposalCommand,
        context: OwnedDraftMediaContext,
    ) -> None:
        """Fail closed when an adapter returns cross-listing or malformed media."""

        if context.listing_id != command.listing_id:
            raise ListingProposalError(
                ListingProposalErrorCode.UNAVAILABLE,
                "The owned listing draft or selected media is unavailable",
                retryable=False,
            )
        returned_ids = tuple(media.media_id for media in context.media)
        if returned_ids != command.media_ids:
            raise ListingProposalError(
                ListingProposalErrorCode.UNAVAILABLE,
                "The owned listing draft or selected media is unavailable",
                retryable=False,
            )
        if len(context.media) > self._limits.maximum_media_count:
            raise _unsupported_media()
        total_bytes = 0
        for media in context.media:
            if (
                media.content_type not in ALLOWED_MEDIA_TYPES
                or media.byte_size > self._limits.maximum_media_bytes
            ):
                raise _unsupported_media()
            total_bytes += media.byte_size
        if total_bytes > self._limits.maximum_total_media_bytes:
            raise _unsupported_media()

    async def _audit(
        self,
        command: ListingProposalCommand,
        result: Literal[
            "SUCCEEDED",
            "REPLAYED",
            "DISABLED",
            "UNAVAILABLE",
            "UNSUPPORTED_MEDIA",
            "REJECTED",
            "TIMED_OUT",
            "PROVIDER_UNAVAILABLE",
            "CANCELLED",
            "IDEMPOTENCY_CONFLICT",
        ],
        started: float,
        *,
        input_tokens: int = 0,
        output_tokens: int = 0,
    ) -> None:
        """Record hashes and bounded counters without prompt, media, or identity data."""

        await self._audit_sink.record(
            ProposalAuditEvent(
                operation="LISTING_CONTENT_PROPOSAL",
                result=result,
                actor_hash=_hash_text(command.actor_user_id),
                listing_hash=_hash_text(command.listing_id),
                request_hash=_hash_text(command.client_request_id),
                correlation_hash=_hash_text(command.correlation_id),
                media_count=len(command.media_ids),
                latency_ms=max(0, round((time.monotonic() - started) * 1_000)),
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            )
        )


def _sanitize_candidate(
    candidate: ListingVisionCandidate,
    *,
    allowed_media_ids: set[str],
    metrics: ListingProposalMetrics,
) -> ListingContentProposal:
    if candidate.prompt_injection_detected:
        metrics.guardrails.labels(rule="prompt_injection").inc()
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "Untrusted media instructions were rejected",
            retryable=False,
        )

    evidence: list[ProposalEvidence] = []
    for item in candidate.evidence:
        if item.media_id not in allowed_media_ids:
            metrics.guardrails.labels(rule="cross_media_evidence").inc()
            raise ListingProposalError(
                ListingProposalErrorCode.REJECTED,
                "Proposal evidence did not match selected media",
                retryable=False,
            )
        evidence.append(
            ProposalEvidence(
                evidence_id=item.evidence_id,
                media_id=item.media_id,
                observation=_safe_proposal_text(item.observation, 240, metrics),
            )
        )

    title = _sanitize_suggested_text(
        candidate.suggested_title,
        180,
        metrics,
    )
    description = _sanitize_suggested_text(
        candidate.suggested_description,
        2_000,
        metrics,
    )
    categories = tuple(
        CategoryCandidate(
            label=_safe_proposal_text(item.label, 80, metrics),
            confidence=item.confidence,
            evidence_ids=item.evidence_ids,
        )
        for item in candidate.category_candidates
    )
    unknowns = set(candidate.unknown_fields) | set(_ALWAYS_UNKNOWN)
    if title is None:
        unknowns.add(UnknownField.TITLE)
    if description is None:
        unknowns.add(UnknownField.DESCRIPTION)
    if not categories:
        unknowns.add(UnknownField.CATEGORY)

    try:
        return ListingContentProposal(
            suggested_title=title,
            suggested_description=description,
            category_candidates=categories,
            evidence=tuple(evidence),
            unknown_fields=tuple(sorted(unknowns, key=str)),
        )
    except ValueError as error:
        metrics.guardrails.labels(rule="schema_grounding").inc()
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "The proposal failed strict grounding validation",
            retryable=False,
        ) from error


def _sanitize_suggested_text(
    value: SuggestedText | None,
    maximum_length: int,
    metrics: ListingProposalMetrics,
) -> SuggestedText | None:
    if value is None:
        return None
    return SuggestedText(
        value=_safe_proposal_text(value.value, maximum_length, metrics),
        confidence=value.confidence,
        evidence_ids=value.evidence_ids,
    )


def _safe_proposal_text(
    value: str,
    maximum_length: int,
    metrics: ListingProposalMetrics,
) -> str:
    normalized = " ".join(
        "".join(character if ord(character) >= 32 else " " for character in value)
        .split()
    )
    try:
        redacted, changed = redact_customer_service_text(normalized)
    except ValueError as error:
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "The proposal contained no safe bounded text",
            retryable=False,
        ) from error
    if changed:
        metrics.guardrails.labels(rule="privacy_redaction").inc()
    if not redacted or len(redacted) > maximum_length:
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "The proposal contained an invalid bounded field",
            retryable=False,
        )
    if _INJECTION_PATTERN.search(redacted):
        metrics.guardrails.labels(rule="prompt_injection").inc()
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "Untrusted media instructions were rejected",
            retryable=False,
        )
    if _FORBIDDEN_CLAIM_PATTERN.search(redacted):
        metrics.guardrails.labels(rule="forbidden_claim").inc()
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "The proposal asserted a seller-owned or unsupported field",
            retryable=False,
        )
    return redacted


def _validate_provider_metadata(result: object) -> None:
    if (
        not isinstance(result, ListingVisionProviderResult)
        or not isinstance(result.candidate, ListingVisionCandidate)
    ):
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "The proposal provider returned an invalid strict result",
            retryable=False,
        )
    for value in (result.input_tokens, result.output_tokens, result.latency_ms):
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 1_000_000:
            raise ListingProposalError(
                ListingProposalErrorCode.REJECTED,
                "The proposal provider returned invalid metadata",
                retryable=False,
            )
    if result.estimated_cost != 0.0:
        raise ListingProposalError(
            ListingProposalErrorCode.REJECTED,
            "Offline proposal cost must remain zero and unpriced",
            retryable=False,
        )


def _unsupported_media() -> ListingProposalError:
    return ListingProposalError(
        ListingProposalErrorCode.UNSUPPORTED_MEDIA,
        "Selected media must be bounded JPEG, PNG, or WebP images",
        retryable=False,
    )


def _metric_result(code: ListingProposalErrorCode) -> str:
    return {
        ListingProposalErrorCode.DISABLED: "disabled",
        ListingProposalErrorCode.UNAVAILABLE: "unavailable",
        ListingProposalErrorCode.UNSUPPORTED_MEDIA: "unsupported_media",
        ListingProposalErrorCode.REJECTED: "rejected",
        ListingProposalErrorCode.TIMED_OUT: "timed_out",
        ListingProposalErrorCode.PROVIDER_UNAVAILABLE: "provider_unavailable",
        ListingProposalErrorCode.IDEMPOTENCY_CONFLICT: "idempotency_conflict",
    }[code]


def _audit_result(
    code: ListingProposalErrorCode,
) -> Literal[
    "DISABLED",
    "UNAVAILABLE",
    "UNSUPPORTED_MEDIA",
    "REJECTED",
    "TIMED_OUT",
    "PROVIDER_UNAVAILABLE",
    "IDEMPOTENCY_CONFLICT",
]:
    return {
        ListingProposalErrorCode.DISABLED: "DISABLED",
        ListingProposalErrorCode.UNAVAILABLE: "UNAVAILABLE",
        ListingProposalErrorCode.UNSUPPORTED_MEDIA: "UNSUPPORTED_MEDIA",
        ListingProposalErrorCode.REJECTED: "REJECTED",
        ListingProposalErrorCode.TIMED_OUT: "TIMED_OUT",
        ListingProposalErrorCode.PROVIDER_UNAVAILABLE: "PROVIDER_UNAVAILABLE",
        ListingProposalErrorCode.IDEMPOTENCY_CONFLICT: "IDEMPOTENCY_CONFLICT",
    }[code]


def _payload_hash(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _hash_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
