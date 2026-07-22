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
        ):
            self.assertIn(f"{variable}: ${{{variable}:-false}}", agent)

        for compose in (self.demo, self.production):
            gateway = _service_block(compose, "api-gateway")
            self.assertIn(
                "GATEWAY_FEATURE_AGENT: ${GATEWAY_FEATURE_AGENT:-false}",
                gateway,
            )

    def test_ai_profile_uses_isolated_approved_kafka_topology(self) -> None:
        zookeeper = _service_block(self.demo, "agent-zookeeper")
        broker = _service_block(self.demo, "broker")

        self.assertIn("confluentinc/cp-zookeeper:7.5.0", zookeeper)
        self.assertIn("confluentinc/cp-kafka:7.5.0", broker)
        self.assertIn("profiles:\n      - ai", zookeeper)
        self.assertIn("profiles:\n      - ai", broker)
        self.assertIn('KAFKA_ZOOKEEPER_CONNECT: "agent-zookeeper:2181"', broker)

    def test_bootstrap_is_explicit_and_agent_startup_waits_for_safe_dependencies(
        self,
    ) -> None:
        bootstrap = _service_block(self.demo, "agent-index-bootstrap")
        agent = _service_block(self.demo, "agent-service")

        self.assertIn("profiles:\n      - ai-bootstrap", bootstrap)
        self.assertIn(
            "AGENT_KNOWLEDGE_ENABLED: ${AGENT_KNOWLEDGE_ENABLED:-false}",
            bootstrap,
        )
        self.assertNotIn("agent-index-bootstrap", agent)
        self.assertIn(
            "agent-migrations:\n        condition: service_completed_successfully",
            agent,
        )
        self.assertIn(
            "agent-opensearch:\n        condition: service_healthy",
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

    def test_no_provider_credential_is_embedded(self) -> None:
        agent = _service_block(self.demo, "agent-service")

        self.assertIn("OPENAI_API_KEY: ${OPENAI_API_KEY:-}", agent)
        self.assertNotIn("sk-", agent.lower())


if __name__ == "__main__":
    unittest.main()
