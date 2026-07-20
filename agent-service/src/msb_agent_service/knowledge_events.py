from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from enum import StrEnum
from typing import Literal, TypeAlias

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

# Listing and event identifiers follow the repository's fixed 26-character
# token contract. Existing domain identifiers are not all canonical ULIDs.
FIXED_ID_PATTERN = re.compile(r"^[0-9A-Z]{26}$")
VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]{0,19})$")
HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
LANGUAGE_PATTERN = re.compile(r"^[a-z0-9]{2,8}(?:-[a-z0-9]{1,8})*$")


class KnowledgeEventErrorCode(StrEnum):
    """Stable, content-free classifications for rejected Kafka records."""

    INVALID_UTF8 = "INVALID_UTF8"
    INVALID_JSON = "INVALID_JSON"
    INVALID_SCHEMA = "INVALID_SCHEMA"
    INVALID_MESSAGE_KEY = "INVALID_MESSAGE_KEY"
    EVENT_ID_CONFLICT = "EVENT_ID_CONFLICT"


class KnowledgeEventValidationError(ValueError):
    """Carries a safe classification without retaining the rejected payload."""

    def __init__(self, code: KnowledgeEventErrorCode) -> None:
        super().__init__(code.value)
        self.code = code


class CompatibleEventModel(BaseModel):
    """Ignores additive fields while validating every version-1 contract field."""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)


class ListingKnowledgeEventPayload(CompatibleEventModel):
    listing_id: str = Field(alias="listingId")
    listing_version: str = Field(alias="listingVersion")
    knowledge_lifecycle: Literal["ACTIVE", "INVALIDATED"] = Field(
        alias="knowledgeLifecycle"
    )
    supersedes_version: str | None = Field(alias="supersedesVersion")
    language: str

    @field_validator("listing_id")
    @classmethod
    def validate_listing_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError("listingId must be an uppercase 26-character ID")
        return value

    @field_validator("listing_version", "supersedes_version")
    @classmethod
    def validate_version(cls, value: str | None) -> str | None:
        if value is not None and not VERSION_PATTERN.fullmatch(value):
            raise ValueError("listing versions must be unsigned decimal strings")
        return value

    @field_validator("language")
    @classmethod
    def validate_language(cls, value: str) -> str:
        normalized = value.lower()
        if value != normalized or not LANGUAGE_PATTERN.fullmatch(value):
            raise ValueError("language must be a lowercase BCP-47 style tag")
        return value

    @model_validator(mode="after")
    def validate_version_order(self) -> "ListingKnowledgeEventPayload":
        source_version = int(self.listing_version)
        if (
            self.supersedes_version is not None
            and int(self.supersedes_version) >= source_version
        ):
            raise ValueError("supersedesVersion must be older than listingVersion")
        return self


class ListingKnowledgeEventEnvelope(CompatibleEventModel):
    event_id: str = Field(alias="eventId")
    event_type: Literal[
        "listing.activated",
        "listing.updated",
        "listing.deactivated",
    ] = Field(alias="eventType")
    event_version: Literal[1] = Field(alias="eventVersion")
    occurred_at: datetime = Field(alias="occurredAt")
    producer: Literal["product-service"]
    aggregate_type: Literal["listing"] = Field(alias="aggregateType")
    aggregate_id: str = Field(alias="aggregateId")
    correlation_id: str | None = Field(alias="correlationId")
    payload: ListingKnowledgeEventPayload

    @field_validator("event_id", "aggregate_id")
    @classmethod
    def validate_fixed_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError(
                "event and aggregate IDs must be uppercase 26-character IDs"
            )
        return value

    @field_validator("occurred_at")
    @classmethod
    def validate_timestamp(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("occurredAt must include a UTC offset")
        return value

    @field_validator("correlation_id")
    @classmethod
    def validate_correlation_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if (
            not 1 <= len(value) <= 100
            or value != value.strip()
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
        ):
            raise ValueError("correlationId contains unsafe characters")
        return value

    @model_validator(mode="after")
    def validate_cross_field_contract(self) -> "ListingKnowledgeEventEnvelope":
        if self.aggregate_id != self.payload.listing_id:
            raise ValueError("aggregateId must equal payload.listingId")
        expected_lifecycle = (
            "INVALIDATED"
            if self.event_type == "listing.deactivated"
            else "ACTIVE"
        )
        if self.payload.knowledge_lifecycle != expected_lifecycle:
            raise ValueError("eventType and knowledgeLifecycle do not agree")
        if (
            self.event_type in {"listing.updated", "listing.deactivated"}
            and self.payload.supersedes_version is None
        ):
            raise ValueError("updated and deactivated events require supersedesVersion")
        return self

    @property
    def source_version(self) -> int:
        return int(self.payload.listing_version)

    @property
    def source_type(self) -> str:
        return "LISTING"

    @property
    def source_id(self) -> str:
        return self.payload.listing_id

    @property
    def language(self) -> str:
        return self.payload.language

    @property
    def lifecycle(self) -> str:
        return self.payload.knowledge_lifecycle

    @property
    def superseded_version(self) -> int | None:
        return (
            None
            if self.payload.supersedes_version is None
            else int(self.payload.supersedes_version)
        )

    def payload_hash(self) -> str:
        """Hash only contracted payload fields so additive fields stay compatible."""

        canonical = json.dumps(
            self.payload.model_dump(by_alias=True, mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()


class CategoryGuidanceEventPayload(CompatibleEventModel):
    source_type: Literal["CATEGORY_GUIDANCE"] = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId")
    source_version_value: str = Field(alias="sourceVersion")
    knowledge_lifecycle: Literal["ACTIVE", "INVALIDATED"] = Field(
        alias="knowledgeLifecycle"
    )
    supersedes_version: str | None = Field(alias="supersedesVersion")
    language: str

    @field_validator("source_id")
    @classmethod
    def validate_source_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError("sourceId must be an uppercase 26-character ID")
        return value

    @field_validator("source_version_value", "supersedes_version")
    @classmethod
    def validate_version(cls, value: str | None) -> str | None:
        if (
            value is not None
            and (
                not VERSION_PATTERN.fullmatch(value)
                or int(value) == 0
            )
        ):
            raise ValueError("category guidance versions must be positive decimals")
        return value

    @field_validator("language")
    @classmethod
    def validate_language(cls, value: str) -> str:
        if value != value.lower() or not LANGUAGE_PATTERN.fullmatch(value):
            raise ValueError("language must be a lowercase BCP-47 style tag")
        return value

    @model_validator(mode="after")
    def validate_version_order(self) -> "CategoryGuidanceEventPayload":
        if (
            self.supersedes_version is not None
            and int(self.supersedes_version) >= int(self.source_version_value)
        ):
            raise ValueError("supersedesVersion must be older than sourceVersion")
        return self


class CategoryGuidanceEventEnvelope(CompatibleEventModel):
    event_id: str = Field(alias="eventId")
    event_type: Literal[
        "category-guidance.activated",
        "category-guidance.updated",
        "category-guidance.invalidated",
    ] = Field(alias="eventType")
    event_version: Literal[1] = Field(alias="eventVersion")
    occurred_at: datetime = Field(alias="occurredAt")
    producer: Literal["product-service"]
    aggregate_type: Literal["category-guidance"] = Field(alias="aggregateType")
    aggregate_id: str = Field(alias="aggregateId")
    correlation_id: str | None = Field(alias="correlationId")
    payload: CategoryGuidanceEventPayload

    @field_validator("event_id", "aggregate_id")
    @classmethod
    def validate_fixed_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError(
                "event and aggregate IDs must be uppercase 26-character IDs"
            )
        return value

    @field_validator("occurred_at")
    @classmethod
    def validate_timestamp(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("occurredAt must include a UTC offset")
        return value

    @field_validator("correlation_id")
    @classmethod
    def validate_correlation_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        if (
            not 1 <= len(value) <= 100
            or value != value.strip()
            or any(ord(character) < 32 or ord(character) == 127 for character in value)
        ):
            raise ValueError("correlationId contains unsafe characters")
        return value

    @model_validator(mode="after")
    def validate_cross_field_contract(self) -> "CategoryGuidanceEventEnvelope":
        if self.aggregate_id != self.payload.source_id:
            raise ValueError("aggregateId must equal payload.sourceId")
        expected_lifecycle = (
            "INVALIDATED"
            if self.event_type == "category-guidance.invalidated"
            else "ACTIVE"
        )
        if self.payload.knowledge_lifecycle != expected_lifecycle:
            raise ValueError("eventType and knowledgeLifecycle do not agree")
        if (
            self.event_type
            in {"category-guidance.updated", "category-guidance.invalidated"}
            and self.payload.supersedes_version is None
        ):
            raise ValueError("updated and invalidated events require supersedesVersion")
        return self

    @property
    def source_type(self) -> str:
        return self.payload.source_type

    @property
    def source_id(self) -> str:
        return self.payload.source_id

    @property
    def source_version(self) -> int:
        return int(self.payload.source_version_value)

    @property
    def language(self) -> str:
        return self.payload.language

    @property
    def lifecycle(self) -> str:
        return self.payload.knowledge_lifecycle

    @property
    def superseded_version(self) -> int | None:
        return (
            None
            if self.payload.supersedes_version is None
            else int(self.payload.supersedes_version)
        )

    def payload_hash(self) -> str:
        """Hash only contracted payload fields for durable deduplication."""

        canonical = json.dumps(
            self.payload.model_dump(by_alias=True, mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()


KnowledgeEventEnvelope: TypeAlias = (
    ListingKnowledgeEventEnvelope | CategoryGuidanceEventEnvelope
)


def parse_listing_knowledge_event(
    raw_value: bytes,
    message_key: bytes | None,
) -> ListingKnowledgeEventEnvelope:
    """Decode and strictly validate a reference-only listing event."""

    try:
        decoded = raw_value.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_UTF8
        ) from exc
    try:
        parsed = json.loads(decoded)
    except json.JSONDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_JSON
        ) from exc
    try:
        event = ListingKnowledgeEventEnvelope.model_validate(parsed)
    except ValidationError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_SCHEMA
        ) from exc

    if message_key is None:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        )
    try:
        key = message_key.decode("ascii")
    except UnicodeDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        ) from exc
    if key != event.payload.listing_id:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        )
    return event


def parse_category_guidance_event(
    raw_value: bytes,
    message_key: bytes | None,
) -> CategoryGuidanceEventEnvelope:
    """Decode and strictly validate a reference-only category event."""

    try:
        decoded = raw_value.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_UTF8
        ) from exc
    try:
        parsed = json.loads(decoded)
    except json.JSONDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_JSON
        ) from exc
    try:
        event = CategoryGuidanceEventEnvelope.model_validate(parsed)
    except ValidationError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_SCHEMA
        ) from exc
    if message_key is None:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        )
    try:
        key = message_key.decode("ascii")
    except UnicodeDecodeError as exc:
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        ) from exc
    if key != f"{event.source_id}:{event.language}":
        raise KnowledgeEventValidationError(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY
        )
    return event


def validate_hash(value: str) -> str:
    """Validate a stored lowercase SHA-256 before it enters a domain object."""

    if not HASH_PATTERN.fullmatch(value):
        raise ValueError("hash must be lowercase SHA-256")
    return value
