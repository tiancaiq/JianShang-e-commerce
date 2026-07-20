from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import logging
import time
from typing import Literal

import httpx
from prometheus_client import CollectorRegistry, Counter, Histogram
from pydantic import AnyHttpUrl, Field, SecretStr, ValidationError, model_validator

from .listing_content_proposal import (
    MAX_MEDIA_BYTES,
    MAX_MEDIA_COUNT,
    MAX_TOTAL_MEDIA_BYTES,
    ListingMediaContent,
    ListingProposalError,
    ListingProposalErrorCode,
    OwnedDraftMediaContext,
    ProposalModel,
    matches_media_signature,
)
from .schemas import Ulid

LOGGER = logging.getLogger(__name__)

MEDIA_TOOL_SCHEMA_VERSION = "ai-list-owned-draft-media-v1"
MAX_ENCODED_RESPONSE_BYTES = 28 * 1024 * 1024


class ProductListingMediaToolSettings(ProposalModel):
    """Keeps the Product adapter inert unless all internal settings are explicit."""

    enabled: bool = False
    product_service_url: AnyHttpUrl | None = None
    internal_service_token: SecretStr | None = Field(default=None, repr=False)
    timeout_seconds: float = Field(default=2.0, ge=0.01, le=30.0)
    maximum_response_bytes: int = Field(
        default=MAX_ENCODED_RESPONSE_BYTES,
        ge=1_024,
        le=30 * 1024 * 1024,
    )

    @model_validator(mode="after")
    def validate_enabled_configuration(self) -> "ProductListingMediaToolSettings":
        if self.enabled and (
            self.product_service_url is None
            or self.internal_service_token is None
            or not self.internal_service_token.get_secret_value()
        ):
            raise ValueError("enabled Product listing-media tool requires internal settings")
        return self


class _ProductMediaRequest(ProposalModel):
    schema_version: Literal["ai-list-owned-draft-media-v1"] = Field(
        default=MEDIA_TOOL_SCHEMA_VERSION,
        alias="schemaVersion",
    )
    actor_user_id: Ulid = Field(alias="actorUserId")
    media_ids: tuple[Ulid, ...] = Field(
        alias="mediaIds",
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )


class _TrustedMediaToolInput(ProposalModel):
    actor_user_id: Ulid
    listing_id: Ulid
    media_ids: tuple[Ulid, ...] = Field(
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )
    correlation_id: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_correlation(self) -> "_TrustedMediaToolInput":
        if (
            self.correlation_id != self.correlation_id.strip()
            or any(ord(character) < 32 for character in self.correlation_id)
        ):
            raise ValueError("correlation ID must be a bounded visible value")
        return self


class _ProductMediaItem(ProposalModel):
    media_id: Ulid = Field(alias="mediaId")
    content_type: Literal["image/jpeg", "image/png", "image/webp"] = Field(
        alias="contentType"
    )
    byte_size: int = Field(alias="byteSize", ge=1, le=MAX_MEDIA_BYTES)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    source_version: str = Field(alias="sourceVersion", min_length=1, max_length=80)
    content_base64: str = Field(
        alias="contentBase64",
        min_length=4,
        max_length=14 * 1024 * 1024,
        repr=False,
    )


class _ProductMediaResponse(ProposalModel):
    schema_version: Literal["ai-list-owned-draft-media-v1"] = Field(
        alias="schemaVersion"
    )
    listing_id: Ulid = Field(alias="listingId")
    listing_version: str = Field(alias="listingVersion", min_length=1, max_length=80)
    eligibility: Literal["OWNED_DRAFT"]
    media: tuple[_ProductMediaItem, ...] = Field(
        min_length=1,
        max_length=MAX_MEDIA_COUNT,
    )


class ProductListingMediaToolMetrics:
    """Records only fixed adapter outcomes and latency without resource labels."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.requests = Counter(
            "agent_product_listing_media_tool_total",
            "Product listing-media adapter requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.duration = Histogram(
            "agent_product_listing_media_tool_duration_seconds",
            "Product listing-media adapter latency.",
            registry=self.registry,
        )
        self.guardrails = Counter(
            "agent_product_listing_media_tool_guardrail_total",
            "Product listing-media response guardrails by bounded rule.",
            ("rule",),
            registry=self.registry,
        )


class ProductListingDraftMediaTool:
    """Reads only the Product-authorized media bytes injected by application context."""

    def __init__(
        self,
        settings: ProductListingMediaToolSettings,
        *,
        client: httpx.AsyncClient | None = None,
        metrics: ProductListingMediaToolMetrics | None = None,
    ) -> None:
        self._settings = settings
        self._base_url = (
            str(settings.product_service_url).rstrip("/")
            if settings.product_service_url is not None
            else None
        )
        self._token = (
            settings.internal_service_token.get_secret_value()
            if settings.internal_service_token is not None
            else None
        )
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(settings.timeout_seconds),
            follow_redirects=False,
            limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
        )
        self._owns_client = client is None
        self._metrics = metrics or ProductListingMediaToolMetrics()

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def read_owned_draft_media(
        self,
        *,
        actor_user_id: str,
        listing_id: str,
        media_ids: tuple[str, ...],
        correlation_id: str,
    ) -> OwnedDraftMediaContext:
        """Resolve trusted IDs through Product without allowing model-selected identity."""

        started = time.monotonic()
        result = "unavailable"
        try:
            if not self._settings.enabled:
                result = "disabled"
                raise ListingProposalError(
                    ListingProposalErrorCode.DISABLED,
                    "The owned listing media tool is disabled",
                    retryable=False,
                )
            trusted_input = _TrustedMediaToolInput(
                actor_user_id=actor_user_id,
                listing_id=listing_id,
                media_ids=media_ids,
                correlation_id=correlation_id,
            )
            request = _ProductMediaRequest(
                actorUserId=trusted_input.actor_user_id,
                mediaIds=trusted_input.media_ids,
            )
            if (
                len(request.media_ids) != len(set(request.media_ids))
                or self._base_url is None
                or self._token is None
            ):
                result = "rejected"
                raise _unavailable(retryable=False)
            raw_response = await self._post_bounded(
                listing_id=trusted_input.listing_id,
                request=request,
                correlation_id=trusted_input.correlation_id,
            )
            response = _ProductMediaResponse.model_validate_json(raw_response)
            context = self._validated_context(
                listing_id=listing_id,
                requested_media_ids=media_ids,
                response=response,
            )
            result = "success"
            self._metrics.duration.observe(time.monotonic() - started)
            return context
        except asyncio.CancelledError:
            result = "cancelled"
            raise
        except (httpx.TimeoutException, TimeoutError) as error:
            result = "timed_out"
            raise ListingProposalError(
                ListingProposalErrorCode.TIMED_OUT,
                "The owned listing media check timed out",
                retryable=True,
            ) from error
        except httpx.TransportError as error:
            result = "unavailable"
            raise _unavailable(retryable=True) from error
        except ListingProposalError as error:
            result = {
                ListingProposalErrorCode.DISABLED: "disabled",
                ListingProposalErrorCode.UNAVAILABLE: "unavailable",
                ListingProposalErrorCode.UNSUPPORTED_MEDIA: "unsupported_media",
                ListingProposalErrorCode.REJECTED: "rejected",
                ListingProposalErrorCode.TIMED_OUT: "timed_out",
                ListingProposalErrorCode.PROVIDER_UNAVAILABLE: "provider_unavailable",
                ListingProposalErrorCode.IDEMPOTENCY_CONFLICT: "idempotency_conflict",
            }[error.code]
            raise
        except (ValidationError, ValueError, binascii.Error) as error:
            result = "invalid_response"
            self._metrics.guardrails.labels(rule="invalid_response").inc()
            raise _unavailable(retryable=False) from error
        finally:
            self._metrics.requests.labels(result=result).inc()
            LOGGER.info(
                "product_listing_media_tool result=%s actor_hash=%s "
                "listing_hash=%s correlation_hash=%s media_count=%d latency_ms=%d",
                result,
                _hash(actor_user_id),
                _hash(listing_id),
                _hash(correlation_id),
                min(max(len(media_ids), 0), MAX_MEDIA_COUNT),
                max(0, round((time.monotonic() - started) * 1_000)),
            )

    async def _post_bounded(
        self,
        *,
        listing_id: str,
        request: _ProductMediaRequest,
        correlation_id: str,
    ) -> bytes:
        assert self._base_url is not None
        assert self._token is not None
        url = (
            f"{self._base_url}/api/v1/internal/agent/listings/"
            f"{listing_id}/draft-media"
        )
        async with asyncio.timeout(self._settings.timeout_seconds):
            async with self._client.stream(
                "POST",
                url,
                headers={
                    "X-Agent-Internal-Service-Token": self._token,
                    "X-Correlation-Id": correlation_id,
                    "Accept": "application/json",
                },
                json=request.model_dump(by_alias=True, mode="json"),
                follow_redirects=False,
            ) as response:
                if 300 <= response.status_code < 400:
                    self._metrics.guardrails.labels(rule="redirect").inc()
                    raise _unavailable(retryable=False)
                if response.status_code in {404, 409}:
                    raise _unavailable(retryable=False)
                if response.status_code in {400, 413, 415, 422}:
                    raise ListingProposalError(
                        ListingProposalErrorCode.UNSUPPORTED_MEDIA,
                        "The selected listing media is unsupported",
                        retryable=False,
                    )
                if response.status_code == 403:
                    raise _unavailable(retryable=False)
                if response.status_code >= 500:
                    raise _unavailable(retryable=True)
                if response.status_code != 200:
                    raise _unavailable(retryable=False)
                chunks: list[bytes] = []
                response_size = 0
                async for chunk in response.aiter_bytes():
                    response_size += len(chunk)
                    if response_size > self._settings.maximum_response_bytes:
                        self._metrics.guardrails.labels(rule="response_size").inc()
                        raise _unavailable(retryable=False)
                    chunks.append(chunk)
                return b"".join(chunks)

    def _validated_context(
        self,
        *,
        listing_id: str,
        requested_media_ids: tuple[str, ...],
        response: _ProductMediaResponse,
    ) -> OwnedDraftMediaContext:
        if response.listing_id != listing_id:
            self._metrics.guardrails.labels(rule="listing_mismatch").inc()
            raise _unavailable(retryable=False)
        returned_ids = tuple(item.media_id for item in response.media)
        if returned_ids != requested_media_ids:
            self._metrics.guardrails.labels(rule="media_order_mismatch").inc()
            raise _unavailable(retryable=False)
        total_bytes = 0
        media: list[ListingMediaContent] = []
        for item in response.media:
            content = base64.b64decode(item.content_base64, validate=True)
            if not matches_media_signature(content, item.content_type):
                self._metrics.guardrails.labels(rule="mime_mismatch").inc()
                raise _unavailable(retryable=False)
            total_bytes += len(content)
            if total_bytes > MAX_TOTAL_MEDIA_BYTES:
                self._metrics.guardrails.labels(rule="total_media_size").inc()
                raise _unavailable(retryable=False)
            media.append(
                ListingMediaContent(
                    media_id=item.media_id,
                    content_type=item.content_type,
                    byte_size=item.byte_size,
                    sha256=item.sha256,
                    source_version=item.source_version,
                    content=content,
                )
            )
        return OwnedDraftMediaContext(
            listing_id=response.listing_id,
            listing_version=response.listing_version,
            eligibility=response.eligibility,
            media=tuple(media),
        )


def _unavailable(*, retryable: bool) -> ListingProposalError:
    return ListingProposalError(
        ListingProposalErrorCode.UNAVAILABLE,
        "The owned listing draft or selected media is unavailable",
        retryable=retryable,
    )


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
