from __future__ import annotations

import re
from collections.abc import Mapping, Sequence

from .schemas import (
    ListingAttachment,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    ToolObservation,
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
    "payment", "delivery", "dispute", "order", "orders", "cart",
    "prohibited item", "business application",
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
    r"\b(?:what(?:'s|s| is)?\s+in|what\s+do\s+i\s+have\s+in|"
    r"(?:show|open|check)(?:\s+me)?|let\s+me\s+see)\s+my\s+cart\b",
    r"\b(?:list|show)\s+(?:my\s+)?(?:recent\s+)?orders?\b",
    r"\b(?:list|show)(?:\s+me)?\s+(?:my\s+)?"
    r"(?:last|latest|recent)\s+(?:one|two|three|[1-9][0-9]?)\s+orders?\b",
    r"\b(?:show|open|check)\s+(?:me\s+)?(?:the\s+)?"
    r"(?:first|second|third|last|latest|most recent)\s+order\b",
    r"\bmy\s+(?:recent|latest|last)\s+orders?\b",
    r"\b(?:show|check|find)\s+order\s+[0-9a-hjkmnp-tv-z]{26}\b",
    r"\b(?:my|the)\s+.+\s+order\b",
    r"\bmy\s+(?:order|payment|account|listing|business application)\s+(?:status|failed|removed|rejected)\b",
    r"\bwhy\s+(?:did|was)\s+my\s+(?:payment|listing|order|business application)\b",
    r"\bwhy\s+is\s+my\s+order\b",
    r"\b(?:add|put)\b.{0,80}\b(?:to|into|in)\s+(?:my\s+)?cart\b",
    r"\b(?:remove|delete|take)\b.{0,80}\b(?:from\s+)?(?:my\s+)?cart\b",
    r"\b(?:change|update|set|make)\b.{0,80}\b(?:cart\s+)?quantity\b",
    r"\b(?:buy|purchase|check\s*out)\s+(?:everything|all|the items?)"
    r"(?:\s+in\s+(?:my\s+)?cart)?\b",
    r"\b(?:buy|purchase|check\s*out)\s+(?:it|them|my cart)\b",
    r"\b(?:complete|start|prepare)\s+(?:my\s+)?checkout\b",
    r"\bcancel\s+(?:my\s+|the\s+)?(?:first|second|third|last|latest|most recent)?\s*order\b",
    r"\bcancel\s+(?:my\s+|the\s+)?order\s+[0-9a-hjkmnp-tv-z]{26}\b",
    r"\bcancel\s+(?:my\s+|the\s+)?order\s+(?:with|for|containing)\b",
    r"\b(?:return|send\s+back)\b.{0,80}\b(?:my|the|last|latest|first|second|third)\b.{0,40}\b(?:order|item|keyboard|mouse|product)\b",
    r"\b(?:my|the)\b.{0,60}\b(?:order|item|keyboard|mouse|product)\b.{0,45}\b(?:arrived|is|was)\s+(?:damaged|broken|wrong)\b",
    r"\bi\s+(?:received|got)\s+(?:the\s+)?wrong\s+item\b",
    r"\b(?:what(?:'s|s| is)\s+(?:happening|going on)|check|show|track)\b.{0,55}\bmy\s+(?:return|refund)\b",
    r"\bhas\s+my\s+(?:return|refund)\b.{0,35}\b(?:finished|completed|succeeded|done)\b",
    r"\b(?:can|could)\s+i\b.{0,45}\b(?:return|refund)\b.{0,55}\b(?:order|item|purchase)\b",
    r"\b(?:prepare|start|create|open|submit)\b.{0,55}\breturn\s+request\b",
)
_CROSS_ACTOR_OWNER_PATTERN = (
    r"(?:another\s+(?:user|customer)(?:['’]s)?|"
    r"someone\s+else(?:['’]s)|other\s+(?:user|customer)(?:['’]s))"
)
_ORDER_STATUS_GUIDANCE_PATTERN = (
    r"\bwhat\s+does\s+(?:confirmed|processing|shipped|in transit|delivered|"
    r"cancelled|refunded)\s+mean\b"
)
_DISCOVERY_PREFIXES = (
    "find ", "find me ", "show me ", "search ", "search for ", "browse ", "compare ",
    "i need ", "i want ", "looking for ", "buy ",
)
_AMBIGUOUS_TERMS = {
    "apple", "java", "python", "the bag issue", "can you help me with this",
}
_CONTEXT_ANCHORS = (
    "listing", "marketplace", "chair", "lamp", "laptop", "phone", "desk", "bag",
    "organizer", "bicycle", "seller", "buyer", "refund", "payment", "delivery",
)


def _selected_order_follow_up(value: str) -> bool:
    """Recognize current-state questions only when an owned order was selected."""

    return bool(re.search(
        r"\bwhat(?:'s| is)\s+happening\s+with\s+(?:it|that|this)\b|"
        r"\bwhat\s+is\s+(?:its|that\s+order's|this\s+order's)\s+status\b|"
        r"\bhow\s+much\s+did\s+i\s+pay\b|"
        r"\bwhat\s+did\s+i\s+pay\b|"
        r"\bwhere\s+is\s+(?:it|that|this)(?:\s+now)?\b",
        value,
    ))


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
        prior_observations: Sequence[ToolObservation] = (),
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
        forbidden_authority = unsupported_customer_authority_response(current_message)
        if forbidden_authority is not None:
            return _result(
                "IN_SCOPE", "NONE", "HIGH", context_available,
                forbidden_authority[0],
            )
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
        if re.search(
            r"\bhow\s+many\b.{0,90}\b(?:items|listings|products)\b"
            r".{0,40}\bavailable\b|"
            r"\bcheck\b.{0,70}\b(?:category|availability)\b",
            normalized,
        ):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", context_available,
                "MARKETPLACE_AVAILABILITY",
            )
        if any(re.search(pattern, normalized) for pattern in _PRIVATE_STATUS_PATTERNS):
            return _result(
                "IN_SCOPE", "PRIVATE_TOOL", "HIGH", context_available,
                "PRIVATE_MARKETPLACE_STATUS",
            )
        if (
            any(
                item.status == "SUCCEEDED"
                and item.tool == "get_my_order"
                and item.order_references
                for item in prior_observations
            )
            and _selected_order_follow_up(normalized)
        ):
            # A session-owned reference identifies the target, not its current state.
            return _result(
                "IN_SCOPE", "PRIVATE_TOOL", "HIGH", True,
                "PRIVATE_COMMERCE_FOLLOW_UP",
            )
        if _is_current_order_item_listing_follow_up(normalized, recent_messages):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", True,
                "ORDER_ITEM_CURRENT_LISTING_FOLLOW_UP",
            )
        if _is_private_commerce_follow_up(normalized, recent_messages):
            return _result(
                "IN_SCOPE", "PRIVATE_TOOL", "HIGH", True,
                "PRIVATE_COMMERCE_FOLLOW_UP",
            )
        if re.search(_ORDER_STATUS_GUIDANCE_PATTERN, normalized):
            return _result(
                "IN_SCOPE", "KNOWLEDGE_RAG", "HIGH", context_available,
                "ORDER_STATUS_GUIDANCE",
            )
        if any(phrase in normalized for phrase in _KNOWLEDGE_PHRASES):
            return _result(
                "IN_SCOPE", "KNOWLEDGE_RAG", "HIGH", context_available,
                "MARKETPLACE_KNOWLEDGE",
            )
        if re.search(
            r"\b(?:i\s+(?:want|need|would like)\s+to\s+(?:sell|list)|"
            r"help me (?:sell|list|create (?:a )?listing)|"
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
        if normalized.startswith(_DISCOVERY_PREFIXES):
            return _result(
                "IN_SCOPE", "LISTING_DATA", "HIGH", context_available,
                "MARKETPLACE_DISCOVERY",
            )
        if any(phrase in normalized for phrase in _MARKETPLACE_PHRASES):
            return _result(
                "IN_SCOPE", "KNOWLEDGE_RAG", "HIGH", context_available,
                "MARKETPLACE_SUPPORT",
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


def _is_private_commerce_follow_up(
    value: str, recent_messages: Sequence[tuple[str, str]]
) -> bool:
    """Classifies grounding only; the model still resolves the referenced object."""

    if _is_generic_refund_policy_question(value):
        return False
    if re.search(
        r"\b(?:refund|money\s+back)\b.{0,35}"
        r"\b(?:for\s+)?(?:it|that|this|that\s+order|that\s+item|"
        r"this\s+order|this\s+item)\b",
        value,
    ):
        # The pronoun makes this operationally customer-specific even when its
        # safe referent is missing; PRIVATE_TOOL then clarifies instead of RAG.
        return True

    recent = " ".join(content.casefold() for _, content in recent_messages[-6:])
    if not re.search(r"\b(?:order|orders|cart|return|refund)\b", recent):
        return False
    if (
        _has_grounded_order_or_return_context(recent_messages)
        and re.search(
            r"\b(?:can|could|would)\s+i\b.{0,45}"
            r"\b(?:refund|money\s+back)\b|"
            r"\bwould\s+i\s+get\s+my\s+money\s+back\b",
            value,
        )
    ):
        return True
    return bool(re.search(
        r"^(?:the\s+)?(?:first|second|third|last|latest|most recent)\s+one\b|"
        r"\b(?:that|this|the)\s+(?:order|item)\b|"
        r"\b(?:order|item)\s+(?:with|for|containing)\b|"
        r"\b(?:make|set|change|update)\s+(?:that|it|the\s+.+?)\s+"
        r"(?:quantity\s+)?(?:to\s+)?(?:one|two|three|four|five|[1-9][0-9]{0,2})\b|"
        r"\b(?:remove|delete)\s+(?:that|it|the\s+.+)\b|"
        r"\bcancel\s+(?:that|it|the\s+.+|(?:my\s+)?order)\b|"
        r"\b(?:return|send\s+back)\s+(?:that|it|the\s+.+)\b|"
        r"\b(?:return|refund)\s+(?:status|finished|complete|done)\b|"
        r"\b(?:is|was)\s+(?:that|it|this|the\s+(?:item|order|store\s+group))"
        r"\s+(?:return\s+)?eligible\b|"
        r"\b(?:can|could)\s+(?:that|it|this)\s+be\s+(?:returned|refunded)\b|"
        r"\b(?:check|show)\s+(?:that|its|the)\s+(?:return\s+)?eligibility\b|"
        r"\b(?:the\s+)?(?:first|second|third|last|latest)\s+store\s+group\b|"
        r"\b(?:the\s+)?(?:entire|whole)?\s*[a-z0-9][a-z0-9 &'’-]{1,80}"
        r"\s+store\s+group\b",
        value,
    ))


def _has_grounded_order_or_return_context(
    recent_messages: Sequence[tuple[str, str]],
) -> bool:
    """Require both a customer-owned target and an assistant grounding turn."""

    recent = recent_messages[-6:]
    customer_target = any(
        role.casefold() == "user"
        and re.search(
            r"\b(?:my|latest|last|first|second|third)\b.{0,55}"
            r"\b(?:order|item|keyboard|mouse|product|return)\b|"
            r"\b(?:order|item|keyboard|mouse|product)\b.{0,55}\bmy\b",
            content.casefold(),
        )
        for role, content in recent
    )
    assistant_grounding = any(
        role.casefold() == "assistant"
        and re.search(
            r"\b(?:your|owned|that|this)\b.{0,60}"
            r"\b(?:order|item|store\s+group|return|eligible)\b|"
            r"\b(?:order|item|store\s+group|return|eligible)\b.{0,60}"
            r"\b(?:your|owned)\b",
            content.casefold(),
        )
        for role, content in recent
    )
    return customer_target and assistant_grounding


def _is_generic_refund_policy_question(value: str) -> bool:
    """Keep explicit marketplace-wide refund questions on the knowledge path."""

    return bool(re.search(
        r"\b(?:general\s+)?(?:refund|return)\s+policy\b|"
        r"\bhow\s+(?:do\s+refunds?\s+work|long\s+do\s+refunds?\s+"
        r"(?:usually\s+)?take)\b|"
        r"\bwhat\b.{0,45}\b(?:products?|items?|kinds?)\b.{0,45}"
        r"\b(?:eligible\s+for\s+refunds?|refundable)\b|"
        r"\bdo\s+you\s+allow\s+refunds?\b",
        value,
    ))


def _is_current_order_item_listing_follow_up(
    value: str, recent_messages: Sequence[tuple[str, str]]
) -> bool:
    recent = " ".join(content.casefold() for _, content in recent_messages[-6:])
    return bool(
        re.search(r"\b(?:order|orders)\b", recent)
        and re.search(r"\b(?:that|this|the)\s+item\b", value)
        and re.search(
            r"\b(?:current|currently|now|still|available|availability|listing|price)\b",
            value,
        )
    )


def hard_safety_response(value: str) -> tuple[str, str] | None:
    """Return a stable unsafe category and canonical customer refusal."""

    normalized = _normalize(value)
    if re.search(
        r"\b(?:show|view|read|open|check|list|get|access|use|change|cancel|refund|"
        r"modify|alter|add|remove|update|confirm|submit|complete|place|pay|"
        r"checkout|check\s*out)\b.{0,80}"
        rf"\b{_CROSS_ACTOR_OWNER_PATTERN}\s+(?:(?:latest|last|first|second|third|recent|delivered|confirmed|cancelled)\s+)?(?:account|cart|orders?|returns?|refunds?|(?:pending\s+|prepared\s+)?checkout)\b|"
        r"\b(?:what(?:'s| is)\s+in|where\s+(?:is|are))\b.{0,60}"
        rf"\b{_CROSS_ACTOR_OWNER_PATTERN}\s+(?:(?:latest|last|first|second|third|recent|delivered|confirmed|cancelled)\s+)?(?:account|cart|orders?|returns?|refunds?|(?:pending\s+|prepared\s+)?checkout)\b",
        normalized,
    ) or re.search(
        r"\b(?:show|view|read|open|check|list|get|access|use|change|cancel|"
        r"refund|modify|approve|submit)\b.{0,80}"
        r"\b(?:return|refund|order|cart)\b.{0,45}"
        r"\b(?:for|belonging\s+to|owned\s+by)\s+(?:customer|user)\s+"
        r"(?!me\b|my\b)[a-z0-9@._+-]+|"
        r"\b(?:customer|user)\s+(?!me\b|my\b)[a-z0-9@._+-]+(?:['’]s)?"
        r"\s+(?:return|refund|order|cart)\b",
        normalized,
    ):
        return (
            "CROSS_ACTOR_ACCESS_REQUEST",
            "I can only access the signed-in customer's cart, checkout, and orders, "
            "plus that customer's returns. "
            "I can't view, use, confirm, or change another person's account, cart, "
            "checkout, orders, returns, or refunds. No "
            "marketplace action was run.",
        )
    prohibited_abuse = (
        (
            r"\b(?:stolen|skimmed)\s+(?:credit\s+|debit\s+)?card\b|"
            r"\b(?:credit\s+|debit\s+)?card\s+(?:number|details|credentials)\s+"
            r"(?:i\s+)?(?:stole|skimmed)\b",
            "STOLEN_PAYMENT_CREDENTIALS",
        ),
        (
            r"\b(?:bypass|evade|skip)\s+(?:the\s+)?(?:payment|checkout|payment verification)\b|"
            r"\b(?:buy|get|take)\b.{0,40}\bwithout\s+paying\b",
            "PAYMENT_BYPASS_REQUEST",
        ),
        (
            r"\b(?:access|take over|break into|log into|hijack)\b.{0,40}"
            rf"\b{_CROSS_ACTOR_OWNER_PATTERN}\s+account\b",
            "ACCOUNT_TAKEOVER_REQUEST",
        ),
        (
            r"\b(?:change|cancel|refund|modify|alter|add|remove|update)\b.{0,60}"
            rf"\b{_CROSS_ACTOR_OWNER_PATTERN}\s+(?:(?:latest|last|first|second|third|recent|delivered|confirmed|cancelled)\s+)?(?:order|cart|return|refund)\b|"
            r"\b(?:change|cancel|refund|modify|alter|add|remove|update)\b.{0,40}"
            r"\bsomeone else's\s+(?:order|cart|return|refund)\b",
            "CROSS_ACTOR_MUTATION_REQUEST",
        ),
        (
            r"\b(?:bypass|disable|evade)\b.{0,30}\b(?:authorization|permission|access control)\b|"
            r"\b(?:exploit|hack)\b.{0,30}\b(?:admin|authorization|permission)\b",
            "AUTHORIZATION_BYPASS_REQUEST",
        ),
        (
            r"\b(?:call|use|invoke)\s+(?:the\s+)?admin\s+api\b|"
            r"\b(?:ban|suspend)\s+(?:a\s+)?(?:user|seller)\s+directly\b",
            "ADMIN_OPERATION_BYPASS_REQUEST",
        ),
    )
    for pattern, reason in prohibited_abuse:
        if re.search(pattern, normalized):
            return (
                reason,
                "I can't help with fraud, unauthorized access, payment bypass, or "
                "changing another person's account or order. No marketplace action "
                "was run. I can help with legitimate marketplace use.",
            )
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


def unsupported_customer_authority_response(value: str) -> tuple[str, str] | None:
    """Return the Level 4 customer capability boundary for direct action requests."""

    normalized = _normalize(value)
    if re.search(
        r"\b(?:force|choose|select|set|simulate|fake|override|control|use)\b.{0,55}"
        r"\b(?:payment\s+provider|provider|payment\s+outcome|payment\s+result|"
        r"declin(?:e|ed)|fail(?:ure|ed)?|succeed(?:ed)?|success(?:ful)?)\b|"
        r"\b(?:make|mark)\b.{0,35}\bpayment\b.{0,20}\b(?:declin(?:e|ed)|"
        r"fail(?:ed)?|succeed(?:ed)?|success(?:ful)?|paid)\b",
        normalized,
    ):
        return (
            "UNSUPPORTED_PAYMENT_CONTROL",
            "I can't choose or force a payment provider, status, or outcome. No payment or "
            "order was submitted.",
        )
    combined_authorities = bool(
        re.search(r"\b(?:admin|administrator|moderator)\b", normalized)
        and re.search(r"\b(?:refund|payment|publish)\b", normalized)
    )
    if not combined_authorities and re.search(r"\b(?:approve|issue|force|process)\b.{0,35}\brefund\b|\brefund\s+me\s+directly\b", normalized):
        return (
            "FORBIDDEN_CUSTOMER_AUTHORITY",
            "I can't approve or directly issue refunds. I can help with the "
            "supported return and refund-request process.",
        )
    if not combined_authorities and re.search(r"\b(?:act as|become|use|invoke)\b.{0,35}\b(?:admin|administrator|moderator)\b", normalized):
        return (
            "FORBIDDEN_CUSTOMER_AUTHORITY",
            "I don't have customer-facing access to Admin actions.",
        )
    patterns = (
        r"\b(?:act as|become|use|invoke)\b.{0,35}\b(?:admin|administrator|moderator)\b",
        r"\b(?:make|grant)\b.{0,25}\b(?:me\s+)?(?:an?\s+)?(?:admin|administrator|moderator)\b",
        r"\b(?:ban|suspend|moderate|approve|reject)\b.{0,45}\b(?:user|seller|account|business|listing)\b",
        r"\bremove\b.{0,35}\bcompetitor(?:'s)?\b.{0,35}\blisting\b",
        r"\b(?:issue|force|process|approve|send|give)\b.{0,35}\brefund\b",
        r"\b(?:take|process|charge|collect)\b.{0,35}\bpayment\b",
        r"\b(?:publish|activate|approve)\b.{0,35}\blisting\b",
    )
    if not any(re.search(pattern, normalized) for pattern in patterns):
        return None
    return (
        "FORBIDDEN_CUSTOMER_AUTHORITY",
        "I can't perform admin or moderation actions, issue refunds, take or "
        "process payments, publish listings, or place or change orders. No "
        "marketplace action was run. I can help with public listings, your own "
        "cart, and your own order information.",
    )


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
    risk_level = 5 if scope == "UNSAFE" else 0
    policy_decision = (
        "TERMINATE" if scope == "UNSAFE"
        else "BOUNDARY_RESPONSE" if scope == "OUT_OF_SCOPE"
        else "ALLOW_MODEL"
    )
    return MarketplaceScopeResult(
        scope=scope,
        requiredGrounding=required_grounding,
        confidence=confidence,
        marketplaceContextUsed=context_used,
        reasonCode=reason_code,
        riskLevel=risk_level,
        policyDecision=policy_decision,
    )
