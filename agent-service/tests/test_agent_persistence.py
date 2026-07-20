from __future__ import annotations

import unittest
from datetime import UTC, datetime

from prometheus_client import CollectorRegistry

from msb_agent_service.agent_persistence import (
    AgentPersistenceError,
    normalize_question_body,
    request_hash,
)
from msb_agent_service.agent_persistence_metrics import AgentPersistenceMetrics


class AgentPersistenceDomainTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
