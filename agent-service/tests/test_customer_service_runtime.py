from __future__ import annotations

import unittest

from prometheus_client import CollectorRegistry

from msb_agent_service.config import (
    AgentApiSettings,
    AgentPersistenceSettings,
    KnowledgeIndexSettings,
    Settings,
)
from msb_agent_service.customer_service_langchain import (
    LangChainQuestionAnswerer,
)
from msb_agent_service.customer_service_runtime import (
    build_customer_service_runtime,
)
from test_customer_service_orchestration import FakeRepository


def runtime_settings() -> Settings:
    return Settings(
        openai_api_key="offline-placeholder",
        agent_persistence=AgentPersistenceSettings(
            enabled=True,
            mysql_password="offline-placeholder",
        ),
        agent_api=AgentApiSettings(
            enabled=True,
            orchestration_enabled=True,
            retrieval_enabled=True,
            provider_enabled=True,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
            product_service_token="offline-placeholder",
        ),
        knowledge=KnowledgeIndexSettings(
            enabled=True,
            url="http://opensearch:9200",
            embedding_provider="openai",
            embedding_model="text-embedding-3-small",
            embedding_dimensions=1536,
        ),
    )


class CustomerServiceRuntimeTest(unittest.IsolatedAsyncioTestCase):
    async def test_composes_existing_retriever_provider_and_langchain_adapter(
        self,
    ) -> None:
        runtime = build_customer_service_runtime(
            runtime_settings(),
            FakeRepository(),  # type: ignore[arg-type]
            object(),  # type: ignore[arg-type]
            CollectorRegistry(),
        )
        self.addAsyncCleanup(runtime.close)

        self.assertIsInstance(runtime.answerer, LangChainQuestionAnswerer)
        self.assertEqual("openai", runtime.answerer.metadata.model_provider)
        self.assertEqual(
            "listing-customer-service-v1",
            runtime.answerer.metadata.prompt_version,
        )
        self.assertIsNotNone(runtime.embedding_provider)
        self.assertIsNotNone(runtime.provider)

    async def test_incomplete_or_disabled_runtime_fails_before_dependencies(
        self,
    ) -> None:
        settings = runtime_settings()
        disabled = Settings(
            **{
                **settings.__dict__,
                "agent_api": AgentApiSettings(
                    **{
                        **settings.agent_api.__dict__,
                        "provider_enabled": False,
                    }
                ),
            }
        )

        with self.assertRaisesRegex(
            RuntimeError,
            "requires configured OpenAI",
        ):
            build_customer_service_runtime(
                disabled,
                FakeRepository(),  # type: ignore[arg-type]
                object(),  # type: ignore[arg-type]
                CollectorRegistry(),
            )


if __name__ == "__main__":
    unittest.main()
