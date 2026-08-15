from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=lambda value: value.split("_")[0]
        + "".join(part.capitalize() for part in value.split("_")[1:]),
        populate_by_name=True,
        extra="forbid",
        strict=True,
    )


ToolName = Literal[
    "check_availability", "search_listings", "get_listing", "request_confirmation",
    "collect_listing_information",
]
ObservationStatus = Literal["SUCCEEDED", "REJECTED", "FAILED"]
ScopeCategory = Literal[
    "IN_SCOPE", "CONVERSATIONAL", "OUT_OF_SCOPE", "UNSAFE", "AMBIGUOUS"
]
ScopeConfidence = Literal["HIGH", "MEDIUM", "LOW"]
GroundingRequirement = Literal["NONE", "LISTING_DATA", "KNOWLEDGE_RAG", "PRIVATE_TOOL"]
EvidenceSourceType = Literal[
    "LISTING", "KNOWLEDGE_DOCUMENT", "ORDER", "ACCOUNT", "EXISTING_OBSERVATION"
]


class MarketplaceScopeResult(StrictModel):
    scope: ScopeCategory
    required_grounding: GroundingRequirement = "NONE"
    confidence: ScopeConfidence
    marketplace_context_used: bool
    reason_code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9_]+$")


class EvidenceReference(StrictModel):
    """Persists internal grounding identity without entering customer-visible prose."""

    source_type: EvidenceSourceType
    source_id: str = Field(min_length=1, max_length=160)
    version: str = Field(min_length=1, max_length=160)
    retrieved_at: datetime


class ListingAttachment(StrictModel):
    type: Literal["LISTING"] = "LISTING"
    listing_id: str = Field(min_length=26, max_length=26)
    title: str = Field(min_length=1, max_length=180)
    category_name: str = Field(min_length=1, max_length=180)
    condition: Literal[
        "NEW", "OPEN_BOX", "LIKE_NEW", "GOOD", "FAIR", "FOR_PARTS"
    ]
    price_amount: Decimal = Field(ge=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    public_city: str | None = Field(default=None, max_length=100)
    public_region: str | None = Field(default=None, max_length=100)
    thumbnail_url: str | None = Field(default=None, max_length=1_000)
    checked_at: datetime
    response_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    match_quality: Literal["EXACT", "RELATED"] | None = None


class ToolActivity(StrictModel):
    tool: ToolName
    status: ObservationStatus
    reason: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9_]+$")
    observed_at: datetime


class ToolFacetValue(StrictModel):
    value: str = Field(min_length=1, max_length=100)
    count: int = Field(ge=1, le=80)


class ToolFacets(StrictModel):
    subtype: tuple[ToolFacetValue, ...] = Field(default=(), max_length=4)
    condition: tuple[ToolFacetValue, ...] = Field(default=(), max_length=4)
    price_band: tuple[ToolFacetValue, ...] = Field(
        default=(), alias="priceBand", max_length=4
    )
    location: tuple[ToolFacetValue, ...] = Field(default=(), max_length=4)


class MarketplaceAgentV2RefinementOption(StrictModel):
    facet: Literal[
        "SUBTYPE", "CONDITION", "PRICE_BAND", "LOCATION",
        "MATCH_SCOPE", "MAXIMUM_PRICE",
    ]
    value: str = Field(min_length=1, max_length=100)
    count: int = Field(ge=1, le=80)


class MarketplaceAgentV2Refinement(StrictModel):
    # The backend owns safe action metadata only. Customer-facing follow-up prose
    # remains part of the model's terminal content.
    question: str | None = Field(default=None, min_length=1, max_length=240)
    options: tuple[MarketplaceAgentV2RefinementOption, ...] = Field(
        min_length=2, max_length=4
    )


class MarketplaceAgentV2PendingInteraction(StrictModel):
    id: str = Field(min_length=26, max_length=26, pattern=r"^[0-9A-HJKMNP-TV-Z]{26}$")
    type: Literal["CONFIRM_ACTION", "SELECT_OPTION", "ANSWER_FIELD"]
    action: Literal["SHOW_DETAILS", "COMPARE_LISTINGS", "RUN_REFINED_SEARCH"] | None = None
    workflow_type: Literal["CREATE_LISTING"] | None = None
    field: Literal[
        "ITEM_TYPE", "TITLE", "CONDITION", "PRICE", "DESCRIPTION", "LOCATION",
        "FULFILLMENT",
    ] | None = None
    question: str | None = Field(default=None, min_length=1, max_length=240)
    arguments: dict[str, object] = Field(default_factory=dict, max_length=12)
    # This workflow-control bit is persisted in session state and sent to the
    # provider context, but is not part of the public assistant-message DTO.
    accepts_replacement: bool = Field(default=False, exclude=True)
    status: Literal["WAITING", "CONSUMED", "CANCELLED"]
    created_at: datetime

    @model_validator(mode="after")
    def valid_interaction_shape(self) -> "MarketplaceAgentV2PendingInteraction":
        seller_field = self.type == "ANSWER_FIELD"
        if seller_field != bool(
            self.workflow_type == "CREATE_LISTING"
            and self.field is not None
            and self.question is not None
            and self.action is None
        ):
            raise ValueError("Invalid pending interaction shape")
        if not seller_field and self.action is None:
            raise ValueError("Action interactions require an action")
        if self.accepts_replacement and not (
            seller_field and self.field == "ITEM_TYPE"
        ):
            raise ValueError("Only an item-type field may accept a replacement")
        return self


class MarketplaceAgentV2SellerField(StrictModel):
    """Separates an answered seller field from deferred or help-needed state."""

    status: Literal[
        "MISSING", "PROVIDED", "REJECTED", "DEFERRED", "NEEDS_HELP"
    ] = "MISSING"
    value: str | None = Field(default=None, max_length=2_000)
    reason: Literal["UNSUPPORTED_CATEGORY"] | None = None

    @model_validator(mode="after")
    def valid_state(self) -> "MarketplaceAgentV2SellerField":
        if self.status in {"PROVIDED", "REJECTED"} and not bool(
            self.value and self.value.strip()
        ):
            raise ValueError("Provided or rejected seller fields require a value")
        if self.status not in {"PROVIDED", "REJECTED"} and self.value is not None:
            raise ValueError("Only provided or rejected seller fields may contain a value")
        if (self.status == "REJECTED") != (self.reason is not None):
            raise ValueError("Only rejected seller fields require a reason")
        return self


class MarketplaceAgentV2SellerFields(StrictModel):
    item_type: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    title: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    condition: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    price: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    description: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    location: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )
    fulfillment: MarketplaceAgentV2SellerField = Field(
        default_factory=MarketplaceAgentV2SellerField
    )

    @field_validator(
        "item_type", "title", "condition", "price", "description", "location",
        "fulfillment", mode="before",
    )
    @classmethod
    def accept_legacy_string_state(cls, value: object) -> object:
        """Reads P1-03 string fields while all new writes use explicit state."""

        if value is None:
            return {"status": "MISSING", "value": None}
        if isinstance(value, str):
            return {"status": "PROVIDED", "value": value}
        return value


class MarketplaceAgentV2ActiveWorkflow(StrictModel):
    """Keeps seller field collection explicit and independent from chat prose."""

    type: Literal["CREATE_LISTING"] = "CREATE_LISTING"
    status: Literal[
        "COLLECTING_INFORMATION", "READY_FOR_REVIEW", "CANCELLED", "UNSUPPORTED"
    ]
    collected_fields: MarketplaceAgentV2SellerFields = Field(
        default_factory=MarketplaceAgentV2SellerFields
    )
    last_updated_at: datetime
    last_resolved_user_message_id: str | None = Field(
        default=None, min_length=26, max_length=26,
        pattern=r"^[0-9A-HJKMNP-TV-Z]{26}$",
    )
    last_resolved_field: Literal[
        "ITEM_TYPE", "TITLE", "CONDITION", "PRICE", "DESCRIPTION", "LOCATION",
        "FULFILLMENT",
    ] | None = None
    last_reply_resolution: Literal[
        "VALUE_PROVIDED", "UNKNOWN", "DEFER", "REQUEST_HELP",
        "CANCEL_WORKFLOW", "CORRECTION", "REPLACE_FIELD_VALUE",
        "UNRELATED_OR_NEW_INTENT",
    ] | None = None
    item_type_eligibility: Literal["UNKNOWN", "SUPPORTED", "UNSUPPORTED"] = "UNKNOWN"


class MarketplaceAgentV2Message(StrictModel):
    role: Literal["ASSISTANT"] = "ASSISTANT"
    content: str = Field(min_length=1, max_length=12_000)
    attachments: tuple[ListingAttachment, ...] = Field(default=(), max_length=8)
    refinement: MarketplaceAgentV2Refinement | None = None
    pending_interaction: MarketplaceAgentV2PendingInteraction | None = None
    citations: tuple[str, ...] = Field(default=(), max_length=10)
    tool_activity: tuple[ToolActivity, ...] = Field(default=(), max_length=5)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def unique_attachments(self) -> "MarketplaceAgentV2Message":
        if len({item.listing_id for item in self.attachments}) != len(self.attachments):
            raise ValueError("Listing attachments must be unique")
        return self


class ToolProposal(StrictModel):
    call_id: str = Field(min_length=1, max_length=200)
    tool: str = Field(min_length=1, max_length=64, pattern=r"^[a-z_]+$")
    arguments: dict[str, object]


class ModelDecision(StrictModel):
    content: str | None = Field(default=None, min_length=1, max_length=12_000)
    tool_proposal: ToolProposal | None = None
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def exactly_one_decision(self) -> "ModelDecision":
        if (self.content is None) == (self.tool_proposal is None):
            raise ValueError("A model decision requires content or one tool proposal")
        return self


class ToolObservation(StrictModel):
    tool: Literal[
        "check_availability", "search_listings", "get_listing",
        "request_confirmation", "collect_listing_information", "DIRECT_RESPONSE",
        "UNREGISTERED",
    ]
    status: ObservationStatus
    reason: Literal[
        "RESULTS_AVAILABLE",
        "CATEGORY_UNAVAILABLE",
        "FILTERS_TOO_STRICT",
        "LOW_RELEVANCE",
        "LISTING_VERIFIED",
        "LISTING_NOT_FOUND",
        "SEARCH_UNAVAILABLE",
        "QUERY_EMBEDDING_UNAVAILABLE",
        "PRODUCT_SEARCH_UNAVAILABLE",
        "INVALID_ARGUMENTS",
        "FORBIDDEN",
        "UNKNOWN_TOOL",
        "DUPLICATE_TOOL_CALL",
        "STEP_BUDGET_EXHAUSTED",
        "INTERACTION_READY",
        "COMPARISON_CONTEXT_REQUIRED",
        "MESSAGE_OUT_OF_MARKETPLACE_SCOPE",
        "GROUNDING_REQUIRED",
        "GROUNDING_TOOL_REQUIRED",
        "WORKFLOW_READY",
        "TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW",
    ]
    observed_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    expires_at: datetime | None = None
    normalized_query: str | None = Field(default=None, max_length=200)
    filter_categories: tuple[str, ...] = Field(default=(), max_length=8)
    result_count: int | None = Field(default=None, ge=0, le=20)
    broad_inventory_count: int | None = Field(default=None, ge=0)
    exact_match_count: int | None = Field(default=None, ge=0, le=80)
    related_match_count: int | None = Field(default=None, ge=0, le=80)
    rejected_candidate_count: int | None = Field(default=None, ge=0, le=80)
    total_matches: int | None = Field(default=None, ge=0, le=80)
    relevant_match_count: int | None = Field(default=None, ge=0, le=80)
    retrieval_confidence: Literal["HIGH", "MEDIUM", "LOW"] | None = None
    presentation_hint: Literal[
        "NO_RESULTS",
        "DIRECT_RESULTS",
        "RESULTS_WITH_REFINEMENT",
        "LOW_CONFIDENCE",
        "FACET_CLARIFICATION",
    ] | None = None
    facets: ToolFacets | None = None
    attachments: tuple[ListingAttachment, ...] = Field(default=(), max_length=8)
    pending_interaction: MarketplaceAgentV2PendingInteraction | None = None
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None


class SearchListingsArguments(StrictModel):
    query: str = Field(min_length=1, max_length=200)
    category_id: str | None = Field(default=None, min_length=26, max_length=26)
    category_name: str | None = Field(default=None, min_length=1, max_length=180)
    condition: Literal[
        "NEW", "OPEN_BOX", "LIKE_NEW", "GOOD", "FAIR", "FOR_PARTS"
    ] | None = None
    # JSON tool arguments carry decimal values as JSON numbers or exact strings;
    # Pydantic performs only this bounded Decimal conversion inside the strict DTO.
    minimum_price: Decimal | None = Field(default=None, ge=0, strict=False)
    maximum_price: Decimal | None = Field(default=None, ge=0, strict=False)
    currency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    city: str | None = Field(default=None, min_length=1, max_length=100)
    county: str | None = Field(default=None, min_length=1, max_length=100)
    limit: int = Field(default=5, ge=1, le=8)

    @model_validator(mode="after")
    def valid_prices(self) -> "SearchListingsArguments":
        if (
            self.minimum_price is not None
            and self.maximum_price is not None
            and self.minimum_price > self.maximum_price
        ):
            raise ValueError("minimumPrice must not exceed maximumPrice")
        if (self.minimum_price is not None or self.maximum_price is not None) and (
            self.currency is None
        ):
            raise ValueError("Filtered prices require currency")
        return self


class RequestConfirmationArguments(SearchListingsArguments):
    type: Literal["CONFIRM_ACTION"] = "CONFIRM_ACTION"
    action: Literal["RUN_REFINED_SEARCH"] = "RUN_REFINED_SEARCH"


class CollectListingInformationArguments(StrictModel):
    field: Literal["ITEM_TYPE"] = "ITEM_TYPE"
    item_type: str | None = Field(default=None, min_length=1, max_length=160)


class CheckAvailabilityArguments(StrictModel):
    category: str = Field(min_length=1, max_length=100)


class GetListingArguments(StrictModel):
    listing_id: str = Field(min_length=26, max_length=26)


class MarketplaceAgentV2ContextualRefinement(StrictModel):
    """Carries one Product-grounded short refinement into the model decision."""

    facet: Literal["SUBTYPE", "CATEGORY"]
    value: str = Field(min_length=1, max_length=100)
    active_query: str = Field(min_length=1, max_length=200)
    search_query: str = Field(min_length=1, max_length=200)


class AgentContext(StrictModel):
    current_message: str = Field(min_length=1, max_length=8_000)
    recent_messages: tuple[dict[str, str], ...] = Field(default=(), max_length=12)
    referenced_listing_ids: tuple[str, ...] = Field(default=(), max_length=20)
    referenced_listings: tuple[ListingAttachment, ...] = Field(default=(), max_length=20)
    observations: tuple[ToolObservation, ...] = Field(default=(), max_length=5)
    pending_interaction: MarketplaceAgentV2PendingInteraction | None = None
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None
    contextual_refinement: MarketplaceAgentV2ContextualRefinement | None = None
    scope_result: MarketplaceScopeResult | None = None


class OrchestrationResult(StrictModel):
    message: MarketplaceAgentV2Message
    decision_count: int = Field(ge=0, le=5)
    observations: tuple[ToolObservation, ...] = Field(default=(), max_length=5)
    pending_interaction: MarketplaceAgentV2PendingInteraction | None = None
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None
    evidence: tuple[EvidenceReference, ...] = Field(default=(), max_length=20)
    scope_result: MarketplaceScopeResult = Field(default_factory=lambda: MarketplaceScopeResult(
        scope="IN_SCOPE",
        confidence="MEDIUM",
        marketplaceContextUsed=False,
        reasonCode="LEGACY_TEST_DEFAULT",
    ))
