from __future__ import annotations

import asyncio
import json
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

from pydantic import ValidationError

from msb_agent_service.customer_service_evaluation import (
    OfflineEvaluationReport,
    load_offline_fixture,
    run_offline_evaluation,
)
from msb_agent_service.customer_service_release_gate import (
    GATE_DECISION_SCHEMA_VERSION,
    GATE_INPUT_SCHEMA_VERSION,
    OBSERVABILITY_SCHEMA_VERSION,
    ApprovalEvidence,
    GateStatus,
    OfflineObservabilityReport,
    ReleaseGateDecision,
    ReleaseGateInput,
    ReleaseSwitchStates,
    RolloutStage,
    SwitchState,
    _pretty_json,
    build_offline_observability,
    evaluate_release_gate,
    load_gate_input,
    report_evidence_sha256,
)

NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)
GENERATED = datetime(2026, 7, 20, 0, 0, tzinfo=UTC)
DEFAULT_GATE_INPUT = (
    Path(__file__).resolve().parents[1]
    / "evals"
    / "ai_cs_01e_b_release_gate_default_blocked_v1.json"
)


def _approval(name: str, *, approved: bool = True) -> dict[str, object]:
    if not approved:
        return {"approved": False}
    return {
        "approved": True,
        "externalEvidenceId": f"EVIDENCE:{name}",
        "approvedAt": "2026-07-19T00:00:00Z",
        "expiresAt": "2026-07-27T00:00:00Z",
    }


def _switches(
    *,
    enabled: bool = True,
    kill: str | None = None,
) -> dict[str, object]:
    names = (
        "customerServiceCapability",
        "providerExecution",
        "retrievalActivation",
        "gatewayExposure",
        "frontendEntryPoint",
    )
    return {
        name: {
            "enabled": enabled,
            "killSwitchEngaged": name == kill,
        }
        for name in names
    }


def _gate_input_payload(
    report: OfflineEvaluationReport,
    *,
    target_stage: str = "INTERNAL",
    generated_at: datetime = GENERATED,
    switches: dict[str, object] | None = None,
    approvals: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": GATE_INPUT_SCHEMA_VERSION,
        "decisionAt": NOW.isoformat(),
        "targetStage": target_stage,
        "reportGeneratedAt": generated_at.isoformat(),
        "reportEvidenceSha256": report_evidence_sha256(report, generated_at),
        "switches": switches or _switches(),
        "approvals": approvals
        or {
            "productionQuality": _approval("QUALITY"),
            "productionLatency": _approval("LATENCY"),
            "productionCost": _approval("COST"),
            "internalRollout": _approval("INTERNAL"),
            "smallCohortRollout": _approval("SMALL"),
            "widerRollout": _approval("WIDER"),
        },
    }


class ReleaseGateSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = asyncio.run(run_offline_evaluation(load_offline_fixture()))

    def test_input_decision_and_observability_schemas_are_strict(self) -> None:
        for schema_model, schema_version in (
            (ReleaseGateInput, GATE_INPUT_SCHEMA_VERSION),
            (ReleaseGateDecision, GATE_DECISION_SCHEMA_VERSION),
            (OfflineObservabilityReport, OBSERVABILITY_SCHEMA_VERSION),
        ):
            with self.subTest(schema=schema_version):
                schema = schema_model.model_json_schema(by_alias=True)
                self.assertEqual(False, schema["additionalProperties"])
                self.assertIn("schemaVersion", schema["properties"])

    def test_approved_evidence_requires_id_and_bounded_validity(self) -> None:
        with self.assertRaises(ValidationError):
            ApprovalEvidence(approved=True)
        with self.assertRaises(ValidationError):
            ApprovalEvidence(
                approved=False,
                externalEvidenceId="EVIDENCE:SHOULD_NOT_EXIST",
            )
        with self.assertRaises(ValidationError):
            ApprovalEvidence(
                approved=True,
                externalEvidenceId="EVIDENCE:EXPIRED",
                approvedAt="2026-07-20T00:00:00Z",
                expiresAt="2026-07-19T00:00:00Z",
            )

    def test_switch_contract_requires_every_explicit_state(self) -> None:
        payload = _switches()
        del payload["frontendEntryPoint"]
        with self.assertRaises(ValidationError):
            ReleaseSwitchStates.model_validate(payload)

    def test_default_fixture_keeps_every_real_switch_and_approval_off(self) -> None:
        gate_input = load_gate_input(DEFAULT_GATE_INPUT)

        self.assertTrue(
            all(
                not state.enabled and not state.kill_switch_engaged
                for state in (
                    gate_input.switches.customer_service_capability,
                    gate_input.switches.provider_execution,
                    gate_input.switches.retrieval_activation,
                    gate_input.switches.gateway_exposure,
                    gate_input.switches.frontend_entry_point,
                )
            )
        )
        self.assertTrue(
            all(
                not approval.approved
                for approval in gate_input.approvals.__dict__.values()
            )
        )

    def test_fixed_output_schemas_reject_duplicate_metric_or_gate_entries(
        self,
    ) -> None:
        observability = build_offline_observability(self.report)
        duplicate_metric = observability.model_dump(mode="json", by_alias=True)
        duplicate_metric["metrics"].append(duplicate_metric["metrics"][0])
        with self.assertRaises(ValidationError):
            OfflineObservabilityReport.model_validate(duplicate_metric)

        gate_input = ReleaseGateInput.model_validate(
            _gate_input_payload(self.report)
        )
        decision = evaluate_release_gate(
            self.report.model_dump(mode="json", by_alias=True),
            gate_input,
        )
        duplicate_gate = decision.model_dump(mode="json", by_alias=True)
        duplicate_gate["gates"].append(duplicate_gate["gates"][0])
        with self.assertRaises(ValidationError):
            ReleaseGateDecision.model_validate(duplicate_gate)


class ReleaseGateEvaluatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = asyncio.run(run_offline_evaluation(load_offline_fixture()))
        cls.report_payload = cls.report.model_dump(mode="json", by_alias=True)

    def decision(
        self,
        *,
        report_payload: object | None = None,
        gate_payload: dict[str, object] | None = None,
    ) -> ReleaseGateDecision:
        payload = (
            _gate_input_payload(self.report)
            if gate_payload is None
            else gate_payload
        )
        report = self.report_payload if report_payload is None else report_payload
        return evaluate_release_gate(
            report,
            ReleaseGateInput.model_validate(payload),
        )

    def test_missing_report_is_blocked(self) -> None:
        decision = evaluate_release_gate(
            None,
            ReleaseGateInput.model_validate(_gate_input_payload(self.report)),
        )

        self.assertEqual("BLOCKED", decision.decision)
        self.assertIn("REPORT_MISSING", decision.blockers)
        self.assertIsNone(decision.observability)

    def test_schema_and_version_mismatches_are_blocked(self) -> None:
        wrong_schema = {**self.report_payload, "schemaVersion": "future-schema"}
        schema_decision = self.decision(report_payload=wrong_schema)
        wrong_version = {**self.report_payload, "runnerVersion": "future-runner"}
        version_decision = self.decision(report_payload=wrong_version)

        self.assertIn("REPORT_SCHEMA_INCOMPATIBLE", schema_decision.blockers)
        self.assertIn("REPORT_VERSION_MISMATCH", version_decision.blockers)

    def test_inconsistent_counts_are_reported_as_invalid(self) -> None:
        inconsistent = {
            **self.report_payload,
            "caseCount": self.report.case_count + 1,
        }
        decision = self.decision(report_payload=inconsistent)

        self.assertIn("REPORT_INVALID", decision.blockers)
        self.assertIsNone(decision.observability)

    def test_stale_future_and_digest_mismatch_reports_are_blocked(self) -> None:
        stale_time = NOW - timedelta(days=8)
        stale = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                generated_at=stale_time,
            )
        )
        future_time = NOW + timedelta(seconds=1)
        future = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                generated_at=future_time,
            )
        )
        mismatch_payload = _gate_input_payload(self.report)
        mismatch_payload["reportEvidenceSha256"] = "0" * 64
        mismatch = self.decision(gate_payload=mismatch_payload)

        self.assertIn("REPORT_STALE", stale.blockers)
        self.assertIn("REPORT_TIMESTAMP_IN_FUTURE", future.blockers)
        self.assertIn("REPORT_DIGEST_MISMATCH", mismatch.blockers)

    def test_threshold_regression_is_machine_blocked(self) -> None:
        regressed = json.loads(json.dumps(self.report_payload))
        metric = regressed["metrics"]["retrieval_relevance"]
        metric.update({"value": "0.8000", "numerator": 8, "denominator": 10})
        threshold = next(
            item
            for item in regressed["thresholds"]
            if item["metric"] == "retrieval_relevance"
        )
        threshold.update({"actual": "0.8000", "passed": False})
        regressed["baselineStatus"] = "FAIL"
        regressed["baselineFailureReasons"] = [
            "THRESHOLD_FAILED:retrieval_relevance"
        ]
        decision = self.decision(report_payload=regressed)

        self.assertIn("QUALITY_THRESHOLD_REGRESSION", decision.blockers)
        assert decision.observability is not None
        precision = next(
            metric
            for metric in decision.observability.metrics
            if metric.name == "retrieval_precision_ratio"
        )
        self.assertEqual(GateStatus.BLOCKED, precision.status)

    def test_partial_enablement_and_missing_approvals_remain_blocked(self) -> None:
        switches = _switches()
        switches["frontendEntryPoint"] = {
            "enabled": False,
            "killSwitchEngaged": False,
        }
        approvals = {
            "productionQuality": _approval("QUALITY", approved=False),
            "productionLatency": _approval("LATENCY", approved=False),
            "productionCost": _approval("COST", approved=False),
            "internalRollout": _approval("INTERNAL", approved=False),
            "smallCohortRollout": _approval("SMALL", approved=False),
            "widerRollout": _approval("WIDER", approved=False),
        }
        decision = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                switches=switches,
                approvals=approvals,
            )
        )

        self.assertIn("FRONTEND_DEFAULT_OFF", decision.blockers)
        self.assertIn("PRODUCTION_QUALITY_APPROVAL_MISSING", decision.blockers)
        self.assertIn("PRODUCTION_LATENCY_APPROVAL_MISSING", decision.blockers)
        self.assertIn("PRODUCTION_COST_APPROVAL_MISSING", decision.blockers)
        self.assertIn("INTERNAL_ROLLOUT_APPROVAL_MISSING", decision.blockers)

    def test_kill_switch_precedence_blocks_fully_approved_synthetic_input(
        self,
    ) -> None:
        decision = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                switches=_switches(kill="customerServiceCapability"),
            )
        )

        self.assertEqual("BLOCKED", decision.decision)
        self.assertEqual(
            "CUSTOMER_SERVICE_KILL_SWITCH_ENGAGED",
            decision.blockers[0],
        )
        self.assertTrue(
            all(
                gate.status == GateStatus.PASS
                for gate in decision.gates[5:]
            )
        )

    def test_expired_external_latency_or_cost_evidence_is_blocked(self) -> None:
        approvals = _gate_input_payload(self.report)["approvals"]
        assert isinstance(approvals, dict)
        approvals["productionLatency"] = {
            "approved": True,
            "externalEvidenceId": "EVIDENCE:LATENCY",
            "approvedAt": "2026-07-18T00:00:00Z",
            "expiresAt": "2026-07-20T11:00:00Z",
        }
        decision = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                approvals=approvals,
            )
        )

        self.assertIn("PRODUCTION_LATENCY_APPROVAL_EXPIRED", decision.blockers)

    def test_rollout_stage_requires_prior_and_applicable_approvals(self) -> None:
        approvals = _gate_input_payload(self.report)["approvals"]
        assert isinstance(approvals, dict)
        approvals["smallCohortRollout"] = _approval("SMALL", approved=False)
        small = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                target_stage="SMALL_COHORT",
                approvals=approvals,
            )
        )
        approvals["smallCohortRollout"] = _approval("SMALL")
        approvals["widerRollout"] = _approval("WIDER", approved=False)
        wider = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                target_stage="WIDER",
                approvals=approvals,
            )
        )

        self.assertIn("SMALL_COHORT_ROLLOUT_APPROVAL_MISSING", small.blockers)
        self.assertIn("WIDER_ROLLOUT_APPROVAL_MISSING", wider.blockers)

    def test_fully_approved_synthetic_decisions_are_ready_for_each_stage(
        self,
    ) -> None:
        for stage in RolloutStage:
            with self.subTest(stage=stage):
                decision = self.decision(
                    gate_payload=_gate_input_payload(
                        self.report,
                        target_stage=stage.value,
                    )
                )
                self.assertEqual("READY", decision.decision)
                self.assertEqual((), decision.blockers)
                self.assertTrue(
                    all(
                        gate.status == GateStatus.PASS
                        for gate in decision.gates
                    )
                )

    def test_evaluation_is_reproducible_and_safe(self) -> None:
        first = self.decision()
        second = self.decision()
        serialized = _pretty_json(first)

        self.assertEqual(serialized, _pretty_json(second))
        self.assertNotIn("grounded-current-description", serialized)
        self.assertNotIn("01ARZ3NDEKTSV4RRFFQ69G5FAV", serialized)
        self.assertNotIn("Ignore previous instructions", serialized)
        self.assertNotIn("sk-proj-", serialized)
        self.assertNotIn("Bearer ", serialized)

    def test_observability_contract_is_low_cardinality_and_offline_honest(
        self,
    ) -> None:
        dashboard = build_offline_observability(self.report)
        dependencies = {
            metric.dependency
            for metric in dashboard.metrics
            if metric.name == "fake_dependency_failure_coverage_ratio"
        }

        self.assertEqual({"TOOL", "MODEL", "OPENSEARCH"}, dependencies)
        self.assertTrue(
            all(metric.evaluation_mode == "OFFLINE" for metric in dashboard.metrics)
        )
        self.assertTrue(
            all(metric.source_scope == "LISTING" for metric in dashboard.metrics)
        )
        self.assertEqual("UNKNOWN", dashboard.production_latency_status)
        self.assertEqual("UNKNOWN", dashboard.production_cost_status)
        self.assertFalse(dashboard.pricing_approved)


if __name__ == "__main__":
    unittest.main()
