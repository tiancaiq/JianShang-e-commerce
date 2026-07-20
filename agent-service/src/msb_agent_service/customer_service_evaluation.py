from __future__ import annotations

import argparse
import asyncio
import json
import math
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from enum import StrEnum
from pathlib import Path
from typing import Any, Literal

from prometheus_client import CollectorRegistry
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .agent_persistence import (
    AgentInvocation,
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    BeginInvocation,
    BeginInvocationResult,
)
from .customer_service_api import AgentApiError, ListingContext
from .customer_service_orchestration import (
    CustomerServiceModelRequest,
    CustomerServiceModelResult,
    ListingCustomerServiceOrchestrator,
    ModelAnswerCandidate,
    ModelUsage,
)
from .customer_service_orchestration_metrics import (
    CustomerServiceOrchestrationMetrics,
)
from .embedding_provider import EmbeddingBatchResult
from .errors import LlmProviderError, ProviderErrorCode
from .knowledge_retriever import (
    KnowledgeRetrievalMetrics,
    OpenSearchListingKnowledgeRetriever,
)

RUNNER_VERSION = "ai-cs-01e-a-runner-v1"
REPORT_SCHEMA_VERSION = "ai-cs-offline-evaluation-report-v1"
DEFAULT_FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "evals"
    / "ai_cs_01e_offline_baseline_v1.json"
)
_NOW = datetime(2026, 7, 20, 0, 0, tzinfo=UTC)
_ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
_SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
_INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
_CLIENT_MESSAGE = "01ARZ3NDEKTSV4RRFFQ69G5FB2"
_ALLOWED_TOOLS = {"getListing", "retrieveKnowledge"}
_REQUIRED_METRICS = {
    "retrieval_relevance",
    "retrieval_recall",
    "answer_faithfulness",
    "citation_validity",
    "citation_completeness",
    "rejected_source_safety",
    "cross_user_isolation",
    "allowlisted_tool_boundary",
    "failure_handling",
    "outcome_conformance",
}
_REQUIRED_RELEASE_BLOCKERS = {
    "CUSTOMER_SERVICE_CAPABILITY_DEFAULT_OFF",
    "PROVIDER_EXECUTION_DEFAULT_OFF",
    "RETRIEVAL_ACTIVATION_DEFAULT_OFF",
    "GATEWAY_EXPOSURE_DEFAULT_OFF",
    "FRONTEND_ENTRY_POINT_DEFAULT_OFF",
    "LIVE_QUALITY_NOT_EVALUATED",
    "PRODUCTION_LATENCY_NOT_EVALUATED",
    "PRICING_UNAPPROVED",
    "ROLLOUT_NOT_APPROVED",
}


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


class EvaluationScenario(StrEnum):
    NORMAL = "NORMAL"
    INDEX_UNAVAILABLE = "INDEX_UNAVAILABLE"
    INVALID_SEARCH_RESPONSE = "INVALID_SEARCH_RESPONSE"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    PROVIDER_RATE_LIMITED = "PROVIDER_RATE_LIMITED"
    PROVIDER_TIMED_OUT = "PROVIDER_TIMED_OUT"
    INVALID_PROVIDER_CITATION = "INVALID_PROVIDER_CITATION"


class OfflineSearchHit(_StrictModel):
    chunk_id: str = Field(min_length=1, max_length=160)
    source_id: str = Field(min_length=1, max_length=160)
    source_version: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    listing_id: str = Field(min_length=1, max_length=160)
    text: str = Field(min_length=1, max_length=2_400)
    ordinal: int = Field(ge=0, le=100_000)
    score: float = Field(ge=0)
    invalidated_at: datetime | None = None
    effective_from: datetime | None = None
    effective_to: datetime | None = None
    language: str = Field(default="und", min_length=2, max_length=40)

    @field_validator("invalidated_at", "effective_from", "effective_to")
    @classmethod
    def timezone_aware_time(
        cls,
        value: datetime | None,
    ) -> datetime | None:
        if value is not None and (
            value.tzinfo is None or value.utcoffset() is None
        ):
            raise ValueError("fixture timestamps must include a UTC offset")
        return value


class OfflineClaim(_StrictModel):
    answer_text: str = Field(min_length=1, max_length=500)
    supporting_source_ids: tuple[str, ...] = Field(min_length=1, max_length=8)


class OfflineModelAnswer(_StrictModel):
    body: str = Field(min_length=1, max_length=12_000)
    resolution_type: Literal[
        "ANSWERED", "PARTIAL", "UNKNOWN", "CONTACT_SELLER", "REFUSED"
    ]
    source_id: str | None = None
    source_version: str | None = None


class OfflineEvaluationCase(_StrictModel):
    id: str = Field(pattern=r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
    scenario: EvaluationScenario = EvaluationScenario.NORMAL
    question: str = Field(min_length=1, max_length=2_000)
    listing_id: str = Field(min_length=1, max_length=160)
    listing_version: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    listing_title: str = Field(min_length=1, max_length=180)
    transaction_notice: str = Field(min_length=1, max_length=1_000)
    hits: tuple[OfflineSearchHit, ...] = Field(default=(), max_length=32)
    model_answer: OfflineModelAnswer | None = None
    claims: tuple[OfflineClaim, ...] = Field(default=(), max_length=8)
    expected_relevant_chunk_ids: tuple[str, ...] = Field(default=(), max_length=8)
    expected_rejected_chunk_ids: tuple[str, ...] = Field(default=(), max_length=16)
    expected_resolution: Literal[
        "ANSWERED", "PARTIAL", "UNKNOWN", "CONTACT_SELLER", "REFUSED"
    ] | None = None
    expected_error_code: Literal["AGENT_ORCHESTRATION_UNAVAILABLE"] | None = None
    expected_tools: tuple[str, ...] = Field(default=(), max_length=2)
    simulated_latency_ms: int = Field(ge=0, le=60_000)

    @model_validator(mode="after")
    def exactly_one_expected_outcome(self) -> "OfflineEvaluationCase":
        if (self.expected_resolution is None) == (self.expected_error_code is None):
            raise ValueError(
                "exactly one of expectedResolution or expectedErrorCode is required"
            )
        if any(tool not in _ALLOWED_TOOLS for tool in self.expected_tools):
            raise ValueError("expectedTools contains a non-allowlisted tool")
        return self


class ReleaseSwitches(_StrictModel):
    customer_service_capability: bool = False
    provider_execution: bool = False
    retrieval_activation: bool = False
    gateway_exposure: bool = False
    frontend_entry_point: bool = False


class OfflineEvaluationFixture(_StrictModel):
    fixture_version: str
    seed: int = Field(ge=0)
    generated_at: datetime
    release_switches: ReleaseSwitches
    cases: tuple[OfflineEvaluationCase, ...] = Field(min_length=1, max_length=200)

    @field_validator("generated_at")
    @classmethod
    def generated_time_is_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("generatedAt must include a UTC offset")
        return value

    @model_validator(mode="after")
    def unique_case_ids_and_default_off(self) -> "OfflineEvaluationFixture":
        ids = [case.id for case in self.cases]
        if len(ids) != len(set(ids)):
            raise ValueError("fixture case IDs must be unique")
        if any(self.release_switches.model_dump().values()):
            raise ValueError("offline evaluation release switches must remain disabled")
        return self


class MetricResult(_StrictModel):
    value: Decimal = Field(ge=0)
    numerator: int = Field(ge=0)
    denominator: int = Field(gt=0)

    @model_validator(mode="after")
    def value_matches_ratio(self) -> "MetricResult":
        if self.numerator > self.denominator:
            raise ValueError("metric numerator must not exceed denominator")
        expected = (
            Decimal(self.numerator) / Decimal(self.denominator)
        ).quantize(Decimal("0.0001"))
        if self.value != expected:
            raise ValueError("metric value must equal numerator / denominator")
        return self


class ThresholdResult(_StrictModel):
    metric: str
    comparator: Literal["GE", "LE", "EQ"]
    threshold: Decimal = Field(ge=0)
    actual: Decimal = Field(ge=0)
    provisional: bool = True
    passed: bool

    @model_validator(mode="after")
    def pass_flag_matches_comparison(self) -> "ThresholdResult":
        if self.passed != _compare(self.actual, self.comparator, self.threshold):
            raise ValueError("threshold pass flag must match its comparison")
        return self


class CaseEvaluationResult(_StrictModel):
    case_id: str
    passed: bool
    failure_reasons: tuple[str, ...]
    returned_chunk_ids: tuple[str, ...]
    rejected_chunk_ids: tuple[str, ...]
    tool_names: tuple[str, ...]
    resolution: str | None
    error_code: str | None
    simulated_latency_ms: int

    @model_validator(mode="after")
    def pass_flag_matches_failures(self) -> "CaseEvaluationResult":
        if self.passed == bool(self.failure_reasons):
            raise ValueError("case pass flag must match failure reasons")
        if any(tool not in _ALLOWED_TOOLS for tool in self.tool_names):
            raise ValueError("case result contains a non-allowlisted tool")
        return self


class OfflineCostMetadata(_StrictModel):
    mode: Literal["ZERO_COST_OFFLINE"] = "ZERO_COST_OFFLINE"
    provider_requests: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    estimated_cost: Decimal = Decimal("0")
    pricing_approved: bool = False

    @model_validator(mode="after")
    def remains_zero_cost_and_unpriced(self) -> "OfflineCostMetadata":
        if (
            self.provider_requests != 0
            or self.input_tokens != 0
            or self.output_tokens != 0
            or self.estimated_cost != 0
            or self.pricing_approved
        ):
            raise ValueError("offline evaluation must remain zero-cost and unpriced")
        return self


class OfflineEvaluationReport(_StrictModel):
    schema_version: Literal["ai-cs-offline-evaluation-report-v1"]
    runner_version: str
    fixture_version: str
    seed: int
    prompt_version: str
    tool_registry_version: str
    policy_version: str
    case_count: int = Field(gt=0)
    case_results: tuple[CaseEvaluationResult, ...]
    metrics: dict[str, MetricResult]
    simulated_latency_p95_ms: int = Field(ge=0)
    thresholds: tuple[ThresholdResult, ...]
    baseline_status: Literal["PASS", "FAIL"]
    baseline_failure_reasons: tuple[str, ...]
    release_decision: Literal["BLOCKED"]
    release_blockers: tuple[str, ...] = Field(min_length=1)
    release_switches: ReleaseSwitches
    cost: OfflineCostMetadata

    @model_validator(mode="after")
    def internally_consistent_and_release_blocked(self) -> "OfflineEvaluationReport":
        if self.case_count != len(self.case_results):
            raise ValueError("caseCount must equal the number of case results")
        if len({case.case_id for case in self.case_results}) != self.case_count:
            raise ValueError("case result IDs must be unique")
        expected_failures = tuple(
            sorted(
                (
                    *(
                        f"THRESHOLD_FAILED:{threshold.metric}"
                        for threshold in self.thresholds
                        if not threshold.passed
                    ),
                    *(
                        f"CASE_FAILED:{case.case_id}"
                        for case in self.case_results
                        if not case.passed
                    ),
                )
            )
        )
        if self.baseline_failure_reasons != expected_failures:
            raise ValueError(
                "baseline failure reasons must match failed thresholds and cases"
            )
        expected_status = "PASS" if not expected_failures else "FAIL"
        if self.baseline_status != expected_status:
            raise ValueError("baseline status must match failure reasons")
        if any(self.release_switches.model_dump().values()):
            raise ValueError("release switches must remain disabled")
        if set(self.metrics) != _REQUIRED_METRICS:
            raise ValueError("report must contain the complete metric contract")
        expected_thresholds = _REQUIRED_METRICS | {"simulated_latency_p95_ms"}
        threshold_names = [item.metric for item in self.thresholds]
        if (
            set(threshold_names) != expected_thresholds
            or len(threshold_names) != len(expected_thresholds)
        ):
            raise ValueError("report must contain one threshold per metric")
        if not _REQUIRED_RELEASE_BLOCKERS.issubset(self.release_blockers):
            raise ValueError("report is missing a mandatory release blocker")
        return self


@dataclass
class _OfflineAuditRepository:
    calls: list[dict[str, object]]

    async def append_tool_call(self, **kwargs: object) -> object:
        self.calls.append(kwargs)
        return object()


class _OfflineEmbeddingProvider:
    async def embed(
        self,
        texts: list[str],
        *,
        correlation_id: str | None,
    ) -> EmbeddingBatchResult:
        return EmbeddingBatchResult(
            vectors=tuple((0.1, 0.2, 0.3) for _ in texts),
            provider="offline",
            model="offline-embedding-v1",
            dimensions=3,
            input_tokens=0,
        )

    async def close(self) -> None:
        return None


class _OfflineSearchClient:
    def __init__(self, case: OfflineEvaluationCase) -> None:
        self._case = case
        self.bodies: list[dict[str, object]] = []

    async def search(self, *, index: str, body: dict[str, object]) -> object:
        self.bodies.append(body)
        if self._case.scenario == EvaluationScenario.INDEX_UNAVAILABLE:
            raise RuntimeError("offline index unavailable")
        if self._case.scenario == EvaluationScenario.INVALID_SEARCH_RESPONSE:
            return {"hits": {"hits": "invalid"}}
        return {
            "hits": {
                "hits": [
                    {
                        "_score": hit.score,
                        "_source": {
                            "chunkId": hit.chunk_id,
                            "sourceType": "LISTING",
                            "sourceId": hit.source_id,
                            "sourceVersion": hit.source_version,
                            "contentHash": "a" * 64,
                            "listingId": hit.listing_id,
                            "visibility": "PUBLIC",
                            "language": hit.language,
                            "effectiveFrom": hit.effective_from,
                            "effectiveTo": hit.effective_to,
                            "invalidatedAt": hit.invalidated_at,
                            "ordinal": hit.ordinal,
                            "sectionLabel": "Description",
                            "text": hit.text,
                        },
                    }
                    for hit in self._case.hits
                ]
            }
        }


class _OfflineModel:
    provider_name = "offline"
    model_name = "offline-scripted-v1"

    def __init__(self, case: OfflineEvaluationCase) -> None:
        self._case = case
        self.requests: list[CustomerServiceModelRequest] = []

    async def generate(
        self,
        request: CustomerServiceModelRequest,
        *,
        correlation_id: str,
    ) -> CustomerServiceModelResult:
        self.requests.append(request)
        error_code = {
            EvaluationScenario.PROVIDER_UNAVAILABLE: ProviderErrorCode.UNAVAILABLE,
            EvaluationScenario.PROVIDER_RATE_LIMITED: ProviderErrorCode.RATE_LIMITED,
            EvaluationScenario.PROVIDER_TIMED_OUT: ProviderErrorCode.TIMED_OUT,
        }.get(self._case.scenario)
        if error_code is not None:
            raise LlmProviderError(
                error_code,
                "offline scripted provider failure",
                retryable=True,
                status_code=503,
            )
        answer = self._case.model_answer
        if answer is None:
            raise RuntimeError("offline case did not define a model answer")
        source_id = answer.source_id
        source_version = answer.source_version
        if self._case.scenario == EvaluationScenario.INVALID_PROVIDER_CITATION:
            source_id = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
            source_version = "1"
        sources: list[dict[str, str]] = []
        if source_id is not None and source_version is not None:
            sources.append(
                {
                    "sourceType": "LISTING",
                    "sourceId": source_id,
                    "sourceVersion": source_version,
                    "label": "Current listing",
                }
            )
        return CustomerServiceModelResult(
            answer=ModelAnswerCandidate(
                body=answer.body,
                resolutionType=answer.resolution_type,
                sources=sources,
                actions=[],
            ),
            usage=ModelUsage(),
        )


def load_offline_fixture(path: Path = DEFAULT_FIXTURE) -> OfflineEvaluationFixture:
    """Load a strict, versioned fixture without consulting runtime configuration."""

    return OfflineEvaluationFixture.model_validate_json(path.read_text(encoding="utf-8"))


async def run_offline_evaluation(
    fixture: OfflineEvaluationFixture,
) -> OfflineEvaluationReport:
    """Evaluate current listing-only boundaries with deterministic local fakes."""

    case_results: list[CaseEvaluationResult] = []
    totals = {
        "retrieval_relevance": [0, 0],
        "retrieval_recall": [0, 0],
        "answer_faithfulness": [0, 0],
        "citation_validity": [0, 0],
        "citation_completeness": [0, 0],
        "rejected_source_safety": [0, 0],
        "cross_user_isolation": [0, 0],
        "allowlisted_tool_boundary": [0, 0],
        "failure_handling": [0, 0],
        "outcome_conformance": [0, 0],
    }
    for case in fixture.cases:
        result, measurements = await _run_case(case)
        case_results.append(result)
        for metric, (numerator, denominator) in measurements.items():
            totals[metric][0] += numerator
            totals[metric][1] += denominator

    metrics = {
        name: _metric(*values)
        for name, values in totals.items()
        if values[1] > 0
    }
    simulated_p95 = _nearest_rank_p95(
        [case.simulated_latency_ms for case in fixture.cases]
    )
    thresholds = _thresholds(metrics, simulated_p95)
    failed_thresholds = [
        f"THRESHOLD_FAILED:{threshold.metric}"
        for threshold in thresholds
        if not threshold.passed
    ]
    failed_cases = [
        f"CASE_FAILED:{case.case_id}" for case in case_results if not case.passed
    ]
    baseline_failures = tuple(sorted((*failed_thresholds, *failed_cases)))
    baseline_status: Literal["PASS", "FAIL"] = (
        "PASS" if not baseline_failures else "FAIL"
    )
    release_blockers = sorted(_REQUIRED_RELEASE_BLOCKERS)
    if baseline_status == "FAIL":
        release_blockers.append("OFFLINE_BASELINE_FAILED")
    return OfflineEvaluationReport(
        schemaVersion=REPORT_SCHEMA_VERSION,
        runnerVersion=RUNNER_VERSION,
        fixtureVersion=fixture.fixture_version,
        seed=fixture.seed,
        promptVersion=ListingCustomerServiceOrchestrator.PROMPT_VERSION,
        toolRegistryVersion=ListingCustomerServiceOrchestrator.TOOL_REGISTRY_VERSION,
        policyVersion=ListingCustomerServiceOrchestrator.POLICY_VERSION,
        caseCount=len(case_results),
        caseResults=case_results,
        metrics=metrics,
        simulatedLatencyP95Ms=simulated_p95,
        thresholds=thresholds,
        baselineStatus=baseline_status,
        baselineFailureReasons=baseline_failures,
        releaseDecision="BLOCKED",
        releaseBlockers=release_blockers,
        releaseSwitches=fixture.release_switches,
        cost=OfflineCostMetadata(),
    )


async def _run_case(
    case: OfflineEvaluationCase,
) -> tuple[CaseEvaluationResult, dict[str, tuple[int, int]]]:
    repository = _OfflineAuditRepository([])
    search = _OfflineSearchClient(case)
    model = _OfflineModel(case)
    retriever = OpenSearchListingKnowledgeRetriever(
        client=search,  # type: ignore[arg-type]
        read_alias="listing-knowledge-read",
        embedding_provider=_OfflineEmbeddingProvider(),
        embedding_provider_name="offline",
        embedding_model="offline-embedding-v1",
        embedding_dimensions=3,
        metrics=KnowledgeRetrievalMetrics(CollectorRegistry()),
    )
    orchestrator = ListingCustomerServiceOrchestrator(
        repository=repository,
        retriever=retriever,
        model=model,
        metrics=CustomerServiceOrchestrationMetrics(CollectorRegistry()),
    )
    resolution: str | None = None
    error_code: str | None = None
    sources: tuple[dict[str, object], ...] = ()
    body = ""
    try:
        answer = await orchestrator.answer(
            begin=_begin(case.question),
            actor_user_id=_ACTOR,
            listing=ListingContext(
                listing_id=case.listing_id,
                source_version=case.listing_version,
                title=case.listing_title,
                thumbnail_url=None,
                transaction_notice=case.transaction_notice,
            ),
            correlation_id=f"offline-{case.id}",
        )
        resolution = answer.resolution_type.value
        body = answer.body
        sources = tuple(answer.sources)
    except AgentApiError as error:
        error_code = error.code.value

    model_passages = (
        tuple(model.requests[0].passages) if model.requests else ()
    )
    returned = tuple(passage.chunk_id for passage in model_passages)
    rejected = tuple(
        chunk_id
        for chunk_id in case.expected_rejected_chunk_ids
        if chunk_id not in returned
    )
    tools = tuple(str(call["tool_name"]) for call in repository.calls)
    failures: list[str] = []
    _expect(
        failures,
        resolution == case.expected_resolution
        and error_code == case.expected_error_code,
        "OUTCOME_MISMATCH",
    )
    _expect(failures, tools == case.expected_tools, "TOOL_SEQUENCE_MISMATCH")
    _expect(
        failures,
        set(tools).issubset(_ALLOWED_TOOLS),
        "NON_ALLOWLISTED_TOOL",
    )
    _expect(
        failures,
        all(call.get("actor_user_id") == _ACTOR for call in repository.calls),
        "TOOL_ACTOR_SCOPE_MISMATCH",
    )
    _expect(
        failures,
        set(case.expected_relevant_chunk_ids).issubset(returned),
        "RETRIEVAL_RECALL_MISS",
    )
    _expect(
        failures,
        len(rejected) == len(case.expected_rejected_chunk_ids),
        "REJECTED_SOURCE_LEAKED",
    )

    expected_citation = (
        case.expected_resolution is not None
        and case.expected_resolution != "REFUSED"
    )
    valid_sources = (not expected_citation) or (
        bool(sources)
        and all(
            source.get("sourceType") == "LISTING"
            and source.get("sourceId") == case.listing_id
            and source.get("sourceVersion") == case.listing_version
            for source in sources
        )
    )
    citation_complete = not expected_citation or bool(sources)
    _expect(failures, valid_sources, "CITATION_INVALID")
    _expect(failures, citation_complete, "CITATION_INCOMPLETE")

    available_support = set(returned) | {"CURRENT_LISTING"}
    supported_claims = sum(
        claim.answer_text in body
        and bool(set(claim.supporting_source_ids) & available_support)
        for claim in case.claims
    )
    _expect(
        failures,
        supported_claims == len(case.claims),
        "ANNOTATED_CLAIM_UNSUPPORTED",
    )
    serialized_untrusted_boundary = json.dumps(
        {
            "search": search.bodies,
            "model": [
                {
                    "listing": request.listing.model_dump(by_alias=True),
                    "passageIds": [passage.chunk_id for passage in request.passages],
                    "allowedSources": [
                        source.model_dump(by_alias=True)
                        for source in request.allowed_sources
                    ],
                }
                for request in model.requests
            ],
        },
        sort_keys=True,
        default=str,
    )
    actor_isolated = _ACTOR not in serialized_untrusted_boundary
    _expect(failures, actor_isolated, "ACTOR_IDENTIFIER_LEAKED")

    relevant_returned = len(set(returned) & set(case.expected_relevant_chunk_ids))
    expected_relevant = len(set(case.expected_relevant_chunk_ids))
    measurements: dict[str, tuple[int, int]] = {
        "outcome_conformance": (int(not failures), 1),
        "cross_user_isolation": (int(actor_isolated), 1),
        "allowlisted_tool_boundary": (
            int(
                tools == case.expected_tools
                and set(tools).issubset(_ALLOWED_TOOLS)
                and all(
                    call.get("actor_user_id") == _ACTOR
                    for call in repository.calls
                )
            ),
            1,
        ),
    }
    if returned:
        measurements["retrieval_relevance"] = (
            relevant_returned,
            len(set(returned)),
        )
    if expected_relevant:
        measurements["retrieval_recall"] = (
            relevant_returned,
            expected_relevant,
        )
    if case.claims:
        measurements["answer_faithfulness"] = (
            supported_claims,
            len(case.claims),
        )
    if expected_citation:
        measurements["citation_validity"] = (int(valid_sources), 1)
        measurements["citation_completeness"] = (int(citation_complete), 1)
    if case.expected_rejected_chunk_ids:
        measurements["rejected_source_safety"] = (
            len(rejected),
            len(case.expected_rejected_chunk_ids),
        )
    if case.expected_error_code is not None:
        measurements["failure_handling"] = (
            int(error_code == case.expected_error_code),
            1,
        )
    return (
        CaseEvaluationResult(
            caseId=case.id,
            passed=not failures,
            failureReasons=tuple(sorted(set(failures))),
            returnedChunkIds=returned,
            rejectedChunkIds=rejected,
            toolNames=tools,
            resolution=resolution,
            errorCode=error_code,
            simulatedLatencyMs=case.simulated_latency_ms,
        ),
        measurements,
    )


def _begin(question: str) -> BeginInvocation:
    invocation = AgentInvocation(
        invocation_id=_INVOCATION,
        session_id=_SESSION,
        actor_user_id=_ACTOR,
        user_message_id=_MESSAGE,
        assistant_message_id=None,
        client_message_id=_CLIENT_MESSAGE,
        request_hash="a" * 64,
        result_status=AgentInvocationStatus.PENDING,
        error_code=None,
        prompt_version=ListingCustomerServiceOrchestrator.PROMPT_VERSION,
        model_provider="offline",
        model_name="offline-scripted-v1",
        schema_version=ListingCustomerServiceOrchestrator.SCHEMA_VERSION,
        tool_registry_version=ListingCustomerServiceOrchestrator.TOOL_REGISTRY_VERSION,
        policy_version=ListingCustomerServiceOrchestrator.POLICY_VERSION,
        input_tokens=0,
        output_tokens=0,
        latency_ms=None,
        estimated_cost=Decimal("0"),
        retry_count=0,
        correlation_id="offline-evaluation",
        created_at=_NOW,
        updated_at=_NOW,
        completed_at=None,
        optimistic_version=0,
    )
    message = AgentMessage(
        message_id=_MESSAGE,
        session_id=_SESSION,
        actor_user_id=_ACTOR,
        role=AgentMessageRole.USER,
        body=question,
        resolution_type=None,
        sources=(),
        actions=(),
        created_at=_NOW,
    )
    return BeginInvocation(BeginInvocationResult.CREATED, invocation, message)


def _metric(numerator: int, denominator: int) -> MetricResult:
    value = (Decimal(numerator) / Decimal(denominator)).quantize(
        Decimal("0.0001")
    )
    return MetricResult(
        value=value,
        numerator=numerator,
        denominator=denominator,
    )


def _thresholds(
    metrics: dict[str, MetricResult],
    simulated_p95: int,
) -> tuple[ThresholdResult, ...]:
    definitions = (
        ("retrieval_relevance", "GE", Decimal("0.9000")),
        ("retrieval_recall", "GE", Decimal("0.9000")),
        ("answer_faithfulness", "EQ", Decimal("1.0000")),
        ("citation_validity", "EQ", Decimal("1.0000")),
        ("citation_completeness", "EQ", Decimal("1.0000")),
        ("rejected_source_safety", "EQ", Decimal("1.0000")),
        ("cross_user_isolation", "EQ", Decimal("1.0000")),
        ("allowlisted_tool_boundary", "EQ", Decimal("1.0000")),
        ("failure_handling", "EQ", Decimal("1.0000")),
        ("outcome_conformance", "EQ", Decimal("1.0000")),
    )
    results = [
        ThresholdResult(
            metric=name,
            comparator=comparator,
            threshold=threshold,
            actual=metrics.get(name, _metric(0, 1)).value,
            passed=_compare(
                metrics.get(name, _metric(0, 1)).value,
                comparator,
                threshold,
            ),
        )
        for name, comparator, threshold in definitions
    ]
    results.append(
        ThresholdResult(
            metric="simulated_latency_p95_ms",
            comparator="LE",
            threshold=Decimal("500"),
            actual=Decimal(simulated_p95),
            passed=simulated_p95 <= 500,
        )
    )
    return tuple(results)


def _compare(actual: Decimal, comparator: str, threshold: Decimal) -> bool:
    if comparator == "GE":
        return actual >= threshold
    if comparator == "LE":
        return actual <= threshold
    return actual == threshold


def _nearest_rank_p95(values: list[int]) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def _expect(failures: list[str], condition: bool, reason: str) -> None:
    if not condition:
        failures.append(reason)


def _json(model: BaseModel) -> str:
    return json.dumps(
        model.model_dump(mode="json", by_alias=True),
        indent=2,
        sort_keys=True,
    )


def main(argv: list[str] | None = None) -> int:
    """Emit the report or its JSON Schema without enabling any runtime provider."""

    parser = argparse.ArgumentParser(description="Run the AI-CS offline evaluation")
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--schema", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.schema:
        print(
            json.dumps(
                OfflineEvaluationReport.model_json_schema(by_alias=True),
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    report = asyncio.run(run_offline_evaluation(load_offline_fixture(arguments.fixture)))
    print(_json(report))
    return 0 if report.baseline_status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
