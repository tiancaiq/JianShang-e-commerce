from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from msb_agent_service.agent_persistence import new_ulid

from .schemas import (
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceAgentV2SellerField,
    MarketplaceAgentV2SellerFields,
)


SellerReplyResolution = Literal[
    "VALUE_PROVIDED", "UNKNOWN", "DEFER", "REQUEST_HELP", "CANCEL_WORKFLOW",
    "CORRECTION", "REPLACE_FIELD_VALUE", "UNRELATED_OR_NEW_INTENT",
]


@dataclass(frozen=True)
class PendingFieldReplyResolution:
    resolution: SellerReplyResolution
    field: str
    normalized_value: str | None
    consume_pending_interaction: bool


_FIELD_ORDER = (
    "ITEM_TYPE", "TITLE", "CONDITION", "PRICE", "DESCRIPTION", "LOCATION",
    "FULFILLMENT",
)
_FIELD_ATTRIBUTE = {
    "ITEM_TYPE": "item_type",
    "TITLE": "title",
    "CONDITION": "condition",
    "PRICE": "price",
    "DESCRIPTION": "description",
    "LOCATION": "location",
    "FULFILLMENT": "fulfillment",
}
_FIELD_LIMIT = {
    "ITEM_TYPE": 160,
    "TITLE": 240,
    "CONDITION": 120,
    "PRICE": 120,
    "DESCRIPTION": 2_000,
    "LOCATION": 200,
    "FULFILLMENT": 200,
}
_QUESTIONS = {
    "ITEM_TYPE": "What are you selling?",
    "TITLE": "What title would you like to use for the listing?",
    "CONDITION": "What condition is it in?",
    "PRICE": "What price are you considering?",
    "DESCRIPTION": "What details should buyers know?",
    "LOCATION": "What city or general area should the listing show?",
    "FULFILLMENT": "Will you offer pickup, shipping, or both?",
}
_UNKNOWN = frozenset({
    "i don't know", "i dont know", "not sure", "no idea", "haven't decided",
    "havent decided", "i don't know yet", "i dont know yet", "不知道", "还没想好",
    "不确定",
})
_DEFER = frozenset({
    "skip", "skip for now", "later", "come back to this", "leave it blank",
    "先跳过", "以后再填",
})
_HELP = frozenset({
    "help me write it", "suggest one", "can you suggest one", "can you make a title",
    "what should i put", "帮我写", "给我建议",
})
_CANCEL = frozenset({
    "cancel", "never mind", "nevermind", "stop", "cancel this", "算了", "取消",
})
# The approved Marketplace product surface lists physical goods only. Product's
# active taxonomy has no real-estate category, so only unambiguous real-estate
# nouns are rejected here; ambiguous goods remain eligible for the General category.
_UNSUPPORTED_REAL_ESTATE = frozenset({
    "house", "apartment", "condo", "condominium", "real estate", "real property",
    "residential property", "commercial property", "parcel of land",
})
_CORRECTION = re.compile(
    r"^(?:actually|correction|i meant)\s*(?:[:,\-]\s*)?(?P<value>.+)$",
    re.IGNORECASE,
)
_INITIAL_ITEM_TYPE = re.compile(
    r"^(?:i\s+(?:want|need|would like)\s+to\s+(?:sell|list)|"
    r"help me (?:sell|list)|create (?:a )?listing(?:\s+for)?)\s+(?P<value>.+)$",
    re.IGNORECASE,
)
_GENERIC_ITEM_TYPES = frozenset({"item", "an item", "something", "stuff", "a thing"})


def start_create_listing_workflow(
    *, initial_item_type: str | None = None, now: datetime | None = None,
) -> tuple[MarketplaceAgentV2ActiveWorkflow, MarketplaceAgentV2PendingInteraction]:
    """Starts local information collection without claiming a seller integration ran."""

    occurred_at = (now or datetime.now(UTC)).astimezone(UTC)
    workflow = MarketplaceAgentV2ActiveWorkflow(
        status="COLLECTING_INFORMATION",
        collectedFields=MarketplaceAgentV2SellerFields(),
        lastUpdatedAt=occurred_at,
    )
    if initial_item_type is None:
        return workflow, _pending("ITEM_TYPE", occurred_at)
    value = _validated_value("ITEM_TYPE", initial_item_type)
    if _is_unsupported_item_type(value):
        fields = workflow.collected_fields.model_copy(update={
            "item_type": MarketplaceAgentV2SellerField(
                status="REJECTED", value=value, reason="UNSUPPORTED_CATEGORY"
            )
        })
        return (
            workflow.model_copy(update={
                "collected_fields": fields,
                "item_type_eligibility": "UNSUPPORTED",
                "last_resolved_field": "ITEM_TYPE",
                "last_reply_resolution": "VALUE_PROVIDED",
            }),
            _pending("ITEM_TYPE", occurred_at, accepts_replacement=True),
        )
    fields = workflow.collected_fields.model_copy(update={
        "item_type": MarketplaceAgentV2SellerField(status="PROVIDED", value=value)
    })
    return (
        workflow.model_copy(update={
            "collected_fields": fields,
            "item_type_eligibility": "SUPPORTED",
            "last_resolved_field": "ITEM_TYPE",
            "last_reply_resolution": "VALUE_PROVIDED",
        }),
        _pending("TITLE", occurred_at),
    )


def extract_initial_item_type(message: str) -> str | None:
    """Extracts only an explicit seller-object phrase after the model chose collection."""

    value = " ".join(message.split()).strip().rstrip(".!?。！？")
    match = _INITIAL_ITEM_TYPE.match(value)
    if match is None:
        return None
    candidate = re.sub(
        r"^(?:my|a|an|the)\s+", "", match.group("value"), flags=re.IGNORECASE
    ).strip()
    if _semantic_text(candidate) in _GENERIC_ITEM_TYPES:
        return None
    return _validated_value("ITEM_TYPE", candidate)


def resolve_pending_field_reply(
    *, pending: MarketplaceAgentV2PendingInteraction, answer: str,
) -> PendingFieldReplyResolution:
    """Classifies only the active field reply; it is not a general intent router."""

    if pending.type != "ANSWER_FIELD" or pending.field is None:
        raise ValueError("Seller workflow field is not waiting")
    value = " ".join(answer.split()).strip()
    normalized = _semantic_text(value)
    if normalized in _CANCEL:
        return _resolution("CANCEL_WORKFLOW", pending.field)
    if normalized in _UNKNOWN:
        return _resolution("UNKNOWN", pending.field)
    if normalized in _DEFER:
        return _resolution("DEFER", pending.field, consume=True)
    if normalized in _HELP or any(
        normalized.startswith(prefix)
        for prefix in ("help me ", "can you suggest", "can you make", "what should i")
    ):
        return _resolution("REQUEST_HELP", pending.field)
    if pending.field == "ITEM_TYPE" and pending.accepts_replacement:
        replacement = _replacement_value(value)
        if replacement is not None:
            return _resolution(
                "REPLACE_FIELD_VALUE", pending.field,
                value=_validated_value(pending.field, replacement), consume=True,
            )
    correction = _CORRECTION.match(value)
    if correction is not None:
        corrected = _validated_value(pending.field, correction.group("value"))
        return _resolution(
            "CORRECTION", pending.field, value=corrected, consume=True
        )
    if _looks_unrelated(normalized, value):
        return _resolution("UNRELATED_OR_NEW_INTENT", pending.field)
    return _resolution(
        "VALUE_PROVIDED", pending.field,
        value=_validated_value(pending.field, value), consume=True,
    )


def apply_field_answer(
    *,
    workflow: MarketplaceAgentV2ActiveWorkflow,
    pending: MarketplaceAgentV2PendingInteraction,
    user_message_id: str,
    answer: str,
    now: datetime | None = None,
) -> tuple[
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction | None,
    str,
]:
    """Resolves one pending seller reply and advances only when its state permits."""

    if (
        workflow.type != "CREATE_LISTING"
        or workflow.status != "COLLECTING_INFORMATION"
        or pending.status != "WAITING"
        or pending.workflow_type != "CREATE_LISTING"
    ):
        raise ValueError("Seller workflow field is not waiting")
    resolution = resolve_pending_field_reply(pending=pending, answer=answer)
    if resolution.resolution == "UNRELATED_OR_NEW_INTENT":
        raise ValueError("Seller field reply does not answer the pending field")
    return apply_field_resolution(
        workflow=workflow, pending=pending, user_message_id=user_message_id,
        resolution=resolution, now=now,
    )


def apply_field_resolution(
    *,
    workflow: MarketplaceAgentV2ActiveWorkflow,
    pending: MarketplaceAgentV2PendingInteraction,
    user_message_id: str,
    resolution: PendingFieldReplyResolution,
    now: datetime | None = None,
) -> tuple[
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction | None,
    str,
]:
    """Applies a validated semantic resolution as one atomic workflow transition."""

    occurred_at = (now or datetime.now(UTC)).astimezone(UTC)
    if resolution.resolution == "CANCEL_WORKFLOW":
        cancelled = workflow.model_copy(update={
            "status": "CANCELLED",
            "last_updated_at": occurred_at,
            "last_resolved_user_message_id": user_message_id,
            "last_resolved_field": pending.field,
            "last_reply_resolution": resolution.resolution,
        })
        return cancelled, None, "Okay—I stopped preparing this listing."

    attribute = _FIELD_ATTRIBUTE[pending.field]
    if resolution.resolution in {"UNKNOWN", "REQUEST_HELP"}:
        fields = workflow.collected_fields.model_copy(update={
            attribute: MarketplaceAgentV2SellerField(status="NEEDS_HELP")
        })
        updated = workflow.model_copy(update={
            "collected_fields": fields,
            "last_updated_at": occurred_at,
            "last_resolved_user_message_id": user_message_id,
            "last_resolved_field": pending.field,
            "last_reply_resolution": resolution.resolution,
        })
        return updated, pending, _help_response(pending.field, resolution.resolution)

    if resolution.resolution == "DEFER":
        if pending.field == "ITEM_TYPE":
            fields = workflow.collected_fields.model_copy(update={
                attribute: MarketplaceAgentV2SellerField(status="NEEDS_HELP")
            })
            updated = workflow.model_copy(update={
                "collected_fields": fields,
                "last_updated_at": occurred_at,
                "last_resolved_user_message_id": user_message_id,
                "last_resolved_field": pending.field,
                "last_reply_resolution": "REQUEST_HELP",
            })
            return updated, pending, "I need the type of item before I can prepare its listing. What are you selling?"
        fields = workflow.collected_fields.model_copy(update={
            attribute: MarketplaceAgentV2SellerField(status="DEFERRED")
        })
        return _advance(
            workflow=workflow, fields=fields, pending=pending,
            user_message_id=user_message_id, resolution=resolution.resolution,
            occurred_at=occurred_at, acknowledgement="Okay—we can come back to that.",
        )

    assert resolution.normalized_value is not None
    fields = workflow.collected_fields.model_copy(update={
        attribute: MarketplaceAgentV2SellerField(
            status="PROVIDED", value=resolution.normalized_value
        )
    })
    if pending.field == "ITEM_TYPE" and _is_unsupported_item_type(
        resolution.normalized_value
    ):
        fields = workflow.collected_fields.model_copy(update={
            attribute: MarketplaceAgentV2SellerField(
                status="REJECTED",
                value=resolution.normalized_value,
                reason="UNSUPPORTED_CATEGORY",
            )
        })
        updated = workflow.model_copy(update={
            "status": "COLLECTING_INFORMATION",
            "collected_fields": fields,
            "item_type_eligibility": "UNSUPPORTED",
            "last_updated_at": occurred_at,
            "last_resolved_user_message_id": user_message_id,
            "last_resolved_field": pending.field,
            "last_reply_resolution": resolution.resolution,
        })
        return (
            updated,
            _pending("ITEM_TYPE", occurred_at, accepts_replacement=True),
            "This marketplace supports item listings, not real estate, so I can't "
            "prepare that listing here. You can list another type of item instead.",
        )
    return _advance(
        workflow=workflow, fields=fields, pending=pending,
        user_message_id=user_message_id, resolution=resolution.resolution,
        occurred_at=occurred_at,
        acknowledgement=(
            f"A {resolution.normalized_value} works."
            if resolution.resolution == "REPLACE_FIELD_VALUE"
            else (
                f"Great—a {resolution.normalized_value}."
                if pending.field == "ITEM_TYPE" else "Got it."
            )
        ),
        item_type_supported=pending.field == "ITEM_TYPE",
    )


def cancel_create_listing_workflow(
    workflow: MarketplaceAgentV2ActiveWorkflow, *, now: datetime | None = None
) -> MarketplaceAgentV2ActiveWorkflow:
    """Pauses seller collection and clears its unresolved interaction."""

    return workflow.model_copy(update={
        "status": "CANCELLED",
        "last_updated_at": (now or datetime.now(UTC)).astimezone(UTC),
        "last_reply_resolution": "CANCEL_WORKFLOW",
    })


def can_resolve_seller_field(
    workflow: MarketplaceAgentV2ActiveWorkflow,
    pending: MarketplaceAgentV2PendingInteraction | None,
) -> bool:
    """Recognizes both current WAITING fields and the prior terminal rejection shape."""

    if (
        workflow.status == "COLLECTING_INFORMATION"
        and pending is not None
        and pending.type == "ANSWER_FIELD"
        and pending.status == "WAITING"
    ):
        return True
    return bool(
        workflow.status == "UNSUPPORTED"
        and workflow.item_type_eligibility == "UNSUPPORTED"
        and workflow.collected_fields.item_type.value
        and pending is None
    )


def restore_rejected_item_pending(
    workflow: MarketplaceAgentV2ActiveWorkflow,
    pending: MarketplaceAgentV2PendingInteraction | None,
    *, now: datetime,
) -> tuple[MarketplaceAgentV2ActiveWorkflow, MarketplaceAgentV2PendingInteraction | None]:
    """Upgrades the legacy terminal unsupported state during the next locked reply."""

    if not (
        workflow.status == "UNSUPPORTED"
        and workflow.item_type_eligibility == "UNSUPPORTED"
        and workflow.collected_fields.item_type.value
        and pending is None
    ):
        return workflow, pending
    fields = workflow.collected_fields.model_copy(update={
        "item_type": MarketplaceAgentV2SellerField(
            status="REJECTED",
            value=workflow.collected_fields.item_type.value,
            reason="UNSUPPORTED_CATEGORY",
        )
    })
    return (
        workflow.model_copy(update={
            "status": "COLLECTING_INFORMATION",
            "collected_fields": fields,
            "last_updated_at": now.astimezone(UTC),
        }),
        _pending("ITEM_TYPE", now.astimezone(UTC), accepts_replacement=True),
    )


def response_for_replayed_field(
    workflow: MarketplaceAgentV2ActiveWorkflow,
    pending: MarketplaceAgentV2PendingInteraction | None,
) -> str:
    """Reconstructs the same bounded next step when a failed response is retried."""

    if (
        workflow.item_type_eligibility == "UNSUPPORTED"
        and workflow.collected_fields.item_type.value is not None
    ):
        return (
            "This marketplace supports item listings, not real estate, so I can't "
            "prepare that listing here. You can list another type of item instead."
        )
    if workflow.status == "CANCELLED":
        return "Okay—I stopped preparing this listing."
    if workflow.last_reply_resolution in {"UNKNOWN", "REQUEST_HELP"}:
        assert workflow.last_resolved_field is not None
        return _help_response(
            workflow.last_resolved_field, workflow.last_reply_resolution
        )
    if pending is not None and pending.question is not None:
        if workflow.last_reply_resolution == "DEFER":
            return f"Okay—we can come back to that. {pending.question}"
        value = None
        if workflow.last_resolved_field is not None:
            field_state = getattr(
                workflow.collected_fields,
                _FIELD_ATTRIBUTE[workflow.last_resolved_field],
            )
            value = field_state.value
        acknowledgement = (
            f"A {value} works."
            if workflow.last_reply_resolution == "REPLACE_FIELD_VALUE" and value
            else (
                f"Great—a {value}."
                if workflow.last_resolved_field == "ITEM_TYPE" and value
                else "Got it."
            )
        )
        return f"{acknowledgement} {pending.question}"
    return (
        "Thanks—I have the listing details prepared. Publishing is not connected "
        "here yet, so please review and create the listing from your selling page."
    )


def _advance(
    *,
    workflow: MarketplaceAgentV2ActiveWorkflow,
    fields: MarketplaceAgentV2SellerFields,
    pending: MarketplaceAgentV2PendingInteraction,
    user_message_id: str,
    resolution: SellerReplyResolution,
    occurred_at: datetime,
    acknowledgement: str,
    item_type_supported: bool = False,
) -> tuple[
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction | None,
    str,
]:
    next_field = next(
        (
            field for field in _FIELD_ORDER
            if getattr(fields, _FIELD_ATTRIBUTE[field]).status == "MISSING"
        ),
        None,
    )
    updated = workflow.model_copy(update={
        "status": "READY_FOR_REVIEW" if next_field is None else "COLLECTING_INFORMATION",
        "collected_fields": fields,
        "item_type_eligibility": (
            "SUPPORTED" if item_type_supported else workflow.item_type_eligibility
        ),
        "last_updated_at": occurred_at,
        "last_resolved_user_message_id": user_message_id,
        "last_resolved_field": pending.field,
        "last_reply_resolution": resolution,
    })
    if next_field is None:
        return (
            updated,
            None,
            "Thanks—I have the listing details prepared. Publishing is not connected "
            "here yet, so please review and create the listing from your selling page.",
        )
    next_pending = _pending(next_field, occurred_at)
    return updated, next_pending, f"{acknowledgement} {_QUESTIONS[next_field]}"


def _validated_value(field: str, answer: str) -> str:
    value = " ".join(answer.split()).strip()
    if field == "ITEM_TYPE":
        value = re.sub(r"^(?:a|an)\s+", "", value, flags=re.IGNORECASE).strip()
    if not value or len(value) > _FIELD_LIMIT[field]:
        raise ValueError("Seller workflow field answer is invalid")
    normalized = _semantic_text(value)
    if normalized in _UNKNOWN | _DEFER | _HELP | _CANCEL:
        raise ValueError("Seller workflow control text cannot be stored as a value")
    return value


def _semantic_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).replace("’", "'")
    normalized = " ".join(normalized.casefold().split())
    return normalized.strip(" \t\r\n.!?。！？")


def _looks_unrelated(normalized: str, original: str) -> bool:
    if "?" in original or "？" in original:
        return True
    return any(
        normalized.startswith(prefix)
        for prefix in (
            "find ", "search ", "show me similar", "buy ", "what about ",
            "who are ", "how do ", "why ",
        )
    )


def _replacement_value(value: str) -> str | None:
    normalized = _semantic_text(value)
    english = re.match(
        r"^(?:how about|what about|then|use|change it to|actually[,:\-]?)\s+"
        r"(?:an|a|the)?\s*(?P<value>.+?)(?:\s+instead)?$",
        normalized,
    )
    if english is not None:
        return english.group("value").strip(" ,")
    maybe = re.match(
        r"^maybe\s+(?:an|a|the)?\s*(?P<value>.+?)\s+instead$", normalized
    )
    if maybe is not None:
        return maybe.group("value").strip(" ,")
    for pattern in (
        r"^那(?P<value>.+)呢$",
        r"^换成(?P<value>.+)$",
        r"^那就卖(?P<value>.+)$",
    ):
        match = re.match(pattern, normalized)
        if match is not None:
            return match.group("value").strip()
    return None


def _is_unsupported_item_type(value: str) -> bool:
    return _semantic_text(value) in _UNSUPPORTED_REAL_ESTATE


def _help_response(field: str, resolution: SellerReplyResolution) -> str:
    if field == "TITLE":
        if resolution == "UNKNOWN":
            return (
                "No problem—I can help create a title. Tell me a few details about "
                "the item, and I'll suggest one."
            )
        return (
            "I can help create a title. What is one detail that makes the item easy "
            "to identify, such as its brand, model, or format?"
        )
    return f"No problem—we can work through that. {_QUESTIONS[field]}"


def _resolution(
    resolution: SellerReplyResolution,
    field: str,
    *,
    value: str | None = None,
    consume: bool = False,
) -> PendingFieldReplyResolution:
    return PendingFieldReplyResolution(
        resolution=resolution,
        field=field,
        normalized_value=value,
        consume_pending_interaction=consume,
    )


def _pending(
    field: str, occurred_at: datetime, *, accepts_replacement: bool = False,
) -> MarketplaceAgentV2PendingInteraction:
    return MarketplaceAgentV2PendingInteraction(
        id=new_ulid(),
        type="ANSWER_FIELD",
        workflowType="CREATE_LISTING",
        field=field,
        question=(
            "What supported item would you like to list instead?"
            if accepts_replacement else _QUESTIONS[field]
        ),
        arguments={},
        acceptsReplacement=accepts_replacement,
        status="WAITING",
        createdAt=occurred_at,
    )
