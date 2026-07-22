from __future__ import annotations

import argparse
import json
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from pathlib import Path
from typing import Literal

from pydantic import (
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

from .listing_proposal_evaluation import (
    REPORT_SCHEMA_VERSION,
    RUNNER_VERSION,
    OfflineProposalReport,
    ProposalEvaluationScenario,
    fixture_evidence_sha256,
    load_offline_fixture,
)
from .listing_proposal_evidence_contract import (
    EvidenceFreshnessIssue,
    MAXIMUM_OFFLINE_EVIDENCE_AGE,
    StrictEvidenceModel as _StrictModel,
    decimal_or_none as _decimal,
    evidence_freshness_issues,
    pretty_json as _pretty_json,
    timestamped_model_evidence_sha256,
)

GATE_INPUT_SCHEMA_VERSION = "ai-list-offline-release-gate-input-v1"
GATE_DECISION_SCHEMA_VERSION = "ai-list-offline-release-gate-decision-v1"
OBSERVABILITY_SCHEMA_VERSION = "ai-list-offline-observability-v1"
EXPECTED_FIXTURE_VERSION = "ai-list-eval-p0-01-fixture-v1"
MAXIMUM_REPORT_AGE = MAXIMUM_OFFLINE_EVIDENCE_AGE
DEFAULT_GATE_INPUT = (
    Path(__file__).resolve().parents[2]
    / "evals"
    / "ai_list_eval_p0_02_release_gate_default_blocked_v1.json"
)

_EXPECTED_METRICS = frozenset(
    {
        "strict_schema_conformance",
        "evidence_unknown_consistency",
        "protected_field_rejection",
        "privacy_redaction",
        "injection_resistance",
        "replay_determinism",
        "dependency_failure_handling",
        "outcome_conformance",
    }
)
_EXPECTED_VERSIONS = {
    "schemaVersion": REPORT_SCHEMA_VERSION,
    "runnerVersion": RUNNER_VERSION,
    "fixtureVersion": EXPECTED_FIXTURE_VERSION,
    "proposalSchemaVersion": "ai-list-proposal-v1",
    "toolRegistryVersion": "owned-listing-media-read-v1",
    "instructionVersion": "listing-proposal-vision-v1",
}
_ROLLBACK_TRIGGERS = (
    "GLOBAL_OR_CAPABILITY_KILL_SWITCH_ENGAGED",
    "PROPOSAL_SCHEMA_OR_THRESHOLD_REGRESSION",
    "EVIDENCE_OR_UNKNOWN_FIELD_REGRESSION",
    "PRIVACY_OR_INJECTION_REGRESSION",
    "REPLAY_IDEMPOTENCY_REGRESSION",
    "MEDIA_OR_VISION_FAILURE_REGRESSION",
    "REPORT_INTEGRITY_OR_FRESHNESS_FAILURE",
    "PRODUCTION_EVIDENCE_OR_APPROVAL_EXPIRED",
)
class RolloutStage(StrEnum):
    INTERNAL = "INTERNAL"
    SMALL_COHORT = "SMALL_COHORT"
    WIDER = "WIDER"


class GateStatus(StrEnum):
    PASS = "PASS"
    BLOCKED = "BLOCKED"
    UNKNOWN = "UNKNOWN"


class EvidenceStatus(StrEnum):
    UNKNOWN = "UNKNOWN"
    APPROVED = "APPROVED"


class EvidenceSource(StrEnum):
    UNKNOWN = "UNKNOWN"
    EXTERNAL_PRODUCTION = "EXTERNAL_PRODUCTION"
    OFFLINE_SIMULATED = "OFFLINE_SIMULATED"


class SwitchState(_StrictModel):
    enabled: bool
    kill_switch_engaged: bool


class ReleaseSwitchStates(_StrictModel):
    global_kill_switch_engaged: bool
    proposal_capability: SwitchState
    media_tool: SwitchState
    provider_execution: SwitchState
    agent_api: SwitchState
    gateway_exposure: SwitchState
    frontend_entry: SwitchState


class ProductionEvidence(_StrictModel):
    status: EvidenceStatus
    source: EvidenceSource
    external_evidence_id: str | None = Field(
        default=None,
        pattern=r"^[A-Z0-9][A-Z0-9._:-]{2,79}$",
    )
    collected_at: datetime | None = None
    expires_at: datetime | None = None

    @field_validator("collected_at", "expires_at")
    @classmethod
    def timezone_aware(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("evidence timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def evidence_shape_is_explicit(self) -> "ProductionEvidence":
        details = (
            self.external_evidence_id,
            self.collected_at,
            self.expires_at,
        )
        if self.status == EvidenceStatus.UNKNOWN:
            if self.source != EvidenceSource.UNKNOWN or any(
                value is not None for value in details
            ):
                raise ValueError("unknown evidence cannot carry evidence details")
            return self
        if self.source == EvidenceSource.UNKNOWN or any(
            value is None for value in details
        ):
            raise ValueError("approved evidence requires source and validity")
        assert self.collected_at is not None
        assert self.expires_at is not None
        if self.expires_at <= self.collected_at:
            raise ValueError("evidence expiry must be after collection")
        return self


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
    def timezone_aware(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("approval timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def approval_shape_is_explicit(self) -> "ApprovalEvidence":
        details = (
            self.external_evidence_id,
            self.approved_at,
            self.expires_at,
        )
        if not self.approved:
            if any(value is not None for value in details):
                raise ValueError("unapproved gates cannot carry evidence")
            return self
        if any(value is None for value in details):
            raise ValueError("approved gates require evidence and validity")
        assert self.approved_at is not None
        assert self.expires_at is not None
        if self.expires_at <= self.approved_at:
            raise ValueError("approval expiry must be after approval time")
        return self


class ProductionEvidenceSet(_StrictModel):
    quality: ProductionEvidence
    latency: ProductionEvidence
    cost: ProductionEvidence


class ReleaseApprovals(_StrictModel):
    privacy: ApprovalEvidence
    policy: ApprovalEvidence
    internal_rollout: ApprovalEvidence
    small_cohort_rollout: ApprovalEvidence
    wider_rollout: ApprovalEvidence


class ListingReleaseGateInput(_StrictModel):
    schema_version: Literal["ai-list-offline-release-gate-input-v1"]
    decision_at: datetime
    target_stage: RolloutStage
    report_generated_at: datetime | None
    report_evidence_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    switches: ReleaseSwitchStates
    production_evidence: ProductionEvidenceSet
    approvals: ReleaseApprovals

    @field_validator("decision_at", "report_generated_at")
    @classmethod
    def timezone_aware(cls, value: datetime | None) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("gate timestamps must include a UTC offset")
        return value


class ObservabilityMetricName(StrEnum):
    SCHEMA_VALIDITY = "proposal_schema_validity_ratio"
    EVIDENCE_UNKNOWN = "evidence_unknown_consistency_ratio"
    PROTECTED_FIELDS = "protected_field_rejection_ratio"
    PRIVACY_REDACTION = "privacy_redaction_ratio"
    INJECTION_RESISTANCE = "injection_resistance_ratio"
    REPLAY_IDEMPOTENCY = "replay_idempotency_ratio"
    DEPENDENCY_FAILURE = "fake_dependency_failure_handling_ratio"
    OUTCOME_CONFORMANCE = "outcome_conformance_ratio"
    MEDIA_FAILURE_COVERAGE = "fake_media_failure_coverage_ratio"
    VISION_FAILURE_COVERAGE = "fake_vision_failure_coverage_ratio"
    SIMULATED_LATENCY_P95 = "simulated_latency_p95_ms"
    PROVIDER_REQUESTS = "offline_provider_requests"
    INPUT_TOKENS = "offline_input_tokens"
    OUTPUT_TOKENS = "offline_output_tokens"
    ESTIMATED_COST = "offline_estimated_cost_unpriced"


class MetricEvidenceKind(StrEnum):
    OFFLINE_MEASURED = "OFFLINE_MEASURED"
    SIMULATED = "SIMULATED"
    ZERO_USAGE_METADATA = "ZERO_USAGE_METADATA"


class ObservabilityMetric(_StrictModel):
    name: ObservabilityMetricName
    value: Decimal = Field(ge=0)
    numerator: int | None = Field(default=None, ge=0)
    denominator: int | None = Field(default=None, gt=0)
    unit: Literal["RATIO", "MILLISECONDS", "COUNT", "TOKENS", "UNPRICED_COST"]
    status: GateStatus
    evidence_kind: MetricEvidenceKind
    evaluation_mode: Literal["DETERMINISTIC_OFFLINE_FAKE"]
    scope: Literal["LISTING_PROPOSAL"]

    @model_validator(mode="after")
    def ratio_is_consistent(self) -> "ObservabilityMetric":
        if (self.numerator is None) != (self.denominator is None):
            raise ValueError("metric numerator and denominator must be paired")
        if self.numerator is not None and self.denominator is not None:
            if self.numerator > self.denominator:
                raise ValueError("metric numerator cannot exceed denominator")
            expected = (
                Decimal(self.numerator) / Decimal(self.denominator)
            ).quantize(Decimal("0.0000"))
            if self.value != expected or self.unit != "RATIO":
                raise ValueError("ratio metadata is inconsistent")
        return self


class ListingOfflineObservability(_StrictModel):
    schema_version: Literal["ai-list-offline-observability-v1"]
    evaluation_report_schema_version: Literal[
        "ai-list-offline-evaluation-report-v1"
    ]
    runner_version: Literal["ai-list-eval-p0-01-runner-v1"]
    fixture_version: Literal["ai-list-eval-p0-01-fixture-v1"]
    seed: int = Field(ge=0)
    metrics: tuple[ObservabilityMetric, ...] = Field(min_length=1)
    production_quality_status: Literal["UNKNOWN"]
    production_latency_status: Literal["UNKNOWN"]
    production_cost_status: Literal["UNKNOWN"]
    pricing_approved: Literal[False]

    @model_validator(mode="after")
    def metric_cardinality_is_fixed(self) -> "ListingOfflineObservability":
        names = [metric.name for metric in self.metrics]
        if set(names) != set(ObservabilityMetricName) or len(names) != len(
            ObservabilityMetricName
        ):
            raise ValueError("observability requires one bounded metric per name")
        return self


class GateName(StrEnum):
    GLOBAL_KILL_SWITCH = "GLOBAL_KILL_SWITCH"
    PROPOSAL_CAPABILITY = "PROPOSAL_CAPABILITY"
    MEDIA_TOOL = "MEDIA_TOOL"
    PROVIDER_EXECUTION = "PROVIDER_EXECUTION"
    AGENT_API = "AGENT_API"
    GATEWAY_EXPOSURE = "GATEWAY_EXPOSURE"
    FRONTEND_ENTRY = "FRONTEND_ENTRY"
    REPORT_PRESENT = "REPORT_PRESENT"
    REPORT_FORMAT = "REPORT_FORMAT"
    REPORT_SCHEMA = "REPORT_SCHEMA"
    REPORT_VALID = "REPORT_VALID"
    REPORT_INTEGRITY = "REPORT_INTEGRITY"
    REPORT_FRESHNESS = "REPORT_FRESHNESS"
    REPORT_VERSION = "REPORT_VERSION"
    REPORT_CASE_COUNT = "REPORT_CASE_COUNT"
    REPORT_METRICS = "REPORT_METRICS"
    REPORT_THRESHOLDS = "REPORT_THRESHOLDS"
    REPORT_FAILURE_COVERAGE = "REPORT_FAILURE_COVERAGE"
    OFFLINE_BASELINE = "OFFLINE_BASELINE"
    ZERO_PROVIDER_USAGE = "ZERO_PROVIDER_USAGE"
    SIMULATED_LATENCY_BOUNDARY = "SIMULATED_LATENCY_BOUNDARY"
    PRODUCTION_QUALITY_EVIDENCE = "PRODUCTION_QUALITY_EVIDENCE"
    PRODUCTION_LATENCY_EVIDENCE = "PRODUCTION_LATENCY_EVIDENCE"
    PRODUCTION_COST_EVIDENCE = "PRODUCTION_COST_EVIDENCE"
    PRIVACY_APPROVAL = "PRIVACY_APPROVAL"
    POLICY_APPROVAL = "POLICY_APPROVAL"
    ROLLOUT_APPROVAL = "ROLLOUT_APPROVAL"
    OFFLINE_RELEASE_AUTHORITY = "OFFLINE_RELEASE_AUTHORITY"


class GateResult(_StrictModel):
    gate: GateName
    status: GateStatus
    reason_code: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,95}$")


class ListingReleaseGateDecision(_StrictModel):
    schema_version: Literal["ai-list-offline-release-gate-decision-v1"]
    input_schema_version: Literal["ai-list-offline-release-gate-input-v1"]
    decision_at: datetime
    target_stage: RolloutStage
    decision: Literal["BLOCKED"]
    release_authorized: Literal[False]
    blockers: tuple[str, ...] = Field(min_length=1)
    gates: tuple[GateResult, ...]
    observability: ListingOfflineObservability | None
    rollback_triggers: tuple[str, ...]

    @model_validator(mode="after")
    def decision_is_complete_and_offline_blocked(
        self,
    ) -> "ListingReleaseGateDecision":
        if [gate.gate for gate in self.gates] != list(GateName):
            raise ValueError("decision requires every gate in precedence order")
        expected = tuple(
            gate.reason_code
            for gate in self.gates
            if gate.status != GateStatus.PASS
        )
        if self.blockers != expected:
            raise ValueError("blockers must match every non-passing gate")
        if (
            self.gates[-1].gate != GateName.OFFLINE_RELEASE_AUTHORITY
            or self.gates[-1].status != GateStatus.BLOCKED
        ):
            raise ValueError("offline evidence can never authorize rollout")
        if self.rollback_triggers != _ROLLBACK_TRIGGERS:
            raise ValueError("rollback trigger contract is incomplete")
        return self


def report_evidence_sha256(
    report: OfflineProposalReport,
    generated_at: datetime,
) -> str:
    """Bind a strict report to its explicit freshness timestamp."""

    return timestamped_model_evidence_sha256(report, generated_at)


def build_offline_observability(
    report: OfflineProposalReport,
) -> ListingOfflineObservability:
    """Project only fixed-cardinality aggregates from a validated report."""

    mappings = (
        (ObservabilityMetricName.SCHEMA_VALIDITY, "strict_schema_conformance"),
        (
            ObservabilityMetricName.EVIDENCE_UNKNOWN,
            "evidence_unknown_consistency",
        ),
        (
            ObservabilityMetricName.PROTECTED_FIELDS,
            "protected_field_rejection",
        ),
        (ObservabilityMetricName.PRIVACY_REDACTION, "privacy_redaction"),
        (
            ObservabilityMetricName.INJECTION_RESISTANCE,
            "injection_resistance",
        ),
        (ObservabilityMetricName.REPLAY_IDEMPOTENCY, "replay_determinism"),
        (
            ObservabilityMetricName.DEPENDENCY_FAILURE,
            "dependency_failure_handling",
        ),
        (
            ObservabilityMetricName.OUTCOME_CONFORMANCE,
            "outcome_conformance",
        ),
    )
    threshold_status = {
        threshold.metric: threshold.passed for threshold in report.thresholds
    }
    metrics = [
        ObservabilityMetric(
            name=output_name,
            value=report.metrics[source_name].value,
            numerator=report.metrics[source_name].numerator,
            denominator=report.metrics[source_name].denominator,
            unit="RATIO",
            status=(
                GateStatus.PASS
                if threshold_status.get(source_name, False)
                else GateStatus.BLOCKED
            ),
            evidenceKind=MetricEvidenceKind.OFFLINE_MEASURED,
            evaluationMode="DETERMINISTIC_OFFLINE_FAKE",
            scope="LISTING_PROPOSAL",
        )
        for output_name, source_name in mappings
    ]
    cases = {case.scenario: case for case in report.case_results}
    metrics.extend(
        (
            _failure_coverage_metric(
                ObservabilityMetricName.MEDIA_FAILURE_COVERAGE,
                cases,
                (
                    ProposalEvaluationScenario.MEDIA_UNAVAILABLE,
                    ProposalEvaluationScenario.MEDIA_TIMEOUT,
                ),
            ),
            _failure_coverage_metric(
                ObservabilityMetricName.VISION_FAILURE_COVERAGE,
                cases,
                (
                    ProposalEvaluationScenario.VISION_OUTAGE,
                    ProposalEvaluationScenario.VISION_TIMEOUT,
                    ProposalEvaluationScenario.MALFORMED_VISION_RESULT,
                ),
            ),
            ObservabilityMetric(
                name=ObservabilityMetricName.SIMULATED_LATENCY_P95,
                value=Decimal(report.simulated_latency.p95_ms),
                unit="MILLISECONDS",
                status=GateStatus.UNKNOWN,
                evidenceKind=MetricEvidenceKind.SIMULATED,
                evaluationMode="DETERMINISTIC_OFFLINE_FAKE",
                scope="LISTING_PROPOSAL",
            ),
            _zero_usage_metric(
                ObservabilityMetricName.PROVIDER_REQUESTS,
                report.usage.provider_requests,
                "COUNT",
            ),
            _zero_usage_metric(
                ObservabilityMetricName.INPUT_TOKENS,
                report.usage.input_tokens,
                "TOKENS",
            ),
            _zero_usage_metric(
                ObservabilityMetricName.OUTPUT_TOKENS,
                report.usage.output_tokens,
                "TOKENS",
            ),
            ObservabilityMetric(
                name=ObservabilityMetricName.ESTIMATED_COST,
                value=report.usage.estimated_cost,
                unit="UNPRICED_COST",
                status=GateStatus.UNKNOWN,
                evidenceKind=MetricEvidenceKind.ZERO_USAGE_METADATA,
                evaluationMode="DETERMINISTIC_OFFLINE_FAKE",
                scope="LISTING_PROPOSAL",
            ),
        )
    )
    return ListingOfflineObservability(
        schemaVersion=OBSERVABILITY_SCHEMA_VERSION,
        evaluationReportSchemaVersion=report.schema_version,
        runnerVersion=report.runner_version,
        fixtureVersion=report.fixture_version,
        seed=report.seed,
        metrics=tuple(metrics),
        productionQualityStatus="UNKNOWN",
        productionLatencyStatus="UNKNOWN",
        productionCostStatus="UNKNOWN",
        pricingApproved=False,
    )


def evaluate_release_gate(
    report_payload: object | None,
    gate_input: ListingReleaseGateInput,
) -> ListingReleaseGateDecision:
    """Evaluate offline evidence while preserving default-off precedence."""

    gates = _switch_gates(gate_input.switches)
    report: OfflineProposalReport | None = None
    observability: ListingOfflineObservability | None = None
    is_mapping = isinstance(report_payload, dict)
    if report_payload is None:
        gates.extend(_unavailable_report_gates("REPORT_MISSING"))
    elif not is_mapping:
        gates.extend(_unavailable_report_gates("REPORT_MALFORMED", present=True))
    else:
        assert isinstance(report_payload, dict)
        gates.append(_passed(GateName.REPORT_PRESENT))
        gates.append(_passed(GateName.REPORT_FORMAT))
        raw_schema = report_payload.get("schemaVersion")
        if raw_schema != REPORT_SCHEMA_VERSION:
            gates.append(
                _blocked(
                    GateName.REPORT_SCHEMA,
                    "REPORT_SCHEMA_INCOMPATIBLE",
                )
            )
            gates.extend(
                _unknown_report_gates_after_schema("REPORT_SCHEMA_INCOMPATIBLE")
            )
        else:
            gates.append(_passed(GateName.REPORT_SCHEMA))
            try:
                report = OfflineProposalReport.model_validate(report_payload)
            except ValidationError:
                gates.append(_blocked(GateName.REPORT_VALID, "REPORT_INVALID"))
            else:
                gates.append(_passed(GateName.REPORT_VALID))

            gates.extend(
                _report_detail_gates(
                    report_payload,
                    report,
                    gate_input,
                )
            )
            if report is not None:
                observability = build_offline_observability(report)

    gates.extend(
        (
            _production_evidence_gate(
                GateName.PRODUCTION_QUALITY_EVIDENCE,
                gate_input.production_evidence.quality,
                gate_input.decision_at,
                "PRODUCTION_QUALITY",
            ),
            _production_evidence_gate(
                GateName.PRODUCTION_LATENCY_EVIDENCE,
                gate_input.production_evidence.latency,
                gate_input.decision_at,
                "PRODUCTION_LATENCY",
            ),
            _production_evidence_gate(
                GateName.PRODUCTION_COST_EVIDENCE,
                gate_input.production_evidence.cost,
                gate_input.decision_at,
                "PRODUCTION_COST",
            ),
            _approval_gate(
                GateName.PRIVACY_APPROVAL,
                gate_input.approvals.privacy,
                gate_input.decision_at,
                "PRIVACY",
            ),
            _approval_gate(
                GateName.POLICY_APPROVAL,
                gate_input.approvals.policy,
                gate_input.decision_at,
                "POLICY",
            ),
            _rollout_gate(gate_input),
            _blocked(
                GateName.OFFLINE_RELEASE_AUTHORITY,
                "OFFLINE_EVIDENCE_CANNOT_AUTHORIZE_ROLLOUT",
            ),
        )
    )
    blockers = tuple(
        gate.reason_code for gate in gates if gate.status != GateStatus.PASS
    )
    return ListingReleaseGateDecision(
        schemaVersion=GATE_DECISION_SCHEMA_VERSION,
        inputSchemaVersion=gate_input.schema_version,
        decisionAt=gate_input.decision_at,
        targetStage=gate_input.target_stage,
        decision="BLOCKED",
        releaseAuthorized=False,
        blockers=blockers,
        gates=tuple(gates),
        observability=observability,
        rollbackTriggers=_ROLLBACK_TRIGGERS,
    )


def load_gate_input(path: Path = DEFAULT_GATE_INPUT) -> ListingReleaseGateInput:
    """Load explicit offline inputs without reading environment or runtime state."""

    return ListingReleaseGateInput.model_validate_json(
        path.read_text(encoding="utf-8")
    )


def _failure_coverage_metric(
    name: ObservabilityMetricName,
    cases: dict[ProposalEvaluationScenario, object],
    scenarios: tuple[ProposalEvaluationScenario, ...],
) -> ObservabilityMetric:
    passed = 0
    for scenario in scenarios:
        case = cases.get(scenario)
        if (
            case is not None
            and getattr(case, "passed", False)
            and getattr(case, "checks", {}).get(
                "dependency_failure_handling",
                False,
            )
        ):
            passed += 1
    denominator = len(scenarios)
    return ObservabilityMetric(
        name=name,
        value=(Decimal(passed) / Decimal(denominator)).quantize(
            Decimal("0.0000")
        ),
        numerator=passed,
        denominator=denominator,
        unit="RATIO",
        status=GateStatus.PASS if passed == denominator else GateStatus.BLOCKED,
        evidenceKind=MetricEvidenceKind.OFFLINE_MEASURED,
        evaluationMode="DETERMINISTIC_OFFLINE_FAKE",
        scope="LISTING_PROPOSAL",
    )


def _zero_usage_metric(
    name: ObservabilityMetricName,
    value: int,
    unit: Literal["COUNT", "TOKENS"],
) -> ObservabilityMetric:
    return ObservabilityMetric(
        name=name,
        value=Decimal(value),
        unit=unit,
        status=GateStatus.PASS if value == 0 else GateStatus.BLOCKED,
        evidenceKind=MetricEvidenceKind.ZERO_USAGE_METADATA,
        evaluationMode="DETERMINISTIC_OFFLINE_FAKE",
        scope="LISTING_PROPOSAL",
    )


def _switch_gates(states: ReleaseSwitchStates) -> list[GateResult]:
    results = [
        (
            _blocked(
                GateName.GLOBAL_KILL_SWITCH,
                "GLOBAL_KILL_SWITCH_ENGAGED",
            )
            if states.global_kill_switch_engaged
            else _passed(GateName.GLOBAL_KILL_SWITCH)
        )
    ]
    definitions = (
        (
            GateName.PROPOSAL_CAPABILITY,
            states.proposal_capability,
            "PROPOSAL_CAPABILITY",
        ),
        (GateName.MEDIA_TOOL, states.media_tool, "MEDIA_TOOL"),
        (
            GateName.PROVIDER_EXECUTION,
            states.provider_execution,
            "PROVIDER_EXECUTION",
        ),
        (GateName.AGENT_API, states.agent_api, "AGENT_API"),
        (GateName.GATEWAY_EXPOSURE, states.gateway_exposure, "GATEWAY"),
        (GateName.FRONTEND_ENTRY, states.frontend_entry, "FRONTEND"),
    )
    for gate, state, prefix in definitions:
        if state.kill_switch_engaged:
            results.append(_blocked(gate, f"{prefix}_KILL_SWITCH_ENGAGED"))
        elif not state.enabled:
            results.append(_blocked(gate, f"{prefix}_DEFAULT_OFF"))
        else:
            results.append(_passed(gate))
    return results


def _unavailable_report_gates(
    reason: str,
    *,
    present: bool = False,
) -> tuple[GateResult, ...]:
    first = (
        _passed(GateName.REPORT_PRESENT)
        if present
        else _blocked(GateName.REPORT_PRESENT, reason)
    )
    format_gate = (
        _blocked(GateName.REPORT_FORMAT, reason)
        if present
        else _unknown(GateName.REPORT_FORMAT, "REPORT_UNAVAILABLE")
    )
    return (
        first,
        format_gate,
        _unknown(GateName.REPORT_SCHEMA, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_VALID, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_INTEGRITY, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_FRESHNESS, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_VERSION, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_CASE_COUNT, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_METRICS, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_THRESHOLDS, "REPORT_UNAVAILABLE"),
        _unknown(GateName.REPORT_FAILURE_COVERAGE, "REPORT_UNAVAILABLE"),
        _unknown(GateName.OFFLINE_BASELINE, "REPORT_UNAVAILABLE"),
        _unknown(GateName.ZERO_PROVIDER_USAGE, "REPORT_UNAVAILABLE"),
        _unknown(GateName.SIMULATED_LATENCY_BOUNDARY, "REPORT_UNAVAILABLE"),
    )


def _unknown_report_gates_after_schema(
    reason: str,
) -> tuple[GateResult, ...]:
    return tuple(
        _unknown(gate, reason)
        for gate in (
            GateName.REPORT_VALID,
            GateName.REPORT_INTEGRITY,
            GateName.REPORT_FRESHNESS,
            GateName.REPORT_VERSION,
            GateName.REPORT_CASE_COUNT,
            GateName.REPORT_METRICS,
            GateName.REPORT_THRESHOLDS,
            GateName.REPORT_FAILURE_COVERAGE,
            GateName.OFFLINE_BASELINE,
            GateName.ZERO_PROVIDER_USAGE,
            GateName.SIMULATED_LATENCY_BOUNDARY,
        )
    )


def _report_detail_gates(
    payload: dict[object, object],
    report: OfflineProposalReport | None,
    gate_input: ListingReleaseGateInput,
) -> tuple[GateResult, ...]:
    valid_reason = "REPORT_INVALID"
    integrity = (
        _integrity_gate(report, gate_input)
        if report is not None
        else _unknown(GateName.REPORT_INTEGRITY, valid_reason)
    )
    freshness = _freshness_gate(gate_input)
    version = _version_gate(payload)
    counts_ok = _raw_case_count_consistent(payload)
    metrics_ok = _raw_metrics_consistent(payload)
    thresholds_ok = _raw_thresholds_consistent(payload)
    failures_ok = _raw_failure_coverage_consistent(payload)
    baseline_ok = _raw_baseline_passed(payload)
    usage_ok = _raw_zero_usage(payload)
    latency_ok = _raw_simulated_latency_boundary(payload)
    return (
        integrity,
        freshness,
        version,
        _boolean_gate(
            GateName.REPORT_CASE_COUNT,
            counts_ok,
            "REPORT_CASE_COUNT_INCONSISTENT",
        ),
        _boolean_gate(
            GateName.REPORT_METRICS,
            metrics_ok,
            "REPORT_METRICS_INCONSISTENT",
        ),
        _boolean_gate(
            GateName.REPORT_THRESHOLDS,
            thresholds_ok,
            "REPORT_THRESHOLDS_INCONSISTENT",
        ),
        _boolean_gate(
            GateName.REPORT_FAILURE_COVERAGE,
            failures_ok,
            "REPORT_FAILURE_COVERAGE_INCONSISTENT",
        ),
        _boolean_gate(
            GateName.OFFLINE_BASELINE,
            baseline_ok,
            "OFFLINE_BASELINE_NOT_PASSING",
        ),
        _boolean_gate(
            GateName.ZERO_PROVIDER_USAGE,
            usage_ok,
            "NONZERO_OR_UNPRICED_USAGE_REJECTED",
        ),
        _boolean_gate(
            GateName.SIMULATED_LATENCY_BOUNDARY,
            latency_ok,
            "SIMULATED_LATENCY_PRODUCTION_CLAIM_REJECTED",
        ),
    )


def _integrity_gate(
    report: OfflineProposalReport,
    gate_input: ListingReleaseGateInput,
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


def _freshness_gate(gate_input: ListingReleaseGateInput) -> GateResult:
    generated = gate_input.report_generated_at
    if generated is None:
        return _unknown(GateName.REPORT_FRESHNESS, "REPORT_TIMESTAMP_MISSING")
    issues = evidence_freshness_issues(
        generated_at=generated,
        evaluated_at=gate_input.decision_at,
        maximum_age=MAXIMUM_REPORT_AGE,
    )
    if EvidenceFreshnessIssue.TIMESTAMP_IN_FUTURE in issues:
        return _blocked(GateName.REPORT_FRESHNESS, "REPORT_TIMESTAMP_IN_FUTURE")
    if EvidenceFreshnessIssue.STALE in issues:
        return _blocked(GateName.REPORT_FRESHNESS, "REPORT_STALE")
    return _passed(GateName.REPORT_FRESHNESS)


def _version_gate(payload: dict[object, object]) -> GateResult:
    expected_fixture_sha = fixture_evidence_sha256(load_offline_fixture())
    valid = all(
        payload.get(key) == value for key, value in _EXPECTED_VERSIONS.items()
    )
    valid = valid and payload.get("fixtureSha256") == expected_fixture_sha
    return _boolean_gate(
        GateName.REPORT_VERSION,
        valid,
        "REPORT_VERSION_MISMATCH",
    )


def _raw_case_count_consistent(payload: dict[object, object]) -> bool:
    cases = payload.get("caseResults")
    count = payload.get("caseCount")
    if not isinstance(cases, list) or not _plain_int(count):
        return False
    ids = [
        case.get("caseId")
        for case in cases
        if isinstance(case, dict)
    ]
    if not all(isinstance(case_id, str) for case_id in ids):
        return False
    return (
        len(ids) == len(cases)
        and count == len(cases)
        and len(ids) == len(set(ids))
    )


def _raw_metrics_consistent(payload: dict[object, object]) -> bool:
    metrics = payload.get("metrics")
    cases = payload.get("caseResults")
    if not isinstance(metrics, dict) or not isinstance(cases, list):
        return False
    if set(metrics) != _EXPECTED_METRICS:
        return False
    totals = {name: [0, 0] for name in _EXPECTED_METRICS}
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("checks"), dict):
            return False
        checks = case["checks"]
        if not set(checks).issubset(_EXPECTED_METRICS):
            return False
        for name, passed in checks.items():
            if not isinstance(passed, bool):
                return False
            totals[name][0] += int(passed)
            totals[name][1] += 1
    for name, raw in metrics.items():
        if not isinstance(raw, dict):
            return False
        numerator = raw.get("numerator")
        denominator = raw.get("denominator")
        value = _decimal(raw.get("value"))
        if (
            not _plain_int(numerator)
            or not _plain_int(denominator)
            or denominator <= 0
            or numerator > denominator
            or totals[name] != [numerator, denominator]
            or value
            != (Decimal(numerator) / Decimal(denominator)).quantize(
                Decimal("0.0000")
            )
        ):
            return False
    return all(denominator > 0 for _, denominator in totals.values())


def _raw_thresholds_consistent(payload: dict[object, object]) -> bool:
    thresholds = payload.get("thresholds")
    metrics = payload.get("metrics")
    if not isinstance(thresholds, list) or not isinstance(metrics, dict):
        return False
    names: list[object] = []
    for threshold in thresholds:
        if not isinstance(threshold, dict):
            return False
        name = threshold.get("metric")
        names.append(name)
        if not isinstance(name, str) or name not in _EXPECTED_METRICS:
            return False
        metric = metrics.get(name)
        actual = _decimal(threshold.get("actual"))
        expected = _decimal(threshold.get("threshold"))
        if (
            not isinstance(metric, dict)
            or threshold.get("comparator") != "EQ"
            or threshold.get("provisionalOfflineContract") is not True
            or expected != Decimal("1.0000")
            or actual != _decimal(metric.get("value"))
            or threshold.get("passed") is not (actual == expected)
        ):
            return False
    return set(names) == _EXPECTED_METRICS and len(names) == len(
        _EXPECTED_METRICS
    )


def _raw_failure_coverage_consistent(payload: dict[object, object]) -> bool:
    cases = payload.get("caseResults")
    thresholds = payload.get("thresholds")
    reasons = payload.get("baselineFailureReasons")
    if (
        not isinstance(cases, list)
        or not isinstance(thresholds, list)
        or not isinstance(reasons, list)
    ):
        return False
    expected: list[str] = []
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("checks"), dict):
            return False
        case_id = case.get("caseId")
        failures = sorted(
            f"CHECK_FAILED:{name}"
            for name, passed in case["checks"].items()
            if passed is False
        )
        if (
            not isinstance(case_id, str)
            or case.get("failureReasons") != failures
            or case.get("passed") is not (not failures)
        ):
            return False
        if failures:
            expected.append(f"CASE_FAILED:{case_id}")
    for threshold in thresholds:
        if not isinstance(threshold, dict):
            return False
        if threshold.get("passed") is False:
            expected.append(f"THRESHOLD_FAILED:{threshold.get('metric')}")
    expected.sort()
    return reasons == expected and payload.get("baselineStatus") == (
        "PASS" if not expected else "FAIL"
    )


def _raw_baseline_passed(payload: dict[object, object]) -> bool:
    cases = payload.get("caseResults")
    thresholds = payload.get("thresholds")
    return (
        payload.get("baselineStatus") == "PASS"
        and payload.get("baselineFailureReasons") == []
        and isinstance(cases, list)
        and all(
            isinstance(case, dict) and case.get("passed") is True
            for case in cases
        )
        and isinstance(thresholds, list)
        and all(
            isinstance(threshold, dict)
            and threshold.get("passed") is True
            for threshold in thresholds
        )
    )


def _raw_zero_usage(payload: dict[object, object]) -> bool:
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return False
    return (
        usage.get("mode") == "DETERMINISTIC_FAKE_ZERO_COST"
        and usage.get("providerRequests") == 0
        and usage.get("inputTokens") == 0
        and usage.get("outputTokens") == 0
        and _decimal(usage.get("estimatedCost")) == Decimal("0")
        and usage.get("pricingApproved") is False
        and usage.get("productionEvidenceEligible") is False
    )


def _raw_simulated_latency_boundary(payload: dict[object, object]) -> bool:
    latency = payload.get("simulatedLatency")
    return (
        isinstance(latency, dict)
        and latency.get("classification") == "NON_PRODUCTION_SIMULATED"
        and latency.get("productionSloEligible") is False
        and _plain_int(latency.get("p95Ms"))
        and latency["p95Ms"] >= 0
    )


def _production_evidence_gate(
    gate: GateName,
    evidence: ProductionEvidence,
    decision_at: datetime,
    prefix: str,
) -> GateResult:
    if evidence.status == EvidenceStatus.UNKNOWN:
        return _unknown(gate, f"{prefix}_UNKNOWN")
    if evidence.source == EvidenceSource.OFFLINE_SIMULATED:
        return _blocked(gate, f"{prefix}_OFFLINE_EVIDENCE_REJECTED")
    assert evidence.collected_at is not None
    assert evidence.expires_at is not None
    if evidence.collected_at > decision_at:
        return _blocked(gate, f"{prefix}_EVIDENCE_IN_FUTURE")
    if evidence.expires_at <= decision_at:
        return _blocked(gate, f"{prefix}_EVIDENCE_EXPIRED")
    return _passed(gate)


def _approval_gate(
    gate: GateName,
    approval: ApprovalEvidence,
    decision_at: datetime,
    prefix: str,
) -> GateResult:
    if not approval.approved:
        return _unknown(gate, f"{prefix}_APPROVAL_MISSING")
    assert approval.approved_at is not None
    assert approval.expires_at is not None
    if approval.approved_at > decision_at:
        return _blocked(gate, f"{prefix}_APPROVAL_NOT_YET_VALID")
    if approval.expires_at <= decision_at:
        return _blocked(gate, f"{prefix}_APPROVAL_EXPIRED")
    return _passed(gate)


def _rollout_gate(gate_input: ListingReleaseGateInput) -> GateResult:
    required = [("INTERNAL", gate_input.approvals.internal_rollout)]
    if gate_input.target_stage in {
        RolloutStage.SMALL_COHORT,
        RolloutStage.WIDER,
    }:
        required.append(
            ("SMALL_COHORT", gate_input.approvals.small_cohort_rollout)
        )
    if gate_input.target_stage == RolloutStage.WIDER:
        required.append(("WIDER", gate_input.approvals.wider_rollout))
    for name, approval in required:
        result = _approval_gate(
            GateName.ROLLOUT_APPROVAL,
            approval,
            gate_input.decision_at,
            f"{name}_ROLLOUT",
        )
        if result.status != GateStatus.PASS:
            return result
    return _passed(GateName.ROLLOUT_APPROVAL)


def _boolean_gate(
    gate: GateName,
    passed: bool,
    failure_reason: str,
) -> GateResult:
    return _passed(gate) if passed else _blocked(gate, failure_reason)


def _passed(gate: GateName) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.PASS, reasonCode="PASSED")


def _blocked(gate: GateName, reason: str) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.BLOCKED, reasonCode=reason)


def _unknown(gate: GateName, reason: str) -> GateResult:
    return GateResult(gate=gate, status=GateStatus.UNKNOWN, reasonCode=reason)


def _plain_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _read_report_payload(path: Path | None) -> object | None:
    if path is None or not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "MALFORMED_REPORT"


def main(argv: list[str] | None = None) -> int:
    """Emit strict schemas or one deterministic, offline-blocked decision."""

    parser = argparse.ArgumentParser(
        description="Evaluate AI-LIST offline release gates"
    )
    parser.add_argument("--report", type=Path)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument(
        "--schema",
        choices=("input", "decision", "observability"),
    )
    arguments = parser.parse_args(argv)
    if arguments.schema:
        model = {
            "input": ListingReleaseGateInput,
            "decision": ListingReleaseGateDecision,
            "observability": ListingOfflineObservability,
        }[arguments.schema]
        print(
            json.dumps(
                model.model_json_schema(by_alias=True),
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    if arguments.inputs is None:
        parser.error("--inputs is required unless --schema is used")
    decision = evaluate_release_gate(
        _read_report_payload(arguments.report),
        load_gate_input(arguments.inputs),
    )
    print(_pretty_json(decision))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
