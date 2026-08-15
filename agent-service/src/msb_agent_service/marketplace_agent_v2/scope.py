from __future__ import annotations

import re
from collections.abc import Mapping, Sequence

from .schemas import (
    ListingAttachment,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
)


MARKETPLACE_SCOPE_BOUNDARY_RESPONSE = (
    "I'm focused on marketplace assistance. I can help you find and compare "
    "listings or help with buyer and seller questions."
)

_CONVERSATIONAL = {
    "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
    "thanks", "thank you", "thank you so much", "thanks a lot", "how are you",
    "okay", "ok", "bye", "goodbye",
    "never mind", "nevermind", "stop",
}
_CANCELLATIONS = {"never mind", "nevermind", "stop"}
_CAPABILITIES = (
    "who are you", "what can you do", "what can you help", "how can you help",
)
_MARKETPLACE_PHRASES = (
    "marketplace", "listing", "listings", "seller", "buyer", "refund", "return",
    "payment", "delivery", "dispute", "prohibited item", "business application",
    "seller profile", "listing image", "listing status", "item availability",
)
_KNOWLEDGE_PHRASES = (
    "refund", "refunds", "return policy", "returns work", "prohibited item",
    "prohibited items", "seller application", "business seller application",
    "pictures can sellers upload", "listing image rules", "marketplace rules",
    "marketplace policy", "marketplace policies", "marketplace safety",
)
_PRIVATE_STATUS_PATTERNS = (
    r"\b(?:where|what)\s+(?:is|was)\s+my\s+order\b",
    r"\bmy\s+(?:order|payment|account|listing|business application)\s+(?:status|failed|removed|rejected)\b",
    r"\bwhy\s+(?:did|was)\s+my\s+(?:payment|listing|order|business application)\b",
)
_DISCOVERY_PREFIXES = (
    "find ", "find me ", "show me ", "search for ", "browse ", "compare ",
    "i need ", "i want ", "looking for ", "buy ",
)
_AMBIGUOUS_TERMS = {
    "apple", "java", "python", "the bag issue", "can you help me with this",
}
_CONTEXT_ANCHORS = (
    "listing", "marketplace", "chair", "lamp", "laptop", "phone", "desk", "bag",
    "organizer", "bicycle", "seller", "buyer", "refund", "payment", "delivery",
)


class MarketplaceScopeClassifier:
    """Classifies only the broad marketplace boundary and never selects a tool."""

    def classify(
        self,
        *,
        current_message: str,
        recent_messages: Sequence[tuple[str, str]],
        referenced_listings: Sequence[ListingAttachment],
        pending_interaction: MarketplaceAgentV2PendingInteraction | None,
        preference_state: Mapping[str, object],
    ) -> MarketplaceScopeResult:
        normalized = _normalize(current_message)
        context_available = _has_marketplace_context(
            recent_messages=recent_messages,
            referenced_listings=referenced_listings,
            pending_interaction=pending_interaction,
            preference_state=preference_state,
        )
        unsafe = hard_safety_response(current_message)
        if unsafe is not None:
            return _result("UNSAFE", "NONE", "HIGH", context_available, unsafe[0])
        unrelated = _unrelated_reason(normalized)
        if unrelated is not None:
            return _result("OUT_OF_SCOPE", "NONE", "HIGH", False, unrelated)
        if normalized in _CONVERSATIONAL:
            reason = (
                "CONVERSATION_CANCELLED"
                if normalized in _CANCELLATIONS
                else "NATURAL_CONVERSATION"
            )
            return _result("CONVERSATIONAL", "NONE", "HIGH", context_available, reason)
        if pending_interaction is not None and normalized in {
            "yes", "yes please", "no", "no thanks", "sure",
        }:
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", True,
                "PENDING_INTERACTION_RESPONSE",
            )
        if any(phrase in normalized for phrase in _CAPABILITIES):
            return _result(
                "IN_SCOPE", "NONE", "HIGH", context_available, "AGENT_CAPABILITIES"
            )
        if any(re.search(pattern, normalized) for pattern in _PRIVATE_STATUS_PATTERNS):
            return _result(
                "IN_SCOPE", "PRIVATE_TOOL", "HIGH", context_available,
                "PRIVATE_MARKETPLACE_STATUS",
            )
        if any(phrase in normalized for phrase in _KNOWLEDGE_PHRASES):
            return _result(
                "IN_SCOPE", "KNOWLEDGE_RAG", "HIGH", context_available,
                "MARKETPLACE_KNOWLEDGE",
            )
        if re.search(
            r"\b(?:i\s+(?:want|need|would like)\s+to\s+sell|help me (?:sell|list)|"
            r"create (?:a )?listing)\b",
            normalized,
        ):
            return _result(
                "IN_SCOPE", "NONE", "HIGH", context_available,
                "SELLER_LISTING_WORKFLOW",
            )
        active_workflow = preference_state.get("activeWorkflow")
        if (
            isinstance(active_workflow, Mapping)
            and active_workflow.get("type") == "CREATE_LISTING"
            and any(
                phrase in normalized
                for phrase in (
                    "publish it", "publish the listing", "post the listing",
                    "make it live",
                )
            )
        ):
            return _result(
                "IN_SCOPE", "NONE", "HIGH", True,
                "SELLER_PUBLISH_ACTION",
            )
        if any(phrase in normalized for phrase in _MARKETPLACE_PHRASES):
            return _result(
                "IN_SCOPE", "KNOWLEDGE_RAG", "HIGH", context_available,
                "MARKETPLACE_SUPPORT",
            )
        if normalized.startswith(_DISCOVERY_PREFIXES):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", context_available,
                "MARKETPLACE_DISCOVERY",
            )
        if re.search(
            r"\b(?:which|what about|compare|cheapest|best|price|available|cheaper)\b",
            normalized,
        ) and (context_available or any(anchor in normalized for anchor in _CONTEXT_ANCHORS)):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", context_available,
                "MARKETPLACE_LISTING_QUESTION",
            )
        if any(anchor in normalized for anchor in _CONTEXT_ANCHORS) and re.search(
            r"(?:\$\s*\d|\b(?:under|below|maximum|max|near)\b)", normalized
        ):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", context_available,
                "MARKETPLACE_DISCOVERY",
            )
        if normalized in _AMBIGUOUS_TERMS:
            if context_available:
                return _result(
                    "IN_SCOPE", "LISTING_DATA", "MEDIUM", True,
                    "CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
                )
            return _result(
                "AMBIGUOUS", "LISTING_DATA", "LOW", False,
                "MARKETPLACE_INTERPRETATION_PLAUSIBLE",
            )
        words = re.findall(r"[^\W_]+", normalized)
        if 1 <= len(words) <= 4:
            return _result(
                "IN_SCOPE", "LISTING_DATA", "LOW", context_available,
                "SHORT_PRODUCT_PHRASE",
            )
        if context_available:
            return _result(
                "IN_SCOPE", "LISTING_DATA", "LOW", True,
                "CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
            )
        return _result(
            "AMBIGUOUS", "NONE", "LOW", False,
            "MARKETPLACE_INTERPRETATION_PLAUSIBLE",
        )


def hard_safety_response(value: str) -> tuple[str, str] | None:
    """Return a stable unsafe category and canonical customer refusal."""

    normalized = _normalize(value)
    if re.search(
        r"\b(system prompt|hidden reasoning|chain[- ]of[- ]thought|seller phone "
        r"numbers?|private seller (?:contact|phone|email))\b",
        normalized,
    ):
        return (
            "PRIVATE_OR_INTERNAL_DATA_REQUEST",
            "I can't provide private seller contact information, hidden instructions, "
            "or internal reasoning. I can help with public listing details or ordinary "
            "marketplace questions.",
        )
    if (
        re.search(r"\b(cure|treat|diagnose)\b", normalized)
        and re.search(r"\b(insomnia|sleep disorder|medical condition)\b", normalized)
    ):
        return (
            "MEDICAL_TREATMENT_REQUEST",
            "I can't recommend a marketplace product as a cure or treatment for "
            "insomnia. Please speak with a qualified clinician for medical guidance. "
            "I can help you browse non-medical comfort items, without making health "
            "outcome claims.",
        )
    return None


def _unrelated_reason(value: str) -> str | None:
    # This intentionally recognizes only explicit high-confidence boundaries;
    # everything uncertain stays with the model-first planner as AMBIGUOUS.
    if _is_explicit_code_generation_request(value):
        return "UNRELATED_CODE_REQUEST"
    if re.search(
        r"\b(?:what is|explain|teach me about)\s+(?:the\s+)?"
        r"(?:cosine|sine|trigonometry|calculus|photosynthesis|world war(?: ii| 2)?)\b",
        value,
    ):
        return "UNRELATED_GENERAL_KNOWLEDGE"
    rules = (
        ((
            "write python", "python code", "write code", "sorting algorithm",
            "write a function", "write a program", "javascript code", "sql query",
        ), "UNRELATED_CODE_REQUEST"),
        ((
            "homework", "solve this assignment", "solve my assignment",
            "solve this equation", "math problem", "calculus problem",
        ), "UNRELATED_HOMEWORK_REQUEST"),
        (("weather", "forecast"), "UNRELATED_WEATHER_REQUEST"),
        (("write a poem", "write me a poem", "creative writing", "write a story"), "UNRELATED_CREATIVE_REQUEST"),
        ((
            "politics", "political question", "election result", "who should i vote",
            "current president", "presidential election",
        ), "UNRELATED_POLITICAL_REQUEST"),
        ((
            "travel itinerary", "plan my trip", "vacation plan", "plan a vacation",
            "tourist itinerary",
        ), "UNRELATED_TRAVEL_REQUEST"),
        ((
            "medical advice", "diagnose my", "what medicine should", "my symptoms",
            "why does my head hurt",
        ), "UNRELATED_MEDICAL_ADVICE"),
        ((
            "legal advice", "financial advice", "investment advice", "tax advice",
            "should i sue", "which stock", "crypto investment",
        ), "UNRELATED_ADVICE_REQUEST"),
        ((
            "movie trivia", "entertainment trivia", "sports trivia", "capital of",
            "who won best actor", "general web research", "research this topic",
        ), "UNRELATED_RESEARCH_REQUEST"),
    )
    for phrases, reason in rules:
        if any(phrase in value for phrase in phrases):
            return reason
    return None


def _is_explicit_code_generation_request(value: str) -> bool:
    """Recognize an explicit programming deliverable without routing marketplace text."""

    tokens = set(re.findall(r"[^\W_]+", value))
    generation_requested = bool(
        tokens.intersection({"write", "create", "build", "generate", "make"})
    )
    programming_language_present = bool(
        tokens.intersection({
            "python", "javascript", "typescript", "java", "ruby", "rust", "golang",
        })
    ) or "c++" in value or "c#" in value
    code_artifact_present = bool(
        tokens.intersection({
            "algorithm", "code", "function", "program", "script", "scipt",
        })
    )
    return generation_requested and programming_language_present and code_artifact_present


def _has_marketplace_context(
    *,
    recent_messages: Sequence[tuple[str, str]],
    referenced_listings: Sequence[ListingAttachment],
    pending_interaction: MarketplaceAgentV2PendingInteraction | None,
    preference_state: Mapping[str, object],
) -> bool:
    if referenced_listings or pending_interaction is not None:
        return True
    if any(key != "pendingInteraction" for key in preference_state):
        return True
    return any(
        anchor in content.casefold()
        for _, content in recent_messages[-6:]
        for anchor in _CONTEXT_ANCHORS
    )


def _normalize(value: str) -> str:
    return " ".join(value.casefold().strip().rstrip(".!?").split())


def _result(
    scope: str,
    required_grounding: str,
    confidence: str,
    context_used: bool,
    reason_code: str,
) -> MarketplaceScopeResult:
    return MarketplaceScopeResult(
        scope=scope,
        requiredGrounding=required_grounding,
        confidence=confidence,
        marketplaceContextUsed=context_used,
        reasonCode=reason_code,
    )
