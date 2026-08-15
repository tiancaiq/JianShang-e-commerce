from __future__ import annotations

import asyncio
import json
import math
import re
import struct
import time
import unicodedata
from datetime import datetime
from decimal import Decimal
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from .discovery_embedding_contract import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    EMBEDDING_PROVIDER,
)
from .embedding_provider import EmbeddingProvider, EmbeddingProviderError
from .marketplace_discovery import (
    CheckedListing,
    DiscoveryAvailabilityProbe,
    DiscoveryProductTool,
    DiscoveryRetrievalProvenance,
    DiscoverySearchFacets,
    DiscoverySearchCandidate,
    DiscoverySearchPage,
    DiscoverySearchRequest,
    DiscoverySearchSummary,
)

_REQUEST_SCHEMA = "MARKETPLACE_HYBRID_SEARCH_V1"
_FACET_RESPONSE_SCHEMA = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2"
_RESPONSE_SCHEMA = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3"
_CONCEPT_RESPONSE_SCHEMA = "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4"
_MAX_REQUEST_BYTES = 65_536
_MAX_RESPONSE_BYTES = 128_000
_CORRELATION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_ULID = re.compile(r"^[0-9A-Z]{26}$")
_PRODUCT_LISTING_ID = r"^[0-9A-HJKMNP-TV-Z]{26}$"
_BIDI = {
    "\u061c",
    "\u200e",
    "\u200f",
    "\u202a",
    "\u202b",
    "\u202c",
    "\u202d",
    "\u202e",
    "\u2066",
    "\u2067",
    "\u2068",
    "\u2069",
}


class MarketplaceRetrievalError(RuntimeError):
    """Carries one fixed safe Product/embedding failure classification."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)


class _HybridIdentity(_StrictModel):
    provider: Literal["openai"]
    model: Literal["text-embedding-3-small"]
    dimensions: Literal[1536]


class _HybridFilters(_StrictModel):
    seller_type: Literal["INDIVIDUAL"] = Field(alias="sellerType")
    category_id: str | None = Field(
        default=None,
        alias="categoryId",
        pattern=r"^[0-9A-Z]{26}$",
    )
    condition: Literal[
        "NEW",
        "OPEN_BOX",
        "LIKE_NEW",
        "GOOD",
        "FAIR",
        "FOR_PARTS",
    ] | None = None
    min_price: Decimal | None = Field(default=None, alias="minPrice", ge=0)
    max_price: Decimal | None = Field(default=None, alias="maxPrice", ge=0)
    currency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    city: str | None = Field(default=None, min_length=1, max_length=100)
    county: str | None = Field(default=None, min_length=1, max_length=100)
    availability: Literal["AVAILABLE"] = "AVAILABLE"


class _HybridRequest(_StrictModel):
    schema_version: Literal["MARKETPLACE_HYBRID_SEARCH_V1"] = Field(
        alias="schemaVersion"
    )
    query: str = Field(min_length=1, max_length=200)
    embedding_identity: _HybridIdentity = Field(alias="embeddingIdentity")
    embedding: tuple[float, ...] = Field(
        min_length=EMBEDDING_DIMENSIONS,
        max_length=EMBEDDING_DIMENSIONS,
    )
    filters: _HybridFilters
    sort: Literal["RELEVANCE"] = "RELEVANCE"
    limit: int = Field(ge=1, le=20)
    response_schema_version: Literal[
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2",
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3",
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
    ] = Field(
        default=_RESPONSE_SCHEMA, alias="responseSchemaVersion"
    )


class _HybridProvenance(_StrictModel):
    final_rank: int = Field(alias="finalRank", ge=1, le=20)
    mode: Literal["HYBRID", "LEXICAL_ONLY", "VECTOR_ONLY"]
    matched_by: tuple[Literal["LEXICAL", "VECTOR"], ...] = Field(
        alias="matchedBy",
        min_length=1,
        max_length=2,
    )
    reason_code: Literal[
        "LEXICAL_AND_VECTOR_MATCH",
        "LEXICAL_MATCH",
        "VECTOR_MATCH",
    ] = Field(alias="reasonCode")


class _HybridResult(_StrictModel):
    listing_id: str = Field(alias="listingId", pattern=_PRODUCT_LISTING_ID)
    listing_version: int = Field(alias="listingVersion", ge=0)
    title: str = Field(min_length=1, max_length=180)
    category_id: str = Field(alias="categoryId", pattern=r"^[0-9A-Z]{26}$")
    category_slug: str = Field(alias="categorySlug", min_length=1, max_length=120)
    category_name: str = Field(alias="categoryName", min_length=1, max_length=180)
    condition: Literal[
        "NEW",
        "OPEN_BOX",
        "LIKE_NEW",
        "GOOD",
        "FAIR",
        "FOR_PARTS",
    ]
    price_amount: Decimal = Field(alias="priceAmount", ge=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    public_city: str | None = Field(default=None, alias="publicCity", max_length=100)
    public_region: str | None = Field(
        default=None,
        alias="publicRegion",
        max_length=100,
    )
    available: Literal[True]
    primary_image_url: str | None = Field(
        default=None,
        alias="primaryImageUrl",
        max_length=1_000,
    )
    published_at: datetime = Field(alias="publishedAt")
    transaction_notice: str = Field(
        alias="transactionNotice",
        min_length=1,
        max_length=1_000,
    )
    provenance: _HybridProvenance
    concept_match: _HybridConceptMatch | None = Field(
        default=None, alias="conceptMatch"
    )


class _HybridConceptMatch(_StrictModel):
    relevance: Literal["HIGH", "MEDIUM"]
    complete_concept_match: Literal[True] = Field(alias="completeConceptMatch")
    product_type_compatible: Literal[True] = Field(alias="productTypeCompatible")
    matched_concepts: tuple[str, ...] = Field(
        alias="matchedConcepts", min_length=1, max_length=8
    )

    @model_validator(mode="after")
    def unique_safe_concepts(self) -> "_HybridConceptMatch":
        if len(set(self.matched_concepts)) != len(self.matched_concepts):
            raise ValueError("Hybrid concept labels must be unique")
        if any(not value.strip() or len(value) > 100 for value in self.matched_concepts):
            raise ValueError("Hybrid concept label is invalid")
        return self


class _HybridMeta(_StrictModel):
    retrieval_mode: Literal["HYBRID", "LEXICAL_ONLY", "VECTOR_ONLY"] = Field(
        alias="retrievalMode"
    )
    degraded: bool
    checked_at: datetime = Field(alias="checkedAt")


class _HybridFacetValue(_StrictModel):
    value: str = Field(min_length=1, max_length=100)
    count: int = Field(ge=1, le=80)


class _HybridFacets(_StrictModel):
    subtype: tuple[_HybridFacetValue, ...] = Field(max_length=12)
    condition: tuple[_HybridFacetValue, ...] = Field(max_length=12)
    price_band: tuple[_HybridFacetValue, ...] = Field(alias="priceBand", max_length=12)
    location: tuple[_HybridFacetValue, ...] = Field(max_length=12)


class _HybridDiscovery(_StrictModel):
    normalized_category: str = Field(alias="normalizedCategory", min_length=1, max_length=200)
    total_matches: int = Field(alias="totalMatches", ge=0, le=80)
    relevant_match_count: int | None = Field(
        default=None, alias="relevantMatchCount", ge=0, le=80
    )
    exact_match_count: int | None = Field(
        default=None, alias="exactMatchCount", ge=0, le=80
    )
    related_match_count: int | None = Field(
        default=None, alias="relatedMatchCount", ge=0, le=80
    )
    rerank_context: _HybridRerankContext | None = Field(
        default=None, alias="rerankContext"
    )
    rejected_candidate_count: int | None = Field(
        default=None, alias="rejectedCandidateCount", ge=0, le=80
    )
    retrieval_confidence: Literal["HIGH", "MEDIUM", "LOW"] | None = Field(
        default=None, alias="retrievalConfidence"
    )
    reason: Literal["RESULTS_AVAILABLE", "CATEGORY_UNAVAILABLE", "LOW_RELEVANCE"]
    facets: _HybridFacets


class _HybridRerankContext(_StrictModel):
    core_concepts: tuple[str, ...] = Field(alias="coreConcepts", min_length=1, max_length=8)
    candidate_product_types: tuple[str, ...] = Field(
        alias="candidateProductTypes", min_length=1, max_length=12
    )
    excluded_broad_types: tuple[str, ...] = Field(
        alias="excludedBroadTypes", max_length=12
    )


class _HybridResponse(_StrictModel):
    schema_version: Literal[
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1",
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2",
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3",
        "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
    ] = Field(
        alias="schemaVersion"
    )
    data: tuple[_HybridResult, ...] = Field(max_length=20)
    meta: _HybridMeta
    discovery: _HybridDiscovery | None = None

    @model_validator(mode="after")
    def validate_rank_and_mode(self) -> "_HybridResponse":
        if len({item.listing_id for item in self.data}) != len(self.data):
            raise ValueError("Hybrid response listing IDs must be unique")
        for expected_rank, item in enumerate(self.data, 1):
            if item.provenance.final_rank != expected_rank:
                raise ValueError("Hybrid response ranks must be contiguous")
            if not _valid_provenance(item.provenance):
                raise ValueError("Hybrid response provenance is inconsistent")
        if self.meta.degraded == (self.meta.retrieval_mode == "HYBRID"):
            raise ValueError("Hybrid response degradation metadata is inconsistent")
        if self.schema_version in {
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2", _RESPONSE_SCHEMA,
            _CONCEPT_RESPONSE_SCHEMA,
        } and self.discovery is None:
            raise ValueError("Hybrid discovery response requires metadata")
        if self.schema_version == "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1" and self.discovery is not None:
            raise ValueError("Hybrid V1 response cannot contain V2 discovery metadata")
        if self.discovery is not None:
            if self.schema_version in {_RESPONSE_SCHEMA, _CONCEPT_RESPONSE_SCHEMA}:
                if (
                    self.discovery.relevant_match_count is None
                    or self.discovery.retrieval_confidence is None
                ):
                    raise ValueError("Hybrid V3 response requires relevance evidence")
            elif (
                self.discovery.relevant_match_count is not None
                or self.discovery.retrieval_confidence is not None
                or self.discovery.reason == "LOW_RELEVANCE"
            ):
                raise ValueError("Hybrid V2 response cannot contain V3 relevance evidence")
            relevant = (
                self.discovery.relevant_match_count
                if self.discovery.relevant_match_count is not None
                else self.discovery.total_matches
            )
            if self.schema_version == _CONCEPT_RESPONSE_SCHEMA:
                if (
                    self.discovery.exact_match_count is None
                    or self.discovery.related_match_count is None
                    or self.discovery.rerank_context is None
                    or self.discovery.rejected_candidate_count is None
                    or any(item.concept_match is None for item in self.data)
                ):
                    raise ValueError("Hybrid V4 response requires concept evidence")
                if self.discovery.exact_match_count + self.discovery.related_match_count != relevant:
                    raise ValueError("Hybrid V4 match counts are inconsistent")
                if self.discovery.rejected_candidate_count + relevant != self.discovery.total_matches:
                    raise ValueError("Hybrid V4 rejected count is inconsistent")
                seen_related = False
                allowed_concepts = set(self.discovery.rerank_context.core_concepts)
                for item in self.data:
                    assert item.concept_match is not None
                    if item.concept_match.relevance == "MEDIUM":
                        seen_related = True
                    elif seen_related:
                        raise ValueError("Hybrid V4 exact matches must precede related matches")
                    if not set(item.concept_match.matched_concepts) <= allowed_concepts:
                        raise ValueError("Hybrid V4 result invented a concept label")
            elif any(item.concept_match is not None for item in self.data):
                raise ValueError("Only Hybrid V4 may contain concept evidence")
            if relevant < len(self.data):
                raise ValueError("Hybrid relevant matches cannot be below returned results")
            if relevant > self.discovery.total_matches:
                raise ValueError("Hybrid relevant matches cannot exceed total matches")
            if (self.discovery.total_matches == 0) != (self.discovery.reason == "CATEGORY_UNAVAILABLE"):
                raise ValueError("Hybrid result reason is inconsistent")
            if self.discovery.reason == "RESULTS_AVAILABLE" and relevant == 0:
                raise ValueError("Hybrid available result requires relevant matches")
            if self.discovery.reason == "LOW_RELEVANCE" and self.discovery.retrieval_confidence != "LOW":
                raise ValueError("Hybrid low-relevance result requires low confidence")
            for values in (
                self.discovery.facets.subtype,
                self.discovery.facets.condition,
                self.discovery.facets.price_band,
                self.discovery.facets.location,
            ):
                if len({item.value.casefold() for item in values}) != len(values):
                    raise ValueError("Hybrid facet values must be unique")
                if any(item.count > relevant for item in values):
                    raise ValueError("Hybrid facet count exceeds relevant matches")
            if self.discovery.facets.subtype and sum(
                item.count for item in self.discovery.facets.subtype
            ) != relevant:
                raise ValueError("Hybrid subtype facets must account for every relevant match")
        return self


class HybridMarketplaceDiscoveryClient:
    """Embeds one bounded phrase and delegates all retrieval/ranking to Product."""

    def __init__(
        self,
        *,
        product: DiscoveryProductTool,
        product_base_url: str,
        product_service_token: str,
        embedding_provider: EmbeddingProvider,
        timeout_seconds: float = 2.0,
        query_timeout_seconds: float = 8.0,
        response_schema_version: Literal[
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2",
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V3",
            "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V4",
        ] = _RESPONSE_SCHEMA,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not product_service_token:
            raise ValueError("Hybrid marketplace retrieval requires a Product token")
        if not 0.1 <= timeout_seconds <= 2.0:
            raise ValueError("Product hybrid timeout must not exceed two seconds")
        if not 0.1 <= query_timeout_seconds <= 8.0:
            raise ValueError("Query embedding timeout must not exceed eight seconds")
        self._product = product
        self._base_url = product_base_url.rstrip("/")
        self._token = product_service_token
        self._embedding_provider = embedding_provider
        self._timeout = timeout_seconds
        self._query_timeout = query_timeout_seconds
        self._response_schema = response_schema_version
        self._client = client

    async def probe_availability(
        self,
        *,
        actor_user_id: str,
        category: str,
        correlation_id: str,
        turn_deadline_monotonic: float | None = None,
        product_timeout_seconds: float | None = None,
    ) -> DiscoveryAvailabilityProbe:
        """Delegate the broad probe without generating a query embedding."""

        return await self._product.probe_availability(
            actor_user_id=actor_user_id,
            category=category,
            correlation_id=correlation_id,
            turn_deadline_monotonic=turn_deadline_monotonic,
            product_timeout_seconds=product_timeout_seconds or self._timeout,
              )

    async def search_individual(
        self,
        *,
        actor_user_id: str,
        request: DiscoverySearchRequest,
        correlation_id: str,
        turn_deadline_monotonic: float | None = None,
        query_timeout_seconds: float | None = None,
        product_timeout_seconds: float | None = None,
    ) -> DiscoverySearchPage:
        """Generate one ephemeral query vector and accept only Product-ranked IDs."""

        if _ULID.fullmatch(actor_user_id) is None:
            raise ValueError("Discovery actor identity is invalid")
        if _CORRELATION.fullmatch(correlation_id) is None:
            raise ValueError("Discovery correlation ID is invalid")
        query = normalize_hybrid_query(request.q)
        if (request.min_price is not None or request.max_price is not None) and (
            request.currency is None
        ):
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_CURRENCY_REQUIRED",
                retryable=False,
            )
        embedding = None
        for attempt in range(2):
            query_timeout = _remaining_step_timeout(
                turn_deadline_monotonic,
                query_timeout_seconds or self._query_timeout,
                "MARKETPLACE_HYBRID_QUERY_EMBEDDING_TIMEOUT",
            )
            try:
                embedding = await asyncio.wait_for(
                    self._embedding_provider.embed(
                        [query],
                        correlation_id=correlation_id,
                    ),
                    timeout=query_timeout,
                )
                break
            except asyncio.TimeoutError as error:
                if attempt == 0:
                    continue
                raise MarketplaceRetrievalError(
                    "MARKETPLACE_HYBRID_QUERY_EMBEDDING_TIMEOUT",
                    retryable=True,
                ) from error
            except EmbeddingProviderError as error:
                raise MarketplaceRetrievalError(
                    "MARKETPLACE_HYBRID_QUERY_EMBEDDING_FAILED",
                    retryable=error.retryable,
                ) from error
        if embedding is None:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_QUERY_EMBEDDING_TIMEOUT",
                retryable=True,
            )
        vector = _validated_vector(embedding)
        payload_model = _HybridRequest(
            schemaVersion=_REQUEST_SCHEMA,
            query=query,
            embeddingIdentity={
                "provider": EMBEDDING_PROVIDER,
                "model": EMBEDDING_MODEL,
                "dimensions": EMBEDDING_DIMENSIONS,
            },
            embedding=vector,
            filters={
                "sellerType": "INDIVIDUAL",
                "categoryId": request.category_id,
                "condition": request.condition,
                "minPrice": request.min_price,
                "maxPrice": request.max_price,
                "currency": request.currency,
                "city": request.city,
                "county": request.county,
                "availability": "AVAILABLE",
            },
            sort="RELEVANCE",
            limit=request.limit,
            responseSchemaVersion=self._response_schema,
        )
        payload_value = payload_model.model_dump(
                mode="json",
                by_alias=True,
                exclude_none=True,
            )
        filter_value = payload_value["filters"]
        assert isinstance(filter_value, dict)
        if request.min_price is not None:
            filter_value["minPrice"] = _json_price(request.min_price)
        if request.max_price is not None:
            filter_value["maxPrice"] = _json_price(request.max_price)
        payload = json.dumps(
            payload_value,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        if len(payload) > _MAX_REQUEST_BYTES:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_REQUEST_INVALID",
                retryable=False,
            )
        product_timeout = _remaining_step_timeout(
            turn_deadline_monotonic,
            product_timeout_seconds or self._timeout,
            "MARKETPLACE_HYBRID_PRODUCT_TIMEOUT",
        )
        response = await self._post(payload, correlation_id, product_timeout)
        if response.status_code != 200:
            raise _mapped_product_error(response.status_code)
        if len(response.content) > _MAX_RESPONSE_BYTES:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_RESPONSE_INVALID",
                retryable=False,
            )
        try:
            parsed = _HybridResponse.model_validate_json(response.content)
        except ValidationError as error:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_RESPONSE_INVALID",
                retryable=False,
            ) from error
        # Legacy discovery asked Product for V2 but historically tolerated a V1
        # body during rolling upgrades. V2 Agent is the first consumer that needs
        # relevance evidence, so its V3 contract remains exact and fail-closed.
        accepted_response_schemas = (
            {self._response_schema}
            if self._response_schema in {_RESPONSE_SCHEMA, _CONCEPT_RESPONSE_SCHEMA}
            else {
                "MARKETPLACE_HYBRID_SEARCH_RESPONSE_V1",
                _FACET_RESPONSE_SCHEMA,
            }
        )
        if parsed.schema_version not in accepted_response_schemas or (
            self._response_schema in {_RESPONSE_SCHEMA, _CONCEPT_RESPONSE_SCHEMA}
            and parsed.discovery is None
        ):
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_RESPONSE_INVALID",
                retryable=False,
            )
        if len(parsed.data) > request.limit:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_RESPONSE_INVALID",
                retryable=False,
            )
        candidates = tuple(
            DiscoverySearchCandidate(
                listingId=item.listing_id,
                matchQuality=(
                    "EXACT" if item.concept_match is not None
                    and item.concept_match.relevance == "HIGH"
                    else "RELATED" if item.concept_match is not None else None
                ),
                retrieval=DiscoveryRetrievalProvenance(
                    listingId=item.listing_id,
                    listingVersion=item.listing_version,
                    finalRank=item.provenance.final_rank,
                    mode=item.provenance.mode,
                    matchedBy=item.provenance.matched_by,
                    reasonCode=item.provenance.reason_code,
                    checkedAt=parsed.meta.checked_at,
                ),
            )
            for item in parsed.data
        )
        return DiscoverySearchPage(
            data=candidates,
            page={"nextCursor": None, "hasMore": False},
            summary=(
                DiscoverySearchSummary(
                    normalizedCategory=parsed.discovery.normalized_category,
                    totalMatches=parsed.discovery.total_matches,
                    relevantMatchCount=parsed.discovery.relevant_match_count,
                    exactMatchCount=parsed.discovery.exact_match_count,
                    relatedMatchCount=parsed.discovery.related_match_count,
                    rejectedCandidateCount=parsed.discovery.rejected_candidate_count,
                    retrievalConfidence=parsed.discovery.retrieval_confidence,
                    reason=parsed.discovery.reason,
                    facets=DiscoverySearchFacets(
                        subtype=tuple(
                            item.model_dump() for item in parsed.discovery.facets.subtype
                        ),
                        condition=tuple(
                            item.model_dump() for item in parsed.discovery.facets.condition
                        ),
                        priceBand=tuple(
                            item.model_dump() for item in parsed.discovery.facets.price_band
                        ),
                        location=tuple(
                            item.model_dump() for item in parsed.discovery.facets.location
                        ),
                    ),
                )
                if parsed.discovery is not None
                else None
            ),
        )

    async def get_listing(self, **kwargs: object) -> CheckedListing | None:
        return await self._product.get_listing(**kwargs)

    async def _post(
        self,
        payload: bytes,
        correlation_id: str,
        timeout_seconds: float,
    ) -> httpx.Response:
        headers = {
            "Content-Type": "application/json",
            "X-Agent-Internal-Service-Token": self._token,
            "X-Correlation-Id": correlation_id,
        }
        url = (
            f"{self._base_url}"
            "/api/v1/internal/agent/marketplace/listings/hybrid-search"
        )
        try:
            if self._client is not None:
                return await self._client.post(
                    url,
                    content=payload,
                    headers=headers,
                    timeout=timeout_seconds,
                    follow_redirects=False,
                )
            async with httpx.AsyncClient(
                timeout=timeout_seconds,
                follow_redirects=False,
            ) as client:
                return await client.post(url, content=payload, headers=headers)
        except httpx.TimeoutException as error:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_PRODUCT_TIMEOUT",
                retryable=True,
            ) from error
        except httpx.TransportError as error:
            raise MarketplaceRetrievalError(
                "MARKETPLACE_HYBRID_PRODUCT_TRANSPORT_FAILED",
                retryable=True,
            ) from error


def normalize_hybrid_query(value: str | None) -> str:
    """Match Product's NFKC/control/bidi normalization before provider access."""

    if value is None:
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_QUERY_REQUIRED",
            retryable=False,
        )
    normalized = unicodedata.normalize("NFKC", value)
    characters = []
    for character in normalized:
        if character.isspace():
            characters.append(" ")
        elif character not in _BIDI and unicodedata.category(character) != "Cc":
            characters.append(character)
    safe = " ".join("".join(characters).split())
    if not 1 <= len(safe) <= 200:
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_QUERY_INVALID",
            retryable=False,
        )
    return safe


def _remaining_step_timeout(
    deadline_monotonic: float | None,
    step_timeout_seconds: float,
    timeout_code: str,
) -> float:
    """Clip one hybrid dependency step to the remaining whole-turn budget."""

    if deadline_monotonic is None:
        return step_timeout_seconds
    remaining = deadline_monotonic - time.monotonic()
    if remaining <= 0:
        raise MarketplaceRetrievalError(timeout_code, retryable=True)
    return min(step_timeout_seconds, remaining)


def _validated_vector(result: object) -> tuple[float, ...]:
    provider = getattr(result, "provider", None)
    model = getattr(result, "model", None)
    dimensions = getattr(result, "dimensions", None)
    vectors = getattr(result, "vectors", ())
    if (
        provider != EMBEDDING_PROVIDER
        or model != EMBEDDING_MODEL
        or dimensions != EMBEDDING_DIMENSIONS
        or len(vectors) != 1
        or len(vectors[0]) != EMBEDDING_DIMENSIONS
    ):
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_QUERY_EMBEDDING_INVALID",
            retryable=False,
        )
    canonical = []
    try:
        for value in vectors[0]:
            number = float(value)
            encoded = struct.pack(">f", number)
            decoded = struct.unpack(">f", encoded)[0]
            if not math.isfinite(number) or not math.isfinite(decoded):
                raise ValueError
            canonical.append(0.0 if decoded == 0.0 else decoded)
    except (OverflowError, TypeError, ValueError, struct.error) as error:
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_QUERY_EMBEDDING_INVALID",
            retryable=False,
        ) from error
    return tuple(canonical)


def _json_price(value: Decimal) -> int | float:
    if (
        value < 0
        or value > Decimal("999999999999.99")
        or max(0, -value.as_tuple().exponent) > 2
    ):
        raise MarketplaceRetrievalError(
            "MARKETPLACE_HYBRID_REQUEST_INVALID",
            retryable=False,
        )
    return int(value) if value == value.to_integral_value() else float(value)


def _valid_provenance(value: _HybridProvenance) -> bool:
    expected = {
        "HYBRID": (("LEXICAL", "VECTOR"), "LEXICAL_AND_VECTOR_MATCH"),
        "LEXICAL_ONLY": (("LEXICAL",), "LEXICAL_MATCH"),
        "VECTOR_ONLY": (("VECTOR",), "VECTOR_MATCH"),
    }[value.mode]
    return value.matched_by == expected[0] and value.reason_code == expected[1]


def _mapped_product_error(status_code: int) -> MarketplaceRetrievalError:
    mapping = {
        400: ("MARKETPLACE_HYBRID_PRODUCT_INVALID_REQUEST", False),
        403: ("MARKETPLACE_HYBRID_PRODUCT_AUTHENTICATION_REQUIRED", False),
        404: ("MARKETPLACE_HYBRID_PRODUCT_DISABLED", False),
        409: ("MARKETPLACE_HYBRID_PRODUCT_IDENTITY_MISMATCH", False),
        503: ("MARKETPLACE_HYBRID_PRODUCT_UNAVAILABLE", True),
    }
    code, retryable = mapping.get(
        status_code,
        ("MARKETPLACE_HYBRID_PRODUCT_UNAVAILABLE", status_code >= 500),
    )
    return MarketplaceRetrievalError(code, retryable=retryable)
