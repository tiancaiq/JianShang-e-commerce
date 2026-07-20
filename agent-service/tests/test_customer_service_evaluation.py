from __future__ import annotations

import asyncio
import logging
import unittest
from decimal import Decimal

from pydantic import ValidationError

from msb_agent_service.customer_service_evaluation import (
    OfflineClaim,
    OfflineEvaluationFixture,
    OfflineEvaluationReport,
    ReleaseSwitches,
    ThresholdResult,
    _json,
    load_offline_fixture,
    run_offline_evaluation,
)


def _replace_case(
    fixture: OfflineEvaluationFixture,
    case_id: str,
    **updates: object,
) -> OfflineEvaluationFixture:
    cases = tuple(
        case.model_copy(update=updates) if case.id == case_id else case
        for case in fixture.cases
    )
    return OfflineEvaluationFixture.model_validate(
        {**fixture.model_dump(by_alias=True), "cases": cases}
    )


class OfflineEvaluationSchemaTest(unittest.TestCase):
    def test_fixture_schema_is_strict_and_requires_default_off_switches(self) -> None:
        fixture = load_offline_fixture()
        payload = fixture.model_dump(by_alias=True)
        payload["unexpected"] = True
        with self.assertRaises(ValidationError):
            OfflineEvaluationFixture.model_validate(payload)

        for switch in ReleaseSwitches.model_fields:
            with self.subTest(switch=switch), self.assertRaises(ValidationError):
                OfflineEvaluationFixture.model_validate(
                    {
                        **fixture.model_dump(by_alias=True),
                        "releaseSwitches": {
                            **fixture.release_switches.model_dump(),
                            switch: True,
                        },
                    }
                )

    def test_report_schema_is_machine_readable_and_strict(self) -> None:
        schema = OfflineEvaluationReport.model_json_schema(by_alias=True)
        self.assertIn("schemaVersion", schema["properties"])
        self.assertEqual(False, schema["additionalProperties"])
        self.assertIn("$defs", schema)

    def test_metric_schema_rejects_inconsistent_ratios(self) -> None:
        with self.assertRaises(ValidationError):
            OfflineEvaluationReport.model_validate(
                {
                    "schemaVersion": "ai-cs-offline-evaluation-report-v1",
                    "runnerVersion": "v1",
                    "fixtureVersion": "v1",
                    "seed": 1,
                    "promptVersion": "v1",
                    "toolRegistryVersion": "v1",
                    "policyVersion": "v1",
                    "caseCount": 1,
                    "caseResults": [],
                    "metrics": {
                        "broken": {
                            "value": "1.0000",
                            "numerator": 0,
                            "denominator": 1,
                        }
                    },
                    "simulatedLatencyP95Ms": 0,
                    "thresholds": [],
                    "baselineStatus": "FAIL",
                    "baselineFailureReasons": ["BROKEN"],
                    "releaseDecision": "BLOCKED",
                    "releaseBlockers": ["DEFAULT_OFF"],
                    "releaseSwitches": ReleaseSwitches().model_dump(by_alias=True),
                    "cost": {},
                }
            )

    def test_every_provisional_threshold_has_a_failing_boundary(self) -> None:
        fixture = load_offline_fixture()
        report = asyncio.run(run_offline_evaluation(fixture))
        for threshold in report.thresholds:
            with self.subTest(metric=threshold.metric):
                if threshold.comparator == "LE":
                    failing_actual = threshold.threshold + Decimal("1")
                else:
                    failing_actual = max(
                        Decimal("0"),
                        threshold.threshold - Decimal("0.0001"),
                    )
                failed = ThresholdResult(
                    metric=threshold.metric,
                    comparator=threshold.comparator,
                    threshold=threshold.threshold,
                    actual=failing_actual,
                    provisional=True,
                    passed=False,
                )
                self.assertFalse(failed.passed)

    def test_report_rejects_duplicate_thresholds_and_drifted_failure_reasons(
        self,
    ) -> None:
        report = asyncio.run(run_offline_evaluation(load_offline_fixture()))
        duplicate = report.model_dump(mode="json", by_alias=True)
        duplicate["thresholds"].append(duplicate["thresholds"][0])
        with self.assertRaises(ValidationError):
            OfflineEvaluationReport.model_validate(duplicate)

        drifted = report.model_dump(mode="json", by_alias=True)
        drifted["baselineStatus"] = "FAIL"
        drifted["baselineFailureReasons"] = ["CASE_FAILED:not-in-report"]
        with self.assertRaises(ValidationError):
            OfflineEvaluationReport.model_validate(drifted)


class OfflineEvaluationRunnerTest(unittest.IsolatedAsyncioTestCase):
    async def test_baseline_is_deterministic_zero_cost_and_release_blocked(self) -> None:
        fixture = load_offline_fixture()
        first = await run_offline_evaluation(fixture)
        second = await run_offline_evaluation(fixture)

        self.assertEqual(_json(first), _json(second))
        self.assertEqual("PASS", first.baseline_status)
        self.assertEqual("BLOCKED", first.release_decision)
        self.assertFalse(any(first.release_switches.model_dump().values()))
        self.assertEqual(0, first.cost.provider_requests)
        self.assertEqual(0, first.cost.input_tokens)
        self.assertEqual(0, first.cost.output_tokens)
        self.assertEqual(Decimal("0"), first.cost.estimated_cost)
        self.assertFalse(first.cost.pricing_approved)
        self.assertTrue(all(threshold.provisional for threshold in first.thresholds))
        self.assertTrue(all(threshold.passed for threshold in first.thresholds))

    async def test_retrieval_relevance_and_recall_thresholds_fail_on_missing_hit(
        self,
    ) -> None:
        fixture = _replace_case(
            load_offline_fixture(),
            "grounded-current-description",
            expected_relevant_chunk_ids=("missing-current-chunk",),
        )
        report = await run_offline_evaluation(fixture)
        failed = {item.metric for item in report.thresholds if not item.passed}

        self.assertEqual("FAIL", report.baseline_status)
        self.assertIn("retrieval_relevance", failed)
        self.assertIn("retrieval_recall", failed)

    async def test_faithfulness_threshold_fails_on_unsupported_annotated_claim(
        self,
    ) -> None:
        fixture = _replace_case(
            load_offline_fixture(),
            "grounded-current-description",
            claims=(
                OfflineClaim(
                    answerText="chain was recently replaced",
                    supportingSourceIds=("current-chain",),
                ),
                OfflineClaim(
                    answerText="includes a helmet",
                    supportingSourceIds=("current-chain",),
                ),
            ),
        )
        report = await run_offline_evaluation(fixture)
        failed = {item.metric for item in report.thresholds if not item.passed}

        self.assertIn("answer_faithfulness", failed)
        self.assertIn(
            "ANNOTATED_CLAIM_UNSUPPORTED",
            report.case_results[0].failure_reasons,
        )

    async def test_citation_thresholds_fail_when_grounded_answer_has_no_source(
        self,
    ) -> None:
        fixture = load_offline_fixture()
        case = fixture.cases[0]
        assert case.model_answer is not None
        fixture = _replace_case(
            fixture,
            case.id,
            model_answer=case.model_answer.model_copy(
                update={"source_id": None, "source_version": None}
            ),
        )
        report = await run_offline_evaluation(fixture)
        failed = {item.metric for item in report.thresholds if not item.passed}

        self.assertIn("citation_validity", failed)
        self.assertIn("citation_completeness", failed)

    async def test_stale_deleted_cross_listing_and_actor_boundaries_pass(self) -> None:
        report = await run_offline_evaluation(load_offline_fixture())
        safe_cases = {
            case.case_id: case
            for case in report.case_results
            if case.case_id
            in {
                "stale-version-rejected",
                "deleted-source-rejected",
                "cross-listing-source-rejected",
            }
        }

        self.assertEqual(3, len(safe_cases))
        self.assertTrue(all(case.passed for case in safe_cases.values()))
        self.assertEqual(
            Decimal("1.0000"),
            report.metrics["rejected_source_safety"].value,
        )
        self.assertEqual(
            Decimal("1.0000"),
            report.metrics["cross_user_isolation"].value,
        )
        self.assertNotIn(
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            _json(report),
        )

    async def test_fake_dependency_failures_are_outage_safe(self) -> None:
        report = await run_offline_evaluation(load_offline_fixture())
        failure_cases = [
            case
            for case in report.case_results
            if case.error_code is not None
        ]

        self.assertEqual(6, len(failure_cases))
        self.assertTrue(all(case.passed for case in failure_cases))
        self.assertTrue(
            all(
                case.error_code == "AGENT_ORCHESTRATION_UNAVAILABLE"
                for case in failure_cases
            )
        )
        self.assertEqual(
            Decimal("1.0000"),
            report.metrics["failure_handling"].value,
        )

    async def test_simulated_latency_gate_fails_without_claiming_production_slo(
        self,
    ) -> None:
        fixture = _replace_case(
            load_offline_fixture(),
            "grounded-current-description",
            simulated_latency_ms=60_000,
        )
        report = await run_offline_evaluation(fixture)
        latency = next(
            threshold
            for threshold in report.thresholds
            if threshold.metric == "simulated_latency_p95_ms"
        )

        self.assertFalse(latency.passed)
        self.assertTrue(latency.provisional)
        self.assertIn("PRODUCTION_LATENCY_NOT_EVALUATED", report.release_blockers)

    async def test_report_and_logs_exclude_fixture_content_and_sensitive_markers(
        self,
    ) -> None:
        fixture = load_offline_fixture()
        with self.assertLogs(level=logging.WARNING) as captured:
            report = await run_offline_evaluation(fixture)
        combined = "\n".join(captured.output) + _json(report)

        self.assertNotIn("Ignore previous instructions", combined)
        self.assertNotIn("recently replaced chain", combined)
        self.assertNotIn("sk-proj-", combined)
        self.assertNotIn("Bearer ", combined)


if __name__ == "__main__":
    unittest.main()
