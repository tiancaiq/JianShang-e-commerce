from __future__ import annotations

import json
import time
from enum import StrEnum

import httpx
from pydantic import ValidationError

from .discovery_embedding_contract import (
    MAX_RESULT_BYTES,
    MAX_SOURCE_BYTES,
    DiscoveryEmbeddingResult,
    DiscoveryEmbeddingResultAcknowledgement,
    DiscoveryEmbeddingSource,
)
from .discovery_embedding_jobs import DiscoveryEmbeddingJob


class DiscoveryEmbeddingClientErrorCode(StrEnum):
    SOURCE_STALE = "SOURCE_STALE"
    SOURCE_UNAUTHORIZED = "SOURCE_UNAUTHORIZED"
    SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    SOURCE_TIMEOUT = "SOURCE_TIMEOUT"
    SOURCE_INVALID_RESPONSE = "SOURCE_INVALID_RESPONSE"
    SOURCE_IDENTITY_MISMATCH = "SOURCE_IDENTITY_MISMATCH"
    CALLBACK_STALE = "CALLBACK_STALE"
    CALLBACK_IDENTITY_CONFLICT = "CALLBACK_IDENTITY_CONFLICT"
    CALLBACK_IDEMPOTENCY_CONFLICT = "CALLBACK_IDEMPOTENCY_CONFLICT"
    CALLBACK_CONFLICT = "CALLBACK_CONFLICT"
    CALLBACK_UNAUTHORIZED = "CALLBACK_UNAUTHORIZED"
    CALLBACK_UNAVAILABLE = "CALLBACK_UNAVAILABLE"
    CALLBACK_TIMEOUT = "CALLBACK_TIMEOUT"
    CALLBACK_INVALID_RESPONSE = "CALLBACK_INVALID_RESPONSE"
    CALLBACK_REQUEST_TOO_LARGE = "CALLBACK_REQUEST_TOO_LARGE"


class DiscoveryEmbeddingClientError(RuntimeError):
    """Carries a safe outbound classification without upstream response content."""

    def __init__(
        self,
        code: DiscoveryEmbeddingClientErrorCode,
        *,
        retryable: bool,
    ) -> None:
        super().__init__(code.value)
        self.code = code
        self.retryable = retryable


class ProductDiscoveryEmbeddingSourceClient:
    """Fetches only Product's exact request-bound 04A canonical source."""

    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not base_url or not service_token:
            raise ValueError("Product discovery source configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._timeout_seconds = timeout_seconds
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds),
            follow_redirects=False,
            limits=httpx.Limits(max_connections=10, max_keepalive_connections=5),
        )

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def fetch(
        self,
        job: DiscoveryEmbeddingJob,
    ) -> tuple[DiscoveryEmbeddingSource, float]:
        """Validate the bounded Product response against every durable event field."""

        started = time.monotonic()
        path = (
            "/api/v1/internal/agent/discovery/embedding-requests/"
            f"{job.request_id}/source"
        )
        try:
            response = await self._client.get(
                f"{self._base_url}{path}",
                headers={
                    "X-Agent-Internal-Service-Token": self._service_token,
                    "X-Correlation-Id": job.correlation_id,
                },
                follow_redirects=False,
                timeout=self._timeout_seconds,
            )
        except httpx.TimeoutException as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_TIMEOUT,
                retryable=True,
            ) from error
        except httpx.TransportError as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_UNAVAILABLE,
                retryable=True,
            ) from error
        duration = time.monotonic() - started
        if response.is_redirect:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_INVALID_RESPONSE,
                retryable=False,
            )
        if response.status_code == 403:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_UNAUTHORIZED,
                retryable=False,
            )
        if response.status_code == 404:
            code = _safe_error_code(response)
            if code == "LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND":
                raise DiscoveryEmbeddingClientError(
                    DiscoveryEmbeddingClientErrorCode.SOURCE_STALE,
                    retryable=False,
                )
            if code == "FEATURE_DISABLED":
                raise DiscoveryEmbeddingClientError(
                    DiscoveryEmbeddingClientErrorCode.SOURCE_UNAVAILABLE,
                    retryable=True,
                )
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_INVALID_RESPONSE,
                retryable=False,
            )
        if response.status_code >= 500:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_UNAVAILABLE,
                retryable=True,
            )
        if response.status_code != 200 or len(response.content) > MAX_SOURCE_BYTES:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_INVALID_RESPONSE,
                retryable=False,
            )
        try:
            source = DiscoveryEmbeddingSource.model_validate_json(response.content)
        except (ValidationError, ValueError) as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_INVALID_RESPONSE,
                retryable=False,
            ) from error
        if not _source_matches_job(source, job):
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.SOURCE_IDENTITY_MISMATCH,
                retryable=False,
            )
        return source, duration


class ProductDiscoveryEmbeddingCallbackClient:
    """Submits one ephemeral vector to the future Product 04C result boundary."""

    def __init__(
        self,
        *,
        base_url: str,
        service_token: str,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not base_url or not service_token:
            raise ValueError("Product discovery callback configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._timeout_seconds = timeout_seconds
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds),
            follow_redirects=False,
            limits=httpx.Limits(max_connections=10, max_keepalive_connections=5),
        )

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def submit(
        self,
        *,
        job: DiscoveryEmbeddingJob,
        result: DiscoveryEmbeddingResult,
    ) -> float:
        """Complete only after Product returns an exact request acknowledgement."""

        body = result.model_dump_json(
            by_alias=True,
            exclude_none=True,
        ).encode("utf-8")
        if len(body) > MAX_RESULT_BYTES:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_REQUEST_TOO_LARGE,
                retryable=False,
            )
        started = time.monotonic()
        path = (
            "/api/v1/internal/agent/discovery/embedding-requests/"
            f"{job.request_id}/result"
        )
        try:
            response = await self._client.post(
                f"{self._base_url}{path}",
                content=body,
                headers={
                    "Content-Type": "application/json",
                    "X-Agent-Internal-Service-Token": self._service_token,
                    "X-Correlation-Id": job.correlation_id,
                },
                follow_redirects=False,
                timeout=self._timeout_seconds,
            )
        except httpx.TimeoutException as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_TIMEOUT,
                retryable=True,
            ) from error
        except httpx.TransportError as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_UNAVAILABLE,
                retryable=True,
            ) from error
        duration = time.monotonic() - started
        if response.is_redirect:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
                retryable=False,
            )
        if response.status_code in {401, 403}:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_UNAUTHORIZED,
                retryable=False,
            )
        if response.status_code == 409:
            code = _safe_error_code(response)
            mapped = {
                "LISTING_DISCOVERY_EMBEDDING_STALE":
                    DiscoveryEmbeddingClientErrorCode.CALLBACK_STALE,
                "LISTING_DISCOVERY_EMBEDDING_IDENTITY_CONFLICT":
                    DiscoveryEmbeddingClientErrorCode.CALLBACK_IDENTITY_CONFLICT,
                "LISTING_DISCOVERY_EMBEDDING_IDEMPOTENCY_CONFLICT":
                    DiscoveryEmbeddingClientErrorCode.CALLBACK_IDEMPOTENCY_CONFLICT,
            }.get(code, DiscoveryEmbeddingClientErrorCode.CALLBACK_CONFLICT)
            raise DiscoveryEmbeddingClientError(mapped, retryable=False)
        if response.status_code >= 500:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_UNAVAILABLE,
                retryable=True,
            )
        if response.status_code < 200 or response.status_code >= 300:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
                retryable=False,
            )
        if len(response.content) > MAX_SOURCE_BYTES:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
                retryable=False,
            )
        try:
            acknowledgement = (
                DiscoveryEmbeddingResultAcknowledgement.model_validate_json(
                    response.content
                )
            )
        except (ValidationError, ValueError) as error:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
                retryable=False,
            ) from error
        if acknowledgement.request_id != job.request_id:
            raise DiscoveryEmbeddingClientError(
                DiscoveryEmbeddingClientErrorCode.CALLBACK_INVALID_RESPONSE,
                retryable=False,
            )
        return duration


def _source_matches_job(
    source: DiscoveryEmbeddingSource,
    job: DiscoveryEmbeddingJob,
) -> bool:
    identity = source.embedding_identity
    return (
        source.request_id == job.request_id
        and source.listing_id == job.listing_id
        and source.listing_version == job.listing_version
        and source.document_schema_version == job.document_schema_version
        and source.document_hash == job.document_hash
        and source.embedding_input_schema_version
        == job.embedding_input_schema_version
        and source.embedding_input_hash == job.embedding_input_hash
        and source.normalizer_version == job.normalizer_version
        and source.redactor_version == job.redactor_version
        and source.language == job.language
        and identity.provider == job.embedding_provider
        and identity.model == job.embedding_model
        and identity.dimensions == job.embedding_dimensions
    )


def _safe_error_code(response: httpx.Response) -> str | None:
    """Extract only a bounded stable code from the standard error envelope."""

    if len(response.content) > 8_192:
        return None
    try:
        value = json.loads(response.content)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return None
    if not isinstance(value, dict) or set(value) != {"error"}:
        return None
    error = value["error"]
    if not isinstance(error, dict):
        return None
    code = error.get("code")
    if (
        not isinstance(code, str)
        or not 1 <= len(code) <= 80
        or any(not (character.isupper() or character.isdigit() or character == "_")
               for character in code)
    ):
        return None
    return code
