from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime
from pathlib import Path

from prometheus_client import CollectorRegistry

from msb_agent_service.agent_persistence import (
    _ALLOWED_TOOLS,
    AgentPersistenceError,
    _fixed_id,
    _safe_error_code,
    _source_refs_json,
    normalize_question_body,
    request_hash,
)
from msb_agent_service.agent_persistence_metrics import AgentPersistenceMetrics


class AgentPersistenceDomainTest(unittest.TestCase):
    def test_discovery_availability_audit_is_allowlisted_for_persistence(self) -> None:
        """Every executable Product discovery tool must persist a safe audit row."""

        self.assertIn("CHECK_AVAILABILITY", _ALLOWED_TOOLS)

    def test_question_normalization_drives_stable_retry_hash(self) -> None:
        self.assertEqual("Is it available?", normalize_question_body("  Is it available? "))
        self.assertEqual(
            request_hash("Is it available?"),
            request_hash("  Is it available? "),
        )

    def test_question_validation_rejects_blank_overflow_and_controls(self) -> None:
        for body in ("   ", "x" * 8_001, "unsafe\u0000body"):
            with self.subTest(body_length=len(body)):
                with self.assertRaises(AgentPersistenceError):
                    normalize_question_body(body)

    def test_metrics_use_only_bounded_labels_and_counts(self) -> None:
        registry = CollectorRegistry()
        metrics = AgentPersistenceMetrics(registry)

        metrics.record_session("create_or_resume", "CREATED")
        metrics.record_invocation("begin", "DEDUPLICATED")
        metrics.record_tool_call("append", "SUCCEEDED")
        metrics.record_retention("messages_deleted", 2)
        metrics.observe_duration("begin_invocation", 0.01)

        rendered = {
            sample.name: sample.value
            for metric in registry.collect()
            for sample in metric.samples
        }
        self.assertEqual(
            1,
            rendered["agent_persistence_session_operations_total"],
        )
        self.assertEqual(
            2,
            rendered["agent_persistence_retention_rows_total"],
        )

    def test_timestamp_fixture_is_timezone_aware(self) -> None:
        self.assertIsNotNone(datetime(2026, 7, 19, tzinfo=UTC).utcoffset())

    def test_source_refs_accept_product_hybrid_provenance_with_opaque_ids(
        self,
    ) -> None:
        refs = [
            {
                "listingId": "81ARZ3NDEKTSV4RRFFQ69G5FAC",
                "listingVersion": 0,
                "finalRank": 1,
                "mode": "VECTOR_ONLY",
                "matchedBy": ["VECTOR"],
                "reasonCode": "VECTOR_MATCH",
                "checkedAt": "2026-07-27T01:00:00+00:00",
            },
            {
                "listingId": "Z1ARZ3NDEKTSV4RRFFQ69G5FAC",
                "listingVersion": 12,
                "finalRank": 2,
                "mode": "HYBRID",
                "matchedBy": ["LEXICAL", "VECTOR"],
                "reasonCode": "LEXICAL_AND_VECTOR_MATCH",
                "checkedAt": "2026-07-27T01:00:00+00:00",
            },
        ]

        self.assertEqual(refs, json.loads(_source_refs_json(refs)))

    def test_source_refs_apply_product_listing_id_contract_only_to_listing_refs(
        self,
    ) -> None:
        refs = [
            {
                "sourceType": "LISTING",
                "sourceId": "Z1ARZ3NDEKTSV4RRFFQ69G5FAC",
                "sourceVersion": "0",
            },
            {
                "sourceType": "MARKETPLACE_POLICY",
                "sourceId": "marketplace-safe-policy-v1",
                "sourceVersion": "12",
            },
        ]

        self.assertEqual(refs, json.loads(_source_refs_json(refs)))

    def test_source_refs_reject_invalid_product_listing_identifiers(self) -> None:
        valid = {
            "listingId": "81ARZ3NDEKTSV4RRFFQ69G5FAC",
            "listingVersion": 0,
            "finalRank": 1,
            "mode": "VECTOR_ONLY",
            "matchedBy": ["VECTOR"],
            "reasonCode": "VECTOR_MATCH",
            "checkedAt": "2026-07-27T01:00:00+00:00",
        }
        invalid_ids = [
            "81ARZ3NDEKTSV4RRFFQ69G5Fac",
            "01ARZ3NDEKTSV4RRFFQ69G5FIC",
            "01ARZ3NDEKTSV4RRFFQ69G5F",
            "01ARZ3NDEKTSV4RRFFQ69G5FAC ",
        ]

        for listing_id in invalid_ids:
            with self.subTest(listing_id=listing_id):
                with self.assertRaises(AgentPersistenceError):
                    _source_refs_json([{**valid, "listingId": listing_id}])

    def test_agent_owned_ids_still_use_the_existing_agent_validator(self) -> None:
        self.assertEqual(
            "01ARZ3NDEKTSV4RRFFQ69G5FAC",
            _fixed_id("invocation_id", "01ARZ3NDEKTSV4RRFFQ69G5FAC"),
        )
        with self.assertRaises(AgentPersistenceError):
            _fixed_id("invocation_id", "01ARZ3NDEKTSV4RRFFQ69G5FaC")

    def test_safe_failure_codes_preserve_stage_kind_categories(self) -> None:
        long_code = (
            "DISCOVERY_ORCHESTRATOR_RUN_FAILED_"
            "TOOL_EXECUTION_PRODUCT_HYBRID_RESPONSE_VALIDATION"
        )

        self.assertEqual(long_code, _safe_error_code(long_code))
        with self.assertRaises(AgentPersistenceError):
            _safe_error_code("DISCOVERY failure")
        with self.assertRaises(AgentPersistenceError):
            _safe_error_code("A" * 121)

    def test_v12_migration_widens_invocation_and_tool_failure_codes(self) -> None:
        migration = (
            Path(__file__).parents[1]
            / "db"
            / "migration"
            / "V12__expand_agent_invocation_failure_code_width.sql"
        ).read_text(encoding="utf-8")

        self.assertIn("agent_invocations", migration)
        self.assertIn("agent_tool_calls", migration)
        self.assertIn("VARCHAR(120)", migration)
        self.assertNotIn("DROP TABLE", migration.upper())
        self.assertNotIn("TRUNCATE", migration.upper())

    def test_v16_migration_adds_only_the_availability_audit_tool(self) -> None:
        migration = (
            Path(__file__).parents[1]
            / "db"
            / "migration"
            / "V16__allow_discovery_availability_tool_audit.sql"
        ).read_text(encoding="utf-8")

        for tool_name in (
            "getListing",
            "retrieveKnowledge",
            "CHECK_AVAILABILITY",
            "SEARCH_INDIVIDUAL",
            "GET_LISTING",
        ):
            self.assertIn(f"'{tool_name}'", migration)
        self.assertNotIn("DROP TABLE", migration.upper())
        self.assertNotIn("TRUNCATE", migration.upper())

    def test_v18_forward_adds_the_v2_availability_audit_tool(self) -> None:
        migration = (
            Path(__file__).parents[1]
            / "db"
            / "migration"
            / "V18__allow_marketplace_agent_v2_availability_tool_audit.sql"
        ).read_text(encoding="utf-8")

        for tool_name in (
            "getListing", "retrieveKnowledge", "CHECK_AVAILABILITY",
            "SEARCH_INDIVIDUAL", "GET_LISTING", "check_availability",
            "search_listings", "get_listing",
        ):
            self.assertIn(f"'{tool_name}'", migration)
        self.assertNotIn("DROP TABLE", migration.upper())
        self.assertNotIn("TRUNCATE", migration.upper())


if __name__ == "__main__":
    unittest.main()
