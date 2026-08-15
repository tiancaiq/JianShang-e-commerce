from __future__ import annotations

import asyncio
import unittest
from typing import Any

import httpx
from prometheus_client import CollectorRegistry

from msb_agent_service.api import create_app
from msb_agent_service.config import (
    DiscoveryEmbeddingSettings,
    KnowledgeIngestionSettings,
    Settings,
)
from msb_agent_service.discovery_embedding_runtime import (
    DiscoveryEmbeddingRuntime,
    DiscoveryEmbeddingRuntimeStatus,
)


class FakeDiscoveryEmbeddingRuntime:
    def __init__(
        self,
        *,
        status: DiscoveryEmbeddingRuntimeStatus = DiscoveryEmbeddingRuntimeStatus.READY,
        fail_start: bool = False,
    ) -> None:
        self.status_value = status
        self.fail_start = fail_start
        self.started = 0
        self.stopped = 0
        self.provider_embed_calls = 0
        self.events: list[str] = []

    async def start(self) -> None:
        self.events.append("intake-start")
        if self.fail_start:
            raise RuntimeError("DISCOVERY_EMBEDDING_START_FAILED")
        self.events.append("worker-start")
        self.started += 1

    async def stop(self) -> None:
        self.events.append("worker-stop")
        self.events.append("intake-stop")
        self.stopped += 1

    def status(self) -> DiscoveryEmbeddingRuntimeStatus:
        return self.status_value


class DiscoveryEmbeddingRuntimeCompositionTests(unittest.IsolatedAsyncioTestCase):
    async def test_default_off_does_not_construct_runtime_or_touch_dependencies(
        self,
    ) -> None:
        factory_calls = 0

        async def factory(*_: Any) -> FakeDiscoveryEmbeddingRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("Default-off startup must not compose the worker")

        app = create_app(
            Settings(openai_api_key="configured-for-test"),
            discovery_embedding_runtime_factory=factory,  # type: ignore[arg-type]
        )
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        self.assertEqual(0, factory_calls)
        self.assertEqual(200, response.status_code)
        self.assertEqual(
            "DISABLED",
            response.json()["discoveryDocumentEmbedding"],
        )

    async def test_kill_switch_precedes_runtime_composition(self) -> None:
        factory_calls = 0

        async def factory(*_: Any) -> FakeDiscoveryEmbeddingRuntime:
            nonlocal factory_calls
            factory_calls += 1
            raise AssertionError("Kill switch must stop runtime composition")

        app = create_app(
            _embedding_settings(kill_switch_enabled=True),
            discovery_embedding_runtime_factory=factory,  # type: ignore[arg-type]
        )
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")

        self.assertEqual(0, factory_calls)
        self.assertEqual(200, response.status_code)
        self.assertEqual(
            "DISABLED",
            response.json()["discoveryDocumentEmbedding"],
        )

    async def test_enabled_runtime_starts_before_ready_and_stops_in_reverse_order(
        self,
    ) -> None:
        runtime = FakeDiscoveryEmbeddingRuntime()
        factory_calls = 0
        supplied_registry: CollectorRegistry | None = None

        async def factory(
            settings: Settings,
            registry: CollectorRegistry,
        ) -> FakeDiscoveryEmbeddingRuntime:
            nonlocal factory_calls, supplied_registry
            factory_calls += 1
            supplied_registry = registry
            self.assertTrue(settings.discovery_embedding.intake_enabled)
            self.assertTrue(settings.discovery_embedding.worker_enabled)
            return runtime

        app = create_app(
            _embedding_settings(),
            discovery_embedding_runtime_factory=factory,  # type: ignore[arg-type]
        )
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get("/ready")
            self.assertEqual(1, runtime.started)
            self.assertEqual(0, runtime.provider_embed_calls)
            self.assertEqual(200, response.status_code)
            self.assertEqual(
                "READY",
                response.json()["discoveryDocumentEmbedding"],
            )

        self.assertEqual(1, factory_calls)
        self.assertIsNotNone(supplied_registry)
        self.assertEqual(1, runtime.stopped)
        self.assertEqual(
            ["intake-start", "worker-start", "worker-stop", "intake-stop"],
            runtime.events,
        )

    async def test_startup_failure_reports_unavailable_without_claiming_ready(
        self,
    ) -> None:
        runtime = FakeDiscoveryEmbeddingRuntime(fail_start=True)

        async def factory(*_: Any) -> FakeDiscoveryEmbeddingRuntime:
            return runtime

        app = create_app(
            _embedding_settings(),
            discovery_embedding_runtime_factory=factory,  # type: ignore[arg-type]
        )
        with self.assertRaisesRegex(RuntimeError, "DISCOVERY_EMBEDDING_START_FAILED"):
            async with app.router.lifespan_context(app):
                pass

        self.assertEqual(1, runtime.stopped)
        self.assertEqual(["intake-start", "worker-stop", "intake-stop"], runtime.events)

    async def test_runtime_worker_loop_honors_shutdown_cancellation(self) -> None:
        class Repository:
            validated = 0
            closed = 0

            async def validate_schema(self) -> None:
                self.validated += 1

            async def close(self) -> None:
                self.closed += 1

        class Intake:
            running = False

            def __init__(self) -> None:
                self.events: list[str] = []

            async def start(self) -> None:
                self.events.append("intake-start")
                self.running = True

            async def stop(self) -> None:
                self.events.append("intake-stop")
                self.running = False

        class Worker:
            def __init__(self) -> None:
                self.calls = 0

            async def run_once(self) -> int:
                self.calls += 1
                await asyncio.sleep(0)
                return 0

        class Closable:
            def __init__(self) -> None:
                self.closed = 0

            async def close(self) -> None:
                self.closed += 1

        repository = Repository()
        intake = Intake()
        worker = Worker()
        source = Closable()
        callback = Closable()
        provider = Closable()
        runtime = DiscoveryEmbeddingRuntime(
            settings=_embedding_settings(),
            repository=repository,  # type: ignore[arg-type]
            metrics=_NoopMetrics(),  # type: ignore[arg-type]
            source_client=source,  # type: ignore[arg-type]
            callback_client=callback,  # type: ignore[arg-type]
            embedding_provider=provider,  # type: ignore[arg-type]
            intake=intake,  # type: ignore[arg-type]
            worker=worker,  # type: ignore[arg-type]
        )

        await runtime.start()
        self.assertEqual(DiscoveryEmbeddingRuntimeStatus.READY, runtime.status())
        await asyncio.sleep(0)
        await runtime.stop()

        self.assertGreaterEqual(worker.calls, 1)
        self.assertEqual(["intake-start", "intake-stop"], intake.events)
        self.assertEqual(1, repository.validated)
        self.assertEqual(1, repository.closed)
        self.assertEqual(1, source.closed)
        self.assertEqual(1, callback.closed)
        self.assertEqual(1, provider.closed)


class _NoopMetrics:
    def record(self, *_: Any, **__: Any) -> None:
        return None


def _embedding_settings(
    *,
    kill_switch_enabled: bool = False,
) -> Settings:
    return Settings(
        openai_api_key="offline-openai-placeholder",
        knowledge_ingestion=KnowledgeIngestionSettings(
            mysql_password="offline-mysql-placeholder",
            product_service_url="http://product-service:8091",
            product_service_token="offline-product-placeholder",
        ),
        discovery_embedding=DiscoveryEmbeddingSettings(
            intake_enabled=True,
            worker_enabled=True,
            provider_enabled=True,
            kill_switch_enabled=kill_switch_enabled,
            claim_batch_size=2,
            worker_concurrency=1,
        ),
    )


if __name__ == "__main__":
    unittest.main()
