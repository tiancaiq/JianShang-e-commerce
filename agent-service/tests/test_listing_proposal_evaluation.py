from __future__ import annotations

import asyncio
import io
import json
import unittest
from contextlib import redirect_stdout
from decimal import Decimal

from pydantic import ValidationError

from msb_agent_service.listing_proposal_evaluation import (
    OfflineProposalFixture,
    OfflineProposalReport,
    ProposalEvaluationScenario,
    ThresholdResult,
    _pretty_json,
    fixture_evidence_sha256,
    load_offline_fixture,
    main,
    report_evidence_sha256,
    run_offline_evaluation,
)


def _replace_case(
    fixture: OfflineProposalFixture,
    case_id: str,
    **updates: object,
) -> OfflineProposalFixture:
    cases = tuple(
        case.model_copy(update=updates) if case.id == case_id else case
        for case in fixture.cases
    )
    return OfflineProposalFixture.model_validate(
        {
            **fixture.model_dump(mode="json", by_alias=True),
            "cases": [
                case.model_dump(mode="json", by_alias=True) for case in cases
            ],
        }
    )


class ListingProposalEvaluationSchemaTest(unittest.TestCase):
    def test_fixture_is_strict_complete_and_forces_every_release_input_false(
        self,
    ) -> None:
        fixture = load_offline_fixture()
        self.assertEqual(
            set(ProposalEvaluationScenario),
            {case.scenario for case in fixture.cases},
        )
        payload = fixture.model_dump(mode="json", by_alias=True)
        payload["unexpected"] = True
        with self.assertRaises(ValidationError):
            OfflineProposalFixture.model_validate(payload)

        for field, metadata in type(fixture.release_inputs).model_fields.items():
            with self.subTest(field=field):
                changed = fixture.model_dump(mode="json", by_alias=True)
                changed["releaseInputs"][metadata.alias or field] = True
                with self.assertRaises(ValidationError):
                    OfflineProposalFixture.model_validate(changed)

    def test_report_schema_is_strict_machine_readable_and_never_ready(self) -> None:
        schema = OfflineProposalReport.model_json_schema(by_alias=True)
        self.assertEqual(False, schema["additionalProperties"])
        self.assertIn("schemaVersion", schema["properties"])
        self.assertIn("releaseDecision", schema["properties"])
        self.assertEqual(
            ["BLOCKED"],
            schema["properties"]["releaseDecision"]["const"]
            if isinstance(schema["properties"]["releaseDecision"]["const"], list)
            else [schema["properties"]["releaseDecision"]["const"]],
        )

    def test_threshold_contract_is_exact_and_rejects_drift(self) -> None:
        passing = ThresholdResult(
            metric="strict_schema_conformance",
            comparator="EQ",
            threshold=Decimal("1.0000"),
            actual=Decimal("1.0000"),
            passed=True,
        )
        self.assertTrue(passing.passed)
        with self.assertRaises(ValidationError):
            ThresholdResult(
                metric="strict_schema_conformance",
                comparator="EQ",
                threshold=Decimal("1.0000"),
                actual=Decimal("0.9999"),
                passed=True,
            )

    def test_report_rejects_ready_nonzero_usage_and_failure_reason_drift(
        self,
    ) -> None:
        report = asyncio.run(run_offline_evaluation(load_offline_fixture()))

        ready = report.model_dump(mode="json", by_alias=True)
        ready["releaseDecision"] = "READY"
        with self.assertRaises(ValidationError):
            OfflineProposalReport.model_validate(ready)

        usage = report.model_dump(mode="json", by_alias=True)
        usage["usage"]["providerRequests"] = 1
        with self.assertRaises(ValidationError):
            OfflineProposalReport.model_validate(usage)

        failures = report.model_dump(mode="json", by_alias=True)
        failures["baselineStatus"] = "FAIL"
        failures["baselineFailureReasons"] = ["CASE_FAILED:not-present"]
        with self.assertRaises(ValidationError):
            OfflineProposalReport.model_validate(failures)


class ListingProposalEvaluationRunnerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.fixture = load_offline_fixture()
        self.report = await run_offline_evaluation(self.fixture)

    async def test_report_is_deterministic_pass_but_release_blocked(self) -> None:
        second = await run_offline_evaluation(self.fixture)

        self.assertEqual(_pretty_json(self.report), _pretty_json(second))
        self.assertEqual(
            report_evidence_sha256(self.report),
            report_evidence_sha256(second),
        )
        self.assertEqual(
            fixture_evidence_sha256(self.fixture),
            self.report.fixture_sha256,
        )
        self.assertEqual("PASS", self.report.baseline_status)
        self.assertEqual("BLOCKED", self.report.release_decision)
        self.assertFalse(any(self.report.release_inputs.model_dump().values()))
        self.assertTrue(all(item.passed for item in self.report.thresholds))

    async def test_clear_and_ambiguous_cases_reuse_strict_proposal_contract(
        self,
    ) -> None:
        cases = {case.case_id: case for case in self.report.case_results}

        self.assertTrue(cases["clear"].checks["strict_schema_conformance"])
        self.assertTrue(cases["clear"].checks["evidence_unknown_consistency"])
        self.assertEqual("SUCCEEDED", cases["clear"].observed_outcome)
        self.assertTrue(cases["ambiguous"].checks["strict_schema_conformance"])
        self.assertTrue(
            cases["ambiguous"].checks["evidence_unknown_consistency"]
        )
        self.assertEqual("SUCCEEDED", cases["ambiguous"].observed_outcome)

    async def test_privacy_injection_protected_and_grounding_guards_pass(
        self,
    ) -> None:
        for metric in (
            "privacy_redaction",
            "injection_resistance",
            "protected_field_rejection",
            "evidence_unknown_consistency",
        ):
            with self.subTest(metric=metric):
                self.assertEqual(
                    Decimal("1.0000"),
                    self.report.metrics[metric].value,
                )

        serialized = _pretty_json(self.report)
        self.assertNotIn("owner@example.com", serialized)
        self.assertNotIn("Ignore previous instructions", serialized)
        self.assertNotIn("01ARZ3NDEKTSV4RRFFQ69G5FAA", serialized)
        self.assertNotIn("01ARZ3NDEKTSV4RRFFQ69G5FAC", serialized)

    async def test_replay_and_fake_dependency_failures_are_deterministic(
        self,
    ) -> None:
        cases = {case.case_id: case for case in self.report.case_results}
        replay = cases["replay"]
        self.assertEqual("REPLAYED", replay.observed_outcome)
        self.assertEqual(1, replay.fake_media_calls)
        self.assertEqual(1, replay.fake_vision_calls)
        self.assertTrue(replay.checks["replay_determinism"])

        expected = {
            "media-unavailable": "UNAVAILABLE",
            "media-timeout": "TIMED_OUT",
            "vision-outage": "PROVIDER_UNAVAILABLE",
            "vision-timeout": "TIMED_OUT",
            "malformed-vision-result": "REJECTED",
        }
        for case_id, outcome in expected.items():
            with self.subTest(case=case_id):
                self.assertEqual(outcome, cases[case_id].observed_outcome)
                self.assertTrue(
                    cases[case_id].checks["dependency_failure_handling"]
                )

    async def test_usage_is_zero_cost_and_latency_is_non_production(self) -> None:
        self.assertEqual(0, self.report.usage.provider_requests)
        self.assertEqual(0, self.report.usage.input_tokens)
        self.assertEqual(0, self.report.usage.output_tokens)
        self.assertEqual(Decimal("0"), self.report.usage.estimated_cost)
        self.assertFalse(self.report.usage.pricing_approved)
        self.assertFalse(self.report.usage.production_evidence_eligible)
        self.assertGreater(self.report.usage.fake_vision_invocations, 0)
        self.assertEqual(
            "NON_PRODUCTION_SIMULATED",
            self.report.simulated_latency.classification,
        )
        self.assertFalse(self.report.simulated_latency.production_slo_eligible)
        self.assertEqual(42, self.report.simulated_latency.p95_ms)

    async def test_every_missing_external_gate_is_a_release_blocker(self) -> None:
        expected = {
            "AGENT_PROPOSAL_API_DEFAULT_OFF",
            "AGENT_ORCHESTRATION_DEFAULT_OFF",
            "PRODUCT_MEDIA_TOOL_DEFAULT_OFF",
            "PROVIDER_EXECUTION_DEFAULT_OFF",
            "GATEWAY_EXPOSURE_DEFAULT_OFF",
            "FRONTEND_ENTRY_DEFAULT_OFF",
            "PRODUCTION_QUALITY_NOT_APPROVED",
            "PRODUCTION_LATENCY_NOT_APPROVED",
            "PRODUCTION_COST_NOT_APPROVED",
            "PRIVACY_REVIEW_NOT_APPROVED",
            "POLICY_REVIEW_NOT_APPROVED",
            "ROLLOUT_NOT_APPROVED",
        }
        self.assertEqual(expected, set(self.report.release_blockers))

    async def test_fixture_regression_fails_offline_without_unblocking_release(
        self,
    ) -> None:
        regressed = _replace_case(
            self.fixture,
            "clear",
            expected_outcome="REJECTED",
        )
        report = await run_offline_evaluation(regressed)

        self.assertEqual("FAIL", report.baseline_status)
        self.assertIn("CASE_FAILED:clear", report.baseline_failure_reasons)
        self.assertIn("OFFLINE_BASELINE_FAILED", report.release_blockers)
        self.assertEqual("BLOCKED", report.release_decision)


class ListingProposalEvaluationCliTest(unittest.TestCase):
    def test_cli_emits_report_schemas_and_deterministic_digest(self) -> None:
        for schema_name in ("fixture", "report"):
            with self.subTest(schema=schema_name):
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(0, main(["--schema", schema_name]))
                schema = json.loads(output.getvalue())
                self.assertEqual(False, schema["additionalProperties"])

        first = io.StringIO()
        second = io.StringIO()
        with redirect_stdout(first):
            self.assertEqual(0, main(["--digest"]))
        with redirect_stdout(second):
            self.assertEqual(0, main(["--digest"]))
        self.assertEqual(first.getvalue(), second.getvalue())
        self.assertRegex(
            json.loads(first.getvalue())["reportSha256"],
            r"^[0-9a-f]{64}$",
        )


if __name__ == "__main__":
    unittest.main()
