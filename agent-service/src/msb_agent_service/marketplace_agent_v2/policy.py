from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field

from pydantic import ValidationError

from .schemas import (
    CheckAvailabilityArguments,
    CollectListingInformationArguments,
    GetListingArguments,
    RequestConfirmationArguments,
    SearchListingsArguments,
    ToolName,
    GroundingRequirement,
    MarketplaceAgentV2ActiveWorkflow,
    ScopeCategory,
    ToolObservation,
    ToolProposal,
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
    seen_calls: set[str] = field(default_factory=set)
    search_executed: bool = False

    def validate(self, proposal: ToolProposal, *, step: int) -> tuple[object | None, ToolObservation | None]:
        if proposal.tool not in {
            "check_availability", "search_listings", "get_listing",
            "request_confirmation", "collect_listing_information",
        }:
            return None, ToolObservation(
                tool="UNREGISTERED", status="REJECTED", reason="UNKNOWN_TOOL"
            )
        if self.message_scope in {"OUT_OF_SCOPE", "CONVERSATIONAL"}:
            return None, self._rejection(
                proposal.tool, "MESSAGE_OUT_OF_MARKETPLACE_SCOPE"
            )
        if (
            proposal.tool != "collect_listing_information"
            and self.required_grounding in {"NONE", "KNOWLEDGE_RAG", "PRIVATE_TOOL"}
        ):
            return None, self._rejection(proposal.tool, "GROUNDING_TOOL_REQUIRED")
        if self.cancelled:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
        if self.confirmation_without_pending:
            return None, self._rejection(proposal.tool, "FORBIDDEN")
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
        if not 1 <= step <= 5:
            return None, self._rejection(proposal.tool, "STEP_BUDGET_EXHAUSTED")
        try:
            if proposal.tool == "check_availability":
                arguments = CheckAvailabilityArguments.model_validate(proposal.arguments)
            elif proposal.tool == "search_listings":
                arguments = SearchListingsArguments.model_validate(proposal.arguments)
            elif proposal.tool == "request_confirmation":
                arguments = RequestConfirmationArguments.model_validate(proposal.arguments)
            elif proposal.tool == "collect_listing_information":
                arguments = CollectListingInformationArguments.model_validate(
                    proposal.arguments
                )
            else:
                arguments = GetListingArguments.model_validate(proposal.arguments)
        except ValidationError:
            return None, self._rejection(proposal.tool, "INVALID_ARGUMENTS")
        if isinstance(arguments, GetListingArguments) and (
            arguments.listing_id not in self.referenced_listing_ids
        ):
            return None, self._rejection(proposal.tool, "FORBIDDEN")
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
            return None, self._rejection(proposal.tool, "DUPLICATE_TOOL_CALL")
        self.seen_calls.add(fingerprint)
        return arguments, None

    def record(self, observation: ToolObservation) -> None:
        """Prevent another Product search after this turn reached a terminal search fact."""

        if observation.tool == "search_listings" and observation.status in {
            "SUCCEEDED", "FAILED",
        }:
            self.search_executed = True

    @staticmethod
    def _rejection(tool: ToolName, reason: str) -> ToolObservation:
        return ToolObservation(tool=tool, status="REJECTED", reason=reason)


def _normalized_text(value: str) -> str:
    return " ".join(value.casefold().split())
