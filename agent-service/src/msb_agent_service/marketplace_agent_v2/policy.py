from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field

from pydantic import ValidationError

from .capabilities import MarketplaceCustomerCapabilityBoundary
from .schemas import (
    AddToMyCartArguments,
    CheckAvailabilityArguments,
    CollectListingInformationArguments,
    GetMyCartArguments,
    GetMyCheckoutArguments,
    GetMyOrderArguments,
    GetMyReturnArguments,
    GetListingArguments,
    ListMyOrdersArguments,
    RemoveFromMyCartArguments,
    RetrieveHelpArguments,
    PrepareMyCheckoutArguments,
    PreviewMyOrderCancellationArguments,
    CancelMyOrderArguments,
    PrepareMyReturnRequestArguments,
    SubmitMyReturnRequestArguments,
    RequestConfirmationArguments,
    SearchListingsArguments,
    ToolName,
    GroundingRequirement,
    MarketplaceAgentV2ActiveWorkflow,
    ScopeCategory,
    ToolObservation,
    ToolProposal,
    UpdateMyCartQuantityArguments,
    SubmitMyCheckoutArguments,
)


_COMMERCE_READ_TOOLS = {"get_my_cart", "list_my_orders", "get_my_order"}
_CART_MUTATION_TOOLS = {
    "add_to_my_cart", "update_my_cart_quantity", "remove_from_my_cart",
}
_CHECKOUT_TOOLS = {
    "prepare_my_checkout", "get_my_checkout", "submit_my_checkout",
}
_ORDER_MUTATION_TOOLS = {
    "preview_my_order_cancellation", "cancel_my_order",
}
_RETURN_REQUEST_TOOLS = {
    "get_my_return", "prepare_my_return_request", "submit_my_return_request",
}
_PRIVATE_COMMERCE_TOOLS = (
    _COMMERCE_READ_TOOLS | _CART_MUTATION_TOOLS | _CHECKOUT_TOOLS
    | _ORDER_MUTATION_TOOLS
    | _RETURN_REQUEST_TOOLS
)


@dataclass
class MarketplaceAgentV2ToolPolicy:
    """Validates model proposals without selecting a replacement action."""

    referenced_listing_ids: frozenset[str]
    cancelled: bool = False
    prior_observations: tuple[ToolObservation, ...] = ()
    comparison_context: bool = False
    confirmation_without_pending: bool = False
    message_scope: ScopeCategory = "IN_SCOPE"
    required_grounding: GroundingRequirement = "LISTING_DATA"
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None
    seller_search_allowed: bool = False
    required_search_query: str | None = None
    required_search_category_name: str | None = None
    required_availability_categories: frozenset[str] = frozenset()
    cart_mutation_reference_ambiguous: bool = False
    order_cancellation_mutation_requested: bool = False
    return_request_mutation_requested: bool = False
    partial_return_requested: bool = False
    required_return_reason: str | None = None
    return_comment_source: str = ""
    seen_calls: set[str] = field(default_factory=set)
    successful_read_calls: set[str] = field(default_factory=set)
    last_call_fingerprint: str | None = None
    successful_order_ids_this_turn: set[str] = field(default_factory=set)
    search_executed: bool = False
    cart_mutation_attempted: bool = False
    order_cancellation_attempted: bool = False
    return_preparation_attempted: bool = False
    knowledge_retrieval_attempted: bool = False
    orders_listed_this_turn: bool = False
    order_ids_read_this_turn: set[str] = field(default_factory=set)
    return_targets_read_this_turn: set[tuple[str, str, str]] = field(
        default_factory=set
    )
    skill_allowed_tools: frozenset[str] | None = None
    return_eligible_this_turn: bool = False
    return_blocked_this_turn: bool = False
    capability_boundary: MarketplaceCustomerCapabilityBoundary = field(
        default_factory=MarketplaceCustomerCapabilityBoundary
    )

    def validate(self, proposal: ToolProposal, *, step: int) -> tuple[object | None, ToolObservation | None]:
        capability = self.capability_boundary.evaluate(proposal.tool)
        if not capability.allowed:
            reason = {
                "UNKNOWN_CAPABILITY": "UNKNOWN_TOOL",
                "CAPABILITY_DISABLED": "CAPABILITY_DISABLED",
                "SURFACE_MISMATCH": "SURFACE_MISMATCH",
            }[capability.code.value]
            return None, ToolObservation(
                tool=(
                    proposal.tool
                    if proposal.tool in self.capability_boundary.known_names
                    else "UNREGISTERED"
                ),
                status="REJECTED",
                reason=reason,
            )
        if (
            self.skill_allowed_tools is not None
            and proposal.tool not in self.skill_allowed_tools
        ):
            return None, self._rejection(
                proposal.tool, "SKILL_TOOL_NOT_ALLOWED"
            )
        if self.message_scope in {"OUT_OF_SCOPE", "CONVERSATIONAL"}:
            return None, self._rejection(
                proposal.tool, "MESSAGE_OUT_OF_MARKETPLACE_SCOPE"
            )
        if (
            proposal.tool != "collect_listing_information"
            and (
                self.required_grounding == "NONE"
                or (
                    self.required_grounding == "KNOWLEDGE_RAG"
                    and proposal.tool != "retrieve_help"
                )
                or (
                    self.required_grounding == "PRIVATE_TOOL"
                    and proposal.tool not in _PRIVATE_COMMERCE_TOOLS
                )
            )
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if (
            proposal.tool == "retrieve_help"
            and self.required_grounding != "KNOWLEDGE_RAG"
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if (
            proposal.tool in _COMMERCE_READ_TOOLS
            and self.required_grounding != "PRIVATE_TOOL"
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if (
            proposal.tool in _RETURN_REQUEST_TOOLS
            and self.required_grounding != "PRIVATE_TOOL"
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if (
            proposal.tool in _CART_MUTATION_TOOLS
            and self.required_grounding not in {"LISTING_DATA", "PRIVATE_TOOL"}
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if self.cancelled:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if self.confirmation_without_pending:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if proposal.tool in {
            "submit_my_checkout", "cancel_my_order", "submit_my_return_request",
        }:
            return None, self._rejection(proposal.tool, "CONFIRMATION_REQUIRED")
        if (
            proposal.tool == "preview_my_order_cancellation"
            and not self.order_cancellation_mutation_requested
        ):
            return None, self._rejection(
                proposal.tool, "MUTATION_INTENT_REQUIRED"
            )
        if (
            proposal.tool == "prepare_my_return_request"
            and not self.return_request_mutation_requested
        ):
            return None, self._rejection(
                proposal.tool, "MUTATION_INTENT_REQUIRED"
            )
        if (
            self.active_workflow is not None
            and self.active_workflow.type == "CREATE_LISTING"
            and self.active_workflow.status == "COLLECTING_INFORMATION"
            and proposal.tool in _CART_MUTATION_TOOLS
        ):
            return None, self._rejection(
                proposal.tool, "TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW"
            )
        if (
            self.active_workflow is not None
            and self.active_workflow.type == "CREATE_LISTING"
            and self.active_workflow.status == "COLLECTING_INFORMATION"
            and proposal.tool in {
                "check_availability", "search_listings", "get_listing",
                "request_confirmation",
            }
            and not self.seller_search_allowed
        ):
            return None, self._rejection(
                proposal.tool, "TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW"
            )
        if self.comparison_context and proposal.tool in {
            "check_availability", "search_listings", "request_confirmation",
        }:
            return None, self._rejection(proposal.tool, "COMPARISON_CONTEXT_REQUIRED")
        if proposal.tool == "search_listings" and self.search_executed:
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if proposal.tool == "retrieve_help" and self.knowledge_retrieval_attempted:
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if proposal.tool in _CART_MUTATION_TOOLS and self.cart_mutation_attempted:
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if (
            proposal.tool == "preview_my_order_cancellation"
            and self.order_cancellation_attempted
        ):
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if (
            proposal.tool == "prepare_my_return_request"
            and self.return_preparation_attempted
        ):
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if proposal.tool == "list_my_orders" and self.orders_listed_this_turn:
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        if (
            proposal.tool in _CART_MUTATION_TOOLS
            and self.cart_mutation_reference_ambiguous
        ):
            return None, self._rejection(proposal.tool, "AMBIGUOUS_REFERENCE")
        if not 1 <= step <= 5:
            return None, self._rejection(proposal.tool, "STEP_BUDGET_EXHAUSTED")
        try:
            if proposal.tool == "retrieve_help":
                arguments = RetrieveHelpArguments.model_validate(proposal.arguments)
            elif proposal.tool == "check_availability":
                arguments = CheckAvailabilityArguments.model_validate(proposal.arguments)
            elif proposal.tool == "search_listings":
                arguments = SearchListingsArguments.model_validate(proposal.arguments)
            elif proposal.tool == "request_confirmation":
                arguments = RequestConfirmationArguments.model_validate(proposal.arguments)
            elif proposal.tool == "collect_listing_information":
                arguments = CollectListingInformationArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "get_my_cart":
                arguments = GetMyCartArguments.model_validate(proposal.arguments)
            elif proposal.tool == "list_my_orders":
                arguments = ListMyOrdersArguments.model_validate(proposal.arguments)
            elif proposal.tool == "get_my_order":
                arguments = GetMyOrderArguments.model_validate(proposal.arguments)
            elif proposal.tool == "add_to_my_cart":
                arguments = AddToMyCartArguments.model_validate(proposal.arguments)
            elif proposal.tool == "update_my_cart_quantity":
                arguments = UpdateMyCartQuantityArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "remove_from_my_cart":
                arguments = RemoveFromMyCartArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "prepare_my_checkout":
                arguments = PrepareMyCheckoutArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "get_my_checkout":
                arguments = GetMyCheckoutArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "submit_my_checkout":
                arguments = SubmitMyCheckoutArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "preview_my_order_cancellation":
                arguments = PreviewMyOrderCancellationArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "cancel_my_order":
                arguments = CancelMyOrderArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "get_my_return":
                arguments = GetMyReturnArguments.model_validate(proposal.arguments)
            elif proposal.tool == "prepare_my_return_request":
                arguments = PrepareMyReturnRequestArguments.model_validate(
                    proposal.arguments
                )
            elif proposal.tool == "submit_my_return_request":
                arguments = SubmitMyReturnRequestArguments.model_validate(
                    proposal.arguments
                )
            else:
                arguments = GetListingArguments.model_validate(proposal.arguments)
        except ValidationError:
            return None, self._rejection(proposal.tool, "INVALID_ARGUMENTS")
        if (
            isinstance(arguments, GetMyOrderArguments)
            and not isinstance(arguments, GetMyReturnArguments)
            and arguments.order_id in self.order_ids_read_this_turn
        ):
            return None, self._rejection(
                proposal.tool,
                "READ_ALREADY_SATISFIED"
                if arguments.order_id in self.successful_order_ids_this_turn
                else "DUPLICATE_TOOL_CALL",
            )
        if isinstance(arguments, GetMyReturnArguments) and not isinstance(
            arguments, PrepareMyReturnRequestArguments
        ):
            return_target = (
                arguments.order_id,
                arguments.listing_id or "",
                _normalized_text(arguments.store_name or ""),
            )
            if return_target in self.return_targets_read_this_turn:
                return None, self._rejection(
                    proposal.tool, "DUPLICATE_TOOL_CALL"
                )
        if (
            isinstance(arguments, PrepareMyReturnRequestArguments)
            and (
                self.required_return_reason is None
                or arguments.reason_code != self.required_return_reason
            )
        ):
            return None, self._rejection(proposal.tool, "INVALID_ARGUMENTS")
        if (
            isinstance(arguments, PrepareMyReturnRequestArguments)
            and arguments.comment is not None
            and _normalized_text(arguments.comment)
            not in _normalized_text(self.return_comment_source)
        ):
            return None, self._rejection(proposal.tool, "INVALID_ARGUMENTS")
        if (
            isinstance(arguments, PrepareMyReturnRequestArguments)
            and self.partial_return_requested
            and not self._selected_item_is_entire_group(arguments)
        ):
            return None, self._rejection(
                proposal.tool, "PARTIAL_RETURN_UNSUPPORTED"
            )
        if isinstance(arguments, GetListingArguments) and (
            arguments.listing_id not in self.referenced_listing_ids
        ):
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if isinstance(arguments, (
            AddToMyCartArguments,
            UpdateMyCartQuantityArguments,
            RemoveFromMyCartArguments,
        )) and arguments.listing_id not in self.referenced_listing_ids:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if (
            proposal.tool == "get_my_checkout"
            and isinstance(arguments, GetMyCheckoutArguments)
            and arguments.checkout_id not in {
                observation.checkout.checkout_id
                for observation in self.prior_observations
                if observation.checkout is not None
            }
        ):
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if (
            proposal.tool == "preview_my_order_cancellation"
            and isinstance(arguments, PreviewMyOrderCancellationArguments)
            and arguments.order_id not in {
                reference.order_id
                for observation in self.prior_observations
                for reference in observation.order_references
            }
        ):
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if isinstance(arguments, (GetMyReturnArguments, PrepareMyReturnRequestArguments)):
            owned_orders = {
                reference.order_id
                for observation in self.prior_observations
                for reference in observation.order_references
            }
            if arguments.order_id not in owned_orders:
                return None, self._rejection(proposal.tool, "FORBIDDEN")
            if arguments.listing_id is not None and not any(
                reference.order_id == arguments.order_id
                and reference.listing_id == arguments.listing_id
                for observation in self.prior_observations
                for reference in observation.order_item_references
            ):
                return None, self._rejection(proposal.tool, "FORBIDDEN")
            if arguments.store_name is not None and not any(
                reference.order_id == arguments.order_id
                and reference.store_name is not None
                and _normalized_text(reference.store_name)
                == _normalized_text(arguments.store_name)
                and (
                    arguments.listing_id is None
                    or reference.listing_id == arguments.listing_id
                )
                for observation in self.prior_observations
                for reference in observation.order_item_references
            ):
                return None, self._rejection(proposal.tool, "FORBIDDEN")
        if (
            isinstance(arguments, PrepareMyReturnRequestArguments)
            and not self.return_eligible_this_turn
        ):
            return None, self._rejection(
                proposal.tool, "RETURN_ELIGIBILITY_REQUIRED"
            )
        if (
            isinstance(arguments, SearchListingsArguments)
            and self.required_search_query is not None
            and _normalized_text(arguments.query)
            != _normalized_text(self.required_search_query)
        ):
            return None, self._rejection(
                proposal.tool, "GROUNDING_TOOL_REQUIRED"
            )
        if (
            isinstance(arguments, CheckAvailabilityArguments)
            and self.required_availability_categories
            and _normalized_text(arguments.category)
            not in self.required_availability_categories
        ):
            return None, self._rejection(
                proposal.tool, "GROUNDING_TOOL_REQUIRED"
            )
        if (
            isinstance(arguments, SearchListingsArguments)
            and self.required_search_category_name is not None
            and (
                arguments.category_id is not None
                or _normalized_text(arguments.category_name or "")
                != _normalized_text(self.required_search_category_name)
            )
        ):
            return None, self._rejection(
                proposal.tool, "GROUNDING_TOOL_REQUIRED"
            )
        if isinstance(arguments, RequestConfirmationArguments) and not self.referenced_listing_ids:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if isinstance(arguments, CollectListingInformationArguments) and (
            self.active_workflow is not None
            and self.active_workflow.status != "CANCELLED"
        ):
            return None, self._rejection(
                proposal.tool, "TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW"
            )
        fingerprint = hashlib.sha256(
            json.dumps(
                {"tool": proposal.tool, "arguments": arguments.model_dump(mode="json")},
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        ).hexdigest()
        if fingerprint in self.seen_calls:
            return None, self._rejection(
                proposal.tool,
                "READ_ALREADY_SATISFIED"
                if fingerprint in self.successful_read_calls
                else "DUPLICATE_TOOL_CALL",
            )
        self.seen_calls.add(fingerprint)
        self.last_call_fingerprint = fingerprint
        if isinstance(arguments, ListMyOrdersArguments):
            self.orders_listed_this_turn = True
        elif (
            isinstance(arguments, GetMyOrderArguments)
            and not isinstance(arguments, GetMyReturnArguments)
        ):
            self.order_ids_read_this_turn.add(arguments.order_id)
        elif isinstance(arguments, GetMyReturnArguments) and not isinstance(
            arguments, PrepareMyReturnRequestArguments
        ):
            self.return_targets_read_this_turn.add((
                arguments.order_id,
                arguments.listing_id or "",
                _normalized_text(arguments.store_name or ""),
            ))
        return arguments, None

    def record(self, observation: ToolObservation) -> None:
        """Prevent another Product search after this turn reached a terminal search fact."""

        self.prior_observations = (*self.prior_observations, observation)[-5:]
        if observation.tool in {
            "get_listing", "check_availability", "get_my_checkout", "get_my_order",
        } and observation.status == "SUCCEEDED" and self.last_call_fingerprint and (
            observation.tool == "check_availability"
            or observation.tool == "get_listing" and bool(observation.attachments)
            or observation.tool == "get_my_checkout" and observation.checkout is not None
            or observation.tool == "get_my_order" and observation.order is not None
        ):
            self.successful_read_calls.add(self.last_call_fingerprint)
            if observation.tool == "get_my_order" and observation.order is not None:
                self.successful_order_ids_this_turn.add(observation.order.order_id)
        if observation.tool in _CART_MUTATION_TOOLS | {
            "submit_my_checkout", "cancel_my_order", "submit_my_return_request",
        } and observation.status == "SUCCEEDED":
            # A state-changing event may require an exact same-target revalidation.
            self.seen_calls.difference_update(self.successful_read_calls)
            self.successful_read_calls.clear()
            self.order_ids_read_this_turn.clear()
            self.successful_order_ids_this_turn.clear()
        if observation.tool == "get_my_cart" and observation.status == "SUCCEEDED":
            # A model-first cart read may resolve a named or ordinal cart target in
            # the same turn. Trust only the actor-owned references returned by the
            # Order Service; never admit a model-invented listing identifier.
            self.referenced_listing_ids = frozenset(
                set(self.referenced_listing_ids)
                | {
                    item.listing_id
                    for item in observation.cart_item_references
                }
            )
        if observation.tool == "search_listings" and observation.status in {
            "SUCCEEDED", "FAILED",
        }:
            self.search_executed = True
        if observation.tool == "retrieve_help":
            self.knowledge_retrieval_attempted = True
        if observation.tool in _CART_MUTATION_TOOLS:
            self.cart_mutation_attempted = True
        if observation.tool == "preview_my_order_cancellation":
            self.order_cancellation_attempted = True
        if observation.tool == "prepare_my_return_request":
            self.return_preparation_attempted = True
            if not (
                observation.status == "SUCCEEDED"
                and observation.reason == "RETURN_REQUEST_READY"
            ):
                self.return_blocked_this_turn = True
        if observation.tool == "get_my_return":
            if (
                observation.status == "SUCCEEDED"
                and observation.reason == "RETURN_ELIGIBLE"
            ):
                self.return_eligible_this_turn = True
            else:
                self.return_blocked_this_turn = True

    def _selected_item_is_entire_group(
        self, arguments: PrepareMyReturnRequestArguments
    ) -> bool:
        """Allow "only this item" when it is already the complete store group."""

        if arguments.listing_id is None:
            return False
        for observation in reversed(self.prior_observations):
            order = observation.order
            if order is None or order.order_id != arguments.order_id:
                continue
            matches = tuple(
                group for group in order.groups
                if any(
                    item.listing_id == arguments.listing_id
                    for item in group.items
                )
            )
            return len(matches) == 1 and len(matches[0].items) == 1
        return False

    @staticmethod
    def _rejection(tool: ToolName, reason: str) -> ToolObservation:
        return ToolObservation(tool=tool, status="REJECTED", reason=reason)


def _normalized_text(value: str) -> str:
    return " ".join(value.casefold().split())
