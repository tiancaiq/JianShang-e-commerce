from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

import httpx
from pydantic import ValidationError

from msb_agent_service.marketplace_agent_v2.capabilities import (
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.commerce import CommerceReadClient
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    AddToMyCartArguments,
    CartItemSnapshot,
    CustomerCartItemReference,
    CustomerCartMutationReference,
    CustomerCartSnapshot,
    ListingAttachment,
    MarketplaceScopeResult,
    ModelDecision,
    RemoveFromMyCartArguments,
    ToolObservation,
    ToolProposal,
    ToolActivity,
    UpdateMyCartQuantityArguments,
)
from msb_agent_service.marketplace_agent_v2.service import (
    _persistable_observation,
    _tool_audit_arguments,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
OTHER_LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
TOKEN = "Bearer delegated-customer-token"
NOW = "2026-09-01T12:00:00Z"


def _boundary(*, mutations: bool, reads: bool = True) -> MarketplaceCustomerCapabilityBoundary:
    families = {
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
    }
    if reads:
        families.add(CapabilityFamily.CUSTOMER_COMMERCE_READ)
    if mutations:
        families.add(CapabilityFamily.CUSTOMER_CART_MUTATION)
    return MarketplaceCustomerCapabilityBoundary(frozenset(families))


def _cart(version: int, *, quantity: int | None = None) -> dict[str, object]:
    items: list[dict[str, object]] = []
    if quantity is not None:
        items.append({
            "listingId": LISTING,
            "title": "Wireless mouse",
            "storeName": "Input Store",
            "quantity": quantity,
            "observedPrice": 49,
            "currency": "USD",
            "addedAt": NOW,
        })
    return {
        "version": version,
        "expiresAt": NOW if version else None,
        "itemCount": len(items),
        "totalQuantity": quantity or 0,
        "totals": (
            [] if quantity is None
            else [{"currency": "USD", "amount": 49 * quantity}]
        ),
        "items": items,
    }


class CartMutationAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_add_uses_current_version_stable_action_key_and_authoritative_result(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.method == "GET":
                return httpx.Response(200, json=_cart(0))
            self.assertEqual("POST", request.method)
            self.assertEqual('/api/v1/cart/items', request.url.path)
            self.assertEqual('"0"', request.headers["If-Match"])
            self.assertEqual(
                f"agent-cart-{INVOCATION}", request.headers["Idempotency-Key"]
            )
            self.assertEqual(TOKEN, request.headers["Authorization"])
            self.assertEqual(
                {"listingId": LISTING, "quantity": 2}, json.loads(request.content)
            )
            return httpx.Response(200, json=_cart(1, quantity=2))

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.add_to_my_cart(
            listing_id=LISTING,
            quantity=2,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="cart-add",
        )

        self.assertEqual(("SUCCEEDED", "CART_ITEM_ADDED"), (result.status, result.reason))
        self.assertEqual(2, result.cart.items[0].quantity)
        self.assertEqual(Decimal("49"), result.cart.items[0].observed_price)
        self.assertEqual(1, result.cart.version)
        self.assertEqual(LISTING, result.cart_mutation_reference.listing_id)
        self.assertEqual(2, len(requests))

    async def test_add_existing_item_replaces_quantity_without_incrementing(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if request.method == "GET":
                return httpx.Response(200, json=_cart(7, quantity=5))
            self.assertEqual({"listingId": LISTING, "quantity": 2}, json.loads(request.content))
            return httpx.Response(200, json=_cart(8, quantity=2))

        result = await CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        ).add_to_my_cart(
            listing_id=LISTING, quantity=2, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="replace-existing",
        )

        self.assertEqual(2, result.cart.total_quantity)
        self.assertEqual(8, result.cart.version)

    async def test_update_and_remove_use_existing_cart_contract_shapes(self) -> None:
        calls: list[tuple[str, str, bytes]] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            calls.append((request.method, request.url.path, request.content))
            if request.method == "GET":
                return httpx.Response(200, json=_cart(3, quantity=2))
            if request.method == "PATCH":
                self.assertEqual('"3"', request.headers["If-Match"])
                return httpx.Response(200, json=_cart(4, quantity=3))
            return httpx.Response(200, json=_cart(4))

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        updated = await client.update_my_cart_quantity(
            listing_id=LISTING, quantity=3, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="cart-update",
        )
        removed = await client.remove_from_my_cart(
            listing_id=LISTING, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="cart-remove",
        )

        self.assertEqual("CART_QUANTITY_UPDATED", updated.reason)
        self.assertEqual(3, updated.cart.items[0].quantity)
        self.assertEqual("CART_ITEM_REMOVED", removed.reason)
        self.assertEqual(0, removed.cart.item_count)
        self.assertIn(("PATCH", f"/api/v1/cart/items/{LISTING}", b'{"quantity":3}'), calls)
        self.assertTrue(any(method == "DELETE" for method, _, _ in calls))

    async def test_owner_error_codes_are_normalized_without_raw_body_leakage(self) -> None:
        cases = (
            ("CART_ITEM_NOT_FOUND", "NOT_FOUND"),
            ("CART_ITEM_NOT_ELIGIBLE", "NOT_PURCHASABLE"),
            ("CART_INSUFFICIENT_STOCK", "OUT_OF_STOCK"),
            ("CART_VERSION_CONFLICT", "CART_CONFLICT"),
            ("CART_ITEM_LIMIT_EXCEEDED", "CART_LIMIT_EXCEEDED"),
            ("CART_IDEMPOTENCY_CONFLICT", "IDEMPOTENCY_CONFLICT"),
        )
        for owner_code, expected in cases:
            async def handler(request: httpx.Request, code: str = owner_code) -> httpx.Response:
                if request.method == "GET":
                    return httpx.Response(200, json=_cart(0))
                return httpx.Response(409, json={
                    "error": {"code": code, "message": "PRIVATE_REDIS_DETAIL"}
                })

            with self.subTest(owner_code=owner_code):
                result = await CommerceReadClient(
                    "http://order-service", timeout_seconds=1,
                    transport=httpx.MockTransport(handler),
                ).add_to_my_cart(
                    listing_id=LISTING, quantity=1, action_reference=INVOCATION,
                    authorization=TOKEN, correlation_id="cart-error",
                )
                self.assertEqual(("REJECTED", expected), (result.status, result.reason))
                self.assertNotIn("PRIVATE_REDIS_DETAIL", result.model_dump_json())

    async def test_lost_response_replays_same_key_without_double_apply(self) -> None:
        applied = 0
        write_calls = 0
        stored: dict[str, object] | None = None

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal applied, write_calls, stored
            if request.method == "GET":
                return httpx.Response(200, json=_cart(0))
            write_calls += 1
            if stored is None:
                applied += 1
                stored = _cart(1, quantity=1)
                raise httpx.ReadTimeout("lost after commit", request=request)
            self.assertEqual(f"agent-cart-{INVOCATION}", request.headers["Idempotency-Key"])
            return httpx.Response(200, json=stored)

        result = await CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        ).add_to_my_cart(
            listing_id=LISTING, quantity=1, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="lost-response",
        )

        self.assertEqual("CART_ITEM_ADDED", result.reason)
        self.assertEqual(1, applied)
        self.assertEqual(2, write_calls)
        self.assertEqual(1, result.cart.total_quantity)

    async def test_outer_invocation_recovery_keeps_key_and_cannot_double_apply(self) -> None:
        cart = _cart(0)
        first_request_version: str | None = None
        applied = 0

        async def handler(request: httpx.Request) -> httpx.Response:
            nonlocal cart, first_request_version, applied
            if request.method == "GET":
                return httpx.Response(200, json=cart)
            self.assertEqual(f"agent-cart-{INVOCATION}", request.headers["Idempotency-Key"])
            if applied == 0:
                first_request_version = request.headers["If-Match"]
                applied = 1
                cart = _cart(1, quantity=1)
                return httpx.Response(200, json=cart)
            self.assertNotEqual(first_request_version, request.headers["If-Match"])
            return httpx.Response(409, json={
                "error": {"code": "CART_IDEMPOTENCY_CONFLICT"}
            })

        client = CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        first = await client.add_to_my_cart(
            listing_id=LISTING, quantity=1, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="initial-invocation",
        )
        recovered = await client.add_to_my_cart(
            listing_id=LISTING, quantity=1, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="invocation-response-retry",
        )

        self.assertEqual("CART_ITEM_ADDED", first.reason)
        self.assertEqual(
            ("REJECTED", "IDEMPOTENCY_CONFLICT"),
            (recovered.status, recovered.reason),
        )
        self.assertEqual(1, applied)

    async def test_timeout_reconciliation_reports_verified_or_unknown_outcome(self) -> None:
        for applied, expected_status, expected_reason in (
            (True, "SUCCEEDED", "CART_MUTATION_RECONCILED"),
            (False, "FAILED", "OUTCOME_UNKNOWN"),
        ):
            get_calls = 0

            async def handler(request: httpx.Request, mutation_applied: bool = applied) -> httpx.Response:
                nonlocal get_calls
                if request.method == "GET":
                    get_calls += 1
                    return httpx.Response(
                        200,
                        json=_cart(
                            1 if mutation_applied and get_calls > 1 else 0,
                            quantity=1 if mutation_applied and get_calls > 1 else None,
                        ),
                    )
                raise httpx.ReadTimeout("uncertain write", request=request)

            with self.subTest(applied=applied):
                result = await CommerceReadClient(
                    "http://order-service", timeout_seconds=1,
                    transport=httpx.MockTransport(handler),
                ).add_to_my_cart(
                    listing_id=LISTING, quantity=1, action_reference=INVOCATION,
                    authorization=TOKEN, correlation_id="reconcile",
                )
                self.assertEqual((expected_status, expected_reason), (result.status, result.reason))

    async def test_pre_write_cart_read_timeout_performs_zero_writes(self) -> None:
        methods: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            methods.append(request.method)
            raise httpx.ConnectTimeout("cart read timeout", request=request)

        result = await CommerceReadClient(
            "http://order-service", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        ).add_to_my_cart(
            listing_id=LISTING, quantity=1, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="pre-write-timeout",
        )

        self.assertEqual(("FAILED", "CART_UPSTREAM_UNAVAILABLE"), (result.status, result.reason))
        self.assertEqual(["GET"], methods)


class _NoProductCalls:
    async def probe_availability(self, **_: Any) -> object:
        raise AssertionError("cart mutation reached Product from Agent Service")

    async def search_individual(self, **_: Any) -> object:
        raise AssertionError("cart mutation reached Product from Agent Service")

    async def get_listing(self, **_: Any) -> object:
        raise AssertionError("cart mutation reached Product from Agent Service")


class _MutationCommerce:
    def __init__(self, result: ToolObservation | None = None) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.result = result

    async def get_my_cart(self, **kwargs: Any) -> ToolObservation:
        self.calls.append(("get_my_cart", kwargs))
        return ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(CustomerCartItemReference(
                listingId=LISTING, title="Wireless mouse", position=1,
            ),),
        )

    async def add_to_my_cart(self, **kwargs: Any) -> ToolObservation:
        self.calls.append(("add_to_my_cart", kwargs))
        return self.result or _mutation_observation("add_to_my_cart", "ADD", 2)

    async def update_my_cart_quantity(self, **kwargs: Any) -> ToolObservation:
        self.calls.append(("update_my_cart_quantity", kwargs))
        return self.result or _mutation_observation(
            "update_my_cart_quantity", "UPDATE_QUANTITY", kwargs["quantity"]
        )

    async def remove_from_my_cart(self, **kwargs: Any) -> ToolObservation:
        self.calls.append(("remove_from_my_cart", kwargs))
        return self.result or _mutation_observation(
            "remove_from_my_cart", "REMOVE", None, empty=True
        )


def _cart_snapshot(quantity: int = 2, *, empty: bool = False) -> CustomerCartSnapshot:
    items = () if empty else (CartItemSnapshot(
        listingId=LISTING,
        title="Wireless mouse",
        storeName="Input Store",
        quantity=quantity,
        observedPrice=Decimal("49"),
        currency="USD",
    ),)
    return CustomerCartSnapshot(
        version=2,
        itemCount=len(items),
        totalQuantity=0 if empty else quantity,
        items=items,
    )


def _mutation_observation(
    tool: str,
    operation: str,
    quantity: int | None,
    *,
    empty: bool = False,
) -> ToolObservation:
    cart = _cart_snapshot(quantity or 1, empty=empty)
    return ToolObservation(
        tool=tool,
        status="SUCCEEDED",
        reason={
            "ADD": "CART_ITEM_ADDED",
            "UPDATE_QUANTITY": "CART_QUANTITY_UPDATED",
            "REMOVE": "CART_ITEM_REMOVED",
        }[operation],
        cart=cart,
        cartItemReferences=tuple(
            CustomerCartItemReference(
                listingId=item.listing_id, title=item.title, position=index
            )
            for index, item in enumerate(cart.items, 1)
        ),
        cartMutationReference=CustomerCartMutationReference(
            listingId=LISTING,
            operation=operation,
            requestedQuantity=quantity,
        ),
    )


def _listing() -> ListingAttachment:
    return ListingAttachment(
        listingId=LISTING,
        title="Wireless mouse",
        categoryName="Mice",
        condition="NEW",
        priceAmount=Decimal("45"),
        currency="USD",
        checkedAt=datetime.now(UTC),
        responseHash="a" * 64,
    )


class _DecisionModel:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.calls = 0

    async def decide(self, **_: Any) -> ModelDecision:
        self.calls += 1
        return self.decisions.pop(0)

    async def close(self) -> None:
        return None


class CartMutationPolicyAndConversationTest(unittest.IsolatedAsyncioTestCase):
    def test_invalid_add_and_update_quantities_fail_strict_schema_validation(self) -> None:
        for arguments_type in (AddToMyCartArguments, UpdateMyCartQuantityArguments):
            for quantity in (0, 1_000):
                with self.subTest(arguments_type=arguments_type, quantity=quantity):
                    with self.assertRaises(ValidationError):
                        arguments_type(listingId=LISTING, quantity=quantity)

    def test_ai_com_01_eval_fixture_covers_text_and_tool_execution_boundaries(self) -> None:
        fixture = json.loads(
            (Path(__file__).parents[1] / "evals" / "ai_com_01_customer_cart_mutation_v1.json")
            .read_text(encoding="utf-8")
        )
        self.assertEqual(
            "AI_COM_01_CUSTOMER_CART_MUTATION_EVAL_V1", fixture["schemaVersion"]
        )
        identifiers = {item["id"] for item in fixture["cases"]}
        self.assertTrue({
            "add-search-second", "add-quantity-two", "update-from-cart",
            "remove-keyboard", "ambiguous-apple", "stale-listing",
            "duplicate-replay", "lost-response-unknown", "unsupported-purchase",
            "forbidden-other-cart", "unsafe-stolen-account", "mutation-gate-off",
        }.issubset(identifiers))
        self.assertTrue(all(
            "expectedText" in item and "expectedTools" in item
            for item in fixture["cases"]
        ))

    async def test_family_gate_removes_schema_and_rejects_policy_and_pre_io(self) -> None:
        commerce = _MutationCommerce()
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=commerce,
            capability_boundary=_boundary(mutations=False),
        )
        self.assertNotIn("add_to_my_cart", registry.names)
        self.assertNotIn("add_to_my_cart", str(registry.provider_schemas()))

        _, rejection = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset({LISTING}),
            required_grounding="LISTING_DATA",
            capability_boundary=_boundary(mutations=False),
        ).validate(ToolProposal(
            callId="disabled-policy", tool="add_to_my_cart",
            arguments={"listingId": LISTING, "quantity": 1},
        ), step=1)
        self.assertEqual("CAPABILITY_DISABLED", rejection.reason)

        execution = await registry.execute(
            tool="add_to_my_cart",
            arguments=AddToMyCartArguments(listingId=LISTING, quantity=1),
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            action_reference=INVOCATION,
            correlation_id="disabled-execution",
            activity=None,
        )
        self.assertEqual("CAPABILITY_DISABLED", execution.reason)
        self.assertEqual([], commerce.calls)
        self.assertIn("get_my_cart", registry.names)

    async def test_schemas_are_actor_free_level_two_commands(self) -> None:
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=_MutationCommerce(),
            capability_boundary=_boundary(mutations=True),
        )
        schemas = {item["name"]: item for item in registry.provider_schemas()}
        self.assertEqual(
            {"listingId", "quantity"},
            set(schemas["add_to_my_cart"]["parameters"]["properties"]),
        )
        self.assertEqual(
            {"listingId"},
            set(schemas["remove_from_my_cart"]["parameters"]["properties"]),
        )
        serialized = json.dumps(schemas).casefold()
        for forbidden in ("userid", "user_id", "cartownerid", "businessid", "buyerid"):
            self.assertNotIn(forbidden, serialized)

    async def test_unreferenced_target_is_forbidden_and_only_one_mutation_may_execute(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset({LISTING}),
            required_grounding="LISTING_DATA",
            capability_boundary=_boundary(mutations=True),
        )
        _, forbidden = policy.validate(ToolProposal(
            callId="unknown-target", tool="add_to_my_cart",
            arguments={"listingId": OTHER_LISTING, "quantity": 1},
        ), step=1)
        self.assertEqual("FORBIDDEN", forbidden.reason)

        arguments, rejection = policy.validate(ToolProposal(
            callId="first", tool="add_to_my_cart",
            arguments={"listingId": LISTING, "quantity": 1},
        ), step=2)
        self.assertIsNone(rejection)
        self.assertIsInstance(arguments, AddToMyCartArguments)
        policy.record(_mutation_observation("add_to_my_cart", "ADD", 1))
        _, duplicate = policy.validate(ToolProposal(
            callId="second", tool="remove_from_my_cart",
            arguments={"listingId": LISTING},
        ), step=3)
        self.assertEqual("DUPLICATE_TOOL_CALL", duplicate.reason)

    async def test_same_turn_cart_read_authorizes_only_returned_remove_target(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            capability_boundary=_boundary(mutations=True),
        )
        policy.record(ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(CustomerCartItemReference(
                listingId=LISTING, title="Wireless mouse", position=1,
            ),),
        ))

        arguments, rejection = policy.validate(ToolProposal(
            callId="remove-after-cart-read", tool="remove_from_my_cart",
            arguments={"listingId": LISTING},
        ), step=2)
        self.assertIsNone(rejection)
        self.assertIsInstance(arguments, RemoveFromMyCartArguments)

        _, invented = policy.validate(ToolProposal(
            callId="remove-invented", tool="remove_from_my_cart",
            arguments={"listingId": OTHER_LISTING},
        ), step=3)
        self.assertEqual("FORBIDDEN", invented.reason)

    async def test_named_remove_can_read_cart_then_mutate_in_same_turn(self) -> None:
        commerce = _MutationCommerce()
        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="read-cart-for-remove", tool="get_my_cart", arguments={},
                )),
                ModelDecision(content=(
                    "I can remove that for you. Removing the Wireless mouse from "
                    "your cart now. Done — your cart is now empty."
                )),
                ModelDecision(toolProposal=ToolProposal(
                    callId="remove-named-cart-item", tool="remove_from_my_cart",
                    arguments={"listingId": LISTING},
                )),
                ModelDecision(content="I removed the item from your cart."),
            ]),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Remove the Wireless mouse from my cart.",
            recent_messages=(), referenced_listings=(), prior_observations=(),
            correlation_id="named-remove-after-cart-read",
            invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
            ),
        )

        self.assertEqual(
            ["get_my_cart", "remove_from_my_cart"],
            [name for name, _ in commerce.calls],
        )
        self.assertEqual("CART_ITEM_REMOVED", result.observations[-1].reason)
        self.assertIn("removed the item", result.message.content)
        self.assertTrue(any(
            item.tool == "DIRECT_RESPONSE"
            and item.reason == "CART_MUTATION_TOOL_REQUIRED"
            for item in result.observations
        ))

    async def test_unresolved_add_reference_clarifies_without_reading_the_cart(self) -> None:
        for message in (
            "Add the quantum teleportation banana to my cart.",
            "Add the quantum teleportation banana into my cart.",
        ):
            with self.subTest(message=message):
                commerce = _MutationCommerce()
                model = _DecisionModel([ModelDecision(toolProposal=ToolProposal(
                    callId="substitute-cart-read",
                    tool="get_my_cart",
                    arguments={},
                ))])

                result = await MarketplaceAgentV2Orchestrator(
                    model,
                    MarketplaceAgentV2ToolRegistry(
                        _NoProductCalls(), commerce=commerce,
                        capability_boundary=_boundary(mutations=True),
                    ),
                ).run(
                    actor_user_id=ACTOR,
                    actor_authorization=TOKEN,
                    current_message=message,
                    recent_messages=(),
                    referenced_listings=(),
                    correlation_id="unresolved-cart-listing",
                    invocation_id=INVOCATION,
                )

                self.assertEqual(0, result.decision_count)
                self.assertEqual(0, model.calls)
                self.assertEqual([], commerce.calls)
                self.assertEqual((), result.observations)
                self.assertIn("couldn't resolve that listing", result.message.content)
                self.assertIn("name or select a current listing", result.message.content)

    async def test_into_cart_phrase_resolves_unique_recommendation_and_adds_it(self) -> None:
        recommendations = (
            _listing().model_copy(update={
                "listing_id": OTHER_LISTING,
                "title": "Harbor Business Desk Lamp",
                "response_hash": "b" * 64,
            }),
            _listing().model_copy(update={
                "title": "Harbor Business Mouse Pad",
            }),
        )
        commerce = _MutationCommerce()
        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="add-mouse-pad",
                tool="add_to_my_cart",
                arguments={"listingId": LISTING, "quantity": 1},
            )),
            ModelDecision(content="I added the mouse pad to your cart."),
        ])

        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="add mouse pad into my cart",
            recent_messages=(),
            referenced_listings=recommendations,
            correlation_id="add-mouse-pad-into-cart",
            invocation_id=INVOCATION,
        )

        self.assertEqual("PRIVATE_TOOL", result.scope_result.required_grounding)
        self.assertEqual(1, len(commerce.calls))
        self.assertEqual("add_to_my_cart", commerce.calls[0][0])
        self.assertEqual("CART_ITEM_ADDED", result.observations[0].reason)
        self.assertIn("I added the item to your cart", result.message.content)

    async def test_search_reference_adds_second_result_with_durable_action_identity(self) -> None:
        commerce = _MutationCommerce()
        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="add-second", tool="add_to_my_cart",
                arguments={"listingId": LISTING, "quantity": 2},
            )),
            ModelDecision(content="I added 2 Wireless mouse items to your cart."),
        ])
        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Add two of the second one.",
            recent_messages=(("USER", "Find wireless mice under $50"),),
            referenced_listings=(
                _listing().model_copy(update={"listing_id": OTHER_LISTING}),
                _listing(),
            ),
            correlation_id="add-second",
            invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )

        self.assertEqual("CART_ITEM_ADDED", result.observations[0].reason)
        self.assertEqual(INVOCATION, commerce.calls[0][1]["action_reference"])
        self.assertEqual(TOKEN, commerce.calls[0][1]["authorization"])
        self.assertNotIn("actor_user_id", commerce.calls[0][1])

    async def test_cart_reference_supports_update_and_mutation_reference_supports_remove_it(self) -> None:
        prior_cart = ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(CustomerCartItemReference(
                listingId=LISTING, title="Wireless mouse", position=1,
            ),),
        )
        commerce = _MutationCommerce()
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=commerce,
            capability_boundary=_boundary(mutations=True),
        )
        scope = MarketplaceScopeResult(
            scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
            marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
        )
        updated = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="quantity", tool="update_my_cart_quantity",
                    arguments={"listingId": LISTING, "quantity": 3},
                )),
                ModelDecision(content="I updated the item quantity in your cart to 3."),
            ]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Make the mouse quantity three.",
            recent_messages=(("USER", "What's in my cart?"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(prior_cart),),
            correlation_id="quantity", invocation_id=INVOCATION,
            scope_result=scope,
        )
        self.assertEqual("CART_QUANTITY_UPDATED", updated.observations[0].reason)

        removed = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="remove-it", tool="remove_from_my_cart",
                    arguments={"listingId": LISTING},
                )),
                ModelDecision(content="I removed the item from your cart."),
            ]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Actually remove it.",
            recent_messages=(("USER", "Make that three"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(updated.observations[0]),),
            correlation_id="remove-it", invocation_id="01ARZ3NDEKTSV4RRFFQ69G5FB0",
            scope_result=scope,
        )
        self.assertEqual("CART_ITEM_REMOVED", removed.observations[0].reason)

    async def test_duplicate_model_proposal_executes_one_write_and_ambiguity_executes_none(self) -> None:
        commerce = _MutationCommerce()
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=commerce,
            capability_boundary=_boundary(mutations=True),
        )
        duplicate = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="first", tool="add_to_my_cart",
                    arguments={"listingId": LISTING, "quantity": 1},
                )),
                ModelDecision(toolProposal=ToolProposal(
                    callId="again", tool="add_to_my_cart",
                    arguments={"listingId": LISTING, "quantity": 1},
                )),
                ModelDecision(content="The item was added to your cart."),
            ]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add the mouse.", recent_messages=(),
            referenced_listings=(_listing(),),
            correlation_id="duplicate", invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )
        self.assertEqual(1, len(commerce.calls))
        self.assertTrue(any(item.reason == "DUPLICATE_TOOL_CALL" for item in duplicate.observations))
        self.assertIn("I added the item to your cart", duplicate.message.content)
        self.assertNotIn("couldn't safely apply", duplicate.message.content)

        before = len(commerce.calls)
        clarification = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([ModelDecision(
                content="Which Apple item do you mean: the USB cable or the USB adapter?"
            )]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Remove the Apple thing.", recent_messages=(),
            referenced_listings=(_listing(), _listing().model_copy(update={
                "listing_id": OTHER_LISTING, "title": "Apple USB adapter",
                "response_hash": "b" * 64,
            })),
            correlation_id="ambiguous",
            invocation_id="01ARZ3NDEKTSV4RRFFQ69G5FB1",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )
        self.assertIn("Which", clarification.message.content)
        self.assertEqual(before, len(commerce.calls))

    async def test_pronoun_with_two_cart_references_rejects_model_selected_mutation(self) -> None:
        prior_cart = ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(
                CustomerCartItemReference(
                    listingId=LISTING, title="Harbor Cart Fixture Tote", position=1,
                ),
                CustomerCartItemReference(
                    listingId=OTHER_LISTING, title="Desk mat", position=2,
                ),
            ),
        )
        commerce = _MutationCommerce()
        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="remove-arbitrary", tool="remove_from_my_cart",
                    arguments={"listingId": OTHER_LISTING},
                )),
                ModelDecision(content="Which cart item do you want me to remove?"),
            ]),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Remove it.",
            recent_messages=(("USER", "What's in my cart?"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(prior_cart),),
            correlation_id="ambiguous-pronoun",
            invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
            ),
        )

        self.assertEqual([], commerce.calls)
        self.assertEqual("AMBIGUOUS_REFERENCE", result.observations[0].reason)
        self.assertEqual(
            "Which cart item do you want me to remove?", result.message.content
        )

    async def test_exact_displayed_title_resolves_one_of_multiple_recommendations(self) -> None:
        cases = (
            (
                "Add Harbor Business Desk Lamp to my cart.",
                (
                    "Harbor Business Desk Lamp",
                    "Harbor Business Folding Chair",
                    "Harbor Business Mouse Pad",
                    "Harbor Business Storage Bin",
                ),
            ),
            (
                "Add Restored oak writing desk to my cart.",
                (
                    "Restored oak writing desk",
                    "Walnut desktop radio with warm dial light",
                    "PIX-762Z LED desk lamp with clean everyday finish - private sale",
                    "Bundle of Urban Nori puzzle storage case, from a smoke-free home - private sale",
                    "Walnut Brown folding picnic mat by Hearthlane - with a small accessory pouch",
                ),
            ),
        )
        listing_ids = (
            LISTING,
            OTHER_LISTING,
            "01ARZ3NDEKTSV4RRFFQ69G5FAW",
            "01ARZ3NDEKTSV4RRFFQ69G5FAT",
            "01ARZ3NDEKTSV4RRFFQ69G5FAS",
        )

        for current_message, titles in cases:
            with self.subTest(current_message=current_message):
                recommendations = tuple(
                    _listing().model_copy(update={
                        "listing_id": listing_ids[index],
                        "title": title,
                        "response_hash": f"{index + 1:x}" * 64,
                    })
                    for index, title in enumerate(titles)
                )
                commerce = _MutationCommerce()
                result = await MarketplaceAgentV2Orchestrator(
                    _DecisionModel([
                        ModelDecision(toolProposal=ToolProposal(
                            callId="exact-title-add",
                            tool="add_to_my_cart",
                            arguments={"listingId": LISTING, "quantity": 1},
                        )),
                        ModelDecision(content="The item was added to your cart."),
                    ]),
                    MarketplaceAgentV2ToolRegistry(
                        _NoProductCalls(), commerce=commerce,
                        capability_boundary=_boundary(mutations=True),
                    ),
                ).run(
                    actor_user_id=ACTOR,
                    actor_authorization=TOKEN,
                    current_message=current_message,
                    recent_messages=(),
                    referenced_listings=recommendations,
                    correlation_id="exact-title-reference",
                    invocation_id=INVOCATION,
                    scope_result=MarketplaceScopeResult(
                        scope="IN_SCOPE",
                        requiredGrounding="LISTING_DATA",
                        confidence="HIGH",
                        marketplaceContextUsed=True,
                        reasonCode="MARKETPLACE_CONTEXT",
                    ),
                )

                self.assertEqual(1, len(commerce.calls))
                self.assertFalse(any(
                    item.reason == "AMBIGUOUS_REFERENCE"
                    for item in result.observations
                ))
                self.assertIn("I added the item to your cart", result.message.content)

    async def test_pronoun_with_two_cart_references_accepts_direct_clarification(self) -> None:
        prior_cart = ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(
                CustomerCartItemReference(
                    listingId=LISTING, title="Harbor Cart Fixture Tote", position=1,
                ),
                CustomerCartItemReference(
                    listingId=OTHER_LISTING, title="Desk mat", position=2,
                ),
            ),
        )
        commerce = _MutationCommerce()
        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(content="Which cart item do you want me to remove?")
            ]),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Remove it.",
            recent_messages=(("USER", "What's in my cart?"),),
            referenced_listings=(),
            prior_observations=(_persistable_observation(prior_cart),),
            correlation_id="ambiguous-direct-clarification",
            invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
            ),
        )

        self.assertEqual([], commerce.calls)
        self.assertEqual(
            "Which cart item do you want me to remove?", result.message.content
        )

    async def test_unknown_outcome_never_accepts_fabricated_success(self) -> None:
        unknown = ToolObservation(
            tool="add_to_my_cart", status="FAILED", reason="OUTCOME_UNKNOWN",
            cartMutationReference=CustomerCartMutationReference(
                listingId=LISTING, operation="ADD", requestedQuantity=1,
            ),
        )
        commerce = _MutationCommerce(result=unknown)
        model = _DecisionModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="unknown", tool="add_to_my_cart",
                arguments={"listingId": LISTING, "quantity": 1},
            )),
            *[ModelDecision(content="I added the item to your cart.") for _ in range(4)],
        ])
        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add the mouse.", recent_messages=(),
            referenced_listings=(_listing(),),
            correlation_id="unknown", invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )
        self.assertIn("couldn't confirm", result.message.content)
        self.assertNotIn("I added", result.message.content)
        self.assertEqual(2, result.decision_count)

    async def test_individual_listing_cart_rejection_cannot_be_rewritten_as_success(self) -> None:
        rejected = ToolObservation(
            tool="add_to_my_cart", status="REJECTED", reason="NOT_PURCHASABLE",
            cartMutationReference=CustomerCartMutationReference(
                listingId=LISTING, operation="ADD", requestedQuantity=1,
            ),
        )
        commerce = _MutationCommerce(result=rejected)
        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="individual-add", tool="add_to_my_cart",
                    arguments={"listingId": LISTING, "quantity": 1},
                )),
                ModelDecision(content=(
                    "That item is not purchasable in the cart, but contact the seller."
                )),
            ]),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add this individual listing to my cart.",
            recent_messages=(), referenced_listings=(_listing(),),
            correlation_id="individual-cart-refusal", invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )

        self.assertEqual(1, len(commerce.calls))
        self.assertEqual(
            "The cart accepts only active business listings. "
            "This listing is not currently eligible.",
            result.message.content,
        )
        self.assertNotIn("I added", result.message.content)
        self.assertNotIn("contact the seller", result.message.content)
        self.assertEqual(2, result.decision_count)

    async def test_duplicate_proposals_cannot_mask_individual_cart_refusal(self) -> None:
        rejected = ToolObservation(
            tool="add_to_my_cart", status="REJECTED", reason="NOT_PURCHASABLE",
            cartMutationReference=CustomerCartMutationReference(
                listingId=LISTING, operation="ADD", requestedQuantity=1,
            ),
        )
        commerce = _MutationCommerce(result=rejected)
        proposals = [
            ModelDecision(toolProposal=ToolProposal(
                callId=f"individual-add-{index}", tool="add_to_my_cart",
                arguments={"listingId": LISTING, "quantity": 1},
            ))
            for index in range(5)
        ]

        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel(proposals),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add this individual listing to my cart.",
            recent_messages=(), referenced_listings=(_listing(),),
            correlation_id="individual-cart-duplicate-refusal",
            invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="LISTING_DATA", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="MARKETPLACE_CONTEXT",
            ),
        )

        self.assertEqual(1, len(commerce.calls))
        self.assertEqual(5, result.decision_count)
        self.assertEqual(4, sum(
            item.reason == "DUPLICATE_TOOL_CALL" for item in result.observations
        ))
        self.assertEqual(
            "The cart accepts only active business listings. "
            "This listing is not currently eligible.",
            result.message.content,
        )
        self.assertNotIn("couldn't safely apply", result.message.content)

    async def test_cart_control_metadata_and_unverified_abstention_are_rejected(self) -> None:
        commerce = _MutationCommerce()
        registry = MarketplaceAgentV2ToolRegistry(
            _NoProductCalls(), commerce=commerce,
            capability_boundary=_boundary(mutations=True),
        )
        scope = MarketplaceScopeResult(
            scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
            marketplaceContextUsed=True, reasonCode="PRIVATE_COMMERCE_FOLLOW_UP",
        )
        cart_read = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(toolProposal=ToolProposal(
                    callId="cart-read", tool="get_my_cart", arguments={},
                )),
                ModelDecision(content=(
                    "Your cart has one item. Cart version: 7 — "
                    "expires: 2026-10-02T11:19:42Z."
                )),
                ModelDecision(content="Your cart has one item."),
            ]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="What's in my cart?", recent_messages=(),
            referenced_listings=(), correlation_id="cart-metadata-redaction",
            invocation_id=INVOCATION, scope_result=scope,
        )
        self.assertEqual("Your cart has one item.", cart_read.message.content)
        self.assertNotIn("version", cart_read.message.content.casefold())
        self.assertNotIn("expires", cart_read.message.content.casefold())

        prior_cart = _persistable_observation(ToolObservation(
            tool="get_my_cart", status="SUCCEEDED", reason="CART_AVAILABLE",
            cart=_cart_snapshot(),
            cartItemReferences=(CustomerCartItemReference(
                listingId=LISTING, title="Wireless mouse", position=1,
            ),),
        ))
        removed = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([
                ModelDecision(content=(
                    "I can't verify that account-specific status here. Please use "
                    "the relevant account page or contact marketplace support."
                )),
                ModelDecision(toolProposal=ToolProposal(
                    callId="remove-after-abstention", tool="remove_from_my_cart",
                    arguments={"listingId": LISTING},
                )),
                ModelDecision(content="I removed the item from your cart."),
            ]), registry,
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Remove it.",
            recent_messages=(("USER", "What's in my cart?"),),
            referenced_listings=(), prior_observations=(prior_cart,),
            correlation_id="remove-after-abstention",
            invocation_id="01ARZ3NDEKTSV4RRFFQ69G5FB0", scope_result=scope,
        )
        self.assertIn("removed the item", removed.message.content)
        self.assertTrue(any(call[0] == "remove_from_my_cart" for call in commerce.calls))

    async def test_disabled_mutation_family_cannot_be_promised_or_confirmed(self) -> None:
        commerce = _MutationCommerce()
        result = await MarketplaceAgentV2Orchestrator(
            _DecisionModel([ModelDecision(content=(
                "I'll add that item to your cart. Just confirming: add quantity 1?"
            ))]),
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=False),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add that one to my cart.", recent_messages=(),
            referenced_listings=(_listing(),),
            correlation_id="mutation-family-disabled", invocation_id=INVOCATION,
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL", confidence="HIGH",
                marketplaceContextUsed=True, reasonCode="PRIVATE_MARKETPLACE_STATUS",
            ),
        )

        self.assertEqual([], commerce.calls)
        self.assertEqual(
            "Cart changes are not available through the Agent right now. No cart "
            "item was added, updated, or removed.",
            result.message.content,
        )

    async def test_cross_actor_cart_request_terminates_before_model_or_write(self) -> None:
        commerce = _MutationCommerce()
        model = _DecisionModel([ModelDecision(content="should not run")])
        result = await MarketplaceAgentV2Orchestrator(
            model,
            MarketplaceAgentV2ToolRegistry(
                _NoProductCalls(), commerce=commerce,
                capability_boundary=_boundary(mutations=True),
            ),
        ).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Add this item to another user's cart.",
            recent_messages=(), referenced_listings=(_listing(),),
            correlation_id="cross-actor", invocation_id=INVOCATION,
        )
        self.assertEqual(0, result.decision_count)
        self.assertEqual(0, model.calls)
        self.assertEqual([], commerce.calls)

    def test_persistence_keeps_only_cart_references_and_not_current_cart_state(self) -> None:
        live = _mutation_observation("add_to_my_cart", "ADD", 2)
        persisted = _persistable_observation(live)
        self.assertIsNone(persisted.cart)
        self.assertEqual(LISTING, persisted.cart_item_references[0].listing_id)
        self.assertEqual("ADD", persisted.cart_mutation_reference.operation)
        self.assertNotIn("observedPrice", persisted.model_dump_json())

    def test_tool_audit_keeps_bounded_intent_and_hashed_action_reference(self) -> None:
        observation = _mutation_observation("add_to_my_cart", "ADD", 2)
        activity = ToolActivity(
            tool=observation.tool,
            status=observation.status,
            reason=observation.reason,
            observedAt=observation.observed_at,
        )

        audit = _tool_audit_arguments(
            activity, (observation,), invocation_id=INVOCATION, sequence=1
        )

        self.assertEqual(LISTING, audit["listingId"])
        self.assertEqual("ADD", audit["operation"])
        self.assertEqual(2, audit["requestedQuantity"])
        self.assertEqual(64, len(audit["actionReference"]))
        serialized = json.dumps(audit)
        self.assertNotIn(INVOCATION, serialized)
        self.assertNotIn(TOKEN, serialized)


if __name__ == "__main__":
    unittest.main()
