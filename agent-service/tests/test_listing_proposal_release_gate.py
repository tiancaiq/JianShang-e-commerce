from __future__ import annotations

import asyncio
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import UTC, datetime, timedelta
from pathlib import Path

from pydantic import ValidationError

from msb_agent_service.listing_proposal_evaluation import (
    OfflineProposalReport,
    load_offline_fixture,
    run_offline_evaluation,
)
from msb_agent_service.listing_proposal_release_gate import (
    DEFAULT_GATE_INPUT,
    GATE_DECISION_SCHEMA_VERSION,
    GATE_INPUT_SCHEMA_VERSION,
    OBSERVABILITY_SCHEMA_VERSION,
    ApprovalEvidence,
    EvidenceSource,
    EvidenceStatus,
    ListingOfflineObservability,
    ListingReleaseGateDecision,
    ListingReleaseGateInput,
    ProductionEvidence,
    RolloutStage,
    _pretty_json,
    build_offline_observability,
    evaluate_release_gate,
    load_gate_input,
    main,
    report_evidence_sha256,
)

NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)
GENERATED = datetime(2026, 7, 20, 0, 0, tzinfo=UTC)


def _approval(name: str, *, approved: bool = True) -> dict[str, object]:
    if not approved:
        return {"approved": False}
    return {
        "approved": True,
        "externalEvidenceId": f"EVIDENCE:{name}",
        "approvedAt": "2026-07-19T00:00:00Z",
        "expiresAt": "2026-07-27T00:00:00Z",
    }


def _production_evidence(
    name: str,
    *,
    approved: bool = True,
    source: str = "EXTERNAL_PRODUCTION",
) -> dict[str, object]:
    if not approved:
        return {"status": "UNKNOWN", "source": "UNKNOWN"}
    return {
        "status": "APPROVED",
        "source": source,
        "externalEvidenceId": f"EVIDENCE:{name}",
        "collectedAt": "2026-07-19T00:00:00Z",
        "expiresAt": "2026-07-27T00:00:00Z",
    }


def _switches(
    *,
    enabled: bool = True,
    kill: str | None = None,
    global_kill: bool = False,
) -> dict[str, object]:
    names = (
        "proposalCapability",
        "mediaTool",
        "providerExecution",
        "agentApi",
        "gatewayExposure",
        "frontendEntry",
    )
    return {
        "globalKillSwitchEngaged": global_kill,
        **{
            name: {
                "enabled": enabled,
                "killSwitchEngaged": name == kill,
            }
            for name in names
        },
    }


def _gate_input_payload(
    report: OfflineProposalReport,
    *,
    generated_at: datetime = GENERATED,
    target_stage: str = "INTERNAL",
    switches: dict[str, object] | None = None,
    production_evidence: dict[str, object] | None = None,
    approvals: dict[str, object] | None = None,
) -> dict[str, object]:
    return {
        "schemaVersion": GATE_INPUT_SCHEMA_VERSION,
        "decisionAt": NOW.isoformat(),
        "targetStage": target_stage,
        "reportGeneratedAt": generated_at.isoformat(),
        "reportEvidenceSha256": report_evidence_sha256(
            report,
            generated_at,
        ),
        "switches": switches or _switches(),
        "productionEvidence": production_evidence
        or {
            "quality": _production_evidence("QUALITY"),
            "latency": _production_evidence("LATENCY"),
            "cost": _production_evidence("COST"),
        },
        "approvals": approvals
        or {
            "privacy": _approval("PRIVACY"),
            "policy": _approval("POLICY"),
            "internalRollout": _approval("INTERNAL"),
            "smallCohortRollout": _approval("SMALL"),
            "widerRollout": _approval("WIDER"),
        },
    }


def _copy(payload: object) -> object:
    return json.loads(json.dumps(payload))


class ListingReleaseGateSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = asyncio.run(run_offline_evaluation(load_offline_fixture()))

    def test_input_decision_and_observability_schemas_are_strict(self) -> None:
        for model, version in (
            (ListingReleaseGateInput, GATE_INPUT_SCHEMA_VERSION),
            (ListingReleaseGateDecision, GATE_DECISION_SCHEMA_VERSION),
            (ListingOfflineObservability, OBSERVABILITY_SCHEMA_VERSION),
        ):
            with self.subTest(version=version):
                schema = model.model_json_schema(by_alias=True)
                self.assertEqual(False, schema["additionalProperties"])
                self.assertIn("schemaVersion", schema["properties"])
        decision_schema = ListingReleaseGateDecision.model_json_schema(
            by_alias=True
        )
        self.assertEqual(
            "BLOCKED",
            decision_schema["properties"]["decision"]["const"],
        )
        self.assertEqual(
            False,
            decision_schema["properties"]["releaseAuthorized"]["const"],
        )

    def test_default_input_keeps_flags_off_and_evidence_unknown(self) -> None:
        gate_input = load_gate_input()

        self.assertFalse(gate_input.switches.global_kill_switch_engaged)
        for state in (
            gate_input.switches.proposal_capability,
            gate_input.switches.media_tool,
            gate_input.switches.provider_execution,
            gate_input.switches.agent_api,
            gate_input.switches.gateway_exposure,
            gate_input.switches.frontend_entry,
        ):
            self.assertFalse(state.enabled)
            self.assertFalse(state.kill_switch_engaged)
        for evidence in (
            gate_input.production_evidence.quality,
            gate_input.production_evidence.latency,
            gate_input.production_evidence.cost,
        ):
            self.assertEqual(EvidenceStatus.UNKNOWN, evidence.status)
            self.assertEqual(EvidenceSource.UNKNOWN, evidence.source)
        self.assertTrue(
            all(
                not approval.approved
                for approval in gate_input.approvals.__dict__.values()
            )
        )

    def test_evidence_contract_rejects_implicit_or_unbounded_claims(self) -> None:
        with self.assertRaises(ValidationError):
            ProductionEvidence(status="APPROVED", source="UNKNOWN")
        with self.assertRaises(ValidationError):
            ProductionEvidence(
                status="UNKNOWN",
                source="UNKNOWN",
                externalEvidenceId="EVIDENCE:NOT_ALLOWED",
            )
        with self.assertRaises(ValidationError):
            ApprovalEvidence(
                approved=True,
                externalEvidenceId="EVIDENCE:EXPIRED",
                approvedAt="2026-07-20T00:00:00Z",
                expiresAt="2026-07-19T00:00:00Z",
            )

    def test_observability_rejects_duplicate_or_missing_metric_names(self) -> None:
        dashboard = build_offline_observability(self.report)
        schema = json.dumps(
            ListingOfflineObservability.model_json_schema(by_alias=True),
            sort_keys=True,
        )
        for forbidden in (
            "actorUserId",
            "listingId",
            "mediaId",
            "requestId",
            "correlationId",
            "caseId",
            "prompt",
            "response",
            "modelId",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, schema)

        payload = dashboard.model_dump(mode="json", by_alias=True)
        payload["metrics"].append(payload["metrics"][0])
        with self.assertRaises(ValidationError):
            ListingOfflineObservability.model_validate(payload)
        payload = dashboard.model_dump(mode="json", by_alias=True)
        payload["metrics"].pop()
        with self.assertRaises(ValidationError):
            ListingOfflineObservability.model_validate(payload)


class ListingReleaseGateEvaluatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = asyncio.run(run_offline_evaluation(load_offline_fixture()))
        cls.report_payload = cls.report.model_dump(mode="json", by_alias=True)

    def decision(
        self,
        *,
        report_payload: object | None = None,
        gate_payload: dict[str, object] | None = None,
    ) -> ListingReleaseGateDecision:
        report = (
            self.report_payload
            if report_payload is None
            else report_payload
        )
        inputs = (
            _gate_input_payload(self.report)
            if gate_payload is None
            else gate_payload
        )
        return evaluate_release_gate(
            report,
            ListingReleaseGateInput.model_validate(inputs),
        )

    def test_valid_offline_report_can_never_authorize_rollout(self) -> None:
        for stage in RolloutStage:
            with self.subTest(stage=stage):
                decision = self.decision(
                    gate_payload=_gate_input_payload(
                        self.report,
                        target_stage=stage.value,
                    )
                )
                self.assertEqual("BLOCKED", decision.decision)
                self.assertFalse(decision.release_authorized)
                self.assertEqual(
                    ("OFFLINE_EVIDENCE_CANNOT_AUTHORIZE_ROLLOUT",),
                    decision.blockers,
                )
                self.assertTrue(
                    all(
                        gate.status == "PASS"
                        for gate in decision.gates[:-1]
                    )
                )

    def test_missing_malformed_and_incompatible_reports_are_blocked(self) -> None:
        gate_input = ListingReleaseGateInput.model_validate(
            _gate_input_payload(self.report)
        )
        missing = evaluate_release_gate(None, gate_input)
        malformed = evaluate_release_gate(["not", "a", "report"], gate_input)
        incompatible = self.decision(
            report_payload={
                **self.report_payload,
                "schemaVersion": "future-schema",
            }
        )

        self.assertIn("REPORT_MISSING", missing.blockers)
        self.assertIn("REPORT_MALFORMED", malformed.blockers)
        self.assertIn("REPORT_SCHEMA_INCOMPATIBLE", incompatible.blockers)

    def test_stale_future_digest_mismatch_and_tampering_are_blocked(self) -> None:
        stale = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                generated_at=NOW - timedelta(days=8),
            )
        )
        future = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                generated_at=NOW + timedelta(seconds=1),
            )
        )
        mismatch_input = _gate_input_payload(self.report)
        mismatch_input["reportEvidenceSha256"] = "0" * 64
        mismatch = self.decision(gate_payload=mismatch_input)
        tampered = dict(self.report_payload)
        tampered["seed"] = self.report.seed + 1
        tamper_decision = self.decision(report_payload=tampered)

        self.assertIn("REPORT_STALE", stale.blockers)
        self.assertIn("REPORT_TIMESTAMP_IN_FUTURE", future.blockers)
        self.assertIn("REPORT_DIGEST_MISMATCH", mismatch.blockers)
        self.assertIn("REPORT_DIGEST_MISMATCH", tamper_decision.blockers)

    def test_case_count_and_failure_coverage_inconsistency_are_explicit(self) -> None:
        count_payload = _copy(self.report_payload)
        assert isinstance(count_payload, dict)
        count_payload["caseCount"] = self.report.case_count + 1
        count = self.decision(report_payload=count_payload)

        failure_payload = _copy(self.report_payload)
        assert isinstance(failure_payload, dict)
        failure_payload["baselineFailureReasons"] = ["CASE_FAILED:missing"]
        failure_payload["baselineStatus"] = "FAIL"
        failure = self.decision(report_payload=failure_payload)

        self.assertIn("REPORT_CASE_COUNT_INCONSISTENT", count.blockers)
        self.assertIn("REPORT_FAILURE_COVERAGE_INCONSISTENT", failure.blockers)
        self.assertIn("REPORT_INVALID", count.blockers)
        self.assertIn("REPORT_INVALID", failure.blockers)

    def test_metric_and_threshold_cross_consistency_are_enforced(self) -> None:
        metric_payload = _copy(self.report_payload)
        assert isinstance(metric_payload, dict)
        metric = metric_payload["metrics"]["strict_schema_conformance"]
        metric.update({"value": "0.0000", "numerator": 0})
        threshold = next(
            item
            for item in metric_payload["thresholds"]
            if item["metric"] == "strict_schema_conformance"
        )
        threshold.update({"actual": "0.0000", "passed": False})
        metric_payload["baselineStatus"] = "FAIL"
        metric_payload["baselineFailureReasons"] = [
            "THRESHOLD_FAILED:strict_schema_conformance"
        ]
        metric_decision = self.decision(report_payload=metric_payload)

        threshold_payload = _copy(self.report_payload)
        assert isinstance(threshold_payload, dict)
        threshold = next(
            item
            for item in threshold_payload["thresholds"]
            if item["metric"] == "privacy_redaction"
        )
        threshold.update({"actual": "0.0000", "passed": False})
        threshold_payload["baselineStatus"] = "FAIL"
        threshold_payload["baselineFailureReasons"] = [
            "THRESHOLD_FAILED:privacy_redaction"
        ]
        threshold_decision = self.decision(report_payload=threshold_payload)

        self.assertIn(
            "REPORT_METRICS_INCONSISTENT",
            metric_decision.blockers,
        )
        self.assertIn(
            "REPORT_THRESHOLDS_INCONSISTENT",
            threshold_decision.blockers,
        )

    def test_nonzero_provider_request_tokens_or_cost_are_rejected(self) -> None:
        for field, value in (
            ("providerRequests", 1),
            ("inputTokens", 1),
            ("outputTokens", 1),
            ("estimatedCost", "0.01"),
        ):
            with self.subTest(field=field):
                payload = _copy(self.report_payload)
                assert isinstance(payload, dict)
                payload["usage"][field] = value
                decision = self.decision(report_payload=payload)
                self.assertIn(
                    "NONZERO_OR_UNPRICED_USAGE_REJECTED",
                    decision.blockers,
                )
                self.assertIn("REPORT_INVALID", decision.blockers)

    def test_simulated_latency_cannot_become_production_evidence(self) -> None:
        report_payload = _copy(self.report_payload)
        assert isinstance(report_payload, dict)
        report_payload["simulatedLatency"]["productionSloEligible"] = True
        report_decision = self.decision(report_payload=report_payload)

        inputs = _gate_input_payload(self.report)
        inputs["productionEvidence"]["latency"] = _production_evidence(
            "LATENCY",
            source="OFFLINE_SIMULATED",
        )
        evidence_decision = self.decision(gate_payload=inputs)

        self.assertIn(
            "SIMULATED_LATENCY_PRODUCTION_CLAIM_REJECTED",
            report_decision.blockers,
        )
        self.assertIn(
            "PRODUCTION_LATENCY_OFFLINE_EVIDENCE_REJECTED",
            evidence_decision.blockers,
        )

    def test_kill_switch_has_first_blocker_precedence(self) -> None:
        inputs = _gate_input_payload(
            self.report,
            switches=_switches(global_kill=True),
        )
        decision = self.decision(gate_payload=inputs)

        self.assertEqual("GLOBAL_KILL_SWITCH_ENGAGED", decision.blockers[0])
        self.assertEqual("BLOCKED", decision.decision)
        self.assertFalse(decision.release_authorized)

        component = self.decision(
            gate_payload=_gate_input_payload(
                self.report,
                switches=_switches(kill="providerExecution"),
            )
        )
        self.assertIn(
            "PROVIDER_EXECUTION_KILL_SWITCH_ENGAGED",
            component.blockers,
        )

    def test_default_off_unknown_evidence_and_missing_approvals_block(self) -> None:
        inputs = load_gate_input(DEFAULT_GATE_INPUT)
        decision = evaluate_release_gate(self.report_payload, inputs)

        for blocker in (
            "PROPOSAL_CAPABILITY_DEFAULT_OFF",
            "MEDIA_TOOL_DEFAULT_OFF",
            "PROVIDER_EXECUTION_DEFAULT_OFF",
            "AGENT_API_DEFAULT_OFF",
            "GATEWAY_DEFAULT_OFF",
            "FRONTEND_DEFAULT_OFF",
            "PRODUCTION_QUALITY_UNKNOWN",
            "PRODUCTION_LATENCY_UNKNOWN",
            "PRODUCTION_COST_UNKNOWN",
            "PRIVACY_APPROVAL_MISSING",
            "POLICY_APPROVAL_MISSING",
            "INTERNAL_ROLLOUT_APPROVAL_MISSING",
        ):
            with self.subTest(blocker=blocker):
                self.assertIn(blocker, decision.blockers)

    def test_observability_is_low_cardinality_and_offline_honest(self) -> None:
        dashboard = build_offline_observability(self.report)
        names = {metric.name for metric in dashboard.metrics}
        metrics = {metric.name.value: metric for metric in dashboard.metrics}

        self.assertEqual(len(names), len(dashboard.metrics))
        self.assertTrue(
            all(
                metric.evaluation_mode == "DETERMINISTIC_OFFLINE_FAKE"
                and metric.scope == "LISTING_PROPOSAL"
                for metric in dashboard.metrics
            )
        )
        self.assertEqual("UNKNOWN", dashboard.production_quality_status)
        self.assertEqual("UNKNOWN", dashboard.production_latency_status)
        self.assertEqual("UNKNOWN", dashboard.production_cost_status)
        self.assertFalse(dashboard.pricing_approved)
        self.assertEqual(
            1,
            metrics["fake_media_failure_coverage_ratio"].value,
        )
        self.assertEqual(
            1,
            metrics["fake_vision_failure_coverage_ratio"].value,
        )
        self.assertEqual(0, metrics["offline_provider_requests"].value)
        self.assertEqual(0, metrics["offline_input_tokens"].value)
        self.assertEqual(0, metrics["offline_output_tokens"].value)
        self.assertEqual("UNKNOWN", metrics["simulated_latency_p95_ms"].status)

    def test_decision_is_deterministic_and_excludes_sensitive_content(self) -> None:
        first = self.decision()
        second = self.decision()
        serialized = _pretty_json(first)

        self.assertEqual(serialized, _pretty_json(second))
        self.assertNotIn("owner@example.com", serialized)
        self.assertNotIn("Ignore previous instructions", serialized)
        self.assertNotIn("01ARZ3NDEKTSV4RRFFQ69G5FAA", serialized)
        self.assertNotIn("sk-proj-", serialized)
        self.assertNotIn("Bearer ", serialized)


class ListingReleaseGateCliTest(unittest.TestCase):
    def test_cli_emits_strict_schemas_and_default_blocked_decision(self) -> None:
        for schema_name in ("input", "decision", "observability"):
            with self.subTest(schema=schema_name):
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(0, main(["--schema", schema_name]))
                schema = json.loads(output.getvalue())
                self.assertEqual(False, schema["additionalProperties"])

        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(
                1,
                main(["--inputs", str(DEFAULT_GATE_INPUT)]),
            )
        decision = json.loads(output.getvalue())
        self.assertEqual("BLOCKED", decision["decision"])
        self.assertFalse(decision["releaseAuthorized"])

    def test_cli_maps_malformed_report_to_safe_blocked_decision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "malformed.json"
            report_path.write_text("{not-json", encoding="utf-8")
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(
                    1,
                    main(
                        [
                            "--inputs",
                            str(DEFAULT_GATE_INPUT),
                            "--report",
                            str(report_path),
                        ]
                    ),
                )
        decision = json.loads(output.getvalue())
        self.assertIn("REPORT_MALFORMED", decision["blockers"])


if __name__ == "__main__":
    unittest.main()
