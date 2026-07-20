from __future__ import annotations

import math
import re
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

SourceType = Literal[
    "LISTING",
    "MARKETPLACE_POLICY",
    "SAFETY_GUIDANCE",
    "MARKETPLACE_FAQ",
    "CATEGORY_GUIDANCE",
]

_HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_LANGUAGE_PATTERN = re.compile(r"^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")


class KnowledgeChunkDocument(BaseModel):
    """Validates one public knowledge chunk before OpenSearch receives it."""

    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
    )

    chunk_id: str = Field(alias="chunkId", min_length=1, max_length=160)
    source_type: SourceType = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId", min_length=1, max_length=160)
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=80)
    content_hash: str = Field(alias="contentHash")
    listing_id: str | None = Field(default=None, alias="listingId", max_length=160)
    visibility: Literal["PUBLIC"] = "PUBLIC"
    language: str = Field(min_length=2, max_length=40)
    effective_from: datetime | None = Field(default=None, alias="effectiveFrom")
    effective_to: datetime | None = Field(default=None, alias="effectiveTo")
    indexed_at: datetime = Field(alias="indexedAt")
    invalidated_at: datetime | None = Field(default=None, alias="invalidatedAt")
    ordinal: int = Field(ge=0, le=100_000)
    section_label: str = Field(alias="sectionLabel", min_length=1, max_length=200)
    text: str = Field(min_length=1, max_length=8_000)
    embedding: list[float] = Field(min_length=1, max_length=16_000)

    @field_validator("chunk_id", "source_id", "source_version")
    @classmethod
    def safe_identifier(cls, value: str) -> str:
        if any(character.isspace() for character in value):
            raise ValueError("identifier fields must not contain whitespace")
        return value

    @field_validator("content_hash")
    @classmethod
    def valid_content_hash(cls, value: str) -> str:
        if _HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("contentHash must be a lowercase SHA-256 digest")
        return value

    @field_validator("language")
    @classmethod
    def valid_language(cls, value: str) -> str:
        normalized = value.lower()
        if _LANGUAGE_PATTERN.fullmatch(normalized) is None:
            raise ValueError("language must be a normalized language tag")
        return normalized

    @field_validator("embedding")
    @classmethod
    def finite_embedding(cls, value: list[float]) -> list[float]:
        if any(not math.isfinite(component) for component in value):
            raise ValueError("embedding values must be finite")
        return value

    @field_validator(
        "effective_from",
        "effective_to",
        "indexed_at",
        "invalidated_at",
    )
    @classmethod
    def timezone_aware_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("knowledge timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def consistent_scope_and_time(self) -> "KnowledgeChunkDocument":
        if self.source_type == "LISTING" and not self.listing_id:
            raise ValueError("LISTING chunks require listingId")
        if self.source_type != "LISTING" and self.listing_id is not None:
            raise ValueError("non-LISTING chunks must not include listingId")
        if (
            self.effective_from is not None
            and self.effective_to is not None
            and self.effective_to <= self.effective_from
        ):
            raise ValueError("effectiveTo must be later than effectiveFrom")
        if self.invalidated_at is not None and self.invalidated_at < self.indexed_at:
            raise ValueError("invalidatedAt must not precede indexedAt")
        return self

    def validate_embedding_dimensions(self, expected: int) -> None:
        """Reject vectors that cannot match the immutable index mapping."""

        if len(self.embedding) != expected:
            raise ValueError(
                f"embedding must contain exactly {expected} dimensions"
            )

    def open_search_source(self) -> dict[str, object]:
        """Serialize using the strict camel-case index field contract."""

        return self.model_dump(mode="json", by_alias=True, exclude_none=True)


def fixed_metadata_filters(
    *,
    source_types: list[SourceType] | None = None,
    source_id: str | None = None,
    source_version: str | None = None,
    listing_id: str | None = None,
    language: str | None = None,
    languages: list[str] | None = None,
    effective_at: datetime | None = None,
    active_only: bool = True,
) -> list[dict[str, object]]:
    """Build only allowlisted exact filters for later vector retrieval."""

    filters: list[dict[str, object]] = [{"term": {"visibility": "PUBLIC"}}]
    if source_types:
        filters.append({"terms": {"sourceType": source_types}})
    if source_id:
        filters.append({"term": {"sourceId": source_id}})
    if source_version:
        filters.append({"term": {"sourceVersion": source_version}})
    if listing_id:
        filters.append({"term": {"listingId": listing_id}})
    if language and languages:
        raise ValueError("language and languages filters are mutually exclusive")
    if language:
        filters.append({"term": {"language": language.lower()}})
    if languages:
        normalized_languages = list(
            dict.fromkeys(value.lower() for value in languages)
        )
        if not normalized_languages:
            raise ValueError("languages filter must not be empty")
        filters.append({"terms": {"language": normalized_languages}})
    if effective_at:
        if effective_at.tzinfo is None or effective_at.utcoffset() is None:
            raise ValueError("effective_at must include a UTC offset")
        timestamp = effective_at.isoformat()
        filters.extend(
            [
                {
                    "bool": {
                        "should": [
                            {"bool": {"must_not": {"exists": {"field": "effectiveFrom"}}}},
                            {"range": {"effectiveFrom": {"lte": timestamp}}},
                        ],
                        "minimum_should_match": 1,
                    }
                },
                {
                    "bool": {
                        "should": [
                            {"bool": {"must_not": {"exists": {"field": "effectiveTo"}}}},
                            {"range": {"effectiveTo": {"gt": timestamp}}},
                        ],
                        "minimum_should_match": 1,
                    }
                },
            ]
        )
    if active_only:
        filters.append({"bool": {"must_not": {"exists": {"field": "invalidatedAt"}}}})
    return filters
