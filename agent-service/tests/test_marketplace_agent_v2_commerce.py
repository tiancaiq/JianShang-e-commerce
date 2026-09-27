from __future__ import annotations

import unittest
import json
from pathlib import Path
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import httpx

from msb_agent_service.marketplace_discovery import CheckedListing, PublicIndividualListing

from msb_agent_service.marketplace_agent_v2.capabilities import (
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.commerce import CommerceReadClient
from msb_agent_service.marketplace_agent_v2.schemas import (
    CartItemSnapshot,
    CommerceMoney,
    CustomerCartItemReference,
    CustomerCartSnapshot,
    CustomerOrderDetail,
    CustomerOrderGroupDetail,
    CustomerOrderItemSnapshot,
    CustomerOrderItemReference,
    CustomerOrderReference,
    CustomerOrderSummary,
    GetMyCartArguments,
    GetMyOrderArguments,
    ListMyOrdersArguments,
    MarketplaceScopeResult,
    ModelDecision,
    ToolObservation,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry
from msb_agent_service.marketplace_agent_v2.service import _persistable_observation


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
ORDER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
TOKEN = "Bearer delegated-customer-token"
NOW = "2026-08-01T12:00:00Z"


class _NoProductCalls:
    async def __getattr__(self, _: str) -> object:
        raise AssertionError("commerce read reached Product Service")


def _boundary(*, commerce: bool) -> MarketplaceCustomerCapabilityBoundary:
    families = {
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
    }
    if commerce:
        families.add(CapabilityFamily.CUSTOMER_COMMERCE_READ)
    return MarketplaceCustomerCapabilityBoundary(frozenset(families))


def _order_detail() -> dict[str, object]:
    return {
        "orderId": ORDER,
        "status": "CONFIRMED",
        "paymentStatus": "SUCCEEDED",
        "totalAmount": 25,
        "currency": "USD",
        "version": 4,
        "createdAt": NOW,
        "updatedAt": NOW,
        "groups": [{
            "businessOrderId": "01ARZ3NDEKTSV4RRFFQ69G5FB0",
            "businessId": "01ARZ3NDEKTSV4RRFFQ69G5FB1",
            "storeId": "01ARZ3NDEKTSV4RRFFQ69G5FB2",
            "storeName": "Keyboard Store",
            "status": "SHIPPED",
            "totalAmount": 25,
            "currency": "USD",
            "version": 3,
            "items": [{
                "listingId": LISTING,
                "title": "Mechanical keyboard",
                "businessId": "01ARZ3NDEKTSV4RRFFQ69G5FB1",
                "storeId": "01ARZ3NDEKTSV4RRFFQ69G5FB2",
                "unitPrice": 25,
                "currency": "USD",
                "quantity": 1,
                "lineTotal": 25,
                "policyVersion": "PRIVATE_POLICY_VERSION",
            }],
            "timeline": [{"status": "SHIPPED", "occurredAt": NOW}],
            "shipment": {
                "shipmentId": "PRIVATE_SHIPMENT_ID",
                "source": "PRIVATE_SOURCE",
                "carrierDisplayName": "Parcel Co",
                "serviceDisplayName": "Ground",
                "trackingNumber": "PRIVATE_TRACKING_NUMBER",
                "status": "IN_TRANSIT",
                "version": 9,
                "shippedAt": NOW,
                "deliveredAt": None,
                "createdAt": NOW,
                "updatedAt": NOW,
            },
        }],
        "shippingAddress": {
            "label": "Home",
            "recipientName": "PRIVATE_RECIPIENT",
            "phone": "PRIVATE_PHONE",
            "line1": "PRIVATE_ADDRESS",
            "line2": None,
            "city": "Irvine",
            "region": "CA",
            "postalCode": "PRIVATE_POSTAL",
            "countryCode": "US",
        },
        "cancellation": None,
    }


class CommerceReadClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_empty_cart_and_order_history_are_explicit_successes(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == "/api/v1/cart":
                return httpx.Response(200, json={
                    "version": 0, "expiresAt": None, "itemCount": 0,
                    "totalQuantity": 0, "totals": [], "items": [],
                })
            return httpx.Response(200, json={
                "items": [], "page": {"nextCursor": None, "hasMore": False}
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        cart = await client.get_my_cart(
            authorization=TOKEN, correlation_id="empty-cart"
        )
        orders = await client.list_my_orders(
            limit=5, cursor=None, authorization=TOKEN,
            correlation_id="empty-orders",
        )

        self.assertEqual(("SUCCEEDED", "CART_EMPTY"), (cart.status, cart.reason))
        self.assertEqual(("SUCCEEDED", "NO_ORDERS"), (orders.status, orders.reason))

    async def test_dependency_timeout_is_never_reported_as_empty_or_not_found(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectTimeout("private timeout detail", request=request)

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.get_my_cart(
            authorization=TOKEN, correlation_id="cart-timeout"
        )

        self.assertEqual("FAILED", observation.status)
        self.assertEqual("COMMERCE_UPSTREAM_UNAVAILABLE", observation.reason)

    async def test_oversized_owner_response_fails_before_schema_or_model_context(self) -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, content=b"x" * 512_001, headers={
                "Content-Type": "application/json"
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.get_my_cart(
            authorization=TOKEN, correlation_id="cart-oversized"
        )

        self.assertEqual("COMMERCE_UPSTREAM_UNAVAILABLE", observation.reason)

    async def test_cart_read_relays_only_trusted_actor_credential_and_normalizes(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(TOKEN, request.headers["Authorization"])
            self.assertEqual("commerce-cart", request.headers["X-Correlation-Id"])
            self.assertEqual("/api/v1/cart", request.url.path)
            return httpx.Response(200, json={
                "version": 2,
                "expiresAt": NOW,
                "itemCount": 1,
                "totalQuantity": 2,
                "totals": [{"currency": "USD", "amount": 50}],
                "items": [{
                    "listingId": LISTING,
                    "title": "Mechanical keyboard",
                    "thumbnailUrl": "/private-media-path",
                    "storeName": "Keyboard Store",
                    "storeSlug": "private-store-slug",
                    "businessVerified": True,
                    "publicCity": "Irvine",
                    "publicRegion": "CA",
                    "quantity": 2,
                    "observedPrice": 25,
                    "currency": "USD",
                    "addedAt": NOW,
                }],
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.get_my_cart(
            authorization=TOKEN, correlation_id="commerce-cart"
        )

        self.assertEqual("CART_AVAILABLE", observation.reason)
        self.assertEqual(Decimal("25"), observation.cart.items[0].observed_price)
        serialized = observation.model_dump_json()
        for private in ("private-store-slug", "/private-media-path", "Irvine"):
            self.assertNotIn(private, serialized)

    async def test_order_detail_omits_address_tracking_and_internal_identifiers(self) -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_order_detail())

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.get_my_order(
            order_id=ORDER, authorization=TOKEN, correlation_id="commerce-order"
        )

        self.assertEqual("ORDER_FOUND", observation.reason)
        self.assertEqual("Mechanical keyboard", observation.order.groups[0].items[0].title)
        serialized = observation.model_dump_json()
        for private in (
            "PRIVATE_RECIPIENT", "PRIVATE_PHONE", "PRIVATE_ADDRESS",
            "PRIVATE_POSTAL", "PRIVATE_TRACKING_NUMBER", "PRIVATE_SHIPMENT_ID",
            "PRIVATE_POLICY_VERSION", "businessId", "storeId", "businessOrderId",
        ):
            self.assertNotIn(private, serialized)

    async def test_missing_and_cross_actor_order_are_indistinguishable(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(404, json={
                "error": {"code": "ORDER_NOT_FOUND", "message": "Order was not found."}
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        missing = await client.get_my_order(
            order_id=ORDER, authorization=TOKEN, correlation_id="missing"
        )
        cross_actor = await client.get_my_order(
            order_id=ORDER,
            authorization="Bearer another-customer-token",
            correlation_id="cross-actor",
        )

        self.assertEqual(missing.status, cross_actor.status)
        self.assertEqual(missing.reason, cross_actor.reason)
        self.assertEqual(missing.result_count, cross_actor.result_count)
        self.assertIsNone(missing.order)
        self.assertIsNone(cross_actor.order)
        self.assertEqual("ORDER_NOT_FOUND", missing.reason)

    async def test_disabled_downstream_order_reads_are_unavailable_not_not_found(self) -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(404, json={
                "error": {
                    "code": "ORDERS_NOT_AVAILABLE",
                    "message": "Buyer order reads are not available.",
                }
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.get_my_order(
            order_id=ORDER, authorization=TOKEN, correlation_id="orders-disabled"
        )

        self.assertEqual("FAILED", observation.status)
        self.assertEqual("COMMERCE_UPSTREAM_UNAVAILABLE", observation.reason)

    async def test_list_orders_preserves_owned_ordering_and_bounded_cursor(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual("3", request.url.params["limit"])
            self.assertEqual("opaque-v1-cursor", request.url.params["cursor"])
            return httpx.Response(200, json={
                "items": [{
                    "orderId": ORDER,
                    "status": "CONFIRMED",
                    "paymentStatus": "SUCCEEDED",
                    "totalAmount": 25,
                    "currency": "USD",
                    "createdAt": NOW,
                    "updatedAt": NOW,
                    "groups": [{
                        "businessOrderId": "private-group",
                        "businessId": "private-business",
                        "storeName": "Keyboard Store",
                        "status": "SHIPPED",
                        "totalAmount": 25,
                        "currency": "USD",
                    }],
                }],
                "page": {"nextCursor": "next-v1-cursor", "hasMore": True},
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        observation = await client.list_my_orders(
            limit=3, cursor="opaque-v1-cursor", authorization=TOKEN,
            correlation_id="commerce-list",
        )

        self.assertEqual("ORDERS_AVAILABLE", observation.reason)
        self.assertEqual((ORDER,), tuple(item.order_id for item in observation.orders))
        self.assertEqual("next-v1-cursor", observation.next_cursor)
        self.assertNotIn("private-business", observation.model_dump_json())


class CommerceReadRegistryTest(unittest.IsolatedAsyncioTestCase):
    def test_persisted_commerce_context_keeps_references_not_live_facts(self) -> None:
        live = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            resultCount=1,
            order=_detail(ORDER),
            orderReferences=(CustomerOrderReference(orderId=ORDER),),
            orderItemReferences=(CustomerOrderItemReference(
                orderId=ORDER, listingId=LISTING, title="Mechanical keyboard"
            ),),
        )

        persisted = _persistable_observation(live)

        self.assertIsNone(persisted.order)
        self.assertIsNone(persisted.result_count)
        self.assertEqual(ORDER, persisted.order_references[0].order_id)
        self.assertEqual(LISTING, persisted.order_item_references[0].listing_id)
        serialized = persisted.model_dump_json()
        self.assertNotIn("SHIPPED", serialized)
        self.assertNotIn("purchaseUnitPrice", serialized)

    def test_ai_com_00_eval_fixture_covers_reads_references_privacy_and_gate(self) -> None:
        fixture = json.loads(
            (Path(__file__).parents[1] / "evals" / "ai_com_00_customer_commerce_read_v1.json")
            .read_text(encoding="utf-8")
        )
        self.assertEqual(
            "AI_COM_00_CUSTOMER_COMMERCE_READ_EVAL_V1", fixture["schemaVersion"]
        )
        identifiers = {item["id"] for item in fixture["cases"]}
        self.assertTrue({
            "cart-current", "orders-recent-three", "order-last-follow-up",
            "order-second-follow-up", "order-item-current-listing",
            "order-item-ambiguous", "cross-actor-hidden",
            "generic-shipped-no-private-read", "commerce-gate-off",
            "mutation-stays-forbidden",
        }.issubset(identifiers))

    async def test_family_gate_removes_provider_policy_and_execution_access(self) -> None:
        client = CommerceReadClient("http://order-service", timeout_seconds=1)
        disabled = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=client, capability_boundary=_boundary(commerce=False)
        )
        self.assertNotIn("get_my_cart", disabled.names)
        self.assertNotIn("get_my_cart", str(disabled.provider_schemas()))

        result = await disabled.execute(
            tool="get_my_cart",
            arguments=GetMyCartArguments(),
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            correlation_id="disabled",
            activity=None,
        )
        self.assertEqual("CAPABILITY_DISABLED", result.reason)

    async def test_enabled_schemas_are_strict_and_never_accept_an_actor_field(self) -> None:
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(),
            commerce=CommerceReadClient("http://order-service", timeout_seconds=1),
            capability_boundary=_boundary(commerce=True),
        )
        schemas = {item["name"]: item for item in registry.provider_schemas()}

        self.assertEqual(
            {"get_my_cart", "list_my_orders", "get_my_order"},
            {name for name in schemas if name.startswith(("get_my_", "list_my_"))},
        )
        self.assertEqual({}, schemas["get_my_cart"]["parameters"]["properties"])
        serialized = str(tuple(schemas.values())).casefold()
        for actor_field in ("actoruserid", "actor_user_id", "userid", "user_id"):
            self.assertNotIn(actor_field, serialized)

    async def test_registry_passes_delegated_credential_outside_model_arguments(self) -> None:
        calls: list[dict[str, Any]] = []

        class _Commerce:
            async def get_my_cart(self, **kwargs: Any) -> Any:
                calls.append(kwargs)
                from msb_agent_service.marketplace_agent_v2.schemas import ToolObservation
                return ToolObservation(
                    tool="get_my_cart", status="SUCCEEDED", reason="CART_EMPTY"
                )

        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=_Commerce(), capability_boundary=_boundary(commerce=True)
        )
        await registry.execute(
            tool="get_my_cart", arguments=GetMyCartArguments(),
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            correlation_id="registry-token", activity=None,
        )

        self.assertEqual(TOKEN, calls[0]["authorization"])
        self.assertNotIn("actor_user_id", calls[0])

    def test_argument_contracts_are_narrow(self) -> None:
        self.assertEqual({}, GetMyCartArguments().model_dump())
        self.assertEqual(5, ListMyOrdersArguments().limit)
        self.assertEqual(ORDER, GetMyOrderArguments(orderId=ORDER).order_id)
        for model, payload in (
            (GetMyCartArguments, {"userId": ACTOR}),
            (ListMyOrdersArguments, {"limit": 5, "buyerId": ACTOR}),
            (GetMyOrderArguments, {"orderId": ORDER, "actorUserId": ACTOR}),
        ):
            with self.subTest(model=model.__name__):
                with self.assertRaises(Exception):
                    model.model_validate(payload)


class _DecisionModel:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[Any] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        return self.decisions.pop(0)

    async def close(self) -> None:
        return None


def _summary(order_id: str, created_at: str) -> CustomerOrderSummary:
    timestamp = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    return CustomerOrderSummary(
        orderId=order_id,
        status="CONFIRMED",
        paymentStatus="SUCCEEDED",
        total=CommerceMoney(amount=Decimal("25"), currency="USD"),
        createdAt=timestamp,
        updatedAt=timestamp,
    )


def _detail(order_id: str) -> CustomerOrderDetail:
    timestamp = datetime.fromisoformat(NOW.replace("Z", "+00:00"))
    return CustomerOrderDetail(
        orderId=order_id,
        status="SHIPPED",
        paymentStatus="SUCCEEDED",
        total=CommerceMoney(amount=Decimal("25"), currency="USD"),
        createdAt=timestamp,
        updatedAt=timestamp,
        groups=(CustomerOrderGroupDetail(
            storeName="Keyboard Store",
            status="SHIPPED",
            total=CommerceMoney(amount=Decimal("25"), currency="USD"),
            items=(CustomerOrderItemSnapshot(
                listingId=LISTING,
                title="Mechanical keyboard",
                quantity=1,
                purchaseUnitPrice=Decimal("25"),
                lineTotal=Decimal("25"),
                currency="USD",
            ),),
        ),),
    )


class _ConversationCommerce:
    def __init__(self) -> None:
        self.order_ids: list[str] = []

    async def list_my_orders(self, **_: Any) -> ToolObservation:
        first = _summary(ORDER, "2026-08-02T12:00:00Z")
        second = _summary("01ARZ3NDEKTSV4RRFFQ69G5FB3", "2026-08-01T12:00:00Z")
        return ToolObservation(
            tool="list_my_orders", status="SUCCEEDED", reason="ORDERS_AVAILABLE",
            orders=(first, second),
            orderReferences=(
                CustomerOrderReference(
                    orderId=first.order_id, position=1, createdAt=first.created_at
                ),
                CustomerOrderReference(
                    orderId=second.order_id, position=2, createdAt=second.created_at
                ),
            ),
            resultCount=2,
            hasMore=False,
        )

    async def get_my_order(self, *, order_id: str, **_: Any) -> ToolObservation:
        self.order_ids.append(order_id)
        return ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            order=_detail(order_id), resultCount=1,
            orderReferences=(CustomerOrderReference(orderId=order_id),),
            orderItemReferences=(CustomerOrderItemReference(
                orderId=order_id, listingId=LISTING, title="Mechanical keyboard"
            ),),
        )

    async def get_my_cart(self, **_: Any) -> ToolObservation:
        raise AssertionError("unexpected cart read")


class _CurrentListingProduct:
    def __init__(self) -> None:
        self.listing_ids: list[str] = []

    async def get_listing(self, **kwargs: Any) -> CheckedListing:
        self.listing_ids.append(kwargs["listing_id"])
        return CheckedListing(
            listing=PublicIndividualListing.model_validate({
                "id": LISTING,
                "sellerType": "INDIVIDUAL",
                "sellerDisplayName": "Seller",
                "sellerAvatarUrl": None,
                "storeId": None,
                "storeSlug": None,
                "storeName": None,
                "businessVerified": False,
                "categoryId": "01ARZ3NDEKTSV4RRFFQ69G5FB4",
                "categorySlug": "keyboards",
                "categoryName": "Keyboards",
                "title": "Mechanical keyboard",
                "description": "Current public listing",
                "condition": "GOOD",
                "conditionNotes": None,
                "priceAmount": "30.00",
                "currency": "USD",
                "negotiable": False,
                "quantity": 1,
                "publicCity": "Irvine",
                "publicRegion": "CA",
                "publishedAt": NOW,
                "transactionNotice": "Arrange directly.",
                "visitCount": 0,
                "likeCount": 0,
                "images": [],
            }),
            checkedAt=datetime.fromisoformat(NOW.replace("Z", "+00:00")),
            responseHash="a" * 64,
        )


class CommerceReadMultiTurnTest(unittest.IsolatedAsyncioTestCase):
    async def test_terse_cart_question_is_classified_and_executes_cart_read(self) -> None:
        class _CartCommerce:
            def __init__(self) -> None:
                self.calls = 0

            async def get_my_cart(self, **_: Any) -> ToolObservation:
                self.calls += 1
                return ToolObservation(
                    tool="get_my_cart",
                    status="SUCCEEDED",
                    reason="CART_EMPTY",
                    cart=CustomerCartSnapshot(
                        version=0, itemCount=0, totalQuantity=0, items=(),
                    ),
                )

        commerce = _CartCommerce()
        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="terse-cart-read", tool="get_my_cart", arguments={},
            )),
            ModelDecision(content="Your cart is empty."),
        ])
        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(commerce=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="what in my cart",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="terse-cart-read",
        )

        self.assertEqual("PRIVATE_TOOL", result.scope_result.required_grounding)
        self.assertEqual(1, commerce.calls)
        self.assertEqual("get_my_cart", result.observations[0].tool)
        self.assertEqual("CART_EMPTY", result.observations[0].reason)
        self.assertEqual("Your cart is empty.", result.message.content)

    async def test_cart_read_retries_an_unavailable_payment_offer_before_sse(self) -> None:
        class _CartCommerce:
            async def get_my_cart(self, **_: Any) -> ToolObservation:
                cart = CustomerCartSnapshot(
                    version=1, itemCount=1, totalQuantity=1,
                    items=(CartItemSnapshot(
                        listingId=LISTING, title="Mechanical keyboard",
                        storeName="Keyboard Store", quantity=1,
                        observedPrice=Decimal("25"), currency="USD",
                    ),),
                )
                return ToolObservation(
                    tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
                    cart=cart,
                    cartItemReferences=(CustomerCartItemReference(
                        listingId=LISTING, title="Mechanical keyboard", position=1,
                    ),),
                )

        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="cart-read", tool="get_my_cart", arguments={},
            )),
            ModelDecision(content=(
                "Your cart has one keyboard. Proceed to review shipping/payment."
            )),
            ModelDecision(content="Your cart has one Mechanical keyboard."),
        ])
        deltas: list[str] = []

        async def capture(delta: str) -> None:
            deltas.append(delta)

        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=_CartCommerce(),
                capability_boundary=_boundary(commerce=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="What's in my cart?",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="cart-read-payment-offer",
            text_delta=capture,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_MARKETPLACE_STATUS",
            ),
        )

        self.assertEqual(3, len(model.contexts))
        self.assertEqual("Your cart has one Mechanical keyboard.", result.message.content)
        self.assertEqual(["Your cart has one Mechanical keyboard."], deltas)

    async def test_order_item_reference_can_revalidate_current_public_listing(self) -> None:
        product = _CurrentListingProduct()
        registry = MarketplaceAgentV2ToolRegistry(
            product,
            commerce=_ConversationCommerce(),
            capability_boundary=_boundary(commerce=True),
        )
        prior = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            order=_detail(ORDER), resultCount=1,
            orderReferences=(CustomerOrderReference(orderId=ORDER),),
            orderItemReferences=(CustomerOrderItemReference(
                orderId=ORDER, listingId=LISTING, title="Mechanical keyboard"
            ),),
        )
        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="current-item", tool="get_listing",
                arguments={"listingId": LISTING},
            )),
            ModelDecision(content="The current public listing is still available."),
        ])
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Is that item still available now?",
            recent_messages=(("user", "Show my keyboard order"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(prior),),
            correlation_id="order-current-listing",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True,
                reasonCode="ORDER_ITEM_CURRENT_LISTING_FOLLOW_UP",
            ),
        )

        self.assertEqual([LISTING], product.listing_ids)
        self.assertEqual((LISTING,), tuple(item.listing_id for item in result.message.attachments))

    async def test_second_one_resolves_from_ordered_prior_refs_and_refetches_detail(self) -> None:
        commerce = _ConversationCommerce()
        boundary = _boundary(commerce=True)
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=commerce, capability_boundary=boundary
        )
        first_model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="orders-1", tool="list_my_orders",
                arguments={"cursor": None, "limit": 5},
            )),
            ModelDecision(content="I found your two most recent orders."),
        ])
        scope = MarketplaceScopeResult(
            scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
            marketplaceContextUsed=True, reasonCode="PRIVATE_MARKETPLACE_STATUS",
        )
        first = await MarketplaceAgentV2Orchestrator(first_model, registry).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Show my recent orders",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="orders-first",
            scope_result=scope,
        )

        second_id = "01ARZ3NDEKTSV4RRFFQ69G5FB3"
        second_model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="orders-2", tool="get_my_order",
                arguments={"orderId": second_id},
            )),
            ModelDecision(content="The second order is shipped."),
        ])
        second = await MarketplaceAgentV2Orchestrator(second_model, registry).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Show me the second one",
            recent_messages=(("user", "Show my recent orders"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(first.observations[0]),),
            correlation_id="orders-second",
            scope_result=scope,
        )

        self.assertEqual([second_id], commerce.order_ids)
        self.assertEqual("ORDER_FOUND", second.observations[0].reason)
        self.assertEqual(
            second_id,
            second_model.contexts[0].observations[0].order_references[1].order_id,
        )

    async def test_prior_private_fact_cannot_ground_a_new_status_answer_without_refresh(self) -> None:
        prior = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            order=_detail(ORDER), resultCount=1,
        )
        model = _DecisionModel([
            ModelDecision(content="Your order is shipped.") for _ in range(5)
        ])
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=_ConversationCommerce(),
            capability_boundary=_boundary(commerce=True),
        )
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Where is that order now?",
            recent_messages=(("user", "Show my order"),),
            referenced_listings=(),
            prior_observations=(prior,),
            correlation_id="order-must-refresh",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
            ),
        )

        self.assertNotEqual("Your order is shipped.", result.message.content)
        self.assertIn("can't verify", result.message.content)


if __name__ == "__main__":
    unittest.main()
