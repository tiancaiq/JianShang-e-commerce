from __future__ import annotations

import logging
import re
from collections.abc import Awaitable, Callable, Sequence
from decimal import Decimal

from .context_builder import ContextBuilder, MarketplaceTurnContext
from .refinement import (
    customer_search_refinement, explicit_search_confirmation_requested,
)
from .policy import MarketplaceAgentV2ToolPolicy
from .capabilities import MarketplaceCustomerCapabilityBoundary
from .provider import (
    MarketplaceAgentV2Model,
    MarketplaceAgentV2ProviderFailure,
    TextDeltaCallback,
)
from .schemas import (
    ActiveSkill,
    CollectListingInformationArguments,
    EvidenceReference,
    ListingAttachment,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2ContextualRefinement,
    MarketplaceAgentV2Refinement,
    MarketplaceAgentV2RefinementOption,
    MarketplaceAgentV2Message,
    MarketplaceAgentV2PendingInteraction,
    ModelDecision,
    MarketplaceScopeResult,
    OrchestrationResult,
    SubmitMyCheckoutArguments,
    CancelMyOrderArguments,
    ToolActivity,
    ToolObservation,
    ToolProposal,
    SkillSummary,
)
from .skill_registry import Skill, SkillRegistry, SkillValidationError
from .seller_workflow import extract_initial_item_type
from .scope import (
    MARKETPLACE_SCOPE_BOUNDARY_RESPONSE,
    MarketplaceScopeClassifier,
    _selected_order_follow_up,
    hard_safety_response,
    unsupported_customer_authority_response,
)
from .tools import ActivityCallback, MarketplaceAgentV2ToolRegistry


MAX_AGENT_STEPS = 5
_LOGGER = logging.getLogger(__name__)
ToolCompletedCallback = Callable[[ToolObservation], Awaitable[None]]
ConfirmationPreparedCallback = Callable[
    [MarketplaceAgentV2PendingInteraction],
    Awaitable[MarketplaceAgentV2PendingInteraction],
]
_CART_MUTATION_TOOLS = {
    "add_to_my_cart", "update_my_cart_quantity", "remove_from_my_cart",
}
_RETURN_REQUEST_TOOLS = {
    "get_my_return", "prepare_my_return_request", "submit_my_return_request",
}
_PRIVATE_COMMERCE_TOOLS = {
    "get_my_cart", "list_my_orders", "get_my_order", *_CART_MUTATION_TOOLS,
    "prepare_my_checkout", "get_my_checkout", "submit_my_checkout",
    "preview_my_order_cancellation", "cancel_my_order",
    *_RETURN_REQUEST_TOOLS,
}
_DEFERRED_COMMERCE_ACTION_RESPONSE = (
    "I can help with your cart, but I can’t check out, take payment, or place an "
    "order. No checkout, payment, or order was started."
)
_CART_MUTATION_DISABLED_RESPONSE = (
    "Cart changes are not available through the Agent right now. No cart item "
    "was added, updated, or removed."
)
_RETURN_REQUEST_DISABLED_RESPONSE = (
    "Return and refund-request tools are not available through the Agent right "
    "now. I did not prepare or submit a return request, and no refund was issued."
)
_GENERIC_RETURN_POLICY_RESPONSE = (
    "For business orders, the marketplace supports a return request for an entire "
    "delivered store group within 30 days of delivery. Eligibility is checked from "
    "the current order state, and submitting a request does not approve the return or "
    "issue a refund. Any refund is handled later by the marketplace return and payment "
    "workflow. Individual listings use buyer-seller arrangements rather than the "
    "business-order return workflow."
)
_NO_REFERENCED_CHECKOUT_RESPONSE = (
    "I don't have a prepared checkout referenced in this conversation. I can review "
    "your cart or prepare checkout for the whole current cart, but I won't submit "
    "payment or place an order without the required confirmation."
)


class MarketplaceAgentV2OrchestrationFailure(RuntimeError):
    """Carries only a stable failure category across the privacy boundary."""

    def __init__(self, kind: str, *, repair_reason: str | None = None) -> None:
        super().__init__(kind)
        self.kind = kind
        self.repair_reason = repair_reason


def _generic_return_policy_request(value: str) -> bool:
    """Recognize marketplace-wide policy, never a customer's own eligibility."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:general\s+)?(?:refund|return)\s+policy\b|"
        r"\bhow\s+do\s+(?:refunds?|returns?)\s+(?:generally\s+)?work\b|"
        r"\bwhat\s+(?:items?|products?)\s+can\s+be\s+returned\b|"
        r"\bhow\s+long\s+is\s+the\s+return\s+window\b",
        normalized,
    ))


def _checkout_read_request(value: str) -> bool:
    """Identify a read of a previously prepared checkout, not preparation."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:what(?:'s| is)\s+in|show(?:\s+me)?|open|review)\b.{0,45}"
        r"\b(?:this|that|my|the)\s+checkout\b|"
        r"\bwhat(?:'s| is)\s+the\s+(?:checkout\s+)?total\s+again\b|"
        r"\bis\s+(?:this|that|my|the)\s+checkout\s+still\s+active\b",
        normalized,
    ))


def _referenced_checkout_ids(
    observations: Sequence[ToolObservation],
) -> tuple[str, ...]:
    return tuple(dict.fromkeys(
        item.checkout.checkout_id
        for item in observations
        if item.checkout is not None
    ))


def _listing_detail_request(value: str) -> bool:
    """Detect one-listing detail intent only after a trusted reference exists."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:tell\s+me\s+more\s+about|show\s+me\s+details?\s+(?:for|of|about)|"
        r"details?\s+(?:of|about)|"
        r"what\s+are\s+the\s+details\s+of|open)\b.{0,80}"
        r"\b(?:first|second|third|fourth|fifth|this|that|it|one|listing)\b|"
        r"\b(?:tell\s+me\s+more\s+about|open)\s+.{2,120}$|"
        r"\bwhat\s+is\s+(?:this|that)\s+item\b",
        normalized,
    ))


def _listing_availability_request(value: str) -> bool:
    """Detect an operational availability question for a grounded listing."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:is|are)\b.{0,60}\b(?:available\s+right\s+now|still\s+available|"
        r"in\s+stock)\b|"
        r"\bcan\s+i\s+buy\b.{0,45}\bright\s+now\b|"
        r"\bdo\s+they\s+still\s+have\s+(?:this|that|it)\b",
        normalized,
    ))


def _explicit_cart_removal_request(value: str) -> bool:
    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"^(?:actually\s+)?(?:please\s+)?(?:remove|delete|take)\b",
        normalized,
    ))


def _explicit_cart_quantity_request(value: str) -> bool:
    """Recognize a requested quantity change only for grounding validation."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:make|set|change|update)\b.{0,65}"
        r"\b(?:quantity\s+)?(?:back\s+)?(?:to\s+)?"
        r"(?:[1-9][0-9]{0,2}|one|two|three|four|five)\b",
        normalized,
    ))


def _ordered_order_detail_request(value: str) -> bool:
    """Require a fresh detail read when the customer selects a listed order."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:first|second|third|latest|most recent)\s+order\b|"
        r"\border\s+(?:one|two|three|1|2|3)\b",
        normalized,
    ))


def _terminal_tool_requirement(
    *,
    current_message: str,
    referenced_listings: Sequence[ListingAttachment],
    prior_observations: Sequence[ToolObservation],
    turn_observations: Sequence[ToolObservation],
    ambiguous_cart_reference: bool,
    seller_collection_required: bool = False,
) -> str | None:
    """Validate missing operational evidence without globally routing messages."""

    if seller_collection_required and not any(
        item.tool == "collect_listing_information"
        for item in turn_observations
    ):
        return "SELLER_COLLECTION_TOOL_REQUIRED"

    if (
        referenced_listings
        and _listing_availability_request(current_message)
        and not any(item.tool == "check_availability" for item in turn_observations)
    ):
        return "AVAILABILITY_TOOL_REQUIRED"
    if (
        referenced_listings
        and _listing_detail_request(current_message)
        and not any(item.tool == "get_listing" for item in turn_observations)
    ):
        return "LISTING_DETAIL_TOOL_REQUIRED"
    if _explicit_cart_removal_request(current_message) and not ambiguous_cart_reference:
        if any(
            item.tool == "remove_from_my_cart" for item in turn_observations
        ):
            return None
        cart_references = next((
            item.cart_item_references
            for item in reversed((*prior_observations, *turn_observations))
            if item.tool in {"get_my_cart", *_CART_MUTATION_TOOLS}
            and item.cart_item_references
        ), ())
        mutation_reference = next((
            item.cart_mutation_reference
            for item in reversed((*prior_observations, *turn_observations))
            if item.status == "SUCCEEDED"
            and item.cart_mutation_reference is not None
        ), None)
        if cart_references or mutation_reference is not None:
            return "CART_MUTATION_TOOL_REQUIRED"
        if (
            not referenced_listings
            and not any(item.tool == "get_my_cart" for item in turn_observations)
        ):
            return "CART_READ_TOOL_REQUIRED"
    if _explicit_cart_quantity_request(current_message) and not ambiguous_cart_reference:
        if any(item.tool == "update_my_cart_quantity" for item in turn_observations):
            return None
        cart_references = next((
            item.cart_item_references
            for item in reversed((*prior_observations, *turn_observations))
            if item.tool in {"get_my_cart", *_CART_MUTATION_TOOLS}
            and item.cart_item_references
        ), ())
        if cart_references:
            return "CART_QUANTITY_TOOL_REQUIRED"
        if not any(item.tool == "get_my_cart" for item in turn_observations):
            return "CART_READ_TOOL_REQUIRED"
    if (
        _checkout_read_request(current_message)
        and _referenced_checkout_ids(prior_observations)
        and not any(item.tool == "get_my_checkout" for item in turn_observations)
    ):
        return "CHECKOUT_READ_TOOL_REQUIRED"
    if (
        _ordered_order_detail_request(current_message)
        and any(
            item.tool == "list_my_orders" and item.order_references
            for item in prior_observations
        )
        and not any(
            item.tool == "get_my_order" for item in turn_observations
        )
    ):
        return "ORDER_DETAIL_TOOL_REQUIRED"
    selected_order = next((
        item for item in reversed(prior_observations)
        if item.tool == "get_my_order" and item.order_references
    ), None)
    if (
        selected_order is not None
        and _selected_order_follow_up(
            " ".join(current_message.casefold().replace("’", "'").split())
        )
        and not any(item.tool == "get_my_order" for item in turn_observations)
    ):
        return "ORDER_DETAIL_TOOL_REQUIRED"
    return None


def _successful_completion_read(
    observations: Sequence[ToolObservation],
) -> bool:
    """Recover only from an owning-service read with enough facts to answer."""

    return any(
        item.status == "SUCCEEDED" and (
            item.tool == "get_listing" and item.reason == "LISTING_VERIFIED"
            and bool(item.attachments)
            or item.tool == "check_availability"
            and item.broad_inventory_count is not None
            or item.tool == "get_my_checkout" and item.checkout is not None
            or item.tool == "get_my_order" and item.order is not None
        )
        for item in observations
    )


def _grounded_selected_order_follow_up(
    current_message: str,
    observations: Sequence[ToolObservation],
) -> str | None:
    """Answer a selected-order follow-up from the refreshed owned order, not an unrelated return probe."""

    if not _selected_order_follow_up(current_message.casefold()):
        return None
    read = next((
        item.order for item in reversed(observations)
        if item.tool == "get_my_order"
        and item.status == "SUCCEEDED"
        and item.order is not None
    ), None)
    if read is None:
        return None
    status = read.status.replace("_", " ").lower()
    payment = read.payment_status.replace("_", " ").lower()
    if re.search(r"\b(?:how much|what)\s+(?:did\s+)?i\s+pay\b", current_message.casefold()):
        return (
            f"The recorded total for your selected order is "
            f"{read.total.amount} {read.total.currency}. Its payment status is {payment}."
        )
    return (
        f"Your selected order is currently {status}. Its payment status is "
        f"{payment}, and its recorded total is {read.total.amount} "
        f"{read.total.currency}."
    )


def _natural_no_tool_fallback(
    current_message: str,
    *,
    scope_result: MarketplaceScopeResult,
    registered_tools: Sequence[str],
) -> str | None:
    """Recover ordinary terminal conversation without inventing tool outcomes."""

    if scope_result.required_grounding != "NONE":
        return None
    normalized = " ".join(current_message.casefold().split())
    if scope_result.reason_code == "AGENT_CAPABILITIES":
        capabilities = ["find, recheck, and compare marketplace listings"]
        if "get_my_cart" in registered_tools:
            capabilities.append("read your cart")
        if "remove_from_my_cart" in registered_tools:
            capabilities.append("make explicit cart changes")
        if "list_my_orders" in registered_tools:
            capabilities.append("read your orders")
        if "collect_listing_information" in registered_tools:
            capabilities.append("help prepare information for a listing you want to sell")
        if len(capabilities) == 1:
            return f"I can {capabilities[0]}."
        return "I can " + ", ".join(capabilities[:-1]) + (
            f", and {capabilities[-1]}."
        )
    if normalized in {"thanks", "thank you", "thank you so much", "thanks a lot"}:
        return "You're welcome."
    if scope_result.reason_code == "NATURAL_CONVERSATION":
        return "Hello! How can I help with the marketplace today?"
    return MARKETPLACE_SCOPE_BOUNDARY_RESPONSE


class MarketplaceAgentV2Orchestrator:
    """Runs an isolated five-decision model-first loop over the strict registry."""

    def __init__(
        self,
        model: MarketplaceAgentV2Model,
        registry: MarketplaceAgentV2ToolRegistry,
        *,
        model_timeout_seconds: float = 10.0,
        confirmation_execution_enabled: bool = True,
        skill_registry: SkillRegistry | None = None,
    ) -> None:
        self._model = model
        self._registry = registry
        self._model_timeout_seconds = model_timeout_seconds
        self._confirmation_execution_enabled = confirmation_execution_enabled
        self._skill_registry = skill_registry
        self._context_builder = ContextBuilder()

    async def close(self) -> None:
        await self._model.close()

    def confirmation_policy_allows(
        self, interaction: MarketplaceAgentV2PendingInteraction
    ) -> bool:
        """Revalidate that the exact prepared action still has an executable tool."""

        return (
            self._confirmation_execution_enabled
            and interaction.type == "CONFIRM_ACTION"
            and (
                (
                    interaction.action == "RUN_REFINED_SEARCH"
                    and "search_listings" in self._registry.names
                )
                or (
                    interaction.action == "SUBMIT_CHECKOUT"
                    and "submit_my_checkout" in self._registry.names
                )
                or (
                    interaction.action == "CANCEL_ORDER"
                    and "cancel_my_order" in self._registry.names
                )
                or (
                    interaction.action == "SUBMIT_RETURN_REQUEST"
                    and "submit_my_return_request" in self._registry.names
                )
            )
        )

    async def revalidate_confirmation(
        self,
        *,
        interaction: MarketplaceAgentV2PendingInteraction,
        actor_authorization: str | None,
        correlation_id: str,
    ):
        """Resolve current owning-service facts before a Level-3 atomic claim."""

        if interaction.action == "SUBMIT_CHECKOUT":
            if "submit_my_checkout" not in self._registry.names:
                return None
            return await self._registry.revalidate_checkout_confirmation(
                arguments=interaction.arguments,
                actor_authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if interaction.action == "CANCEL_ORDER":
            if "cancel_my_order" not in self._registry.names:
                return None
            return await self._registry.revalidate_order_cancellation_confirmation(
                arguments=interaction.arguments,
                actor_authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if interaction.action == "SUBMIT_RETURN_REQUEST":
            if "submit_my_return_request" not in self._registry.names:
                return None
            return await self._registry.revalidate_return_confirmation(
                arguments=interaction.arguments,
                actor_authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        return None

    async def cancel_checkout_confirmation(
        self,
        *,
        interaction: MarketplaceAgentV2PendingInteraction,
        actor_authorization: str | None,
        correlation_id: str,
    ) -> bool:
        """Cancels the Order-owned checkout behind a terminal confirmation."""

        if interaction.action != "SUBMIT_CHECKOUT":
            return True
        return await self._registry.cancel_checkout_confirmation(
            arguments=interaction.arguments,
            actor_authorization=actor_authorization,
            correlation_id=correlation_id,
            action_reference=interaction.confirmation_id or interaction.id,
        )

    async def run(
        self,
        *,
        actor_user_id: str,
        actor_authorization: str | None = None,
        current_message: str,
        recent_messages: Sequence[tuple[str, str]],
        referenced_listings: Sequence[ListingAttachment],
        prior_observations: Sequence[ToolObservation] = (),
        turn_context: MarketplaceTurnContext | None = None,
        pending_interaction: MarketplaceAgentV2PendingInteraction | None = None,
        active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None,
        confirmed_interaction: MarketplaceAgentV2PendingInteraction | None = None,
        correlation_id: str,
        activity: ActivityCallback | None = None,
        tool_completed: ToolCompletedCallback | None = None,
        text_delta: TextDeltaCallback | None = None,
        scope_result: MarketplaceScopeResult | None = None,
        invocation_id: str | None = None,
        confirmation_prepared: ConfirmationPreparedCallback | None = None,
        checkout_release_verified: bool | None = None,
    ) -> OrchestrationResult:
        if turn_context is None:
            turn_context = self._context_builder.from_existing_inputs(
                current_message=current_message,
                recent_messages=recent_messages,
                referenced_listings=referenced_listings,
                prior_observations=prior_observations,
                pending_interaction=pending_interaction,
                active_workflow=active_workflow,
            )
        elif (
            turn_context.current_message != current_message
            or (
                turn_context.identity is not None
                and turn_context.identity.actor_user_id != actor_user_id
            )
        ):
            raise ValueError("Marketplace Agent V2 turn context mismatch")
        recent_messages = turn_context.recent_messages
        referenced_listings = turn_context.referenced_listings
        prior_observations = turn_context.prior_observations
        selected_scope = scope_result or MarketplaceScopeClassifier().classify(
            current_message=current_message,
            recent_messages=recent_messages,
            referenced_listings=referenced_listings,
            pending_interaction=pending_interaction,
            preference_state={},
        )
        # A canonical search anchors an exact query restatement even when a
        # product title contains support words such as "Return".
        if (
            selected_scope.scope == "IN_SCOPE"
            and selected_scope.reason_code == "MARKETPLACE_SUPPORT"
            and active_workflow is None
            and pending_interaction is None
            and "search_listings" in self._registry.names
            and (
                replacement := customer_search_refinement(
                    current_message, turn_context.latest_search,
                )
            ) is not None
            and replacement.kind == "QUERY_REPLACEMENT"
        ):
            selected_scope = selected_scope.model_copy(update={
                "required_grounding": "LISTING_DATA",
                "reason_code": "CONTEXTUAL_MARKETPLACE_FOLLOW_UP",
                "marketplace_context_used": True,
            })
        if selected_scope.scope == "UNSAFE":
            safety = hard_safety_response(current_message)
            content = (
                safety[1] if safety is not None
                else "I can't help with that request. I can help with safe marketplace questions."
            )
            terminal_interaction = next((
                item for item in (pending_interaction, confirmed_interaction)
                if item is not None
                and item.type == "CONFIRM_ACTION"
                and item.status in {
                    "CONSUMED", "CANCELLED", "EXPIRED", "INVALIDATED",
                }
            ), None)
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=content,
                    pendingInteraction=terminal_interaction,
                ),
                decisionCount=0,
                pendingInteraction=terminal_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        forbidden_authority = unsupported_customer_authority_response(current_message)
        if forbidden_authority is not None:
            content = forbidden_authority[1]
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=None,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if selected_scope.scope == "OUT_OF_SCOPE":
            if text_delta is not None:
                await text_delta(MARKETPLACE_SCOPE_BOUNDARY_RESPONSE)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=MARKETPLACE_SCOPE_BOUNDARY_RESPONSE
                ),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        if current_message.strip().casefold() in {"never mind", "nevermind"}:
            content = "Okay — I’ll stop here. No marketplace tool was run."
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        if (
            confirmed_interaction is not None
            and confirmed_interaction.status == "CANCELLED"
        ):
            if confirmed_interaction.action == "CANCEL_ORDER":
                content = (
                    "Okay — I won’t request cancellation of that order. "
                    "The order was not changed."
                )
            elif confirmed_interaction.action == "SUBMIT_RETURN_REQUEST":
                content = (
                    "Okay — I won’t submit that return request. "
                    "The order and refund status were not changed."
                )
            elif confirmed_interaction.action == "SUBMIT_CHECKOUT":
                content = (
                    "Okay — I cancelled that prepared checkout and released its "
                    "temporary inventory hold. No payment or order was submitted."
                    if checkout_release_verified
                    else "Okay — I cancelled that checkout confirmation, but I couldn't "
                    "verify release of its temporary inventory hold. No payment or order "
                    "was submitted."
                )
            else:
                content = "Okay — I won’t run that refined search."
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=content,
                    pendingInteraction=confirmed_interaction,
                ),
                decisionCount=0,
                pendingInteraction=confirmed_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if (
            confirmed_interaction is not None
            and confirmed_interaction.status in {"EXPIRED", "INVALIDATED"}
        ):
            content = (
                _order_cancellation_confirmation_terminal_content(
                    confirmed_interaction.status, prior_observations
                )
                if confirmed_interaction.action == "CANCEL_ORDER"
                else _return_confirmation_terminal_content(
                    confirmed_interaction.status, prior_observations
                )
                if confirmed_interaction.action == "SUBMIT_RETURN_REQUEST"
                else "That confirmation expired. Please prepare the action again if you "
                "still want it."
                if confirmed_interaction.status == "EXPIRED"
                else "That prepared action is no longer valid. Please prepare a new "
                "confirmation if you still want it."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=confirmed_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if (
            pending_interaction is not None
            and pending_interaction.type == "CONFIRM_ACTION"
            and pending_interaction.status in {
                "CONSUMED", "CANCELLED", "EXPIRED", "INVALIDATED",
            }
            and _confirmation_answer(current_message) is not None
        ):
            content = (
                _order_cancellation_confirmation_terminal_content(
                    pending_interaction.status, prior_observations
                )
                if pending_interaction.action == "CANCEL_ORDER"
                else _return_confirmation_terminal_content(
                    pending_interaction.status, prior_observations
                )
                if pending_interaction.action == "SUBMIT_RETURN_REQUEST"
                else _checkout_confirmation_terminal_content(
                    pending_interaction.status, prior_observations
                )
                if pending_interaction.action == "SUBMIT_CHECKOUT"
                else _terminal_confirmation_response(pending_interaction.status)
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=content,
                    pendingInteraction=pending_interaction,
                ),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if (
            pending_interaction is not None
            and pending_interaction.type == "CONFIRM_ACTION"
            and pending_interaction.status in {"WAITING", "CONFIRMED"}
            and _ambiguous_confirmation_answer(current_message)
        ):
            content = (
                "Please answer yes to request cancellation of that exact order, or no "
                "to keep it unchanged."
                if pending_interaction.action == "CANCEL_ORDER"
                else "Please answer yes to submit that exact return request, or no "
                "to leave the order unchanged."
                if pending_interaction.action == "SUBMIT_RETURN_REQUEST"
                else "Please answer yes to submit that exact prepared checkout, or no "
                "to cancel it."
                if pending_interaction.action == "SUBMIT_CHECKOUT"
                else "Please answer yes to run that exact prepared search, or no to cancel it."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=content,
                    pendingInteraction=pending_interaction,
                ),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if _cart_add_reference_unresolved(
            current_message,
            referenced_listings=referenced_listings,
            prior_observations=prior_observations,
        ):
            content = (
                "I couldn't resolve that listing. Please name or select a current "
                "listing before I change your cart."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if _partial_cart_checkout_request(current_message):
            content = (
                "Checkout uses your whole current cart. If you want only that item, "
                "tell me which other cart items to remove first; I won't remove them "
                "or start checkout without that direction."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if _ambiguous_checkout_reference(
            current_message,
            referenced_listings=referenced_listings,
            prior_observations=prior_observations,
        ):
            content = (
                "Which item do you mean? Checkout uses your whole current cart, so "
                "please name the item or ask me to review the cart first."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if (
            selected_scope.required_grounding == "LISTING_DATA"
            and active_workflow is None
            and pending_interaction is None
            and _unavailable_listing_ordinal(current_message, referenced_listings)
        ):
            # An ordinal outside the displayed cards cannot identify a listing,
            # even when a model could choose another active card by name.
            content = (
                "I don't have that numbered listing in the current results. "
                "Please name or select a displayed listing."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        observations: list[ToolObservation] = list(prior_observations[-5:])
        turn_observations: list[ToolObservation] = []
        attachments: dict[str, ListingAttachment] = {}
        activities: list[ToolActivity] = []
        input_tokens = 0
        output_tokens = 0
        terminal_repair_used = False
        contextual_refinement = _contextual_refinement_selection(
            current_message, prior_observations
        )
        comparison_context = _is_comparison_request(
            current_message, referenced_listings
        )
        contextual_attachments = _contextual_response_attachments(
            current_message, referenced_listings
        )
        recommendation_context = bool(contextual_attachments) or comparison_context
        search_refinement = (
            customer_search_refinement(current_message, turn_context.latest_search)
            if (
                selected_scope.required_grounding == "LISTING_DATA"
                and "search_listings" in self._registry.names
                and active_workflow is None
                and pending_interaction is None
                and not comparison_context
            ) else None
        )
        search_dependent_turn = bool(
            selected_scope.required_grounding == "LISTING_DATA"
            and turn_context.latest_search is not None
            and active_workflow is None
            and pending_interaction is None
            and confirmed_interaction is None
        )
        malformed_input = _looks_like_unintelligible_input(current_message)
        ambiguous_cart_reference = _cart_mutation_reference_ambiguous(
            current_message,
            referenced_listings=referenced_listings,
            prior_observations=prior_observations,
        )
        informational_order_cancellation = (
            _informational_order_cancellation_request(current_message)
        )
        informational_return = _return_read_only_request(
            current_message, recent_messages
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(
                {item.listing_id for item in referenced_listings}
                | set(_commerce_listing_ids(prior_observations))
            ),
            prior_observations=tuple(prior_observations[-5:]),
            comparison_context=(
                comparison_context
                and not _listing_detail_request(current_message)
                and not _listing_availability_request(current_message)
            ),
            confirmation_without_pending=(
                _confirmation_answer(current_message) is not None
                and confirmed_interaction is None
            ),
            message_scope=selected_scope.scope,
            required_grounding=selected_scope.required_grounding,
            active_workflow=active_workflow,
            seller_search_allowed=_explicit_seller_comparison_request(current_message),
            required_search_query=(
                contextual_refinement.search_query
                if contextual_refinement is not None else None
            ),
            required_search_category_name=(
                contextual_refinement.value
                if contextual_refinement is not None
                and contextual_refinement.facet == "CATEGORY"
                else None
            ),
            latest_search=turn_context.latest_search,
            search_refinement=search_refinement,
            explicit_search_confirmation=explicit_search_confirmation_requested(
                current_message
            ),
            required_availability_categories=frozenset(
                " ".join(item.category_name.casefold().split())
                for item in referenced_listings
            ) if _listing_availability_request(current_message) else frozenset(),
            cart_mutation_reference_ambiguous=ambiguous_cart_reference,
            order_cancellation_mutation_requested=(
                not informational_order_cancellation
                and _explicit_order_cancellation_request(current_message)
            ),
            return_request_mutation_requested=(
                not informational_return
                and _return_request_mutation_intent(
                    current_message, recent_messages
                )
            ),
            partial_return_requested=_partial_return_request(current_message),
            required_return_reason=_required_return_reason(
                current_message, recent_messages
            ),
            return_comment_source=current_message,
            capability_boundary=getattr(
                self._registry,
                "capability_boundary",
                MarketplaceCustomerCapabilityBoundary(),
            ),
        )
        schemas = self._registry.provider_schemas()
        if tuple(item["name"] for item in schemas) != self._registry.names:
            raise RuntimeError("Marketplace Agent V2 registry is inconsistent")
        available_skills = (
            self._skill_registry.available(
                surface="customer", enabled_tools=self._registry.names
            )
            if self._skill_registry is not None else ()
        )
        active_skill: Skill | None = None
        cart_mutation_disabled = (
            _is_explicit_cart_mutation_request(current_message)
            and not any(tool in self._registry.names for tool in _CART_MUTATION_TOOLS)
        )
        checkout_enabled = "prepare_my_checkout" in self._registry.names
        return_tools_disabled = (
            _private_return_workflow_request(current_message, recent_messages)
            and not any(
                tool in self._registry.names
                for tool in (
                    "get_my_return",
                    "prepare_my_return_request",
                    "submit_my_return_request",
                )
            )
        )
        if return_tools_disabled:
            if text_delta is not None:
                await text_delta(_RETURN_REQUEST_DISABLED_RESPONSE)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=_RETURN_REQUEST_DISABLED_RESPONSE
                ),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )
        if (
            _checkout_read_request(current_message)
            and not _referenced_checkout_ids(prior_observations)
        ):
            if text_delta is not None:
                await text_delta(_NO_REFERENCED_CHECKOUT_RESPONSE)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=_NO_REFERENCED_CHECKOUT_RESPONSE
                ),
                decisionCount=0,
                pendingInteraction=pending_interaction,
                activeWorkflow=active_workflow,
                scopeResult=selected_scope,
            )

        new_pending: MarketplaceAgentV2PendingInteraction | None = None
        new_active_workflow = active_workflow
        if confirmed_interaction is not None:
            if confirmed_interaction.action == "SUBMIT_RETURN_REQUEST":
                if confirmed_interaction.status != "CONSUMED":
                    content = _return_confirmation_terminal_content(
                        confirmed_interaction.status, prior_observations
                    )
                    if text_delta is not None:
                        await text_delta(content)
                    return OrchestrationResult(
                        message=MarketplaceAgentV2Message(
                            content=content,
                            pendingInteraction=confirmed_interaction,
                        ),
                        decisionCount=0,
                        observations=tuple(prior_observations[-5:]),
                        pendingInteraction=confirmed_interaction,
                        activeWorkflow=active_workflow,
                        scopeResult=selected_scope,
                    )
                observation = await self._registry.execute_confirmed_return_request(
                    arguments=confirmed_interaction.arguments,
                    actor_user_id=actor_user_id,
                    actor_authorization=actor_authorization,
                    correlation_id=correlation_id,
                    action_reference=confirmed_interaction.id,
                    activity=activity,
                )
                if tool_completed is not None:
                    await tool_completed(observation)
                content = _step_limit_content(
                    (observation,), (),
                    required_grounding=selected_scope.required_grounding,
                )
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        pendingInteraction=confirmed_interaction,
                        toolActivity=(ToolActivity(
                            tool="submit_my_return_request",
                            status=observation.status,
                            reason=observation.reason,
                            observedAt=observation.observed_at,
                        ),),
                    ),
                    decisionCount=0,
                    observations=(observation,),
                    pendingInteraction=confirmed_interaction,
                    activeWorkflow=active_workflow,
                    scopeResult=selected_scope,
                )
            if confirmed_interaction.action == "CANCEL_ORDER":
                if confirmed_interaction.status != "CONSUMED":
                    content = _order_cancellation_confirmation_terminal_content(
                        confirmed_interaction.status, prior_observations
                    )
                    if text_delta is not None:
                        await text_delta(content)
                    return OrchestrationResult(
                        message=MarketplaceAgentV2Message(
                            content=content,
                            pendingInteraction=confirmed_interaction,
                        ),
                        decisionCount=0,
                        observations=tuple(prior_observations[-5:]),
                        pendingInteraction=confirmed_interaction,
                        activeWorkflow=active_workflow,
                        scopeResult=selected_scope,
                    )
                observation = await self._registry.execute_confirmed_order_cancellation(
                    arguments=confirmed_interaction.arguments,
                    actor_user_id=actor_user_id,
                    actor_authorization=actor_authorization,
                    correlation_id=correlation_id,
                    action_reference=confirmed_interaction.id,
                    activity=activity,
                )
                if tool_completed is not None:
                    await tool_completed(observation)
                content = _step_limit_content(
                    (observation,), (),
                    required_grounding=selected_scope.required_grounding,
                )
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        pendingInteraction=confirmed_interaction,
                        toolActivity=(ToolActivity(
                            tool="cancel_my_order",
                            status=observation.status,
                            reason=observation.reason,
                            observedAt=observation.observed_at,
                        ),),
                    ),
                    decisionCount=0,
                    observations=(observation,),
                    pendingInteraction=confirmed_interaction,
                    activeWorkflow=active_workflow,
                    scopeResult=selected_scope,
                )
            if confirmed_interaction.action == "SUBMIT_CHECKOUT":
                if confirmed_interaction.status != "CONSUMED":
                    content = _checkout_confirmation_terminal_content(
                        confirmed_interaction.status, prior_observations
                    )
                    if text_delta is not None:
                        await text_delta(content)
                    return OrchestrationResult(
                        message=MarketplaceAgentV2Message(
                            content=content,
                            pendingInteraction=confirmed_interaction,
                        ),
                        decisionCount=0,
                        observations=tuple(prior_observations[-5:]),
                        pendingInteraction=confirmed_interaction,
                        activeWorkflow=active_workflow,
                        scopeResult=selected_scope,
                    )
                try:
                    exact = SubmitMyCheckoutArguments.model_validate({
                        "checkoutId": confirmed_interaction.arguments.get(
                            "checkoutId"
                        )
                    })
                except Exception:
                    observation = ToolObservation(
                        tool="submit_my_checkout", status="REJECTED",
                        reason="INVALID_ARGUMENTS",
                    )
                else:
                    observation = await self._registry.execute(
                        tool="submit_my_checkout",
                        arguments=exact,
                        actor_user_id=actor_user_id,
                        correlation_id=correlation_id,
                        activity=activity,
                        actor_authorization=actor_authorization,
                        action_reference=confirmed_interaction.id,
                    )
                if tool_completed is not None:
                    await tool_completed(observation)
                content = _step_limit_content(
                    (observation,), (),
                    required_grounding=selected_scope.required_grounding,
                )
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        pendingInteraction=confirmed_interaction,
                        toolActivity=(ToolActivity(
                            tool="submit_my_checkout",
                            status=observation.status,
                            reason=observation.reason,
                            observedAt=observation.observed_at,
                        ),),
                    ),
                    decisionCount=0,
                    observations=(observation,),
                    pendingInteraction=confirmed_interaction,
                    activeWorkflow=active_workflow,
                    scopeResult=selected_scope,
                )
            confirmed = ToolProposal(
                callId=confirmed_interaction.id,
                tool="search_listings",
                arguments=confirmed_interaction.arguments,
            )
            confirmed_arguments, rejection = policy.validate(confirmed, step=1)
            if rejection is not None:
                observations.append(rejection)
                turn_observations.append(rejection)
            else:
                observation = await self._registry.execute(
                    tool="search_listings", arguments=confirmed_arguments,
                    actor_user_id=actor_user_id, correlation_id=correlation_id,
                    activity=activity,
                    actor_authorization=actor_authorization,
                    action_reference=invocation_id,
                )
                observations.append(observation)
                turn_observations.append(observation)
                policy.record(observation)
                if tool_completed is not None:
                    await tool_completed(observation)
                activities.append(ToolActivity(
                    tool="search_listings", status=observation.status,
                    reason=observation.reason, observedAt=observation.observed_at,
                ))
                for attachment in observation.attachments:
                    attachments[attachment.listing_id] = attachment

            # Consuming the durable confirmation is the final decision for this
            # action. Synthesize directly from its one authoritative observation;
            # a provider call cannot reopen, replay, or disguise the outcome.
            content = _step_limit_content(
                turn_observations,
                tuple(attachments.values()),
                required_grounding=selected_scope.required_grounding,
            )
            if text_delta is not None:
                await text_delta(content)
            response_attachments = tuple(attachments.values())
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=_safe_content(content),
                    attachments=response_attachments,
                    refinement=_refinement(turn_observations),
                    pendingInteraction=confirmed_interaction,
                    toolActivity=tuple(activities),
                ),
                decisionCount=0,
                observations=tuple(turn_observations[-5:]),
                pendingInteraction=confirmed_interaction,
                activeWorkflow=new_active_workflow,
                evidence=_evidence_references(
                    referenced_listings=tuple(referenced_listings),
                    current_attachments=response_attachments,
                    observations=tuple(turn_observations),
                ),
                scopeResult=selected_scope,
            )

        for step in range(1, MAX_AGENT_STEPS + 1):
            current_search_executed = any(
                item.tool == "search_listings"
                and item.status in {"SUCCEEDED", "FAILED"}
                for item in turn_observations
            )
            current_search_results = any(
                item.tool == "search_listings" and item.attachments
                for item in turn_observations
            )
            current_cart_mutation = any(
                item.tool in _CART_MUTATION_TOOLS for item in turn_observations
            )
            current_private_commerce = (
                selected_scope.required_grounding == "PRIVATE_TOOL"
                or any(
                    item.tool in _PRIVATE_COMMERCE_TOOLS
                    for item in turn_observations
                )
            )
            terminal_tool_requirement = _terminal_tool_requirement(
                current_message=current_message,
                referenced_listings=referenced_listings,
                prior_observations=prior_observations,
                turn_observations=turn_observations,
                ambiguous_cart_reference=ambiguous_cart_reference,
                seller_collection_required=(
                    selected_scope.reason_code == "SELLER_LISTING_WORKFLOW"
                    and active_workflow is None
                    and "collect_listing_information" in self._registry.names
                ),
            )
            grounding_available = _has_required_grounding(
                selected_scope.required_grounding,
                referenced_listings=referenced_listings,
                current_attachments=tuple(attachments.values()),
                observations=tuple(turn_observations),
            )
            stream_provider_text = bool(
                text_delta is not None
                and not comparison_context
                and not malformed_input
                and grounding_available
                and not current_search_executed
                and not search_dependent_turn
                and (contextual_refinement is None or current_search_executed)
                and search_refinement is None
                and not current_private_commerce
                and new_pending is None
                and terminal_tool_requirement is None
                and not _successful_completion_read(turn_observations)
            )
            safe_stream = _CustomerTextStream(
                (
                    text_delta if stream_provider_text else None
                ),
                forbidden_ids=tuple(
                    {item.listing_id for item in referenced_listings}
                    | set(attachments)
                ),
                private_ids=_private_reference_ids(
                    current_message, observations
                ),
                remove_display_confirmation=current_search_results,
                suppress_result_questions=current_search_results,
            )
            decision_schemas = (
                _return_mutation_tool_schemas(
                    schemas,
                    current_message=current_message,
                    required_reason=policy.required_return_reason,
                    observations=tuple(observations),
                    eligible_this_turn=policy.return_eligible_this_turn,
                    blocked_this_turn=policy.return_blocked_this_turn,
                )
                if policy.return_request_mutation_requested
                else schemas
            )
            if active_skill is not None:
                decision_schemas = tuple(
                    schema for schema in decision_schemas
                    if schema["name"] in active_skill.allowed_tools
                )
            elif self._skill_registry is not None and available_skills:
                decision_schemas = (
                    *decision_schemas,
                    self._skill_registry.selection_schema(available_skills),
                )
            if _selected_order_follow_up(current_message.casefold()) and not informational_return:
                # The selected owned order needs a fresh order read; a prior
                # assistant offer about returns is not return-read authority.
                decision_schemas = tuple(
                    schema for schema in decision_schemas
                    if schema["name"] not in _RETURN_REQUEST_TOOLS
                )
            exposed_schemas = () if current_search_executed else decision_schemas
            if terminal_repair_used and not current_search_executed:
                # Recovery may search or clarify, but no other read or write
                # capability can escape this one bounded terminal correction.
                exposed_schemas = tuple(
                    schema for schema in exposed_schemas
                    if schema["name"] == "search_listings"
                )
            if (
                search_refinement is not None
                and not current_search_executed
                and any(item.reason == "SEARCH_REFINEMENT_MISMATCH"
                        for item in turn_observations)
            ):
                # A rejected clear read-only refinement has one safe recovery
                # tool; the model still supplies every search argument.
                exposed_schemas = tuple(
                    schema for schema in exposed_schemas
                    if schema["name"] == "search_listings"
                )
            packet = self._context_builder.for_decision(
                turn=turn_context,
                scope_result=selected_scope,
                pending_interaction=(
                    new_pending or pending_interaction or confirmed_interaction
                ),
                active_workflow=new_active_workflow,
                observations=observations,
                contextual_refinement=contextual_refinement,
                available_skills=tuple(
                    SkillSummary.model_validate(skill.compact())
                    for skill in available_skills
                ) if active_skill is None else (),
                active_skill=(
                    ActiveSkill.model_validate(active_skill.model_context())
                    if active_skill is not None else None
                ),
                suppress_prior_listings=current_search_results,
                commerce_listing_ids=_commerce_listing_ids(prior_observations),
                exposed_tool_names=tuple(schema["name"] for schema in exposed_schemas),
            )
            try:
                decision = await self._model.decide(
                    context=packet.to_agent_context(),
                    # A Product search is the only inventory action for this turn.
                    # The following bounded decision can explain its observation,
                    # but cannot start a second differently-shaped search loop.
                    tools=exposed_schemas,
                    correlation_id=correlation_id,
                    on_text_delta=safe_stream.push if text_delta is not None else None,
                    timeout_seconds=self._model_timeout_seconds,
                )
            except MarketplaceAgentV2OrchestrationFailure:
                raise
            except MarketplaceAgentV2ProviderFailure as error:
                if (
                    error.kind == "MODEL_RESPONSE_UNSUPPORTED"
                    and (
                        current_search_executed
                        or _successful_completion_read(turn_observations)
                    )
                    and not stream_provider_text
                ):
                    # Preserve an authoritative successful read when the model's
                    # follow-up synthesis is unusable; never discard verified cards.
                    decision = ModelDecision(content=_step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    ))
                elif error.kind == "MODEL_RESPONSE_UNSUPPORTED":
                    if _generic_return_policy_request(current_message):
                        decision = ModelDecision(
                            content=_GENERIC_RETURN_POLICY_RESPONSE
                        )
                    else:
                        no_tool_fallback = _natural_no_tool_fallback(
                            current_message,
                            scope_result=selected_scope,
                            registered_tools=self._registry.names,
                        )
                        if no_tool_fallback is not None:
                            decision = ModelDecision(content=no_tool_fallback)
                        else:
                            decision = _results_first_failure_decision(
                                current_message=current_message,
                                scope_result=selected_scope,
                                step=step,
                                search_executed=current_search_executed,
                                contextual_refinement=contextual_refinement,
                                comparison_context=comparison_context,
                                active_workflow=active_workflow,
                            )
                            if decision is None:
                                raise MarketplaceAgentV2OrchestrationFailure(
                                    error.kind
                                ) from error
                else:
                    raise MarketplaceAgentV2OrchestrationFailure(error.kind) from error
            except ValueError as error:
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_DECISION_INVALID"
                ) from error
            except Exception as error:
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_PROVIDER_UNAVAILABLE"
                ) from error
            input_tokens += decision.input_tokens
            output_tokens += decision.output_tokens
            if decision.skill_selection is not None:
                if self._skill_registry is None or active_skill is not None:
                    _LOGGER.warning(
                        "skill_validation_failed",
                        extra={"correlation_id": correlation_id, "error_category": "INVALID_STATE"},
                    )
                    raise MarketplaceAgentV2OrchestrationFailure("UNKNOWN_SKILL")
                try:
                    selected_skill = self._skill_registry.get(
                        decision.skill_selection.name
                    )
                except SkillValidationError as error:
                    _LOGGER.warning(
                        "skill_validation_failed",
                        extra={
                            "correlation_id": correlation_id,
                            "skill_name": decision.skill_selection.name,
                            "error_category": "UNKNOWN_SKILL",
                        },
                    )
                    raise MarketplaceAgentV2OrchestrationFailure(
                        "UNKNOWN_SKILL"
                    ) from error
                if selected_skill not in available_skills:
                    _LOGGER.warning(
                        "skill_validation_failed",
                        extra={
                            "correlation_id": correlation_id,
                            "skill_name": selected_skill.name,
                            "error_category": "DISABLED_CAPABILITY",
                        },
                    )
                    raise MarketplaceAgentV2OrchestrationFailure("UNKNOWN_SKILL")
                active_skill = selected_skill
                policy.skill_allowed_tools = frozenset(active_skill.allowed_tools)
                _LOGGER.info(
                    "skill_selected",
                    extra={
                        "correlation_id": correlation_id,
                        "skill_name": active_skill.name,
                        "outcome": "SELECTED",
                    },
                )
                _LOGGER.info(
                    "skill_loaded",
                    extra={
                        "correlation_id": correlation_id,
                        "skill_name": active_skill.name,
                        "outcome": "LOADED",
                    },
                )
                continue
            return_progress_reason = _return_terminal_progress_reason(
                current_message=current_message,
                required_reason=policy.required_return_reason,
                observations=tuple(observations),
                eligible_this_turn=policy.return_eligible_this_turn,
                blocked_this_turn=policy.return_blocked_this_turn,
            ) if policy.return_request_mutation_requested else None
            if decision.content is not None and return_progress_reason is not None:
                # Natural prose remains valid for clarifications and authoritative
                # blockers. Once the exact actor-owned workflow is grounded, a
                # typed observation keeps the model moving without selecting a
                # replacement tool on its behalf.
                rejection = ToolObservation(
                    tool="DIRECT_RESPONSE",
                    status="REJECTED",
                    reason=return_progress_reason,
                )
                observations.append(rejection)
                turn_observations.append(rejection)
                continue
            if decision.content is not None and terminal_tool_requirement is not None:
                # Natural prose remains a valid terminal decision. It is rejected
                # only when this exact turn still lacks the authoritative read or
                # reversible mutation required by already-grounded context.
                rejection = ToolObservation(
                    tool="DIRECT_RESPONSE",
                    status="REJECTED",
                    reason=terminal_tool_requirement,
                )
                observations.append(rejection)
                turn_observations.append(rejection)
                continue
            if decision.content is not None:
                if contextual_refinement is not None and not current_search_executed:
                    # A Product-grounded facet selection advances the active search;
                    # prose alone would lose the user's committed refinement.
                    rejection = ToolObservation(
                        tool="DIRECT_RESPONSE",
                        status="REJECTED",
                        reason="GROUNDING_TOOL_REQUIRED",
                        normalizedQuery=contextual_refinement.search_query,
                        filterCategories=(contextual_refinement.facet,),
                    )
                    observations.append(rejection)
                    turn_observations.append(rejection)
                    continue
                if search_refinement is not None and not current_search_executed:
                    # The model had the first planning choice. A clear edit to a
                    # fresh executed search cannot complete as permission prose.
                    rejection = ToolObservation(
                        tool="DIRECT_RESPONSE", status="REJECTED",
                        reason="SEARCH_REFINEMENT_TOOL_REQUIRED",
                    )
                    observations.append(rejection)
                    turn_observations.append(rejection)
                    _LOGGER.info(
                        "marketplace_refinement_rejected reason=%s repairMismatch=%s",
                        rejection.reason,
                        (
                            rejection.refinement_repair.mismatch
                            if hasattr(rejection.refinement_repair, "mismatch")
                            else None
                        ),
                        extra={
                            "correlation_id": correlation_id,
                            "reason": rejection.reason,
                        },
                    )
                    continue
                if text_delta is not None:
                    if not safe_stream.raw_text:
                        await safe_stream.push(decision.content)
                    elif safe_stream.raw_text != decision.content:
                        raise MarketplaceAgentV2OrchestrationFailure(
                            "MODEL_DECISION_INVALID"
                        )
                    await safe_stream.finish()
                    content = safe_stream.text
                else:
                    content = _customer_safe_text(
                        decision.content,
                        tuple(
                            {item.listing_id for item in referenced_listings}
                            | set(attachments)
                        ),
                        private_ids=_private_reference_ids(
                            current_message, observations
                        ),
                        remove_display_confirmation=current_search_results,
                        suppress_result_questions=current_search_results,
                    )
                deferred_action_response = _deferred_commerce_action_response(
                    current_message
                )
                if deferred_action_response is not None and not checkout_enabled:
                    # The model still interprets the ordinary request, but the
                    # application owns this release-boundary response.
                    content = deferred_action_response
                if cart_mutation_disabled:
                    # The capability registry is authoritative. Model prose may
                    # not promise or request confirmation for a disabled write.
                    content = _CART_MUTATION_DISABLED_RESPONSE
                elif _generic_return_policy_request(current_message):
                    # This text is sourced from the approved Order return contract,
                    # not provider memory. Customer-specific eligibility still uses
                    # the actor-scoped return tools.
                    content = _GENERIC_RETURN_POLICY_RESPONSE
                elif current_cart_mutation:
                    # The application, not provider prose, owns the outcome of a
                    # cart write so rejected individual listings cannot be
                    # described as purchasable through an alternate flow.
                    content = _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                elif any(
                    item.tool == "prepare_my_checkout"
                    for item in turn_observations
                ):
                    # Preparation failures are authoritative terminal outcomes.
                    # Provider prose must not invent a second confirmation after
                    # an empty, stale, or otherwise ineligible cart was rejected.
                    content = _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                elif any(
                    item.tool == "prepare_my_return_request"
                    for item in turn_observations
                ):
                    # Order's preparation result is authoritative. A failed or
                    # blocked preparation must not be replaced with a promise to
                    # retry, a second confirmation, or eligibility-only prose.
                    content = _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                if ambiguous_cart_reference:
                    content = _ambiguous_cart_clarification(current_message)
                if new_pending is not None and new_pending.status == "WAITING":
                    content = (
                        f"{new_pending.summary} Confirm?"
                        if new_pending.summary is not None
                        else content
                    )
                if current_search_executed and not content.strip():
                    content = _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                selected_order_answer = _grounded_selected_order_follow_up(
                    current_message, turn_observations
                )
                if selected_order_answer is not None:
                    content = selected_order_answer
                try:
                    _validate_grounding(
                        required_grounding=selected_scope.required_grounding,
                        allow_listing_clarification=(
                            selected_scope.reason_code != "MARKETPLACE_DISCOVERY"
                        ),
                        current_message=current_message,
                        content=content,
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                        allow_focused_clarification=ambiguous_cart_reference,
                    )
                    _validate_terminal_response(
                        current_message=current_message,
                        content=content,
                        active_recommendations=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                        has_waiting_interaction=any(
                            item is not None and item.status == "WAITING"
                            for item in (new_pending, pending_interaction)
                        ),
                        checkout_enabled=checkout_enabled,
                        return_read_required=informational_return,
                        query_only_refinement=(
                            search_refinement is not None
                            and search_refinement.kind in {
                                "RAM", "SIZE", "WIRELESS", "NO_RGB"
                            }
                        ),
                        search_dependent=search_dependent_turn,
                        explicit_search_confirmation=policy.explicit_search_confirmation,
                    )
                except MarketplaceAgentV2OrchestrationFailure as error:
                    repair_reason = error.repair_reason or (
                        "SEARCH_TERMINAL_GROUNDING_REQUIRED"
                        if error.kind == "GROUNDING_REQUIRED" else None
                    )
                    if (
                        search_dependent_turn
                        and not recommendation_context
                        and not current_search_executed
                        and not current_private_commerce
                        and new_pending is None
                        and repair_reason is not None
                    ):
                        if terminal_repair_used or step >= MAX_AGENT_STEPS:
                            raise
                        # Reject only the valid model text, never its bytes. One
                        # in-loop observation can elicit a model-authored read or
                        # focused clarification within the existing step budget.
                        rejection = ToolObservation(
                            tool="DIRECT_RESPONSE", status="REJECTED",
                            reason=repair_reason,
                        )
                        observations.append(rejection)
                        turn_observations.append(rejection)
                        terminal_repair_used = True
                        _LOGGER.info(
                            "marketplace_terminal_repair_requested",
                            extra={"correlation_id": correlation_id,
                                   "reason": repair_reason},
                        )
                        continue
                    if error.kind == "GROUNDING_REQUIRED":
                        rejection = ToolObservation(
                            tool="DIRECT_RESPONSE",
                            status="REJECTED",
                            reason=(
                                "RETURN_STATUS_TOOL_REQUIRED"
                                if informational_return
                                else "GROUNDING_REQUIRED"
                            ),
                        )
                        observations.append(rejection)
                        turn_observations.append(rejection)
                        continue
                    if error.kind != "MODEL_RESPONSE_UNSUPPORTED":
                        raise
                    if current_search_executed or _successful_completion_read(
                        turn_observations
                    ):
                        # Post-search model text is buffered until validation so
                        # unusable synthesis can never expose codes or discard facts.
                        content = _step_limit_content(
                            turn_observations,
                            tuple(attachments.values()),
                            required_grounding=selected_scope.required_grounding,
                        )
                        content = _grounded_selected_order_follow_up(
                            current_message, turn_observations
                        ) or content
                    elif current_private_commerce:
                        rejection = ToolObservation(
                            tool="DIRECT_RESPONSE",
                            status="REJECTED",
                            reason=(
                                "RETURN_STATUS_TOOL_REQUIRED"
                                if informational_return
                                else "GROUNDING_REQUIRED"
                            ),
                        )
                        observations.append(rejection)
                        turn_observations.append(rejection)
                        continue
                    elif recommendation_context:
                        content = _grounded_recommendation_content(
                            current_message, referenced_listings
                        )
                    elif malformed_input:
                        content = (
                            "I didn't understand that. "
                            "What marketplace item or question can I help with?"
                        )
                    elif (
                        fallback := _natural_no_tool_fallback(
                            current_message,
                            scope_result=selected_scope,
                            registered_tools=self._registry.names,
                        )
                    ) is not None:
                        content = fallback
                    else:
                        raise
                    _validate_terminal_response(
                        current_message=current_message,
                        content=content,
                        active_recommendations=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                        has_waiting_interaction=any(
                            item is not None and item.status == "WAITING"
                            for item in (new_pending, pending_interaction)
                        ),
                        return_read_required=informational_return,
                        query_only_refinement=(
                            search_refinement is not None
                            and search_refinement.kind in {
                                "RAM", "SIZE", "WIRELESS", "NO_RGB"
                            }
                        ),
                        search_dependent=search_dependent_turn,
                        explicit_search_confirmation=policy.explicit_search_confirmation,
                    )
                if text_delta is not None and not stream_provider_text:
                    await text_delta(content)
                response_attachments = (
                    tuple(attachments.values()) or contextual_attachments
                )
                evidence = _evidence_references(
                    referenced_listings=tuple(referenced_listings),
                    current_attachments=response_attachments,
                    observations=tuple(turn_observations),
                )
                effective_pending = _effective_workflow_pending(
                    new_pending, pending_interaction, new_active_workflow
                )
                if active_skill is not None:
                    _LOGGER.info(
                        "skill_completed",
                        extra={
                            "correlation_id": correlation_id,
                            "skill_name": active_skill.name,
                            "outcome": "COMPLETED",
                        },
                    )
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=_safe_content(content),
                        attachments=response_attachments,
                        refinement=_refinement(turn_observations),
                        pendingInteraction=effective_pending,
                        citations=_knowledge_citations(turn_observations),
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=effective_pending,
                    activeWorkflow=new_active_workflow,
                    evidence=evidence,
                    scopeResult=selected_scope,
                )
            proposal = decision.tool_proposal
            assert proposal is not None
            if proposal.tool in {"get_my_return", "prepare_my_return_request"}:
                grounded_order = next((
                    item for item in reversed(observations)
                    if item.tool == "get_my_order"
                    and item.status == "SUCCEEDED"
                    and item.order_item_references
                    and any(
                        reference.order_id == proposal.arguments.get("orderId")
                        for reference in item.order_item_references
                    )
                ), None)
                if grounded_order is not None:
                    grounded_target = _grounded_return_read_arguments(
                        current_message, proposal.arguments, grounded_order
                    )
                    proposal = proposal.model_copy(update={
                        "arguments": {
                            **grounded_target,
                            **({
                                key: value for key, value in proposal.arguments.items()
                                if key in {"reasonCode", "comment"}
                            } if proposal.tool == "prepare_my_return_request" else {}),
                        }
                    })
            arguments, rejection = policy.validate(proposal, step=step)
            if (
                rejection is not None
                and rejection.reason == "MUTATION_INTENT_REQUIRED"
                and proposal.tool == "preview_my_order_cancellation"
                and informational_order_cancellation
            ):
                # The model resolved an actor-owned target but over-selected the
                # mutating preview. Downgrade only this guarded case to the exact
                # owned detail read so eligibility can be answered without storing
                # authority or asking the customer to repeat the question.
                proposal = proposal.model_copy(update={"tool": "get_my_order"})
                arguments, rejection = policy.validate(proposal, step=step)
            if (
                rejection is not None
                and rejection.reason == "MUTATION_INTENT_REQUIRED"
                and proposal.tool == "prepare_my_return_request"
                and informational_return
            ):
                safe_arguments = {
                    key: value for key, value in proposal.arguments.items()
                    if key in {"orderId", "listingId", "storeName"}
                }
                proposal = proposal.model_copy(update={
                    "tool": "get_my_return", "arguments": safe_arguments,
                })
                arguments, rejection = policy.validate(proposal, step=step)
            if rejection is not None:
                observations.append(rejection)
                turn_observations.append(rejection)
                if rejection.reason in {
                    "SEARCH_REFINEMENT_MISMATCH",
                    "SEARCH_REFINEMENT_TOOL_REQUIRED",
                    "CONFIRMATION_INTENT_REQUIRED",
                }:
                    _LOGGER.info(
                        "marketplace_refinement_rejected",
                        extra={
                            "correlation_id": correlation_id,
                            "reason": rejection.reason,
                            "repair_mismatch": (
                                rejection.refinement_repair.mismatch
                                if hasattr(rejection.refinement_repair, "mismatch")
                                else None
                            ),
                        },
                    )
                if proposal.tool in self._registry.names and proposal.tool not in {
                    "request_confirmation", "collect_listing_information",
                }:
                    activities.append(ToolActivity(
                        tool=proposal.tool,
                        status=rejection.status,
                        reason=rejection.reason,
                        observedAt=rejection.observed_at,
                    ))
                continue
            if (
                proposal.tool == "collect_listing_information"
                and isinstance(arguments, CollectListingInformationArguments)
                and arguments.item_type is None
            ):
                # The model selected seller collection; preserve an explicit object
                # already present in that request so the workflow cannot ask it again.
                extracted_item_type = extract_initial_item_type(current_message)
                if extracted_item_type is not None:
                    arguments = arguments.model_copy(update={
                        "item_type": extracted_item_type
                    })
            observation = await self._registry.execute(
                tool=proposal.tool,
                arguments=arguments,
                actor_user_id=actor_user_id,
                correlation_id=correlation_id,
                activity=activity,
                actor_authorization=actor_authorization,
                action_reference=invocation_id,
            )
            if (
                observation.pending_interaction is not None
                and observation.pending_interaction.type == "CONFIRM_ACTION"
                and confirmation_prepared is not None
            ):
                durable_pending = await confirmation_prepared(
                    observation.pending_interaction
                )
                observation = observation.model_copy(update={
                    "pending_interaction": durable_pending
                })
            observations.append(observation)
            turn_observations.append(observation)
            policy.record(observation)
            if tool_completed is not None and proposal.tool not in {
                "request_confirmation", "collect_listing_information",
            }:
                await tool_completed(observation)
            if proposal.tool not in {
                "request_confirmation", "collect_listing_information",
            }:
                activities.append(ToolActivity(
                    tool=proposal.tool,
                    status=observation.status,
                    reason=observation.reason,
                    observedAt=observation.observed_at,
                ))
            for attachment in observation.attachments:
                attachments[attachment.listing_id] = attachment
            if observation.pending_interaction is not None:
                new_pending = observation.pending_interaction
            if observation.active_workflow is not None:
                new_active_workflow = observation.active_workflow
            if (
                proposal.tool == "get_my_order"
                and informational_return
                and observation.status == "SUCCEEDED"
            ):
                # Order detail is the authority for mapping a named purchased item
                # or store group. Complete the read from that actor-owned evidence
                # instead of allowing model prose to substitute for return status.
                return_proposal = ToolProposal(
                    callId=f"{proposal.call_id[:180]}-return-status",
                    tool="get_my_return",
                    arguments=_grounded_return_read_arguments(
                        current_message, proposal.arguments, observation
                    ),
                )
                return_arguments, return_rejection = policy.validate(
                    return_proposal, step=step
                )
                if return_rejection is not None:
                    return_observation = return_rejection
                else:
                    return_observation = await self._registry.execute(
                        tool="get_my_return",
                        arguments=return_arguments,
                        actor_user_id=actor_user_id,
                        correlation_id=correlation_id,
                        activity=activity,
                        actor_authorization=actor_authorization,
                        action_reference=invocation_id,
                    )
                    policy.record(return_observation)
                observations.append(return_observation)
                turn_observations.append(return_observation)
                if tool_completed is not None:
                    await tool_completed(return_observation)
                activities.append(ToolActivity(
                    tool="get_my_return",
                    status=return_observation.status,
                    reason=return_observation.reason,
                    observedAt=return_observation.observed_at,
                ))
                content = _step_limit_content(
                    (return_observation,), (),
                    required_grounding=selected_scope.required_grounding,
                )
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=pending_interaction,
                    activeWorkflow=new_active_workflow,
                    evidence=_evidence_references(
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                    ),
                    scopeResult=selected_scope,
                )
            if proposal.tool == "get_my_return" and informational_return:
                content = _step_limit_content(
                    (observation,), (),
                    required_grounding=selected_scope.required_grounding,
                )
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=pending_interaction,
                    activeWorkflow=new_active_workflow,
                    evidence=_evidence_references(
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                    ),
                    scopeResult=selected_scope,
                )
            if (
                proposal.tool == "get_my_order"
                and informational_order_cancellation
                and observation.status == "SUCCEEDED"
                and observation.order is not None
            ):
                content = _order_cancellation_eligibility_content(observation)
                if text_delta is not None:
                    await text_delta(content)
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=content,
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=pending_interaction,
                    activeWorkflow=new_active_workflow,
                    evidence=_evidence_references(
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                    ),
                    scopeResult=selected_scope,
                )
            if (
                proposal.tool in {
                    "request_confirmation", "prepare_my_checkout",
                    "preview_my_order_cancellation", "prepare_my_return_request",
                }
                and new_pending is not None
                and new_pending.status == "WAITING"
            ):
                # Durable preparation is the terminal decision for this turn.
                # The application-owned summary must not depend on another model
                # call that could fail after authority has already been stored.
                content = (
                    f"{new_pending.summary} Confirm?"
                    if new_pending.summary is not None
                    else _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                )
                if text_delta is not None:
                    await text_delta(content)
                response_attachments = (
                    tuple(attachments.values()) or contextual_attachments
                )
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=_safe_content(content),
                        attachments=response_attachments,
                        refinement=_refinement(turn_observations),
                        pendingInteraction=new_pending,
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=new_pending,
                    activeWorkflow=new_active_workflow,
                    evidence=_evidence_references(
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=response_attachments,
                        observations=tuple(turn_observations),
                    ),
                    scopeResult=selected_scope,
                )

        fallback = _step_limit_content(
            turn_observations,
            tuple(attachments.values()),
            required_grounding=selected_scope.required_grounding,
        )
        fallback = _grounded_selected_order_follow_up(
            current_message, turn_observations
        ) or fallback
        if new_pending is not None and new_pending.status == "WAITING":
            fallback = (
                f"{new_pending.summary} Confirm?"
                if new_pending.summary is not None
                else fallback
            )
        if text_delta is not None:
            await text_delta(fallback)
        if active_skill is not None:
            _LOGGER.info(
                "skill_abandoned",
                extra={
                    "correlation_id": correlation_id,
                    "skill_name": active_skill.name,
                    "outcome": "STEP_BUDGET_EXHAUSTED",
                },
            )
        effective_pending = _effective_workflow_pending(
            new_pending, pending_interaction, new_active_workflow
        )
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(
                content=fallback,
                attachments=tuple(attachments.values()),
                refinement=_refinement(turn_observations),
                pendingInteraction=effective_pending,
                citations=_knowledge_citations(turn_observations),
                toolActivity=tuple(activities),
                inputTokens=input_tokens,
                outputTokens=output_tokens,
            ),
            decisionCount=MAX_AGENT_STEPS,
            observations=tuple(turn_observations[-5:]),
            pendingInteraction=effective_pending,
            activeWorkflow=new_active_workflow,
            evidence=_evidence_references(
                referenced_listings=tuple(referenced_listings),
                current_attachments=tuple(attachments.values()),
                observations=tuple(turn_observations),
            ),
            scopeResult=selected_scope,
        )


def _results_first_failure_decision(
    *,
    current_message: str,
    scope_result: MarketplaceScopeResult,
    step: int,
    search_executed: bool,
    contextual_refinement: MarketplaceAgentV2ContextualRefinement | None,
    comparison_context: bool,
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None,
) -> ModelDecision | None:
    """Recovers a failed model decision only for a simple explicit discovery."""

    if (
        scope_result.reason_code != "MARKETPLACE_DISCOVERY"
        or scope_result.confidence != "HIGH"
        or search_executed
        or contextual_refinement is not None
        or comparison_context
        or active_workflow is not None
    ):
        return None
    query = _simple_explicit_discovery_query(current_message)
    if query is None:
        return None
    return ModelDecision(toolProposal=ToolProposal(
        callId=f"results-first-recovery-{step}",
        tool="search_listings",
        arguments={"query": query, "limit": 5},
    ))


def _simple_explicit_discovery_query(value: str) -> str | None:
    """Extracts only an unfiltered product phrase from an explicit search request."""

    normalized = " ".join(value.strip().split())
    prefixes = (
        r"(?:please\s+)?search\s+(?:the\s+)?marketplace\s+for\s+",
        r"(?:please\s+)?search\s+for\s+",
        r"(?:please\s+)?search\s+",
        r"(?:please\s+)?find\s+me\s+",
        r"(?:please\s+)?find\s+",
        r"(?:please\s+)?show\s+me\s+",
        r"(?:please\s+)?browse\s+(?:for\s+)?",
        r"i\s+need\s+",
        r"i\s+want\s+",
        r"looking\s+for\s+",
        r"buy\s+",
    )
    query = None
    for prefix in prefixes:
        match = re.match(rf"^{prefix}", normalized, flags=re.IGNORECASE)
        if match is not None:
            query = normalized[match.end():]
            break
    if query is None:
        return None
    query = re.sub(
        r"\s+(?:in|on|from)\s+(?:the\s+)?marketplace[.!?]*$",
        "",
        query,
        flags=re.IGNORECASE,
    ).strip(" \t\r\n.,!?\"'")
    if (
        not query
        or len(query) > 120
        or re.search(
            r"(?:\$|\d|\b(?:under|below|over|above|between|max(?:imum)?|"
            r"min(?:imum)?|near|within|condition|new|used|like[- ]new)\b)",
            query,
            flags=re.IGNORECASE,
        )
        or not re.fullmatch(r"[\w&'’\-]+(?:\s+[\w&'’\-]+){0,7}", query)
    ):
        return None
    words = query.split()
    leading_fillers = {"a", "an", "the", "some", "current", "available"}
    trailing_fillers = {
        "item", "items", "listing", "listings", "product", "products",
        "option", "options", "match", "matches",
    }
    while len(words) > 1 and words[0].casefold() in leading_fillers:
        words.pop(0)
    while len(words) > 1 and words[-1].casefold() in trailing_fillers:
        words.pop()
    while len(words) > 1 and words[-1].casefold() in {"business", "individual"}:
        words.pop()
    return " ".join(words) or None


def _explicit_seller_comparison_request(value: str) -> bool:
    """Allows inventory only when a seller explicitly asks for market comparison."""

    normalized = " ".join(value.casefold().split())
    return bool(re.search(
        r"\b(?:show|find|search|compare|check)\b.{0,40}\b(?:similar|comparable)\b|"
        r"\b(?:what price|how much)\b.{0,40}\b(?:similar|listings?|market)\b|"
        r"\bwhat price should i (?:use|set|ask)\b|"
        r"\bhow much should i (?:list|sell|ask)\b",
        normalized,
    ))


def _effective_workflow_pending(
    new_pending: MarketplaceAgentV2PendingInteraction | None,
    current_pending: MarketplaceAgentV2PendingInteraction | None,
    workflow: MarketplaceAgentV2ActiveWorkflow | None,
) -> MarketplaceAgentV2PendingInteraction | None:
    """Keeps a seller question active across an explicit comparison side trip."""

    if new_pending is not None:
        return new_pending
    if (
        workflow is not None
        and workflow.type == "CREATE_LISTING"
        and workflow.status == "COLLECTING_INFORMATION"
        and current_pending is not None
        and current_pending.type == "ANSWER_FIELD"
        and current_pending.status == "WAITING"
    ):
        return current_pending
    return None


def _safe_content(value: str) -> str:
    # Validate without normalizing: persisted terminal content must exactly match
    # the bytes already emitted by the provider-backed SSE stream.
    if not value.strip() or len(value) > 12_000:
        raise ValueError("Invalid Marketplace Agent V2 assistant content")
    lowered = value.casefold()
    if "chain-of-thought" in lowered or "system prompt" in lowered:
        raise ValueError("Unsafe Marketplace Agent V2 assistant content")
    return value


def _looks_like_unintelligible_input(value: str) -> bool:
    """Recognize only obvious keyboard-noise input for one safe post-model fallback."""

    normalized = " ".join(value.casefold().split())
    return bool(re.search(r"\b(?:asdf(?:gh)?|qwerty|zxcv(?:bnm)?|hjkl)\b", normalized))


_CUSTOMER_REPLACEMENTS = (
    ("provisional preview", "current matches"),
    ("low-confidence result set", "closest current matches"),
    ("facets from current inventory", "ways to narrow these results"),
    ("retrieval confidence", "match quality"),
    ("seller contact/purchase page", "listing page"),
    ("purchase page", "listing page"),
)

_DISPLAY_CONFIRMATION_REPLACEMENTS = (
    # Cards are already attached to this answer, so a display-permission question
    # is removed rather than creating another customer turn or pending action.
    (" Would you like to view these results?", ""),
    (" Would you like to view the results?", ""),
    (" Would you like me to show these results?", ""),
)

_BLOCKED_STREAM_ACTION_PHRASES = (
    "opening the photo gallery",
    "opening a photo gallery",
    "starting a purchase",
    "starting the purchase",
)


class _CustomerTextStream:
    """Redacts bounded private/internal terms before any model text reaches SSE."""

    def __init__(
        self,
        callback: TextDeltaCallback | None,
        *,
        forbidden_ids: Sequence[str],
        private_ids: Sequence[str] = (),
        remove_display_confirmation: bool = False,
        suppress_result_questions: bool = False,
    ) -> None:
        self._callback = callback
        self._replacements = (
            _CUSTOMER_REPLACEMENTS
            + (_DISPLAY_CONFIRMATION_REPLACEMENTS if remove_display_confirmation else ())
            + tuple(
            (listing_id, "this listing") for listing_id in forbidden_ids
            )
            + tuple(
                (private_id, "this order") for private_id in private_ids
            )
        )
        self._blocked_phrases = _BLOCKED_STREAM_ACTION_PHRASES
        self._suppress_result_questions = suppress_result_questions
        self._pending = ""
        self._sentence_pending = ""
        self._raw: list[str] = []
        self._safe: list[str] = []

    @property
    def raw_text(self) -> str:
        return "".join(self._raw)

    @property
    def text(self) -> str:
        return "".join(self._safe)

    async def push(self, delta: str) -> None:
        self._raw.append(delta)
        self._pending += delta
        await self._drain(final=False)

    async def finish(self) -> None:
        await self._drain(final=True)
        await self._drain_sentences(final=True)

    async def _drain(self, *, final: bool) -> None:
        while self._pending:
            lowered = self._pending.casefold()
            if any(
                phrase.casefold() in lowered for phrase in self._blocked_phrases
            ):
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_RESPONSE_UNSUPPORTED"
                )
            matches = [
                (lowered.find(needle.casefold()), needle, replacement)
                for needle, replacement in self._replacements
                if lowered.find(needle.casefold()) >= 0
            ]
            if matches:
                index, needle, replacement = min(matches, key=lambda item: item[0])
                await self._emit(self._pending[:index])
                await self._emit(replacement)
                self._pending = self._pending[index + len(needle):]
                continue
            if final:
                await self._emit(self._pending)
                self._pending = ""
                return
            retained = max(
                (
                    size
                    for needle in (
                        tuple(item[0] for item in self._replacements)
                        + self._blocked_phrases
                    )
                    for size in range(1, min(len(needle), len(self._pending) + 1))
                    if lowered.endswith(needle.casefold()[:size])
                ),
                default=0,
            )
            safe_length = len(self._pending) - retained
            if safe_length == 0:
                return
            await self._emit(self._pending[:safe_length])
            self._pending = self._pending[safe_length:]

    async def _emit(self, value: str) -> None:
        if not value:
            return
        if self._suppress_result_questions:
            self._sentence_pending += value
            await self._drain_sentences(final=False)
            return
        await self._emit_safe(value)

    async def _drain_sentences(self, *, final: bool) -> None:
        """Streams declarative result prose while retaining questions for removal."""

        while self._sentence_pending:
            boundary = _sentence_boundary(self._sentence_pending, final=final)
            if boundary is None:
                return
            sentence = self._sentence_pending[:boundary]
            self._sentence_pending = self._sentence_pending[boundary:]
            if "?" not in sentence:
                await self._emit_safe(sentence)

    async def _emit_safe(self, value: str) -> None:
        if not value:
            return
        self._safe.append(value)
        if self._callback is not None:
            await self._callback(value)


def _sentence_boundary(value: str, *, final: bool) -> int | None:
    """Find a completed sentence without treating decimal points as boundaries."""

    for index, character in enumerate(value):
        if character not in ".!?":
            continue
        if index + 1 < len(value) and value[index + 1].isspace():
            return index + 1
        if final and index + 1 == len(value):
            return index + 1
    if final:
        return len(value)
    return None


def _customer_safe_text(
    value: str,
    forbidden_ids: Sequence[str],
    *,
    private_ids: Sequence[str] = (),
    remove_display_confirmation: bool = False,
    suppress_result_questions: bool = False,
) -> str:
    safe = value
    replacements = (
        _CUSTOMER_REPLACEMENTS
        + (_DISPLAY_CONFIRMATION_REPLACEMENTS if remove_display_confirmation else ())
        + tuple((listing_id, "this listing") for listing_id in forbidden_ids)
        + tuple((private_id, "this order") for private_id in private_ids)
    )
    for needle, replacement in replacements:
        start = safe.casefold().find(needle.casefold())
        while start >= 0:
            safe = safe[:start] + replacement + safe[start + len(needle):]
            start = safe.casefold().find(needle.casefold(), start + len(replacement))
    if suppress_result_questions:
        safe = _without_question_sentences(safe)
    return safe


def _private_reference_ids(
    current_message: str,
    observations: Sequence[ToolObservation],
) -> tuple[str, ...]:
    """Collect customer-owned opaque identifiers for pre-SSE redaction."""

    result = set(re.findall(
        r"\b[0-9A-HJKMNP-TV-Z]{26}\b", current_message, flags=re.IGNORECASE
    ))
    for observation in observations:
        result.update(item.order_id for item in observation.orders)
        result.update(item.order_id for item in observation.order_references)
        result.update(item.order_id for item in observation.order_item_references)
        if observation.order is not None:
            result.add(observation.order.order_id)
    return tuple(sorted(result, key=lambda item: (-len(item), item)))


def _without_question_sentences(value: str) -> str:
    """Keep result introductions declarative; structured actions render after cards."""

    result: list[str] = []
    pending = value
    while pending:
        boundary = _sentence_boundary(pending, final=True)
        assert boundary is not None
        sentence = pending[:boundary]
        pending = pending[boundary:]
        if "?" not in sentence:
            result.append(sentence)
    return "".join(result).strip()


def _contextual_refinement_selection(
    current_message: str,
    prior_observations: Sequence[ToolObservation],
) -> MarketplaceAgentV2ContextualRefinement | None:
    """Resolve an exact short reply only against the latest Product-owned facets."""

    selected = " ".join(current_message.casefold().strip().rstrip(".!?").split())
    if not selected:
        return None
    for observation in reversed(prior_observations):
        if observation.tool != "search_listings":
            continue
        if (
            observation.status != "SUCCEEDED"
            or not observation.attachments
            or observation.normalized_query is None
        ):
            return None
        choices: list[tuple[str, str]] = []
        if observation.facets is not None:
            choices.extend(
                ("SUBTYPE", option.value)
                for option in observation.facets.subtype
            )
        # Product V4 may omit subtype facets while each revalidated attachment
        # still carries its authoritative public category. An exact short reply
        # may select that category, but no unobserved category is inferred.
        choices.extend(
            ("CATEGORY", attachment.category_name)
            for attachment in observation.attachments
        )
        seen: set[tuple[str, str]] = set()
        for facet, value in choices:
            normalized_option = " ".join(value.casefold().split())
            identity = (facet, normalized_option)
            if identity in seen:
                continue
            seen.add(identity)
            if selected == normalized_option:
                return MarketplaceAgentV2ContextualRefinement(
                    facet=facet,
                    value=value,
                    activeQuery=observation.normalized_query,
                    searchQuery=(
                        observation.normalized_query
                        if facet == "CATEGORY"
                        else f"{observation.normalized_query} {value}"
                    )[:200],
                )
        return None
    return None


def _refinement(
    observations: Sequence[ToolObservation],
) -> MarketplaceAgentV2Refinement | None:
    """Builds prose-free suggested actions from current Product-owned facet facts."""

    observation = next(
        (
            item
            for item in reversed(observations)
            if item.tool == "search_listings"
            and item.status == "SUCCEEDED"
            and item.attachments
            and item.facets is not None
        ),
        None,
    )
    if observation is None or observation.facets is None:
        return None
    options: list[MarketplaceAgentV2RefinementOption] = []
    exact = observation.exact_match_count or 0
    related = observation.related_match_count or 0
    if exact > 0 and related > 0:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="MATCH_SCOPE", value="Exact matches only", count=exact
        ))
    prices = tuple(
        item.price_amount for item in observation.attachments
        if item.currency == "USD"
    )
    for threshold in (Decimal("20"), Decimal("50"), Decimal("100"), Decimal("200")):
        if len(prices) != len(observation.attachments):
            break
        count = sum(price < threshold for price in prices)
        if 0 < count < len(prices):
            options.append(MarketplaceAgentV2RefinementOption(
                facet="MAXIMUM_PRICE",
                value=f"Under ${threshold:.0f}",
                count=count,
            ))
            break
    new_condition = next(
        (item for item in observation.facets.condition if item.value.casefold() == "new"),
        None,
    )
    if new_condition is not None:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="CONDITION", value="New condition", count=new_condition.count
        ))
    location = next(iter(observation.facets.location), None)
    if location is not None:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="LOCATION", value=f"Near {location.value}", count=location.count
        ))
    options = options[:4]
    if len(options) < 2:
        return None
    return MarketplaceAgentV2Refinement(
        options=tuple(options),
    )


_ORDINALS = {
    "first": 1, "second": 2, "third": 3, "fourth": 4,
    "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8,
}


def _unavailable_listing_ordinal(
    value: str, recommendations: Sequence[ListingAttachment]
) -> bool:
    """Reject explicit card ordinals beyond the active displayed result set."""

    normalized = " ".join(value.casefold().split())
    return bool(recommendations) and any(
        ordinal > len(recommendations)
        and re.search(
            rf"\b(?:the\s+)?{word}\s+(?:one|listing|result|item|card)\b",
            normalized,
        )
        for word, ordinal in _ORDINALS.items()
    )


def _confirmation_answer(value: str) -> bool | None:
    normalized = " ".join(
        re.sub(r"[,;:]+", " ", value.strip().casefold().rstrip(".!?")).split()
    )
    if normalized in {
        "yes", "y", "yes please", "sure", "okay", "ok", "confirm",
        "go ahead", "do it",
    }:
        return True
    if normalized in {
        "no", "n", "no thanks", "no thank you", "never mind", "nevermind",
        "don't do it", "do not do it", "cancel that", "cancel the request",
        "no keep the order", "no keep my order", "no keep that order",
        "no please keep the order", "no leave the order unchanged",
        "no leave my order unchanged", "no leave that order alone",
    }:
        return False
    if re.fullmatch(r"no(?:\s+please)?\s+(?:never\s*mind|don't\s+do\s+it)", normalized):
        return False
    return None


def _ambiguous_confirmation_answer(value: str) -> bool:
    """Recognize a narrow non-consent reply while an explicit confirmation waits."""

    normalized = " ".join(value.strip().casefold().rstrip(".!?").split())
    return normalized in {
        "maybe", "maybe later", "not sure", "i'm not sure", "i am not sure",
    }


def _terminal_confirmation_response(status: str) -> str:
    """Explains a durable terminal confirmation without model or tool execution."""

    return {
        "CONSUMED": (
            "That prepared search has already been completed. Tell me what you’d "
            "like to search for next."
        ),
        "CANCELLED": (
            "That prepared search was cancelled. Please prepare it again if you "
            "still want it."
        ),
        "EXPIRED": (
            "That confirmation expired. Please prepare the search again if you "
            "still want it."
        ),
        "INVALIDATED": (
            "That prepared search is no longer valid. Please prepare it again if "
            "you still want it."
        ),
    }[status]


def _checkout_confirmation_terminal_content(
    status: str,
    observations: Sequence[ToolObservation],
) -> str:
    reason = next(
        (
            item.reason for item in reversed(observations)
            if item.tool == "submit_my_checkout"
        ),
        None,
    )
    if reason == "PRICE_CHANGED":
        return (
            "The authoritative price changed, so I did not submit payment or place "
            "an order. Please prepare a new checkout to review the current total."
        )
    if reason in {"CHECKOUT_STALE", "ITEM_UNAVAILABLE", "CHECKOUT_NOT_FOUND"}:
        return (
            "That prepared checkout is no longer current, so I did not submit "
            "payment or place an order. Please review the cart and prepare a new checkout."
        )
    if reason == "AUTHENTICATION_REQUIRED":
        return "Please sign in again. No payment or order was submitted."
    return {
        "CONSUMED": (
            "That checkout confirmation has already been used. I did not submit it again."
        ),
        "CANCELLED": "That prepared checkout was cancelled. No payment or order was submitted.",
        "EXPIRED": (
            "That checkout confirmation expired. No payment or order was submitted. "
            "Please prepare a new checkout."
        ),
        "INVALIDATED": (
            "That prepared checkout is no longer valid. No payment or order was submitted. "
            "Please prepare a new checkout."
        ),
    }[status]


def _order_cancellation_confirmation_terminal_content(
    status: str,
    observations: Sequence[ToolObservation],
) -> str:
    reason = next(
        (
            item.reason for item in reversed(observations)
            if item.tool == "cancel_my_order"
        ),
        None,
    )
    if reason == "ORDER_VERSION_CONFLICT":
        return (
            "That order changed after you reviewed it, so I did not request "
            "cancellation. Please review the current order and try again."
        )
    if reason == "ORDER_CANCELLATION_FULFILLMENT_STARTED":
        return (
            "Fulfillment has started, so this order is no longer eligible for "
            "customer cancellation. I did not change the order."
        )
    if reason == "ORDER_CANCELLATION_WINDOW_CLOSED":
        return (
            "The cancellation window has closed, so I did not change the order."
        )
    if reason == "AUTHENTICATION_REQUIRED":
        return "Please sign in again. I did not request order cancellation."
    return {
        "CONSUMED": (
            "That order cancellation confirmation has already been used. "
            "I did not request it again."
        ),
        "CANCELLED": (
            "That order cancellation was declined. The order was not changed."
        ),
        "EXPIRED": (
            "That order cancellation confirmation expired. I did not change the "
            "order. Please review the current order before preparing a new request."
        ),
        "INVALIDATED": (
            "That prepared order cancellation is no longer valid. I did not change "
            "the order. Please review it before preparing a new request."
        ),
    }[status]


def _return_confirmation_terminal_content(
    status: str,
    observations: Sequence[ToolObservation],
) -> str:
    reason = next((
        item.reason for item in reversed(observations)
        if item.tool == "submit_my_return_request"
    ), None)
    if reason == "RETURN_VERSION_CONFLICT":
        return (
            "That order group changed after you reviewed it, so I did not submit "
            "the return request. Please review the current order first."
        )
    if reason == "RETURN_REQUIRES_DELIVERY":
        return "That order group is not delivered, so I did not submit a return request."
    if reason == "RETURN_WINDOW_EXPIRED":
        return "The return window has closed, so I did not submit a return request."
    if reason == "RETURN_CANCELLED_GROUP":
        return "That order group was cancelled and cannot enter the return workflow."
    if reason == "RETURN_ALREADY_EXISTS":
        return (
            "A return request already exists for that order group. "
            "I did not create another one."
        )
    if reason == "AUTHENTICATION_REQUIRED":
        return "Please sign in again. I did not submit a return request."
    return {
        "CONSUMED": (
            "That return-request confirmation has already been used. "
            "I did not submit it again."
        ),
        "CANCELLED": (
            "That return request was declined. The order and refund status were not changed."
        ),
        "EXPIRED": (
            "That return-request confirmation expired. I did not submit a request. "
            "Please review current eligibility before preparing a new one."
        ),
        "INVALIDATED": (
            "That prepared return request is no longer valid. I did not submit it. "
            "Please review the current order before preparing a new request."
        ),
    }[status]


def _order_cancellation_eligibility_content(
    observation: ToolObservation,
) -> str:
    """Translate the owned Order projection without preparing or promising a write."""

    cancellation = observation.order.cancellation if observation.order is not None else None
    if cancellation is None:
        return (
            "I couldn't verify cancellation eligibility for that order right now. "
            "I did not request cancellation."
        )
    if cancellation.eligible:
        return (
            "Yes — this order is currently eligible for customer cancellation. "
            "I only checked its status and did not request cancellation."
        )
    if cancellation.request_status is not None:
        return (
            "A cancellation request already exists for this order. I only checked "
            "its status and did not submit another request."
        )
    return {
        "FULFILLMENT_ALREADY_STARTED": (
            "Fulfillment has started, so this order is no longer eligible for "
            "customer cancellation. I did not change the order."
        ),
        "CANCELLATION_WINDOW_CLOSED": (
            "The cancellation window for this order has closed. I did not change "
            "the order."
        ),
    }.get(
        cancellation.ineligibility_code,
        "This order is not currently eligible for customer cancellation. "
        "I did not change the order.",
    )


def _is_comparison_request(
    value: str,
    recommendations: Sequence[ListingAttachment],
) -> bool:
    """Recognize only comparison language to constrain tools, never to choose prose."""

    if not recommendations:
        return False
    normalized = " ".join(value.casefold().split())
    return bool(re.search(
        r"\b(compare|comparison|cheaper|cheapest|best value|best overall|"
        r"which one.{0,24}best|first\s+(?:versus|vs\.?|and)\s+second|"
        r"(?:what about\s+)?(?:the\s+)?(?:first|second|third|fourth|fifth)\s+one)\b",
        normalized,
    ))


def _contextual_response_attachments(
    value: str,
    recommendations: Sequence[ListingAttachment],
) -> tuple[ListingAttachment, ...]:
    """Attach the active card selected by a grounded ordinal or price follow-up."""

    if not recommendations:
        return ()
    normalized = " ".join(value.casefold().split())
    if re.search(r"\b(?:cheaper|cheapest)\b", normalized):
        return (min(recommendations, key=lambda item: item.price_amount),)
    if "compare" in normalized or "comparison" in normalized:
        return ()
    mentioned = [
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", normalized)
    ]
    if len(mentioned) != 1 or mentioned[0] > len(recommendations):
        return ()
    return (recommendations[mentioned[0] - 1],)


def _mentioned_active_titles(
    content: str,
    recommendations: Sequence[ListingAttachment],
) -> set[str]:
    """Resolve only unambiguous active-card title prefixes used in model prose."""

    content_words = tuple(re.findall(r"[^\W_]+", content.casefold()))
    searchable_content = f" {' '.join(content_words)} "
    title_words = {
        item.listing_id: tuple(re.findall(r"[^\W_]+", item.title.casefold()))
        for item in recommendations
    }
    mentioned: set[str] = set()
    for item in recommendations:
        words = title_words[item.listing_id]
        full_title = " ".join(words)
        if full_title and f" {full_title} " in searchable_content:
            mentioned.add(item.listing_id)
            continue
        # Customer-facing prose may omit card suffixes; accept a three-or-more-word
        # prefix only when that prefix identifies exactly one active recommendation.
        for length in range(min(len(words), 8), 2, -1):
            prefix_words = words[:length]
            if sum(
                candidate[:length] == prefix_words
                for candidate in title_words.values()
            ) != 1:
                continue
            prefix = " ".join(prefix_words)
            if f" {prefix} " in searchable_content:
                mentioned.add(item.listing_id)
                break
    return mentioned


def _has_explicit_best_selection(content: str) -> bool:
    """Require a present-tense winner instead of another preference interrogation."""

    normalized = " ".join(content.casefold().split())
    if re.search(r"\b(best overall|my pick|i recommend|my recommendation)\b", normalized):
        return True
    subject = r"(?:first|second|third|fourth|fifth|[a-z0-9][a-z0-9 -]{2,80})"
    return bool(re.search(
        rf"\b{subject}\b.{{0,60}}\b(?:is|as)\s+(?:the\s+)?(?:best(?: value)?|pick)\b",
        normalized,
    ))


def _grounded_recommendation_content(
    request: str,
    recommendations: Sequence[ListingAttachment],
) -> str:
    """Compose a bounded selection/comparison from ordered validated public facts."""

    if not recommendations:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    lowered = request.casefold()
    if "cheaper" in lowered or "cheapest" in lowered or "best" in lowered:
        if len(recommendations) < 2:
            raise MarketplaceAgentV2OrchestrationFailure(
                "MODEL_RESPONSE_UNSUPPORTED"
            )
        index = min(
            range(len(recommendations)),
            key=lambda item: recommendations[item].price_amount,
        )
        listing = recommendations[index]
        ordinal = tuple(_ORDINALS)[index] if index < len(_ORDINALS) else str(index + 1)
        return (
            f"The {ordinal} listing, {listing.title}, is the best value because it "
            f"is the lowest-priced current option at {_customer_price(listing)}. "
            f"It is listed in "
            f"{_customer_condition(listing.condition)} condition"
            f"{_customer_location_suffix(listing)}."
        )
    mentioned = [
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", lowered)
    ]
    if "compare" not in lowered and len(mentioned) == 1:
        ordinal = mentioned[0]
        if ordinal > len(recommendations):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
        listing = recommendations[ordinal - 1]
        word = tuple(_ORDINALS)[ordinal - 1]
        return (
            f"The {word} listing is {listing.title} at {_customer_price(listing)}. "
            f"It is listed in {_customer_condition(listing.condition)} condition"
            f"{_customer_location_suffix(listing)}."
        )
    if len(recommendations) < 2:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    first, second = recommendations[:2]
    return (
        f"The first listing, {first.title}, is {_customer_price(first)} and is listed "
        f"in {_customer_condition(first.condition)} condition"
        f"{_customer_location_suffix(first)}. "
        f"The second listing, {second.title}, is {_customer_price(second)} and is "
        f"listed in {_customer_condition(second.condition)} condition"
        f"{_customer_location_suffix(second)}."
    )


def _customer_price(listing: ListingAttachment) -> str:
    amount = f"{listing.price_amount:.2f}"
    return f"${amount}" if listing.currency == "USD" else f"{amount} {listing.currency}"


def _customer_condition(value: str) -> str:
    labels = {
        "NEW": "New",
        "OPEN_BOX": "Open Box",
        "LIKE_NEW": "Like New",
        "GOOD": "Good",
        "FAIR": "Fair",
        "FOR_PARTS": "For Parts",
    }
    return labels.get(value, value.replace("_", " ").title())


def _customer_location_suffix(listing: ListingAttachment) -> str:
    location = listing.public_city or listing.public_region
    return "" if location is None else f" in {location}"


def _active_title_position(
    content: str,
    listing: ListingAttachment,
) -> int | None:
    """Find a customer-visible title or stable title prefix for order validation."""

    searchable = " ".join(re.findall(r"[^\W_]+", content.casefold()))
    title_words = tuple(re.findall(r"[^\W_]+", listing.title.casefold()))
    for length in range(len(title_words), 2, -1):
        position = searchable.find(" ".join(title_words[:length]))
        if position >= 0:
            return position
    return None


def _has_required_grounding(
    required_grounding: str,
    *,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
) -> bool:
    """Confirm required facts came from an approved observation, never model memory."""

    if required_grounding == "NONE":
        return True
    if required_grounding == "LISTING_DATA":
        return bool(referenced_listings or current_attachments) or any(
            item.status == "SUCCEEDED"
            and item.tool in {"check_availability", "search_listings", "get_listing"}
            for item in observations
        )
    if required_grounding == "PRIVATE_TOOL":
        return any(
            item.tool in _PRIVATE_COMMERCE_TOOLS
            and item.status in {"SUCCEEDED", "REJECTED"}
            for item in observations
        )
    if required_grounding == "KNOWLEDGE_RAG":
        return any(
            item.tool == "retrieve_help"
            and item.status == "SUCCEEDED"
            and item.reason == "KNOWLEDGE_AVAILABLE"
            and item.knowledge_passages
            for item in observations
        )
    return False


def _is_focused_clarification(content: str) -> bool:
    normalized = " ".join(content.casefold().split())
    return bool(
        len(content) <= 400
        and content.count("?") == 1
        and re.match(
            r"^(?:do you mean|which|what|could you|can you clarify|"
            r"are you looking|would you|please clarify)",
            normalized,
        )
    )


def _is_safe_grounding_abstention(
    required_grounding: str,
    content: str,
    *,
    allow_listing_clarification: bool,
    current_message: str,
) -> bool:
    normalized = " ".join(content.casefold().split())
    if normalized == " ".join(_DEFERRED_COMMERCE_ACTION_RESPONSE.casefold().split()):
        return True
    if normalized == " ".join(_CART_MUTATION_DISABLED_RESPONSE.casefold().split()):
        return True
    if required_grounding == "KNOWLEDGE_RAG":
        return normalized in {
            "i couldn't find an official marketplace document that answers that clearly.",
            " ".join(_GENERIC_RETURN_POLICY_RESPONSE.casefold().split()),
        } and (
            normalized != " ".join(_GENERIC_RETURN_POLICY_RESPONSE.casefold().split())
            or _generic_return_policy_request(current_message)
        )
    if required_grounding == "PRIVATE_TOOL":
        private_abstention = normalized == (
            "i can't verify that account-specific status here. please use the relevant "
            "account page or contact marketplace support."
        )
        # An explicit unambiguous cart command cannot terminate as a generic
        # account-status abstention. Return it to the model as a grounding
        # rejection so the allowlisted cart capability can still be selected.
        cart_command = bool(re.search(
            r"\b(?:add|put|remove|delete|take|change|update|set|make)\b.{0,100}"
            r"\b(?:cart|quantity|it|that one|item)\b",
            current_message.casefold(),
        ))
        checkout_command = bool(re.search(
            r"\b(?:buy|purchase|checkout|check\s*out|place\s+(?:my|the|an?)\s+order)\b",
            current_message.casefold(),
        ))
        return private_abstention and not cart_command and not checkout_command
    if required_grounding == "LISTING_DATA":
        return allow_listing_clarification and _is_focused_clarification(content)
    return False


def _validate_grounding(
    *,
    required_grounding: str,
    allow_listing_clarification: bool,
    current_message: str,
    content: str,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
    allow_focused_clarification: bool = False,
) -> None:
    """Reject unsupported terminal prose before any ungrounded bytes reach SSE."""

    if (
        allow_focused_clarification and _is_focused_clarification(content)
    ) or _looks_like_unintelligible_input(current_message) or _has_required_grounding(
        required_grounding,
        referenced_listings=referenced_listings,
        current_attachments=current_attachments,
        observations=observations,
    ) or _is_safe_grounding_abstention(
        required_grounding,
        content,
        allow_listing_clarification=allow_listing_clarification,
        current_message=current_message,
    ):
        return
    raise MarketplaceAgentV2OrchestrationFailure("GROUNDING_REQUIRED")


def _evidence_references(
    *,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation] = (),
) -> tuple[EvidenceReference, ...]:
    """Record internal listing evidence while keeping identifiers out of SSE text."""

    result: list[EvidenceReference] = []
    seen: set[tuple[str, str]] = set()
    for source_type, listings in (
        ("LISTING", current_attachments),
        ("EXISTING_OBSERVATION", referenced_listings),
    ):
        for listing in listings:
            identity = (source_type, listing.listing_id)
            if identity in seen:
                continue
            seen.add(identity)
            result.append(EvidenceReference(
                sourceType=source_type,
                sourceId=listing.listing_id,
                version=listing.response_hash,
                retrievedAt=listing.checked_at,
            ))
    for observation in observations:
        if observation.status != "SUCCEEDED":
            continue
        for passage in observation.knowledge_passages:
            identity = ("KNOWLEDGE_DOCUMENT", passage.article_id)
            if identity in seen:
                continue
            seen.add(identity)
            result.append(EvidenceReference(
                sourceType="KNOWLEDGE_DOCUMENT",
                sourceId=passage.article_id,
                version=passage.version,
                retrievedAt=observation.observed_at,
            ))
        order_ids = (
            tuple(item.order_id for item in observation.orders)
            + (() if observation.order is None else (observation.order.order_id,))
            + tuple(item.order_id for item in observation.order_references)
        )
        for order_id in order_ids:
            identity = ("ORDER", order_id)
            if identity in seen:
                continue
            seen.add(identity)
            result.append(EvidenceReference(
                sourceType="ORDER",
                sourceId=order_id,
                version=(
                    str(observation.order.version)
                    if observation.order is not None
                    and observation.order.order_id == order_id
                    else observation.observed_at.isoformat()
                ),
                retrievedAt=observation.observed_at,
            ))
    return tuple(result[:20])


def _knowledge_citations(
    observations: Sequence[ToolObservation],
) -> tuple[str, ...]:
    """Project approved help evidence into stable customer-visible citations."""

    result: list[str] = []
    seen: set[str] = set()
    for observation in reversed(observations):
        if observation.tool != "retrieve_help" or observation.status != "SUCCEEDED":
            continue
        for passage in observation.knowledge_passages:
            if passage.article_id in seen:
                continue
            seen.add(passage.article_id)
            section = (
                "" if passage.section == passage.title else f" — {passage.section}"
            )
            result.append(f"{passage.article_id}: {passage.title}{section}")
    return tuple(result[:10])


def _validate_terminal_response(
    *,
    current_message: str,
    content: str,
    active_recommendations: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
    has_waiting_interaction: bool,
    checkout_enabled: bool = False,
    return_read_required: bool = False,
    query_only_refinement: bool = False,
    search_dependent: bool = False,
    explicit_search_confirmation: bool = False,
) -> None:
    """Reject unsupported comparison/result claims before canonical persistence."""

    lowered = content.casefold()
    if query_only_refinement and re.search(
        r"\b(?:all|every|each|verified|confirmed|guaranteed)\b.{0,100}"
        r"\b(?:\d{1,3}\s*gb|ram|\d{1,3}\s*inch(?:es)?|wireless|rgb)\b|"
        r"\b(?:\d{1,3}\s*gb|ram|\d{1,3}\s*inch(?:es)?|wireless|rgb)\b"
        r".{0,100}\b(?:verified|confirmed|guaranteed)\b",
        lowered,
    ):
        # A free-text search wish is not an authoritative Product attribute.
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    disabled_cart_response = lowered == _CART_MUTATION_DISABLED_RESPONSE.casefold()
    if (
        _informational_order_cancellation_request(current_message)
        and not any(
            item.tool == "get_my_order"
            and item.status == "SUCCEEDED"
            and item.order is not None
            for item in observations
        )
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if return_read_required and not any(
        item.tool == "get_my_return"
        and item.status in {"SUCCEEDED", "REJECTED", "FAILED"}
        for item in observations
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if re.search(
        r"\bcart\s+version\b|\bcart\s+expires?\b|"
        r"\bexpires?\s*(?:at|:)\s*\d{4}-\d{2}-\d{2}t",
        lowered,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if re.search(r"\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b", content):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if re.search(
        r"\b(?:broadInventoryCount|exactMatchCount|normalizedQuery|"
        r"resultCount|totalMatches)\b",
        content,
    ) or re.search(r"\b(?:NEW|GOOD|FAIR|OPEN_BOX|LIKE_NEW|FOR_PARTS)\b", content):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if re.search(
        r"\b(?:open_box|like_new|for_parts)\b|"
        r"\bmatch\s*:\s*(?:exact|related)\b|\bmatch_quality\b",
        lowered,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    question_limit = 2 if _is_unambiguous_greeting(current_message) else 1
    if (
        content.count("?") > question_limit
        or "\nrefine:" in lowered
        or lowered.startswith("refine:")
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if current_attachments and "?" in content:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if not has_waiting_interaction and re.search(
        r"\byes\s*(?:/|or)\s*no\b", lowered
    ):
        raise MarketplaceAgentV2OrchestrationFailure(
            "MODEL_RESPONSE_UNSUPPORTED",
            repair_reason=(
                "SEARCH_PERMISSION_UNREQUESTED" if search_dependent
                and not explicit_search_confirmation else None
            ),
        )
    if (
        search_dependent and not explicit_search_confirmation
        and not has_waiting_interaction
        and "?" in content
        and re.search(
            r"\b(?:would you like me to|do you want me to|shall i|"
            r"should i|may i|can i)\s+(?:search|look for|find|show|"
            r"list|display|(?:run|start)\s+(?:(?:a|an|another|the|"
            r"that|this)\s+)?(?:(?:new|updated|refined|current)\s+)?"
            r"search)\b",
            lowered,
        )
    ):
        # A prior executed search is context, not permission to advertise a
        # new search or ask for consent before an ordinary read-only repair.
        raise MarketplaceAgentV2OrchestrationFailure(
            "MODEL_RESPONSE_UNSUPPORTED",
            repair_reason="SEARCH_PERMISSION_UNREQUESTED",
        )
    if (
        search_dependent and not explicit_search_confirmation
        and not has_waiting_interaction
        and re.search(r"\b(?:search|re-run|rerun)\b", lowered)
        and re.search(r"\b(?:proceed|go ahead)\s*\?\s*$", lowered)
    ):
        # A completed read-only search must not be presented as a proposed
        # action awaiting permission, even when Product returned no cards.
        raise MarketplaceAgentV2OrchestrationFailure(
            "MODEL_RESPONSE_UNSUPPORTED",
            repair_reason="SEARCH_PERMISSION_UNREQUESTED",
        )
    if not has_waiting_interaction and re.search(
        r"\b(?:add|adding|put)\b.{0,80}\bcart\b.{0,80}"
        r"\brequires?\b.{0,30}\bconfirmation\b",
        lowered,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if (
        not disabled_cart_response
        and _claims_or_offers_unavailable_action(
            lowered, checkout_enabled=checkout_enabled
        )
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    evidence = tuple(current_attachments) or tuple(active_recommendations)
    for item in evidence:
        identifier_prefix = item.listing_id.casefold()[:6]
        if identifier_prefix and re.search(
            rf"\b{re.escape(identifier_prefix)}[0-9a-z]*\b", lowered
        ):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    order_ids = {
        order_id
        for observation in observations
        for order_id in (
            *(item.order_id for item in observation.orders),
            *(item.order_id for item in observation.order_references),
            *(() if observation.order is None else (observation.order.order_id,)),
        )
    }
    if any(
        re.search(rf"\b{re.escape(order_id.casefold()[:6])}[0-9a-z]*\b", lowered)
        for order_id in order_ids
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if evidence:
        for word, ordinal in _ORDINALS.items():
            if re.search(rf"\b{word}\b", lowered) and ordinal > len(evidence):
                raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    exact_claim = re.search(r"\b(\d{1,2})\s+exact\s+match(?:es)?\b", lowered)
    if exact_claim is not None:
        supported = next(
            (item.exact_match_count for item in reversed(observations)
             if item.exact_match_count is not None),
            sum(item.match_quality == "EXACT" for item in evidence),
        )
        if int(exact_claim.group(1)) != supported:
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    # Result-presentation claims require cards on this response, while ordinary
    # comparison prose such as "Here are the differences" may reuse prior cards.
    presents_results = bool(
        re.search(r"\bi found\b", lowered)
        or re.search(
            r"\bhere are\s+(?:the\s+)?(?:(?:\d+|some|current|closest|top)\s+)?"
            r"(?:listings?|matches?|results?)\b",
            lowered,
        )
        or re.search(
            r"\bshowing\b.{0,80}\b(?:listings?|matches?|results?)\b",
            lowered,
        )
        or re.search(r"\b(?:closest\s+)?matches\s+are\s+shown\b", lowered)
    )
    commerce_grounded = any(
        item.status == "SUCCEEDED"
        and item.tool in _PRIVATE_COMMERCE_TOOLS
        for item in observations
    )
    order_detail = next((
        item.order for item in reversed(observations)
        if item.tool == "get_my_order"
        and item.status == "SUCCEEDED"
        and item.order is not None
    ), None)
    if order_detail is not None and _order_item_snapshot_requested(current_message):
        item_titles = tuple(
            item.title.casefold()
            for group in order_detail.groups
            for item in group.items
        )
        if item_titles and not any(title in lowered for title in item_titles):
            raise MarketplaceAgentV2OrchestrationFailure(
                "MODEL_RESPONSE_UNSUPPORTED"
            )
    if presents_results and not current_attachments and not commerce_grounded:
        raise MarketplaceAgentV2OrchestrationFailure(
            "MODEL_RESPONSE_UNSUPPORTED",
            repair_reason=(
                "SEARCH_RESULT_CLAIM_UNGROUNDED" if search_dependent else None
            ),
        )
    if not disabled_cart_response and re.search(
        r"\b(?:added|adding|removed|removing|updated|updating|changed|"
        r"changing|set|setting)\b.{0,80}"
        r"\b(?:cart|quantity|item)\b|"
        r"\b(?:cart|quantity|item)\b.{0,80}"
        r"\b(?:added|adding|removed|removing|updated|updating|changed|"
        r"changing|set|setting)\b",
        lowered,
    ) and not any(
        item.tool in _CART_MUTATION_TOOLS and item.status == "SUCCEEDED"
        for item in observations
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if re.search(
        r"\b(?:cart|basket)\b.{0,40}\b(?:is\s+)?now\s+empty\b",
        lowered,
    ) and not any(
        item.status == "SUCCEEDED"
        and item.cart is not None
        and item.cart.item_count == 0
        for item in observations
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if any(item.tool in _CART_MUTATION_TOOLS for item in observations):
        # Ordinals such as "add the second one" select a cart target; they do
        # not turn the canonical mutation outcome into a comparison response.
        return
    if (
        _listing_availability_request(current_message)
        and any(
            item.tool == "check_availability" and item.status == "SUCCEEDED"
            for item in observations
        )
    ) or (
        _listing_detail_request(current_message)
        and any(
            item.tool == "get_listing" and item.status == "SUCCEEDED"
            for item in observations
        )
    ):
        if re.search(
            r"\b(?:do you want me to|would you like me to|shall i|i can)\s+"
            r"(?:check|verify|look up)\b",
            lowered,
        ):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
        # Ordinals identify the grounded target here; they do not turn the
        # response into a multi-listing comparison.
        return
    if (
        _confirmation_answer(current_message) is True
        and any(
            item.tool == "search_listings"
            and item.status in {"SUCCEEDED", "FAILED"}
            for item in observations
        )
        and (
            "yes/no" in lowered
            or re.search(
                r"\b(?:proceed(?:\s+to)?|run|start)\b.{0,80}"
                r"\b(?:marketplace\s+)?search\b",
                lowered,
            )
        )
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if not _is_comparison_request(current_message, active_recommendations):
        return
    inference_text = lowered.replace("match quality", "")
    if re.search(
        r"\b(quality|reliability|reliable|longevity|durability|durable|likely|"
        r"implies?|suggests?|possibly|may|might|could|portable|simpler|premium|appearance|"
        r"aesthetic|sleek(?:er|est)?|larger|bigger|smaller|higher?[- ]?end|"
        r"feature(?:d|ful)?|"
        r"hidden wear|expected wear|"
        r"build quality)\b",
        inference_text,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    mentioned_titles = _mentioned_active_titles(content, active_recommendations)
    mentioned_ordinals = {
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", lowered)
    }
    if not mentioned_titles and not mentioned_ordinals:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    request = current_message.casefold()
    if "compare" in request or re.search(r"\bfirst\s+(?:versus|vs\.?|and)\s+second\b", request):
        if not ({1, 2} <= mentioned_ordinals or len(mentioned_titles) >= 2):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
        if len(active_recommendations) >= 2:
            first_position = _active_title_position(
                content, active_recommendations[0]
            )
            second_position = _active_title_position(
                content, active_recommendations[1]
            )
            if (
                first_position is not None
                and second_position is not None
                and first_position > second_position
            ):
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_RESPONSE_UNSUPPORTED"
                )
    if "best" in request or "cheaper" in request or "cheapest" in request:
        supported_criteria = (
            "price", "cost", "condition", "new", "like new", "match", "location",
        )
        if not any(item in lowered for item in supported_criteria):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if ("which one" in request and "best" in request) or "best overall" in request:
        if not _has_explicit_best_selection(content):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if "cheaper" in request or "cheapest" in request:
        cheapest_index = min(
            range(len(active_recommendations)),
            key=lambda index: active_recommendations[index].price_amount,
        ) + 1
        cheapest = active_recommendations[cheapest_index - 1]
        if cheapest.listing_id not in mentioned_titles and cheapest_index not in mentioned_ordinals:
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")


def _claims_or_offers_unavailable_action(
    lowered: str,
    *,
    checkout_enabled: bool = False,
) -> bool:
    """Blocks promises for capabilities that are absent from the V2 registry."""

    always_unavailable = (
        r"(?:^|[.!?]\s*)(?:opening|opened)\s+(?:the|a)\s+photo gallery\b",
        r"\b(?:i(?:'m| am)|we(?:'re| are))\s+opening\s+(?:the|a)\s+photo gallery\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:show|retrieve|access)\b.{0,80}\bseller(?:'s|’s)?\b.{0,80}"
        r"\b(?:pickup|payment)\b.{0,40}\binstructions\b",
    )
    checkout_unavailable = (
        r"\b(?:do you\s+)?want\s+to\s+(?:checkout|check\s*out)\b",
        r"\bor\s+(?:checkout|check\s*out)(?:\s+(?:now|next))?\s*\?",
        r"\b(?:you can|you may|feel free to)\s+(?:checkout|check\s*out)\b",
        r"(?m)^\s*(?:\d+\s*[).]|[-*])\s*(?:checkout|check\s*out)\b",
        r"\b(?:purchase|buy)\s+(?:everything|all)\s+in\s+(?:my|your|the)\s+cart\b",
        r"\b(?:i(?:'m| am)|we(?:'re| are))\s+starting\s+(?:a|the)\s+purchase\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:start|complete|make)\s+(?:a|the)\s+purchase\b",
        r"\b(?:would you like(?: me)? to|i can|we can|shall i|let me)\s+"
        r"(?:proceed\s+to\s+|start\s+|complete\s+)?(?:checkout|check\s*out)\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:take|process|make)\s+(?:a|the|your)?\s*payment\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:place|submit|create)\s+(?:an?|the|your)?\s*order\b",
        r"\bproceed\s+to\b.{0,60}\b(?:checkout|shipping|payment)\b",
    )
    patterns = always_unavailable + (() if checkout_enabled else checkout_unavailable)
    return any(re.search(pattern, lowered) for pattern in patterns)


def _deferred_commerce_action_response(value: str) -> str | None:
    """Return the release-boundary response only for an explicit action request."""

    normalized = " ".join(value.casefold().strip().split())
    action = (
        r"(?:check\s*out|checkout|pay(?:\s+now)?|"
        r"(?:buy|purchase)\s+(?:everything|all|my\s+cart)|"
        r"place\s+(?:an?|the|my)\s+order|"
        r"complete\s+(?:the|my)\s+purchase)"
    )
    if re.search(rf"^(?:please\s+)?{action}\b", normalized) or re.search(
        rf"\b(?:can|could|would|will)\s+you\s+{action}\b", normalized
    ):
        return _DEFERRED_COMMERCE_ACTION_RESPONSE
    return None


def _partial_cart_checkout_request(value: str) -> bool:
    normalized = " ".join(value.casefold().split())
    return bool(
        re.search(r"\b(?:buy|purchase|check\s*out)\b", normalized)
        and re.search(r"\b(?:only|just)\b", normalized)
        and not re.search(r"\b(?:everything|all(?:\s+of)?\s+(?:it|them|my\s+cart))\b", normalized)
    )


def _informational_order_cancellation_request(value: str) -> bool:
    """Keep eligibility questions and explicit no-op language on the read path."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    if re.search(
        r"\b(?:do not|don't|dont|without)\b.{0,45}\bcancel(?:led|lation|ing)?\b",
        normalized,
    ) or re.search(
        r"\b(?:only|just)\s+(?:tell|check|show)\b.{0,65}\bcancel",
        normalized,
    ):
        return True
    return bool(
        re.search(
            r"\bcan\s+(?:this|that|my|the)\b.{0,45}\border\b.{0,35}"
            r"\bbe\s+cancelled\b",
            normalized,
        )
        or re.search(
            r"\b(?:is|was)\s+(?:this|that|my|the)\b.{0,45}\border\b.{0,35}"
            r"\b(?:eligible|available)\b.{0,25}\bcancell?",
            normalized,
        )
        or re.search(
            r"\b(?:can|could)\s+i\s+(?:still\s+)?cancel\b.{0,45}\border\b",
            normalized,
        )
    )


def _explicit_order_cancellation_request(value: str) -> bool:
    """Recognize a customer command without converting eligibility questions."""

    if _informational_order_cancellation_request(value):
        return False
    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(
        re.search(
            r"^(?:please\s+)?(?:go\s+ahead\s+and\s+)?cancel\b",
            normalized,
        )
        or re.search(
            r"\b(?:i\s+(?:want|need|would\s+like|'d\s+like)\s+to|"
            r"can\s+you|could\s+you|would\s+you)\s+cancel\b",
            normalized,
        )
    )


def _informational_return_request(value: str) -> bool:
    """Keep status, eligibility, and explicit no-op return questions read-only."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    if re.search(
        r"\b(?:do not|don't|dont|without)\b.{0,55}\b(?:return|refund)",
        normalized,
    ):
        return True
    return bool(re.search(
        r"\b(?:what(?:'s| is)\s+(?:happening|going on)|status|track|check)\b"
        r".{0,50}\b(?:return|refund)\b|"
        r"\bhas\b.{0,45}\b(?:return|refund)\b.{0,25}\b(?:finished|completed|done)\b|"
        r"\b(?:can|could|am)\s+i\b.{0,35}\b(?:return|refund)\b|"
        r"\b(?:is|was)\b.{0,45}\b(?:eligible|available)\b.{0,25}"
        r"\b(?:return|refund)",
        normalized,
    ))


def _return_read_only_request(
    current_message: str,
    recent_messages: Sequence[tuple[str, str]],
) -> bool:
    """Recognize a return eligibility/status read, including a grounded follow-up."""

    if _informational_return_request(current_message):
        return True
    if not recent_messages:
        return False
    # An assistant's optional offer to help with returns does not turn the
    # customer's later order-status pronoun into a return-status question.
    recent = " ".join(
        content.casefold() for role, content in recent_messages[-6:]
        if role.casefold() == "user"
    )
    if not re.search(r"\b(?:return|refund)\b", recent):
        return False
    normalized = " ".join(
        current_message.casefold().replace("’", "'").split()
    )
    if re.search(
        r"\b(?:is|was)\s+(?:that|it|this|the\s+(?:item|order|store\s+group))"
        r"\s+(?:return\s+)?eligible\b|"
        r"\b(?:can|could)\s+(?:that|it|this)\s+be\s+(?:returned|refunded)\b|"
        r"\b(?:check|show)\s+(?:that|its|the)\s+(?:return\s+)?eligibility\b",
        normalized,
    ):
        return True
    return bool(
        re.search(r"\b(?:return|refund)\b", recent)
        and re.search(
            r"\b(?:what(?:'s| is)\s+(?:happening|going on)|status|track)\b"
            r".{0,40}\b(?:that|it|this)\b",
            normalized,
        )
    )


def _grounded_return_read_arguments(
    current_message: str,
    order_arguments: dict[str, object],
    observation: ToolObservation,
) -> dict[str, object]:
    """Select only an exact actor-owned item or store returned by Order."""

    result: dict[str, object] = {"orderId": order_arguments.get("orderId")}
    normalized = " ".join(current_message.casefold().split())
    title_matches = tuple(
        reference for reference in observation.order_item_references
        if " ".join(reference.title.casefold().split()) in normalized
    )
    if len(title_matches) == 1:
        result["listingId"] = title_matches[0].listing_id
        return result
    store_matches = tuple(
        reference for reference in observation.order_item_references
        if reference.store_name is not None
        and " ".join(reference.store_name.casefold().split()) in normalized
    )
    store_names = {
        reference.store_name for reference in store_matches
        if reference.store_name is not None
    }
    if len(store_names) == 1:
        result["storeName"] = next(iter(store_names))
    return result


def _grounded_return_order_observation(
    current_message: str,
    observations: Sequence[ToolObservation],
) -> ToolObservation | None:
    """Find an exact actor-owned return target without guessing model arguments."""

    for observation in reversed(observations):
        if (
            observation.tool != "get_my_order"
            or observation.status != "SUCCEEDED"
            or not observation.order_references
        ):
            continue
        order_id = observation.order_references[0].order_id
        grounded = _grounded_return_read_arguments(
            current_message, {"orderId": order_id}, observation
        )
        if "listingId" in grounded or "storeName" in grounded:
            return observation
        if observation.order is not None and len(observation.order.groups) == 1:
            return observation
    return None


def _return_mutation_tool_schemas(
    schemas: Sequence[dict[str, object]],
    *,
    current_message: str,
    required_reason: str | None,
    observations: Sequence[ToolObservation],
    eligible_this_turn: bool,
    blocked_this_turn: bool,
) -> tuple[dict[str, object], ...]:
    """Expose only the still-valid stage of one explicit customer return flow."""

    by_name = {str(item.get("name")): item for item in schemas}
    if blocked_this_turn:
        return ()
    grounded_order = _grounded_return_order_observation(
        current_message, observations
    )
    if eligible_this_turn:
        prepare = by_name.get("prepare_my_return_request")
        return () if prepare is None else (prepare,)
    if grounded_order is not None:
        if required_reason is None:
            return ()
        eligibility = by_name.get("get_my_return")
        return () if eligibility is None else (eligibility,)
    has_owned_order_reference = any(
        observation.order_references
        for observation in observations
        if observation.status == "SUCCEEDED"
    )
    next_tool = "get_my_order" if has_owned_order_reference else "list_my_orders"
    schema = by_name.get(next_tool)
    return () if schema is None else (schema,)


def _return_terminal_progress_reason(
    *,
    current_message: str,
    required_reason: str | None,
    observations: Sequence[ToolObservation],
    eligible_this_turn: bool,
    blocked_this_turn: bool,
) -> str | None:
    """Classify incomplete explicit-return prose without choosing the next tool."""

    if blocked_this_turn:
        return None
    grounded_order = _grounded_return_order_observation(
        current_message, observations
    )
    if grounded_order is None or required_reason is None:
        return None
    if eligible_this_turn:
        return "RETURN_PREPARATION_REQUIRED"
    return "RETURN_ELIGIBILITY_REQUIRED"


def _private_return_workflow_request(
    current_message: str,
    recent_messages: Sequence[tuple[str, str]],
) -> bool:
    """Identify a private return workflow turn for capability-off handling."""

    return (
        _return_read_only_request(current_message, recent_messages)
        or _return_request_mutation_intent(current_message, recent_messages)
    )


def _explicit_return_request(value: str) -> bool:
    """Recognize intent to submit a return request, never direct refund authority."""

    if _informational_return_request(value):
        return False
    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(
        re.search(
            r"^(?:please\s+)?(?:go\s+ahead\s+and\s+)?(?:return|send\s+back)\b|"
            r"\b(?:prepare|start|create|open|submit)\b.{0,45}"
            r"\breturn(?:\s+request)?\b|"
            r"\b(?:i\s+(?:want|need|would\s+like|'d\s+like)\s+to|"
            r"can\s+you|could\s+you|would\s+you)\s+(?:return|send\s+back)\b|"
            r"\b(?:arrived|was|is)\s+(?:damaged|broken)\b|"
            r"\b(?:received|got)\s+(?:the\s+)?wrong\s+item\b",
            normalized,
        )
    )


def _return_reason_from_context(
    current_message: str,
    recent_messages: Sequence[tuple[str, str]],
) -> str | None:
    """Map explicit customer language to only an existing Order reason code."""

    # A reason changes the exact confirmed action. Never carry one forward from
    # an older request; a follow-up answer such as "It was damaged" contains
    # the reason in the current turn and remains sufficient.
    del recent_messages
    normalized = " ".join(
        current_message.casefold().replace("’", "'").split()
    )
    if re.search(r"\bwrong\s+(?:item|product|order)\b", normalized):
        return "WRONG_ITEM"
    if re.search(r"\b(?:damaged|broken|cracked|defective)\b", normalized):
        return "DAMAGED"
    if re.search(
        r"\b(?:not\s+as\s+(?:expected|described)|different\s+from\s+the\s+listing)\b",
        normalized,
    ):
        return "NOT_AS_EXPECTED"
    if re.search(
        r"\b(?:no\s+longer\s+needed|don't\s+need|do\s+not\s+need|"
        r"changed\s+my\s+mind)\b",
        normalized,
    ):
        return "NO_LONGER_NEEDED"
    if re.search(r"\b(?:other\s+reason|something\s+else)\b", normalized):
        return "OTHER"
    return None


def _return_request_mutation_intent(
    current_message: str,
    recent_messages: Sequence[tuple[str, str]],
) -> bool:
    """Carry explicit return intent through one focused reason-answer turn."""

    if _explicit_return_request(current_message):
        return True
    if not recent_messages or recent_messages[-1][0].casefold() != "assistant":
        return False
    assistant_prompt = " ".join(
        recent_messages[-1][1].casefold().replace("’", "'").split()
    )
    if not re.search(r"\b(?:return|refund)\b", assistant_prompt):
        return False
    prior_intent = any(
        role.casefold() == "user" and _explicit_return_request(content)
        for role, content in recent_messages[-4:-1]
    )
    if not prior_intent:
        return False
    if _return_reason_from_context(current_message, ()) is not None:
        return bool(re.search(
            r"\b(?:reason|why|issue|happened)\b", assistant_prompt
        ))
    return bool(
        re.search(r"\b(?:which|store|group|item|order|mean)\b", assistant_prompt)
        and re.search(
            r"\b(?:first|second|third|last|latest|store|group|item|order|"
            r"this|that|entire|whole)\b",
            current_message.casefold(),
        )
    )


def _required_return_reason(
    current_message: str,
    recent_messages: Sequence[tuple[str, str]],
) -> str | None:
    """Reuse a reason only across an immediate return-target clarification."""

    current = _return_reason_from_context(current_message, ())
    if current is not None:
        return current
    if not recent_messages or recent_messages[-1][0].casefold() != "assistant":
        return None
    prompt = recent_messages[-1][1].casefold()
    if not (
        re.search(r"\breturn\b", prompt)
        and re.search(r"\b(?:which|store|group|item|order|mean)\b", prompt)
    ):
        return None
    for role, content in reversed(recent_messages[-4:-1]):
        if role.casefold() != "user" or not _explicit_return_request(content):
            continue
        return _return_reason_from_context(content, ())
    return None


def _partial_return_request(value: str) -> bool:
    """Detect a requested subset the existing whole-group API cannot preserve."""

    normalized = " ".join(value.casefold().replace("’", "'").split())
    return bool(re.search(
        r"\b(?:return|send\s+back)\s+only\b|"
        r"\bonly\s+(?:return|send\s+back)\b|"
        r"\b(?:return|send\s+back)\b.{0,55}\bbut\s+not\b",
        normalized,
    ))


def _ambiguous_checkout_reference(
    value: str,
    *,
    referenced_listings: Sequence[ListingAttachment],
    prior_observations: Sequence[ToolObservation],
) -> bool:
    normalized = " ".join(value.casefold().split())
    if not re.search(r"\b(?:buy|purchase)\s+(?:that|it|one)\b", normalized):
        return False
    cart_references = next((
        item.cart_item_references
        for item in reversed(prior_observations)
        if item.tool in {"get_my_cart", *_CART_MUTATION_TOOLS}
        and item.cart_item_references
    ), ())
    if cart_references:
        return len(cart_references) > 1
    return len(referenced_listings) != 1


def _cart_mutation_reference_ambiguous(
    current_message: str,
    *,
    referenced_listings: Sequence[ListingAttachment],
    prior_observations: Sequence[ToolObservation],
) -> bool:
    """Reject a pronoun mutation when more than one current cart target fits."""

    normalized = " ".join(current_message.casefold().split())
    if not re.search(
        r"\b(?:add|put|remove|delete|take|quantity|update|change|make|set)\b",
        normalized,
    ):
        return False

    latest_mutation = next((
        item for item in reversed(prior_observations)
        if item.tool in _CART_MUTATION_TOOLS
        and item.status == "SUCCEEDED"
        and item.cart_mutation_reference is not None
    ), None)
    if latest_mutation is not None and re.search(
        r"\b(?:it|that|this)\b", normalized
    ):
        # The immediately preceding successful mutation owns a single explicit
        # target even when its returned cart snapshot contains multiple lines.
        return False

    references: tuple[tuple[str, int], ...] = ()
    for observation in reversed(prior_observations):
        if observation.tool not in {"get_my_cart", *_CART_MUTATION_TOOLS}:
            continue
        references = tuple(
            (item.title, item.position) for item in observation.cart_item_references
        )
        break
    if not references:
        references = tuple(
            (item.title, position)
            for position, item in enumerate(referenced_listings, 1)
        )
    if len(references) <= 1:
        return False

    ordinals = {
        "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5,
    }
    if any(
        re.search(rf"\b{word}\b", normalized)
        and any(position == ordinal for _, position in references)
        for word, ordinal in ordinals.items()
    ):
        return False

    # A displayed title is an authoritative conversation reference. Resolve a
    # unique full-title mention before the broader shared-term safeguard so
    # sibling cards such as "Harbor Business ..." do not create false ambiguity.
    normalized_reference = " ".join(re.findall(r"[^\W_]+", normalized))
    exact_title_matches = sum(
        bool(
            (normalized_title := " ".join(
                re.findall(r"[^\W_]+", title.casefold())
            ))
            and normalized_title in normalized_reference
        )
        for title, _ in references
    )
    if exact_title_matches:
        return exact_title_matches != 1

    ignored = {
        "add", "cart", "change", "delete", "from", "item", "make", "my",
        "quantity", "remove", "set", "the", "this", "that", "to", "update",
    }
    message_terms = {
        term for term in re.findall(r"[^\W_]+", normalized)
        if len(term) >= 3 and term not in ignored
    }
    matching_references = 0
    for title, _ in references:
        title_terms = {
            term for term in re.findall(r"[^\W_]+", title.casefold())
            if len(term) >= 3 and term not in ignored
        }
        if message_terms & title_terms:
            matching_references += 1
    return matching_references != 1


def _cart_add_reference_unresolved(
    current_message: str,
    *,
    referenced_listings: Sequence[ListingAttachment],
    prior_observations: Sequence[ToolObservation],
) -> bool:
    """Reject an add command whose listing target is absent from current evidence."""

    normalized = " ".join(current_message.casefold().split())
    if not re.search(
        r"\b(?:add|put)\b.{0,120}\b(?:to|into|in)\s+(?:my\s+)?cart\b",
        normalized,
    ):
        return False

    references: tuple[tuple[str, int], ...] = ()
    for observation in reversed(prior_observations):
        if observation.tool not in {"get_my_cart", *_CART_MUTATION_TOOLS}:
            continue
        references = tuple(
            (item.title, item.position) for item in observation.cart_item_references
        )
        if references:
            break
    if not references:
        references = tuple(
            (item.title, position)
            for position, item in enumerate(referenced_listings, 1)
        )
    if not references:
        return True

    ordinals = {
        "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5,
    }
    if any(
        re.search(rf"\b{word}\b", normalized)
        and any(position == ordinal for _, position in references)
        for word, ordinal in ordinals.items()
    ):
        return False
    if len(references) == 1 and re.search(
        r"\b(?:this|that|it|the item|the listing|this item|that item)\b",
        normalized,
    ):
        return False
    if re.search(r"\bone of (?:them|these|those)\b", normalized):
        return False

    ignored = {
        "add", "cart", "in", "item", "listing", "my", "please", "put",
        "the", "this", "that", "to",
    }
    message_terms = {
        term for term in re.findall(r"[^\W_]+", normalized)
        if len(term) >= 3 and term not in ignored
    }
    if not message_terms:
        return len(references) != 1
    return not any(
        message_terms & {
            term for term in re.findall(r"[^\W_]+", title.casefold())
            if len(term) >= 3 and term not in ignored
        }
        for title, _ in references
    )


def _ambiguous_cart_clarification(current_message: str) -> str:
    """Keep an ambiguous reversible command to one focused customer question."""

    normalized = current_message.casefold()
    if re.search(r"\b(?:remove|delete|take)\b", normalized):
        action = "remove"
    elif re.search(r"\b(?:add|put)\b", normalized):
        action = "add"
    elif re.search(r"\b(?:quantity|update|change|make|set)\b", normalized):
        action = "update"
    else:
        action = "change"
    return f"Which cart item do you want me to {action}?"


def _is_explicit_cart_mutation_request(value: str) -> bool:
    """Recognize only an imperative cart change for disabled-capability truth."""

    return bool(re.match(
        r"^\s*(?:please\s+)?(?:add|put|remove|delete|take|change|update|set|make)\b",
        value.casefold(),
    ))


def _is_unambiguous_greeting(value: str) -> bool:
    """Recognize only a greeting so common provider prose shapes are not rejected."""

    normalized = " ".join(value.casefold().strip().rstrip(".!?").split())
    return normalized in {
        "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
    }


def _step_limit_content(
    observations: Sequence[ToolObservation],
    attachments: Sequence[ListingAttachment] = (),
    *,
    required_grounding: str = "NONE",
) -> str:
    """Complete a bounded turn from current authoritative tool facts at step five."""

    knowledge = next((
        item for item in reversed(observations)
        if item.tool == "retrieve_help"
        and item.status == "SUCCEEDED"
        and item.knowledge_passages
    ), None)
    if knowledge is not None:
        passage = knowledge.knowledge_passages[0]
        return (
            f"The marketplace help article “{passage.title}” says:\n\n"
            f"{passage.excerpt}"
        )

    # The model gets the first synthesis opportunity. At the hard step limit,
    # preserve only facts supplied by the successful owning-service read.
    checkout_read = next((
        item for item in reversed(observations)
        if item.tool == "get_my_checkout"
        and item.status == "SUCCEEDED"
        and item.checkout is not None
    ), None)
    if checkout_read is not None:
        checkout = checkout_read.checkout
        items = "; ".join(
            f"{item.title} (quantity {item.quantity})"
            for item in checkout.items[:5]
        )
        return (
            f"Your checkout is {checkout.status.replace('_', ' ').lower()}. "
            f"It contains {items}. The current total is {checkout.total} "
            f"{checkout.currency}. No payment or order was submitted by this read."
        )
    listing_read = next((
        item for item in reversed(observations)
        if item.tool == "get_listing"
        and item.status == "SUCCEEDED"
        and item.reason == "LISTING_VERIFIED"
        and item.attachments
    ), None)
    if listing_read is not None:
        listing = listing_read.attachments[0]
        return (
            f"{listing.title} is a current {listing.category_name} listing at "
            f"{listing.price_amount} {listing.currency}. "
            "This detail check does not verify live stock."
        )
    availability_read = next((
        item for item in reversed(observations)
        if item.tool == "check_availability"
        and item.status == "SUCCEEDED"
        and item.broad_inventory_count is not None
    ), None)
    if availability_read is not None:
        count = availability_read.broad_inventory_count
        category = availability_read.normalized_query or "that category"
        return (
            f"The current category check shows {count} active "
            f"listing{'s' if count != 1 else ''} in "
            f"{category}. This category check does not confirm stock for one "
            "specific listing."
        )
    if any(
        item.tool == "get_listing" and item.reason == "LISTING_NOT_FOUND"
        for item in observations
    ):
        return "I couldn't find a current listing for that item."
    if any(
        item.tool == "check_availability" and item.status == "FAILED"
        for item in observations
    ):
        return "I couldn't verify current availability right now. Please try again later."
    if any(
        item.tool == "check_availability" and item.reason == "CATEGORY_NOT_FOUND"
        for item in observations
    ):
        return "I couldn't find that marketplace category. Please choose a current category."
    if any(
        item.tool == "check_availability" and item.reason == "INVALID_ARGUMENTS"
        for item in observations
    ):
        return "I couldn't understand which category to check. Please name a marketplace category."
    if any(
        item.tool == "get_listing" and item.status == "FAILED"
        for item in observations
    ):
        return "I couldn't verify that listing's current details right now."

    result = next(
        (
            item for item in reversed(observations)
            if item.tool == "search_listings"
            and item.status == "SUCCEEDED"
            and item.reason == "RESULTS_AVAILABLE"
            and item.attachments
        ),
        None,
    )
    verified = tuple(attachments) or (() if result is None else result.attachments)
    if result is not None and verified:
        count = len(verified)
        filters = _customer_filter_summary(result.filter_categories)
        scope = "your request" if not filters else f"your {filters} filters"
        noun = "listing" if count == 1 else "listings"
        exact = result.exact_match_count or 0
        related = result.related_match_count or 0
        fit = (
            " The closest matches are shown first, followed by related alternatives."
            if exact > 0 and related > 0
            else " The verified option is shown below."
            if count == 1
            else " The verified options are shown below."
        )
        return f"I found {count} current {noun} matching {scope}.{fit}"
    unavailable = next(
        (item for item in reversed(observations) if item.reason == "CATEGORY_UNAVAILABLE"),
        None,
    )
    if unavailable is not None:
        category = unavailable.normalized_query or "that category"
        return f'I checked current availability for "{category}" and found no active listings.'
    if any(item.reason == "FILTERS_TOO_STRICT" for item in observations):
        return "I did not find a verified current match in this search. You can adjust the request or ask me to check again."
    if any(item.reason == "AMBIGUOUS_REFERENCE" for item in observations):
        return "Which cart item do you want me to change?"
    if (
        any(
            item.reason == "RETURN_PREPARATION_REQUIRED"
            for item in observations
        )
        and not any(
            item.tool == "prepare_my_return_request"
            for item in observations
        )
    ):
        return (
            "I verified that the selected store group is eligible, but I could "
            "not prepare the return confirmation in this turn. No return request "
            "or refund was submitted."
        )
    return_results = tuple(
        item for item in observations
        if item.tool in {
            "get_my_return", "prepare_my_return_request",
            "submit_my_return_request",
        }
    )
    return_result = next((
        item for item in reversed(return_results)
        if item.reason != "DUPLICATE_TOOL_CALL"
    ), return_results[-1] if return_results else None)
    if return_result is not None:
        value = return_result.return_request
        if return_result.reason == "RETURN_REQUEST_SUBMITTED":
            status = (
                "requested" if value is None or value.status is None
                else value.status.replace("_", " ").lower()
            )
            return (
                f"Your return request was submitted and is {status}. This did not "
                "approve the return or issue a refund; the existing marketplace "
                "workflow will handle the next steps."
            )
        if return_result.reason == "RETURN_AVAILABLE" and value is not None:
            status = (value.status or "requested").replace("_", " ").lower()
            refund = (
                " Refund status is not available yet."
                if value.refund_status is None
                else f" Refund status is {value.refund_status.replace('_', ' ').lower()}."
            )
            return f"Your return is {status}.{refund}"
        if return_result.reason == "RETURN_ELIGIBLE":
            return (
                "That store group is currently eligible for a whole-group return. "
                "I only checked eligibility and did not submit a request."
            )
        return {
            "RETURN_REQUIRES_DELIVERY": (
                "That store group must be delivered before it can enter the return workflow."
            ),
            "RETURN_CANCELLED_GROUP": (
                "That store group was cancelled and is not eligible for a return request."
            ),
            "RETURN_WINDOW_EXPIRED": "The return window for that store group has closed.",
            "RETURN_ALREADY_EXISTS": (
                "A return request already exists for that store group. "
                "I did not submit another one."
            ),
            "RETURN_GROUP_AMBIGUOUS": (
                "Which store group or purchased item do you want me to check?"
            ),
            "RETURN_ITEM_NOT_FOUND": (
                "I couldn't match that item to the selected order."
            ),
            "PARTIAL_RETURN_UNSUPPORTED": (
                "This return workflow can only submit the entire store group, "
                "not only selected items. I did not prepare or submit a request."
            ),
            "ORDER_NOT_FOUND": "I couldn't find that order in your account.",
            "FORBIDDEN": "I couldn't find an order you can access with that reference.",
            "RETURN_VERSION_CONFLICT": (
                "That order group changed, so I did not submit the return request. "
                "Please review it again."
            ),
            "RETURN_IDEMPOTENCY_CONFLICT": (
                "I couldn't safely replay that return request. Please check its "
                "current status before trying again."
            ),
            "AUTHENTICATION_REQUIRED": (
                "Please sign in again before checking or requesting a return."
            ),
            "OUTCOME_UNKNOWN": (
                "I couldn't confirm whether the return request was submitted. "
                "Please check its status before trying again."
            ),
        }.get(
            return_result.reason,
            "The return workflow is temporarily unavailable. No refund was issued.",
        )
    cancellation_result = next((
        item for item in reversed(observations)
        if item.tool == "cancel_my_order"
    ), None)
    if cancellation_result is not None:
        return {
            "ORDER_CANCELLATION_REQUESTED": (
                "Your cancellation request was accepted. The marketplace will handle "
                "the order cancellation, inventory release, and any local demo refund."
            ),
            "ORDER_CANCELLATION_COMPLETED": (
                "Your order cancellation is complete. The marketplace completed the "
                "inventory release and local demo refund workflow."
            ),
            "ORDER_VERSION_CONFLICT": (
                "That order changed after you reviewed it, so I did not request "
                "cancellation. Please review the current order and try again."
            ),
            "ORDER_CANCELLATION_FULFILLMENT_STARTED": (
                "Fulfillment has started, so this order can no longer be cancelled "
                "through the customer cancellation flow."
            ),
            "ORDER_CANCELLATION_WINDOW_CLOSED": (
                "The cancellation window for this order has closed."
            ),
            "ORDER_CANCELLATION_NOT_ALLOWED": (
                "This order is not eligible for customer cancellation."
            ),
            "ORDER_CANCELLATION_ALREADY_REQUESTED": (
                "A cancellation request already exists for this order. I did not "
                "create another one."
            ),
            "AUTHENTICATION_REQUIRED": (
                "Please sign in again. I did not request order cancellation."
            ),
            "OUTCOME_UNKNOWN": (
                "I couldn't confirm the cancellation outcome. Do not submit another "
                "request yet; check the order first."
            ),
        }.get(
            cancellation_result.reason,
            "Order cancellation is temporarily unavailable. The Agent did not "
            "report the order as cancelled.",
        )
    cancellation_preview = next((
        item for item in reversed(observations)
        if item.tool == "preview_my_order_cancellation"
    ), None)
    if cancellation_preview is not None:
        return {
            "ORDER_NOT_FOUND": "I couldn't find that order in your account.",
            "ORDER_CANCELLATION_FULFILLMENT_STARTED": (
                "Fulfillment has started, so this order is no longer eligible for "
                "customer cancellation."
            ),
            "ORDER_CANCELLATION_WINDOW_CLOSED": (
                "The cancellation window for this order has closed."
            ),
            "ORDER_CANCELLATION_NOT_ALLOWED": (
                "This order is not eligible for customer cancellation."
            ),
            "ORDER_CANCELLATION_ALREADY_REQUESTED": (
                "A cancellation request already exists for this order."
            ),
            "AUTHENTICATION_REQUIRED": (
                "Please sign in again before requesting order cancellation."
            ),
        }.get(
            cancellation_preview.reason,
            "Order cancellation is temporarily unavailable. The order was not changed.",
        )
    checkout_result = next((
        item for item in reversed(observations)
        if item.tool == "submit_my_checkout"
    ), None)
    if checkout_result is not None:
        if checkout_result.reason == "ORDER_CONFIRMED" and checkout_result.order is not None:
            status = checkout_result.order.status.replace("_", " ").lower()
            return (
                f"Your order was placed successfully and is {status}. "
                f"The authoritative total is {checkout_result.order.total.amount} "
                f"{checkout_result.order.total.currency}."
            )
        return {
            "ORDER_CONFIRMATION_PENDING": (
                "Demo payment was accepted, but the order is still being confirmed. "
                "Do not pay again; check your Orders page shortly."
            ),
            "PAYMENT_OUTCOME_UNKNOWN": (
                "The payment outcome could not be confirmed. I did not retry the payment. "
                "Please check your checkout or Orders page before trying anything again."
            ),
            "PAYMENT_FAILED": (
                "Payment was not successful, so I did not report an order as placed."
            ),
            "CHECKOUT_STALE": (
                "The prepared checkout changed, so no payment or order was submitted."
            ),
            "PRICE_CHANGED": (
                "The authoritative price changed, so no payment or order was submitted. "
                "Please prepare a new checkout to review the current total."
            ),
            "ITEM_UNAVAILABLE": (
                "An item or reservation is no longer available, so no payment or order was submitted."
            ),
            "CONFIRMATION_REQUIRED": (
                "That checkout requires an exact confirmation before it can be submitted."
            ),
        }.get(
            checkout_result.reason,
            "I could not complete the checkout safely. No order success was reported.",
        )
    checkout_preparation_content = {
        "CHECKOUT_EMPTY": "Your current cart is empty, so there is nothing to check out.",
        "CHECKOUT_ADDRESS_REQUIRED": (
            "Add a saved delivery address in your account before preparing checkout."
        ),
        "PRICE_CHANGED": (
            "A current item price differs from the cart, so I did not prepare checkout. "
            "Review the cart before trying again."
        ),
        "ITEM_UNAVAILABLE": (
            "An item is no longer available in the requested quantity, so I did not "
            "prepare checkout."
        ),
        "CHECKOUT_STALE": (
            "The cart or checkout changed, so I did not continue. Please review the cart."
        ),
        "AUTHENTICATION_REQUIRED": "Please sign in again before preparing checkout.",
        "CHECKOUT_NOT_FOUND": "I couldn't find that checkout in your account.",
        "CHECKOUT_UPSTREAM_UNAVAILABLE": (
            "Checkout is temporarily unavailable. No payment or order was submitted."
        ),
    }
    checkout_preparations = tuple(
        item for item in observations
        if item.tool in {"prepare_my_checkout", "get_my_checkout"}
    )
    checkout_preparation = next((
        item for item in reversed(checkout_preparations)
        if item.reason in checkout_preparation_content
    ), checkout_preparations[-1] if checkout_preparations else None)
    if checkout_preparation is not None:
        return checkout_preparation_content.get(
            checkout_preparation.reason,
            "I could not prepare checkout safely. No payment or order was submitted.",
        )
    uncertain = next(
        (item for item in reversed(observations) if item.reason == "OUTCOME_UNKNOWN"),
        None,
    )
    if uncertain is not None:
        return (
            "I couldn't confirm whether the cart update completed. "
            "Please check your cart before trying again."
        )
    successful_mutation = next((
        item for item in reversed(observations)
        if item.tool in _CART_MUTATION_TOOLS
        and item.status == "SUCCEEDED"
        and item.cart is not None
    ), None)
    if successful_mutation is not None:
        action = {
            "add_to_my_cart": "added the item to",
            "update_my_cart_quantity": "updated the item quantity in",
            "remove_from_my_cart": "removed the item from",
        }[successful_mutation.tool]
        return (
            f"I {action} your cart. Your cart now has "
            f"{successful_mutation.cart.total_quantity} total item"
            f"{'s' if successful_mutation.cart.total_quantity != 1 else ''}."
        )
    cart_rejection_content = {
        "NOT_FOUND": "I couldn't find that item in your current cart.",
        "NOT_PURCHASABLE": (
            "The cart accepts only active business listings. "
            "This listing is not currently eligible."
        ),
        "OUT_OF_STOCK": "That quantity is not currently available.",
        "INVALID_QUANTITY": "Please choose a cart quantity from 1 through 999.",
        "CART_CONFLICT": "Your cart changed before I could update it. Please check the current cart and try again.",
        "CART_LIMIT_EXCEEDED": "Your cart has reached its item limit.",
        "IDEMPOTENCY_CONFLICT": "I couldn't safely replay that cart update. Please check your cart before trying again.",
        "AUTHENTICATION_REQUIRED": "Please sign in again before changing your cart.",
    }
    cart_rejections = tuple(
        item for item in observations
        if item.tool in _CART_MUTATION_TOOLS and item.status == "REJECTED"
    )
    cart_rejection = next((
        item for item in reversed(cart_rejections)
        if item.reason in cart_rejection_content
    ), cart_rejections[-1] if cart_rejections else None)
    if cart_rejection is not None:
        return cart_rejection_content.get(
            cart_rejection.reason,
            "I couldn't safely apply that cart update.",
        )
    cart_read = next((
        item for item in reversed(observations)
        if item.tool == "get_my_cart"
        and item.status == "SUCCEEDED"
        and item.cart is not None
    ), None)
    if cart_read is not None:
        cart = cart_read.cart
        if not cart.items:
            return "Your cart is empty."
        items = "; ".join(
            f"{item.title} (quantity {item.quantity})" for item in cart.items[:5]
        )
        remainder = f" and {len(cart.items) - 5} more" if len(cart.items) > 5 else ""
        totals = "; ".join(
            f"{total.amount} {total.currency}" for total in cart.totals
        )
        total_text = f" Current cart total: {totals}." if totals else ""
        noun = "item" if cart.item_count == 1 else "items"
        return f"Your cart has {cart.item_count} {noun}: {items}{remainder}.{total_text}"
    if any(item.status == "FAILED" for item in observations):
        return "I could not complete the marketplace check because the service is temporarily unavailable. Please try again."
    private = next((
        item for item in reversed(observations)
        if item.status == "SUCCEEDED"
        and item.tool in _PRIVATE_COMMERCE_TOOLS
    ), None)
    if private is not None:
        if private.tool in _CART_MUTATION_TOOLS and private.cart is not None:
            action = {
                "add_to_my_cart": "added the item to",
                "update_my_cart_quantity": "updated the item quantity in",
                "remove_from_my_cart": "removed the item from",
            }[private.tool]
            return (
                f"I {action} your cart. Your cart now has "
                f"{private.cart.total_quantity} total item"
                f"{'s' if private.cart.total_quantity != 1 else ''}."
            )
        if private.reason == "CART_EMPTY":
            return "Your current cart is empty."
        if private.cart is not None:
            return (
                f"Your current cart has {private.cart.item_count} item "
                f"type{'s' if private.cart.item_count != 1 else ''} and "
                f"{private.cart.total_quantity} total item"
                f"{'s' if private.cart.total_quantity != 1 else ''}."
            )
        if private.reason == "NO_ORDERS":
            return "I found no orders for your account."
        if private.orders:
            return (
                f"I found {len(private.orders)} recent order"
                f"{'s' if len(private.orders) != 1 else ''}."
            )
        if private.order is not None:
            status = private.order.status.replace("_", " ").lower()
            items = tuple(
                item
                for group in private.order.groups
                for item in group.items
            )
            total = private.order.total
            if not items:
                return (
                    f"Your order is currently {status}. Its recorded total is "
                    f"{total.amount} {total.currency}."
                )
            item_summary = "; ".join(
                f"{item.title} — quantity {item.quantity} at "
                f"{item.purchase_unit_price} {item.currency} each"
                for item in items[:5]
            )
            suffix = "" if len(items) <= 5 else f"; plus {len(items) - 5} more item(s)"
            return (
                f"Your order is currently {status}. Its recorded total is "
                f"{total.amount} {total.currency}. Purchase-time items: "
                f"{item_summary}{suffix}."
            )
        if private.reason == "ORDER_NOT_FOUND":
            return "I couldn't find that order in your account."
    if required_grounding == "KNOWLEDGE_RAG":
        return "I couldn't find an official marketplace document that answers that clearly."
    if required_grounding == "PRIVATE_TOOL":
        return (
            "I can't verify that account-specific status here. Please use the relevant "
            "account page or contact marketplace support."
        )
    if required_grounding == "LISTING_DATA" and any(
        item.reason == "GROUNDING_REQUIRED" for item in observations
    ):
        return "What marketplace listing or product would you like me to check?"
    return "I could not complete that request within this turn. Please ask a more focused marketplace question."


def _order_item_snapshot_requested(value: str) -> bool:
    """Recognize an order-detail request that requires purchase-time item facts."""

    normalized = " ".join(value.casefold().split())
    if not re.search(r"\border(?:s)?\b", normalized):
        return False
    return bool(
        re.search(
            r"\b(?:items?|contents?|details?|contain(?:s|ed)?|bought|purchased)\b",
            normalized,
        )
        or re.search(
            r"\bshow\s+me\s+(?:my\s+|the\s+)?"
            r"(?:first|second|third|last|latest|most\s+recent|that)\s+order\b",
            normalized,
        )
    )


def _commerce_listing_ids(
    observations: Sequence[ToolObservation],
) -> tuple[str, ...]:
    """Carries only purchase-snapshot listing references into later revalidation."""

    result: list[str] = []
    for observation in observations:
        result.extend(item.listing_id for item in observation.cart_item_references)
        if observation.cart_mutation_reference is not None:
            result.append(observation.cart_mutation_reference.listing_id)
        result.extend(
            item.listing_id for item in observation.order_item_references
        )
        if observation.order is None:
            continue
        for group in observation.order.groups:
            result.extend(item.listing_id for item in group.items)
    return tuple(dict.fromkeys(result))


def _customer_filter_summary(categories: Sequence[str]) -> str:
    """Translate allowlisted filter categories without exposing raw tool arguments."""

    labels: list[str] = []
    for category in categories:
        label = {
            "CATEGORY": "category",
            "CONDITION": "condition",
            "MINIMUM_PRICE": "price",
            "MAXIMUM_PRICE": "price",
            "CITY": "location",
            "COUNTY": "location",
        }.get(category)
        if label is not None and label not in labels:
            labels.append(label)
    if len(labels) < 2:
        return "" if not labels else labels[0]
    if len(labels) == 2:
        return f"{labels[0]} and {labels[1]}"
    return f"{', '.join(labels[:-1])}, and {labels[-1]}"
