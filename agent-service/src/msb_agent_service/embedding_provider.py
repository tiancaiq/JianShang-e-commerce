from __future__ import annotations

import logging
import math
import time
from dataclasses import dataclass
from typing import Any, Protocol, Sequence

import tiktoken
from openai import AsyncOpenAI

from .errors import classify_openai_error
from .knowledge_ingestion_metrics import KnowledgeIngestionMetrics

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class EmbeddingBatchResult:
    vectors: tuple[tuple[float, ...], ...]
    provider: str
    model: str
    dimensions: int
    input_tokens: int


class EmbeddingProviderError(RuntimeError):
    """Carries a safe embedding failure classification and retry decision."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class EmbeddingProvider(Protocol):
    async def embed(
        self,
        texts: Sequence[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult: ...

    async def close(self) -> None: ...


class OpenAIEmbeddingProvider:
    """Calls the bounded online Embeddings API and validates every vector."""

    provider = "openai"

    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        dimensions: int,
        timeout_seconds: float,
        max_retries: int,
        maximum_inputs: int,
        maximum_input_tokens: int,
        maximum_total_tokens: int,
        metrics: KnowledgeIngestionMetrics,
        client: Any | None = None,
        encoding: Any | None = None,
    ) -> None:
        self.model = model
        self.dimensions = dimensions
        self._maximum_inputs = maximum_inputs
        self._maximum_input_tokens = maximum_input_tokens
        self._maximum_total_tokens = maximum_total_tokens
        self._metrics = metrics
        self._owns_client = client is None
        self._client = client or AsyncOpenAI(
            api_key=api_key,
            timeout=timeout_seconds,
            max_retries=max_retries,
        )
        if encoding is not None:
            self._encoding = encoding
        else:
            try:
                self._encoding = tiktoken.encoding_for_model(model)
            except KeyError:
                self._encoding = tiktoken.get_encoding("cl100k_base")

    async def embed(
        self,
        texts: Sequence[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        """Return finite vectors in exact input order or fail before indexing."""

        token_counts = self._validate_inputs(texts)
        started = time.monotonic()
        try:
            response = await self._client.embeddings.create(
                model=self.model,
                input=list(texts),
                dimensions=self.dimensions,
                encoding_format="float",
            )
        except Exception as error:
            mapped = classify_openai_error(error)
            self._metrics.record_embedding(
                mapped.code.value,
                time.monotonic() - started,
            )
            logger.warning(
                "Embedding request failed provider=openai model=%s "
                "inputCount=%s correlationId=%s errorCode=%s retryable=%s",
                self.model,
                len(texts),
                correlation_id,
                mapped.code.value,
                mapped.retryable,
            )
            raise EmbeddingProviderError(
                mapped.code.value,
                retryable=mapped.retryable,
            ) from error

        duration = time.monotonic() - started
        result = self._validate_response(response, len(texts))
        usage = getattr(response, "usage", None)
        reported_tokens = getattr(usage, "total_tokens", None)
        input_tokens = (
            sum(token_counts)
            if reported_tokens is None
            else int(reported_tokens)
        )
        if input_tokens < 0:
            raise EmbeddingProviderError(
                "EMBEDDING_USAGE_INVALID",
                retryable=False,
            )
        self._metrics.record_embedding(
            "success",
            duration,
            input_tokens=input_tokens,
        )
        logger.info(
            "Embedding request completed provider=openai model=%s "
            "inputCount=%s dimensions=%s inputTokens=%s correlationId=%s "
            "latencyMs=%s",
            self.model,
            len(texts),
            self.dimensions,
            input_tokens,
            correlation_id,
            round(duration * 1000),
        )
        return EmbeddingBatchResult(
            vectors=result,
            provider=self.provider,
            model=self.model,
            dimensions=self.dimensions,
            input_tokens=input_tokens,
        )

    async def close(self) -> None:
        if self._owns_client:
            await self._client.close()

    def _validate_inputs(self, texts: Sequence[str]) -> tuple[int, ...]:
        if not 1 <= len(texts) <= self._maximum_inputs:
            raise EmbeddingProviderError(
                "EMBEDDING_INPUT_COUNT_INVALID",
                retryable=False,
            )
        counts: list[int] = []
        for text in texts:
            if not text or len(text) > 8_000:
                raise EmbeddingProviderError(
                    "EMBEDDING_INPUT_INVALID",
                    retryable=False,
                )
            count = len(self._encoding.encode(text))
            if count > self._maximum_input_tokens:
                raise EmbeddingProviderError(
                    "EMBEDDING_INPUT_TOKEN_LIMIT",
                    retryable=False,
                )
            counts.append(count)
        if sum(counts) > self._maximum_total_tokens:
            raise EmbeddingProviderError(
                "EMBEDDING_BATCH_TOKEN_LIMIT",
                retryable=False,
            )
        return tuple(counts)

    def _validate_response(
        self,
        response: Any,
        expected_count: int,
    ) -> tuple[tuple[float, ...], ...]:
        if getattr(response, "model", None) != self.model:
            raise EmbeddingProviderError(
                "EMBEDDING_MODEL_MISMATCH",
                retryable=False,
            )
        data = list(getattr(response, "data", ()))
        if len(data) != expected_count:
            raise EmbeddingProviderError(
                "EMBEDDING_COUNT_MISMATCH",
                retryable=False,
            )
        vectors: list[tuple[float, ...]] = []
        for expected_index, item in enumerate(data):
            if getattr(item, "index", None) != expected_index:
                raise EmbeddingProviderError(
                    "EMBEDDING_ORDER_INVALID",
                    retryable=False,
                )
            raw_vector = getattr(item, "embedding", None)
            if not isinstance(raw_vector, list) or len(raw_vector) != self.dimensions:
                raise EmbeddingProviderError(
                    "EMBEDDING_DIMENSIONS_INVALID",
                    retryable=False,
                )
            vector = tuple(float(value) for value in raw_vector)
            if any(not math.isfinite(value) for value in vector):
                raise EmbeddingProviderError(
                    "EMBEDDING_VALUES_INVALID",
                    retryable=False,
                )
            vectors.append(vector)
        return tuple(vectors)
