from __future__ import annotations

import hashlib
import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

FIXTURE_SCHEMA = "AI_DISC_SEARCH_P0_07_FIXTURE_V1"
REPORT_SCHEMA = "AI_DISC_SEARCH_P0_07_REPORT_V1"
RUNNER_VERSION = "ai-disc-search-p0-07-offline-runner-v1"


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class HybridEvaluationCase(_StrictModel):
    caseId: str = Field(pattern=r"^[a-z0-9-]{3,80}$")
    relevantIds: tuple[str, ...] = Field(min_length=1, max_length=20)
    lexicalIds: tuple[str, ...] = Field(max_length=20)
    hybridIds: tuple[str, ...] = Field(max_length=20)


class HybridEvaluationFixture(_StrictModel):
    schemaVersion: str
    fixtureVersion: str = Field(min_length=1, max_length=80)
    cases: tuple[HybridEvaluationCase, ...] = Field(min_length=1, max_length=50)


class HybridEvaluationReport(_StrictModel):
    schemaVersion: str
    fixtureVersion: str
    runnerVersion: str
    caseCount: int = Field(ge=1)
    lexicalRecall: float = Field(ge=0, le=1)
    hybridRecall: float = Field(ge=0, le=1)
    hybridNonRegression: bool
    externalProviderRequests: int = Field(ge=0)
    externalNetworkCalls: int = Field(ge=0)
    latencyEvidence: str
    releaseDecision: str
    releaseAuthorized: bool
    digest: str = Field(pattern=r"^[0-9a-f]{64}$")


def evaluate_hybrid_fixture(path: Path) -> HybridEvaluationReport:
    """Compare synthetic ranks deterministically without claiming production quality."""

    fixture = HybridEvaluationFixture.model_validate_json(path.read_bytes())
    if fixture.schemaVersion != FIXTURE_SCHEMA:
        raise ValueError("Hybrid evaluation fixture schema is incompatible")
    lexical = sum(_recall(case.lexicalIds, case.relevantIds) for case in fixture.cases)
    hybrid = sum(_recall(case.hybridIds, case.relevantIds) for case in fixture.cases)
    count = len(fixture.cases)
    payload = {
        "schemaVersion": REPORT_SCHEMA,
        "fixtureVersion": fixture.fixtureVersion,
        "runnerVersion": RUNNER_VERSION,
        "caseCount": count,
        "lexicalRecall": round(lexical / count, 4),
        "hybridRecall": round(hybrid / count, 4),
        "hybridNonRegression": hybrid >= lexical,
        "externalProviderRequests": 0,
        "externalNetworkCalls": 0,
        "latencyEvidence": "NON_PRODUCTION_SYNTHETIC",
        "releaseDecision": "BLOCKED",
        "releaseAuthorized": False,
    }
    digest = hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return HybridEvaluationReport(**payload, digest=digest)


def _recall(found: tuple[str, ...], relevant: tuple[str, ...]) -> float:
    expected = set(relevant)
    return len(expected.intersection(found)) / len(expected)
