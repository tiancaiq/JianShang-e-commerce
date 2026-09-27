from __future__ import annotations

from datetime import UTC, datetime, timedelta
from math import ceil
from typing import Awaitable, Callable, Literal, cast

from pydantic import BaseModel

from msb_agent_service.marketplace_discovery import (
    CheckedListing,
    DiscoveryProductTool,
    DiscoverySearchRequest,
    DiscoverySearchSummary,
)
from msb_agent_service.marketplace_listing_retrieval import MarketplaceRetrievalError
from msb_agent_service.agent_persistence import new_ulid

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
    ListingAttachment,
    MarketplaceAgentV2PendingInteraction,
    RequestConfirmationArguments,
    RemoveFromMyCartArguments,
    PrepareMyCheckoutArguments,
    PreviewMyOrderCancellationArguments,
    CancelMyOrderArguments,
    PrepareMyReturnRequestArguments,
    SubmitMyReturnRequestArguments,
    SearchListingsArguments,
    ToolFacets,
    ToolName,
    ToolObservation,
    UpdateMyCartQuantityArguments,
    SubmitMyCheckoutArguments,
)
from .seller_workflow import start_create_listing_workflow
from .commerce import CommerceReadClient


ActivityCallback = Callable[[ToolName, str], Awaitable[None]]


class MarketplaceAgentV2ToolRegistry:
    """Defines the complete V2 tool schemas, activity labels, and executors."""

    def __init__(
        self,
        product: DiscoveryProductTool,
        *,
        commerce: CommerceReadClient | None = None,
        direct_result_max: int = 5,
        clarification_result_min: int = 10,
        max_clarification_options: int = 4,
        default_discovery_top_k: int = 5,
        max_discovery_top_k: int = 8,
        capability_boundary: MarketplaceCustomerCapabilityBoundary | None = None,
    ) -> None:
        self._product = product
        self._commerce = commerce
        self._direct_result_max = direct_result_max
        self._clarification_result_min = clarification_result_min
        self._max_clarification_options = max_clarification_options
        self._default_discovery_top_k = default_discovery_top_k
        self._max_discovery_top_k = max_discovery_top_k
        self.capability_boundary = (
            capability_boundary or MarketplaceCustomerCapabilityBoundary()
        )

    @property
    def names(self) -> tuple[ToolName, ...]:
        return cast(tuple[ToolName, ...], self.capability_boundary.enabled_names)

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        search_parameters = _strict_parameters(SearchListingsArguments)
        search_properties = search_parameters["properties"]
        assert isinstance(search_properties, dict)
        limit_schema = search_properties["limit"]
        assert isinstance(limit_schema, dict)
        limit_schema["description"] = (
            f"Return {self._default_discovery_top_k} listings by default; "
            f"choose an integer from 1 through {self._max_discovery_top_k}."
        )
        schemas = (
            {
                "type": "function",
                "name": "check_availability",
                "description": (
                    "Check authoritative current active inventory for the category of "
                    "an already-grounded listing when the customer asks whether it is "
                    "available, still available, or in stock. It may also probe one broad "
                    "marketplace category before detailed preferences. This is category-"
                    "level and count-only; it never proves exact listing stock or returns "
                    "recommendations."
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
                    "Revalidate current Product-owned details for exactly one listing "
                    "already referenced in this conversation. Use for ordinal, exact-title, "
                    "or unambiguous pronoun detail requests such as 'tell me more about the "
                    "second one'; never ask the customer for a listing ID."
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
                    "naturally asks for help selling or preparing a marketplace listing. "
                    "The first call collects the item type and starts the authenticated "
                    "CREATE_LISTING information workflow. It stores pending seller fields "
                    "only; it does not create, publish, or search listings."
                ),
                "strict": True,
                "parameters": _strict_parameters(CollectListingInformationArguments),
            },
            {
                "type": "function",
                "name": "get_my_cart",
                "description": (
                    "Read the authenticated customer's current cart. The customer "
                    "identity is application-owned and is never a tool argument. "
                    "Observed cart prices are advisory and are not purchase prices."
                ),
                "strict": True,
                "parameters": _strict_parameters(GetMyCartArguments),
            },
            {
                "type": "function",
                "name": "list_my_orders",
                "description": (
                    "List the authenticated customer's recent orders in newest-first "
                    "order. Use a returned opaque cursor only for explicit pagination."
                ),
                "strict": True,
                "parameters": _strict_parameters(ListMyOrdersArguments),
            },
            {
                "type": "function",
                "name": "get_my_order",
                "description": (
                    "Read one authenticated customer-owned order by order ID. Use it "
                    "to refresh current order status or inspect purchase-time item snapshots. "
                    "Reuse the selected owned order reference for immediate follow-ups instead "
                    "of listing orders again."
                ),
                "strict": True,
                "parameters": _strict_parameters(GetMyOrderArguments),
            },
            {
                "type": "function",
                "name": "add_to_my_cart",
                "description": (
                    "For an explicit, unambiguous request, set the referenced business "
                    "listing's cart quantity through the authenticated customer's cart. "
                    "Adding an existing item replaces its quantity; it does not increment."
                ),
                "strict": True,
                "parameters": _strict_parameters(AddToMyCartArguments),
            },
            {
                "type": "function",
                "name": "update_my_cart_quantity",
                "description": (
                    "For an explicit, unambiguous request, replace the quantity of one "
                    "referenced item already associated with the customer's cart context."
                ),
                "strict": True,
                "parameters": _strict_parameters(UpdateMyCartQuantityArguments),
            },
            {
                "type": "function",
                "name": "remove_from_my_cart",
                "description": (
                    "For an explicit, unambiguous request, remove one referenced listing "
                    "from the authenticated customer's cart. A pronoun is unambiguous when "
                    "the latest actor-owned cart read contains exactly one item or the latest "
                    "successful cart mutation identifies one item."
                ),
                "strict": True,
                "parameters": _strict_parameters(RemoveFromMyCartArguments),
            },
            {
                "type": "function",
                "name": "prepare_my_checkout",
                "description": (
                    "Prepare the authenticated customer's whole current business cart "
                    "using the default saved delivery address. This creates the normal "
                    "short-lived checkout reservation and returns authoritative totals "
                    "plus one exact durable payment confirmation. Never use for a "
                    "partial-cart request."
                ),
                "strict": True,
                "parameters": _strict_parameters(PrepareMyCheckoutArguments),
            },
            {
                "type": "function",
                "name": "get_my_checkout",
                "description": (
                    "Read one already-referenced checkout owned by the authenticated "
                    "customer. Use it for current checkout contents, total, status, or active/"
                    "expiry questions. Do not prepare a new checkout or submit it for a read. "
                    "The application supplies customer identity."
                ),
                "strict": True,
                "parameters": _strict_parameters(GetMyCheckoutArguments),
            },
            {
                "type": "function",
                "name": "submit_my_checkout",
                "description": (
                    "Submit one exact prepared checkout. Direct model proposals are "
                    "rejected; the application invokes this only after a durable "
                    "AI-CONF-01 claim. The payment provider and outcome are never inputs."
                ),
                "strict": True,
                "parameters": _strict_parameters(SubmitMyCheckoutArguments),
            },
            {
                "type": "function",
                "name": "preview_my_order_cancellation",
                "description": (
                    "Read one referenced authenticated customer-owned order, check "
                    "the Order Service cancellation eligibility projection, and prepare "
                    "one exact durable whole-order cancellation confirmation. Use only "
                    "for an explicit cancellation request, never an informational question."
                ),
                "strict": True,
                "parameters": _strict_parameters(
                    PreviewMyOrderCancellationArguments
                ),
            },
            {
                "type": "function",
                "name": "cancel_my_order",
                "description": (
                    "Request cancellation of one exact owned order. Direct model proposals "
                    "are rejected; the application invokes this only after a durable "
                    "AI-CONF-01 claim. Order Service owns all cancellation, inventory, "
                    "and refund processing."
                ),
                "strict": True,
                "parameters": _strict_parameters(CancelMyOrderArguments),
            },
            {
                "type": "function",
                "name": "get_my_return",
                "description": (
                    "Read the authenticated customer's existing return/refund-request "
                    "status, or current whole-store-group return eligibility. Resolve "
                    "the order and optional item only from owned order observations."
                ),
                "strict": True,
                "parameters": _strict_parameters(GetMyReturnArguments),
            },
            {
                "type": "function",
                "name": "prepare_my_return_request",
                "description": (
                    "Check authoritative eligibility and prepare one durable confirmation "
                    "for the exact owned whole-store-group return request. The reason is "
                    "required; the comment is optional. This does not approve a return or "
                    "issue a refund."
                ),
                "strict": True,
                "parameters": _strict_parameters(PrepareMyReturnRequestArguments),
            },
            {
                "type": "function",
                "name": "submit_my_return_request",
                "description": (
                    "Submit one exact confirmed customer return request. Direct model "
                    "proposals are rejected; the application invokes it only after a "
                    "durable AI-CONF-01 claim. It never approves or issues a refund."
                ),
                "strict": True,
                "parameters": _strict_parameters(SubmitMyReturnRequestArguments),
            },
        )
        return tuple(item for item in schemas if item["name"] in self.names)

    async def execute(
        self,
        *,
        tool: ToolName,
        arguments: object,
        actor_user_id: str,
        correlation_id: str,
        activity: ActivityCallback | None,
        actor_authorization: str | None = None,
        action_reference: str | None = None,
    ) -> ToolObservation:
        capability = self.capability_boundary.evaluate(tool)
        if not capability.allowed:
            return ToolObservation(
                tool=tool,
                status="REJECTED",
                reason=(
                    "CAPABILITY_DISABLED"
                    if capability.code.value == "CAPABILITY_DISABLED"
                    else "SURFACE_MISMATCH"
                ),
            )
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
            confirmation_id = new_ulid()
            pending = MarketplaceAgentV2PendingInteraction(
                id=confirmation_id,
                confirmationId=confirmation_id,
                type=arguments.type,
                action=arguments.action,
                arguments=search.model_dump(mode="json", by_alias=True),
                summary=_refined_search_confirmation_summary(search),
                status="WAITING",
                createdAt=datetime.now(UTC),
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
        if self._commerce is None and tool in {
            "get_my_cart", "list_my_orders", "get_my_order", "add_to_my_cart",
            "update_my_cart_quantity", "remove_from_my_cart",
            "prepare_my_checkout", "get_my_checkout", "submit_my_checkout",
            "preview_my_order_cancellation", "cancel_my_order",
            "get_my_return", "prepare_my_return_request",
            "submit_my_return_request",
        }:
            return ToolObservation(
                tool=tool, status="FAILED", reason="COMMERCE_UPSTREAM_UNAVAILABLE"
            )
        if tool == "get_my_cart" and isinstance(arguments, GetMyCartArguments):
            assert self._commerce is not None
            if activity is not None:
                await activity("get_my_cart", "Reading your current cart")
            return await self._commerce.get_my_cart(
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "list_my_orders" and isinstance(arguments, ListMyOrdersArguments):
            assert self._commerce is not None
            if activity is not None:
                await activity("list_my_orders", "Reading your recent orders")
            return await self._commerce.list_my_orders(
                limit=arguments.limit,
                cursor=arguments.cursor,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "get_my_order" and isinstance(arguments, GetMyOrderArguments):
            assert self._commerce is not None
            if activity is not None:
                await activity("get_my_order", "Reading your order")
            return await self._commerce.get_my_order(
                order_id=arguments.order_id,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "add_to_my_cart" and isinstance(arguments, AddToMyCartArguments):
            assert self._commerce is not None
            if activity is not None:
                await activity("add_to_my_cart", "Updating your cart")
            return await self._commerce.add_to_my_cart(
                listing_id=arguments.listing_id,
                quantity=arguments.quantity,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "update_my_cart_quantity" and isinstance(
            arguments, UpdateMyCartQuantityArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity("update_my_cart_quantity", "Updating your cart quantity")
            return await self._commerce.update_my_cart_quantity(
                listing_id=arguments.listing_id,
                quantity=arguments.quantity,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "remove_from_my_cart" and isinstance(
            arguments, RemoveFromMyCartArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity("remove_from_my_cart", "Removing the item from your cart")
            return await self._commerce.remove_from_my_cart(
                listing_id=arguments.listing_id,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "prepare_my_checkout" and isinstance(
            arguments, PrepareMyCheckoutArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity("prepare_my_checkout", "Preparing your checkout")
            return await self._commerce.prepare_my_checkout(
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "get_my_checkout" and isinstance(
            arguments, GetMyCheckoutArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity("get_my_checkout", "Reading your checkout")
            return await self._commerce.get_my_checkout(
                checkout_id=arguments.checkout_id,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "submit_my_checkout" and isinstance(
            arguments, SubmitMyCheckoutArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity("submit_my_checkout", "Submitting your checkout")
            return await self._commerce.submit_my_checkout(
                checkout_id=arguments.checkout_id,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "preview_my_order_cancellation" and isinstance(
            arguments, PreviewMyOrderCancellationArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity(
                    "preview_my_order_cancellation",
                    "Checking order cancellation eligibility",
                )
            return await self._commerce.preview_my_order_cancellation(
                order_id=arguments.order_id,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "cancel_my_order" and isinstance(
            arguments, CancelMyOrderArguments
        ):
            return ToolObservation(
                tool="cancel_my_order", status="REJECTED",
                reason="CONFIRMATION_REQUIRED",
            )
        if tool == "get_my_return" and isinstance(arguments, GetMyReturnArguments):
            assert self._commerce is not None
            if activity is not None:
                await activity("get_my_return", "Reading your return status")
            return await self._commerce.get_my_return(
                order_id=arguments.order_id,
                listing_id=arguments.listing_id,
                store_name=arguments.store_name,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "prepare_my_return_request" and isinstance(
            arguments, PrepareMyReturnRequestArguments
        ):
            assert self._commerce is not None
            if activity is not None:
                await activity(
                    "prepare_my_return_request", "Checking return eligibility"
                )
            return await self._commerce.prepare_my_return_request(
                order_id=arguments.order_id,
                listing_id=arguments.listing_id,
                store_name=arguments.store_name,
                reason_code=arguments.reason_code,
                comment=arguments.comment,
                action_reference=action_reference,
                authorization=actor_authorization,
                correlation_id=correlation_id,
            )
        if tool == "submit_my_return_request" and isinstance(
            arguments, SubmitMyReturnRequestArguments
        ):
            return ToolObservation(
                tool="submit_my_return_request", status="REJECTED",
                reason="CONFIRMATION_REQUIRED",
            )
        return ToolObservation(tool=tool, status="REJECTED", reason="INVALID_ARGUMENTS")

    async def revalidate_checkout_confirmation(
        self,
        *,
        arguments: object,
        actor_authorization: str | None,
        correlation_id: str,
    ):
        from .schemas import SubmitCheckoutConfirmationArguments

        if self._commerce is None:
            return None
        try:
            binding = SubmitCheckoutConfirmationArguments.model_validate(arguments)
        except Exception:
            return None
        return await self._commerce.revalidate_checkout_confirmation(
            binding=binding,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

    async def cancel_checkout_confirmation(
        self,
        *,
        arguments: object,
        actor_authorization: str | None,
        correlation_id: str,
        action_reference: str | None,
    ) -> bool:
        """Releases a prepared checkout as application lifecycle cleanup."""

        from .schemas import SubmitCheckoutConfirmationArguments

        if self._commerce is None:
            return False
        try:
            binding = SubmitCheckoutConfirmationArguments.model_validate(arguments)
        except Exception:
            return False
        return await self._commerce.cancel_prepared_checkout(
            checkout_id=binding.checkout_id,
            action_reference=action_reference,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

    async def revalidate_order_cancellation_confirmation(
        self,
        *,
        arguments: object,
        actor_authorization: str | None,
        correlation_id: str,
    ):
        from .schemas import CancelOrderConfirmationArguments

        if self._commerce is None:
            return None
        try:
            binding = CancelOrderConfirmationArguments.model_validate(arguments)
        except Exception:
            return None
        return await self._commerce.revalidate_order_cancellation_confirmation(
            binding=binding,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

    async def execute_confirmed_order_cancellation(
        self,
        *,
        arguments: object,
        actor_user_id: str,
        actor_authorization: str | None,
        correlation_id: str,
        action_reference: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        """Execute only an application-owned immutable cancellation binding."""

        from .schemas import CancelOrderConfirmationArguments

        capability = self.capability_boundary.evaluate("cancel_my_order")
        if not capability.allowed or self._commerce is None:
            return ToolObservation(
                tool="cancel_my_order", status="REJECTED",
                reason="CAPABILITY_DISABLED",
            )
        try:
            binding = CancelOrderConfirmationArguments.model_validate(arguments)
        except Exception:
            return ToolObservation(
                tool="cancel_my_order", status="REJECTED",
                reason="INVALID_ARGUMENTS",
            )
        del actor_user_id
        if activity is not None:
            await activity("cancel_my_order", "Requesting order cancellation")
        return await self._commerce.cancel_my_order(
            order_id=binding.order_id,
            expected_version=binding.order_version,
            action_reference=action_reference,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

    async def revalidate_return_confirmation(
        self,
        *,
        arguments: object,
        actor_authorization: str | None,
        correlation_id: str,
    ):
        from .schemas import SubmitReturnConfirmationArguments

        if self._commerce is None:
            return None
        try:
            binding = SubmitReturnConfirmationArguments.model_validate(arguments)
        except Exception:
            return None
        return await self._commerce.revalidate_return_confirmation(
            binding=binding,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

    async def execute_confirmed_return_request(
        self,
        *,
        arguments: object,
        actor_user_id: str,
        actor_authorization: str | None,
        correlation_id: str,
        action_reference: str,
        activity: ActivityCallback | None,
    ) -> ToolObservation:
        """Execute only the application-owned immutable customer request binding."""

        from .schemas import SubmitReturnConfirmationArguments

        capability = self.capability_boundary.evaluate("submit_my_return_request")
        if not capability.allowed or self._commerce is None:
            return ToolObservation(
                tool="submit_my_return_request", status="REJECTED",
                reason="CAPABILITY_DISABLED",
            )
        try:
            binding = SubmitReturnConfirmationArguments.model_validate(arguments)
        except Exception:
            return ToolObservation(
                tool="submit_my_return_request", status="REJECTED",
                reason="INVALID_ARGUMENTS",
            )
        del actor_user_id
        if activity is not None:
            await activity(
                "submit_my_return_request", "Submitting your return request"
            )
        return await self._commerce.submit_my_return_request(
            binding=binding,
            action_reference=action_reference,
            authorization=actor_authorization,
            correlation_id=correlation_id,
        )

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
            search_marketplace = getattr(
                self._product, "search_marketplace", self._product.search_individual
            )
            page = await search_marketplace(
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


def _refined_search_confirmation_summary(
    arguments: SearchListingsArguments,
) -> str:
    """Derive customer confirmation prose from the immutable typed action."""

    details: list[str] = [f'for “{arguments.query}”']
    if arguments.category_name is not None:
        details.append(f'in category “{arguments.category_name}”')
    if arguments.condition is not None:
        details.append(f"in {arguments.condition.replace('_', ' ').title()} condition")
    if arguments.maximum_price is not None and arguments.currency is not None:
        details.append(
            f"at or below {arguments.maximum_price} {arguments.currency}"
        )
    if arguments.city is not None:
        details.append(f"near {arguments.city}")
    return "Run the prepared marketplace search " + ", ".join(details) + "."


def _strict_parameters(model: type[BaseModel]) -> dict[str, object]:
    """Expose a lean strict schema; Pydantic remains the executable validator."""

    schema = _provider_safe_schema(model.model_json_schema())
    properties = schema.get("properties")
    if not isinstance(properties, dict):
        raise RuntimeError("Marketplace Agent V2 tool schema has no properties")
    schema["required"] = list(properties)
    schema["additionalProperties"] = False
    return schema


def _provider_safe_schema(raw: object) -> dict[str, object]:
    """Remove generator-only constraints that can exhaust provider schema compilation."""

    if not isinstance(raw, dict):
        raise RuntimeError("Marketplace Agent V2 tool schema is invalid")
    any_of = raw.get("anyOf")
    if isinstance(any_of, list):
        variants = tuple(_provider_safe_schema(item) for item in any_of)
        primitive_types = tuple(
            item.get("type") for item in variants
            if set(item) == {"type"} and isinstance(item.get("type"), str)
        )
        if len(primitive_types) == len(variants):
            selected = tuple(dict.fromkeys(primitive_types))
            # Decimal accepts JSON numbers at the application boundary. Avoid a
            # redundant string alternative that materially expands the strict grammar.
            if "number" in selected and "string" in selected:
                selected = tuple(item for item in selected if item != "string")
            return {"type": list(selected)}
        return {"anyOf": list(variants)}

    result: dict[str, object] = {}
    for key in ("type", "enum", "const", "description"):
        if key in raw:
            result[key] = raw[key]
    properties = raw.get("properties")
    if isinstance(properties, dict):
        result["properties"] = {
            name: _provider_safe_schema(value)
            for name, value in properties.items()
        }
    items = raw.get("items")
    if isinstance(items, dict):
        result["items"] = _provider_safe_schema(items)
    required = raw.get("required")
    if isinstance(required, list):
        result["required"] = list(required)
    if result.get("type") == "object":
        result["additionalProperties"] = False
    return result


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
