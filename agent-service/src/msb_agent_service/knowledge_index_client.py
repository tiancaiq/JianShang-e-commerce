from __future__ import annotations

from enum import StrEnum
from urllib.parse import urlparse

from opensearchpy import AsyncHttpConnection, AsyncOpenSearch
from opensearchpy.exceptions import (
    AuthenticationException,
    AuthorizationException,
    ConnectionError as OpenSearchConnectionError,
    ConnectionTimeout,
    RequestError,
    TransportError,
)

from .config import KnowledgeIndexSettings


class KnowledgeIndexErrorCode(StrEnum):
    CONFIGURATION_INVALID = "KNOWLEDGE_INDEX_CONFIGURATION_INVALID"
    UNAVAILABLE = "KNOWLEDGE_INDEX_UNAVAILABLE"
    UNAUTHORIZED = "KNOWLEDGE_INDEX_UNAUTHORIZED"
    INCOMPATIBLE = "KNOWLEDGE_INDEX_INCOMPATIBLE"
    ALIAS_INVALID = "KNOWLEDGE_INDEX_ALIAS_INVALID"
    DOCUMENT_INVALID = "KNOWLEDGE_INDEX_DOCUMENT_INVALID"
    PARTIAL_FAILURE = "KNOWLEDGE_INDEX_PARTIAL_FAILURE"
    OPERATION_REJECTED = "KNOWLEDGE_INDEX_OPERATION_REJECTED"


class KnowledgeIndexError(RuntimeError):
    """Carries a stable, secret-safe OpenSearch failure classification."""

    def __init__(
        self,
        code: KnowledgeIndexErrorCode,
        message: str,
        *,
        retryable: bool = False,
        failed_ids: tuple[str, ...] = (),
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.failed_ids = failed_ids


def create_open_search_client(settings: KnowledgeIndexSettings) -> AsyncOpenSearch:
    """Create one bounded async client without performing network I/O."""

    try:
        settings.validate()
    except ValueError as error:
        raise KnowledgeIndexError(
            KnowledgeIndexErrorCode.CONFIGURATION_INVALID,
            "Knowledge index configuration is invalid",
        ) from error
    if not settings.enabled or settings.url is None:
        raise KnowledgeIndexError(
            KnowledgeIndexErrorCode.CONFIGURATION_INVALID,
            "Knowledge index is not enabled and configured",
        )

    parsed = urlparse(settings.url)
    use_ssl = parsed.scheme == "https"
    port = parsed.port or (443 if use_ssl else 80)
    host: dict[str, object] = {"host": parsed.hostname, "port": port}
    path = parsed.path.strip("/")
    if path:
        host["url_prefix"] = path
    authentication = None
    if settings.username and settings.password:
        authentication = (settings.username, settings.password)

    return AsyncOpenSearch(
        hosts=[host],
        http_auth=authentication,
        use_ssl=use_ssl,
        verify_certs=settings.verify_certs,
        ssl_show_warn=False,
        connection_class=AsyncHttpConnection,
        http_compress=True,
        timeout=settings.request_timeout_seconds,
        max_retries=settings.max_retries,
        retry_on_timeout=True,
    )


def classify_open_search_error(error: Exception) -> KnowledgeIndexError:
    """Map client exceptions without leaking response bodies or endpoints."""

    if isinstance(error, (AuthenticationException, AuthorizationException)):
        return KnowledgeIndexError(
            KnowledgeIndexErrorCode.UNAUTHORIZED,
            "OpenSearch rejected the configured service credentials",
        )
    if isinstance(error, (ConnectionTimeout, OpenSearchConnectionError)):
        return KnowledgeIndexError(
            KnowledgeIndexErrorCode.UNAVAILABLE,
            "OpenSearch is temporarily unavailable",
            retryable=True,
        )
    if isinstance(error, RequestError):
        return KnowledgeIndexError(
            KnowledgeIndexErrorCode.OPERATION_REJECTED,
            "OpenSearch rejected the validated index operation",
        )
    if isinstance(error, TransportError):
        status_code = getattr(error, "status_code", None)
        if status_code in {502, 503, 504}:
            return KnowledgeIndexError(
                KnowledgeIndexErrorCode.UNAVAILABLE,
                "OpenSearch is temporarily unavailable",
                retryable=True,
            )
        return KnowledgeIndexError(
            KnowledgeIndexErrorCode.OPERATION_REJECTED,
            "OpenSearch index operation failed",
        )
    return KnowledgeIndexError(
        KnowledgeIndexErrorCode.UNAVAILABLE,
        "OpenSearch index operation failed",
    )
