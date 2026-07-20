from __future__ import annotations

from dataclasses import dataclass

from opensearchpy import AsyncOpenSearch
from prometheus_client import CollectorRegistry, Counter, Histogram

from .agent_persistence import AgentPersistenceRepository
from .config import Settings
from .customer_service_api import QuestionAnswerer
from .customer_service_langchain import LangChainQuestionAnswerer
from .customer_service_provider import build_provider_bound_answerer
from .embedding_provider import OpenAIEmbeddingProvider
from .knowledge_retriever import (
    KnowledgeRetrievalMetrics,
    OpenSearchListingKnowledgeRetriever,
)
from .provider import OpenAIProvider


class _Utf8ByteEncoding:
    """Conservatively bound query input without downloading tokenizer assets."""

    @staticmethod
    def encode(value: str) -> bytes:
        return value.encode("utf-8")


class CustomerServiceEmbeddingMetrics:
    """Records customer-service query embeddings without request identifiers."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self._requests = Counter(
            "agent_customer_service_embedding_requests_total",
            "Customer-service query embedding requests by bounded result.",
            ("result",),
            registry=registry,
        )
        self._duration = Histogram(
            "agent_customer_service_embedding_duration_seconds",
            "Customer-service query embedding latency.",
            registry=registry,
        )
        self._input_tokens = Counter(
            "agent_customer_service_embedding_input_tokens_total",
            "Provider-reported customer-service query embedding input tokens.",
            registry=registry,
        )

    def record_embedding(
        self,
        result: str,
        duration_seconds: float,
        *,
        input_tokens: int = 0,
    ) -> None:
        self._requests.labels(result=result).inc()
        self._duration.observe(duration_seconds)
        if input_tokens > 0:
            self._input_tokens.inc(input_tokens)


@dataclass
class CustomerServiceRuntime:
    """Owns production answer composition and its provider resources."""

    answerer: QuestionAnswerer
    embedding_provider: OpenAIEmbeddingProvider
    provider: OpenAIProvider

    async def close(self) -> None:
        try:
            await self.embedding_provider.close()
        finally:
            await self.provider.close()


def build_customer_service_runtime(
    settings: Settings,
    repository: AgentPersistenceRepository,
    open_search_client: AsyncOpenSearch,
    registry: CollectorRegistry,
) -> CustomerServiceRuntime:
    """Compose the existing secured RAG path only after every runtime gate passes."""

    knowledge = settings.knowledge
    if (
        not settings.agent_api.generation_enabled
        or not settings.openai_configured
        or not knowledge.enabled
        or knowledge.embedding_provider != "openai"
        or knowledge.embedding_model is None
        or knowledge.embedding_dimensions is None
    ):
        raise RuntimeError(
            "Enabled customer-service generation requires configured OpenAI "
            "provider and OpenSearch retrieval"
        )
    embedding_provider = OpenAIEmbeddingProvider(
        api_key=str(settings.openai_api_key),
        model=knowledge.embedding_model,
        dimensions=knowledge.embedding_dimensions,
        timeout_seconds=settings.openai_timeout_seconds,
        max_retries=settings.openai_max_retries,
        maximum_inputs=1,
        maximum_input_tokens=8_000,
        maximum_total_tokens=8_000,
        metrics=CustomerServiceEmbeddingMetrics(registry),
        encoding=_Utf8ByteEncoding(),
    )
    provider = OpenAIProvider(settings)
    retriever = OpenSearchListingKnowledgeRetriever(
        client=open_search_client,
        read_alias=knowledge.read_alias,
        embedding_provider=embedding_provider,
        embedding_provider_name="openai",
        embedding_model=knowledge.embedding_model,
        embedding_dimensions=knowledge.embedding_dimensions,
        metrics=KnowledgeRetrievalMetrics(registry),
    )
    direct_answerer = build_provider_bound_answerer(
        repository=repository,
        retriever=retriever,
        provider=provider,
    )
    return CustomerServiceRuntime(
        answerer=LangChainQuestionAnswerer(direct_answerer),
        embedding_provider=embedding_provider,
        provider=provider,
    )
