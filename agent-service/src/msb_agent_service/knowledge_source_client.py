from __future__ import annotations

import re
import time
from datetime import datetime
from enum import StrEnum

import httpx
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

from .config import KnowledgeIngestionSettings
from .knowledge_events import FIXED_ID_PATTERN, HASH_PATTERN, VERSION_PATTERN

DECIMAL_PATTERN = re.compile(r"^(0|[1-9][0-9]*)(?:\.[0-9]+)?$")
CURRENCY_PATTERN = re.compile(r"^[A-Z]{3}$")
CATEGORY_SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LANGUAGE_PATTERN = re.compile(r"^[a-z0-9]{2,8}(?:-[a-z0-9]{1,8})*$")


class KnowledgeSourceErrorCode(StrEnum):
    TIMEOUT = "SOURCE_TIMEOUT"
    UNAVAILABLE = "SOURCE_UNAVAILABLE"
    UNAUTHORIZED = "SOURCE_UNAUTHORIZED"
    NOT_FOUND = "SOURCE_NOT_FOUND"
    INVALID_RESPONSE = "SOURCE_INVALID_RESPONSE"


class KnowledgeSourceError(RuntimeError):
    """Carries a stable source failure without retaining response content."""

    def __init__(
        self,
        code: KnowledgeSourceErrorCode,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.retryable = retryable


class StrictSourceModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class ListingPrice(StrictSourceModel):
    amount: str
    currency: str

    @field_validator("amount")
    @classmethod
    def validate_amount(cls, value: str) -> str:
        if len(value) > 40 or not DECIMAL_PATTERN.fullmatch(value):
            raise ValueError("amount must be a bounded non-negative decimal string")
        return value

    @field_validator("currency")
    @classmethod
    def validate_currency(cls, value: str) -> str:
        if not CURRENCY_PATTERN.fullmatch(value):
            raise ValueError("currency must be uppercase ISO-4217 form")
        return value


class ListingPublicLocation(StrictSourceModel):
    city: str = Field(min_length=1, max_length=160)
    region: str = Field(min_length=1, max_length=160)


class ListingKnowledgeContent(StrictSourceModel):
    title: str = Field(min_length=1, max_length=240)
    description: str = Field(min_length=1, max_length=20_000)
    price: ListingPrice
    public_location: ListingPublicLocation = Field(alias="publicLocation")


class ListingKnowledgeSource(StrictSourceModel):
    source_type: str = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId")
    source_version: str = Field(alias="sourceVersion")
    supersedes_version: str | None = Field(default=None, alias="supersedesVersion")
    lifecycle: str
    visibility: str
    language: str
    effective_from: datetime | None = Field(default=None, alias="effectiveFrom")
    invalidated_at: datetime | None = Field(default=None, alias="invalidatedAt")
    source_published_at: datetime | None = Field(
        default=None, alias="sourcePublishedAt"
    )
    content_hash: str | None = Field(default=None, alias="contentHash")
    content: ListingKnowledgeContent | None = None

    @field_validator("source_type")
    @classmethod
    def validate_source_type(cls, value: str) -> str:
        if value != "LISTING":
            raise ValueError("sourceType must be LISTING")
        return value

    @field_validator("source_id")
    @classmethod
    def validate_source_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError("sourceId must be an uppercase 26-character ID")
        return value

    @field_validator("source_version", "supersedes_version")
    @classmethod
    def validate_version(cls, value: str | None) -> str | None:
        if value is not None and not VERSION_PATTERN.fullmatch(value):
            raise ValueError("source versions must be unsigned decimal strings")
        return value

    @field_validator("lifecycle")
    @classmethod
    def validate_lifecycle(cls, value: str) -> str:
        if value not in {"ACTIVE", "INVALIDATED"}:
            raise ValueError("unsupported lifecycle")
        return value

    @field_validator("visibility")
    @classmethod
    def validate_visibility(cls, value: str) -> str:
        if value != "PUBLIC":
            raise ValueError("visibility must be PUBLIC")
        return value

    @field_validator("language")
    @classmethod
    def validate_language(cls, value: str) -> str:
        if not 2 <= len(value) <= 16 or value != value.lower():
            raise ValueError("language must be a lowercase bounded tag")
        return value

    @field_validator("effective_from", "invalidated_at", "source_published_at")
    @classmethod
    def validate_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("source timestamps must include a UTC offset")
        return value

    @field_validator("content_hash")
    @classmethod
    def validate_content_hash(cls, value: str | None) -> str | None:
        if value is not None and not HASH_PATTERN.fullmatch(value):
            raise ValueError("contentHash must be lowercase SHA-256")
        return value

    @model_validator(mode="after")
    def validate_lifecycle_shape(self) -> "ListingKnowledgeSource":
        if self.supersedes_version is not None and int(
            self.supersedes_version
        ) >= int(self.source_version):
            raise ValueError("supersedesVersion must be older than sourceVersion")
        if self.lifecycle == "ACTIVE":
            if (
                self.content is None
                or self.content_hash is None
                or self.effective_from is None
                or self.invalidated_at is not None
            ):
                raise ValueError("active source shape is incomplete")
        elif (
            self.content is not None
            or self.content_hash is not None
            or self.invalidated_at is None
        ):
            raise ValueError("invalidated source shape is invalid")
        return self


class ListingKnowledgeExportPage(StrictSourceModel):
    """Strict watermark-stable Product Service page used by rebuilds."""

    items: list[ListingKnowledgeSource] = Field(max_length=200)
    next_cursor: str | None = Field(default=None, alias="nextCursor", max_length=1200)
    has_more: bool = Field(alias="hasMore")
    export_watermark: datetime = Field(alias="exportWatermark")

    @field_validator("export_watermark")
    @classmethod
    def validate_export_watermark(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("exportWatermark must include a UTC offset")
        return value

    @model_validator(mode="after")
    def validate_page_shape(self) -> "ListingKnowledgeExportPage":
        if self.has_more != (self.next_cursor is not None):
            raise ValueError("nextCursor and hasMore are inconsistent")
        if any(item.lifecycle != "ACTIVE" for item in self.items):
            raise ValueError("listing export may contain only active sources")
        return self


class CategoryGuidanceContent(StrictSourceModel):
    category_slug: str = Field(alias="categorySlug", min_length=1, max_length=120)
    category_name: str = Field(alias="categoryName", min_length=1, max_length=180)
    title: str = Field(min_length=1, max_length=180)
    body: str = Field(min_length=1, max_length=12_000)

    @field_validator("category_slug")
    @classmethod
    def validate_slug(cls, value: str) -> str:
        if CATEGORY_SLUG_PATTERN.fullmatch(value) is None:
            raise ValueError("categorySlug must be a normalized slug")
        return value


class CategoryGuidanceSource(StrictSourceModel):
    source_type: str = Field(alias="sourceType")
    source_id: str = Field(alias="sourceId")
    source_version: str = Field(alias="sourceVersion")
    supersedes_version: str | None = Field(default=None, alias="supersedesVersion")
    lifecycle: str
    visibility: str
    language: str
    effective_from: datetime | None = Field(default=None, alias="effectiveFrom")
    invalidated_at: datetime | None = Field(default=None, alias="invalidatedAt")
    content_hash: str | None = Field(default=None, alias="contentHash")
    content: CategoryGuidanceContent | None = None

    @field_validator("source_type")
    @classmethod
    def validate_source_type(cls, value: str) -> str:
        if value != "CATEGORY_GUIDANCE":
            raise ValueError("sourceType must be CATEGORY_GUIDANCE")
        return value

    @field_validator("source_id")
    @classmethod
    def validate_source_id(cls, value: str) -> str:
        if not FIXED_ID_PATTERN.fullmatch(value):
            raise ValueError("sourceId must be an uppercase 26-character ID")
        return value

    @field_validator("source_version", "supersedes_version")
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

    @field_validator("lifecycle")
    @classmethod
    def validate_lifecycle(cls, value: str) -> str:
        if value not in {"ACTIVE", "INVALIDATED"}:
            raise ValueError("unsupported lifecycle")
        return value

    @field_validator("visibility")
    @classmethod
    def validate_visibility(cls, value: str) -> str:
        if value != "PUBLIC":
            raise ValueError("visibility must be PUBLIC")
        return value

    @field_validator("language")
    @classmethod
    def validate_language(cls, value: str) -> str:
        if LANGUAGE_PATTERN.fullmatch(value) is None:
            raise ValueError("language must be a normalized lowercase tag")
        return value

    @field_validator("effective_from", "invalidated_at")
    @classmethod
    def validate_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("source timestamps must include a UTC offset")
        return value

    @field_validator("content_hash")
    @classmethod
    def validate_content_hash(cls, value: str | None) -> str | None:
        if value is not None and not HASH_PATTERN.fullmatch(value):
            raise ValueError("contentHash must be lowercase SHA-256")
        return value

    @model_validator(mode="after")
    def validate_lifecycle_shape(self) -> "CategoryGuidanceSource":
        if self.supersedes_version is not None and int(
            self.supersedes_version
        ) >= int(self.source_version):
            raise ValueError("supersedesVersion must be older than sourceVersion")
        if self.lifecycle == "ACTIVE":
            if (
                self.content is None
                or self.content_hash is None
                or self.effective_from is None
                or self.invalidated_at is not None
            ):
                raise ValueError("active category guidance shape is incomplete")
        elif (
            self.content is not None
            or self.content_hash is not None
            or self.effective_from is not None
            or self.invalidated_at is None
        ):
            raise ValueError("invalidated category guidance shape is invalid")
        return self


class CategoryGuidanceExportPage(StrictSourceModel):
    items: list[CategoryGuidanceSource] = Field(max_length=200)
    next_cursor: str | None = Field(default=None, alias="nextCursor", max_length=1200)
    has_more: bool = Field(alias="hasMore")
    export_watermark: datetime = Field(alias="exportWatermark")

    @field_validator("export_watermark")
    @classmethod
    def validate_export_watermark(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("exportWatermark must include a UTC offset")
        return value

    @model_validator(mode="after")
    def validate_page_shape(self) -> "CategoryGuidanceExportPage":
        if self.has_more != (self.next_cursor is not None):
            raise ValueError("nextCursor and hasMore are inconsistent")
        if any(item.lifecycle != "ACTIVE" for item in self.items):
            raise ValueError("category guidance export may contain only active sources")
        return self


class KnowledgeSourceClient:
    """Fetches only exact configured Product Service source versions."""

    def __init__(
        self,
        settings: KnowledgeIngestionSettings,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if settings.product_service_url is None or settings.product_service_token is None:
            raise ValueError("Product Service source configuration is incomplete")
        self._base_url = settings.product_service_url.rstrip("/")
        self._token = settings.product_service_token
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(settings.source_timeout_seconds),
            follow_redirects=False,
            limits=httpx.Limits(max_connections=20, max_keepalive_connections=10),
        )

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def fetch_listing(
        self,
        listing_id: str,
        source_version: int,
    ) -> tuple[ListingKnowledgeSource, float]:
        """Fetch and validate one exact immutable listing source."""

        if not FIXED_ID_PATTERN.fullmatch(listing_id):
            raise ValueError("listing_id must be an uppercase 26-character ID")
        if not 0 <= source_version <= 2**64 - 1:
            raise ValueError("source_version must be an unsigned 64-bit integer")
        path = (
            "/api/v1/internal/agent/knowledge/listings/"
            f"{listing_id}/versions/{source_version}"
        )
        started = time.monotonic()
        try:
            response = await self._client.get(
                f"{self._base_url}{path}",
                headers={"X-Agent-Internal-Service-Token": self._token},
            )
        except httpx.TimeoutException as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.TIMEOUT,
                retryable=True,
            ) from exc
        except httpx.TransportError as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            ) from exc
        duration = time.monotonic() - started
        if response.status_code == 403:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAUTHORIZED,
                retryable=False,
            )
        if response.status_code == 404:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.NOT_FOUND,
                retryable=False,
            )
        if response.status_code >= 500:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            )
        if response.status_code != 200:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            )
        try:
            source = ListingKnowledgeSource.model_validate_json(response.content)
        except (ValidationError, ValueError) as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            ) from exc
        if (
            source.source_id != listing_id
            or int(source.source_version) != source_version
        ):
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            )
        return source, duration

    async def fetch_listing_export(
        self,
        *,
        cursor: str | None = None,
        limit: int = 100,
    ) -> tuple[ListingKnowledgeExportPage, float]:
        """Fetch one bounded opaque-cursor rebuild page without URL freedom."""

        if not 1 <= limit <= 200:
            raise ValueError("limit must be between 1 and 200")
        if cursor is not None and (not cursor or len(cursor) > 1200):
            raise ValueError("cursor must be a bounded opaque value")
        started = time.monotonic()
        parameters: dict[str, str | int] = {"limit": limit}
        if cursor is not None:
            parameters["cursor"] = cursor
        try:
            response = await self._client.get(
                f"{self._base_url}/api/v1/internal/agent/knowledge/listings/export",
                headers={"X-Agent-Internal-Service-Token": self._token},
                params=parameters,
            )
        except httpx.TimeoutException as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.TIMEOUT,
                retryable=True,
            ) from exc
        except httpx.TransportError as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            ) from exc
        duration = time.monotonic() - started
        if response.status_code == 403:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAUTHORIZED,
                retryable=False,
            )
        if response.status_code >= 500:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            )
        if response.status_code != 200:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            )
        try:
            page = ListingKnowledgeExportPage.model_validate_json(response.content)
        except (ValidationError, ValueError) as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            ) from exc
        return page, duration

    async def fetch_category_guidance(
        self,
        category_id: str,
        language: str,
        source_version: int,
    ) -> tuple[CategoryGuidanceSource, float]:
        """Fetch and validate one exact immutable category-guidance source."""

        if not FIXED_ID_PATTERN.fullmatch(category_id):
            raise ValueError("category_id must be an uppercase 26-character ID")
        if LANGUAGE_PATTERN.fullmatch(language) is None:
            raise ValueError("language must be a normalized lowercase tag")
        if not 1 <= source_version <= 2**64 - 1:
            raise ValueError("source_version must be a positive 64-bit integer")
        path = (
            "/api/v1/internal/agent/knowledge/category-guidance/"
            f"{category_id}/languages/{language}/versions/{source_version}"
        )
        response, duration = await self._get(path)
        try:
            source = CategoryGuidanceSource.model_validate_json(response.content)
        except (ValidationError, ValueError) as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            ) from exc
        if (
            source.source_id != category_id
            or source.language != language
            or int(source.source_version) != source_version
        ):
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            )
        return source, duration

    async def fetch_category_guidance_export(
        self,
        *,
        cursor: str | None = None,
        limit: int = 100,
    ) -> tuple[CategoryGuidanceExportPage, float]:
        """Fetch one bounded watermark-stable category-guidance export page."""

        if not 1 <= limit <= 200:
            raise ValueError("limit must be between 1 and 200")
        if cursor is not None and (not cursor or len(cursor) > 1200):
            raise ValueError("cursor must be a bounded opaque value")
        parameters: dict[str, str | int] = {"limit": limit}
        if cursor is not None:
            parameters["cursor"] = cursor
        response, duration = await self._get(
            "/api/v1/internal/agent/knowledge/category-guidance/export",
            parameters=parameters,
        )
        try:
            page = CategoryGuidanceExportPage.model_validate_json(response.content)
        except (ValidationError, ValueError) as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            ) from exc
        return page, duration

    async def _get(
        self,
        path: str,
        *,
        parameters: dict[str, str | int] | None = None,
    ) -> tuple[httpx.Response, float]:
        """Execute one allowlisted source read with shared safe classification."""

        started = time.monotonic()
        try:
            response = await self._client.get(
                f"{self._base_url}{path}",
                headers={"X-Agent-Internal-Service-Token": self._token},
                params=parameters,
            )
        except httpx.TimeoutException as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.TIMEOUT,
                retryable=True,
            ) from exc
        except httpx.TransportError as exc:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            ) from exc
        duration = time.monotonic() - started
        if response.status_code == 403:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAUTHORIZED,
                retryable=False,
            )
        if response.status_code == 404:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.NOT_FOUND,
                retryable=False,
            )
        if response.status_code >= 500:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.UNAVAILABLE,
                retryable=True,
            )
        if response.status_code != 200:
            raise KnowledgeSourceError(
                KnowledgeSourceErrorCode.INVALID_RESPONSE,
                retryable=False,
            )
        return response, duration
