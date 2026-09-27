from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime
from decimal import Decimal

import httpx

from msb_agent_service.marketplace_agent_v2.capabilities import (
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.commerce import CommerceReadClient
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2Orchestrator,
    _explicit_order_cancellation_request,
    _informational_order_cancellation_request,
)
from msb_agent_service.marketplace_agent_v2.confirmations import (
    ConfirmationRecord, ConfirmationState,
)
from msb_agent_service.marketplace_agent_v2.persistence import (
    MarketplaceAgentV2Persistence,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.scope import MarketplaceScopeClassifier
from msb_agent_service.marketplace_agent_v2.schemas import (
    CancelOrderConfirmationArguments,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    ModelDecision,
    ToolObservation,
    ToolProposal,
    ToolActivity,
)
from msb_agent_service.marketplace_agent_v2.service import _tool_audit_arguments


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
ORDER = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
REQUEST = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
TOKEN = "Bearer delegated-customer-token"
NOW = datetime(2026, 9, 8, 12, 0, tzinfo=UTC)


def _order(
    *,
    version: int = 4,
    eligible: bool = True,
    ineligibility: str | None = None,
    request_id: str | None = None,
    request_status: str | None = None,
) -> dict[str, object]:
    return {
        "orderId": ORDER,
        "status": "CONFIRMED" if request_id is None else "CANCELLATION_REQUESTED",
        "paymentStatus": "SUCCEEDED",
        "totalAmount": 18.25,
        "currency": "USD",
        "version": version,
        "createdAt": NOW.isoformat(),
        "updatedAt": NOW.isoformat(),
        "groups": [{
            "businessOrderId": "01ARZ3NDEKTSV4RRFFQ69G5FB0",
            "businessId": "01ARZ3NDEKTSV4RRFFQ69G5FB1",
            "storeId": "01ARZ3NDEKTSV4RRFFQ69G5FB2",
            "storeName": "Harbor Business",
            "status": "PENDING_ACCEPTANCE",
            "totalAmount": 18.25,
            "currency": "USD",
            "items": [{
                "listingId": LISTING,
                "title": "Harbor Business Desk Lamp",
                "unitPrice": 18.25,
                "currency": "USD",
                "quantity": 1,
                "lineTotal": 18.25,
            }],
            "timeline": [],
        }],
        "cancellation": {
            "eligible": eligible,
            "ineligibilityCode": ineligibility,
            "requestId": request_id,
            "requestStatus": request_status,
            "inventoryStatus": None,
            "refund": None,
        },
    }


def _boundary() -> MarketplaceCustomerCapabilityBoundary:
    return MarketplaceCustomerCapabilityBoundary(frozenset({
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        CapabilityFamily.CUSTOMER_COMMERCE_READ,
        CapabilityFamily.CUSTOMER_ORDER_MUTATION,
    }))


class OrderCancellationAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_preview_binds_owned_order_version_and_safe_summary(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(f"/api/v1/orders/{ORDER}", request.url.path)
            self.assertEqual(TOKEN, request.headers["Authorization"])
            return httpx.Response(200, json=_order())

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.preview_my_order_cancellation(
            order_id=ORDER,
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="prepare-cancel",
        )

        self.assertEqual("ORDER_CANCELLATION_READY", result.reason)
        self.assertEqual("CANCEL_ORDER", result.pending_interaction.action)
        binding = CancelOrderConfirmationArguments.model_validate(
            result.pending_interaction.arguments
        )
        self.assertEqual(ORDER, binding.order_id)
        self.assertEqual(4, binding.order_version)
        self.assertEqual(64, len(binding.order_fingerprint))
        self.assertNotIn(ORDER, result.pending_interaction.summary)
        self.assertIn("marketplace", result.pending_interaction.summary)

    async def test_ineligible_preview_does_not_create_confirmation(self) -> None:
        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_order(
                eligible=False, ineligibility="FULFILLMENT_STARTED"
            ))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.preview_my_order_cancellation(
            order_id=ORDER, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="ineligible",
        )

        self.assertEqual("REJECTED", result.status)
        self.assertEqual("ORDER_CANCELLATION_FULFILLMENT_STARTED", result.reason)
        self.assertIsNone(result.pending_interaction)

    async def test_revalidation_rejects_changed_order_version(self) -> None:
        phase = {"version": 4}

        async def handler(_: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_order(version=phase["version"]))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        prepared = await client.preview_my_order_cancellation(
            order_id=ORDER, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="prepare",
        )
        binding = CancelOrderConfirmationArguments.model_validate(
            prepared.pending_interaction.arguments
        )
        phase["version"] = 5

        state = await client.revalidate_order_cancellation_confirmation(
            binding=binding, authorization=TOKEN, correlation_id="stale",
        )

        self.assertFalse(state.valid)
        self.assertEqual("ORDER_VERSION_CONFLICT", state.reason)
        self.assertEqual({f"ORDER:{ORDER}": "5"}, state.current_versions)

    async def test_confirmed_command_is_bodyless_versioned_and_idempotent(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.method == "GET":
                return httpx.Response(200, json=_order(
                    version=5, eligible=False, request_id=REQUEST,
                    request_status="PENDING",
                ))
            self.assertEqual(b"", request.content)
            self.assertEqual('"4"', request.headers["If-Match"])
            self.assertEqual(
                f"agent-action-{INVOCATION}",
                request.headers["Idempotency-Key"],
            )
            return httpx.Response(201, json={
                "orderId": ORDER,
                "cancellationRequestId": REQUEST,
                "status": "CANCELLATION_REQUESTED",
                "requestStatus": "PENDING",
                "version": 5,
                "requestedAt": NOW.isoformat(),
            })

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.cancel_my_order(
            order_id=ORDER, expected_version=4,
            action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="execute",
        )

        self.assertEqual("SUCCEEDED", result.status)
        self.assertEqual("ORDER_CANCELLATION_REQUESTED", result.reason)
        self.assertEqual(2, len(requests))

    async def test_lost_responses_reconcile_from_authoritative_order(self) -> None:
        counts = {"post": 0, "get": 0}

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.method == "POST":
                counts["post"] += 1
                raise httpx.ReadTimeout("lost", request=request)
            counts["get"] += 1
            return httpx.Response(200, json=_order(
                version=5, eligible=False, request_id=REQUEST,
                request_status="PENDING",
            ))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.cancel_my_order(
            order_id=ORDER, expected_version=4,
            action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="lost-response",
        )

        self.assertEqual("ORDER_CANCELLATION_REQUESTED", result.reason)
        self.assertEqual({"post": 2, "get": 1}, counts)


class OrderCancellationPolicyTest(unittest.TestCase):
    def test_cancellation_eligibility_language_does_not_authorize_preparation(self) -> None:
        informational = (
            "Can my latest order still be cancelled? Please only tell me; do not cancel it.",
            "Can I still cancel my latest order?",
            "Is my latest order eligible for cancellation?",
        )
        for prompt in informational:
            with self.subTest(prompt=prompt):
                self.assertTrue(_informational_order_cancellation_request(prompt))
                self.assertFalse(_explicit_order_cancellation_request(prompt))

        for prompt in (
            "Cancel my latest order.",
            "Please cancel the first order.",
            "I want to cancel my order.",
            "Can you cancel my latest order?",
        ):
            with self.subTest(prompt=prompt):
                self.assertTrue(_explicit_order_cancellation_request(prompt))

    def test_execution_audit_hashes_order_and_confirmation_identity(self) -> None:
        observed = ToolObservation(
            tool="cancel_my_order", status="SUCCEEDED",
            reason="ORDER_CANCELLATION_REQUESTED", observedAt=NOW,
        )
        activity = ToolActivity(
            tool="cancel_my_order", status="SUCCEEDED",
            reason="ORDER_CANCELLATION_REQUESTED", observedAt=NOW,
        )
        audit = _tool_audit_arguments(
            activity, (observed,), invocation_id=INVOCATION, sequence=1,
            confirmation_id=INVOCATION, order_id=ORDER,
        )
        serialized = json.dumps(audit)
        self.assertNotIn(ORDER, serialized)
        self.assertNotIn(INVOCATION, serialized)
        self.assertEqual(64, len(audit["orderReference"]))

    def test_explicit_and_follow_up_cancellation_require_private_grounding(self) -> None:
        classifier = MarketplaceScopeClassifier()
        for prompt, recent in (
            ("Cancel my latest order.", ()),
            ("Cancel order 01ARZ3NDEKTSV4RRFFQ69G5FAX.", ()),
            ("Cancel it.", (("ASSISTANT", "I found your latest order."),)),
        ):
            with self.subTest(prompt=prompt):
                result = classifier.classify(
                    current_message=prompt,
                    recent_messages=recent,
                    referenced_listings=(), pending_interaction=None,
                    preference_state={},
                )
                self.assertEqual("PRIVATE_TOOL", result.required_grounding)

    def test_preview_requires_an_actor_owned_order_reference(self) -> None:
        proposal = ToolProposal(
            callId="preview-1", tool="preview_my_order_cancellation",
            arguments={"orderId": ORDER},
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            order_cancellation_mutation_requested=True,
            capability_boundary=_boundary(),
        )
        _, rejection = policy.validate(proposal, step=1)
        self.assertEqual("FORBIDDEN", rejection.reason)

        policy.record(ToolObservation(
            tool="list_my_orders", status="SUCCEEDED", reason="ORDERS_AVAILABLE",
            orderReferences=({"orderId": ORDER, "position": 1},),
        ))
        arguments, rejection = policy.validate(proposal, step=2)
        self.assertIsNone(rejection)
        self.assertEqual(ORDER, arguments.order_id)

    def test_preview_rejects_informational_intent_even_with_owned_reference(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=(ToolObservation(
                tool="list_my_orders", status="SUCCEEDED", reason="ORDERS_AVAILABLE",
                orderReferences=({"orderId": ORDER, "position": 1},),
            ),),
            order_cancellation_mutation_requested=False,
            capability_boundary=_boundary(),
        )

        _, rejection = policy.validate(ToolProposal(
            callId="preview-info", tool="preview_my_order_cancellation",
            arguments={"orderId": ORDER},
        ), step=1)

        self.assertEqual("MUTATION_INTENT_REQUIRED", rejection.reason)

    def test_model_can_never_directly_propose_execution(self) -> None:
        _, rejection = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            capability_boundary=_boundary(),
        ).validate(ToolProposal(
            callId="cancel-1", tool="cancel_my_order",
            arguments={"orderId": ORDER},
        ), step=1)
        self.assertEqual("CONFIRMATION_REQUIRED", rejection.reason)


class _NoModel:
    async def decide(self, **_: object) -> object:
        raise AssertionError("confirmed cancellation must not invoke the model")

    async def close(self) -> None:
        return None


class _ConfirmedRegistry:
    names = ("cancel_my_order",)
    capability_boundary = _boundary()

    def __init__(self) -> None:
        self.executions = 0

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return ({"name": "cancel_my_order"},)

    async def execute_confirmed_order_cancellation(
        self, **kwargs: object
    ) -> ToolObservation:
        self.executions += 1
        if kwargs["action_reference"] != INVOCATION:
            raise AssertionError("confirmation identity must be the action key")
        return ToolObservation(
            tool="cancel_my_order", status="SUCCEEDED",
            reason="ORDER_CANCELLATION_REQUESTED",
        )


class _InformationalModel:
    def __init__(self) -> None:
        self.calls = 0

    async def decide(self, **_: object) -> ModelDecision:
        self.calls += 1
        if self.calls == 1:
            return ModelDecision(toolProposal=ToolProposal(
                callId="orders", tool="list_my_orders", arguments={"limit": 5},
            ))
        if self.calls == 2:
            return ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="preview_my_order_cancellation",
                arguments={"orderId": ORDER},
            ))
        raise AssertionError("owned eligibility must terminate after the order read")

    async def close(self) -> None:
        return None


class _InformationalRegistry:
    names = ("list_my_orders", "get_my_order", "preview_my_order_cancellation")
    capability_boundary = _boundary()

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return tuple({"name": name} for name in self.names)

    async def execute(self, *, tool: str, **_: object) -> ToolObservation:
        if tool == "list_my_orders":
            return ToolObservation(
                tool=tool, status="SUCCEEDED", reason="ORDERS_AVAILABLE",
                orderReferences=({"orderId": ORDER, "position": 1},),
            )
        if tool == "get_my_order":
            return ToolObservation(
                tool=tool, status="SUCCEEDED", reason="ORDER_FOUND",
                order={
                    "orderId": ORDER,
                    "status": "CONFIRMED",
                    "paymentStatus": "SUCCEEDED",
                    "total": {"amount": Decimal("18.25"), "currency": "USD"},
                    "version": 4,
                    "createdAt": NOW,
                    "updatedAt": NOW,
                    "groups": (),
                    "cancellation": {"eligible": True},
                },
                orderReferences=({"orderId": ORDER, "position": 1},),
            )
        raise AssertionError(f"unexpected tool: {tool}")


class OrderCancellationOrchestratorTest(unittest.IsolatedAsyncioTestCase):
    async def test_informational_eligibility_reads_order_without_preparing(self) -> None:
        model = _InformationalModel()
        orchestrator = MarketplaceAgentV2Orchestrator(
            model, _InformationalRegistry()
        )

        result = await orchestrator.run(
            actor_user_id=ACTOR,
            actor_authorization=TOKEN,
            current_message=(
                "Can my latest order still be cancelled? Please only tell me; "
                "do not cancel it."
            ),
            recent_messages=(),
            referenced_listings=(),
            correlation_id="informational-cancellation",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL",
                confidence="HIGH", marketplaceContextUsed=True,
                reasonCode="PRIVATE_ORDER_REQUEST",
            ),
        )

        self.assertEqual(2, model.calls)
        self.assertIsNone(result.pending_interaction)
        self.assertIn("currently eligible", result.message.content)
        self.assertIn("did not request cancellation", result.message.content)

    async def test_consumed_confirmation_executes_exactly_once_without_model(self) -> None:
        registry = _ConfirmedRegistry()
        orchestrator = MarketplaceAgentV2Orchestrator(_NoModel(), registry)
        binding = {
            "orderId": ORDER,
            "orderVersion": 4,
            "orderFingerprint": "a" * 64,
            "total": "18.25",
            "currency": "USD",
        }
        pending = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION, confirmationId=INVOCATION,
            type="CONFIRM_ACTION", action="CANCEL_ORDER",
            summary="Cancel your whole order.", arguments=binding,
            status="CONSUMED", createdAt=NOW,
        )

        result = await orchestrator.run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Yes", recent_messages=(), referenced_listings=(),
            confirmed_interaction=pending, correlation_id="confirmed-cancel",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL",
                confidence="HIGH", marketplaceContextUsed=True,
                reasonCode="PENDING_INTERACTION_RESPONSE",
            ),
        )

        self.assertEqual(1, registry.executions)
        self.assertEqual(0, result.decision_count)
        self.assertIn("accepted", result.message.content)


class _CapturedConfirmations:
    def __init__(self) -> None:
        self.request = None

    async def prepare(self, request, *, pending_interaction, now):
        self.request = request
        return ConfirmationRecord(
            confirmation_id=request.confirmation_id,
            actor_user_id=request.actor_user_id,
            session_id=request.session_id,
            originating_invocation_id=request.originating_invocation_id,
            workflow_id=request.workflow_id,
            capability=request.capability,
            capability_version=request.capability_version,
            action_name=request.action_name,
            normalized_arguments=request.normalized_arguments,
            targets=request.targets,
            financial_facts=request.financial_facts,
            action_fingerprint="b" * 64,
            human_summary=request.human_summary,
            risk_level=request.risk_level,
            state=ConfirmationState.PENDING,
            created_at=now,
            expires_at=request.expires_at,
            action_key=f"agent-action-{request.confirmation_id}",
        )


class OrderCancellationPersistenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_prepare_persists_exact_level_three_order_binding(self) -> None:
        persistence = object.__new__(MarketplaceAgentV2Persistence)
        persistence._confirmation_ttl_seconds = 900
        persistence._confirmations = _CapturedConfirmations()
        binding = {
            "orderId": ORDER,
            "orderVersion": 4,
            "orderFingerprint": "a" * 64,
            "total": "18.25",
            "currency": "USD",
        }
        interaction = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION, confirmationId=INVOCATION,
            type="CONFIRM_ACTION", action="CANCEL_ORDER",
            summary="Request cancellation of your whole order.",
            arguments=binding, status="WAITING", createdAt=NOW,
        )

        stored = await persistence.prepare_confirmation(
            session_id=ORDER, actor_user_id=ACTOR,
            originating_invocation_id=LISTING,
            interaction=interaction, correlation_id="persist-cancel", now=NOW,
        )
        request = persistence._confirmations.request

        self.assertEqual("CUSTOMER_ORDER_CANCELLATION", request.workflow_id)
        self.assertEqual("cancel_my_order", request.capability)
        self.assertEqual("CANCEL_ORDER", request.action_name)
        self.assertEqual(3, request.risk_level)
        self.assertEqual("ORDER", request.targets[0].resource_type)
        self.assertEqual(ORDER, request.targets[0].resource_id)
        self.assertEqual("4", request.targets[0].version_token)
        self.assertEqual("18.25", str(request.financial_facts[0].amount))
        self.assertEqual("WAITING", stored.status)


if __name__ == "__main__":
    unittest.main()
