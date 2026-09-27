from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx

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
    CustomerCheckoutSnapshot,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    PrepareMyCheckoutArguments,
    SubmitCheckoutConfirmationArguments,
    ToolActivity,
    ToolObservation,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.service import (
    _checkout_confirmation_inspection,
    _public_pending,
    _tool_audit_arguments,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
CHECKOUT = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
PAYMENT = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
ORDER = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
ADDRESS = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
TOKEN = "Bearer delegated-customer-token"
NOW = datetime.now(UTC)
EXPIRY = (NOW + timedelta(minutes=15)).isoformat().replace("+00:00", "Z")


class _NoProduct:
    pass


class _NoModel:
    async def decide(self, **_: object) -> object:
        raise AssertionError("A consumed checkout must not invoke the model")

    async def close(self) -> None:
        return None


class _CheckoutRegistry:
    names = ("submit_my_checkout",)
    capability_boundary: MarketplaceCustomerCapabilityBoundary

    def __init__(self) -> None:
        self.executions = 0

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return ({"name": "submit_my_checkout"},)

    async def execute(self, **kwargs: object) -> ToolObservation:
        self.executions += 1
        if kwargs["action_reference"] != INVOCATION:
            raise AssertionError("The durable confirmation identity must be the action key")
        return ToolObservation(
            tool="submit_my_checkout",
            status="SUCCEEDED",
            reason="ORDER_CONFIRMATION_PENDING",
        )


def _validation(*, version: int = 4, price: int = 20) -> dict[str, object]:
    return {
        "cartVersion": version,
        "validatedAt": NOW.isoformat(),
        "checkoutReady": True,
        "itemCount": 1,
        "totalQuantity": 1,
        "validatedTotals": [{"currency": "USD", "amount": price}],
        "cartIssues": [],
        "items": [{
            "listingId": LISTING,
            "title": "Harbor Business Mouse Pad",
            "requestedQuantity": 1,
            "availableQuantity": 8,
            "observedPrice": 20,
            "currentPrice": price,
            "observedCurrency": "USD",
            "currentCurrency": "USD",
            "status": "READY",
            "issues": [],
        }],
    }


def _checkout() -> dict[str, object]:
    return {
        "id": CHECKOUT,
        "status": "PENDING_PAYMENT",
        "cartVersion": 4,
        "currency": "USD",
        "subtotal": 20,
        "shipping": 0,
        "tax": 0,
        "discount": 0,
        "total": 20,
        "expiresAt": EXPIRY,
        "reservation": {
            "id": "01ARZ3NDEKTSV4RRFFQ69G5FB2",
            "status": "ACTIVE",
            "releaseStatus": "NOT_REQUIRED",
        },
        "address": {
            "sourceAddressId": ADDRESS,
            "sourceVersion": 2,
            "label": "Home",
            "recipientName": "PRIVATE",
            "phone": "PRIVATE",
            "line1": "PRIVATE",
            "line2": None,
            "city": "Irvine",
            "region": "CA",
            "postalCode": "PRIVATE",
            "countryCode": "US",
        },
        "items": [{
            "listingId": LISTING,
            "businessId": "01ARZ3NDEKTSV4RRFFQ69G5FB3",
            "storeId": "01ARZ3NDEKTSV4RRFFQ69G5FB4",
            "storeName": "Harbor Business",
            "catalogVersion": 7,
            "title": "Harbor Business Mouse Pad",
            "sku": "PAD",
            "condition": "NEW",
            "thumbnailUrl": None,
            "quantity": 1,
            "unitPrice": 20,
            "lineSubtotal": 20,
            "shippingAllocation": 0,
            "taxAllocation": 0,
            "discountAllocation": 0,
            "lineTotal": 20,
            "policyVersion": "LOCAL_DEMO_V1",
        }],
        "shippingQuotes": [],
        "taxQuote": {"amount": 0, "adapter": "ZERO_LOCAL_DEMO_V1"},
        "policies": [],
        "failureCode": None,
        "createdAt": NOW.isoformat(),
        "updatedAt": NOW.isoformat(),
    }


def _cancelled_checkout(*, release_status: str = "COMPLETE") -> dict[str, object]:
    checkout = _checkout()
    checkout["status"] = "CANCELLED"
    checkout["reservation"] = {
        "id": "01ARZ3NDEKTSV4RRFFQ69G5FB2",
        "status": "RELEASED",
        "releaseStatus": release_status,
    }
    return checkout


def _intent(status: str) -> dict[str, object]:
    return {
        "id": PAYMENT,
        "checkoutId": CHECKOUT,
        "status": status,
        "version": 1,
        "amount": 20,
        "currency": "USD",
        "expiresAt": EXPIRY,
        "action": None,
        "error": None,
    }


def _order() -> dict[str, object]:
    return {
        "orderId": ORDER,
        "status": "CONFIRMED",
        "paymentStatus": "SUCCEEDED",
        "totalAmount": 20,
        "currency": "USD",
        "createdAt": NOW.isoformat(),
        "updatedAt": NOW.isoformat(),
        "groups": [],
    }


def _boundary(enabled: bool) -> MarketplaceCustomerCapabilityBoundary:
    families = {
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        CapabilityFamily.CUSTOMER_COMMERCE_READ,
    }
    if enabled:
        families.add(CapabilityFamily.CUSTOMER_CHECKOUT)
    return MarketplaceCustomerCapabilityBoundary(frozenset(families))


class CheckoutAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_declining_confirmation_cancels_checkout_and_verifies_release(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.method == "POST":
                self.assertEqual(
                    f"agent-checkout-cancel-{INVOCATION}",
                    request.headers["Idempotency-Key"],
                )
                return httpx.Response(
                    200, json=_cancelled_checkout(release_status="PENDING")
                )
            return httpx.Response(200, json=_cancelled_checkout())

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )

        released = await client.cancel_prepared_checkout(
            checkout_id=CHECKOUT,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="checkout-decline",
        )

        self.assertTrue(released)
        self.assertEqual(
            [
                ("POST", f"/api/v1/checkouts/{CHECKOUT}/cancel"),
                ("GET", f"/api/v1/checkouts/{CHECKOUT}"),
            ],
            [(request.method, request.url.path) for request in requests],
        )

    async def test_prepare_uses_default_address_and_creates_exact_confirmation(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path == "/api/v1/cart/validate":
                return httpx.Response(200, json=_validation())
            if request.url.path == "/api/v1/users/me/addresses":
                return httpx.Response(200, json={"data": [{
                    "id": ADDRESS, "label": "Home", "city": "Irvine",
                    "region": "CA", "countryCode": "US", "isDefault": True,
                    "version": 2,
                }]})
            if request.url.path == "/api/v1/checkouts":
                body = json.loads(request.content)
                self.assertEqual({"cartVersion": 4, "addressId": ADDRESS}, body)
                self.assertEqual(
                    f"agent-checkout-{INVOCATION}",
                    request.headers["Idempotency-Key"],
                )
                return httpx.Response(201, json=_checkout())
            raise AssertionError(request.url)

        client = CommerceReadClient(
            "http://order", auth_base_url="http://auth", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.prepare_my_checkout(
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="checkout-prepare",
        )

        self.assertEqual("SUCCEEDED", result.status)
        self.assertEqual("CHECKOUT_READY", result.reason)
        self.assertEqual(20, result.checkout.total)
        self.assertNotIn("PRIVATE", result.checkout.address_summary)
        self.assertEqual("SUBMIT_CHECKOUT", result.pending_interaction.action)
        binding = SubmitCheckoutConfirmationArguments.model_validate(
            result.pending_interaction.arguments
        )
        self.assertEqual(CHECKOUT, binding.checkout_id)
        self.assertEqual(20, binding.total)
        self.assertEqual("USD", binding.currency)
        self.assertEqual(3, len(requests))

    async def test_changed_cart_and_price_fail_revalidation(self) -> None:
        phase = {"price": 20, "version": 4}

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith(CHECKOUT):
                return httpx.Response(200, json=_checkout())
            if request.url.path == "/api/v1/cart/validate":
                return httpx.Response(
                    200,
                    json=_validation(version=phase["version"], price=phase["price"]),
                )
            raise AssertionError(request.url)

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        baseline_validation = _validation()
        from msb_agent_service.marketplace_agent_v2.commerce import (
            _CartValidation,
            _Checkout,
            _checkout_binding,
        )
        binding = _checkout_binding(
            _Checkout.model_validate(_checkout()),
            _CartValidation.model_validate(baseline_validation),
        )

        phase["price"] = 25
        changed_price = await client.revalidate_checkout_confirmation(
            binding=binding, authorization=TOKEN, correlation_id="price-change"
        )
        self.assertFalse(changed_price.valid)
        self.assertEqual("PRICE_CHANGED", changed_price.reason)

        phase["price"] = 20
        phase["version"] = 5
        changed_cart = await client.revalidate_checkout_confirmation(
            binding=binding, authorization=TOKEN, correlation_id="cart-change"
        )
        self.assertFalse(changed_cart.valid)
        self.assertEqual("CHECKOUT_STALE", changed_cart.reason)

    async def test_timeout_after_completion_reconciles_without_second_completion(self) -> None:
        counts = {"intent": 0, "complete": 0, "intent_get": 0}

        async def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path.endswith("/payment-intent") and request.method == "POST":
                counts["intent"] += 1
                return httpx.Response(201, json=_intent("REQUIRES_ACTION"))
            if path.endswith("/complete-demo-payment"):
                counts["complete"] += 1
                raise httpx.ReadTimeout("response lost", request=request)
            if path.endswith("/payment-intent") and request.method == "GET":
                counts["intent_get"] += 1
                return httpx.Response(200, json=_intent("SUCCEEDED"))
            if path.endswith("/confirmed-order"):
                return httpx.Response(200, json={"orderId": ORDER, "confirmed": True})
            if path.endswith(ORDER):
                return httpx.Response(200, json=_order())
            raise AssertionError(request.url)

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.submit_my_checkout(
            checkout_id=CHECKOUT,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="lost-response",
        )

        self.assertEqual("ORDER_CONFIRMED", result.reason)
        self.assertEqual(1, counts["complete"])
        self.assertGreaterEqual(counts["intent_get"], 1)

    async def test_failed_payment_intent_never_runs_demo_completion_or_reports_order(self) -> None:
        counts = {"complete": 0, "order": 0}

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/payment-intent"):
                return httpx.Response(200, json=_intent("FAILED"))
            if request.url.path.endswith("/complete-demo-payment"):
                counts["complete"] += 1
            if request.url.path.endswith("/confirmed-order"):
                counts["order"] += 1
            raise AssertionError(request.url)

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.submit_my_checkout(
            checkout_id=CHECKOUT,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="declined-payment",
        )

        self.assertEqual("PAYMENT_FAILED", result.reason)
        self.assertEqual(0, counts["complete"])
        self.assertEqual(0, counts["order"])

    async def test_delayed_order_confirmation_is_reported_as_pending_without_repayment(self) -> None:
        counts = {"complete": 0, "resolution": 0}

        async def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path.endswith("/payment-intent") and request.method == "POST":
                return httpx.Response(201, json=_intent("REQUIRES_ACTION"))
            if path.endswith("/payment-intent") and request.method == "GET":
                return httpx.Response(200, json=_intent("REQUIRES_ACTION"))
            if path.endswith("/complete-demo-payment"):
                counts["complete"] += 1
                return httpx.Response(200, json={
                    "paymentIntentId": PAYMENT, "status": "SUCCEEDED",
                    "outcome": "SUCCEEDED", "replayed": False,
                })
            if path.endswith("/confirmed-order"):
                counts["resolution"] += 1
                return httpx.Response(202, json={"orderId": None, "confirmed": False})
            if path.endswith(CHECKOUT):
                return httpx.Response(200, json=_checkout())
            raise AssertionError(request.url)

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.submit_my_checkout(
            checkout_id=CHECKOUT,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="delayed-order",
        )

        self.assertEqual("ORDER_CONFIRMATION_PENDING", result.reason)
        self.assertEqual(1, counts["complete"])
        self.assertEqual(11, counts["resolution"])


class CheckoutOrchestrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_partial_cart_checkout_requires_direction_without_a_tool(self) -> None:
        registry = _CheckoutRegistry()
        registry.capability_boundary = _boundary(True)
        result = await MarketplaceAgentV2Orchestrator(_NoModel(), registry).run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Buy only the mouse.",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="partial-checkout",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL",
                confidence="HIGH", marketplaceContextUsed=True,
                reasonCode="PRIVATE_COMMERCE_REQUEST",
            ),
        )

        self.assertIn("whole current cart", result.message.content)
        self.assertEqual(0, result.decision_count)
        self.assertEqual(0, registry.executions)

    async def test_consumed_confirmation_executes_exactly_once_without_a_model_call(self) -> None:
        registry = _CheckoutRegistry()
        registry.capability_boundary = _boundary(True)
        orchestrator = MarketplaceAgentV2Orchestrator(_NoModel(), registry)
        interaction = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION,
            confirmationId=INVOCATION,
            type="CONFIRM_ACTION",
            action="SUBMIT_CHECKOUT",
            arguments={"checkoutId": CHECKOUT},
            summary="Submit one exact checkout for 20.00 USD.",
            status="CONSUMED",
            createdAt=NOW,
        )
        scope = MarketplaceScopeResult(
            scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL",
            confidence="HIGH", marketplaceContextUsed=True,
            reasonCode="PRIVATE_COMMERCE_REQUEST",
        )

        result = await orchestrator.run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Yes",
            recent_messages=(),
            referenced_listings=(),
            confirmed_interaction=interaction,
            correlation_id="confirmed-checkout",
            scope_result=scope,
        )
        replay = await orchestrator.run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message="Yes",
            recent_messages=(),
            referenced_listings=(),
            pending_interaction=interaction,
            correlation_id="replayed-checkout",
            scope_result=scope,
        )

        self.assertEqual(0, result.decision_count)
        self.assertEqual("ORDER_CONFIRMATION_PENDING", result.observations[0].reason)
        self.assertIn("already been used", replay.message.content)
        self.assertEqual(1, registry.executions)


class CheckoutCapabilityTest(unittest.TestCase):
    def test_read_only_checkout_question_does_not_cancel_pending_confirmation(self) -> None:
        pending = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION,
            confirmationId=INVOCATION,
            type="CONFIRM_ACTION",
            action="SUBMIT_CHECKOUT",
            arguments={"checkoutId": CHECKOUT},
            summary="Submit one exact checkout for 20.00 USD.",
            status="WAITING",
            createdAt=NOW,
        )

        self.assertTrue(_checkout_confirmation_inspection(
            "What's in this checkout?", pending
        ))
        self.assertTrue(_checkout_confirmation_inspection(
            "What's the total again?", pending
        ))
        self.assertFalse(_checkout_confirmation_inspection(
            "Change the checkout total.", pending
        ))

    def test_eval_fixture_covers_the_nine_required_checkout_scenarios(self) -> None:
        fixture = json.loads((
            Path(__file__).parents[1]
            / "evals"
            / "ai_chk_01_customer_checkout_mock_payment_v1.json"
        ).read_text(encoding="utf-8"))

        self.assertEqual(
            "AI_CHK_01_CUSTOMER_CHECKOUT_MOCK_PAYMENT_EVAL_V1",
            fixture["schemaVersion"],
        )
        self.assertFalse(fixture["providerOutcomeModelControlled"])
        self.assertEqual({
            "full-happy-path", "cart-changed-before-confirm",
            "price-changed-before-confirm", "payment-decline",
            "timeout-after-provider-success", "duplicate-callback",
            "duplicate-yes", "unsupported-payment-privilege",
            "cross-user-checkout",
        }, {item["id"] for item in fixture["cases"]})

    def test_flag_controls_exact_provider_schema(self) -> None:
        disabled = MarketplaceAgentV2ToolRegistry(
            _NoProduct(), capability_boundary=_boundary(False)
        )
        enabled = MarketplaceAgentV2ToolRegistry(
            _NoProduct(), capability_boundary=_boundary(True)
        )
        self.assertNotIn("prepare_my_checkout", disabled.names)
        self.assertEqual(
            ("prepare_my_checkout", "get_my_checkout", "submit_my_checkout"),
            tuple(name for name in enabled.names if "checkout" in name),
        )
        self.assertEqual(
            enabled.names,
            tuple(item["name"] for item in enabled.provider_schemas()),
        )

    def test_direct_submit_proposal_requires_confirmation(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            capability_boundary=_boundary(True),
        )
        arguments, rejection = policy.validate(
            ToolProposal(
                callId="direct-submit",
                tool="submit_my_checkout",
                arguments={"checkoutId": CHECKOUT},
            ),
            step=1,
        )
        self.assertIsNone(arguments)
        self.assertEqual("CONFIRMATION_REQUIRED", rejection.reason)

    def test_checkout_read_requires_an_authoritative_conversation_reference(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            capability_boundary=_boundary(True),
        )
        proposal = ToolProposal(
            callId="read-checkout",
            tool="get_my_checkout",
            arguments={"checkoutId": CHECKOUT},
        )

        arguments, rejection = policy.validate(proposal, step=1)

        self.assertIsNone(arguments)
        self.assertEqual("FORBIDDEN", rejection.reason)

        policy.record(ToolObservation(
            tool="prepare_my_checkout",
            status="SUCCEEDED",
            reason="CHECKOUT_READY",
            checkout=CustomerCheckoutSnapshot.model_validate_json(json.dumps({
                "checkoutId": CHECKOUT,
                "status": "PENDING_PAYMENT",
                "currency": "USD",
                "subtotal": 20,
                "shipping": 0,
                "tax": 0,
                "discount": 0,
                "total": 20,
                "expiresAt": EXPIRY,
                "addressSummary": "Home · Irvine, CA · US",
                "shippingSummary": "Saved delivery address",
                "items": [{
                    "listingId": LISTING,
                    "title": "Harbor Business Mouse Pad",
                    "quantity": 1,
                    "unitPrice": 20,
                    "lineTotal": 20,
                }],
            })),
        ))
        arguments, rejection = policy.validate(proposal, step=2)

        self.assertEqual(CHECKOUT, arguments.checkout_id)
        self.assertIsNone(rejection)

    def test_checkout_tools_have_no_actor_provider_or_payment_outcome_arguments(self) -> None:
        self.assertEqual({}, PrepareMyCheckoutArguments().model_dump())
        schema = MarketplaceAgentV2ToolRegistry(
            _NoProduct(), capability_boundary=_boundary(True)
        ).provider_schemas()
        checkout_schemas = {
            item["name"]: set(item["parameters"]["properties"])
            for item in schema
            if item["name"] in {
                "prepare_my_checkout", "get_my_checkout", "submit_my_checkout",
            }
        }
        self.assertEqual({
            "prepare_my_checkout": set(),
            "get_my_checkout": {"checkoutId"},
            "submit_my_checkout": {"checkoutId"},
        }, checkout_schemas)
        forbidden = {
            "actorId", "userId", "buyerId", "provider", "paymentMethod",
            "paymentOutcome", "forcePaymentResult", "markPaid",
        }
        for properties in checkout_schemas.values():
            self.assertFalse(properties & forbidden)

    def test_checkout_binding_is_not_returned_in_the_public_pending_payload(self) -> None:
        pending = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION,
            confirmationId=INVOCATION,
            type="CONFIRM_ACTION",
            action="SUBMIT_CHECKOUT",
            arguments={
                "checkoutId": CHECKOUT,
                "checkoutFingerprint": "a" * 64,
                "cartVersion": 4,
                "cartFingerprint": "b" * 64,
                "total": "20.00",
                "currency": "USD",
            },
            summary="Submit one exact checkout for 20.00 USD.",
            status="WAITING",
            createdAt=NOW,
        )

        self.assertEqual({}, _public_pending(pending).arguments)
        self.assertEqual(CHECKOUT, pending.arguments["checkoutId"])

    def test_submit_audit_hashes_checkout_confirmation_and_action_identity(self) -> None:
        observation = ToolObservation(
            tool="submit_my_checkout", status="SUCCEEDED",
            reason="ORDER_CONFIRMATION_PENDING",
        )
        activity = ToolActivity(
            tool="submit_my_checkout", status="SUCCEEDED",
            reason=observation.reason, observedAt=observation.observed_at,
        )

        audit = _tool_audit_arguments(
            activity, (observation,), invocation_id=ACTOR, sequence=1,
            confirmation_id=INVOCATION, checkout_id=CHECKOUT,
        )

        serialized = json.dumps(audit)
        self.assertNotIn(INVOCATION, serialized)
        self.assertNotIn(CHECKOUT, serialized)
        self.assertEqual(64, len(audit["actionReference"]))


if __name__ == "__main__":
    unittest.main()
