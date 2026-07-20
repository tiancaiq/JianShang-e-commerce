from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from pathlib import Path
from typing import Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

from .customer_service_evaluation import (
    REPORT_SCHEMA_VERSION,
    RUNNER_VERSION,
    OfflineEvaluationReport,
)
from .customer_service_orchestration import ListingCustomerServiceOrchestrator

GATE_INPUT_SCHEMA_VERSION = "ai-cs-release-gate-input-v1"
GATE_DECISION_SCHEMA_VERSION = "ai-cs-release-gate-decision-v1"
OBSERVABILITY_SCHEMA_VERSION = "ai-cs-offline-observability-v1"
EXPECTED_FIXTURE_VERSION = "ai-cs-01e-a-fixture-v1"
MAXIMUM_REPORT_AGE = timedelta(days=7)
_EVIDENCE_ID_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9._:-]{2,79}$")
_DEPENDENCY_CASES = {
    "TOOL": {"malformed-search-safe-failure"},
    "MODEL": {
        "provider-unavailable-safe-failure",
        "provider-rate-limit-safe-failure",
        "provider-timeout-safe-failure",
        "invalid-provider-citation-safe-failure",
    },
    "OPENSEARCH": {"index-unavailable-safe-failure"},
}
_ROLLBACK_TRIGGERS = (
    "KILL_SWITCH_ENGAGED",
    "QUALITY_THRESHOLD_REGRESSION",
    "CITATION_OR_ISOLATION_REGRESSION",
    "STALE_OR_DELETED_SOURCE_LEAK",
    "DEPENDENCY_FAILURE_BUDGET_EXCEEDED",
    "PRODUCTION_LATENCY_EVIDENCE_EXPIRED",
    "PRODUCTION_COST_EVIDENCE_EXPIRED",
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class _StrictModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=_to_camel,
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )


class RolloutStage(StrEnum):
    INTERNAL = "INTERNAL"
    SMALL_COHORT = "SMALL_COHORT"
    WIDER = "WIDER"


class GateStatus(StrEnum):
    PASS = "PASS"
    BLOCKED = "BLOCKED"
    UNKNOWN = "UNKNOWN"


class SwitchState(_StrictModel):
    enabled: bool
    kill_switch_engaged: bool


class ReleaseSwitchStates(_StrictModel):
    customer_service_capability: SwitchState
    provider_execution: SwitchState
    retrieval_activation: SwitchState
    gateway_exposure: SwitchState
    frontend_entry_point: SwitchState


class ApprovalEvidence(_StrictModel):
    approved: bool
    external_evidence_id: str | None = Field(
        default=None,
        pattern=r"^[A-Z0-9][A-Z0-9._:-]{2,79}$",
    )
    approved_at: datetime | None = None
    expires_at: datetime | None = None

    @field_validator("approved_at", "expires_at")
    @classmethod
    def timezone_aware(
        cls,
        value: datetime | None,
    ) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("approval timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def explicit_and_bounded(self) -> "ApprovalEvidence":
        fields = (
            self.external_evidence_id,
            self.approved_at,
            self.expires_at,
        )
        if self.approved:
            if any(value is None for value in fields):
                raise ValueError(
                    "approved gates require external evidence and validity timestamps"
                )
            assert self.approved_at is not None
            assert self.expires_at is not None
            if self.expires_at <= self.approved_at:
                raise ValueError("approval expiry must be after approval time")
        elif any(value is not None for value in fields):
            raise ValueError("unapproved gates cannot carry approval evidence")
        return self


class ReleaseApprovals(_StrictModel):
    production_quality: ApprovalEvidence
    production_latency: ApprovalEvidence
    production_cost: ApprovalEvidence
    internal_rollout: ApprovalEvidence
    small_cohort_rollout: ApprovalEvidence
    wider_rollout: ApprovalEvidence


class ReleaseGateInput(_StrictModel):
    schema_version: Literal["ai-cs-release-gate-input-v1"]
    decision_at: datetime
    target_stage: RolloutStage
    report_generated_at: datetime | None
    report_evidence_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    switches: ReleaseSwitchStates
    approvals: ReleaseApprovals

    @field_validator("decision_at", "report_generated_at")
    @classmethod
    def timezone_aware(
        cls,
        value: datetime | None,
    ) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("gate timestamps must include a UTC offset")
        return value


class ObservabilityMetricName(StrEnum):
    RETRIEVAL_PRECISION = "retrieval_precision_ratio"
    RETRIEVAL_RECALL = "retrieval_recall_ratio"
    ANSWER_FAITHFULNESS = "answer_faithfulness_ratio"
    CITATION_VALIDITY = "citation_validity_ratio"
    CITATION_COMPLETENESS = "citation_completeness_ratio"
    ACTOR_ISOLATION = "actor_isolation_ratio"
    REJECTED_SOURCE_SAFETY = "rejected_stale_deleted_source_ratio"
    TOOL_BOUNDARY = "allowlisted_tool_boundary_ratio"
    FAKE_FAILURE_HANDLING = "fake_failure_handling_ratio"
    FAKE_DEPENDENCY_COVERAGE = "fake_dependency_failure_coverage_ratio"
    SIMULATED_LATENCY_P95 = "simulated_latency_p95_ms"
    OFFLINE_INPUT_TOKENS = "offline_input_tokens"
    OFFLINE_OUTPUT_TOKENS = "offline_output_tokens"
    OFFLINE_ESTIMATED_COST = "offline_estimated_cost_unpriced"


class MetricEvidenceKind(StrEnum):
    OFFLINE_MEASURED = "OFFLINE_MEASURED"
    SIMULATED = "SIMULATED"
    ZERO_COST_METADATA = "ZERO_COST_METADATA"


class ObservabilityMetric(_StrictModel):
    name: ObservabilityMetricName
    value: Decimal | None = Field(default=None, ge=0)
    numerator: int | None = Field(default=None, ge=0)
    denominator: int | None = Field(default=None, gt=0)
    unit: Literal["RATIO", "MILLISECONDS", "TOKENS", "UNPRICED_COST"]
    status: GateStatus
    evidence_kind: MetricEvidenceKind
    evaluation_mode: Literal["OFFLINE"] = "OFFLINE"
    source_scope: Literal["LISTING"] = "LISTING"
    dependency: Literal["NONE", "TOOL", "MODEL", "OPENSEARCH"] = "NONE"

    @model_validator(mode="after")
    def ratio_is_internally_consistent(self) -> "ObservabilityMetric":
        if (self.numerator is None) != (self.denominator is None):
            raise ValueError("metric numerator and denominator must be paired")
        if self.numerator is not None and self.denominator is not None:
            if self.numerator > self.denominator:
                raise ValueError("metric numerator cannot exceed denominator")
            expected = (
                Decimal(self.numerator) / Decimal(self.denominator)
            ).quantize(Decimal("0.0001"))
            if self.value != expected:
                raise ValueError("ratio value must match numerator / denominator")
        return self


class OfflineObservabilityReport(_StrictModel):
    schema_version: Literal["ai-cs-offline-observability-v1"]
    evaluation_report_schema_version: Literal[
        "ai-cs-offline-evaluation-report-v1"
    ]
    runner_version: str
    fixture_version: str
    seed: int
    metrics: tuple[ObservabilityMetric, ...] = Field(min_length=1)
    production_latency_status: Literal["UNKNOWN"]
    production_cost_status: Literal["UNKNOWN"]
    pricing_approved: Literal[False]

    @model_validator(mode="after")
    def has_fixed_low_cardinality_metric_contract(
        self,
    ) -> "OfflineObservabilityReport":
        identities = [(metric.name, metric.dependency) for metric in self.metrics]
        dependency_identities = {
            (ObservabilityMetricName.FAKE_DEPENDENCY_COVERAGE, dependency)
            for dependency in ("TOOL", "MODEL", "OPENSEARCH")
        }
        non_dependency_identities = {
            (name, "NONE")
            for name in ObservabilityMetricName
            if name != ObservabilityMetricName.FAKE_DEPENDENCY_COVERAGE
        }
        expected_identities = dependency_identities | non_dependency_identities
        if (
            set(identities) != expected_identities
            or len(identities) != len(expected_identities)
        ):
            raise ValueError(
                "observability report requires one entry per bounded metric identity"
            )
        if {
            dependency
            for name, dependency in identities
            if name == ObservabilityMetricName.FAKE_DEPENDENCY_COVERAGE
        } != {
            "TOOL",
            "MODEL",
            "OPENSEARCH",
        }:
            raise ValueError("dependency coverage requires three bounded labels")
        return self


class GateName(StrEnum):
    CUSTOMER_SERVICE_SWITCH = "CUSTOMER_SERVICE_SWITCH"
    PROVIDER_SWITCH = "PROVIDER_SWITCH"
    RETRIEVAL_SWITCH = "RETRIEVAL_SWITCH"
    GATEWAY_SWITCH = "GATEWAY_SWITCH"
    FRONTEND_SWITCH = "FRONTEND_SWITCH"
    REPORT_PRESENT = "REPORT_PRESENT"
    REPORT_SCHEMA = "REPORT_SCHEMA"
    REPORT_VALID = "REPORT_VALID"
    REPORT_INTEGRITY = "REPORT_INTEGRITY"
    REPORT_FRESHNESS = "REPORT_FRESHNESS"
    REPORT_VERSION = "REPORT_VERSION"
    QUALITY_THRESHOLDS = "QUALITY_THRESHOLDS"
    PRODUCTION_QUALITY_APPROVAL = "PRODUCTION_QUALITY_APPROVAL"
    PRODUCTION_LATENCY_APPROVAL = "PRODUCTION_LATENCY_APPROVAL"
    PRODUCTION_COST_APPROVAL = "PRODUCTION_COST_APPROVAL"
    ROLLOUT_APPROVAL = "ROLLOUT_APPROVAL"


class GateResult(_StrictModel):
    gate: GateName
    status: GateStatus
    reason_code: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,79}$")


class ReleaseGateDecision(_StrictModel):
    schema_version: Literal["ai-cs-release-gate-decision-v1"]
    input_schema_version: Literal["ai-cs-release-gate-input-v1"]
    decision_at: datetime
    target_stage: RolloutStage
    decision: Literal["BLOCKED", "READY"]
    blockers: tuple[str, ...]
    gates: tuple[GateResult, ...]
    observability: OfflineObservabilityReport | None
    rollback_triggers: tuple[str, ...]

    @model_validator(mode="after")
    def decision_matches_gate_results(self) -> "ReleaseGateDecision":
        gate_names = [gate.gate for gate in self.gates]
        if set(gate_names) != set(GateName) or len(gate_names) != len(GateName):
            raise ValueError("decision requires exactly one result per release gate")
        expected_blockers = tuple(
            gate.reason_code
            for gate in self.gates
            if gate.status != GateStatus.PASS
        )
        if self.blockers != expected_blockers:
            raise ValueError("decision blockers must match non-passing gates")
        expected_decision = "READY" if not expected_blockers else "BLOCKED"
        if self.decision != expected_decision:
            raise ValueError("decision must match gate results")
        if self.rollback_triggers != _ROLLBACK_TRIGGERS:
            raise ValueError("rollback trigger contract is incomplete")
        return self


def report_evidence_sha256(
    report: OfflineEvaluationReport,
    generated_at: datetime,
) -> str:
    """Bind the canonical report to explicit deterministic freshness evidence."""

    if generated_at.tzinfo is None or generated_at.utcoffset() is None:
        raise ValueError("report generation time must include a UTC offset")
    payload = (
        _canonical_json(report)
        + "\n"
        + generated_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def build_offline_observability(
    report: OfflineEvaluationReport,
) -> OfflineObservabilityReport:
    """Project only bounded aggregate offline metrics for dashboards."""

    metric_map = (
        (
            ObservabilityMetricName.RETRIEVAL_PRECISION,
            "retrieval_relevance",
        ),
        (ObservabilityMetricName.RETRIEVAL_RECALL, "retrieval_recall"),
        (
            ObservabilityMetricName.ANSWER_FAITHFULNESS,
            "answer_faithfulness",
        ),
        (ObservabilityMetricName.CITATION_VALIDITY, "citation_validity"),
        (
            ObservabilityMetricName.CITATION_COMPLETENESS,
            "citation_completeness",
        ),
        (ObservabilityMetricName.ACTOR_ISOLATION, "cross_user_isolation"),
        (
            ObservabilityMetricName.REJECTED_SOURCE_SAFETY,
            "rejected_source_safety",
        ),
        (
            ObservabilityMetricName.TOOL_BOUNDARY,
            "allowlisted_tool_boundary",
        ),
        (
            ObservabilityMetricName.FAKE_FAILURE_HANDLING,
            "failure_handling",
        ),
    )
    metrics: list[ObservabilityMetric] = []
    threshold_status = {
        threshold.metric: threshold.passed for threshold in report.thresholds
    }
    for output_name, report_name in metric_map:
        value = report.metrics[report_name]
        metrics.append(
            ObservabilityMetric(
                name=output_name,
                value=value.value,
                numerator=value.numerator,
                denominator=value.denominator,
                unit="RATIO",
                status=(
                    GateStatus.PASS
                    if threshold_status.get(report_name, False)
                    else GateStatus.BLOCKED
                ),
                evidenceKind=MetricEvidenceKind.OFFLINE_MEASURED,
            )
        )
    case_results = {case.case_id: case for case in report.case_results}
    for dependency, expected_cases in _DEPENDENCY_CASES.items():
        passed = sum(
            case_results.get(case_id) is not None
            and case_results[case_id].passed
            for case_id in expected_cases
        )
        metrics.append(
            ObservabilityMetric(
                name=ObservabilityMetricName.FAKE_DEPENDENCY_COVERAGE,
                value=(
                    Decimal(passed) / Decimal(len(expected_cases))
                ).quantize(Decimal("0.0001")),
                numerator=passed,
                denominator=len(expected_cases),
                unit="RATIO",
                status=(
                    GateStatus.PASS
                    if passed == len(expected_cases)
                    else GateStatus.BLOCKED
                ),
                evidenceKind=MetricEvidenceKind.OFFLINE_MEASURED,
                dependency=dependency,
            )
        )
    latency_passed = threshold_status.get(
        "simulated_latency_p95_ms",
        False,
    )
    metrics.extend(
        (
            ObservabilityMetric(
                name=ObservabilityMetricName.SIMULATED_LATENCY_P95,
                value=Decimal(report.simulated_latency_p95_ms),
                unit="MILLISECONDS",
                status=(
                    GateStatus.PASS if latency_passed else GateStatus.BLOCKED
                ),
                evidenceKind=MetricEvidenceKind.SIMULATED,
            ),
            ObservabilityMetric(
                name=ObservabilityMetricName.OFFLINE_INPUT_TOKENS,
                value=Decimal(report.cost.input_tokens),
                unit="TOKENS",
                status=GateStatus.UNKNOWN,
                evidenceKind=MetricEvidenceKind.ZERO_COST_METADATA,
            ),
            ObservabilityMetric(
                name=ObservabilityMetricName.OFFLINE_OUTPUT_TOKENS,
                value=Decimal(report.cost.output_tokens),
                unit="TOKENS",
                status=GateStatus.UNKNOWN,
                evidenceKind=MetricEvidenceKind.ZERO_COST_METADATA,
            ),
            ObservabilityMetric(
                name=ObservabilityMetricName.OFFLINE_ESTIMATED_COST,
                value=report.cost.estimated_cost,
                unit="UNPRICED_COST",
                status=GateStatus.UNKNOWN,
                evidenceKind=MetricEvidenceKind.ZERO_COST_METADATA,
            ),
        )
    )
    return OfflineObservabilityReport(
        schemaVersion=OBSERVABILITY_SCHEMA_VERSION,
        evaluationReportSchemaVersion=report.schema_version,
        runnerVersion=report.runner_version,
        fixtureVersion=report.fixture_version,
        seed=report.seed,
        metrics=metrics,
        productionLatencyStatus="UNKNOWN",
        productionCostStatus="UNKNOWN",
        pricingApproved=False,
    )


def evaluate_release_gate(
    report_payload: object | None,
    gate_input: ReleaseGateInput,
) -> ReleaseGateDecision:
    """Return READY only when every deterministic and external gate passes."""

    gates = _switch_gates(gate_input.switches)
    report: OfflineEvaluationReport | None = None
    observability: OfflineObservabilityReport | None = None
    if report_payload is None:
        gates.extend(
            (
                _blocked(GateName.REPORT_PRESENT, "REPORT_MISSING"),
                _unknown(GateName.REPORT_SCHEMA, "REPORT_UNAVAILABLE"),
                _unknown(GateName.REPORT_VALID, "REPORT_UNAVAILABLE"),
                _unknown(GateName.REPORT_INTEGRITY, "REPORT_UNAVAILABLE"),
                _unknown(GateName.REPORT_FRESHNESS, "REPORT_UNAVAILABLE"),
                _unknown(GateName.REPORT_VERSION, "REPORT_UNAVAILABLE"),
                _unknown(GateName.QUALITY_THRESHOLDS, "REPORT_UNAVAILABLE"),
            )
        )
    else:
        gates.append(_passed(GateName.REPORT_PRESENT))
        raw_schema = (
            report_payload.get("schemaVersion")
            if isinstance(report_payload, dict)
            else None
        )
        if raw_schema != REPORT_SCHEMA_VERSION:
            gates.extend(
                (
                    _blocked(
                        GateName.REPORT_SCHEMA,
                        "REPORT_SCHEMA_INCOMPATIBLE",
                    ),
                    _unknown(GateName.REPORT_VALID, "REPORT_SCHEMA_INCOMPATIBLE"),
                    _unknown(
                        GateName.REPORT_INTEGRITY,
                        "REPORT_SCHEMA_INCOMPATIBLE",
                    ),
                    _unknown(
                        GateName.REPORT_FRESHNESS,
                        "REPORT_SCHEMA_INCOMPATIBLE",
                    ),
                    _unknown(
                        GateName.REPORT_VERSION,
                        "REPORT_SCHEMA_INCOMPATIBLE",
                    ),
                    _unknown(
                        GateName.QUALITY_THRESHOLDS,
                        "REPORT_SCHEMA_INCOMPATIBLE",
                    ),
                )
            )
        else:
            gates.append(_passed(GateName.REPORT_SCHEMA))
            try:
                report = OfflineEvaluationReport.model_validate(report_payload)
            except ValidationError:
                gates.extend(
                    (
                        _blocked(GateName.REPORT_VALID, "REPORT_INVALID"),
                        _unknown(GateName.REPORT_INTEGRITY, "REPORT_INVALID"),
                        _unknown(GateName.REPORT_FRESHNESS, "REPORT_INVALID"),
                        _unknown(GateName.REPORT_VERSION, "REPORT_INVALID"),
                        _unknown(GateName.QUALITY_THRESHOLDS, "REPORT_INVALID"),
                    )
                )
            else:
                gates.append(_passed(GateName.REPORT_VALID))
                observability = build_offline_observability(report)
                gates.append(_integrity_gate(report, gate_input))
                gates.append(_freshness_gate(gate_input))
                gates.append(_version_gate(report))
                gates.append(_quality_gate(report, observability))

    gates.extend(
        (
            _approval_gate(
                GateName.PRODUCTION_QUALITY_APPROVAL,
                gate_input.approvals.production_quality,
                gate_input.decision_at,
                "PRODUCTION_QUALITY",
            ),
            _approval_gate(
                GateName.PRODUCTION_LATENCY_APPROVAL,
                gate_input.approvals.production_latency,
                gate_input.decision_at,
                "PRODUCTION_LATENCY",
            ),
            _approval_gate(
                GateName.PRODUCTION_COST_APPROVAL,
                gate_input.approvals.production_cost,
                gate_input.decision_at,
                "PRODUCTION_COST",
            ),
            _rollout_gate(gate_input),
        )
    )
    blockers = tuple(
        gate.reason_code for gate in gates if gate.status != GateStatus.PASS
    )
    return ReleaseGateDecision(
        schemaVersion=GATE_DECISION_SCHEMA_VERSION,
        inputSchemaVersion=gate_input.schema_version,
        decisionAt=gate_input.decision_at,
        targetStage=gate_input.target_stage,
        decision="READY" if not blockers else "BLOCKED",
        blockers=blockers,
        gates=gates,
        observability=observability,
        rollbackTriggers=_ROLLBACK_TRIGGERS,
    )


def load_gate_input(path: Path) -> ReleaseGateInput:
    """Load explicit switches and approvals without consulting environment state."""

    return ReleaseGateInput.model_validate_json(path.read_text(encoding="utf-8"))


def _switch_gates(states: ReleaseSwitchStates) -> list[GateResult]:
    definitions = (
        (
            GateName.CUSTOMER_SERVICE_SWITCH,
            states.customer_service_capability,
            "CUSTOMER_SERVICE",
        ),
        (GateName.PROVIDER_SWITCH, states.provider_execution, "PROVIDER"),
        (GateName.RETRIEVAL_SWITCH, states.retrieval_activation, "RETRIEVAL"),
        (GateName.GATEWAY_SWITCH, states.gateway_exposure, "GATEWAY"),
        (GateName.FRONTEND_SWITCH, states.frontend_entry_point, "FRONTEND"),
    )
    results: list[GateResult] = []
    for gate, state, prefix in definitions:
        if state.kill_switch_engaged:
            results.append(_blocked(gate, f"{prefix}_KILL_SWITCH_ENGAGED"))
        elif not state.enabled:
            results.append(_blocked(gate, f"{prefix}_DEFAULT_OFF"))
        else:
            results.append(_passed(gate))
    return results


def _integrity_gate(
    report: OfflineEvaluationReport,
    gate_input: ReleaseGateInput,
) -> GateResult:
    if (
        gate_input.report_generated_at is None
        or gate_input.report_evidence_sha256 is None
    ):
        return _unknown(GateName.REPORT_INTEGRITY, "REPORT_EVIDENCE_MISSING")
    actual = report_evidence_sha256(report, gate_input.report_generated_at)
    if actual != gate_input.report_evidence_sha256:
        return _blocked(GateName.REPORT_INTEGRITY, "REPORT_DIGEST_MISMATCH")
    return _passed(GateName.REPORT_INTEGRITY)


def _freshness_gate(gate_input: ReleaseGateInput) -> GateResult:
    generated_at = gate_input.report_generated_at
    if generated_at is None:
        return _unknown(GateName.REPORT_FRESHNESS, "REPORT_TIMESTAMP_MISSING")
    if generated_at > gate_input.decision_at:
        return _blocked(GateName.REPORT_FRESHNESS, "REPORT_TIMESTAMP_IN_FUTURE")
    if gate_input.decision_at - generated_at > MAXIMUM_REPORT_AGE:
        return _blocked(GateName.REPORT_FRESHNESS, "REPORT_STALE")
    return _passed(GateName.REPORT_FRESHNESS)


def _version_gate(report: OfflineEvaluationReport) -> GateResult:
    expected = (
        report.runner_version == RUNNER_VERSION
        and report.fixture_version == EXPECTED_FIXTURE_VERSION
        and report.prompt_version == ListingCustomerServiceOrchestrator.PROMPT_VERSION
        and report.tool_registry_version
        == ListingCustomerServiceOrchestrator.TOOL_REGISTRY_VERSION
        and report.policy_version == ListingCustomerServiceOrchestrator.POLICY_VERSION
    )
    if not expected:
        return _blocked(GateName.REPORT_VERSION, "REPORT_VERSION_MISMATCH")
    return _passed(GateName.REPORT_VERSION)


def _quality_gate(
    report: OfflineEvaluationReport,
    observability: OfflineObservabilityReport,
) -> GateResult:
    offline_quality_metrics = [
        metric
        for metric in observability.metrics
        if metric.evidence_kind == MetricEvidenceKind.OFFLINE_MEASURED
        or metric.evidence_kind == MetricEvidenceKind.SIMULATED
    ]
    if (
        report.baseline_status != "PASS"
        or report.baseline_failure_reasons
        or not all(threshold.passed for threshold in report.thresholds)
        or not all(
            metric.status == GateStatus.PASS
            for metric in offline_quality_metrics
        )
    ):
        return _blocked(
            GateName.QUALITY_THRESHOLDS,
            "QUALITY_THRESHOLD_REGRESSION",
        )
    return _passed(GateName.QUALITY_THRESHOLDS)


def _approval_gate(
    gate: GateName,
    evidence: ApprovalEvidence,
    decision_at: datetime,
    prefix: str,
) -> GateResult:
    if not evidence.approved:
        return _unknown(gate, f"{prefix}_APPROVAL_MISSING")
    assert evidence.approved_at is not None
    assert evidence.expires_at is not None
    assert evidence.external_evidence_id is not None
    if evidence.approved_at > decision_at:
        return _blocked(gate, f"{prefix}_APPROVAL_NOT_YET_VALID")
    if evidence.expires_at <= decision_at:
        return _blocked(gate, f"{prefix}_APPROVAL_EXPIRED")
    if _EVIDENCE_ID_PATTERN.fullmatch(evidence.external_evidence_id) is None:
        return _blocked(gate, f"{prefix}_EVIDENCE_INVALID")
    return _passed(gate)


def _rollout_gate(gate_input: ReleaseGateInput) -> GateResult:
    required = [gate_input.approvals.internal_rollout]
    if gate_input.target_stage in {
        RolloutStage.SMALL_COHORT,
        RolloutStage.WIDER,
    }:
        required.append(gate_input.approvals.small_cohort_rollout)
    if gate_input.target_stage == RolloutStage.WIDER:
        required.append(gate_input.approvals.wider_rollout)
    names = ("INTERNAL", "SMALL_COHORT", "WIDER")
    for name, evidence in zip(names[: len(required)], required, strict=True):
        result = _approval_gate(
            GateName.ROLLOUT_APPROVAL,
            evidence,
            gate_input.decision_at,
            f"{name}_ROLLOUT",
        )
        if result.status != GateStatus.PASS:
            return result
    return _passed(GateName.ROLLOUT_APPROVAL)


def _passed(gate: GateName) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.PASS, reasonCode="PASSED")


def _blocked(gate: GateName, reason: str) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.BLOCKED, reasonCode=reason)


def _unknown(gate: GateName, reason: str) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.UNKNOWN, reasonCode=reason)


def _canonical_json(model: BaseModel) -> str:
    return json.dumps(
        model.model_dump(mode="json", by_alias=True),
        sort_keys=True,
        separators=(",", ":"),
    )


def _pretty_json(model: BaseModel) -> str:
    return json.dumps(
        model.model_dump(mode="json", by_alias=True),
        indent=2,
        sort_keys=True,
    )


def main(argv: list[str] | None = None) -> int:
    """Evaluate one explicit report/input pair without reading runtime state."""

    parser = argparse.ArgumentParser(description="Evaluate AI-CS release gates")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument(
        "--schema",
        choices=("input", "decision", "observability"),
    )
    arguments = parser.parse_args(argv)
    if arguments.schema:
        schema_model = {
            "input": ReleaseGateInput,
            "decision": ReleaseGateDecision,
            "observability": OfflineObservabilityReport,
        }[arguments.schema]
        print(
            json.dumps(
                schema_model.model_json_schema(by_alias=True),
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    if arguments.inputs is None:
        parser.error("--inputs is required unless --schema is used")
    report_payload: object | None = None
    if arguments.report is not None:
        report_payload = json.loads(arguments.report.read_text(encoding="utf-8"))
    decision = evaluate_release_gate(
        report_payload,
        load_gate_input(arguments.inputs),
    )
    print(_pretty_json(decision))
    return 0 if decision.decision == "READY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
