from __future__ import annotations

import unittest
from dataclasses import replace
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock

from msb_agent_service.config import (
    KnowledgeIndexSettings,
    KnowledgeIngestionSettings,
    Settings,
)
from msb_agent_service.knowledge_index import (
    KnowledgeIndexStatus,
    KnowledgeReadinessStatus,
)
from msb_agent_service.knowledge_operations import (
    KnowledgeRebuildRun,
    RebuildStatus,
)
from msb_agent_service.knowledge_rebuild import (
    KnowledgeRebuildService,
    RebuildGateError,
)
from msb_agent_service.knowledge_source_client import (
    ListingKnowledgeExportPage,
    ListingKnowledgeSource,
)

RUN_ID = "01R00000000000000000000001"
LISTING_ID = "01L00000000000000000000001"
READ_INDEX = "msb-agent-knowledge-v0001-000001"
WRITE_INDEX = "msb-agent-knowledge-v0001-000002"
NOW = datetime(2026, 7, 19, 3, 0, tzinfo=UTC)


def runtime_settings() -> Settings:
    return Settings(
        openai_api_key="configured",
        knowledge=KnowledgeIndexSettings(
            enabled=True,
            url="http://localhost:9200",
            embedding_provider="openai",
            embedding_model="text-embedding-3-small",
            embedding_dimensions=1536,
        ),
        knowledge_ingestion=KnowledgeIngestionSettings(
            enabled=True,
            mysql_password="secret",
            product_service_url="http://product-service:8091",
            product_service_token="secret",
        ),
    )


def rebuild_run(
    *,
    complete: bool = False,
    processed: int = 0,
    expected: int = 0,
) -> KnowledgeRebuildRun:
    return KnowledgeRebuildRun(
        run_id=RUN_ID,
        target_generation=WRITE_INDEX,
        previous_read_generation=READ_INDEX,
        export_cursor=None,
        export_watermark=NOW if complete else None,
        export_complete=complete,
        expected_count=expected,
        processed_count=processed,
        failed_count=0,
        skipped_count=0,
        tombstoned_count=0,
        status=RebuildStatus.RUNNING,
        initiated_by="operator@example.test",
    )


def source() -> ListingKnowledgeSource:
    return ListingKnowledgeSource.model_validate(
        {
            "sourceType": "LISTING",
            "sourceId": LISTING_ID,
            "sourceVersion": "2",
            "supersedesVersion": "1",
            "lifecycle": "ACTIVE",
            "visibility": "PUBLIC",
            "language": "und",
            "effectiveFrom": "2026-07-19T01:00:00Z",
            "sourcePublishedAt": "2026-07-19T01:00:00Z",
            "contentHash": "a" * 64,
            "content": {
                "title": "Desk",
                "description": "Approved public description.",
                "price": {"amount": "10.00", "currency": "USD"},
                "publicLocation": {"city": "Irvine", "region": "CA"},
            },
        }
    )


class FakeOperations:
    def __init__(self, run: KnowledgeRebuildRun) -> None:
        self.run = run
        self.ready_calls = 0

    async def get_rebuild(self, run_id: str) -> KnowledgeRebuildRun | None:
        return self.run if run_id == self.run.run_id else None

    async def checkpoint_rebuild_page(self, run_id: str, **values: object) -> None:
        self.run = replace(
            self.run,
            export_cursor=values["next_cursor"],  # type: ignore[arg-type]
            export_watermark=values["export_watermark"],  # type: ignore[arg-type]
            export_complete=bool(values["complete"]),
            expected_count=self.run.expected_count + int(values["expected_delta"]),
            processed_count=self.run.processed_count + int(values["processed_delta"]),
            skipped_count=self.run.skipped_count + int(values["skipped_delta"]),
            tombstoned_count=(
                self.run.tombstoned_count + int(values["tombstoned_delta"])
            ),
        )

    async def mark_rebuild_failed(self, *args: object) -> None:
        self.run = replace(self.run, status=RebuildStatus.FAILED, failed_count=1)

    async def mark_ready_to_promote(self, *args: object) -> None:
        self.ready_calls += 1
        self.run = replace(self.run, status=RebuildStatus.READY_TO_PROMOTE)

    async def deletion_counts(self) -> dict[str, int]:
        return {}


class FakeJobs:
    def __init__(self, states: list[object | None] | None = None) -> None:
        self.states = states or []

    async def get_source_state(self, *args: object) -> object | None:
        return self.states.pop(0) if self.states else None

    async def job_counts(self) -> dict[str, int]:
        return {"SUCCEEDED": 1}

    async def oldest_pending_age_seconds(self, now: datetime) -> float:
        return 0.0


class FakeWriter:
    def __init__(self, source_count: int = 1, document_count: int = 2) -> None:
        self.upserts = 0
        self.deletes = 0
        self._source_count = source_count
        self._document_count = document_count

    async def upsert(self, documents: object) -> None:
        self.upserts += 1

    async def delete_source(self, *args: object) -> int:
        self.deletes += 1
        return 1

    async def refresh(self) -> None:
        return None

    async def source_count(self) -> int:
        return self._source_count

    async def count(self) -> int:
        return self._document_count


class FakeAdmin:
    async def status(self) -> KnowledgeIndexStatus:
        return KnowledgeIndexStatus(
            KnowledgeReadinessStatus.READY,
            READ_INDEX,
            WRITE_INDEX,
            2,
            "2.15.0",
        )


class KnowledgeRebuildServiceTest(unittest.IsolatedAsyncioTestCase):
    def service(
        self,
        run: KnowledgeRebuildRun,
        *,
        jobs: FakeJobs | None = None,
        writer: FakeWriter | None = None,
        lag: int = 0,
        category_retrieval: bool = False,
    ) -> tuple[KnowledgeRebuildService, FakeOperations, FakeWriter]:
        operations = FakeOperations(run)
        writer = writer or FakeWriter()
        page = ListingKnowledgeExportPage(
            items=[source()],
            nextCursor=None,
            hasMore=False,
            exportWatermark=NOW,
        )
        source_client = SimpleNamespace(
            fetch_listing_export=AsyncMock(
                return_value=(page, 0.01)
            )
        )
        builder = SimpleNamespace(
            build=AsyncMock(
                return_value=("a" * 64, object(), NOW, (object(), object()))
            )
        )
        settings = runtime_settings()
        if category_retrieval:
            settings = replace(
                settings,
                knowledge_ingestion=replace(
                    settings.knowledge_ingestion,
                    category_guidance_retrieval_enabled=True,
                ),
            )
        service = KnowledgeRebuildService(
            settings=settings,
            jobs=jobs or FakeJobs(),  # type: ignore[arg-type]
            operations=operations,  # type: ignore[arg-type]
            source_client=source_client,
            builder=builder,
            index_admin=FakeAdmin(),  # type: ignore[arg-type]
            exact_writer_factory=lambda target: writer,  # type: ignore[arg-type]
            lag_reader=AsyncMock(return_value=lag),
            clock=lambda: NOW,
        )
        return service, operations, writer

    async def test_concurrent_tombstone_removes_just_loaded_exact_version(
        self,
    ) -> None:
        tombstone = SimpleNamespace(
            latest_observed_version=3,
            state="TOMBSTONED",
        )
        service, operations, writer = self.service(
            rebuild_run(),
            jobs=FakeJobs([None, tombstone]),
        )

        result = await service.start_or_resume(
            operator="operator@example.test",
            run_id=RUN_ID,
        )

        self.assertTrue(result.export_complete)
        self.assertEqual(1, result.tombstoned_count)
        self.assertEqual(1, writer.upserts)
        self.assertEqual(1, writer.deletes)
        self.assertEqual(RebuildStatus.RUNNING, operations.run.status)

    async def test_validation_marks_ready_only_after_all_gates_pass(self) -> None:
        service, operations, _ = self.service(
            rebuild_run(complete=True, processed=1, expected=1)
        )

        result = await service.validate(RUN_ID)

        self.assertEqual("READY_TO_PROMOTE", result["status"])
        self.assertEqual(1, operations.ready_calls)
        self.assertEqual(
            RebuildStatus.READY_TO_PROMOTE,
            operations.run.status,
        )

    async def test_validation_blocks_nonzero_kafka_lag(self) -> None:
        service, operations, _ = self.service(
            rebuild_run(complete=True, processed=1, expected=1),
            lag=1,
        )

        with self.assertRaises(RebuildGateError) as captured:
            await service.validate(RUN_ID)

        self.assertEqual("KAFKA_LAG_NONZERO", captured.exception.code)
        self.assertEqual(0, operations.ready_calls)

    async def test_listing_only_promotion_is_blocked_after_category_enablement(
        self,
    ) -> None:
        service, operations, _ = self.service(
            rebuild_run(complete=True, processed=1, expected=1),
            category_retrieval=True,
        )

        with self.assertRaises(RebuildGateError) as captured:
            await service.validate(RUN_ID)

        self.assertEqual(
            "REBUILD_SOURCE_SET_INCOMPLETE",
            captured.exception.code,
        )
        self.assertEqual(0, operations.ready_calls)


if __name__ == "__main__":
    unittest.main()
