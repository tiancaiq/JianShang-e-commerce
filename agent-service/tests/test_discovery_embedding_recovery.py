from __future__ import annotations

import unittest
from datetime import UTC, datetime

from msb_agent_service.config import DiscoveryEmbeddingSettings
from msb_agent_service.discovery_embedding_jobs import (
    DiscoveryEmbeddingRecoveryMode,
    DiscoveryEmbeddingRecoveryOutcome,
    DiscoveryEmbeddingRecoveryResult,
)
from msb_agent_service.discovery_embedding_recovery import (
    DiscoveryEmbeddingRecoveryCommand,
)

NOW = datetime(2026, 7, 26, 10, tzinfo=UTC)


class FakeRepository:
    def __init__(self) -> None:
        self.validated = 0
        self.calls: list[tuple[str, int, datetime]] = []
        self.result = DiscoveryEmbeddingRecoveryResult(
            outcome=DiscoveryEmbeddingRecoveryOutcome.RECOVERED,
            expected_count=28,
            recovered_count=28,
        )

    async def validate_recovery_schema(self) -> None:
        self.validated += 1

    async def recover_dead_lettered_max_attempts(
        self,
        *,
        recovery_key: str,
        expected_count: int,
        recovered_at: datetime,
    ) -> DiscoveryEmbeddingRecoveryResult:
        self.calls.append((recovery_key, expected_count, recovered_at))
        return self.result

    async def recover_product_version_zero_callback_contract(
        self,
        *,
        recovery_key: str,
        expected_count: int,
        recovered_at: datetime,
    ) -> DiscoveryEmbeddingRecoveryResult:
        self.calls.append((recovery_key, expected_count, recovered_at))
        return self.result


class DiscoveryEmbeddingRecoveryCommandTests(unittest.IsolatedAsyncioTestCase):
    async def test_default_off_and_kill_switch_perform_zero_database_work(self) -> None:
        for settings in (
            DiscoveryEmbeddingSettings(),
            DiscoveryEmbeddingSettings(
                recovery_enabled=True,
                kill_switch_enabled=True,
            ),
        ):
            for mode in DiscoveryEmbeddingRecoveryMode:
                repository = FakeRepository()
                command = DiscoveryEmbeddingRecoveryCommand(
                    settings=settings,
                    repository=repository,  # type: ignore[arg-type]
                    clock=lambda: NOW,
                )
                result = await command.run(
                    recovery_key="recovery-key-0001",
                    expected_count=28,
                    mode=mode,
                )
                self.assertEqual(
                    DiscoveryEmbeddingRecoveryOutcome.DISABLED,
                    result.outcome,
                )
                self.assertEqual(0, repository.validated)
                self.assertEqual([], repository.calls)

    async def test_enabled_command_validates_schema_and_forwards_exact_boundary(
        self,
    ) -> None:
        repository = FakeRepository()
        command = DiscoveryEmbeddingRecoveryCommand(
            settings=DiscoveryEmbeddingSettings(recovery_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            clock=lambda: NOW,
        )
        result = await command.run(
            recovery_key="recovery-key-0002",
            expected_count=28,
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, result.outcome)
        self.assertEqual(1, repository.validated)
        self.assertEqual([("recovery-key-0002", 28, NOW)], repository.calls)

    async def test_version_zero_callback_mode_uses_explicit_repository_boundary(
        self,
    ) -> None:
        repository = FakeRepository()
        command = DiscoveryEmbeddingRecoveryCommand(
            settings=DiscoveryEmbeddingSettings(recovery_enabled=True),
            repository=repository,  # type: ignore[arg-type]
            clock=lambda: NOW,
        )
        result = await command.run(
            recovery_key="recovery-key-0003",
            expected_count=137,
            mode=(
                DiscoveryEmbeddingRecoveryMode
                .PRODUCT_VERSION_ZERO_CALLBACK_CONTRACT_REPAIRED_V1
            ),
        )
        self.assertEqual(DiscoveryEmbeddingRecoveryOutcome.RECOVERED, result.outcome)
        self.assertEqual(1, repository.validated)
        self.assertEqual([("recovery-key-0003", 137, NOW)], repository.calls)


if __name__ == "__main__":
    unittest.main()
