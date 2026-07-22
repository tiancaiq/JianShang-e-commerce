from __future__ import annotations

import json
import logging
import math
import re
import time
from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Literal, Protocol

from opensearchpy import AsyncOpenSearch
from prometheus_client import CollectorRegistry, Counter, Histogram
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from .embedding_provider import (
    EmbeddingProvider,
    EmbeddingProviderError,
)
from .knowledge_index_client import (
    classify_open_search_error,
)
from .knowledge_index_documents import fixed_metadata_filters

LOGGER = logging.getLogger(__name__)
_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_FIXED_ID_PATTERN = re.compile(r"^[0-9A-Z]{26}$")
_LANGUAGE_PATTERN = re.compile(r"^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")
_LISTING_VERSION_PATTERN = re.compile(r"^(?:0|[1-9][0-9]{0,18})$")
_CATEGORY_VERSION_PATTERN = re.compile(r"^[1-9][0-9]{0,18}$")


@dataclass(frozen=True)
class KnowledgeRetrievalLimits:
    """Bounds query embedding, OpenSearch candidates, and returned context."""

    maximum_query_characters: int = 2_000
    maximum_top_k: int = 8
    candidate_multiplier: int = 4
    maximum_candidates: int = 32
    maximum_passage_characters: int = 2_400
    maximum_context_characters: int = 8_000
    default_language: str = "und"

    def validate(self) -> None:
        if not 1 <= self.maximum_query_characters <= 8_000:
            raise ValueError("maximum query characters must be between 1 and 8000")
        if not 1 <= self.maximum_top_k <= 20:
            raise ValueError("maximum top-k must be between 1 and 20")
        if not 1 <= self.candidate_multiplier <= 20:
            raise ValueError("candidate multiplier must be between 1 and 20")
        if not self.maximum_top_k <= self.maximum_candidates <= 200:
            raise ValueError("maximum candidates must be between top-k and 200")
        if not 1 <= self.maximum_passage_characters <= 8_000:
            raise ValueError("maximum passage characters must be between 1 and 8000")
        if not self.maximum_passage_characters <= self.maximum_context_characters <= 40_000:
            raise ValueError(
                "maximum context characters must be between passage limit and 40000"
            )
        if _LANGUAGE_PATTERN.fullmatch(self.default_language) is None:
            raise ValueError("default language must be a normalized language tag")


class ListingKnowledgeRetrievalRequest(BaseModel):
    """Carries trusted listing context plus the only model-selectable arguments."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    actor_user_id: str = Field(alias="actorUserId", min_length=1, max_length=160)
    listing_id: str = Field(alias="listingId", min_length=1, max_length=160)
    listing_version: str = Field(alias="listingVersion")
    query: str = Field(min_length=1, max_length=8_000)
    source_types: tuple[Literal["LISTING"], ...] = Field(
        default=("LISTING",),
        alias="sourceTypes",
        min_length=1,
        max_length=1,
    )
    language: str = Field(default="und", min_length=2, max_length=40)
    effective_at: datetime = Field(alias="effectiveAt")
    top_k: int = Field(default=5, alias="topK", ge=1, le=20)
    correlation_id: str | None = Field(
        default=None,
        alias="correlationId",
        min_length=1,
        max_length=160,
    )

    @field_validator(
        "actor_user_id",
        "listing_id",
        "correlation_id",
    )
    @classmethod
    def safe_identifier(cls, value: str | None) -> str | None:
        if value is not None and (
            value != value.strip()
            or any(character.isspace() for character in value)
            or any(ord(character) < 32 for character in value)
        ):
            raise ValueError("identifier fields must not contain whitespace or controls")
        return value

    @field_validator("listing_version")
    @classmethod
    def valid_version(cls, value: str) -> str:
        if _LISTING_VERSION_PATTERN.fullmatch(value) is None:
            raise ValueError("listingVersion must be a decimal listing version")
        return value

    @field_validator("query")
    @classmethod
    def safe_query(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("query must not be blank")
        if any(
            ord(character) < 32 and character not in "\n\r\t"
            for character in normalized
        ):
            raise ValueError("query contains unsupported control characters")
        return normalized

    @field_validator("language")
    @classmethod
    def valid_language(cls, value: str) -> str:
        normalized = value.lower()
        if _LANGUAGE_PATTERN.fullmatch(normalized) is None:
            raise ValueError("language must be a normalized language tag")
        return normalized

    @field_validator("effective_at")
    @classmethod
    def timezone_aware_effective_at(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("effectiveAt must include a UTC offset")
        return value


class KnowledgePassage(BaseModel):
    """Returns bounded untrusted text with exact citation metadata."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    chunk_id: str = Field(alias="chunkId", min_length=1, max_length=160)
    source_type: Literal["LISTING"] = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId", min_length=1, max_length=160)
    source_version: str = Field(alias="sourceVersion")
    content_hash: str = Field(alias="contentHash")
    listing_id: str = Field(alias="listingId", min_length=1, max_length=160)
    visibility: Literal["PUBLIC"]
    language: str = Field(min_length=2, max_length=40)
    effective_from: datetime | None = Field(default=None, alias="effectiveFrom")
    effective_to: datetime | None = Field(default=None, alias="effectiveTo")
    invalidated_at: datetime | None = Field(default=None, alias="invalidatedAt")
    ordinal: int = Field(ge=0, le=100_000)
    section_label: str = Field(alias="sectionLabel", min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=8_000)
    score: float = Field(ge=0)

    @field_validator("source_version")
    @classmethod
    def valid_source_version(cls, value: str) -> str:
        if _LISTING_VERSION_PATTERN.fullmatch(value) is None:
            raise ValueError("sourceVersion must be a decimal listing version")
        return value

    @field_validator("content_hash")
    @classmethod
    def valid_content_hash(cls, value: str) -> str:
        if _HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("contentHash must be a lowercase SHA-256 digest")
        return value

    @field_validator("language")
    @classmethod
    def normalized_language(cls, value: str) -> str:
        if _LANGUAGE_PATTERN.fullmatch(value) is None:
            raise ValueError("language must be a normalized language tag")
        return value

    @field_validator("effective_from", "effective_to", "invalidated_at")
    @classmethod
    def timezone_aware_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("knowledge timestamps must include a UTC offset")
        return value

    @field_validator("score")
    @classmethod
    def finite_score(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("score must be finite")
        return value


class CategoryGuidanceVersionScope(BaseModel):
    """Identifies one trusted fully indexed category/language version."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    language: str = Field(min_length=2, max_length=40)
    source_version: str = Field(alias="sourceVersion")

    @field_validator("language")
    @classmethod
    def normalized_language(cls, value: str) -> str:
        normalized = value.lower()
        if _LANGUAGE_PATTERN.fullmatch(normalized) is None:
            raise ValueError("language must be a normalized language tag")
        return normalized

    @field_validator("source_version")
    @classmethod
    def valid_version(cls, value: str) -> str:
        if _CATEGORY_VERSION_PATTERN.fullmatch(value) is None:
            raise ValueError("sourceVersion must be a positive decimal version")
        return value


class CategoryGuidanceRetrievalRequest(BaseModel):
    """Carries only runtime-resolved category scopes plus a bounded query."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    actor_user_id: str = Field(alias="actorUserId", min_length=1, max_length=160)
    category_id: str = Field(alias="categoryId", min_length=1, max_length=160)
    scopes: tuple[CategoryGuidanceVersionScope, ...] = Field(
        min_length=1,
        max_length=3,
    )
    query: str = Field(min_length=1, max_length=8_000)
    effective_at: datetime = Field(alias="effectiveAt")
    top_k: int = Field(default=3, alias="topK", ge=1, le=3)
    correlation_id: str | None = Field(
        default=None,
        alias="correlationId",
        min_length=1,
        max_length=160,
    )

    @field_validator("actor_user_id", "category_id", "correlation_id")
    @classmethod
    def safe_identifier(cls, value: str | None) -> str | None:
        if value is not None and (
            value != value.strip()
            or any(character.isspace() for character in value)
            or any(ord(character) < 32 for character in value)
        ):
            raise ValueError("identifier fields must not contain whitespace or controls")
        return value

    @field_validator("category_id")
    @classmethod
    def fixed_category_id(cls, value: str) -> str:
        if _FIXED_ID_PATTERN.fullmatch(value) is None:
            raise ValueError("categoryId must be an uppercase 26-character ID")
        return value

    @field_validator("query")
    @classmethod
    def safe_query(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("query must not be blank")
        if any(
            ord(character) < 32 and character not in "\n\r\t"
            for character in normalized
        ):
            raise ValueError("query contains unsupported control characters")
        return normalized

    @field_validator("effective_at")
    @classmethod
    def timezone_aware_effective_at(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("effectiveAt must include a UTC offset")
        return value

    @field_validator("scopes")
    @classmethod
    def unique_scopes(
        cls,
        value: tuple[CategoryGuidanceVersionScope, ...],
    ) -> tuple[CategoryGuidanceVersionScope, ...]:
        identities = {(item.language, item.source_version) for item in value}
        if len(identities) != len(value):
            raise ValueError("category guidance scopes must be unique")
        return value


class CategoryGuidancePassage(BaseModel):
    """Strict category passage that can never claim listing scope."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    chunk_id: str = Field(alias="chunkId", min_length=1, max_length=160)
    source_type: Literal["CATEGORY_GUIDANCE"] = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId", min_length=1, max_length=160)
    source_version: str = Field(alias="sourceVersion")
    content_hash: str = Field(alias="contentHash")
    listing_id: None = Field(default=None, alias="listingId")
    visibility: Literal["PUBLIC"]
    language: str = Field(min_length=2, max_length=40)
    effective_from: datetime | None = Field(default=None, alias="effectiveFrom")
    effective_to: datetime | None = Field(default=None, alias="effectiveTo")
    invalidated_at: datetime | None = Field(default=None, alias="invalidatedAt")
    ordinal: int = Field(ge=0, le=100_000)
    section_label: str = Field(alias="sectionLabel", min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=8_000)
    score: float = Field(ge=0)

    @field_validator("source_version")
    @classmethod
    def valid_source_version(cls, value: str) -> str:
        if _CATEGORY_VERSION_PATTERN.fullmatch(value) is None:
            raise ValueError("sourceVersion must be a positive decimal version")
        return value

    @field_validator("content_hash")
    @classmethod
    def valid_content_hash(cls, value: str) -> str:
        if _HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("contentHash must be a lowercase SHA-256 digest")
        return value

    @field_validator("language")
    @classmethod
    def normalized_language(cls, value: str) -> str:
        if _LANGUAGE_PATTERN.fullmatch(value) is None:
            raise ValueError("language must be a normalized language tag")
        return value

    @field_validator("effective_from", "effective_to", "invalidated_at")
    @classmethod
    def timezone_aware_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("knowledge timestamps must include a UTC offset")
        return value

    @field_validator("score")
    @classmethod
    def finite_score(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("score must be finite")
        return value


@dataclass(frozen=True)
class KnowledgeRetrievalResult:
    passages: tuple[KnowledgePassage | CategoryGuidancePassage, ...]
    embedded_input_tokens: int
    discarded_hits: int
    context_characters: int


class KnowledgeRetrievalErrorCode(StrEnum):
    REQUEST_INVALID = "KNOWLEDGE_RETRIEVAL_REQUEST_INVALID"
    EMBEDDING_FAILED = "KNOWLEDGE_RETRIEVAL_EMBEDDING_FAILED"
    INDEX_UNAVAILABLE = "KNOWLEDGE_RETRIEVAL_INDEX_UNAVAILABLE"
    RESPONSE_INVALID = "KNOWLEDGE_RETRIEVAL_RESPONSE_INVALID"
    CONFLICTING_RESULTS = "KNOWLEDGE_RETRIEVAL_CONFLICTING_RESULTS"


class KnowledgeRetrievalError(RuntimeError):
    """Carries a secret-safe retrieval failure and retry classification."""

    def __init__(
        self,
        code: KnowledgeRetrievalErrorCode,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.retryable = retryable


class KnowledgeRetriever(Protocol):
    async def retrieve(
        self,
        request: ListingKnowledgeRetrievalRequest,
    ) -> KnowledgeRetrievalResult: ...


class CategoryGuidanceStateStore(Protocol):
    async def active_category_guidance_states(
        self,
        category_id: str,
        languages: tuple[str, ...],
    ) -> list[object]: ...


class CategoryGuidanceScopeResolver:
    """Maps a trusted category and locale to fully indexed source versions."""

    def __init__(
        self,
        store: CategoryGuidanceStateStore,
        *,
        embedding_provider: str,
        embedding_model: str,
        embedding_dimensions: int,
    ) -> None:
        self._store = store
        self._provider = embedding_provider
        self._model = embedding_model
        self._dimensions = embedding_dimensions

    async def resolve(
        self,
        category_id: str,
        requested_language: str,
    ) -> tuple[CategoryGuidanceVersionScope, ...]:
        """Resolve exact, primary-language, then und scopes without guessing."""

        normalized = requested_language.lower()
        if _LANGUAGE_PATTERN.fullmatch(normalized) is None:
            raise ValueError("requested language must be normalized")
        languages = tuple(
            dict.fromkeys(
                (
                    normalized,
                    normalized.split("-", 1)[0],
                    "und",
                )
            )
        )
        states = await self._store.active_category_guidance_states(
            category_id,
            languages,
        )
        by_language: dict[str, object] = {}
        for state in states:
            if (
                str(getattr(state, "source_type")) != "CATEGORY_GUIDANCE"
                or str(getattr(state, "source_id")) != category_id
                or getattr(state, "latest_indexed_version") is None
                or getattr(state, "latest_content_hash") is None
                or str(getattr(state, "embedding_provider")) != self._provider
                or str(getattr(state, "embedding_model")) != self._model
                or getattr(state, "embedding_dimensions", None) != self._dimensions
            ):
                continue
            by_language[str(getattr(state, "language"))] = state
        return tuple(
            CategoryGuidanceVersionScope(
                language=language,
                sourceVersion=str(
                    getattr(by_language[language], "latest_indexed_version")
                ),
            )
            for language in languages
            if language in by_language
        )


class KnowledgeRetrievalMetrics:
    """Tracks low-cardinality retrieval outcomes without query or actor labels."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.requests = Counter(
            "agent_knowledge_retrieval_requests_total",
            "Listing knowledge retrieval requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.duration = Histogram(
            "agent_knowledge_retrieval_duration_seconds",
            "Listing knowledge retrieval latency.",
            registry=self.registry,
        )
        self.discarded_hits = Counter(
            "agent_knowledge_retrieval_discarded_hits_total",
            "OpenSearch hits discarded by local defense-in-depth validation.",
            registry=self.registry,
        )
        self.passages = Histogram(
            "agent_knowledge_retrieval_passages",
            "Validated passages returned per retrieval.",
            buckets=(0, 1, 2, 3, 5, 8),
            registry=self.registry,
        )
        self.context_characters = Histogram(
            "agent_knowledge_retrieval_context_characters",
            "Bounded context characters returned per retrieval.",
            buckets=(0, 500, 1_000, 2_000, 4_000, 8_000),
            registry=self.registry,
        )


class OpenSearchListingKnowledgeRetriever:
    """Embeds one bounded query and retrieves only the current subject listing."""

    def __init__(
        self,
        *,
        client: AsyncOpenSearch,
        read_alias: str,
        embedding_provider: EmbeddingProvider,
        embedding_provider_name: str,
        embedding_model: str,
        embedding_dimensions: int,
        limits: KnowledgeRetrievalLimits | None = None,
        metrics: KnowledgeRetrievalMetrics | None = None,
    ) -> None:
        if not read_alias or read_alias != read_alias.strip():
            raise ValueError("read alias must not be blank or padded")
        self._client = client
        self._read_alias = read_alias
        self._embedding_provider = embedding_provider
        self._embedding_provider_name = embedding_provider_name
        self._embedding_model = embedding_model
        self._embedding_dimensions = embedding_dimensions
        self._limits = limits or KnowledgeRetrievalLimits()
        self._limits.validate()
        self._metrics = metrics or KnowledgeRetrievalMetrics()

    async def retrieve(
        self,
        request: ListingKnowledgeRetrievalRequest,
    ) -> KnowledgeRetrievalResult:
        """Return bounded current-version passages or a typed safe failure."""

        started = time.monotonic()
        result_label = "failed"
        try:
            self._validate_request_limits(request)
            embedding = await self._embedding_provider.embed(
                [request.query],
                correlation_id=request.correlation_id,
            )
            if (
                embedding.provider != self._embedding_provider_name
                or embedding.model != self._embedding_model
                or embedding.dimensions != self._embedding_dimensions
                or len(embedding.vectors) != 1
                or len(embedding.vectors[0]) != self._embedding_dimensions
            ):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.EMBEDDING_FAILED,
                    retryable=False,
                )
            body = self._search_body(request, embedding.vectors[0])
            try:
                response = await self._client.search(
                    index=self._read_alias,
                    body=body,
                )
            except Exception as error:
                mapped = classify_open_search_error(error)
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.INDEX_UNAVAILABLE,
                    retryable=mapped.retryable,
                ) from error
            result = self._validated_result(
                request,
                response,
                input_tokens=embedding.input_tokens,
            )
            result_label = "empty" if not result.passages else "success"
            self._metrics.discarded_hits.inc(result.discarded_hits)
            self._metrics.passages.observe(len(result.passages))
            self._metrics.context_characters.observe(result.context_characters)
            LOGGER.info(
                json.dumps(
                    {
                        "event": "knowledge_retrieval",
                        "status": result_label,
                        "sourceType": "LISTING",
                        "passageCount": len(result.passages),
                        "discardedHits": result.discarded_hits,
                        "contextCharacters": result.context_characters,
                        "correlationId": request.correlation_id,
                    }
                )
            )
            return result
        except EmbeddingProviderError as error:
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.EMBEDDING_FAILED,
                retryable=error.retryable,
            ) from error
        except KnowledgeRetrievalError:
            raise
        finally:
            self._metrics.requests.labels(result=result_label).inc()
            self._metrics.duration.observe(time.monotonic() - started)

    def _validate_request_limits(
        self,
        request: ListingKnowledgeRetrievalRequest,
    ) -> None:
        if (
            len(request.query) > self._limits.maximum_query_characters
            or request.top_k > self._limits.maximum_top_k
            or request.source_types != ("LISTING",)
        ):
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.REQUEST_INVALID,
                retryable=False,
            )

    def _search_body(
        self,
        request: ListingKnowledgeRetrievalRequest,
        vector: tuple[float, ...],
    ) -> dict[str, object]:
        candidates = min(
            self._limits.maximum_candidates,
            max(request.top_k, request.top_k * self._limits.candidate_multiplier),
        )
        languages = tuple(
            dict.fromkeys((request.language, self._limits.default_language))
        )
        filters = fixed_metadata_filters(
            source_types=["LISTING"],
            source_id=request.listing_id,
            source_version=request.listing_version,
            listing_id=request.listing_id,
            languages=list(languages),
            effective_at=request.effective_at,
        )
        return {
            "size": candidates,
            "track_total_hits": False,
            "_source": {"excludes": ["embedding", "indexedAt"]},
            "query": {
                "knn": {
                    "embedding": {
                        "vector": list(vector),
                        "k": candidates,
                        "filter": {"bool": {"filter": filters}},
                    }
                }
            },
        }

    def _validated_result(
        self,
        request: ListingKnowledgeRetrievalRequest,
        response: object,
        *,
        input_tokens: int,
    ) -> KnowledgeRetrievalResult:
        if not isinstance(response, dict):
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                retryable=False,
            )
        raw_hits = response.get("hits", {})
        hits = raw_hits.get("hits") if isinstance(raw_hits, dict) else None
        if not isinstance(hits, list):
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                retryable=False,
            )
        allowed_languages = {
            request.language,
            self._limits.default_language,
        }
        passages: list[KnowledgePassage] = []
        discarded = 0
        context_characters = 0
        ordinals: dict[int, tuple[str, str]] = {}
        seen_chunk_ids: set[str] = set()
        source_content_hash: str | None = None
        for hit in hits:
            if not isinstance(hit, dict) or not isinstance(hit.get("_source"), dict):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                    retryable=False,
                )
            source = dict(hit["_source"])
            score = hit.get("_score")
            try:
                passage = KnowledgePassage.model_validate(
                    {
                        **source,
                        "score": score,
                    }
                )
            except ValidationError as error:
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                    retryable=False,
                ) from error
            if (
                passage.source_id != request.listing_id
                or passage.listing_id != request.listing_id
                or passage.source_version != request.listing_version
                or passage.language not in allowed_languages
                or passage.invalidated_at is not None
                or (
                    passage.effective_from is not None
                    and passage.effective_from > request.effective_at
                )
                or (
                    passage.effective_to is not None
                    and passage.effective_to <= request.effective_at
                )
            ):
                discarded += 1
                continue
            if (
                source_content_hash is not None
                and source_content_hash != passage.content_hash
            ):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.CONFLICTING_RESULTS,
                    retryable=False,
                )
            source_content_hash = passage.content_hash
            ordinal_identity = (passage.chunk_id, passage.content_hash)
            prior = ordinals.get(passage.ordinal)
            if prior is not None and prior != ordinal_identity:
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.CONFLICTING_RESULTS,
                    retryable=False,
                )
            ordinals[passage.ordinal] = ordinal_identity
            if passage.chunk_id in seen_chunk_ids:
                continue
            seen_chunk_ids.add(passage.chunk_id)
            remaining = self._limits.maximum_context_characters - context_characters
            if remaining <= 0 or len(passages) >= request.top_k:
                break
            bounded_text = passage.text[
                : min(self._limits.maximum_passage_characters, remaining)
            ].rstrip()
            if not bounded_text:
                continue
            bounded = passage.model_copy(update={"text": bounded_text})
            passages.append(bounded)
            context_characters += len(bounded_text)
        return KnowledgeRetrievalResult(
            passages=tuple(passages),
            embedded_input_tokens=input_tokens,
            discarded_hits=discarded,
            context_characters=context_characters,
        )


class OpenSearchCategoryGuidanceRetriever:
    """Retrieves only exact runtime-resolved category guidance versions."""

    def __init__(
        self,
        *,
        client: AsyncOpenSearch,
        read_alias: str,
        embedding_provider: EmbeddingProvider,
        embedding_provider_name: str,
        embedding_model: str,
        embedding_dimensions: int,
        limits: KnowledgeRetrievalLimits | None = None,
        metrics: KnowledgeRetrievalMetrics | None = None,
    ) -> None:
        if not read_alias or read_alias != read_alias.strip():
            raise ValueError("read alias must not be blank or padded")
        self._client = client
        self._read_alias = read_alias
        self._embedding_provider = embedding_provider
        self._embedding_provider_name = embedding_provider_name
        self._embedding_model = embedding_model
        self._embedding_dimensions = embedding_dimensions
        self._limits = limits or KnowledgeRetrievalLimits()
        self._limits.validate()
        self._metrics = metrics or KnowledgeRetrievalMetrics()

    async def retrieve(
        self,
        request: CategoryGuidanceRetrievalRequest,
    ) -> KnowledgeRetrievalResult:
        """Return bounded category passages or a typed secret-safe failure."""

        started = time.monotonic()
        result_label = "failed"
        try:
            if (
                len(request.query) > self._limits.maximum_query_characters
                or request.top_k > min(3, self._limits.maximum_top_k)
            ):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.REQUEST_INVALID,
                    retryable=False,
                )
            embedding = await self._embedding_provider.embed(
                [request.query],
                correlation_id=request.correlation_id,
            )
            if (
                embedding.provider != self._embedding_provider_name
                or embedding.model != self._embedding_model
                or embedding.dimensions != self._embedding_dimensions
                or len(embedding.vectors) != 1
                or len(embedding.vectors[0]) != self._embedding_dimensions
            ):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.EMBEDDING_FAILED,
                    retryable=False,
                )
            try:
                response = await self._client.search(
                    index=self._read_alias,
                    body=self._search_body(request, embedding.vectors[0]),
                )
            except Exception as error:
                mapped = classify_open_search_error(error)
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.INDEX_UNAVAILABLE,
                    retryable=mapped.retryable,
                ) from error
            result = self._validated_result(
                request,
                response,
                input_tokens=embedding.input_tokens,
            )
            result_label = "empty" if not result.passages else "success"
            self._metrics.discarded_hits.inc(result.discarded_hits)
            self._metrics.passages.observe(len(result.passages))
            self._metrics.context_characters.observe(result.context_characters)
            LOGGER.info(
                json.dumps(
                    {
                        "event": "knowledge_retrieval",
                        "status": result_label,
                        "sourceType": "CATEGORY_GUIDANCE",
                        "passageCount": len(result.passages),
                        "discardedHits": result.discarded_hits,
                        "contextCharacters": result.context_characters,
                        "correlationId": request.correlation_id,
                    }
                )
            )
            return result
        except EmbeddingProviderError as error:
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.EMBEDDING_FAILED,
                retryable=error.retryable,
            ) from error
        finally:
            self._metrics.requests.labels(result=result_label).inc()
            self._metrics.duration.observe(time.monotonic() - started)

    def _search_body(
        self,
        request: CategoryGuidanceRetrievalRequest,
        vector: tuple[float, ...],
    ) -> dict[str, object]:
        candidates = min(
            self._limits.maximum_candidates,
            max(request.top_k, request.top_k * self._limits.candidate_multiplier),
        )
        filters = fixed_metadata_filters(
            source_types=["CATEGORY_GUIDANCE"],
            source_id=request.category_id,
            effective_at=request.effective_at,
        )
        filters.append(
            {
                "bool": {
                    "should": [
                        {
                            "bool": {
                                "filter": [
                                    {"term": {"language": scope.language}},
                                    {
                                        "term": {
                                            "sourceVersion": scope.source_version
                                        }
                                    },
                                ]
                            }
                        }
                        for scope in request.scopes
                    ],
                    "minimum_should_match": 1,
                }
            }
        )
        return {
            "size": candidates,
            "track_total_hits": False,
            "_source": {"excludes": ["embedding", "indexedAt"]},
            "query": {
                "knn": {
                    "embedding": {
                        "vector": list(vector),
                        "k": candidates,
                        "filter": {"bool": {"filter": filters}},
                    }
                }
            },
        }

    def _validated_result(
        self,
        request: CategoryGuidanceRetrievalRequest,
        response: object,
        *,
        input_tokens: int,
    ) -> KnowledgeRetrievalResult:
        if not isinstance(response, dict):
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                retryable=False,
            )
        raw_hits = response.get("hits", {})
        hits = raw_hits.get("hits") if isinstance(raw_hits, dict) else None
        if not isinstance(hits, list):
            raise KnowledgeRetrievalError(
                KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                retryable=False,
            )
        allowed = {
            (scope.language, scope.source_version)
            for scope in request.scopes
        }
        passages: list[CategoryGuidancePassage] = []
        discarded = 0
        context_characters = 0
        source_hashes: dict[tuple[str, str], str] = {}
        ordinals: dict[tuple[str, str, int], tuple[str, str]] = {}
        seen_chunk_ids: set[str] = set()
        for hit in hits:
            if not isinstance(hit, dict) or not isinstance(hit.get("_source"), dict):
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                    retryable=False,
                )
            try:
                passage = CategoryGuidancePassage.model_validate(
                    {**dict(hit["_source"]), "score": hit.get("_score")}
                )
            except ValidationError as error:
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.RESPONSE_INVALID,
                    retryable=False,
                ) from error
            identity = (passage.language, passage.source_version)
            if (
                passage.source_id != request.category_id
                or identity not in allowed
                or passage.invalidated_at is not None
                or (
                    passage.effective_from is not None
                    and passage.effective_from > request.effective_at
                )
                or (
                    passage.effective_to is not None
                    and passage.effective_to <= request.effective_at
                )
            ):
                discarded += 1
                continue
            prior_hash = source_hashes.get(identity)
            if prior_hash is not None and prior_hash != passage.content_hash:
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.CONFLICTING_RESULTS,
                    retryable=False,
                )
            source_hashes[identity] = passage.content_hash
            ordinal_key = (*identity, passage.ordinal)
            ordinal_identity = (passage.chunk_id, passage.content_hash)
            prior_ordinal = ordinals.get(ordinal_key)
            if prior_ordinal is not None and prior_ordinal != ordinal_identity:
                raise KnowledgeRetrievalError(
                    KnowledgeRetrievalErrorCode.CONFLICTING_RESULTS,
                    retryable=False,
                )
            ordinals[ordinal_key] = ordinal_identity
            if passage.chunk_id in seen_chunk_ids:
                continue
            seen_chunk_ids.add(passage.chunk_id)
            remaining = self._limits.maximum_context_characters - context_characters
            if remaining <= 0 or len(passages) >= request.top_k:
                break
            bounded_text = passage.text[
                : min(self._limits.maximum_passage_characters, remaining)
            ].rstrip()
            if not bounded_text:
                continue
            passages.append(passage.model_copy(update={"text": bounded_text}))
            context_characters += len(bounded_text)
        return KnowledgeRetrievalResult(
            passages=tuple(passages),
            embedded_input_tokens=input_tokens,
            discarded_hits=discarded,
            context_characters=context_characters,
        )
