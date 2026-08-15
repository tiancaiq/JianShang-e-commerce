from __future__ import annotations

import hashlib
import json
import math
import re
from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

EVENT_TOPIC = "listing-discovery-embedding-request-v1"
EVENT_TYPE = "listing.discovery.embedding-requested"
DOCUMENT_SCHEMA_VERSION = "MARKETPLACE_LISTING_DISCOVERY_V2"
INPUT_SCHEMA_VERSION = "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1"
NORMALIZER_VERSION = "NFKC_WHITESPACE_V1"
REDACTOR_VERSION = "PUBLIC_CONTACT_REDACTION_V1"
SOURCE_SCHEMA_VERSION = "MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1"
RESULT_SCHEMA_VERSION = "MARKETPLACE_LISTING_EMBEDDING_RESULT_V1"
RESULT_ACK_SCHEMA_VERSION = "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1"
EMBEDDING_PROVIDER = "openai"
EMBEDDING_MODEL = "text-embedding-3-small"
EMBEDDING_DIMENSIONS = 1536
LANGUAGE = "und"
MAX_EVENT_BYTES = 8_192
MAX_SOURCE_BYTES = 32_768
MAX_EMBEDDING_TEXT_CHARACTERS = 8_000
MAX_RESULT_BYTES = 65_536

ULID_PATTERN = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")
HASH_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CORRELATION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class _StrictModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        str_strip_whitespace=False,
    )


class DiscoveryEmbeddingContractErrorCode(StrEnum):
    INVALID_UTF8 = "INVALID_UTF8"
    INVALID_JSON = "INVALID_JSON"
    INVALID_SCHEMA = "INVALID_SCHEMA"
    INVALID_TOPIC = "INVALID_TOPIC"
    INVALID_MESSAGE_KEY = "INVALID_MESSAGE_KEY"
    EVENT_ID_CONFLICT = "EVENT_ID_CONFLICT"
    REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT"


class DiscoveryEmbeddingContractError(ValueError):
    """Carries only a bounded contract failure code, never rejected content."""

    def __init__(self, code: DiscoveryEmbeddingContractErrorCode) -> None:
        super().__init__(code.value)
        self.code = code


class EmbeddingIdentity(_StrictModel):
    provider: Literal["openai"]
    model: Literal["text-embedding-3-small"]
    dimensions: Literal[1536]


class DiscoveryEmbeddingEventPayload(_StrictModel):
    request_id: str = Field(alias="requestId")
    listing_id: str = Field(alias="listingId")
    listing_version: int = Field(alias="listingVersion", ge=0, le=2**63 - 1)
    document_schema_version: Literal["MARKETPLACE_LISTING_DISCOVERY_V2"] = Field(
        alias="documentSchemaVersion"
    )
    document_hash: str = Field(alias="documentHash")
    embedding_input_schema_version: Literal[
        "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1"
    ] = Field(alias="embeddingInputSchemaVersion")
    embedding_input_hash: str = Field(alias="embeddingInputHash")
    normalizer_version: Literal["NFKC_WHITESPACE_V1"] = Field(
        alias="normalizerVersion"
    )
    redactor_version: Literal["PUBLIC_CONTACT_REDACTION_V1"] = Field(
        alias="redactorVersion"
    )
    language: Literal["und"]
    embedding_identity: EmbeddingIdentity = Field(alias="embeddingIdentity")

    @field_validator("request_id", "listing_id")
    @classmethod
    def validate_ulid(cls, value: str) -> str:
        if ULID_PATTERN.fullmatch(value) is None:
            raise ValueError("identifier must be a canonical uppercase ULID")
        return value

    @field_validator("document_hash", "embedding_input_hash")
    @classmethod
    def validate_hash(cls, value: str) -> str:
        if HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("hash must be lowercase SHA-256")
        return value


class DiscoveryEmbeddingEvent(_StrictModel):
    event_id: str = Field(alias="eventId")
    event_type: Literal["listing.discovery.embedding-requested"] = Field(
        alias="eventType"
    )
    event_version: Literal[1] = Field(alias="eventVersion")
    occurred_at: datetime = Field(alias="occurredAt")
    producer: Literal["product-service"]
    aggregate_type: Literal["listing"] = Field(alias="aggregateType")
    aggregate_id: str = Field(alias="aggregateId")
    correlation_id: str = Field(alias="correlationId")
    payload: DiscoveryEmbeddingEventPayload

    @field_validator("event_id", "aggregate_id")
    @classmethod
    def validate_ulid(cls, value: str) -> str:
        if ULID_PATTERN.fullmatch(value) is None:
            raise ValueError("identifier must be a canonical uppercase ULID")
        return value

    @field_validator("occurred_at")
    @classmethod
    def validate_timestamp(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("occurredAt must include a UTC offset")
        return value

    @field_validator("correlation_id")
    @classmethod
    def validate_correlation(cls, value: str) -> str:
        if CORRELATION_PATTERN.fullmatch(value) is None:
            raise ValueError("correlationId is invalid")
        return value

    @model_validator(mode="after")
    def validate_aggregate(self) -> "DiscoveryEmbeddingEvent":
        if self.aggregate_id != self.payload.listing_id:
            raise ValueError("aggregateId must equal payload.listingId")
        return self

    def payload_hash(self) -> str:
        """Hash the entire strict event so event/request replays are comparable."""

        canonical = json.dumps(
            self.model_dump(by_alias=True, mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()


class DiscoveryEmbeddingSource(_StrictModel):
    schema_version: Literal["MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1"] = Field(
        alias="schemaVersion"
    )
    request_id: str = Field(alias="requestId")
    listing_id: str = Field(alias="listingId")
    listing_version: int = Field(alias="listingVersion", ge=0, le=2**63 - 1)
    document_schema_version: Literal["MARKETPLACE_LISTING_DISCOVERY_V2"] = Field(
        alias="documentSchemaVersion"
    )
    document_hash: str = Field(alias="documentHash")
    embedding_input_schema_version: Literal[
        "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1"
    ] = Field(alias="embeddingInputSchemaVersion")
    embedding_input_hash: str = Field(alias="embeddingInputHash")
    normalizer_version: Literal["NFKC_WHITESPACE_V1"] = Field(
        alias="normalizerVersion"
    )
    redactor_version: Literal["PUBLIC_CONTACT_REDACTION_V1"] = Field(
        alias="redactorVersion"
    )
    language: Literal["und"]
    embedding_identity: EmbeddingIdentity = Field(alias="embeddingIdentity")
    embedding_text: str = Field(
        alias="embeddingText",
        min_length=1,
        max_length=MAX_EMBEDDING_TEXT_CHARACTERS,
    )

    @field_validator("request_id", "listing_id")
    @classmethod
    def validate_ulid(cls, value: str) -> str:
        if ULID_PATTERN.fullmatch(value) is None:
            raise ValueError("identifier must be a canonical uppercase ULID")
        return value

    @field_validator("document_hash", "embedding_input_hash")
    @classmethod
    def validate_hash(cls, value: str) -> str:
        if HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("hash must be lowercase SHA-256")
        return value

    def matches(self, event: DiscoveryEmbeddingEvent) -> bool:
        payload = event.payload
        return (
            self.request_id == payload.request_id
            and self.listing_id == payload.listing_id
            and self.listing_version == payload.listing_version
            and self.document_schema_version == payload.document_schema_version
            and self.document_hash == payload.document_hash
            and self.embedding_input_schema_version
            == payload.embedding_input_schema_version
            and self.embedding_input_hash == payload.embedding_input_hash
            and self.normalizer_version == payload.normalizer_version
            and self.redactor_version == payload.redactor_version
            and self.language == payload.language
            and self.embedding_identity == payload.embedding_identity
        )


class DiscoveryEmbeddingResult(_StrictModel):
    schema_version: Literal["MARKETPLACE_LISTING_EMBEDDING_RESULT_V1"] = Field(
        default=RESULT_SCHEMA_VERSION,
        alias="schemaVersion",
    )
    listing_version: int = Field(alias="listingVersion", ge=0, le=2**63 - 1)
    document_schema_version: Literal["MARKETPLACE_LISTING_DISCOVERY_V2"] = Field(
        alias="documentSchemaVersion"
    )
    document_hash: str = Field(alias="documentHash")
    embedding_input_schema_version: Literal[
        "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1"
    ] = Field(alias="embeddingInputSchemaVersion")
    embedding_input_hash: str = Field(alias="embeddingInputHash")
    embedding_identity: EmbeddingIdentity = Field(alias="embeddingIdentity")
    vector: list[float] = Field(
        min_length=EMBEDDING_DIMENSIONS,
        max_length=EMBEDDING_DIMENSIONS,
    )

    @field_validator("document_hash", "embedding_input_hash")
    @classmethod
    def validate_hash(cls, value: str) -> str:
        if HASH_PATTERN.fullmatch(value) is None:
            raise ValueError("hash must be lowercase SHA-256")
        return value

    @field_validator("vector")
    @classmethod
    def validate_vector(cls, value: list[float]) -> list[float]:
        if any(not math.isfinite(component) for component in value):
            raise ValueError("vector values must be finite")
        return value


class DiscoveryEmbeddingResultAcknowledgement(_StrictModel):
    schema_version: Literal["MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1"] = (
        Field(alias="schemaVersion")
    )
    request_id: str = Field(alias="requestId")
    outcome: Literal["ACCEPTED"]

    @field_validator("request_id")
    @classmethod
    def validate_request_id(cls, value: str) -> str:
        if ULID_PATTERN.fullmatch(value) is None:
            raise ValueError("requestId must be a canonical uppercase ULID")
        return value


def parse_discovery_embedding_event(
    *,
    topic: str,
    message_key: bytes | str | None,
    body: bytes,
) -> DiscoveryEmbeddingEvent:
    """Validate one exact Product 04A record before durable intake."""

    if topic != EVENT_TOPIC:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_TOPIC
        )
    if len(body) > MAX_EVENT_BYTES:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_SCHEMA
        )
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as error:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_UTF8
        ) from error
    try:
        decoded = json.loads(text)
    except json.JSONDecodeError as error:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_JSON
        ) from error
    try:
        event = DiscoveryEmbeddingEvent.model_validate(decoded)
    except ValidationError as error:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_SCHEMA
        ) from error
    try:
        decoded_key = (
            None
            if message_key is None
            else message_key.decode("ascii")
            if isinstance(message_key, bytes)
            else message_key
        )
    except UnicodeDecodeError as error:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_MESSAGE_KEY
        ) from error
    if decoded_key != event.aggregate_id:
        raise DiscoveryEmbeddingContractError(
            DiscoveryEmbeddingContractErrorCode.INVALID_MESSAGE_KEY
        )
    return event
