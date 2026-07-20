import unittest

import httpx

from msb_agent_service.api import create_app
from msb_agent_service.config import AgentPersistenceSettings
from msb_agent_service.config import (
    KnowledgeIndexSettings,
    KnowledgeIngestionSettings,
    Settings,
)
from msb_agent_service.knowledge_index import KnowledgeReadinessStatus
from msb_agent_service.knowledge_ingestion_runtime import KnowledgeIngestionStatus


class HealthApiTest(unittest.IsolatedAsyncioTestCase):
    async def test_health_does_not_require_openai(self) -> None:
        app = create_app(Settings(openai_api_key=None))
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"status": "UP", "service": "agent-service"}, response.json())

    async def test_readiness_is_unavailable_without_key(self) -> None:
        app = create_app(Settings(openai_api_key=None))
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/ready")

        self.assertEqual(503, response.status_code)
        self.assertEqual(
            {
                "status": "NOT_READY",
                "openai": "OPENAI_API_KEY_NOT_CONFIGURED",
                "knowledgeIndex": "DISABLED",
                "knowledgeIngestion": "DISABLED",
                "categoryGuidanceIntake": "DISABLED",
                "agentPersistence": "DISABLED",
                "customerServiceApi": "DISABLED",
            },
            response.json(),
        )

    async def test_readiness_checks_configuration_without_provider_call(self) -> None:
        app = create_app(Settings(openai_api_key="configured-for-test"))
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get("/ready")

        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {
                "status": "READY",
                "openai": "CONFIGURED",
                "knowledgeIndex": "DISABLED",
                "knowledgeIngestion": "DISABLED",
                "categoryGuidanceIntake": "DISABLED",
                "agentPersistence": "DISABLED",
                "customerServiceApi": "DISABLED",
            },
            response.json(),
        )

    async def test_enabled_knowledge_index_affects_readiness(self) -> None:
        calls = 0

        async def unavailable() -> KnowledgeReadinessStatus:
            nonlocal calls
            calls += 1
            return KnowledgeReadinessStatus.UNAVAILABLE

        settings = Settings(
            openai_api_key="configured-for-test",
            knowledge=KnowledgeIndexSettings(
                enabled=True,
                url="http://localhost:9201",
                embedding_provider="synthetic",
                embedding_model="test",
                embedding_dimensions=3,
            ),
        )
        app = create_app(settings, knowledge_readiness_probe=unavailable)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport, base_url="http://test"
        ) as client:
            response = await client.get("/ready")

        self.assertEqual(1, calls)
        self.assertEqual(503, response.status_code)
        self.assertEqual("UNAVAILABLE", response.json()["knowledgeIndex"])

    async def test_metrics_endpoint_is_internal_and_contains_knowledge_metrics(
        self,
    ) -> None:
        app = create_app(Settings(openai_api_key=None))
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport, base_url="http://test"
        ) as client:
            response = await client.get("/metrics/")

        self.assertEqual(200, response.status_code)
        self.assertIn(
            "agent_knowledge_index_operation_total",
            response.text,
        )

    async def test_customer_service_routes_fail_closed_when_disabled(self) -> None:
        app = create_app(Settings(openai_api_key="configured-for-test"))
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://test",
        ) as client:
            response = await client.get(
                "/api/v1/agent/sessions/01ARZ3NDEKTSV4RRFFQ69G5FAY",
                headers={"X-Correlation-Id": "disabled-agent-test"},
            )

        self.assertEqual(404, response.status_code)
        self.assertEqual("AGENT_FEATURE_DISABLED", response.json()["error"]["code"])
        self.assertEqual(
            "disabled-agent-test",
            response.json()["error"]["correlationId"],
        )

    async def test_openapi_uses_approved_agent_session_path_parameter(self) -> None:
        app = create_app(Settings(openai_api_key="configured-for-test"))

        schema = app.openapi()

        self.assertIn("/api/v1/agent/sessions/{sessionId}", schema["paths"])
        self.assertIn(
            "/api/v1/agent/sessions/{sessionId}/messages",
            schema["paths"],
        )
        self.assertNotIn("/api/v1/agent/sessions/{session_id}", schema["paths"])

    async def test_enabled_ingestion_starts_and_stops_in_lifespan(self) -> None:
        class FakeRuntime:
            started = False
            stopped = False

            async def start(self) -> None:
                self.started = True

            async def stop(self) -> None:
                self.stopped = True

            def status(self) -> KnowledgeIngestionStatus:
                return (
                    KnowledgeIngestionStatus.READY
                    if self.started and not self.stopped
                    else KnowledgeIngestionStatus.UNAVAILABLE
                )

        runtime = FakeRuntime()

        async def factory(settings, registry):
            return runtime

        app = create_app(
            Settings(
                openai_api_key="configured-for-test",
                knowledge_ingestion=KnowledgeIngestionSettings(enabled=True),
            ),
            ingestion_runtime_factory=factory,
        )
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")
            self.assertTrue(runtime.started)
            self.assertEqual(200, response.status_code)
            self.assertEqual("READY", response.json()["knowledgeIngestion"])

        self.assertTrue(runtime.stopped)

    async def test_enabled_persistence_validates_and_closes_in_lifespan(self) -> None:
        class FakeRepository:
            validated = False
            closed = False

            async def validate_schema(self) -> None:
                self.validated = True

            async def close(self) -> None:
                self.closed = True

        repository = FakeRepository()

        async def factory(settings, metrics):
            return repository

        app = create_app(
            Settings(
                openai_api_key="configured-for-test",
                agent_persistence=AgentPersistenceSettings(
                    enabled=True,
                    mysql_password="database-secret",
                ),
            ),
            persistence_repository_factory=factory,
        )
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")
            self.assertTrue(repository.validated)
            self.assertEqual("READY", response.json()["agentPersistence"])

        self.assertTrue(repository.closed)


if __name__ == "__main__":
    unittest.main()
