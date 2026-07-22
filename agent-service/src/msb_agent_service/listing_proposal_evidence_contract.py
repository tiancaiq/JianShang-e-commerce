from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from enum import StrEnum

from pydantic import BaseModel, ConfigDict

MAXIMUM_OFFLINE_EVIDENCE_AGE = timedelta(days=7)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class StrictEvidenceModel(BaseModel):
    """Keep every offline evidence schema immutable, strict, and camel-cased."""

    model_config = ConfigDict(
        alias_generator=_to_camel,
        extra="forbid",
        frozen=True,
        populate_by_name=True,
    )


class EvidenceFreshnessIssue(StrEnum):
    TIMESTAMP_IN_FUTURE = "TIMESTAMP_IN_FUTURE"
    WINDOW_INVALID = "WINDOW_INVALID"
    STALE = "STALE"


def canonical_json(payload: object) -> str:
    """Serialize evidence with one deterministic representation for all digests."""

    return json.dumps(
        _jsonable(payload),
        separators=(",", ":"),
        sort_keys=True,
    )


def pretty_json(model: BaseModel) -> str:
    """Render a strict evidence model for stable, machine-readable CLI output."""

    return json.dumps(
        model.model_dump(mode="json", by_alias=True),
        indent=2,
        sort_keys=True,
    )


def raw_evidence_sha256(payload: object) -> str:
    """Hash any JSON-compatible evidence payload using the canonical encoding."""

    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def model_evidence_sha256(model: BaseModel) -> str:
    """Hash a validated evidence model without changing its external schema."""

    return raw_evidence_sha256(model.model_dump(mode="json", by_alias=True))


def timestamped_model_evidence_sha256(
    model: BaseModel,
    generated_at: datetime,
) -> str:
    """Bind validated evidence to one explicit timezone-aware generation time."""

    if not _timezone_aware(generated_at):
        raise ValueError("evidence generation time must include a UTC offset")
    payload = (
        canonical_json(model)
        + "\n"
        + generated_at.astimezone(UTC).isoformat().replace("+00:00", "Z")
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def decimal_or_none(value: object) -> Decimal | None:
    """Parse bounded numeric evidence without accepting parser exceptions."""

    try:
        return Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None


def datetime_or_none(value: object) -> datetime | None:
    """Parse only timezone-aware ISO-8601 evidence timestamps."""

    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if _timezone_aware(parsed) else None


def evidence_freshness_issues(
    *,
    generated_at: datetime,
    evaluated_at: datetime,
    maximum_age: timedelta = MAXIMUM_OFFLINE_EVIDENCE_AGE,
    expires_at: datetime | None = None,
) -> tuple[EvidenceFreshnessIssue, ...]:
    """Classify future, invalid-window, and stale evidence in fixed order."""

    timestamps = (generated_at, evaluated_at, expires_at)
    if any(
        value is not None and not _timezone_aware(value)
        for value in timestamps
    ):
        return (EvidenceFreshnessIssue.WINDOW_INVALID,)
    issues: list[EvidenceFreshnessIssue] = []
    if generated_at > evaluated_at:
        issues.append(EvidenceFreshnessIssue.TIMESTAMP_IN_FUTURE)
    if expires_at is not None and (
        expires_at <= generated_at
        or expires_at - generated_at > maximum_age
    ):
        issues.append(EvidenceFreshnessIssue.WINDOW_INVALID)
    if (
        evaluated_at - generated_at > maximum_age
        or (expires_at is not None and evaluated_at > expires_at)
    ):
        issues.append(EvidenceFreshnessIssue.STALE)
    return tuple(issues)


def _jsonable(value: object) -> object:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json", by_alias=True)
    if isinstance(value, datetime):
        if not _timezone_aware(value):
            raise ValueError("evidence timestamps must include a UTC offset")
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, StrEnum):
        return value.value
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_jsonable(item) for item in value]
    return value


def _timezone_aware(value: datetime) -> bool:
    return value.tzinfo is not None and value.utcoffset() is not None
