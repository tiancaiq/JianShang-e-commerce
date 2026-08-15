from __future__ import annotations

import argparse
import asyncio
import sys
from dataclasses import dataclass
from datetime import UTC, datetime

from .config import DiscoveryEmbeddingSettings, Settings
from .discovery_embedding_jobs import (
    DiscoveryEmbeddingJobRepository,
    DiscoveryEmbeddingRecoveryMode,
    DiscoveryEmbeddingRecoveryOutcome,
    DiscoveryEmbeddingRecoveryResult,
)


@dataclass(frozen=True)
class DiscoveryEmbeddingRecoveryCommandResult:
    outcome: DiscoveryEmbeddingRecoveryOutcome
    expected_count: int
    recovered_count: int


class DiscoveryEmbeddingRecoveryCommand:
    """Runs the local operator-only recovery path behind an independent gate."""

    def __init__(
        self,
        *,
        settings: DiscoveryEmbeddingSettings,
        repository: DiscoveryEmbeddingJobRepository,
        clock=lambda: datetime.now(UTC),
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._clock = clock

    async def run(
        self,
        *,
        recovery_key: str,
        expected_count: int,
        mode: DiscoveryEmbeddingRecoveryMode = (
            DiscoveryEmbeddingRecoveryMode.MAX_ATTEMPTS_EXHAUSTED
        ),
    ) -> DiscoveryEmbeddingRecoveryCommandResult:
        if (
            not self._settings.recovery_enabled
            or self._settings.kill_switch_enabled
        ):
            return DiscoveryEmbeddingRecoveryCommandResult(
                outcome=DiscoveryEmbeddingRecoveryOutcome.DISABLED,
                expected_count=expected_count,
                recovered_count=0,
            )
        await self._repository.validate_recovery_schema()
        if mode == DiscoveryEmbeddingRecoveryMode.MAX_ATTEMPTS_EXHAUSTED:
            result: DiscoveryEmbeddingRecoveryResult = (
                await self._repository.recover_dead_lettered_max_attempts(
                    recovery_key=recovery_key,
                    expected_count=expected_count,
                    recovered_at=self._clock(),
                )
            )
        else:
            result = (
                await self._repository.recover_product_version_zero_callback_contract(
                    recovery_key=recovery_key,
                    expected_count=expected_count,
                    recovered_at=self._clock(),
                )
            )
        return DiscoveryEmbeddingRecoveryCommandResult(
            outcome=result.outcome,
            expected_count=result.expected_count,
            recovered_count=result.recovered_count,
        )


async def _run_cli() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Recover exhausted Agent discovery embedding jobs without replaying "
            "Product events."
        )
    )
    parser.add_argument("--recovery-key", required=True)
    parser.add_argument("--expected-count", required=True, type=int)
    parser.add_argument(
        "--mode",
        choices=[item.value for item in DiscoveryEmbeddingRecoveryMode],
        default=DiscoveryEmbeddingRecoveryMode.MAX_ATTEMPTS_EXHAUSTED.value,
    )
    args = parser.parse_args()
    settings = Settings.from_env()
    repository = await DiscoveryEmbeddingJobRepository.create(
        settings.knowledge_ingestion
    )
    try:
        command = DiscoveryEmbeddingRecoveryCommand(
            settings=settings.discovery_embedding,
            repository=repository,
        )
        result = await command.run(
            recovery_key=args.recovery_key,
            expected_count=args.expected_count,
            mode=DiscoveryEmbeddingRecoveryMode(args.mode),
        )
        sys.stdout.write(
            "outcome={outcome} expectedCount={expected} recoveredCount={recovered}".format(
                outcome=result.outcome.value,
                expected=result.expected_count,
                recovered=result.recovered_count,
            )
            + "\n"
        )
        return 0 if result.outcome in {
            DiscoveryEmbeddingRecoveryOutcome.RECOVERED,
            DiscoveryEmbeddingRecoveryOutcome.REPLAY,
        } else 2
    finally:
        await repository.close()


def main() -> None:
    raise SystemExit(asyncio.run(_run_cli()))


if __name__ == "__main__":
    main()
