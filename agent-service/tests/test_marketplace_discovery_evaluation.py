from __future__ import annotations

import asyncio
import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import timedelta
from decimal import Decimal

from pydantic import ValidationError

from msb_agent_service.marketplace_discovery_evaluation import (
    DEFAULT_FIXTURE,
    FIXTURE_VERSION,
    GATE_SCHEMA_VERSION,
    REPORT_SCHEMA_VERSION,
    RUNNER_VERSION,
    DiscoveryOfflineFixture,
    DiscoveryOfflineReport,
    evidence_freshness_reasons,
    evaluate_release_gate,
    load_offline_fixture,
    main,
    report_sha256,
    run_offline_evaluation,
)


class MarketplaceDiscoveryEvaluationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = load_offline_fixture()

    def run_report(self) -> DiscoveryOfflineReport:
        return asyncio.run(run_offline_evaluation(self.fixture))

    def test_fixture_is_complete_unique_default_off_and_fake_only(self) -> None:
        self.assertEqual(FIXTURE_VERSION, self.fixture.fixture_version)
        self.assertEqual(14, len(self.fixture.cases))
        self.assertTrue(self.fixture.runtime_switches.global_kill_switch_engaged)
        switches = self.fixture.runtime_switches.model_dump()
        self.assertFalse(any(value for key, value in switches.items() if key != "global_kill_switch_engaged"))
        self.assertEqual(
            {"UNKNOWN"},
            set(self.fixture.production_evidence.model_dump().values()),
        )
        self.assertEqual(
            {"UNKNOWN"},
            set(self.fixture.approvals.model_dump().values()),
        )

    def test_report_passes_every_provisional_threshold_but_release_is_blocked(
        self,
    ) -> None:
        report = self.run_report()

        self.assertEqual(REPORT_SCHEMA_VERSION, report.schema_version)
        self.assertEqual(RUNNER_VERSION, report.runner_version)
        self.assertEqual("PASS", report.baseline_status)
        self.assertEqual((), report.baseline_failure_reasons)
        self.assertTrue(all(item.passed for item in report.thresholds))
        isolation = report.metrics["actor_listing_isolation"]
        self.assertGreater(isolation.denominator, 0)
        self.assertEqual(isolation.numerator, isolation.denominator)
        exact = {
            item.metric: item
            for item in report.thresholds
            if item.comparator == "EQ"
        }
        self.assertTrue(exact)
        self.assertTrue(
            all(item.threshold == Decimal("1.0000") for item in exact.values())
        )
        ratios = {item.metric: item for item in report.thresholds}
        self.assertEqual(
            Decimal("0.9500"),
            ratios["grounded_reason_ratio"].threshold,
        )
        self.assertEqual(
            Decimal("0.9000"),
            ratios["clarification_quality"].threshold,
        )
        self.assertEqual("BLOCKED", report.release_gate.decision)
        self.assertFalse(report.release_gate.release_authorized)
        self.assertEqual(GATE_SCHEMA_VERSION, report.release_gate.schema_version)
        self.assertIn(
            "GLOBAL_KILL_SWITCH_ENGAGED",
            report.release_gate.blockers,
        )
        self.assertIn(
            "PRODUCTION_LATENCY_EVIDENCE_UNKNOWN",
            report.release_gate.blockers,
        )

    def test_report_covers_all_outcomes_and_fake_failure_classes(self) -> None:
        report = self.run_report()
        outcomes = {
            outcome.value
            for item in report.case_results
            for outcome in item.turn_outcomes
        }
        self.assertTrue(
            {
                "ASK_CLARIFY",
                "RECOMMEND",
                "COMPARE",
                "NO_RESULTS",
                "REFUSE",
                "HANDOFF",
                "UNAVAILABLE",
                "TIMED_OUT",
                "CANCELLED",
                "REJECTED",
                "LIMIT_REJECTED",
                "REPLAYED",
            }.issubset(outcomes)
        )
        self.assertTrue(all(item.passed for item in report.case_results))
        self.assertTrue(
            all(
                item.call_counts.fake_model <= 7
                and item.call_counts.fake_search <= 2
                and item.call_counts.fake_detail <= 5
                and item.call_counts.fake_search + item.call_counts.fake_detail <= 6
                for item in report.case_results
            )
        )

    def test_usage_is_zero_cost_and_latency_is_explicitly_non_production(
        self,
    ) -> None:
        report = self.run_report()

        self.assertEqual(0, report.usage.provider_requests)
        self.assertEqual(0, report.usage.input_tokens)
        self.assertEqual(0, report.usage.output_tokens)
        self.assertEqual(Decimal("0"), report.usage.estimated_cost)
        self.assertEqual("UNPRICED", report.usage.pricing_status)
        self.assertFalse(report.usage.production_evidence_eligible)
        self.assertEqual(
            "NON_PRODUCTION_SIMULATED",
            report.simulated_latency.classification,
        )
        self.assertLessEqual(report.simulated_latency.p95_ms, 12_000)
        self.assertFalse(report.simulated_latency.production_slo_eligible)

    def test_observability_names_are_fixed_and_have_no_identity_labels(self) -> None:
        report = self.run_report()
        names = [item.name for item in report.observability]

        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(names, sorted(names))
        rendered = json.dumps(
            [item.model_dump(mode="json", by_alias=True) for item in report.observability]
        ).casefold()
        for forbidden in (
            "actor_id",
            "actoruserid",
            "listing_id",
            "listingid",
            "session_id",
            "request_id",
            "correlation_id",
            "prompt",
            "chain-of-thought",
        ):
            self.assertNotIn(forbidden, rendered)

    def test_report_and_fixture_digests_are_reproducible(self) -> None:
        first = self.run_report()
        second = self.run_report()

        self.assertEqual(first.fixture_sha256, second.fixture_sha256)
        self.assertEqual(report_sha256(first), report_sha256(second))
        self.assertEqual(
            first.model_dump(mode="json", by_alias=True),
            second.model_dump(mode="json", by_alias=True),
        )

    def test_report_rejects_tampering_and_high_cardinality_metric_drift(self) -> None:
        report = self.run_report()
        payload = report.model_dump(mode="json", by_alias=True)
        payload["caseResults"][0]["recommendationCount"] = 5
        with self.assertRaisesRegex(ValidationError, "reportSha256"):
            DiscoveryOfflineReport.model_validate(payload)

        payload = report.model_dump(mode="json", by_alias=True)
        payload["observability"][0]["name"] = "discovery_actor_123"
        with self.assertRaisesRegex(ValidationError, "observability"):
            DiscoveryOfflineReport.model_validate(payload)

    def test_nonzero_provider_usage_and_production_latency_claim_are_rejected(
        self,
    ) -> None:
        report = self.run_report()
        payload = report.model_dump(mode="json", by_alias=True)
        payload["usage"]["providerRequests"] = 1
        with self.assertRaises(ValidationError):
            DiscoveryOfflineReport.model_validate(payload)

        payload = report.model_dump(mode="json", by_alias=True)
        payload["simulatedLatency"]["classification"] = "PRODUCTION_MEASURED"
        with self.assertRaises(ValidationError):
            DiscoveryOfflineReport.model_validate(payload)

    def test_missing_duplicate_or_incomplete_fixture_coverage_is_rejected(
        self,
    ) -> None:
        payload = self.fixture.model_dump(mode="json", by_alias=True)
        payload["cases"] = payload["cases"][:-1]
        with self.assertRaisesRegex(ValidationError, "every approved"):
            DiscoveryOfflineFixture.model_validate(payload)

        payload = self.fixture.model_dump(mode="json", by_alias=True)
        payload["cases"][1]["id"] = payload["cases"][0]["id"]
        with self.assertRaisesRegex(ValidationError, "unique"):
            DiscoveryOfflineFixture.model_validate(payload)

    def test_call_budget_regression_causes_offline_fail_without_authorizing_release(
        self,
    ) -> None:
        payload = self.fixture.model_dump(mode="json", by_alias=True)
        case = next(
            item
            for item in payload["cases"]
            if item["scenario"] == "MEDICAL_BOUNDARY"
        )
        case["expectedModelCalls"] = 1
        mutated = DiscoveryOfflineFixture.model_validate(payload)
        report = asyncio.run(run_offline_evaluation(mutated))

        self.assertEqual("FAIL", report.baseline_status)
        self.assertIn(
            "THRESHOLD_FAILED:allowlisted_tool_boundary",
            report.baseline_failure_reasons,
        )
        self.assertEqual("BLOCKED", report.release_gate.decision)
        self.assertFalse(report.release_gate.release_authorized)

    def test_future_and_stale_evidence_are_classified_deterministically(self) -> None:
        report = self.run_report()
        self.assertEqual(
            ("REPORT_FUTURE_DATED",),
            evidence_freshness_reasons(
                report,
                decision_at=report.generated_at - timedelta(seconds=1),
            ),
        )
        self.assertEqual(
            ("REPORT_STALE",),
            evidence_freshness_reasons(
                report,
                decision_at=report.valid_until + timedelta(seconds=1),
            ),
        )
        self.assertEqual(
            (),
            evidence_freshness_reasons(
                report,
                decision_at=report.generated_at,
            ),
        )
        future_gate = evaluate_release_gate(
            fixture=self.fixture,
            evaluated_at=report.generated_at - timedelta(seconds=1),
        )
        self.assertEqual("FUTURE", future_gate.evidence_freshness)
        self.assertIn("REPORT_FUTURE_DATED", future_gate.blockers)
        stale_gate = evaluate_release_gate(
            fixture=self.fixture,
            evaluated_at=report.valid_until + timedelta(seconds=1),
        )
        self.assertEqual("STALE", stale_gate.evidence_freshness)
        self.assertIn("REPORT_STALE", stale_gate.blockers)
        self.assertFalse(future_gate.release_authorized)
        self.assertFalse(stale_gate.release_authorized)

    def test_cli_report_schema_and_digest_are_machine_readable(self) -> None:
        output = io.StringIO()
        with redirect_stdout(output):
            status = main(["--fixture", str(DEFAULT_FIXTURE), "--digest"])
        self.assertEqual(0, status)
        digest = json.loads(output.getvalue())
        self.assertRegex(digest["reportSha256"], r"^[0-9a-f]{64}$")

        output = io.StringIO()
        with redirect_stdout(output):
            status = main(["--schema", "gate"])
        self.assertEqual(0, status)
        schema = json.loads(output.getvalue())
        self.assertEqual("DiscoveryReleaseGate", schema["title"])


if __name__ == "__main__":
    unittest.main()
