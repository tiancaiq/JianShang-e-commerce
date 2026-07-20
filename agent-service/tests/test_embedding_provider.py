from __future__ import annotations

import unittest
from types import SimpleNamespace

from prometheus_client import CollectorRegistry

from msb_agent_service.embedding_provider import (
    EmbeddingProviderError,
    OpenAIEmbeddingProvider,
)
from msb_agent_service.knowledge_ingestion_metrics import (
    KnowledgeIngestionMetrics,
)


class FakeEmbeddings:
    def __init__(self, response: object | None = None) -> None:
        self.response = response
        self.calls: list[dict[str, object]] = []
        self.failure: Exception | None = None

    async def create(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        if self.failure is not None:
            raise self.failure
        return self.response


class FakeClient:
    def __init__(self, response: object | None = None) -> None:
        self.embeddings = FakeEmbeddings(response)


class FakeEncoding:
    def encode(self, text: str) -> list[int]:
        return list(text.encode("utf-8"))


def response(
    *,
    model: str = "text-embedding-3-small",
    vectors: list[list[float]] | None = None,
    indices: list[int] | None = None,
    total_tokens: int | None = 7,
) -> object:
    values = vectors or [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
    positions = indices or list(range(len(values)))
    return SimpleNamespace(
        model=model,
        data=[
            SimpleNamespace(index=index, embedding=vector)
            for index, vector in zip(positions, values, strict=True)
        ],
        usage=SimpleNamespace(total_tokens=total_tokens),
    )


class OpenAIEmbeddingProviderTest(unittest.IsolatedAsyncioTestCase):
    def provider(self, client: FakeClient) -> OpenAIEmbeddingProvider:
        return OpenAIEmbeddingProvider(
            api_key="test-key",
            model="text-embedding-3-small",
            dimensions=3,
            timeout_seconds=1,
            max_retries=0,
            maximum_inputs=4,
            maximum_input_tokens=100,
            maximum_total_tokens=200,
            metrics=KnowledgeIngestionMetrics(CollectorRegistry()),
            client=client,
            encoding=FakeEncoding(),
        )

    async def test_success_preserves_order_and_sends_exact_identity(self) -> None:
        client = FakeClient(response(total_tokens=None))
        provider = self.provider(client)

        result = await provider.embed(
            ["first", "second"],
            correlation_id="01C00000000000000000000001",
        )

        self.assertEqual(
            ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0)),
            result.vectors,
        )
        self.assertGreater(result.input_tokens, 0)
        self.assertEqual(
            {
                "model": "text-embedding-3-small",
                "input": ["first", "second"],
                "dimensions": 3,
                "encoding_format": "float",
            },
            client.embeddings.calls[0],
        )

    async def test_invalid_response_shapes_fail_closed(self) -> None:
        cases = (
            (
                response(model="other-model"),
                "EMBEDDING_MODEL_MISMATCH",
            ),
            (
                response(vectors=[[1.0, 0.0, 0.0]]),
                "EMBEDDING_COUNT_MISMATCH",
            ),
            (
                response(indices=[1, 0]),
                "EMBEDDING_ORDER_INVALID",
            ),
            (
                response(vectors=[[1.0, 0.0], [0.0, 1.0]]),
                "EMBEDDING_DIMENSIONS_INVALID",
            ),
            (
                response(
                    vectors=[[float("nan"), 0.0, 0.0], [0.0, 1.0, 0.0]]
                ),
                "EMBEDDING_VALUES_INVALID",
            ),
        )
        for invalid_response, expected_code in cases:
            with self.subTest(expected_code=expected_code):
                with self.assertRaises(EmbeddingProviderError) as captured:
                    await self.provider(FakeClient(invalid_response)).embed(
                        ["first", "second"],
                        correlation_id=None,
                    )
                self.assertEqual(expected_code, captured.exception.code)
                self.assertFalse(captured.exception.retryable)

    async def test_input_limits_fail_before_provider_call(self) -> None:
        client = FakeClient(response())
        provider = self.provider(client)

        with self.assertRaisesRegex(
            EmbeddingProviderError,
            "EMBEDDING_INPUT_COUNT_INVALID",
        ):
            await provider.embed([], correlation_id=None)
        with self.assertRaisesRegex(
            EmbeddingProviderError,
            "EMBEDDING_INPUT_INVALID",
        ):
            await provider.embed([""], correlation_id=None)

        self.assertEqual([], client.embeddings.calls)

    async def test_unknown_sdk_failure_is_safe_and_non_retryable(self) -> None:
        client = FakeClient(response())
        client.embeddings.failure = RuntimeError("provider detail")

        with self.assertRaises(EmbeddingProviderError) as captured:
            await self.provider(client).embed(["safe"], correlation_id=None)

        self.assertEqual("OPENAI_UNAVAILABLE", captured.exception.code)
        self.assertFalse(captured.exception.retryable)
        self.assertNotIn("provider detail", str(captured.exception))


if __name__ == "__main__":
    unittest.main()
