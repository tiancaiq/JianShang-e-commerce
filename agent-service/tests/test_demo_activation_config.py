from pathlib import Path
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _service_block(compose: str, service: str) -> str:
    """Return one top-level Compose service block for static contract checks."""

    marker = f"  {service}:\n"
    start = compose.index(marker)
    lines = compose[start + len(marker) :].splitlines()
    block: list[str] = []
    for line in lines:
        if line.startswith("  ") and not line.startswith("    "):
            break
        block.append(line)
    return "\n".join(block)


class DemoActivationConfigTests(unittest.TestCase):
    """Protect the tracked demo opt-in while keeping every shared default off."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.demo = (REPOSITORY_ROOT / "docker-compose.demo.yml").read_text(
            encoding="utf-8"
        )
        cls.production = (REPOSITORY_ROOT / "docker-compose.prod.yml").read_text(
            encoding="utf-8"
        )
        cls.combined = (
            REPOSITORY_ROOT / "docker-compose.demo-ai-main.yml"
        ).read_text(encoding="utf-8")

    def test_agent_and_gateway_capabilities_default_off(self) -> None:
        agent = _service_block(self.demo, "agent-service")
        for variable in (
            "AGENT_KNOWLEDGE_ENABLED",
            "AGENT_KNOWLEDGE_INGESTION_ENABLED",
            "AGENT_KNOWLEDGE_PROCESSOR_ENABLED",
            "AGENT_PERSISTENCE_ENABLED",
            "AGENT_CUSTOMER_SERVICE_API_ENABLED",
            "AGENT_CUSTOMER_SERVICE_KILL_SWITCH_ENABLED",
            "AGENT_CUSTOMER_SERVICE_ORCHESTRATION_ENABLED",
            "AGENT_CUSTOMER_SERVICE_RETRIEVAL_ENABLED",
            "AGENT_CUSTOMER_SERVICE_PROVIDER_ENABLED",
            "AGENT_DISCOVERY_API_ENABLED",
            "AGENT_DISCOVERY_KILL_SWITCH_ENABLED",
            "AGENT_DISCOVERY_ORCHESTRATION_ENABLED",
            "AGENT_DISCOVERY_PRODUCT_TOOLS_ENABLED",
            "AGENT_DISCOVERY_PROVIDER_ENABLED",
            "AGENT_MARKETPLACE_V2_API_ENABLED",
            "AGENT_MARKETPLACE_V2_PROVIDER_ENABLED",
            "AGENT_MARKETPLACE_V2_PRODUCT_TOOLS_ENABLED",
            "AGENT_MARKETPLACE_V2_COMMERCE_READS_ENABLED",
            "AGENT_MARKETPLACE_V2_CART_MUTATIONS_ENABLED",
            "AGENT_MARKETPLACE_V2_CHECKOUT_ENABLED",
            "AGENT_MARKETPLACE_V2_ORDER_MUTATIONS_ENABLED",
            "AGENT_MARKETPLACE_V2_RETURN_REQUESTS_ENABLED",
        ):
            self.assertIn(f"{variable}: ${{{variable}:-false}}", agent)

        for compose in (self.demo, self.production):
            gateway = _service_block(compose, "api-gateway")
            self.assertIn(
                "GATEWAY_FEATURE_AGENT: ${GATEWAY_FEATURE_AGENT:-false}",
                gateway,
            )
            self.assertIn(
                "GATEWAY_FEATURE_AGENT_DISCOVERY: "
                "${GATEWAY_FEATURE_AGENT_DISCOVERY:-false}",
                gateway,
            )

        product = _service_block(self.demo, "product-service")
        self.assertIn(
            "LISTING_SEARCH_HYBRID_ENABLED: "
            "${LISTING_SEARCH_HYBRID_ENABLED:-false}",
            product,
        )

    def test_ai_profile_uses_isolated_approved_kafka_topology(self) -> None:
        zookeeper = _service_block(self.demo, "agent-zookeeper")
        broker = _service_block(self.demo, "broker")

        self.assertIn("confluentinc/cp-zookeeper:7.5.0", zookeeper)
        self.assertIn("confluentinc/cp-kafka:7.5.0", broker)
        self.assertIn("profiles:\n      - ai", zookeeper)
        self.assertIn("profiles:\n      - ai", broker)
        self.assertIn('KAFKA_ZOOKEEPER_CONNECT: "agent-zookeeper:2181"', broker)
        self.assertIn("cub zk-ready localhost 2181", zookeeper)
        self.assertNotIn("cub zk-ready localhost 2181 30", zookeeper)
        self.assertIn("restart: unless-stopped", zookeeper)
        self.assertIn(
            "agent-zookeeper:\n        condition: service_healthy",
            broker,
        )
        self.assertIn("cub kafka-ready -b localhost:29092 1 30", broker)
        self.assertIn("restart: unless-stopped", broker)

    def test_bootstrap_is_explicit_and_agent_startup_waits_for_safe_dependencies(
        self,
    ) -> None:
        bootstrap = _service_block(self.demo, "agent-index-bootstrap")
        agent = _service_block(self.demo, "agent-service")
        migrations = _service_block(self.demo, "agent-migrations")

        self.assertIn("profiles:\n      - ai-bootstrap", bootstrap)
        self.assertIn(
            "AGENT_KNOWLEDGE_ENABLED: ${AGENT_KNOWLEDGE_ENABLED:-false}",
            bootstrap,
        )
        self.assertNotIn("agent-index-bootstrap", agent)
        self.assertIn("command: migrate", migrations)
        self.assertIn(
            "agent-migrations:\n        condition: service_completed_successfully",
            agent,
        )
        self.assertIn(
            "agent-opensearch:\n        condition: service_healthy",
            agent,
        )
        self.assertIn(
            "broker:\n        condition: service_healthy",
            agent,
        )

    def test_frontend_container_build_defaults_to_production(self) -> None:
        for compose in (self.demo, self.production):
            frontend = _service_block(compose, "frontend")
            self.assertIn(
                "FRONTEND_BUILD_CONFIGURATION: "
                "${FRONTEND_BUILD_CONFIGURATION:-production}",
                frontend,
            )
            self.assertIn(
                "GATEWAY_FEATURE_AGENT_DISCOVERY: "
                "${GATEWAY_FEATURE_AGENT_DISCOVERY:-false}",
                frontend,
            )

    def test_discovery_frontend_container_build_is_gateway_gated(self) -> None:
        dockerfile = (
            REPOSITORY_ROOT / "frontend" / "Dockerfile"
        ).read_text(encoding="utf-8")

        self.assertIn("ARG GATEWAY_FEATURE_AGENT_DISCOVERY=false", dockerfile)
        self.assertIn(
            "demo-ai-discovery|demo-agent-v2|demo-cart-ai-discovery",
            dockerfile,
        )
        self.assertIn(
            "Discovery frontend builds require "
            "GATEWAY_FEATURE_AGENT_DISCOVERY=true.",
            dockerfile,
        )

    def test_no_provider_credential_is_embedded(self) -> None:
        agent = _service_block(self.demo, "agent-service")

        self.assertIn("OPENAI_API_KEY: ${OPENAI_API_KEY:-}", agent)
        self.assertIn("OPENAI_MODEL: ${OPENAI_MODEL:-gpt-5-mini}", agent)
        self.assertNotIn("sk-", agent.lower())

    def test_combined_main_overlay_activates_the_complete_customer_agent_route(self) -> None:
        agent = _service_block(self.combined, "agent-service")
        product = _service_block(self.combined, "product-service")
        gateway = _service_block(self.combined, "api-gateway")
        frontend = _service_block(self.combined, "frontend")

        self.assertIn('AGENT_KNOWLEDGE_ENABLED: "true"', agent)
        self.assertIn('AGENT_MARKETPLACE_V2_COMMERCE_READS_ENABLED: "true"', agent)
        self.assertIn('AGENT_MARKETPLACE_V2_CART_MUTATIONS_ENABLED: "true"', agent)
        self.assertIn('AGENT_MARKETPLACE_V2_CHECKOUT_ENABLED: "false"', agent)
        self.assertIn(
            "AGENT_MARKETPLACE_V2_RETURN_REQUESTS_ENABLED: "
            "${AGENT_MARKETPLACE_V2_RETURN_REQUESTS_ENABLED:-false}",
            agent,
        )
        self.assertIn('AGENT_MARKETPLACE_V2_MAX_OUTPUT_TOKENS: "4096"', agent)
        self.assertIn('ORDER_SERVICE_URL: "http://order-service:8081"', agent)
        self.assertIn(
            "LISTING_MEDIA_STORAGE: ${LISTING_MEDIA_STORAGE:-local-demo}",
            product,
        )
        self.assertIn('GATEWAY_FEATURE_AGENT: "true"', gateway)
        self.assertIn('GATEWAY_FEATURE_AGENT_DISCOVERY: "true"', gateway)
        self.assertIn(
            "FRONTEND_BUILD_CONFIGURATION: demo-cart-ai-discovery", frontend
        )
        self.assertIn('GATEWAY_FEATURE_AGENT_DISCOVERY: "true"', frontend)


if __name__ == "__main__":
    unittest.main()
