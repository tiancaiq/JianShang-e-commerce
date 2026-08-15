from __future__ import annotations

import json
from enum import StrEnum


class MarketplaceAgentV2ApiErrorCode(StrEnum):
    FEATURE_DISABLED = "MARKETPLACE_AGENT_V2_FEATURE_DISABLED"
    AUTHENTICATION_REQUIRED = "MARKETPLACE_AGENT_V2_AUTHENTICATION_REQUIRED"
    UNAVAILABLE = "MARKETPLACE_AGENT_V2_UNAVAILABLE"
    SESSION_NOT_FOUND = "MARKETPLACE_AGENT_V2_SESSION_NOT_FOUND"
    RESPONSE_IN_PROGRESS = "MARKETPLACE_AGENT_V2_RESPONSE_IN_PROGRESS"
    STREAM_INTERRUPTED = "MARKETPLACE_AGENT_V2_STREAM_INTERRUPTED"


class MarketplaceAgentV2ApiError(RuntimeError):
    def __init__(self, code: MarketplaceAgentV2ApiErrorCode, status_code: int, message: str) -> None:
        super().__init__(code.value)
        self.code = code
        self.status_code = status_code
        self.public_message = message


def stream_event(sequence: int, event_type: str, **payload: object) -> str:
    """Serialize one strict single-data-line SSE event without private payloads."""

    value = {
        "schemaVersion": "MARKETPLACE_AGENT_V2_STREAM_EVENT_V1",
        "sequence": sequence,
        "type": event_type,
        **payload,
    }
    return (
        f"event: {event_type}\n"
        f"data: {json.dumps(value, ensure_ascii=False, separators=(',', ':'))}\n\n"
    )
