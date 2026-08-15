from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

FIXTURE_SCHEMA = "AI_DISC_AGENT_V2_MULTICONCEPT_FIXTURE_V1"
REPORT_SCHEMA = "AI_DISC_AGENT_V2_MULTICONCEPT_REPORT_V1"
RUNNER_VERSION = "ai-disc-agent-v2-multiconcept-offline-v1"


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class RankedCandidate(_StrictModel):
    candidateId: str = Field(pattern=r"^[a-z0-9-]{3,80}$")
    label: Literal["HIGHLY_RELEVANT", "RELEVANT", "RELATED", "IRRELEVANT"]
    missingCoreConcept: bool
    displayed: bool


class MulticonceptCase(_StrictModel):
    caseId: str = Field(pattern=r"^[a-z0-9-]{3,80}$")
    query: str = Field(min_length=1, max_length=200)
    rankedCandidates: tuple[RankedCandidate, ...] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_candidates(self) -> "MulticonceptCase":
        if len({item.candidateId for item in self.rankedCandidates}) != len(
            self.rankedCandidates
        ):
            raise ValueError("Evaluation candidate IDs must be unique per query")
        return self


class MulticonceptFixture(_StrictModel):
    schemaVersion: str
    fixtureVersion: str = Field(min_length=1, max_length=80)
    cases: tuple[MulticonceptCase, ...] = Field(min_length=10, max_length=10)


class MulticonceptReport(_StrictModel):
    schemaVersion: str
    fixtureVersion: str
    runnerVersion: str
    caseCount: int = Field(ge=10, le=10)
    precisionAt3: float = Field(ge=0, le=1)
    precisionAt5: float = Field(ge=0, le=1)
    recallAt10: float = Field(ge=0, le=1)
    meanReciprocalRank: float = Field(ge=0, le=1)
    ndcgAt5: float = Field(ge=0, le=1)
    displayedMissingCorePercentage: float = Field(ge=0, le=100)
    externalProviderRequests: Literal[0]
    externalNetworkCalls: Literal[0]
    digest: str = Field(pattern=r"^[0-9a-f]{64}$")


def evaluate_multiconcept_fixture(path: Path) -> MulticonceptReport:
    """Scores the checked-in judgments without network or model access."""

    fixture = MulticonceptFixture.model_validate_json(path.read_bytes())
    if fixture.schemaVersion != FIXTURE_SCHEMA:
        raise ValueError("Multiconcept evaluation fixture schema is incompatible")
    metrics = [_case_metrics(case) for case in fixture.cases]
    displayed = [
        item for case in fixture.cases for item in case.rankedCandidates if item.displayed
    ]
    payload = {
        "schemaVersion": REPORT_SCHEMA,
        "fixtureVersion": fixture.fixtureVersion,
        "runnerVersion": RUNNER_VERSION,
        "caseCount": len(fixture.cases),
        "precisionAt3": _mean(item[0] for item in metrics),
        "precisionAt5": _mean(item[1] for item in metrics),
        "recallAt10": _mean(item[2] for item in metrics),
        "meanReciprocalRank": _mean(item[3] for item in metrics),
        "ndcgAt5": _mean(item[4] for item in metrics),
        "displayedMissingCorePercentage": round(
            100 * sum(item.missingCoreConcept for item in displayed) / len(displayed), 4
        ) if displayed else 0.0,
        "externalProviderRequests": 0,
        "externalNetworkCalls": 0,
    }
    digest = hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return MulticonceptReport(**payload, digest=digest)


def _case_metrics(case: MulticonceptCase) -> tuple[float, float, float, float, float]:
    relevant = {"HIGHLY_RELEVANT", "RELEVANT", "RELATED"}
    rows = case.rankedCandidates
    total_relevant = sum(item.label in relevant for item in rows)
    p3 = sum(item.label in relevant for item in rows[:3]) / 3
    p5 = sum(item.label in relevant for item in rows[:5]) / 5
    recall10 = sum(item.label in relevant for item in rows[:10]) / total_relevant
    first = next(
        (index for index, item in enumerate(rows, 1) if item.label in relevant), None
    )
    reciprocal = 0.0 if first is None else 1 / first
    gains = {"HIGHLY_RELEVANT": 3, "RELEVANT": 2, "RELATED": 1, "IRRELEVANT": 0}
    actual = _dcg([gains[item.label] for item in rows[:5]])
    ideal = _dcg(sorted((gains[item.label] for item in rows), reverse=True)[:5])
    return p3, p5, recall10, reciprocal, 0.0 if ideal == 0 else actual / ideal


def _dcg(gains: list[int]) -> float:
    return sum((2**gain - 1) / math.log2(index + 1) for index, gain in enumerate(gains, 1))


def _mean(values: object) -> float:
    items = list(values)  # type: ignore[arg-type]
    return round(sum(items) / len(items), 4)
