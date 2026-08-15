from __future__ import annotations

from datetime import UTC, datetime, timedelta
from math import ceil
from typing import Awaitable, Callable, Literal

from pydantic import BaseModel

from msb_agent_service.marketplace_discovery import (
    CheckedListing,
    DiscoveryProductTool,
    DiscoverySearchRequest,
    DiscoverySearchSummary,
)
from msb_agent_service.marketplace_listing_retrieval import MarketplaceRetrievalError
from msb_agent_service.agent_persistence import new_ulid

from .schemas import (
    CheckAvailabilityArguments,
    CollectListingInformationArguments,
    GetListingArguments,
    ListingAttachment,
    MarketplaceAgentV2PendingInteraction,
    RequestConfirmationArguments,
    SearchListingsArguments,
    ToolFacets,
    ToolName,
    ToolObservation,
)
from .seller_workflow import start_create_listing_workflow


ActivityCallback = Callable[[ToolName, str], Awaitable[None]]


class MarketplaceAgentV2ToolRegistry:
    """Defines the complete V2 tool schemas, activity labels, and executors."""

    def __init__(
        self,
        product: DiscoveryProductTool,
        *,
        direct_result_max: int = 5,
        clarification_result_min: int = 10,
        max_clarification_options: int = 4,
        default_discovery_top_k: int = 5,
        max_discovery_top_k: int = 8,
    ) -> None:
        self._product = product
        self._direct_result_max = direct_result_max
        self._clarification_result_min = clarification_result_min
        self._max_clarification_options = max_clarification_options
        self._default_discovery_top_k = default_discovery_top_k
        self._max_discovery_top_k = max_discovery_top_k

    @property
    def names(self) -> tuple[ToolName, ...]:
        return (
            "check_availability", "search_listings", "get_listing",
            "request_confirmation", "collect_listing_information",
        )

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        search_parameters = _strict_parameters(SearchListingsArguments)
        search_properties = search_parameters["properties"]
        assert isinstance(search_properties, dict)
        limit_schema = search_properties["limit"]
        assert isinstance(limit_schema, dict)
        limit_schema["default"] = self._default_discovery_top_k
        limit_schema["maximum"] = self._max_discovery_top_k
        return (
            {
                "type": "function",
                "name": "check_availability",
                "description": (
                    "Check authoritative active inventory for one broad marketplace "
                    "category before asking detailed preference questions. This is a "
                    "count-only probe and never returns recommendations."
                ),
                "strict": True,
                "parameters": _strict_parameters(CheckAvailabilityArguments),
            },
            {
                "type": "function",
                "name": "search_listings",
                "description": (
                    "Search current public marketplace listings when current inventory "
                    "would help, including broad product requests. Returns Product-owned "
                    "revalidated totals, facets, and top listing facts."
                ),
                "strict": True,
                "parameters": search_parameters,
            },
            {
                "type": "function",
                "name": "get_listing",
                "description": (
                    "Revalidate one listing already referenced in this conversation."
                ),
                "strict": True,
                "parameters": _strict_parameters(GetListingArguments),
            },
            {
                "type": "function",
                "name": "request_confirmation",
                "description": (
                    "Prepare one persisted yes/no confirmation for a materially changed "
                    "refined search. Never use this to display listings, compare existing "
                    "recommendations, answer a question, or repeat a prior confirmation."
                ),
                "strict": True,
                "parameters": _strict_parameters(RequestConfirmationArguments),
            },
            {
                "type": "function",
                "name": "collect_listing_information",
                "description": (
                    "Start structured listing-information collection when the customer "
                    "wants help preparing an item for sale. This stores a pending seller "
                    "field only; it does not create, publish, or search listings."
                ),
                "strict": True,
                "parameters": _strict_parameters(CollectListingInformationArguments),
            },
        )

    async def execute(
        self,
        *,
        tool: ToolName,
        arguments: object,
        actor_user_id: str,
        correlation_id: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        if tool == "check_availability" and isinstance(arguments, CheckAvailabilityArguments):
            return await self._check_availability(
                arguments, actor_user_id=actor_user_id,
                correlation_id=correlation_id, activity=activity,
            )
        if tool == "search_listings" and isinstance(arguments, SearchListingsArguments):
            return await self._search(
                arguments, actor_user_id=actor_user_id,
                correlation_id=correlation_id, activity=activity,
            )
        if tool == "get_listing" and isinstance(arguments, GetListingArguments):
            return await self._get(
                arguments, actor_user_id=actor_user_id,
                correlation_id=correlation_id, activity=activity,
            )
        if tool == "request_confirmation" and isinstance(
            arguments, RequestConfirmationArguments
        ):
            # This control action performs no marketplace I/O and emits no activity.
            # It prepares an exact, bounded search action for a later single-use yes.
            search = SearchListingsArguments.model_validate({
                key: value for key, value in arguments.model_dump(mode="json").items()
                if key not in {"type", "action"}
            })
            pending = MarketplaceAgentV2PendingInteraction(
                id=new_ulid(), type=arguments.type, action=arguments.action,
                arguments=search.model_dump(mode="json", by_alias=True),
                status="WAITING", createdAt=datetime.now(UTC),
            )
            return ToolObservation(
                tool="request_confirmation", status="SUCCEEDED",
                reason="INTERACTION_READY", pendingInteraction=pending,
            )
        if tool == "collect_listing_information" and isinstance(
            arguments, CollectListingInformationArguments
        ):
            # This state-only action performs no Product, embedding, or provider I/O.
            workflow, pending = start_create_listing_workflow(
                initial_item_type=arguments.item_type
            )
            return ToolObservation(
                tool="collect_listing_information",
                status="SUCCEEDED",
                reason="WORKFLOW_READY",
                pendingInteraction=pending,
                activeWorkflow=workflow,
            )
        return ToolObservation(tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS")

    async def _check_availability(
        self,
        arguments: CheckAvailabilityArguments,
        *,
        actor_user_id: str,
        correlation_id: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        """Runs only Product's broad count probe; it never creates listing attachments."""

        if activity is not None:
            await activity("check_availability", "Checking current availability")
        try:
            probe = await self._product.probe_availability(
                actor_user_id=actor_user_id,
                category=arguments.category,
                correlation_id=correlation_id,
            )
        except Exception:
            return ToolObservation(
                tool="check_availability", status="FAILED",
                reason="SEARCH_UNAVAILABLE", normalizedQuery=arguments.category,
            )
        now = datetime.now(UTC)
        count = probe.total_active_category_inventory
        return ToolObservation(
            tool="check_availability",
            status="SUCCEEDED",
            reason="RESULTS_AVAILABLE" if count > 0 else "CATEGORY_UNAVAILABLE",
            observedAt=now,
            expiresAt=now + timedelta(minutes=5),
            normalizedQuery=probe.category,
            resultCount=0,
            broadInventoryCount=count,
            exactMatchCount=0,
        )

    async def _search(
        self,
        arguments: SearchListingsArguments,
        *,
        actor_user_id: str,
        correlation_id: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        if arguments.limit > self._max_discovery_top_k:
            return ToolObservation(
                tool="search_listings", status="REJECTED", reason="INVALID_ARGUMENTS"
            )
        if activity is not None:
            await activity("search_listings", "Searching current public listings")
        request = DiscoverySearchRequest(
            q=arguments.query,
            categoryId=arguments.category_id,
            condition=arguments.condition,
            minPrice=arguments.minimum_price,
            maxPrice=arguments.maximum_price,
            currency=arguments.currency,
            city=arguments.city,
            county=arguments.county,
            limit=arguments.limit,
        )
        try:
            page = await self._product.search_individual(
                actor_user_id=actor_user_id,
                request=request,
                correlation_id=correlation_id,
            )
            attachments: list[ListingAttachment] = []
            for candidate in page.data[: arguments.limit]:
                checked = await self._product.get_listing(
                    actor_user_id=actor_user_id,
                    listing_id=candidate.listing_id,
                    correlation_id=correlation_id,
                )
                if (
                    checked is not None
                    and checked.listing.quantity > 0
                    and (
                        arguments.category_name is None
                        or _normalized_text(checked.listing.category_name)
                        == _normalized_text(arguments.category_name)
                    )
                ):
                    attachments.append(_attachment(
                        checked, match_quality=candidate.match_quality
                    ))
            now = datetime.now(UTC)
            summary = page.summary
            # Product's results-first `totalMatches` counts revalidated hybrid
            # candidates before the stricter concept-relevance gate. It is not
            # an authoritative category-inventory count. Only the dedicated
            # availability probe may populate `broadInventoryCount`, and the
            # model-facing search total is the validated relevant-match count.
            broad_inventory_count: int | None = None
            reason = summary.reason if summary is not None else "RESULTS_AVAILABLE"
            if arguments.category_name is not None and not attachments:
                # Category names are Product-owned public facts on revalidated
                # listings, not free-text query terms. An empty category slice
                # means the selected filter is currently too strict; it is not
                # proof that the broad category has no active inventory.
                reason = "FILTERS_TOO_STRICT"
            if summary is None and not attachments:
                # A zero-result search is not enough to claim no inventory. The
                # Product-owned broad probe distinguishes inventory absence from
                # filters that excluded otherwise active listings.
                probe = await self._product.probe_availability(
                    actor_user_id=actor_user_id,
                    category=arguments.query,
                    correlation_id=correlation_id,
                )
                broad_inventory_count = probe.total_active_category_inventory
                reason = (
                    "CATEGORY_UNAVAILABLE"
                    if broad_inventory_count == 0
                    else "FILTERS_TOO_STRICT"
                )
            facets = _tool_facets(summary, self._max_clarification_options)
            presentation_hint = _presentation_hint(
                total_matches=(
                    len(attachments)
                    if arguments.category_name is not None
                    else (
                        summary.relevant_match_count
                        if summary is not None else len(attachments)
                    )
                ),
                confidence=(
                    summary.retrieval_confidence if summary is not None else "MEDIUM"
                ),
                facets=facets,
                direct_result_max=self._direct_result_max,
                clarification_result_min=self._clarification_result_min,
            )
            # Product has already concept-filtered and the Agent has revalidated
            # every item below. Result-set quality may suggest an optional
            # refinement, but it never permits hiding useful verified listings.
            displayed = (
                () if presentation_hint == "NO_RESULTS"
                else tuple(attachments[: self._max_discovery_top_k])
            )
            exact_matches = sum(item.match_quality == "EXACT" for item in displayed)
            related_matches = sum(item.match_quality == "RELATED" for item in displayed)
            if not exact_matches and not related_matches:
                exact_matches = min(
                    len(displayed),
                    summary.exact_match_count
                    if summary is not None and summary.exact_match_count is not None
                    else 0,
                )
                related_matches = len(displayed) - exact_matches
            # A small verified result set is a successful customer-facing tool
            # outcome. Keep Product's confidence in Product telemetry; do not
            # let that internal ranking diagnostic drive another model question.
            suppress_low_confidence = bool(
                presentation_hint == "DIRECT_RESULTS"
                and displayed
                and summary is not None
                and summary.retrieval_confidence == "LOW"
            )
            observation_reason = (
                "RESULTS_AVAILABLE"
                if suppress_low_confidence
                else reason
            )
            observation_confidence = (
                None
                if suppress_low_confidence
                else (
                    summary.retrieval_confidence
                    if summary is not None else "MEDIUM"
                )
            )
            return ToolObservation(
                tool="search_listings",
                status="SUCCEEDED",
                reason=observation_reason,
                observedAt=now,
                expiresAt=now + timedelta(minutes=5),
                normalizedQuery=arguments.query,
                filterCategories=_filter_categories(arguments),
                resultCount=len(attachments),
                broadInventoryCount=broad_inventory_count,
                exactMatchCount=exact_matches,
                relatedMatchCount=related_matches,
                rejectedCandidateCount=(
                    summary.rejected_candidate_count if summary is not None else None
                ),
                totalMatches=(
                    len(attachments)
                    if arguments.category_name is not None
                    else (
                        summary.relevant_match_count
                        if summary is not None else len(attachments)
                    )
                ),
                relevantMatchCount=(
                    len(attachments)
                    if arguments.category_name is not None
                    else (
                        summary.relevant_match_count
                        if summary is not None else len(attachments)
                    )
                ),
                retrievalConfidence=observation_confidence,
                presentationHint=presentation_hint,
                facets=facets,
                attachments=displayed,
            )
        except MarketplaceRetrievalError as error:
            return ToolObservation(
                tool="search_listings",
                status="FAILED",
                reason=_retrieval_failure_reason(error.code),
                normalizedQuery=arguments.query,
                filterCategories=_filter_categories(arguments),
            )
        except Exception:
            return ToolObservation(
                tool="search_listings",
                status="FAILED",
                reason="SEARCH_UNAVAILABLE",
                normalizedQuery=arguments.query,
                filterCategories=_filter_categories(arguments),
            )

    async def _get(
        self,
        arguments: GetListingArguments,
        *,
        actor_user_id: str,
        correlation_id: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        if activity is not None:
            await activity("get_listing", "Checking the current listing")
        try:
            checked = await self._product.get_listing(
                actor_user_id=actor_user_id,
                listing_id=arguments.listing_id,
                correlation_id=correlation_id,
            )
        except Exception:
            return ToolObservation(tool="get_listing", status="FAILED", reason="SEARCH_UNAVAILABLE")
        if checked is None or checked.listing.quantity <= 0:
            return ToolObservation(
                tool="get_listing", status="SUCCEEDED", reason="LISTING_NOT_FOUND",
                resultCount=0,
            )
        return ToolObservation(
            tool="get_listing", status="SUCCEEDED", reason="LISTING_VERIFIED",
            resultCount=1, attachments=(_attachment(checked),),
        )


def _strict_parameters(model: type[BaseModel]) -> dict[str, object]:
    """Make every declared field required by Responses strict mode; nullable fields stay nullable."""

    schema = model.model_json_schema()
    properties = schema.get("properties")
    if not isinstance(properties, dict) or not properties:
        raise RuntimeError("Marketplace Agent V2 tool schema has no properties")
    schema["required"] = list(properties)
    schema["additionalProperties"] = False
    return schema


def _tool_facets(
    summary: DiscoverySearchSummary | None,
    maximum: int,
) -> ToolFacets | None:
    if summary is None:
        return None

    def values(items: tuple[object, ...]) -> tuple[dict[str, object], ...]:
        return tuple(
            item.model_dump(mode="json")  # type: ignore[attr-defined]
            for item in items[:maximum]
        )

    return ToolFacets(
        subtype=values(summary.facets.subtype),
        condition=values(summary.facets.condition),
        priceBand=values(summary.facets.price_band),
        location=values(summary.facets.location),
    )


def _presentation_hint(
    *,
    total_matches: int,
    confidence: str,
    facets: ToolFacets | None,
    direct_result_max: int,
    clarification_result_min: int,
) -> str:
    """Classify result shape without choosing the model's customer-facing wording."""

    if total_matches == 0:
        return "NO_RESULTS"
    if total_matches <= direct_result_max or facets is None:
        return "DIRECT_RESULTS"
    if confidence == "LOW":
        return "LOW_CONFIDENCE"
    significant_minimum = max(2, ceil(total_matches * 0.15))
    significant = [
        item for item in facets.subtype if item.count >= significant_minimum
    ]
    dominant = bool(facets.subtype) and facets.subtype[0].count / total_matches >= 0.70
    if total_matches >= clarification_result_min and len(significant) >= 2 and not dominant:
        return "RESULTS_WITH_REFINEMENT"
    return "DIRECT_RESULTS"


def _retrieval_failure_reason(code: str) -> str:
    """Reduce allowlisted integration failures to stable provider/Product categories."""

    if "QUERY_EMBEDDING" in code:
        return "QUERY_EMBEDDING_UNAVAILABLE"
    if "PRODUCT" in code:
        return "PRODUCT_SEARCH_UNAVAILABLE"
    return "SEARCH_UNAVAILABLE"


def _attachment(
    checked: CheckedListing,
    *,
    match_quality: Literal["EXACT", "RELATED"] | None = None,
) -> ListingAttachment:
    listing = checked.listing
    thumbnail = listing.images[0].url if listing.images else None
    return ListingAttachment(
        listingId=listing.id,
        title=listing.title,
        categoryName=listing.category_name,
        condition=listing.condition,
        priceAmount=listing.price_amount,
        currency=listing.currency,
        publicCity=listing.public_city,
        publicRegion=listing.public_region,
        thumbnailUrl=thumbnail,
        checkedAt=checked.checked_at,
        responseHash=checked.response_hash,
        matchQuality=match_quality,
    )


def _filter_categories(arguments: SearchListingsArguments) -> tuple[str, ...]:
    categories = []
    for value, label in (
        (arguments.category_id, "CATEGORY"),
        (arguments.category_name, "CATEGORY"),
        (arguments.condition, "CONDITION"),
        (arguments.minimum_price, "MINIMUM_PRICE"),
        (arguments.maximum_price, "MAXIMUM_PRICE"),
        (arguments.city, "CITY"),
        (arguments.county, "COUNTY"),
    ):
        if value is not None:
            categories.append(label)
    return tuple(categories)


def _normalized_text(value: str) -> str:
    return " ".join(value.casefold().split())
