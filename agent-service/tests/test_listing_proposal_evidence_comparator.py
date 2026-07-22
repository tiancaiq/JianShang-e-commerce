from __future__ import annotations

import asyncio
import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import UTC, datetime, timedelta

from pydantic import ValidationError

from msb_agent_service.listing_proposal_evaluation import (
    OfflineProposalReport,
    report_evidence_sha256 as offline_report_sha256,
)
from msb_agent_service.listing_proposal_evidence_contract import (
    model_evidence_sha256 as shared_model_sha256,
    timestamped_model_evidence_sha256,
)
from msb_agent_service.listing_proposal_evidence_comparator import (
    BUNDLE_SCHEMA_VERSION,
    COMPARISON_SCHEMA_VERSION,
    DEFAULT_MANIFEST,
    MANIFEST_SCHEMA_VERSION,
    PINNED_MANIFEST_SHA256,
    ComparisonReason,
    EvidenceManifest,
    OfflineEvidenceBundle,
    RegressionComparison,
    RegressionStatus,
    build_current_bundle,
    compare_evidence,
    comparison_evidence_sha256,
    load_manifest,
    main,
    model_evidence_sha256,
    raw_evidence_sha256,
)
from msb_agent_service.listing_proposal_release_gate import (
    ListingReleaseGateDecision,
    report_evidence_sha256 as timestamped_report_sha256,
)

EVALUATED_AT = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)


def _copy(payload: object) -> object:
    return json.loads(json.dumps(payload))


def _refresh_valid_digests(payload: dict[str, object]) -> None:
    report = OfflineProposalReport.model_validate(payload["report"])
    decision = ListingReleaseGateDecision.model_validate(payload["decision"])
    generated_at = datetime.fromisoformat(
        str(payload["generatedAt"]).replace("Z", "+00:00")
    )
    assert decision.observability is not None
    payload["reportSha256"] = offline_report_sha256(report)
    payload["timestampedReportSha256"] = timestamped_report_sha256(
        report,
        generated_at,
    )
    payload["decisionSha256"] = model_evidence_sha256(decision)
    payload["observabilitySha256"] = model_evidence_sha256(
        decision.observability
    )


class ListingEvidenceManifestSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = load_manifest()
        cls.manifest_payload = json.loads(
            DEFAULT_MANIFEST.read_text(encoding="utf-8")
        )
        cls.bundle = asyncio.run(build_current_bundle())

    def test_manifest_bundle_and_comparison_schemas_are_strict(self) -> None:
        for model, version in (
            (EvidenceManifest, MANIFEST_SCHEMA_VERSION),
            (OfflineEvidenceBundle, BUNDLE_SCHEMA_VERSION),
            (RegressionComparison, COMPARISON_SCHEMA_VERSION),
        ):
            with self.subTest(version=version):
                schema = model.model_json_schema(by_alias=True)
                self.assertEqual(False, schema["additionalProperties"])
                self.assertIn("schemaVersion", schema["properties"])
        comparison_schema = RegressionComparison.model_json_schema(
            by_alias=True
        )
        self.assertEqual(
            "BLOCKED",
            comparison_schema["properties"]["rolloutDecision"]["const"],
        )
        self.assertEqual(
            False,
            comparison_schema["properties"]["releaseAuthorized"]["const"],
        )

    def test_manifest_digest_and_exact_pins_are_stable(self) -> None:
        self.assertEqual(
            PINNED_MANIFEST_SHA256,
            raw_evidence_sha256(self.manifest_payload),
        )
        self.assertEqual(13, self.manifest.report.case_count)
        self.assertEqual(
            self.manifest.report.case_count,
            len(self.manifest.report.case_ids),
        )
        self.assertEqual(
            "3d16fe7020286923dbb07aba3773c5ef891eba9828aed1d3e84f4be5e9f6cd3c",
            self.manifest.report.baseline_report_sha256,
        )
        self.assertEqual(
            "afc03104084db7b3e095fb6ec044869550e595dcc78a01c8b19c38629de507d3",
            self.manifest.gate.baseline_decision_sha256,
        )
        self.assertEqual(
            "192aca78603e8dbcdeb0db0f7a3bee779a0f1953aa84a8450df735544751b8b3",
            self.manifest.gate.baseline_observability_sha256,
        )
        self.assertEqual(15, len(self.manifest.observability.metric_names))

    def test_manifest_rejects_duplicate_cases_metrics_and_observability(self) -> None:
        case_payload = _copy(self.manifest_payload)
        assert isinstance(case_payload, dict)
        case_payload["report"]["caseIds"].append(
            case_payload["report"]["caseIds"][0]
        )
        case_payload["report"]["caseCount"] += 1
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(case_payload)

        metric_payload = _copy(self.manifest_payload)
        assert isinstance(metric_payload, dict)
        metric_payload["report"]["metrics"].append(
            metric_payload["report"]["metrics"][0]
        )
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(metric_payload)

        observable_payload = _copy(self.manifest_payload)
        assert isinstance(observable_payload, dict)
        observable_payload["observability"]["metricNames"].append(
            observable_payload["observability"]["metricNames"][0]
        )
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(observable_payload)

    def test_manifest_rejects_inconsistent_pins_and_precedence_drift(self) -> None:
        metric_payload = _copy(self.manifest_payload)
        assert isinstance(metric_payload, dict)
        metric_payload["report"]["metrics"][0]["numerator"] = 4
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(metric_payload)

        gates_payload = _copy(self.manifest_payload)
        assert isinstance(gates_payload, dict)
        gate_names = gates_payload["gate"]["gateNames"]
        gate_names[0], gate_names[1] = gate_names[1], gate_names[0]
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(gates_payload)

        observability_payload = _copy(self.manifest_payload)
        assert isinstance(observability_payload, dict)
        metric_names = observability_payload["observability"]["metricNames"]
        metric_names[0], metric_names[1] = metric_names[1], metric_names[0]
        with self.assertRaises(ValidationError):
            EvidenceManifest.model_validate(observability_payload)

    def test_shared_digest_contract_preserves_all_pinned_evidence(self) -> None:
        self.assertEqual(
            offline_report_sha256(self.bundle.report),
            shared_model_sha256(self.bundle.report),
        )
        self.assertEqual(
            timestamped_report_sha256(
                self.bundle.report,
                self.bundle.generated_at,
            ),
            timestamped_model_evidence_sha256(
                self.bundle.report,
                self.bundle.generated_at,
            ),
        )
        self.assertEqual(
            self.bundle.decision_sha256,
            shared_model_sha256(self.bundle.decision),
        )
        assert self.bundle.decision.observability is not None
        self.assertEqual(
            self.bundle.observability_sha256,
            shared_model_sha256(self.bundle.decision.observability),
        )

    def test_comparison_digest_is_self_consistent_and_tamper_evident(self) -> None:
        comparison = compare_evidence(
            self.bundle.model_dump(mode="json", by_alias=True),
            self.manifest_payload,
            EVALUATED_AT,
        )
        self.assertEqual(
            comparison.comparison_sha256,
            comparison_evidence_sha256(comparison),
        )
        payload = comparison.model_dump(mode="json", by_alias=True)
        payload["evaluatedAt"] = "2026-07-20T12:00:01Z"
        with self.assertRaises(ValidationError):
            RegressionComparison.model_validate(payload)


class ListingEvidenceComparatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest_payload = json.loads(
            DEFAULT_MANIFEST.read_text(encoding="utf-8")
        )
        cls.bundle = asyncio.run(build_current_bundle())
        cls.bundle_payload = cls.bundle.model_dump(mode="json", by_alias=True)

    def compare(
        self,
        *,
        candidate: object | None = None,
        manifest: object | None = None,
        evaluated_at: datetime = EVALUATED_AT,
    ) -> RegressionComparison:
        return compare_evidence(
            self.bundle_payload if candidate is None else candidate,
            self.manifest_payload if manifest is None else manifest,
            evaluated_at,
        )

    def test_current_candidate_is_regression_pass_but_release_blocked(self) -> None:
        first = self.compare()
        second = self.compare()

        self.assertEqual(RegressionStatus.PASS, first.regression_status)
        self.assertEqual((), first.reason_codes)
        self.assertEqual("BLOCKED", first.rollout_decision)
        self.assertFalse(first.release_authorized)
        self.assertTrue(all(check.passed for check in first.checks))
        self.assertEqual(first.comparison_sha256, second.comparison_sha256)
        self.assertEqual(first.model_dump(), second.model_dump())

    def test_missing_malformed_incompatible_and_tampered_inputs_fail(self) -> None:
        missing = compare_evidence(
            None,
            self.manifest_payload,
            EVALUATED_AT,
        )
        malformed = compare_evidence(
            ["not", "a", "bundle"],
            self.manifest_payload,
            EVALUATED_AT,
        )
        incompatible_payload = _copy(self.bundle_payload)
        assert isinstance(incompatible_payload, dict)
        incompatible_payload["schemaVersion"] = "future-bundle"
        incompatible = self.compare(candidate=incompatible_payload)
        tampered_manifest = _copy(self.manifest_payload)
        assert isinstance(tampered_manifest, dict)
        tampered_manifest["report"]["seed"] += 1
        tampered = self.compare(manifest=tampered_manifest)

        self.assertIn(ComparisonReason.BUNDLE_MISSING, missing.reason_codes)
        self.assertIn(ComparisonReason.BUNDLE_MALFORMED, malformed.reason_codes)
        self.assertIn(
            ComparisonReason.BUNDLE_SCHEMA_INCOMPATIBLE,
            incompatible.reason_codes,
        )
        self.assertIn(
            ComparisonReason.MANIFEST_DIGEST_MISMATCH,
            tampered.reason_codes,
        )

    def test_identity_and_all_evidence_digests_are_verified(self) -> None:
        identity_payload = _copy(self.bundle_payload)
        assert isinstance(identity_payload, dict)
        identity_payload["report"]["fixtureVersion"] = "future-fixture"
        identity = self.compare(candidate=identity_payload)
        self.assertIn(ComparisonReason.IDENTITY_MISMATCH, identity.reason_codes)

        fields_and_reasons = (
            ("reportSha256", ComparisonReason.REPORT_DIGEST_MISMATCH),
            (
                "timestampedReportSha256",
                ComparisonReason.TIMESTAMPED_REPORT_DIGEST_MISMATCH,
            ),
            ("decisionSha256", ComparisonReason.DECISION_DIGEST_MISMATCH),
            (
                "observabilitySha256",
                ComparisonReason.OBSERVABILITY_DIGEST_MISMATCH,
            ),
        )
        for field, reason in fields_and_reasons:
            with self.subTest(field=field):
                payload = _copy(self.bundle_payload)
                assert isinstance(payload, dict)
                payload[field] = "0" * 64
                comparison = self.compare(candidate=payload)
                self.assertIn(reason, comparison.reason_codes)

    def test_missing_extra_and_duplicate_cases_are_rejected(self) -> None:
        missing_payload = _copy(self.bundle_payload)
        assert isinstance(missing_payload, dict)
        missing_payload["report"]["caseResults"].pop()
        missing_payload["report"]["caseCount"] -= 1
        _refresh_valid_digests(missing_payload)
        missing = self.compare(candidate=missing_payload)

        extra_payload = _copy(self.bundle_payload)
        assert isinstance(extra_payload, dict)
        extra = _copy(extra_payload["report"]["caseResults"][0])
        extra["caseId"] = "extra-case"
        extra_payload["report"]["caseResults"].append(extra)
        extra_payload["report"]["caseCount"] += 1
        _refresh_valid_digests(extra_payload)
        extra_result = self.compare(candidate=extra_payload)

        duplicate_payload = _copy(self.bundle_payload)
        assert isinstance(duplicate_payload, dict)
        duplicate_payload["report"]["caseResults"].append(
            duplicate_payload["report"]["caseResults"][0]
        )
        duplicate_payload["report"]["caseCount"] += 1
        duplicate = self.compare(candidate=duplicate_payload)

        self.assertIn(ComparisonReason.CASE_COUNT_MISMATCH, missing.reason_codes)
        self.assertIn(ComparisonReason.CASE_SET_MISMATCH, missing.reason_codes)
        self.assertIn(
            ComparisonReason.CASE_COUNT_MISMATCH,
            extra_result.reason_codes,
        )
        self.assertIn(
            ComparisonReason.CASE_SET_MISMATCH,
            extra_result.reason_codes,
        )
        self.assertIn(
            ComparisonReason.CASE_SET_MISMATCH,
            duplicate.reason_codes,
        )

    def test_failure_coverage_metric_regression_and_coverage_loss_fail(self) -> None:
        failure_payload = _copy(self.bundle_payload)
        assert isinstance(failure_payload, dict)
        failure_payload["report"]["caseResults"][0]["passed"] = False
        failure_payload["report"]["caseResults"][0]["failureReasons"] = [
            "CHECK_FAILED:strict_schema_conformance"
        ]
        failure_payload["report"]["caseResults"][0]["checks"][
            "strict_schema_conformance"
        ] = False
        failure_payload["report"]["baselineStatus"] = "FAIL"
        failure_payload["report"]["baselineFailureReasons"] = [
            "CASE_FAILED:clear",
            "THRESHOLD_FAILED:strict_schema_conformance",
        ]
        failure = self.compare(candidate=failure_payload)
        self.assertIn(
            ComparisonReason.FAILURE_COVERAGE_LOSS,
            failure.reason_codes,
        )

        regression_payload = _copy(self.bundle_payload)
        assert isinstance(regression_payload, dict)
        metric = regression_payload["report"]["metrics"][
            "strict_schema_conformance"
        ]
        metric.update({"value": "0.7500", "numerator": 3})
        threshold = next(
            item
            for item in regression_payload["report"]["thresholds"]
            if item["metric"] == "strict_schema_conformance"
        )
        threshold.update({"actual": "0.7500", "passed": False})
        regression_payload["report"]["baselineStatus"] = "FAIL"
        regression_payload["report"]["baselineFailureReasons"] = [
            "THRESHOLD_FAILED:strict_schema_conformance"
        ]
        _refresh_valid_digests(regression_payload)
        regression = self.compare(candidate=regression_payload)
        self.assertIn(
            ComparisonReason.METRIC_REGRESSION,
            regression.reason_codes,
        )

        coverage_payload = _copy(self.bundle_payload)
        assert isinstance(coverage_payload, dict)
        coverage_payload["report"]["metrics"]["outcome_conformance"].update(
            {"value": "1.0000", "numerator": 12, "denominator": 12}
        )
        _refresh_valid_digests(coverage_payload)
        coverage = self.compare(candidate=coverage_payload)
        self.assertIn(
            ComparisonReason.METRIC_COVERAGE_LOSS,
            coverage.reason_codes,
        )

    def test_extra_metric_and_weakened_threshold_fail(self) -> None:
        metric_payload = _copy(self.bundle_payload)
        assert isinstance(metric_payload, dict)
        metric_payload["report"]["metrics"]["extra_metric"] = {
            "value": "1.0000",
            "numerator": 1,
            "denominator": 1,
        }
        metric = self.compare(candidate=metric_payload)
        self.assertIn(ComparisonReason.METRIC_SET_MISMATCH, metric.reason_codes)

        threshold_payload = _copy(self.bundle_payload)
        assert isinstance(threshold_payload, dict)
        threshold_payload["report"]["thresholds"][0]["threshold"] = "0.9000"
        threshold = self.compare(candidate=threshold_payload)
        self.assertIn(
            ComparisonReason.THRESHOLD_WEAKENED,
            threshold.reason_codes,
        )

    def test_nonzero_usage_is_classified_with_fixed_reasons(self) -> None:
        for field, value, reason in (
            (
                "providerRequests",
                1,
                ComparisonReason.NONZERO_PROVIDER_REQUESTS,
            ),
            ("inputTokens", 1, ComparisonReason.NONZERO_TOKENS),
            ("outputTokens", 1, ComparisonReason.NONZERO_TOKENS),
            ("estimatedCost", "0.01", ComparisonReason.NONZERO_COST),
        ):
            with self.subTest(field=field):
                payload = _copy(self.bundle_payload)
                assert isinstance(payload, dict)
                payload["report"]["usage"][field] = value
                comparison = self.compare(candidate=payload)
                self.assertIn(reason, comparison.reason_codes)
                self.assertEqual(
                    ComparisonReason.BUNDLE_INVALID,
                    comparison.reason_codes[0],
                )

    def test_simulated_latency_label_claim_and_bound_are_enforced(self) -> None:
        label_payload = _copy(self.bundle_payload)
        assert isinstance(label_payload, dict)
        label_payload["report"]["simulatedLatency"]["classification"] = (
            "PRODUCTION"
        )
        label = self.compare(candidate=label_payload)

        claim_payload = _copy(self.bundle_payload)
        assert isinstance(claim_payload, dict)
        claim_payload["report"]["simulatedLatency"][
            "productionSloEligible"
        ] = True
        claim = self.compare(candidate=claim_payload)

        latency_payload = _copy(self.bundle_payload)
        assert isinstance(latency_payload, dict)
        latency_payload["report"]["simulatedLatency"]["p95Ms"] = 48
        _refresh_valid_digests(latency_payload)
        latency = self.compare(candidate=latency_payload)

        self.assertIn(
            ComparisonReason.SIMULATED_LATENCY_LABEL_MISMATCH,
            label.reason_codes,
        )
        self.assertIn(
            ComparisonReason.SIMULATED_LATENCY_PRODUCTION_CLAIM,
            claim.reason_codes,
        )
        self.assertIn(
            ComparisonReason.SIMULATED_LATENCY_REGRESSION,
            latency.reason_codes,
        )

    def test_observability_names_decision_and_default_off_precedence_are_exact(
        self,
    ) -> None:
        metrics_payload = _copy(self.bundle_payload)
        assert isinstance(metrics_payload, dict)
        metrics = metrics_payload["decision"]["observability"]["metrics"]
        metrics.append(metrics[0])
        cardinality = self.compare(candidate=metrics_payload)
        self.assertIn(
            ComparisonReason.OBSERVABILITY_METRIC_SET_MISMATCH,
            cardinality.reason_codes,
        )

        decision_payload = _copy(self.bundle_payload)
        assert isinstance(decision_payload, dict)
        decision_payload["decision"]["releaseAuthorized"] = True
        decision_payload["decision"]["decision"] = "READY"
        unsafe = self.compare(candidate=decision_payload)
        self.assertIn(ComparisonReason.RELEASE_AUTHORIZED, unsafe.reason_codes)
        self.assertIn(ComparisonReason.DECISION_NOT_BLOCKED, unsafe.reason_codes)

        precedence_payload = _copy(self.bundle_payload)
        assert isinstance(precedence_payload, dict)
        gates = precedence_payload["decision"]["gates"]
        gates[0], gates[1] = gates[1], gates[0]
        precedence = self.compare(candidate=precedence_payload)
        self.assertIn(
            ComparisonReason.KILL_SWITCH_PRECEDENCE_DRIFT,
            precedence.reason_codes,
        )

    def test_production_and_readiness_claims_are_rejected(self) -> None:
        cases = (
            (
                ("decision", "observability", "productionQualityStatus"),
                "PASS",
                ComparisonReason.PRODUCTION_QUALITY_CLAIMED,
            ),
            (
                ("decision", "observability", "productionLatencyStatus"),
                "PASS",
                ComparisonReason.PRODUCTION_LATENCY_CLAIMED,
            ),
            (
                ("decision", "observability", "productionCostStatus"),
                "PASS",
                ComparisonReason.PRODUCTION_COST_CLAIMED,
            ),
            (
                ("report", "releaseInputs", "rolloutApproval"),
                True,
                ComparisonReason.READINESS_CLAIMED,
            ),
        )
        for path, value, reason in cases:
            with self.subTest(path=path):
                payload = _copy(self.bundle_payload)
                assert isinstance(payload, dict)
                target = payload
                for segment in path[:-1]:
                    target = target[segment]
                target[path[-1]] = value
                comparison = self.compare(candidate=payload)
                self.assertIn(reason, comparison.reason_codes)

        gate_payload = _copy(self.bundle_payload)
        assert isinstance(gate_payload, dict)
        quality_gate = next(
            gate
            for gate in gate_payload["decision"]["gates"]
            if gate["gate"] == "PRODUCTION_QUALITY_EVIDENCE"
        )
        quality_gate.update({"status": "PASS", "reasonCode": "PASSED"})
        quality = self.compare(candidate=gate_payload)
        self.assertIn(
            ComparisonReason.PRODUCTION_QUALITY_CLAIMED,
            quality.reason_codes,
        )

    def test_stale_and_future_evidence_are_rejected(self) -> None:
        stale = self.compare(
            evaluated_at=EVALUATED_AT + timedelta(days=8)
        )
        future_payload = _copy(self.bundle_payload)
        assert isinstance(future_payload, dict)
        future_payload["generatedAt"] = "2026-07-21T00:00:00Z"
        future_payload["expiresAt"] = "2026-07-28T00:00:00Z"
        future = self.compare(candidate=future_payload)

        self.assertIn(ComparisonReason.EVIDENCE_STALE, stale.reason_codes)
        self.assertIn(
            ComparisonReason.EVIDENCE_TIMESTAMP_IN_FUTURE,
            future.reason_codes,
        )

    def test_overlong_evidence_window_is_rejected_with_fixed_precedence(
        self,
    ) -> None:
        payload = _copy(self.bundle_payload)
        assert isinstance(payload, dict)
        payload["expiresAt"] = "2026-07-28T00:00:00Z"

        comparison = self.compare(candidate=payload)

        self.assertEqual(
            (
                ComparisonReason.BUNDLE_INVALID,
                ComparisonReason.EVIDENCE_WINDOW_INVALID,
            ),
            comparison.reason_codes,
        )


class ListingEvidenceComparatorCliTest(unittest.TestCase):
    def test_cli_schemas_current_comparison_and_digest_are_deterministic(
        self,
    ) -> None:
        for schema_name in ("manifest", "bundle", "comparison"):
            with self.subTest(schema=schema_name):
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(0, main(["--schema", schema_name]))
                schema = json.loads(output.getvalue())
                self.assertEqual(False, schema["additionalProperties"])

        arguments = ["--evaluated-at", "2026-07-20T12:00:00Z"]
        first = io.StringIO()
        second = io.StringIO()
        with redirect_stdout(first):
            self.assertEqual(0, main(arguments))
        with redirect_stdout(second):
            self.assertEqual(0, main([*arguments, "--digest"]))
        comparison = json.loads(first.getvalue())
        digest = json.loads(second.getvalue())

        self.assertEqual("REGRESSION_PASS", comparison["regressionStatus"])
        self.assertEqual("BLOCKED", comparison["rolloutDecision"])
        self.assertFalse(comparison["releaseAuthorized"])
        self.assertEqual(
            comparison["comparisonSha256"],
            digest["comparisonSha256"],
        )


if __name__ == "__main__":
    unittest.main()
