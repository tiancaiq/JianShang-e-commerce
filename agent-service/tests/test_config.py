import os
import unittest
from unittest.mock import patch

from msb_agent_service.config import (
    AgentApiSettings,
    AgentPersistenceSettings,
    DiscoveryEmbeddingSettings,
    KnowledgeIndexSettings,
    KnowledgeIngestionSettings,
    ListingProposalApiSettings,
    MarketplaceAgentV2Settings,
    Settings,
)


class SettingsTest(unittest.TestCase):
    def test_marketplace_agent_v2_result_shape_thresholds_are_defaulted_and_bounded(
        self,
    ) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertEqual(5, settings.marketplace_agent_v2.direct_result_max)
        self.assertEqual(10, settings.marketplace_agent_v2.clarification_result_min)
        self.assertEqual(4, settings.marketplace_agent_v2.max_clarification_options)
        self.assertEqual(5, settings.marketplace_agent_v2.default_discovery_top_k)
        self.assertEqual(8, settings.marketplace_agent_v2.max_discovery_top_k)

        with self.assertRaisesRegex(ValueError, "clarification threshold"):
            MarketplaceAgentV2Settings(
                direct_result_max=5,
                clarification_result_min=5,
            ).validate(persistence_enabled=False, provider_configured=False)

        with self.assertRaisesRegex(ValueError, "top-K"):
            MarketplaceAgentV2Settings(
                default_discovery_top_k=6,
                max_discovery_top_k=5,
            ).validate(persistence_enabled=False, provider_configured=False)

    def test_discovery_hybrid_and_query_embedding_are_default_off(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.discovery_api.hybrid_retrieval_enabled)
        self.assertFalse(settings.discovery_api.query_embedding_enabled)
        self.assertEqual(10.0, settings.discovery_api.model_call_timeout_seconds)

    def test_discovery_model_call_timeout_is_explicit_and_bounded(self) -> None:
        with patch.dict(
            os.environ,
            {"AGENT_DISCOVERY_MODEL_CALL_TIMEOUT_SECONDS": "9.5"},
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertEqual(9.5, settings.discovery_api.model_call_timeout_seconds)

        with patch.dict(
            os.environ,
            {"AGENT_DISCOVERY_MODEL_CALL_TIMEOUT_SECONDS": "10.1"},
            clear=True,
        ):
            with self.assertRaisesRegex(
                ValueError,
                "AGENT_DISCOVERY_MODEL_CALL_TIMEOUT_SECONDS",
            ):
                Settings.from_env()

    def test_discovery_hybrid_requires_separate_query_embedding_gate(self) -> None:
        environment = {
            "OPENAI_API_KEY": "offline-placeholder",
            "AGENT_MYSQL_PASSWORD": "database-placeholder",
            "AGENT_PERSISTENCE_ENABLED": "true",
            "AGENT_DISCOVERY_API_ENABLED": "true",
            "AGENT_DISCOVERY_ORCHESTRATION_ENABLED": "true",
            "AGENT_DISCOVERY_PRODUCT_TOOLS_ENABLED": "true",
            "AGENT_DISCOVERY_PROVIDER_ENABLED": "true",
            "AGENT_DISCOVERY_HYBRID_RETRIEVAL_ENABLED": "true",
            "AUTH_SERVICE_URL": "http://auth-service:8085",
            "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
            "AGENT_PRODUCT_SERVICE_TOKEN": "product-placeholder",
        }
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(
                ValueError,
                "AGENT_DISCOVERY_QUERY_EMBEDDING_ENABLED",
            ):
                Settings.from_env()

    def test_discovery_embedding_worker_is_independently_default_off(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.discovery_embedding.intake_enabled)
        self.assertFalse(settings.discovery_embedding.worker_enabled)
        self.assertFalse(settings.discovery_embedding.provider_enabled)
        self.assertFalse(settings.discovery_embedding.recovery_enabled)
        self.assertFalse(settings.discovery_embedding.kill_switch_enabled)
        self.assertEqual(
            "listing-discovery-embedding-request-v1",
            settings.discovery_embedding.kafka_topic,
        )

    def test_discovery_embedding_rejects_partial_or_wrong_topic_enablement(
        self,
    ) -> None:
        with self.assertRaisesRegex(ValueError, "Product 04A event contract"):
            DiscoveryEmbeddingSettings(kafka_topic="listing-knowledge-v1").validate(
                mysql_password_configured=False,
                product_source_configured=False,
                provider_configured=False,
            )

        with patch.dict(
            os.environ,
            {"AGENT_DISCOVERY_EMBEDDING_INTAKE_ENABLED": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "AGENT_MYSQL_PASSWORD"):
                Settings.from_env()

        environment = {
            "AGENT_DISCOVERY_EMBEDDING_WORKER_ENABLED": "true",
            "AGENT_MYSQL_PASSWORD": "database-secret",
            "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
            "AGENT_PRODUCT_SERVICE_TOKEN": "source-secret",
        }
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(
                ValueError,
                "AGENT_DISCOVERY_EMBEDDING_PROVIDER_ENABLED",
            ):
                Settings.from_env()

        with patch.dict(
            os.environ,
            {"AGENT_DISCOVERY_EMBEDDING_RECOVERY_ENABLED": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "AGENT_MYSQL_PASSWORD"):
                Settings.from_env()

    def test_discovery_embedding_recovery_requires_only_agent_database(
        self,
    ) -> None:
        with patch.dict(
            os.environ,
            {
                "AGENT_MYSQL_PASSWORD": "database-secret",
                "AGENT_DISCOVERY_EMBEDDING_RECOVERY_ENABLED": "true",
            },
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertTrue(settings.discovery_embedding.recovery_enabled)
        self.assertFalse(settings.discovery_embedding.intake_enabled)
        self.assertFalse(settings.discovery_embedding.worker_enabled)
        self.assertNotIn("database-secret", repr(settings))

    def test_discovery_embedding_enabled_configuration_is_redacted(self) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "provider-secret",
                "AGENT_MYSQL_PASSWORD": "database-secret",
                "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
                "AGENT_PRODUCT_SERVICE_TOKEN": "source-secret",
                "AGENT_DISCOVERY_EMBEDDING_INTAKE_ENABLED": "true",
                "AGENT_DISCOVERY_EMBEDDING_WORKER_ENABLED": "true",
                "AGENT_DISCOVERY_EMBEDDING_PROVIDER_ENABLED": "true",
            },
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertTrue(settings.discovery_embedding.intake_enabled)
        self.assertTrue(settings.discovery_embedding.worker_enabled)
        self.assertNotIn("provider-secret", repr(settings))
        self.assertNotIn("database-secret", repr(settings))
        self.assertNotIn("source-secret", repr(settings))

    def test_customer_service_api_is_disabled_by_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.agent_api.enabled)
        self.assertFalse(settings.agent_api.kill_switch_enabled)
        self.assertFalse(settings.agent_api.orchestration_enabled)
        self.assertFalse(settings.agent_api.retrieval_enabled)
        self.assertFalse(settings.agent_api.provider_enabled)
        self.assertFalse(settings.agent_api.generation_enabled)
        self.assertIsNone(settings.agent_api.auth_service_url)
        self.assertIsNone(settings.agent_api.product_service_token)

    def test_customer_service_api_requires_persistence_and_dependencies(self) -> None:
        with self.assertRaisesRegex(ValueError, "AGENT_PERSISTENCE_ENABLED"):
            AgentApiSettings(
                enabled=True,
                auth_service_url="http://auth-service:8085",
                product_service_url="http://product-service:8091",
                product_service_token="secret",
            ).validate(persistence_enabled=False)

        with self.assertRaisesRegex(ValueError, "AUTH_SERVICE_URL"):
            AgentApiSettings(
                enabled=True,
                product_service_url="http://product-service:8091",
                product_service_token="secret",
            ).validate(persistence_enabled=True)

    def test_customer_service_generation_requires_api_and_every_gate(self) -> None:
        with self.assertRaisesRegex(
            ValueError,
            "AGENT_CUSTOMER_SERVICE_API_ENABLED",
        ):
            AgentApiSettings(orchestration_enabled=True).validate(
                persistence_enabled=False
            )

        partial = AgentApiSettings(
            enabled=True,
            orchestration_enabled=True,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
            product_service_token="secret",
        )
        partial.validate(persistence_enabled=True)
        self.assertFalse(partial.generation_enabled)

        enabled = AgentApiSettings(
            enabled=True,
            orchestration_enabled=True,
            retrieval_enabled=True,
            provider_enabled=True,
            auth_service_url="http://auth-service:8085",
            product_service_url="http://product-service:8091",
            product_service_token="secret",
        )
        enabled.validate(persistence_enabled=True)
        self.assertTrue(enabled.generation_enabled)
        self.assertFalse(
            enabled.__class__(
                **{
                    **enabled.__dict__,
                    "kill_switch_enabled": True,
                }
            ).generation_enabled
        )

    def test_listing_proposal_api_and_generation_flags_are_default_off(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.listing_proposal_api.enabled)
        self.assertFalse(settings.listing_proposal_api.kill_switch_enabled)
        self.assertFalse(settings.listing_proposal_api.orchestration_enabled)
        self.assertFalse(settings.listing_proposal_api.media_tool_enabled)
        self.assertFalse(settings.listing_proposal_api.multimodal_provider_enabled)
        self.assertFalse(
            settings.listing_proposal_api.multimodal_provider_configured
        )

    def test_listing_proposal_api_requires_persistence_and_provider_config(self) -> None:
        with self.assertRaisesRegex(ValueError, "AGENT_PERSISTENCE_ENABLED"):
            ListingProposalApiSettings(
                enabled=True,
                auth_service_url="http://auth-service:8085",
            ).validate(persistence_enabled=False)
        with self.assertRaisesRegex(
            ValueError,
            "AGENT_LISTING_PROPOSAL_PROVIDER_CONFIGURED",
        ):
            ListingProposalApiSettings(
                multimodal_provider_enabled=True,
            ).validate(persistence_enabled=False)

    def test_agent_persistence_is_disabled_and_redacted_by_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.agent_persistence.enabled)
        self.assertEqual(90, settings.agent_persistence.message_retention_days)
        self.assertEqual(365, settings.agent_persistence.audit_retention_days)
        self.assertIsNone(settings.agent_persistence.mysql_password)

    def test_enabled_agent_persistence_requires_database_secret(self) -> None:
        with patch.dict(
            os.environ,
            {"AGENT_PERSISTENCE_ENABLED": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "AGENT_MYSQL_PASSWORD"):
                Settings.from_env()

    def test_agent_persistence_cannot_extend_approved_retention(self) -> None:
        with self.assertRaisesRegex(ValueError, "between 1 and 90"):
            AgentPersistenceSettings(message_retention_days=91).validate()

    def test_category_processing_requires_explicit_processor_enablement(self) -> None:
        with patch.dict(
            os.environ,
            {"AGENT_CATEGORY_GUIDANCE_PROCESSING_ENABLED": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(
                ValueError,
                "AGENT_KNOWLEDGE_PROCESSOR_ENABLED",
            ):
                Settings.from_env()

    def test_missing_key_is_allowed_but_not_ready(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.openai_configured)
        self.assertIsNone(settings.openai_api_key)
        self.assertEqual("gpt-5-mini", settings.openai_model)

    def test_secret_is_not_in_settings_representation(self) -> None:
        settings = Settings(openai_api_key="test-secret")

        self.assertNotIn("test-secret", repr(settings))

    def test_open_search_password_is_not_in_settings_representation(self) -> None:
        configured = KnowledgeIndexSettings(
            enabled=True,
            url="https://search.example.test",
            username="agent",
            password="super-secret",
            embedding_provider="synthetic",
            embedding_model="test-model",
            embedding_dimensions=3,
        )

        self.assertNotIn("super-secret", repr(configured))

    def test_invalid_retry_count_fails_configuration(self) -> None:
        with patch.dict(os.environ, {"OPENAI_MAX_RETRIES": "11"}, clear=True):
            with self.assertRaisesRegex(ValueError, "between 0 and 10"):
                Settings.from_env()

    def test_knowledge_index_is_disabled_by_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.knowledge.enabled)
        self.assertIsNone(settings.knowledge.url)

    def test_knowledge_ingestion_is_disabled_by_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            settings = Settings.from_env()

        self.assertFalse(settings.knowledge_ingestion.enabled)
        self.assertIsNone(settings.knowledge_ingestion.mysql_password)
        self.assertIsNone(settings.knowledge_ingestion.product_service_token)

    def test_enabled_ingestion_requires_database_and_source_secrets(self) -> None:
        with patch.dict(
            os.environ,
            {"AGENT_KNOWLEDGE_INGESTION_ENABLED": "true"},
            clear=True,
        ):
            with self.assertRaisesRegex(ValueError, "AGENT_MYSQL_PASSWORD"):
                Settings.from_env()

    def test_enabled_ingestion_configuration_is_validated_and_redacted(self) -> None:
        with patch.dict(
            os.environ,
            {
                "AGENT_KNOWLEDGE_INGESTION_ENABLED": "true",
                "AGENT_MYSQL_PASSWORD": "database-secret",
                "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
                "AGENT_PRODUCT_SERVICE_TOKEN": "source-secret",
                "AGENT_KAFKA_BOOTSTRAP_SERVERS": "broker:29092",
            },
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertTrue(settings.knowledge_ingestion.enabled)
        self.assertEqual(
            "listing-knowledge-v1",
            settings.knowledge_ingestion.kafka_topic,
        )
        self.assertNotIn("database-secret", repr(settings))
        self.assertNotIn("source-secret", repr(settings))

    def test_processor_requires_openai_key_and_exact_embedding_identity(
        self,
    ) -> None:
        environment = {
            "AGENT_KNOWLEDGE_INGESTION_ENABLED": "true",
            "AGENT_MYSQL_PASSWORD": "database-secret",
            "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
            "AGENT_PRODUCT_SERVICE_TOKEN": "source-secret",
            "AGENT_KNOWLEDGE_ENABLED": "true",
            "AGENT_OPENSEARCH_URL": "http://opensearch:9200",
            "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER": "openai",
            "AGENT_KNOWLEDGE_EMBEDDING_MODEL": "text-embedding-3-small",
            "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS": "1536",
            "AGENT_KNOWLEDGE_PROCESSOR_ENABLED": "true",
        }
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(ValueError, "OPENAI_API_KEY"):
                Settings.from_env()

        environment["OPENAI_API_KEY"] = "test-key"
        environment["AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS"] = "1024"
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(
                ValueError,
                "text-embedding-3-small/1536",
            ):
                Settings.from_env()

    def test_exact_processor_configuration_is_accepted_and_redacted(
        self,
    ) -> None:
        with patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "embedding-secret",
                "AGENT_KNOWLEDGE_INGESTION_ENABLED": "true",
                "AGENT_MYSQL_PASSWORD": "database-secret",
                "AGENT_PRODUCT_SERVICE_URL": "http://product-service:8091",
                "AGENT_PRODUCT_SERVICE_TOKEN": "source-secret",
                "AGENT_KNOWLEDGE_ENABLED": "true",
                "AGENT_OPENSEARCH_URL": "http://opensearch:9200",
                "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER": "openai",
                "AGENT_KNOWLEDGE_EMBEDDING_MODEL": "text-embedding-3-small",
                "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS": "1536",
                "AGENT_KNOWLEDGE_PROCESSOR_ENABLED": "true",
            },
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertTrue(settings.knowledge_ingestion.processor_enabled)
        self.assertNotIn("embedding-secret", repr(settings))

    def test_ingestion_rejects_credentials_in_source_url(self) -> None:
        configured = KnowledgeIngestionSettings(
            enabled=True,
            mysql_password="database-secret",
            product_service_url="http://agent:secret@product-service:8091",
            product_service_token="source-secret",
        )

        with self.assertRaisesRegex(ValueError, "must not contain credentials"):
            configured.validate()

    def test_enabled_knowledge_index_requires_embedding_identity(self) -> None:
        with patch.dict(
            os.environ,
            {
                "AGENT_KNOWLEDGE_ENABLED": "true",
                "AGENT_OPENSEARCH_URL": "http://localhost:9201",
            },
            clear=True,
        ):
            with self.assertRaisesRegex(
                ValueError, "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER"
            ):
                Settings.from_env()

    def test_enabled_knowledge_index_configuration_is_validated(self) -> None:
        with patch.dict(
            os.environ,
            {
                "AGENT_KNOWLEDGE_ENABLED": "true",
                "AGENT_OPENSEARCH_URL": "http://localhost:9201",
                "AGENT_KNOWLEDGE_EMBEDDING_PROVIDER": "synthetic",
                "AGENT_KNOWLEDGE_EMBEDDING_MODEL": "test-model",
                "AGENT_KNOWLEDGE_EMBEDDING_DIMENSIONS": "3",
                "AGENT_OPENSEARCH_PASSWORD": "secret",
                "AGENT_OPENSEARCH_USERNAME": "agent",
            },
            clear=True,
        ):
            settings = Settings.from_env()

        self.assertTrue(settings.knowledge.enabled)
        self.assertEqual(3, settings.knowledge.embedding_dimensions)
        self.assertNotIn("secret", repr(settings))

    def test_knowledge_index_rejects_product_service_index(self) -> None:
        settings = KnowledgeIndexSettings(index_prefix="msb-public-listings")

        with self.assertRaisesRegex(ValueError, "Product Service"):
            settings.validate()

    def test_knowledge_index_rejects_unsafe_url_and_alias_collision(self) -> None:
        base = {
            "enabled": True,
            "url": "http://localhost:9201?token=unsafe",
            "embedding_provider": "synthetic",
            "embedding_model": "test-model",
            "embedding_dimensions": 3,
        }
        with self.assertRaisesRegex(ValueError, "query or fragment"):
            KnowledgeIndexSettings(**base).validate()

        base["url"] = "http://localhost:9201"
        base["read_alias"] = "msb-public-listings"
        with self.assertRaisesRegex(ValueError, "Product Service"):
            KnowledgeIndexSettings(**base).validate()


if __name__ == "__main__":
    unittest.main()
