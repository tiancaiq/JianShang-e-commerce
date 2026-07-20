from __future__ import annotations

from enum import StrEnum

from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AuthenticationError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
)


class ProviderErrorCode(StrEnum):
    NOT_CONFIGURED = "OPENAI_API_KEY_NOT_CONFIGURED"
    AUTHENTICATION_FAILED = "OPENAI_AUTHENTICATION_FAILED"
    MODEL_UNAVAILABLE = "OPENAI_MODEL_UNAVAILABLE"
    QUOTA_EXHAUSTED = "OPENAI_QUOTA_EXHAUSTED"
    RATE_LIMITED = "OPENAI_RATE_LIMITED"
    TIMED_OUT = "OPENAI_TIMED_OUT"
    UNAVAILABLE = "OPENAI_UNAVAILABLE"
    INVALID_RESPONSE = "OPENAI_INVALID_RESPONSE"


class LlmProviderError(RuntimeError):
    """Carries a stable, non-secret provider failure classification."""

    def __init__(
        self,
        code: ProviderErrorCode,
        message: str,
        *,
        retryable: bool,
        status_code: int,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.status_code = status_code


def provider_not_configured() -> LlmProviderError:
    return LlmProviderError(
        ProviderErrorCode.NOT_CONFIGURED,
        "OpenAI runtime credentials are not configured",
        retryable=False,
        status_code=503,
    )


def invalid_provider_response() -> LlmProviderError:
    return LlmProviderError(
        ProviderErrorCode.INVALID_RESPONSE,
        "OpenAI returned an unusable structured response",
        retryable=False,
        status_code=502,
    )


def classify_openai_error(error: Exception) -> LlmProviderError:
    """Map SDK exceptions to stable application-facing error categories."""

    if isinstance(error, AuthenticationError):
        return LlmProviderError(
            ProviderErrorCode.AUTHENTICATION_FAILED,
            "OpenAI rejected the configured credentials",
            retryable=False,
            status_code=503,
        )
    if isinstance(error, (PermissionDeniedError, NotFoundError)):
        return LlmProviderError(
            ProviderErrorCode.MODEL_UNAVAILABLE,
            "The configured OpenAI model is unavailable to this project",
            retryable=False,
            status_code=503,
        )
    if isinstance(error, RateLimitError):
        error_code = getattr(error, "code", None)
        if error_code == "insufficient_quota":
            return LlmProviderError(
                ProviderErrorCode.QUOTA_EXHAUSTED,
                "OpenAI project quota is exhausted",
                retryable=False,
                status_code=503,
            )
        return LlmProviderError(
            ProviderErrorCode.RATE_LIMITED,
            "OpenAI rate limited the request",
            retryable=True,
            status_code=503,
        )
    if isinstance(error, APITimeoutError):
        return LlmProviderError(
            ProviderErrorCode.TIMED_OUT,
            "OpenAI request timed out",
            retryable=True,
            status_code=504,
        )
    if isinstance(error, APIConnectionError):
        return LlmProviderError(
            ProviderErrorCode.UNAVAILABLE,
            "OpenAI could not be reached",
            retryable=True,
            status_code=503,
        )
    if isinstance(error, APIStatusError) and error.status_code >= 500:
        return LlmProviderError(
            ProviderErrorCode.UNAVAILABLE,
            "OpenAI is temporarily unavailable",
            retryable=True,
            status_code=503,
        )
    return LlmProviderError(
        ProviderErrorCode.UNAVAILABLE,
        "OpenAI request failed",
        retryable=False,
        status_code=502,
    )

