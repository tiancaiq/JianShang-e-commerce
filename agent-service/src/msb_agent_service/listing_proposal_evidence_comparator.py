from __future__ import annotations

import argparse
import asyncio
import json
from collections.abc import Iterable
from datetime import datetime, timedelta
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
    OfflineProposalReport,
    load_offline_fixture,
    report_evidence_sha256 as offline_report_sha256,
    run_offline_evaluation,
)
from .listing_proposal_evidence_contract import (
    EvidenceFreshnessIssue,
    MAXIMUM_OFFLINE_EVIDENCE_AGE,
    StrictEvidenceModel as _StrictModel,
    datetime_or_none as _datetime,
    decimal_or_none as _decimal,
    evidence_freshness_issues,
    model_evidence_sha256,
    raw_evidence_sha256,
)
from .listing_proposal_release_gate import (
    GateName,
    ListingReleaseGateDecision,
    ListingReleaseGateInput,
    ObservabilityMetricName,
    evaluate_release_gate,
    load_gate_input,
    report_evidence_sha256 as timestamped_report_sha256,
)

COMPARATOR_VERSION = "ai-list-eval-p0-03-comparator-v1"
MANIFEST_SCHEMA_VERSION = "ai-list-offline-evidence-manifest-v1"
BUNDLE_SCHEMA_VERSION = "ai-list-offline-evidence-bundle-v1"
COMPARISON_SCHEMA_VERSION = "ai-list-offline-regression-comparison-v1"
DEFAULT_MANIFEST = (
    Path(__file__).resolve().parents[2]
    / "evals"
    / "ai_list_eval_p0_03_evidence_manifest_v1.json"
)
PINNED_MANIFEST_SHA256 = (
    "0f9b8c3ece2623d8523e189a96dd1d5f7b1e279fd9720403c4e6e25f60aa3a61"
)

_CHECK_ORDER = (
    "MANIFEST_INTEGRITY",
    "BUNDLE_SCHEMA",
    "IDENTITY_COMPATIBILITY",
    "EVIDENCE_DIGESTS",
    "CASE_COVERAGE",
    "METRIC_NON_REGRESSION",
    "THRESHOLD_STRENGTH",
    "ZERO_PROVIDER_USAGE",
    "SIMULATED_LATENCY_BOUNDARY",
    "OBSERVABILITY_CARDINALITY",
    "DECISION_SAFETY",
    "DEFAULT_OFF_PRECEDENCE",
    "PRODUCTION_CLAIM_BOUNDARY",
    "EVIDENCE_FRESHNESS",
)
_DEFAULT_OFF_REASON_CODES = (
    "PROPOSAL_CAPABILITY_DEFAULT_OFF",
    "MEDIA_TOOL_DEFAULT_OFF",
    "PROVIDER_EXECUTION_DEFAULT_OFF",
    "AGENT_API_DEFAULT_OFF",
    "GATEWAY_DEFAULT_OFF",
    "FRONTEND_DEFAULT_OFF",
)


class RegressionStatus(StrEnum):
    PASS = "REGRESSION_PASS"
    FAIL = "REGRESSION_FAIL"


class ComparisonReason(StrEnum):
    MANIFEST_INVALID = "MANIFEST_INVALID"
    MANIFEST_DIGEST_MISMATCH = "MANIFEST_DIGEST_MISMATCH"
    BUNDLE_MISSING = "BUNDLE_MISSING"
    BUNDLE_MALFORMED = "BUNDLE_MALFORMED"
    BUNDLE_SCHEMA_INCOMPATIBLE = "BUNDLE_SCHEMA_INCOMPATIBLE"
    BUNDLE_INVALID = "BUNDLE_INVALID"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    REPORT_DIGEST_MISMATCH = "REPORT_DIGEST_MISMATCH"
    TIMESTAMPED_REPORT_DIGEST_MISMATCH = "TIMESTAMPED_REPORT_DIGEST_MISMATCH"
    DECISION_DIGEST_MISMATCH = "DECISION_DIGEST_MISMATCH"
    OBSERVABILITY_DIGEST_MISMATCH = "OBSERVABILITY_DIGEST_MISMATCH"
    CASE_SET_MISMATCH = "CASE_SET_MISMATCH"
    CASE_COUNT_MISMATCH = "CASE_COUNT_MISMATCH"
    FAILURE_COVERAGE_LOSS = "FAILURE_COVERAGE_LOSS"
    METRIC_SET_MISMATCH = "METRIC_SET_MISMATCH"
    METRIC_REGRESSION = "METRIC_REGRESSION"
    METRIC_COVERAGE_LOSS = "METRIC_COVERAGE_LOSS"
    THRESHOLD_SET_MISMATCH = "THRESHOLD_SET_MISMATCH"
    THRESHOLD_WEAKENED = "THRESHOLD_WEAKENED"
    NONZERO_PROVIDER_REQUESTS = "NONZERO_PROVIDER_REQUESTS"
    NONZERO_TOKENS = "NONZERO_TOKENS"
    NONZERO_COST = "NONZERO_COST"
    SIMULATED_LATENCY_LABEL_MISMATCH = "SIMULATED_LATENCY_LABEL_MISMATCH"
    SIMULATED_LATENCY_PRODUCTION_CLAIM = (
        "SIMULATED_LATENCY_PRODUCTION_CLAIM"
    )
    SIMULATED_LATENCY_REGRESSION = "SIMULATED_LATENCY_REGRESSION"
    OBSERVABILITY_SCHEMA_MISMATCH = "OBSERVABILITY_SCHEMA_MISMATCH"
    OBSERVABILITY_METRIC_SET_MISMATCH = (
        "OBSERVABILITY_METRIC_SET_MISMATCH"
    )
    DECISION_NOT_BLOCKED = "DECISION_NOT_BLOCKED"
    RELEASE_AUTHORIZED = "RELEASE_AUTHORIZED"
    OFFLINE_AUTHORITY_MISSING = "OFFLINE_AUTHORITY_MISSING"
    DEFAULT_OFF_PRECEDENCE_DRIFT = "DEFAULT_OFF_PRECEDENCE_DRIFT"
    KILL_SWITCH_PRECEDENCE_DRIFT = "KILL_SWITCH_PRECEDENCE_DRIFT"
    PRODUCTION_QUALITY_CLAIMED = "PRODUCTION_QUALITY_CLAIMED"
    PRODUCTION_LATENCY_CLAIMED = "PRODUCTION_LATENCY_CLAIMED"
    PRODUCTION_COST_CLAIMED = "PRODUCTION_COST_CLAIMED"
    READINESS_CLAIMED = "READINESS_CLAIMED"
    EVIDENCE_TIMESTAMP_IN_FUTURE = "EVIDENCE_TIMESTAMP_IN_FUTURE"
    EVIDENCE_STALE = "EVIDENCE_STALE"
    EVIDENCE_WINDOW_INVALID = "EVIDENCE_WINDOW_INVALID"


class ManifestMetricPin(_StrictModel):
    name: str = Field(pattern=r"^[a-z][a-z0-9_]{2,79}$")
    value: Decimal = Field(ge=0, le=1)
    numerator: int = Field(ge=0)
    denominator: int = Field(gt=0)

    @model_validator(mode="after")
    def ratio_is_consistent(self) -> "ManifestMetricPin":
        expected = (
            Decimal(self.numerator) / Decimal(self.denominator)
        ).quantize(Decimal("0.0000"))
        if self.numerator > self.denominator or self.value != expected:
            raise ValueError("manifest metric ratio must be internally consistent")
        return self


class ManifestThresholdPin(_StrictModel):
    metric: str = Field(pattern=r"^[a-z][a-z0-9_]{2,79}$")
    comparator: Literal["EQ"]
    threshold: Decimal = Field(ge=1, le=1)


class ReportPins(_StrictModel):
    schema_version: Literal["ai-list-offline-evaluation-report-v1"]
    runner_version: Literal["ai-list-eval-p0-01-runner-v1"]
    fixture_version: Literal["ai-list-eval-p0-01-fixture-v1"]
    fixture_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    proposal_schema_version: Literal["ai-list-proposal-v1"]
    tool_registry_version: Literal["owned-listing-media-read-v1"]
    instruction_version: Literal["listing-proposal-vision-v1"]
    seed: int = Field(ge=0)
    baseline_report_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    baseline_timestamped_report_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    case_count: int = Field(gt=0)
    case_ids: tuple[str, ...] = Field(min_length=1)
    failure_reasons: tuple[str, ...]
    metrics: tuple[ManifestMetricPin, ...] = Field(min_length=1)
    thresholds: tuple[ManifestThresholdPin, ...] = Field(min_length=1)
    simulated_latency_classification: Literal["NON_PRODUCTION_SIMULATED"]
    baseline_simulated_latency_p95_ms: int = Field(ge=0)
    maximum_simulated_latency_regression_ms: int = Field(ge=0, le=100)
    provider_requests: Literal[0]
    input_tokens: Literal[0]
    output_tokens: Literal[0]
    estimated_cost: Decimal = Field(ge=0, le=0)

    @model_validator(mode="after")
    def pins_are_unique_and_complete(self) -> "ReportPins":
        if len(self.case_ids) != self.case_count or len(set(self.case_ids)) != len(
            self.case_ids
        ):
            raise ValueError("manifest case pins must be exact and unique")
        metric_names = [metric.name for metric in self.metrics]
        threshold_names = [threshold.metric for threshold in self.thresholds]
        if (
            len(set(metric_names)) != len(metric_names)
            or len(set(threshold_names)) != len(threshold_names)
            or set(metric_names) != set(threshold_names)
        ):
            raise ValueError("manifest metric and threshold pins must align")
        if metric_names != sorted(metric_names) or threshold_names != sorted(
            threshold_names
        ):
            raise ValueError("manifest metric and threshold pins must be ordered")
        thresholds = {item.metric: item for item in self.thresholds}
        if any(
            metric.value != thresholds[metric.name].threshold
            for metric in self.metrics
        ):
            raise ValueError("manifest baseline metrics must meet exact thresholds")
        return self


class GatePins(_StrictModel):
    decision_schema_version: Literal[
        "ai-list-offline-release-gate-decision-v1"
    ]
    input_schema_version: Literal["ai-list-offline-release-gate-input-v1"]
    observability_schema_version: Literal["ai-list-offline-observability-v1"]
    baseline_decision_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    baseline_observability_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    decision: Literal["BLOCKED"]
    release_authorized: Literal[False]
    gate_names: tuple[GateName, ...] = Field(min_length=1)
    default_off_reason_codes: tuple[str, ...] = Field(min_length=1)
    offline_authority_reason: Literal[
        "OFFLINE_EVIDENCE_CANNOT_AUTHORIZE_ROLLOUT"
    ]

    @model_validator(mode="after")
    def gate_pins_are_unique(self) -> "GatePins":
        if self.gate_names != tuple(GateName):
            raise ValueError("manifest gate pins must match fixed precedence")
        if self.default_off_reason_codes != _DEFAULT_OFF_REASON_CODES:
            raise ValueError("manifest default-off blockers must match precedence")
        return self


class ObservabilityPins(_StrictModel):
    metric_names: tuple[ObservabilityMetricName, ...] = Field(min_length=1)
    production_quality_status: Literal["UNKNOWN"]
    production_latency_status: Literal["UNKNOWN"]
    production_cost_status: Literal["UNKNOWN"]
    pricing_approved: Literal[False]

    @model_validator(mode="after")
    def metric_names_are_unique(self) -> "ObservabilityPins":
        if self.metric_names != tuple(ObservabilityMetricName):
            raise ValueError("manifest observability names must match fixed order")
        return self


class EvidenceManifest(_StrictModel):
    schema_version: Literal["ai-list-offline-evidence-manifest-v1"]
    manifest_version: Literal["ai-list-eval-p0-03-manifest-v1"]
    generated_at: datetime
    expires_at: datetime
    maximum_evidence_age_seconds: int = Field(gt=0, le=604_800)
    report: ReportPins
    gate: GatePins
    observability: ObservabilityPins

    @field_validator("generated_at", "expires_at")
    @classmethod
    def timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("manifest timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def freshness_window_is_bounded(self) -> "EvidenceManifest":
        issues = evidence_freshness_issues(
            generated_at=self.generated_at,
            evaluated_at=self.generated_at,
            expires_at=self.expires_at,
            maximum_age=timedelta(
                seconds=self.maximum_evidence_age_seconds
            ),
        )
        if issues:
            raise ValueError("manifest freshness exceeds its bounded maximum")
        return self


class OfflineEvidenceBundle(_StrictModel):
    schema_version: Literal["ai-list-offline-evidence-bundle-v1"]
    generated_at: datetime
    expires_at: datetime
    report_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    timestamped_report_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    decision_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    observability_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    report: OfflineProposalReport
    decision: ListingReleaseGateDecision

    @field_validator("generated_at", "expires_at")
    @classmethod
    def timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("bundle timestamps must include a UTC offset")
        return value

    @model_validator(mode="after")
    def bundle_has_observability_and_bounded_time(self) -> "OfflineEvidenceBundle":
        issues = evidence_freshness_issues(
            generated_at=self.generated_at,
            evaluated_at=self.generated_at,
            expires_at=self.expires_at,
            maximum_age=MAXIMUM_OFFLINE_EVIDENCE_AGE,
        )
        if issues:
            raise ValueError("bundle evidence window must be bounded")
        if self.decision.observability is None:
            raise ValueError("comparison bundle requires observability")
        return self


class ComparisonCheck(_StrictModel):
    name: str
    passed: bool
    reason_codes: tuple[ComparisonReason, ...]

    @model_validator(mode="after")
    def result_matches_reasons(self) -> "ComparisonCheck":
        if self.passed != (not self.reason_codes):
            raise ValueError("check pass state must match reason codes")
        return self


class RegressionComparison(_StrictModel):
    schema_version: Literal["ai-list-offline-regression-comparison-v1"]
    comparator_version: Literal["ai-list-eval-p0-03-comparator-v1"]
    manifest_version: str
    evaluated_at: datetime
    regression_status: RegressionStatus
    rollout_decision: Literal["BLOCKED"]
    release_authorized: Literal[False]
    reason_codes: tuple[ComparisonReason, ...]
    checks: tuple[ComparisonCheck, ...]
    manifest_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    candidate_bundle_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    baseline_report_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    candidate_report_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    comparison_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")

    @field_validator("evaluated_at")
    @classmethod
    def timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("comparison timestamp must include a UTC offset")
        return value

    @model_validator(mode="after")
    def comparison_is_complete_safe_and_digest_bound(
        self,
    ) -> "RegressionComparison":
        if [check.name for check in self.checks] != list(_CHECK_ORDER):
            raise ValueError("comparison requires every check in fixed order")
        expected_reasons = _unique_reasons(
            reason
            for check in self.checks
            for reason in check.reason_codes
        )
        if self.reason_codes != expected_reasons:
            raise ValueError("comparison reasons must match failed checks")
        expected_status = (
            RegressionStatus.PASS
            if not expected_reasons
            else RegressionStatus.FAIL
        )
        if self.regression_status != expected_status:
            raise ValueError("regression status must match failed checks")
        if self.comparison_sha256 != comparison_evidence_sha256(self):
            raise ValueError("comparison digest does not match its payload")
        return self


def load_manifest(path: Path = DEFAULT_MANIFEST) -> EvidenceManifest:
    """Load one pinned manifest without consulting runtime or environment state."""

    return EvidenceManifest.model_validate_json(path.read_text(encoding="utf-8"))


async def build_current_bundle() -> OfflineEvidenceBundle:
    """Build the current deterministic report and default-off gate bundle."""

    report = await run_offline_evaluation(load_offline_fixture())
    gate_input = load_gate_input()
    generated_at = gate_input.report_generated_at
    if generated_at is None:
        raise ValueError("default gate input must pin report generation time")
    gate_payload = gate_input.model_dump(mode="json", by_alias=True)
    gate_payload["reportEvidenceSha256"] = timestamped_report_sha256(
        report,
        generated_at,
    )
    bound_input = ListingReleaseGateInput.model_validate(gate_payload)
    decision = evaluate_release_gate(
        report.model_dump(mode="json", by_alias=True),
        bound_input,
    )
    assert decision.observability is not None
    return OfflineEvidenceBundle(
        schemaVersion=BUNDLE_SCHEMA_VERSION,
        generatedAt=generated_at,
        expiresAt=generated_at + MAXIMUM_OFFLINE_EVIDENCE_AGE,
        reportSha256=offline_report_sha256(report),
        timestampedReportSha256=timestamped_report_sha256(
            report,
            generated_at,
        ),
        decisionSha256=model_evidence_sha256(decision),
        observabilitySha256=model_evidence_sha256(decision.observability),
        report=report,
        decision=decision,
    )


def compare_evidence(
    candidate_payload: object | None,
    manifest_payload: object,
    evaluated_at: datetime,
) -> RegressionComparison:
    """Compare one candidate against pinned offline evidence without readiness."""

    manifest = _validate_manifest(manifest_payload)
    candidate = _validate_candidate(candidate_payload)
    checks: list[ComparisonCheck] = []

    manifest_reasons: list[ComparisonReason] = []
    manifest_sha = raw_evidence_sha256(manifest_payload)
    if manifest is None:
        manifest_reasons.append(ComparisonReason.MANIFEST_INVALID)
    elif manifest_sha != PINNED_MANIFEST_SHA256:
        manifest_reasons.append(ComparisonReason.MANIFEST_DIGEST_MISMATCH)
    checks.append(_check("MANIFEST_INTEGRITY", manifest_reasons))

    checks.append(
        _check(
            "BUNDLE_SCHEMA",
            _bundle_schema_reasons(candidate_payload, candidate),
        )
    )
    if manifest is None:
        for name in _CHECK_ORDER[2:]:
            checks.append(_check(name, (ComparisonReason.MANIFEST_INVALID,)))
    elif candidate is None:
        checks.extend(
            (
                _check(
                    "IDENTITY_COMPATIBILITY",
                    _raw_identity_reasons(candidate_payload, manifest),
                ),
                _check(
                    "EVIDENCE_DIGESTS",
                    (ComparisonReason.BUNDLE_INVALID,),
                ),
                _check(
                    "CASE_COVERAGE",
                    _raw_case_reasons(candidate_payload, manifest),
                ),
                _check(
                    "METRIC_NON_REGRESSION",
                    _raw_metric_reasons(candidate_payload, manifest),
                ),
                _check(
                    "THRESHOLD_STRENGTH",
                    _raw_threshold_reasons(candidate_payload, manifest),
                ),
                _check(
                    "ZERO_PROVIDER_USAGE",
                    _raw_usage_reasons(candidate_payload),
                ),
                _check(
                    "SIMULATED_LATENCY_BOUNDARY",
                    _raw_latency_reasons(candidate_payload, manifest),
                ),
                _check(
                    "OBSERVABILITY_CARDINALITY",
                    _raw_observability_reasons(candidate_payload, manifest),
                ),
                _check(
                    "DECISION_SAFETY",
                    _raw_decision_reasons(candidate_payload, manifest),
                ),
                _check(
                    "DEFAULT_OFF_PRECEDENCE",
                    _raw_precedence_reasons(candidate_payload, manifest),
                ),
                _check(
                    "PRODUCTION_CLAIM_BOUNDARY",
                    _raw_production_claim_reasons(candidate_payload),
                ),
                _check(
                    "EVIDENCE_FRESHNESS",
                    _raw_freshness_reasons(
                        candidate_payload,
                        manifest,
                        evaluated_at,
                    ),
                ),
            )
        )
    else:
        checks.extend(
            (
                _check(
                    "IDENTITY_COMPATIBILITY",
                    _identity_reasons(candidate, manifest),
                ),
                _check(
                    "EVIDENCE_DIGESTS",
                    _digest_reasons(candidate, manifest),
                ),
                _check(
                    "CASE_COVERAGE",
                    _case_reasons(candidate, manifest),
                ),
                _check(
                    "METRIC_NON_REGRESSION",
                    _metric_reasons(candidate, manifest),
                ),
                _check(
                    "THRESHOLD_STRENGTH",
                    _threshold_reasons(candidate, manifest),
                ),
                _check(
                    "ZERO_PROVIDER_USAGE",
                    _usage_reasons(candidate),
                ),
                _check(
                    "SIMULATED_LATENCY_BOUNDARY",
                    _latency_reasons(candidate, manifest),
                ),
                _check(
                    "OBSERVABILITY_CARDINALITY",
                    _observability_reasons(candidate, manifest),
                ),
                _check(
                    "DECISION_SAFETY",
                    _decision_reasons(candidate, manifest),
                ),
                _check(
                    "DEFAULT_OFF_PRECEDENCE",
                    _precedence_reasons(candidate, manifest),
                ),
                _check(
                    "PRODUCTION_CLAIM_BOUNDARY",
                    _production_claim_reasons(candidate),
                ),
                _check(
                    "EVIDENCE_FRESHNESS",
                    _freshness_reasons(candidate, manifest, evaluated_at),
                ),
            )
        )
    return _build_comparison(
        checks=tuple(checks),
        evaluated_at=evaluated_at,
        manifest_sha=manifest_sha,
        candidate_sha=(
            raw_evidence_sha256(candidate_payload)
            if isinstance(candidate_payload, dict)
            else None
        ),
        manifest=manifest,
        candidate=candidate,
    )


def _validate_manifest(payload: object) -> EvidenceManifest | None:
    try:
        return EvidenceManifest.model_validate(payload)
    except ValidationError:
        return None


def _validate_candidate(payload: object | None) -> OfflineEvidenceBundle | None:
    try:
        return OfflineEvidenceBundle.model_validate(payload)
    except ValidationError:
        return None


def _bundle_schema_reasons(
    payload: object | None,
    candidate: OfflineEvidenceBundle | None,
) -> tuple[ComparisonReason, ...]:
    if payload is None:
        return (ComparisonReason.BUNDLE_MISSING,)
    if not isinstance(payload, dict):
        return (ComparisonReason.BUNDLE_MALFORMED,)
    if payload.get("schemaVersion") != BUNDLE_SCHEMA_VERSION:
        return (ComparisonReason.BUNDLE_SCHEMA_INCOMPATIBLE,)
    if candidate is None:
        return (ComparisonReason.BUNDLE_INVALID,)
    return ()


def _raw_parts(
    payload: object | None,
) -> tuple[dict[object, object], dict[object, object], dict[object, object]]:
    if not isinstance(payload, dict):
        return {}, {}, {}
    report = payload.get("report")
    decision = payload.get("decision")
    report_dict = report if isinstance(report, dict) else {}
    decision_dict = decision if isinstance(decision, dict) else {}
    observability = decision_dict.get("observability")
    observability_dict = (
        observability if isinstance(observability, dict) else {}
    )
    return report_dict, decision_dict, observability_dict


def _raw_identity_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report, decision, observability = _raw_parts(payload)
    pins = manifest.report
    expected = {
        "schemaVersion": pins.schema_version,
        "runnerVersion": pins.runner_version,
        "fixtureVersion": pins.fixture_version,
        "fixtureSha256": pins.fixture_sha256,
        "proposalSchemaVersion": pins.proposal_schema_version,
        "toolRegistryVersion": pins.tool_registry_version,
        "instructionVersion": pins.instruction_version,
        "seed": pins.seed,
    }
    valid = all(report.get(key) == value for key, value in expected.items())
    valid = valid and (
        decision.get("schemaVersion")
        == manifest.gate.decision_schema_version
        and decision.get("inputSchemaVersion")
        == manifest.gate.input_schema_version
        and observability.get("schemaVersion")
        == manifest.gate.observability_schema_version
    )
    return () if valid else (ComparisonReason.IDENTITY_MISMATCH,)


def _raw_case_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report, _, _ = _raw_parts(payload)
    cases = report.get("caseResults")
    reasons: list[ComparisonReason] = []
    if not isinstance(cases, list):
        return (
            ComparisonReason.CASE_COUNT_MISMATCH,
            ComparisonReason.CASE_SET_MISMATCH,
        )
    ids = [
        case.get("caseId")
        for case in cases
        if isinstance(case, dict)
    ]
    if report.get("caseCount") != manifest.report.case_count:
        reasons.append(ComparisonReason.CASE_COUNT_MISMATCH)
    if (
        len(ids) != len(cases)
        or not all(isinstance(case_id, str) for case_id in ids)
        or tuple(ids) != manifest.report.case_ids
        or len(set(ids)) != len(ids)
    ):
        reasons.append(ComparisonReason.CASE_SET_MISMATCH)
    if (
        report.get("baselineFailureReasons")
        != list(manifest.report.failure_reasons)
        or any(
            not isinstance(case, dict) or case.get("passed") is not True
            for case in cases
        )
    ):
        reasons.append(ComparisonReason.FAILURE_COVERAGE_LOSS)
    return tuple(reasons)


def _raw_metric_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report, _, _ = _raw_parts(payload)
    metrics = report.get("metrics")
    baseline = {pin.name: pin for pin in manifest.report.metrics}
    if not isinstance(metrics, dict) or set(metrics) != set(baseline):
        return (ComparisonReason.METRIC_SET_MISMATCH,)
    reasons: list[ComparisonReason] = []
    for name, pin in baseline.items():
        metric = metrics.get(name)
        if not isinstance(metric, dict):
            return (ComparisonReason.METRIC_SET_MISMATCH,)
        value = _decimal(metric.get("value"))
        denominator = metric.get("denominator")
        if not isinstance(denominator, int) or denominator < pin.denominator:
            reasons.append(ComparisonReason.METRIC_COVERAGE_LOSS)
        if value is None or value < pin.value:
            reasons.append(ComparisonReason.METRIC_REGRESSION)
    return _unique_reasons(reasons)


def _raw_threshold_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report, _, _ = _raw_parts(payload)
    thresholds = report.get("thresholds")
    baseline = {pin.metric: pin for pin in manifest.report.thresholds}
    if not isinstance(thresholds, list):
        return (ComparisonReason.THRESHOLD_SET_MISMATCH,)
    current = {
        item.get("metric"): item
        for item in thresholds
        if isinstance(item, dict) and isinstance(item.get("metric"), str)
    }
    if len(current) != len(thresholds) or set(current) != set(baseline):
        return (ComparisonReason.THRESHOLD_SET_MISMATCH,)
    for name, pin in baseline.items():
        threshold = current[name]
        if (
            threshold.get("comparator") != pin.comparator
            or _decimal(threshold.get("threshold")) != pin.threshold
        ):
            return (ComparisonReason.THRESHOLD_WEAKENED,)
    return ()


def _raw_usage_reasons(
    payload: object | None,
) -> tuple[ComparisonReason, ...]:
    report, _, _ = _raw_parts(payload)
    usage = report.get("usage")
    if not isinstance(usage, dict):
        return (
            ComparisonReason.NONZERO_PROVIDER_REQUESTS,
            ComparisonReason.NONZERO_TOKENS,
            ComparisonReason.NONZERO_COST,
        )
    reasons: list[ComparisonReason] = []
    if usage.get("providerRequests") != 0:
        reasons.append(ComparisonReason.NONZERO_PROVIDER_REQUESTS)
    if usage.get("inputTokens") != 0 or usage.get("outputTokens") != 0:
        reasons.append(ComparisonReason.NONZERO_TOKENS)
    if _decimal(usage.get("estimatedCost")) != Decimal("0"):
        reasons.append(ComparisonReason.NONZERO_COST)
    return tuple(reasons)


def _raw_latency_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report, _, _ = _raw_parts(payload)
    latency = report.get("simulatedLatency")
    if not isinstance(latency, dict):
        return (ComparisonReason.SIMULATED_LATENCY_LABEL_MISMATCH,)
    reasons: list[ComparisonReason] = []
    if (
        latency.get("classification")
        != manifest.report.simulated_latency_classification
    ):
        reasons.append(ComparisonReason.SIMULATED_LATENCY_LABEL_MISMATCH)
    if latency.get("productionSloEligible") is not False:
        reasons.append(ComparisonReason.SIMULATED_LATENCY_PRODUCTION_CLAIM)
    p95 = latency.get("p95Ms")
    maximum = (
        manifest.report.baseline_simulated_latency_p95_ms
        + manifest.report.maximum_simulated_latency_regression_ms
    )
    if not isinstance(p95, int) or p95 > maximum:
        reasons.append(ComparisonReason.SIMULATED_LATENCY_REGRESSION)
    return tuple(reasons)


def _raw_observability_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    _, _, observability = _raw_parts(payload)
    reasons: list[ComparisonReason] = []
    if (
        observability.get("schemaVersion")
        != manifest.gate.observability_schema_version
    ):
        reasons.append(ComparisonReason.OBSERVABILITY_SCHEMA_MISMATCH)
    metrics = observability.get("metrics")
    if not isinstance(metrics, list):
        reasons.append(ComparisonReason.OBSERVABILITY_METRIC_SET_MISMATCH)
    else:
        names = [
            metric.get("name")
            for metric in metrics
            if isinstance(metric, dict)
        ]
        if (
            len(names) != len(metrics)
            or tuple(names)
            != tuple(name.value for name in manifest.observability.metric_names)
            or len(set(names)) != len(names)
        ):
            reasons.append(ComparisonReason.OBSERVABILITY_METRIC_SET_MISMATCH)
    return tuple(reasons)


def _raw_decision_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    _, decision, _ = _raw_parts(payload)
    reasons: list[ComparisonReason] = []
    if decision.get("decision") != manifest.gate.decision:
        reasons.append(ComparisonReason.DECISION_NOT_BLOCKED)
    if decision.get("releaseAuthorized") is not False:
        reasons.append(ComparisonReason.RELEASE_AUTHORIZED)
    blockers = decision.get("blockers")
    if (
        not isinstance(blockers, list)
        or not blockers
        or blockers[-1] != manifest.gate.offline_authority_reason
    ):
        reasons.append(ComparisonReason.OFFLINE_AUTHORITY_MISSING)
    return tuple(reasons)


def _raw_precedence_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    _, decision, _ = _raw_parts(payload)
    gates = decision.get("gates")
    blockers = decision.get("blockers")
    reasons: list[ComparisonReason] = []
    if not isinstance(gates, list):
        reasons.append(ComparisonReason.KILL_SWITCH_PRECEDENCE_DRIFT)
    else:
        names = [
            gate.get("gate")
            for gate in gates
            if isinstance(gate, dict)
        ]
        if tuple(names) != tuple(name.value for name in manifest.gate.gate_names):
            reasons.append(ComparisonReason.KILL_SWITCH_PRECEDENCE_DRIFT)
    if (
        not isinstance(blockers, list)
        or tuple(blockers[:6])
        != manifest.gate.default_off_reason_codes
    ):
        reasons.append(ComparisonReason.DEFAULT_OFF_PRECEDENCE_DRIFT)
    return tuple(reasons)


def _raw_production_claim_reasons(
    payload: object | None,
) -> tuple[ComparisonReason, ...]:
    report, decision, observability = _raw_parts(payload)
    reasons: list[ComparisonReason] = []
    release_inputs = report.get("releaseInputs")
    if (
        report.get("releaseDecision") != "BLOCKED"
        or not isinstance(release_inputs, dict)
        or any(release_inputs.values())
        or decision.get("decision") != "BLOCKED"
    ):
        reasons.append(ComparisonReason.READINESS_CLAIMED)
    if observability.get("productionQualityStatus") != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_QUALITY_CLAIMED)
    if observability.get("productionLatencyStatus") != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_LATENCY_CLAIMED)
    if (
        observability.get("productionCostStatus") != "UNKNOWN"
        or observability.get("pricingApproved") is not False
    ):
        reasons.append(ComparisonReason.PRODUCTION_COST_CLAIMED)
    gates = decision.get("gates")
    if isinstance(gates, list):
        gate_status = {
            gate.get("gate"): gate.get("status")
            for gate in gates
            if isinstance(gate, dict)
        }
        if gate_status.get("PRODUCTION_QUALITY_EVIDENCE") != "UNKNOWN":
            reasons.append(ComparisonReason.PRODUCTION_QUALITY_CLAIMED)
        if gate_status.get("PRODUCTION_LATENCY_EVIDENCE") != "UNKNOWN":
            reasons.append(ComparisonReason.PRODUCTION_LATENCY_CLAIMED)
        if gate_status.get("PRODUCTION_COST_EVIDENCE") != "UNKNOWN":
            reasons.append(ComparisonReason.PRODUCTION_COST_CLAIMED)
        if any(
            gate_status.get(name) == "PASS"
            for name in (
                "PRIVACY_APPROVAL",
                "POLICY_APPROVAL",
                "ROLLOUT_APPROVAL",
            )
        ):
            reasons.append(ComparisonReason.READINESS_CLAIMED)
    return tuple(reasons)


def _raw_freshness_reasons(
    payload: object | None,
    manifest: EvidenceManifest,
    evaluated_at: datetime,
) -> tuple[ComparisonReason, ...]:
    if not isinstance(payload, dict):
        return (ComparisonReason.EVIDENCE_WINDOW_INVALID,)
    generated = _datetime(payload.get("generatedAt"))
    expires = _datetime(payload.get("expiresAt"))
    if generated is None or expires is None:
        return (ComparisonReason.EVIDENCE_WINDOW_INVALID,)
    reasons = list(
        _window_reasons(
            manifest.generated_at,
            manifest.expires_at,
            evaluated_at,
            manifest.maximum_evidence_age_seconds,
        )
    )
    reasons.extend(
        _window_reasons(
            generated,
            expires,
            evaluated_at,
            manifest.maximum_evidence_age_seconds,
        )
    )
    _, decision, _ = _raw_parts(payload)
    decision_at = _datetime(decision.get("decisionAt"))
    if decision_at is None or decision_at < generated:
        reasons.append(ComparisonReason.EVIDENCE_WINDOW_INVALID)
    elif decision_at > evaluated_at:
        reasons.append(ComparisonReason.EVIDENCE_TIMESTAMP_IN_FUTURE)
    return _unique_reasons(reasons)


def _identity_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report = candidate.report
    pins = manifest.report
    identities = (
        report.schema_version == pins.schema_version,
        report.runner_version == pins.runner_version,
        report.fixture_version == pins.fixture_version,
        report.fixture_sha256 == pins.fixture_sha256,
        report.proposal_schema_version == pins.proposal_schema_version,
        report.tool_registry_version == pins.tool_registry_version,
        report.instruction_version == pins.instruction_version,
        report.seed == pins.seed,
        candidate.decision.schema_version
        == manifest.gate.decision_schema_version,
        candidate.decision.input_schema_version
        == manifest.gate.input_schema_version,
        candidate.decision.observability is not None
        and candidate.decision.observability.schema_version
        == manifest.gate.observability_schema_version,
    )
    return () if all(identities) else (ComparisonReason.IDENTITY_MISMATCH,)


def _digest_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    reasons: list[ComparisonReason] = []
    if (
        candidate.report_sha256 != offline_report_sha256(candidate.report)
        or candidate.report_sha256
        != manifest.report.baseline_report_sha256
    ):
        reasons.append(ComparisonReason.REPORT_DIGEST_MISMATCH)
    if (
        candidate.timestamped_report_sha256
        != timestamped_report_sha256(
            candidate.report,
            candidate.generated_at,
        )
        or candidate.timestamped_report_sha256
        != manifest.report.baseline_timestamped_report_sha256
    ):
        reasons.append(ComparisonReason.TIMESTAMPED_REPORT_DIGEST_MISMATCH)
    if (
        candidate.decision_sha256 != model_evidence_sha256(candidate.decision)
        or candidate.decision_sha256
        != manifest.gate.baseline_decision_sha256
    ):
        reasons.append(ComparisonReason.DECISION_DIGEST_MISMATCH)
    assert candidate.decision.observability is not None
    if (
        candidate.observability_sha256
        != model_evidence_sha256(candidate.decision.observability)
        or candidate.observability_sha256
        != manifest.gate.baseline_observability_sha256
    ):
        reasons.append(ComparisonReason.OBSERVABILITY_DIGEST_MISMATCH)
    return tuple(reasons)


def _case_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    report = candidate.report
    ids = tuple(case.case_id for case in report.case_results)
    reasons: list[ComparisonReason] = []
    if report.case_count != manifest.report.case_count:
        reasons.append(ComparisonReason.CASE_COUNT_MISMATCH)
    if ids != manifest.report.case_ids or len(set(ids)) != len(ids):
        reasons.append(ComparisonReason.CASE_SET_MISMATCH)
    if (
        report.baseline_failure_reasons != manifest.report.failure_reasons
        or any(not case.passed for case in report.case_results)
    ):
        reasons.append(ComparisonReason.FAILURE_COVERAGE_LOSS)
    return tuple(reasons)


def _metric_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    baseline = {pin.name: pin for pin in manifest.report.metrics}
    current = candidate.report.metrics
    reasons: list[ComparisonReason] = []
    if set(current) != set(baseline):
        reasons.append(ComparisonReason.METRIC_SET_MISMATCH)
        return tuple(reasons)
    for name, pin in baseline.items():
        metric = current[name]
        if metric.denominator < pin.denominator:
            reasons.append(ComparisonReason.METRIC_COVERAGE_LOSS)
        if metric.value < pin.value:
            reasons.append(ComparisonReason.METRIC_REGRESSION)
    return _unique_reasons(reasons)


def _threshold_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    baseline = {pin.metric: pin for pin in manifest.report.thresholds}
    current = {item.metric: item for item in candidate.report.thresholds}
    if set(current) != set(baseline):
        return (ComparisonReason.THRESHOLD_SET_MISMATCH,)
    for name, pin in baseline.items():
        threshold = current[name]
        if (
            threshold.comparator != pin.comparator
            or threshold.threshold != pin.threshold
        ):
            return (ComparisonReason.THRESHOLD_WEAKENED,)
    return ()


def _usage_reasons(
    candidate: OfflineEvidenceBundle,
) -> tuple[ComparisonReason, ...]:
    usage = candidate.report.usage
    reasons: list[ComparisonReason] = []
    if usage.provider_requests != 0:
        reasons.append(ComparisonReason.NONZERO_PROVIDER_REQUESTS)
    if usage.input_tokens != 0 or usage.output_tokens != 0:
        reasons.append(ComparisonReason.NONZERO_TOKENS)
    if usage.estimated_cost != Decimal("0"):
        reasons.append(ComparisonReason.NONZERO_COST)
    return tuple(reasons)


def _latency_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    latency = candidate.report.simulated_latency
    pins = manifest.report
    reasons: list[ComparisonReason] = []
    if latency.classification != pins.simulated_latency_classification:
        reasons.append(ComparisonReason.SIMULATED_LATENCY_LABEL_MISMATCH)
    if latency.production_slo_eligible:
        reasons.append(ComparisonReason.SIMULATED_LATENCY_PRODUCTION_CLAIM)
    maximum = (
        pins.baseline_simulated_latency_p95_ms
        + pins.maximum_simulated_latency_regression_ms
    )
    if latency.p95_ms > maximum:
        reasons.append(ComparisonReason.SIMULATED_LATENCY_REGRESSION)
    return tuple(reasons)


def _observability_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    observability = candidate.decision.observability
    if observability is None:
        return (ComparisonReason.OBSERVABILITY_SCHEMA_MISMATCH,)
    reasons: list[ComparisonReason] = []
    if observability.schema_version != manifest.gate.observability_schema_version:
        reasons.append(ComparisonReason.OBSERVABILITY_SCHEMA_MISMATCH)
    names = tuple(metric.name.value for metric in observability.metrics)
    if (
        names
        != tuple(name.value for name in manifest.observability.metric_names)
        or len(set(names)) != len(names)
    ):
        reasons.append(ComparisonReason.OBSERVABILITY_METRIC_SET_MISMATCH)
    return tuple(reasons)


def _decision_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    decision = candidate.decision
    reasons: list[ComparisonReason] = []
    if decision.decision != manifest.gate.decision:
        reasons.append(ComparisonReason.DECISION_NOT_BLOCKED)
    if decision.release_authorized or manifest.gate.release_authorized:
        reasons.append(ComparisonReason.RELEASE_AUTHORIZED)
    if (
        not decision.blockers
        or decision.blockers[-1] != manifest.gate.offline_authority_reason
    ):
        reasons.append(ComparisonReason.OFFLINE_AUTHORITY_MISSING)
    return tuple(reasons)


def _precedence_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
) -> tuple[ComparisonReason, ...]:
    gates = candidate.decision.gates
    names = tuple(gate.gate.value for gate in gates)
    reasons: list[ComparisonReason] = []
    if names != tuple(name.value for name in manifest.gate.gate_names) or names[
        :7
    ] != tuple(
        gate.value
        for gate in (
            GateName.GLOBAL_KILL_SWITCH,
            GateName.PROPOSAL_CAPABILITY,
            GateName.MEDIA_TOOL,
            GateName.PROVIDER_EXECUTION,
            GateName.AGENT_API,
            GateName.GATEWAY_EXPOSURE,
            GateName.FRONTEND_ENTRY,
        )
    ):
        reasons.append(ComparisonReason.KILL_SWITCH_PRECEDENCE_DRIFT)
    if tuple(candidate.decision.blockers[:6]) != (
        manifest.gate.default_off_reason_codes
    ):
        reasons.append(ComparisonReason.DEFAULT_OFF_PRECEDENCE_DRIFT)
    return tuple(reasons)


def _production_claim_reasons(
    candidate: OfflineEvidenceBundle,
) -> tuple[ComparisonReason, ...]:
    report = candidate.report
    decision = candidate.decision
    observability = decision.observability
    reasons: list[ComparisonReason] = []
    if report.release_decision != "BLOCKED" or any(
        report.release_inputs.model_dump().values()
    ):
        reasons.append(ComparisonReason.READINESS_CLAIMED)
    if observability is None:
        return _unique_reasons(
            (*reasons, ComparisonReason.READINESS_CLAIMED)
        )
    if observability.production_quality_status != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_QUALITY_CLAIMED)
    if observability.production_latency_status != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_LATENCY_CLAIMED)
    if observability.production_cost_status != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_COST_CLAIMED)
    if observability.pricing_approved:
        reasons.append(ComparisonReason.PRODUCTION_COST_CLAIMED)
    gate_status = {gate.gate: gate.status for gate in decision.gates}
    if gate_status.get(GateName.PRODUCTION_QUALITY_EVIDENCE) != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_QUALITY_CLAIMED)
    if gate_status.get(GateName.PRODUCTION_LATENCY_EVIDENCE) != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_LATENCY_CLAIMED)
    if gate_status.get(GateName.PRODUCTION_COST_EVIDENCE) != "UNKNOWN":
        reasons.append(ComparisonReason.PRODUCTION_COST_CLAIMED)
    if any(
        gate_status.get(gate) == "PASS"
        for gate in (
            GateName.PRIVACY_APPROVAL,
            GateName.POLICY_APPROVAL,
            GateName.ROLLOUT_APPROVAL,
        )
    ):
        reasons.append(ComparisonReason.READINESS_CLAIMED)
    return _unique_reasons(reasons)


def _freshness_reasons(
    candidate: OfflineEvidenceBundle,
    manifest: EvidenceManifest,
    evaluated_at: datetime,
) -> tuple[ComparisonReason, ...]:
    reasons: list[ComparisonReason] = []
    for generated, expires in (
        (manifest.generated_at, manifest.expires_at),
        (candidate.generated_at, candidate.expires_at),
    ):
        reasons.extend(
            _window_reasons(
                generated,
                expires,
                evaluated_at,
                manifest.maximum_evidence_age_seconds,
            )
        )
    if candidate.decision.decision_at < candidate.generated_at:
        reasons.append(ComparisonReason.EVIDENCE_WINDOW_INVALID)
    if candidate.decision.decision_at > evaluated_at:
        reasons.append(ComparisonReason.EVIDENCE_TIMESTAMP_IN_FUTURE)
    return _unique_reasons(reasons)


def _check(
    name: str,
    reasons: tuple[ComparisonReason, ...] | list[ComparisonReason],
) -> ComparisonCheck:
    bounded = _unique_reasons(reasons)
    return ComparisonCheck(
        name=name,
        passed=not bounded,
        reasonCodes=bounded,
    )


def _build_comparison(
    *,
    checks: tuple[ComparisonCheck, ...],
    evaluated_at: datetime,
    manifest_sha: str,
    candidate_sha: str | None,
    manifest: EvidenceManifest | None,
    candidate: OfflineEvidenceBundle | None,
) -> RegressionComparison:
    reasons = _unique_reasons(
        reason for check in checks for reason in check.reason_codes
    )
    payload = {
        "schemaVersion": COMPARISON_SCHEMA_VERSION,
        "comparatorVersion": COMPARATOR_VERSION,
        "manifestVersion": (
            manifest.manifest_version if manifest is not None else "UNKNOWN"
        ),
        "evaluatedAt": evaluated_at,
        "regressionStatus": (
            RegressionStatus.PASS if not reasons else RegressionStatus.FAIL
        ),
        "rolloutDecision": "BLOCKED",
        "releaseAuthorized": False,
        "reasonCodes": reasons,
        "checks": checks,
        "manifestSha256": manifest_sha,
        "candidateBundleSha256": candidate_sha,
        "baselineReportSha256": (
            manifest.report.baseline_report_sha256
            if manifest is not None
            else None
        ),
        "candidateReportSha256": (
            candidate.report_sha256 if candidate is not None else None
        ),
    }
    digest = raw_evidence_sha256(payload)
    return RegressionComparison.model_validate(
        {**payload, "comparisonSha256": digest}
    )


def comparison_evidence_sha256(comparison: RegressionComparison) -> str:
    """Hash the canonical comparison without its self-referential digest."""

    return raw_evidence_sha256(
        comparison.model_dump(
            mode="json",
            by_alias=True,
            exclude={"comparison_sha256"},
        )
    )


def _unique_reasons(
    reasons: Iterable[ComparisonReason],
) -> tuple[ComparisonReason, ...]:
    unique: list[ComparisonReason] = []
    for reason in reasons:
        reason = ComparisonReason(reason)
        if reason not in unique:
            unique.append(reason)
    return tuple(unique)


def _read_json(path: Path | None) -> object | None:
    if path is None or not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return "MALFORMED_JSON"


def _window_reasons(
    generated_at: datetime,
    expires_at: datetime,
    evaluated_at: datetime,
    maximum_age_seconds: int,
) -> tuple[ComparisonReason, ...]:
    issues = evidence_freshness_issues(
        generated_at=generated_at,
        evaluated_at=evaluated_at,
        expires_at=expires_at,
        maximum_age=timedelta(seconds=maximum_age_seconds),
    )
    mapping = {
        EvidenceFreshnessIssue.TIMESTAMP_IN_FUTURE: (
            ComparisonReason.EVIDENCE_TIMESTAMP_IN_FUTURE
        ),
        EvidenceFreshnessIssue.WINDOW_INVALID: (
            ComparisonReason.EVIDENCE_WINDOW_INVALID
        ),
        EvidenceFreshnessIssue.STALE: ComparisonReason.EVIDENCE_STALE,
    }
    return tuple(mapping[issue] for issue in issues)


def _parse_timestamp(value: str) -> datetime:
    parsed = _datetime(value)
    if parsed is None:
        raise argparse.ArgumentTypeError("timestamp must include a UTC offset")
    return parsed


def main(argv: list[str] | None = None) -> int:
    """Compare current or supplied offline evidence against the pinned manifest."""

    parser = argparse.ArgumentParser(
        description="Compare AI-LIST offline evidence"
    )
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--evaluated-at", type=_parse_timestamp)
    parser.add_argument(
        "--schema",
        choices=("manifest", "bundle", "comparison"),
    )
    parser.add_argument("--digest", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.schema:
        model = {
            "manifest": EvidenceManifest,
            "bundle": OfflineEvidenceBundle,
            "comparison": RegressionComparison,
        }[arguments.schema]
        print(
            json.dumps(
                model.model_json_schema(by_alias=True),
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    if arguments.evaluated_at is None:
        parser.error("--evaluated-at is required unless --schema is used")
    candidate_payload = _read_json(arguments.candidate)
    if arguments.candidate is None:
        candidate_payload = asyncio.run(
            build_current_bundle()
        ).model_dump(mode="json", by_alias=True)
    manifest_payload = _read_json(arguments.manifest)
    comparison = compare_evidence(
        candidate_payload,
        manifest_payload,
        arguments.evaluated_at,
    )
    if arguments.digest:
        print(
            json.dumps(
                {"comparisonSha256": comparison.comparison_sha256},
                sort_keys=True,
            )
        )
    else:
        print(
            json.dumps(
                comparison.model_dump(mode="json", by_alias=True),
                indent=2,
                sort_keys=True,
            )
        )
    return 0 if comparison.regression_status == RegressionStatus.PASS else 1


if __name__ == "__main__":
    raise SystemExit(main())
