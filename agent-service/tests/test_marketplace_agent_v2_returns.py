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
from msb_agent_service.marketplace_agent_v2.confirmations import (
    ConfirmationRecord,
    ConfirmationState,
)
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2OrchestrationFailure,
    MarketplaceAgentV2Orchestrator,
    _CustomerTextStream,
    _customer_safe_text,
    _explicit_return_request,
    _informational_return_request,
    _partial_return_request,
    _required_return_reason,
    _return_reason_from_context,
    _return_request_mutation_intent,
    _step_limit_content,
    _validate_terminal_response,
)
from msb_agent_service.marketplace_agent_v2.persistence import (
    MarketplaceAgentV2Persistence,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    ModelDecision,
    SubmitReturnConfirmationArguments,
    ToolObservation,
    ToolProposal,
    ToolActivity,
)
from msb_agent_service.marketplace_agent_v2.scope import (
    MarketplaceScopeClassifier,
    hard_safety_response,
)
from msb_agent_service.marketplace_agent_v2.service import (
    _public_pending,
    _tool_audit_arguments,
)


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
ORDER = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
GROUP = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
SECOND_LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
RETURN = "01ARZ3NDEKTSV4RRFFQ69G5FB2"
TOKEN = "Bearer delegated-customer-token"
NOW = datetime(2026, 9, 8, 12, 0, tzinfo=UTC)


def _group(*, version: int = 7, group_id: str = GROUP) -> dict[str, object]:
    return {
        "businessOrderId": group_id,
        "businessId": "01ARZ3NDEKTSV4RRFFQ69G5FB3",
        "storeId": "01ARZ3NDEKTSV4RRFFQ69G5FB4",
        "storeName": "Harbor Business",
        "status": "DELIVERED",
        "totalAmount": 48.00,
        "currency": "USD",
        "version": version,
        "items": [
            {
                "listingId": LISTING,
                "title": "Harbor Keyboard",
                "unitPrice": 36.00,
                "currency": "USD",
                "quantity": 1,
                "lineTotal": 36.00,
            },
            {
                "listingId": SECOND_LISTING,
                "title": "Harbor Mouse",
                "unitPrice": 12.00,
                "currency": "USD",
                "quantity": 1,
                "lineTotal": 12.00,
            },
        ],
        "timeline": [],
        "shipment": {
            "carrierDisplayName": "Local demo carrier",
            "serviceDisplayName": "Demo delivery",
            "status": "DELIVERED",
            "deliveredAt": NOW.isoformat(),
        },
    }


def _order(*, version: int = 7, extra_group: bool = False) -> dict[str, object]:
    groups = [_group(version=version)]
    if extra_group:
        second = _group(
            version=version,
            group_id="01ARZ3NDEKTSV4RRFFQ69G5FB5",
        )
        second["storeName"] = "Other Store"
        second["items"] = [{
            "listingId": "01ARZ3NDEKTSV4RRFFQ69G5FB6",
            "title": "Other item",
            "unitPrice": 5.00,
            "currency": "USD",
            "quantity": 1,
            "lineTotal": 5.00,
        }]
        groups.append(second)
    return {
        "orderId": ORDER,
        "status": "CONFIRMED",
        "paymentStatus": "SUCCEEDED",
        "totalAmount": 48.00,
        "currency": "USD",
        "version": 3,
        "createdAt": NOW.isoformat(),
        "updatedAt": NOW.isoformat(),
        "groups": groups,
    }


def _return(
    *, version: int = 7, existing: bool = False,
) -> dict[str, object]:
    return {
        "eligible": not existing,
        "ineligibilityCode": "RETURN_ALREADY_EXISTS" if existing else None,
        "returnId": RETURN if existing else None,
        "orderId": ORDER,
        "businessOrderId": GROUP,
        "storeName": "Harbor Business",
        "reasonCode": "DAMAGED" if existing else None,
        "buyerComment": "Keyboard arrived damaged." if existing else None,
        "requestedAt": NOW.isoformat() if existing else None,
        "windowExpiresAt": NOW.isoformat(),
        "status": "RETURN_REQUESTED" if existing else None,
        "refundStatus": "NONE" if existing else None,
        "inventoryDisposition": None,
        "receivedAt": None,
        "refundId": None,
        "refundAmount": None,
        "currency": "USD",
        "completedAt": None,
        "version": version,
        "shipment": None,
        "timeline": [],
    }


def _boundary() -> MarketplaceCustomerCapabilityBoundary:
    return MarketplaceCustomerCapabilityBoundary(frozenset({
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        CapabilityFamily.CUSTOMER_COMMERCE_READ,
        CapabilityFamily.CUSTOMER_RETURN_REQUEST,
    }))


class ReturnAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_prepare_resolves_owned_item_and_binds_whole_group(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(TOKEN, request.headers["Authorization"])
            if request.url.path.endswith("/return"):
                return httpx.Response(200, json=_return())
            return httpx.Response(200, json=_order())

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.prepare_my_return_request(
            order_id=ORDER,
            listing_id=LISTING,
            reason_code="DAMAGED",
            comment="Keyboard arrived damaged.",
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="prepare-return",
        )

        self.assertEqual("RETURN_REQUEST_READY", result.reason)
        self.assertEqual("SUBMIT_RETURN_REQUEST", result.pending_interaction.action)
        binding = SubmitReturnConfirmationArguments.model_validate(
            result.pending_interaction.arguments
        )
        self.assertEqual(GROUP, binding.business_order_id)
        self.assertEqual(7, binding.group_version)
        self.assertEqual("DAMAGED", binding.reason_code)
        self.assertIn("entire Harbor Business order group", result.pending_interaction.summary)
        self.assertIn("Harbor Keyboard", result.pending_interaction.summary)
        self.assertIn("Harbor Mouse", result.pending_interaction.summary)
        serialized = result.return_request.model_dump_json()
        self.assertNotIn(GROUP, serialized)
        self.assertNotIn(RETURN, serialized)

    async def test_multiple_groups_without_item_is_ambiguous(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/return"):
                return httpx.Response(200, json={
                    **_return(),
                    "businessOrderId": request.url.path.split("/")[-2],
                })
            return httpx.Response(200, json=_order(extra_group=True))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.get_my_return(
            order_id=ORDER, listing_id=None, authorization=TOKEN,
            correlation_id="ambiguous-return",
        )

        self.assertEqual("REJECTED", result.status)
        self.assertEqual("RETURN_GROUP_AMBIGUOUS", result.reason)

    async def test_exact_owned_store_name_resolves_one_group(self) -> None:
        requested_groups: list[str] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/return"):
                group_id = request.url.path.split("/")[-2]
                requested_groups.append(group_id)
                return httpx.Response(200, json={
                    **_return(), "businessOrderId": group_id,
                    "storeName": "Other Store",
                })
            return httpx.Response(200, json=_order(extra_group=True))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.get_my_return(
            order_id=ORDER, listing_id=None, store_name="other store",
            authorization=TOKEN, correlation_id="store-return",
        )

        self.assertEqual("SUCCEEDED", result.status)
        self.assertEqual(["01ARZ3NDEKTSV4RRFFQ69G5FB5"], requested_groups)

        missing = await client.get_my_return(
            order_id=ORDER, listing_id=None, store_name="Invented Store",
            authorization=TOKEN, correlation_id="missing-store-return",
        )
        self.assertEqual("REJECTED", missing.status)
        self.assertEqual("RETURN_GROUP_AMBIGUOUS", missing.reason)
        self.assertEqual(["01ARZ3NDEKTSV4RRFFQ69G5FB5"], requested_groups)

    async def test_multiple_groups_resolve_one_existing_return(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/return"):
                group_id = request.url.path.split("/")[-2]
                return httpx.Response(200, json={
                    **_return(existing=group_id == GROUP),
                    "businessOrderId": group_id,
                })
            return httpx.Response(200, json=_order(extra_group=True))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.get_my_return(
            order_id=ORDER, listing_id=None, authorization=TOKEN,
            correlation_id="existing-return",
        )

        self.assertEqual("SUCCEEDED", result.status)
        self.assertEqual("RETURN_AVAILABLE", result.reason)
        self.assertEqual("RETURN_REQUESTED", result.return_request.status)

    async def test_multiple_group_status_scan_fails_closed(self) -> None:
        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/return"):
                group_id = request.url.path.split("/")[-2]
                if group_id == GROUP:
                    return httpx.Response(503)
                return httpx.Response(200, json={
                    **_return(), "businessOrderId": group_id,
                })
            return httpx.Response(200, json=_order(extra_group=True))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.get_my_return(
            order_id=ORDER, listing_id=None, authorization=TOKEN,
            correlation_id="failed-return-scan",
        )

        self.assertEqual("FAILED", result.status)
        self.assertEqual("RETURN_UPSTREAM_UNAVAILABLE", result.reason)

    async def test_revalidation_rejects_changed_group(self) -> None:
        phase = {"version": 7}

        async def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/return"):
                return httpx.Response(200, json=_return(version=phase["version"]))
            return httpx.Response(200, json=_order(version=phase["version"]))

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        prepared = await client.prepare_my_return_request(
            order_id=ORDER, listing_id=LISTING, reason_code="DAMAGED",
            comment=None, action_reference=INVOCATION,
            authorization=TOKEN, correlation_id="prepare",
        )
        binding = SubmitReturnConfirmationArguments.model_validate(
            prepared.pending_interaction.arguments
        )
        phase["version"] = 8

        state = await client.revalidate_return_confirmation(
            binding=binding, authorization=TOKEN, correlation_id="stale",
        )

        self.assertFalse(state.valid)
        self.assertEqual("RETURN_VERSION_CONFLICT", state.reason)
        self.assertEqual({f"BUSINESS_ORDER_GROUP:{GROUP}": "8"}, state.current_versions)

    async def test_confirmed_submission_uses_existing_buyer_api(self) -> None:
        requests: list[httpx.Request] = []

        async def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.method == "GET":
                return httpx.Response(200, json=_order(version=8))
            self.assertEqual(
                f"/api/v1/orders/{ORDER}/groups/{GROUP}/returns",
                request.url.path,
            )
            self.assertEqual('"7"', request.headers["If-Match"])
            self.assertEqual(
                f"agent-action-{INVOCATION}", request.headers["Idempotency-Key"]
            )
            self.assertEqual(
                {"reasonCode": "WRONG_ITEM", "comment": "Wrong color."},
                json.loads(request.content),
            )
            return httpx.Response(200, json={
                **_return(version=0, existing=True),
                "reasonCode": "WRONG_ITEM",
                "buyerComment": "Wrong color.",
            })

        client = CommerceReadClient(
            "http://order", timeout_seconds=1,
            transport=httpx.MockTransport(handler),
        )
        result = await client.submit_my_return_request(
            binding=SubmitReturnConfirmationArguments(
                orderId=ORDER, businessOrderId=GROUP, groupVersion=7,
                groupFingerprint="a" * 64, reasonCode="WRONG_ITEM",
                comment="Wrong color.",
            ),
            action_reference=INVOCATION,
            authorization=TOKEN,
            correlation_id="submit-return",
        )

        self.assertEqual("SUCCEEDED", result.status)
        self.assertEqual("RETURN_REQUEST_SUBMITTED", result.reason)
        self.assertEqual("RETURN_REQUESTED", result.return_request.status)
        self.assertEqual(2, len(requests))


class ReturnPolicyTest(unittest.TestCase):
    def test_intent_detection_separates_status_from_submission(self) -> None:
        self.assertTrue(_informational_return_request("Has my refund finished?"))
        self.assertTrue(_informational_return_request("Can I get a refund for my last order?"))
        self.assertTrue(_explicit_return_request(
            "The keyboard from my last order arrived damaged. I want to return it."
        ))
        self.assertTrue(_explicit_return_request("I received the wrong item."))
        self.assertEqual(
            "DAMAGED",
            _return_reason_from_context(
                "I want to return it because it arrived damaged.",
                (),
            ),
        )
        self.assertEqual(
            "NO_LONGER_NEEDED",
            _return_reason_from_context(
                "I changed my mind and no longer need this one.",
                (("USER", "An older return was for a damaged item."),),
            ),
        )
        self.assertIsNone(_return_reason_from_context(
            "Return only the mouse from my order.",
            (("USER", "An older return was for a damaged item."),),
        ))
        self.assertTrue(_return_request_mutation_intent(
            "Damaged.",
            (
                ("USER", "I want to return the keyboard from my last order."),
                ("ASSISTANT", "What is the reason for the return?"),
            ),
        ))
        self.assertFalse(_return_request_mutation_intent(
            "Damaged.",
            (("ASSISTANT", "What product would you like to find?"),),
        ))
        self.assertTrue(_partial_return_request(
            "Return only the mouse from the second order."
        ))
        self.assertTrue(_return_request_mutation_intent(
            "Prepare a return request for the entire Harbor Business store group.",
            (),
        ))
        recent = (
            ("USER", "The keyboard arrived damaged. I want to return it."),
            ("ASSISTANT", "Which store group do you want to return?"),
        )
        self.assertTrue(_return_request_mutation_intent(
            "The Harbor Business store group.", recent,
        ))
        self.assertEqual(
            "DAMAGED",
            _required_return_reason("The Harbor Business store group.", recent),
        )

    def test_prepare_requires_owned_order_and_item_references(self) -> None:
        proposal = ToolProposal(
            callId="prepare", tool="prepare_my_return_request",
            arguments={
                "orderId": ORDER,
                "listingId": LISTING,
                "reasonCode": "DAMAGED",
                "comment": "Damaged on arrival.",
            },
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            return_request_mutation_requested=True,
            required_return_reason="DAMAGED",
            return_comment_source="The item was damaged on arrival.",
            capability_boundary=_boundary(),
        )
        _, rejection = policy.validate(proposal, step=1)
        self.assertEqual("FORBIDDEN", rejection.reason)

        policy.record(ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            orderReferences=({"orderId": ORDER},),
            orderItemReferences=({
                "orderId": ORDER, "listingId": LISTING,
                "title": "Harbor Keyboard",
            },),
        ))
        policy.record(ToolObservation(
            tool="get_my_return", status="SUCCEEDED",
            reason="RETURN_ELIGIBLE",
            orderReferences=({"orderId": ORDER},),
        ))
        arguments, rejection = policy.validate(proposal, step=2)
        self.assertIsNone(rejection)
        self.assertEqual("DAMAGED", arguments.reason_code)

        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=policy.prior_observations,
            return_request_mutation_requested=True,
            required_return_reason="DAMAGED",
            return_comment_source="The item arrived damaged.",
            capability_boundary=_boundary(),
        )
        _, rejection = policy.validate(proposal, step=1)
        self.assertEqual("INVALID_ARGUMENTS", rejection.reason)

    def test_prepare_accepts_only_an_exact_grounded_store_name(self) -> None:
        observation = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            orderReferences=({"orderId": ORDER},),
            orderItemReferences=({
                "orderId": ORDER, "listingId": LISTING,
                "title": "Harbor Keyboard", "storeName": "Harbor Business",
            },),
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=(observation,),
            return_request_mutation_requested=True,
            required_return_reason="DAMAGED",
            return_eligible_this_turn=True,
            capability_boundary=_boundary(),
        )
        proposal = ToolProposal(
            callId="store-return", tool="prepare_my_return_request",
            arguments={
                "orderId": ORDER, "storeName": "harbor business",
                "reasonCode": "DAMAGED", "comment": None,
            },
        )

        arguments, rejection = policy.validate(proposal, step=1)
        self.assertIsNone(rejection)
        self.assertEqual("harbor business", arguments.store_name)

        _, rejection = policy.validate(proposal.model_copy(update={
            "call_id": "invented-store",
            "arguments": {
                **proposal.arguments, "storeName": "Invented Store",
            },
        }), step=2)
        self.assertEqual("FORBIDDEN", rejection.reason)

    def test_partial_item_request_cannot_prepare_a_whole_group_return(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=(ToolObservation(
                tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
                orderReferences=({"orderId": ORDER},),
                orderItemReferences=({
                    "orderId": ORDER, "listingId": LISTING,
                    "title": "Harbor Keyboard",
                },),
            ),),
            return_request_mutation_requested=True,
            partial_return_requested=True,
            required_return_reason="DAMAGED",
            return_eligible_this_turn=True,
            capability_boundary=_boundary(),
        )

        _, rejection = policy.validate(ToolProposal(
            callId="partial-return", tool="prepare_my_return_request",
            arguments={
                "orderId": ORDER, "listingId": LISTING,
                "reasonCode": "DAMAGED", "comment": None,
            },
        ), step=1)

        self.assertEqual("PARTIAL_RETURN_UNSUPPORTED", rejection.reason)

    def test_only_item_can_prepare_when_it_is_the_entire_store_group(self) -> None:
        observation = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            order={
                "orderId": ORDER,
                "status": "CONFIRMED",
                "paymentStatus": "SUCCEEDED",
                "total": {"amount": Decimal("36.00"), "currency": "USD"},
                "version": 3,
                "createdAt": NOW,
                "updatedAt": NOW,
                "groups": ({
                    "storeName": "Harbor Business",
                    "status": "DELIVERED",
                    "total": {"amount": Decimal("36.00"), "currency": "USD"},
                    "items": ({
                        "listingId": LISTING,
                        "title": "Harbor Keyboard",
                        "quantity": 1,
                        "purchaseUnitPrice": Decimal("36.00"),
                        "lineTotal": Decimal("36.00"),
                        "currency": "USD",
                    },),
                    "timeline": (),
                    "shipment": None,
                },),
            },
            orderReferences=({"orderId": ORDER},),
            orderItemReferences=({
                "orderId": ORDER, "listingId": LISTING,
                "title": "Harbor Keyboard",
            },),
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=(observation,),
            return_request_mutation_requested=True,
            partial_return_requested=True,
            required_return_reason="DAMAGED",
            return_eligible_this_turn=True,
            capability_boundary=_boundary(),
        )

        arguments, rejection = policy.validate(ToolProposal(
            callId="single-item-return", tool="prepare_my_return_request",
            arguments={
                "orderId": ORDER, "listingId": LISTING,
                "reasonCode": "DAMAGED", "comment": None,
            },
        ), step=1)

        self.assertIsNone(rejection)
        self.assertEqual(LISTING, arguments.listing_id)

    def test_model_cannot_directly_submit_or_receive_finance_tools(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            capability_boundary=_boundary(),
        )
        _, rejection = policy.validate(ToolProposal(
            callId="submit", tool="submit_my_return_request",
            arguments={"orderId": ORDER},
        ), step=1)
        self.assertEqual("CONFIRMATION_REQUIRED", rejection.reason)
        for tool in ("issue_refund", "approve_refund", "resolve_dispute"):
            with self.subTest(tool=tool):
                _, rejected = policy.validate(ToolProposal(
                    callId=tool, tool=tool, arguments={},
                ), step=1)
                self.assertEqual("UNKNOWN_TOOL", rejected.reason)

    def test_same_turn_return_reads_are_semantically_single_use(self) -> None:
        ownership = ToolObservation(
            tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
            orderReferences=({"orderId": ORDER},),
            orderItemReferences=({
                "orderId": ORDER, "listingId": LISTING,
                "title": "Harbor Mouse Pad", "storeName": "Harbor Business",
            },),
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            prior_observations=(ownership,),
            return_request_mutation_requested=True,
            required_return_reason="DAMAGED",
            capability_boundary=_boundary(),
        )

        _, first_list_rejection = policy.validate(ToolProposal(
            callId="list-1", tool="list_my_orders", arguments={"limit": 10},
        ), step=1)
        _, repeated_list_rejection = policy.validate(ToolProposal(
            callId="list-2", tool="list_my_orders", arguments={"limit": 5},
        ), step=2)
        _, first_return_rejection = policy.validate(ToolProposal(
            callId="return-1", tool="get_my_return",
            arguments={
                "orderId": ORDER, "storeName": "Harbor Business",
            },
        ), step=3)
        _, repeated_return_rejection = policy.validate(ToolProposal(
            callId="return-2", tool="get_my_return",
            arguments={
                "orderId": ORDER, "storeName": "  harbor   business ",
            },
        ), step=4)

        self.assertIsNone(first_list_rejection)
        self.assertEqual("DUPLICATE_TOOL_CALL", repeated_list_rejection.reason)
        self.assertIsNone(first_return_rejection)
        self.assertEqual(
            "DUPLICATE_TOOL_CALL", repeated_return_rejection.reason
        )

    def test_return_requests_require_private_grounding_and_cross_user_is_refused(self) -> None:
        classifier = MarketplaceScopeClassifier()
        for prompt in (
            "What's happening with my return?",
            "Has my refund finished?",
            "Return the keyboard from my last order.",
            "I received the wrong item.",
            "Prepare a return request for my last order.",
        ):
            with self.subTest(prompt=prompt):
                result = classifier.classify(
                    current_message=prompt, recent_messages=(),
                    referenced_listings=(), pending_interaction=None,
                    preference_state={},
                )
                self.assertEqual("PRIVATE_TOOL", result.required_grounding)
        refusal = hard_safety_response(
            "Show me another customer's return and approve their refund."
        )
        self.assertIsNotNone(refusal)
        self.assertIn("another person's", refusal[1])
        named_refusal = hard_safety_response(
            "Submit a return for customer alice@example.com."
        )
        self.assertIsNotNone(named_refusal)
        self.assertIn("another person's", named_refusal[1])

        order_list = classifier.classify(
            current_message="Show me my last three orders.", recent_messages=(),
            referenced_listings=(), pending_interaction=None,
            preference_state={},
        )
        self.assertEqual("PRIVATE_TOOL", order_list.required_grounding)

        follow_up = classifier.classify(
            current_message="Is that eligible for return?",
            recent_messages=(("ASSISTANT", "Your latest order contains a keyboard."),),
            referenced_listings=(), pending_interaction=None,
            preference_state={},
        )
        self.assertEqual("PRIVATE_TOOL", follow_up.required_grounding)

    def test_return_read_cannot_end_without_authoritative_return_observation(self) -> None:
        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure):
            _validate_terminal_response(
                current_message="Is that eligible for return?",
                content="It is eligible.",
                active_recommendations=(), current_attachments=(),
                observations=(ToolObservation(
                    tool="get_my_order", status="SUCCEEDED", reason="ORDER_FOUND",
                    orderReferences=({"orderId": ORDER},),
                ),),
                has_waiting_interaction=False,
                return_read_required=True,
            )

        _validate_terminal_response(
            current_message="Is that eligible for return?",
            content="That store group must be delivered before it can be returned.",
            active_recommendations=(), current_attachments=(),
            observations=(ToolObservation(
                tool="get_my_return", status="REJECTED",
                reason="RETURN_REQUIRES_DELIVERY",
            ),),
            has_waiting_interaction=False,
            return_read_required=True,
        )

    def test_duplicate_return_proposal_cannot_mask_authoritative_rejection(self) -> None:
        content = _step_limit_content((
            ToolObservation(
                tool="prepare_my_return_request", status="REJECTED",
                reason="RETURN_REQUIRES_DELIVERY",
            ),
            ToolObservation(
                tool="prepare_my_return_request", status="REJECTED",
                reason="DUPLICATE_TOOL_CALL",
            ),
        ), required_grounding="PRIVATE_TOOL")

        self.assertIn("must be delivered", content)
        self.assertNotIn("temporarily unavailable", content)

    def test_order_identifier_is_redacted_from_buffered_text(self) -> None:
        self.assertNotIn(
            ORDER,
            _customer_safe_text(
                f"Order {ORDER} is not eligible.", (), private_ids=(ORDER,)
            ),
        )

    def test_public_confirmation_and_audit_hide_internal_return_identity(self) -> None:
        pending = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION, confirmationId=INVOCATION,
            type="CONFIRM_ACTION", action="SUBMIT_RETURN_REQUEST",
            summary="Submit a return request.",
            arguments={
                "orderId": ORDER, "businessOrderId": GROUP,
                "groupVersion": 7, "groupFingerprint": "a" * 64,
                "reasonCode": "DAMAGED", "comment": None,
            },
            status="WAITING", createdAt=NOW,
        )
        self.assertEqual({}, _public_pending(pending).arguments)
        observation = ToolObservation(
            tool="submit_my_return_request", status="SUCCEEDED",
            reason="RETURN_REQUEST_SUBMITTED", observedAt=NOW,
        )
        activity = ToolActivity(
            tool="submit_my_return_request", status="SUCCEEDED",
            reason="RETURN_REQUEST_SUBMITTED", observedAt=NOW,
        )
        audit = _tool_audit_arguments(
            activity, (observation,), invocation_id=INVOCATION, sequence=1,
            confirmation_id=INVOCATION, business_order_id=GROUP,
        )
        serialized = json.dumps(audit)
        self.assertNotIn(INVOCATION, serialized)
        self.assertNotIn(GROUP, serialized)


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
            normalized_arguments=dict(request.normalized_arguments),
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


class ReturnPersistenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_prepare_persists_level_three_group_binding(self) -> None:
        persistence = object.__new__(MarketplaceAgentV2Persistence)
        persistence._confirmation_ttl_seconds = 900
        persistence._confirmations = _CapturedConfirmations()
        interaction = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION, confirmationId=INVOCATION,
            type="CONFIRM_ACTION", action="SUBMIT_RETURN_REQUEST",
            summary="Submit a whole-group return request.",
            arguments={
                "orderId": ORDER,
                "businessOrderId": GROUP,
                "groupVersion": 7,
                "groupFingerprint": "a" * 64,
                "reasonCode": "DAMAGED",
                "comment": "Damaged on arrival.",
            },
            status="WAITING", createdAt=NOW,
        )

        stored = await persistence.prepare_confirmation(
            session_id=SESSION, actor_user_id=ACTOR,
            originating_invocation_id=ORDER,
            interaction=interaction, correlation_id="persist-return", now=NOW,
        )
        request = persistence._confirmations.request

        self.assertEqual("CUSTOMER_RETURN_REQUEST", request.workflow_id)
        self.assertEqual("submit_my_return_request", request.capability)
        self.assertEqual("SUBMIT_RETURN_REQUEST", request.action_name)
        self.assertEqual(3, request.risk_level)
        self.assertEqual("BUSINESS_ORDER_GROUP", request.targets[0].resource_type)
        self.assertEqual(GROUP, request.targets[0].resource_id)
        self.assertEqual((), request.financial_facts)
        self.assertEqual("WAITING", stored.status)


class _NoModel:
    async def decide(self, **_: object) -> object:
        raise AssertionError("confirmed return must not invoke the model")

    async def close(self) -> None:
        return None


class _ConfirmedRegistry:
    names = ("submit_my_return_request",)
    capability_boundary = _boundary()

    def __init__(self) -> None:
        self.executions = 0

    def provider_schemas(self):
        return ({"name": "submit_my_return_request"},)

    async def execute_confirmed_return_request(self, **kwargs: object) -> ToolObservation:
        self.executions += 1
        self.assertion_reference = kwargs["action_reference"]
        return ToolObservation(
            tool="submit_my_return_request", status="SUCCEEDED",
            reason="RETURN_REQUEST_SUBMITTED",
        )


class _ScriptedReturnModel:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[AgentContext] = []
        self.tools: list[tuple[str, ...]] = []

    async def decide(self, **kwargs: object) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        self.tools.append(tuple(
            str(item["name"]) for item in kwargs["tools"]
        ))
        return self.decisions.pop(0)

    async def close(self) -> None:
        return None


class _ScriptedReturnRegistry:
    names = (
        "list_my_orders", "get_my_order", "get_my_return",
        "prepare_my_return_request",
    )
    capability_boundary = _boundary()

    def __init__(
        self, *, return_reason: str = "RETURN_REQUIRES_DELIVERY",
        item_title: str = "Harbor Keyboard",
    ) -> None:
        self.executed: list[str] = []
        self.return_listing_id: str | None = None
        self.return_reason = return_reason
        self.item_title = item_title

    def provider_schemas(self):
        return tuple({"name": name} for name in self.names)

    async def execute(self, *, tool: str, arguments: object, **_: object) -> ToolObservation:
        self.executed.append(tool)
        if tool == "list_my_orders":
            return ToolObservation(
                tool=tool, status="SUCCEEDED", reason="ORDERS_AVAILABLE",
                orderReferences=({"orderId": ORDER},),
            )
        if tool == "get_my_order":
            return ToolObservation(
                tool=tool, status="SUCCEEDED", reason="ORDER_FOUND",
                orderReferences=({"orderId": ORDER},),
                orderItemReferences=({
                    "orderId": ORDER, "listingId": LISTING,
                    "title": self.item_title, "storeName": "Harbor Business",
                },),
            )
        if tool in {"get_my_return", "prepare_my_return_request"}:
            self.return_listing_id = getattr(arguments, "listing_id", None)
        if tool == "get_my_return":
            return ToolObservation(
                tool=tool,
                status=(
                    "SUCCEEDED"
                    if self.return_reason == "RETURN_ELIGIBLE"
                    else "REJECTED"
                ),
                reason=self.return_reason,
                orderReferences=({"orderId": ORDER},),
            )
        if tool == "prepare_my_return_request":
            return ToolObservation(
                tool=tool, status="SUCCEEDED", reason="RETURN_REQUEST_READY",
                pendingInteraction=MarketplaceAgentV2PendingInteraction(
                    id=INVOCATION, confirmationId=INVOCATION,
                    type="CONFIRM_ACTION", action="SUBMIT_RETURN_REQUEST",
                    summary="Submit a whole-group return request.",
                    arguments={
                        "orderId": ORDER, "businessOrderId": GROUP,
                        "groupVersion": 7, "groupFingerprint": "a" * 64,
                        "reasonCode": "DAMAGED", "comment": None,
                    },
                    status="WAITING", createdAt=NOW,
                ),
            )
        raise AssertionError(f"unexpected tool: {tool}")


class ReturnOrchestratorTest(unittest.IsolatedAsyncioTestCase):
    async def test_fresh_damaged_item_request_reaches_preparation_in_four_steps(
        self,
    ) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={"orderId": ORDER, "listingId": LISTING},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="prepare", tool="prepare_my_return_request",
                arguments={
                    "orderId": ORDER, "listingId": LISTING,
                    "reasonCode": "DAMAGED", "comment": None,
                },
            )),
        ])
        registry = _ScriptedReturnRegistry(
            return_reason="RETURN_ELIGIBLE", item_title="Harbor Mouse Pad"
        )

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "The Harbor Mouse Pad from my last order arrived damaged. "
                "I want to return it."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="fresh-damaged-item-return",
        )

        self.assertEqual(4, result.decision_count)
        self.assertEqual([
            ("list_my_orders",), ("get_my_order",), ("get_my_return",),
            ("prepare_my_return_request",),
        ], model.tools)
        self.assertEqual([
            "list_my_orders", "get_my_order", "get_my_return",
            "prepare_my_return_request",
        ], registry.executed)
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertIn("Confirm?", result.message.content)

    async def test_explicit_return_stops_at_authoritative_ineligibility(self) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={"orderId": ORDER, "listingId": LISTING},
            )),
            ModelDecision(content=(
                "That item must be delivered before a return can be requested."
            )),
        ])
        registry = _ScriptedReturnRegistry(item_title="Harbor Mouse Pad")

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "The Harbor Mouse Pad from my last order arrived damaged. "
                "I want to return it."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="ineligible-damaged-item-return",
        )

        self.assertEqual((), model.tools[-1])
        self.assertEqual([
            "list_my_orders", "get_my_order", "get_my_return",
        ], registry.executed)
        self.assertIsNone(result.pending_interaction)
        self.assertIn("must be delivered", result.message.content)

    async def test_eligibility_only_prose_becomes_preparation_required(self) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={"orderId": ORDER, "listingId": LISTING},
            )),
            ModelDecision(content="That item is eligible for a return."),
            ModelDecision(toolProposal=ToolProposal(
                callId="prepare", tool="prepare_my_return_request",
                arguments={
                    "orderId": ORDER, "listingId": LISTING,
                    "reasonCode": "DAMAGED", "comment": None,
                },
            )),
        ])
        registry = _ScriptedReturnRegistry(
            return_reason="RETURN_ELIGIBLE", item_title="Harbor Mouse Pad"
        )

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "The Harbor Mouse Pad from my last order arrived damaged. "
                "I want to return it."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="eligibility-prose-return-preparation",
        )

        self.assertEqual(
            "RETURN_PREPARATION_REQUIRED",
            model.contexts[4].observations[-1].reason,
        )
        self.assertEqual(("prepare_my_return_request",), model.tools[4])
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertNotEqual(
            "That item is eligible for a return.", result.message.content
        )

    async def test_grounded_refund_follow_up_recovers_from_private_abstention(
        self,
    ) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(content=(
                "I can't verify that account-specific status here. Please use "
                "the relevant account page or contact marketplace support."
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
        ])
        registry = _ScriptedReturnRegistry()

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Can I get a refund for it?",
            recent_messages=(
                ("USER", "Can I return my latest order?"),
                (
                    "ASSISTANT",
                    (
                        "Your latest order contains Harbor Keyboard and Harbor "
                        "Mouse. That store group is eligible for a return."
                    ),
                ),
            ),
            referenced_listings=(),
            correlation_id="grounded-refund-follow-up-recovery",
        )

        self.assertEqual(
            "RETURN_STATUS_TOOL_REQUIRED",
            model.contexts[1].observations[-1].reason,
        )
        self.assertEqual(
            ["list_my_orders", "get_my_order", "get_my_return"],
            registry.executed,
        )
        self.assertIn("must be delivered", result.message.content)

    async def test_return_status_abstention_recovers_with_specific_tool_signal(
        self,
    ) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(content=(
                "I can't verify that account-specific status here. Please use "
                "the relevant account page or contact marketplace support."
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
        ])
        registry = _ScriptedReturnRegistry()

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="What's happening with my return? Has my refund finished?",
            recent_messages=(), referenced_listings=(),
            correlation_id="return-status-abstention-recovery",
        )

        self.assertEqual(
            "RETURN_STATUS_TOOL_REQUIRED",
            model.contexts[1].observations[-1].reason,
        )
        self.assertEqual(
            ["list_my_orders", "get_my_order", "get_my_return"],
            registry.executed,
        )
        self.assertIn("must be delivered", result.message.content)

    async def test_return_eligibility_uses_return_projection_after_order_resolution(
        self,
    ) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
        ])
        registry = _ScriptedReturnRegistry()
        orchestrator = MarketplaceAgentV2Orchestrator(model, registry)

        result = await orchestrator.run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Can I return my second most recent order?",
            recent_messages=(), referenced_listings=(),
            correlation_id="return-eligibility-read",
        )

        self.assertEqual(
            ["list_my_orders", "get_my_order", "get_my_return"],
            registry.executed,
        )
        self.assertIn("must be delivered", result.message.content)

    async def test_return_eligibility_maps_exact_owned_item_before_return_read(
        self,
    ) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
        ])
        registry = _ScriptedReturnRegistry()

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "Check whether the Harbor Keyboard from my latest order is "
                "eligible for a return."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="return-exact-owned-item",
        )

        self.assertEqual(LISTING, registry.return_listing_id)
        self.assertIn("must be delivered", result.message.content)

    async def test_live_style_order_to_named_group_preparation_sequence(self) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={
                    "orderId": ORDER, "storeName": "Harbor Business",
                },
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="prepare", tool="prepare_my_return_request",
                arguments={
                    "orderId": ORDER, "storeName": "Harbor Business",
                    "reasonCode": "DAMAGED", "comment": None,
                },
            )),
        ])
        registry = _ScriptedReturnRegistry(return_reason="RETURN_ELIGIBLE")
        orchestrator = MarketplaceAgentV2Orchestrator(model, registry)

        result = await orchestrator.run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "Prepare a return request for the entire Harbor Business store "
                "group because the keyboard arrived damaged."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="live-style-return",
        )

        self.assertEqual(
            [
                "list_my_orders", "get_my_order", "get_my_return",
                "prepare_my_return_request",
            ],
            registry.executed,
        )
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertIn("Confirm?", result.message.content)

    async def test_explicit_return_cannot_end_with_extra_proceed_question(self) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(content="I can check that now. Proceed?"),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={"orderId": ORDER, "listingId": LISTING},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="prepare", tool="prepare_my_return_request",
                arguments={
                    "orderId": ORDER, "listingId": LISTING,
                    "reasonCode": "DAMAGED", "comment": None,
                },
            )),
        ])
        registry = _ScriptedReturnRegistry(return_reason="RETURN_ELIGIBLE")

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "Start a return request for Harbor Keyboard from my latest "
                "order because it arrived damaged."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="return-no-extra-proceed",
        )

        self.assertEqual(
            [
                "list_my_orders", "get_my_order", "get_my_return",
                "prepare_my_return_request",
            ],
            registry.executed,
        )
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertNotIn("Proceed?", result.message.content)

    async def test_item_title_cannot_be_used_as_an_invented_store_name(self) -> None:
        model = _ScriptedReturnModel([
            ModelDecision(toolProposal=ToolProposal(
                callId="list", tool="list_my_orders", arguments={"limit": 10},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="order", tool="get_my_order",
                arguments={"orderId": ORDER},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="eligibility", tool="get_my_return",
                arguments={"orderId": ORDER, "listingId": LISTING},
            )),
            ModelDecision(toolProposal=ToolProposal(
                callId="prepare", tool="prepare_my_return_request",
                arguments={
                    "orderId": ORDER, "storeName": "Harbor Keyboard",
                    "reasonCode": "DAMAGED", "comment": None,
                },
            )),
        ])
        registry = _ScriptedReturnRegistry(return_reason="RETURN_ELIGIBLE")

        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message=(
                "Start a return request for Harbor Keyboard from my latest "
                "order because it arrived damaged."
            ),
            recent_messages=(), referenced_listings=(),
            correlation_id="return-item-title-normalization",
        )

        self.assertEqual(LISTING, registry.return_listing_id)
        self.assertEqual("WAITING", result.pending_interaction.status)

    async def test_order_identifier_is_redacted_before_streaming(self) -> None:
        stream = _CustomerTextStream(None, forbidden_ids=(), private_ids=(ORDER,))
        await stream.push(f"Order {ORDER[:13]}")
        await stream.push(f"{ORDER[13:]} is not eligible.")
        await stream.finish()

        self.assertNotIn(ORDER, stream.text)
        self.assertIn("this order", stream.text)

    async def test_disabled_return_capability_is_explicit_and_model_free(self) -> None:
        class DisabledRegistry:
            names: tuple[str, ...] = ()
            capability_boundary = _boundary()

            def provider_schemas(self):
                return ()

        orchestrator = MarketplaceAgentV2Orchestrator(_NoModel(), DisabledRegistry())

        result = await orchestrator.run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Prepare a return request for my last order.",
            recent_messages=(), referenced_listings=(),
            correlation_id="disabled-return",
        )

        self.assertEqual(0, result.decision_count)
        self.assertIn("not available", result.message.content)
        self.assertIn("no refund was issued", result.message.content)

    async def test_consumed_confirmation_submits_once_without_model(self) -> None:
        registry = _ConfirmedRegistry()
        orchestrator = MarketplaceAgentV2Orchestrator(_NoModel(), registry)
        pending = MarketplaceAgentV2PendingInteraction(
            id=INVOCATION, confirmationId=INVOCATION,
            type="CONFIRM_ACTION", action="SUBMIT_RETURN_REQUEST",
            summary="Submit a return request.",
            arguments={
                "orderId": ORDER, "businessOrderId": GROUP,
                "groupVersion": 7, "groupFingerprint": "a" * 64,
                "reasonCode": "DAMAGED", "comment": None,
            },
            status="CONSUMED", createdAt=NOW,
        )

        result = await orchestrator.run(
            actor_user_id=ACTOR, actor_authorization=TOKEN,
            current_message="Yes", recent_messages=(), referenced_listings=(),
            confirmed_interaction=pending, correlation_id="confirmed-return",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE", requiredGrounding="PRIVATE_TOOL",
                confidence="HIGH", marketplaceContextUsed=True,
                reasonCode="PENDING_INTERACTION_RESPONSE",
            ),
        )

        self.assertEqual(1, registry.executions)
        self.assertEqual(INVOCATION, registry.assertion_reference)
        self.assertEqual(0, result.decision_count)
        self.assertIn("submitted", result.message.content)
        self.assertIn("did not approve", result.message.content)


if __name__ == "__main__":
    unittest.main()
