from __future__ import annotations

import unittest
import json
from pathlib import Path
from typing import Any

from msb_agent_service.marketplace_agent_v2.capabilities import (
    AgentSurface,
    CapabilityDecisionCode,
    CapabilityFamily,
    CapabilityRiskLevel,
    CUSTOMER_CAPABILITIES,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    CheckAvailabilityArguments,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


class _NeverCalledProduct:
    def __init__(self) -> None:
        self.calls = 0

    async def probe_availability(self, **_: Any) -> object:
        self.calls += 1
        raise AssertionError("disabled capability reached Product")


class MarketplaceCustomerCapabilityBoundaryTest(unittest.TestCase):
    def test_ai_pol_00_eval_fixture_covers_allowed_forbidden_and_unsafe_cases(self) -> None:
        fixture = json.loads(
            (Path(__file__).parents[1] / "evals" / "ai_pol_00_customer_capability_v1.json")
            .read_text(encoding="utf-8")
        )
        self.assertEqual(
            "AI_POL_00_CUSTOMER_CAPABILITY_EVAL_V1", fixture["schemaVersion"]
        )
        identifiers = {item["id"] for item in fixture["cases"]}
        self.assertTrue({
            "allowed-search", "allowed-capabilities", "allowed-availability",
            "forbidden-ban", "forbidden-suspend", "forbidden-refund",
            "forbidden-approve-refund", "forbidden-remove-listing",
            "forbidden-role",
            "unsafe-stolen-card", "unsafe-payment-bypass",
            "unsafe-account-takeover", "unsafe-cross-order", "unsafe-admin-api",
            "safe-redirection",
        }.issubset(identifiers))

    def test_registry_is_the_exact_current_customer_capability_set(self) -> None:
        self.assertEqual(
            (
                "retrieve_help",
                "check_availability",
                "search_listings",
                "get_listing",
                "request_confirmation",
                "collect_listing_information",
                "get_my_cart",
                "list_my_orders",
                "get_my_order",
                "add_to_my_cart",
                "update_my_cart_quantity",
                "remove_from_my_cart",
                "prepare_my_checkout",
                "get_my_checkout",
                "submit_my_checkout",
                "preview_my_order_cancellation",
                "cancel_my_order",
                "get_my_return",
                "prepare_my_return_request",
                "submit_my_return_request",
            ),
            tuple(item.name for item in CUSTOMER_CAPABILITIES),
        )
        self.assertTrue(all(
            item.surface == AgentSurface.MARKETPLACE_CUSTOMER
            for item in CUSTOMER_CAPABILITIES
        ))
        self.assertEqual(
            {
                CapabilityRiskLevel.READ_ONLY,
                CapabilityRiskLevel.LOW_RISK_STATE,
                CapabilityRiskLevel.SENSITIVE_STATE,
            },
            {item.risk_level for item in CUSTOMER_CAPABILITIES},
        )
        mutations = tuple(
            item for item in CUSTOMER_CAPABILITIES if item.mutates_marketplace_state
        )
        self.assertEqual(
            (
                "add_to_my_cart",
                "update_my_cart_quantity",
                "remove_from_my_cart",
                "prepare_my_checkout",
                "submit_my_checkout",
                "cancel_my_order",
                "submit_my_return_request",
            ),
            tuple(item.name for item in mutations),
        )
        self.assertEqual(
            (
                "submit_my_checkout", "cancel_my_order",
                "submit_my_return_request",
            ),
            tuple(item.name for item in CUSTOMER_CAPABILITIES if item.requires_confirmation),
        )

    def test_unknown_admin_finance_and_system_capabilities_fail_closed(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(referenced_listing_ids=frozenset())
        for index, tool in enumerate((
            "ban_user", "suspend_seller", "issue_refund", "approve_refund",
            "admin_remove_listing", "change_role", "change_order",
            "access_account", "call_admin_api", "publish_listing",
            "execute_sql", "impersonate_user",
            "add_to_cart", "remove_from_cart", "cancel_order", "refund_order",
        ), 1):
            with self.subTest(tool=tool):
                arguments, observation = policy.validate(
                    ToolProposal(callId=f"unknown-{index}", tool=tool, arguments={}),
                    step=min(index, 5),
                )
                self.assertIsNone(arguments)
                self.assertIsNotNone(observation)
                self.assertEqual("UNREGISTERED", observation.tool)
                self.assertEqual("UNKNOWN_TOOL", observation.reason)

    def test_surface_mismatch_is_denied(self) -> None:
        decision = MarketplaceCustomerCapabilityBoundary().evaluate(
            "search_listings", surface=AgentSurface.LISTING_CUSTOMER_SERVICE
        )
        self.assertFalse(decision.allowed)
        self.assertEqual(CapabilityDecisionCode.SURFACE_MISMATCH, decision.code)

    def test_tool_schemas_never_expose_actor_identity(self) -> None:
        registry = MarketplaceAgentV2ToolRegistry(_NeverCalledProduct())
        serialized = str(registry.provider_schemas()).casefold()
        for field in ("actoruserid", "actor_user_id", "userid", "user_id"):
            self.assertNotIn(field, serialized)


class MarketplaceCustomerCapabilityExecutionTest(unittest.IsolatedAsyncioTestCase):
    async def test_disabled_family_is_removed_and_rejected_before_io(self) -> None:
        boundary = MarketplaceCustomerCapabilityBoundary(
            enabled_families=frozenset({CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL})
        )
        product = _NeverCalledProduct()
        registry = MarketplaceAgentV2ToolRegistry(
            product, capability_boundary=boundary
        )

        self.assertEqual(
            ("request_confirmation", "collect_listing_information"), registry.names
        )
        self.assertEqual(registry.names, tuple(
            item["name"] for item in registry.provider_schemas()
        ))
        observation = await registry.execute(
            tool="check_availability",
            arguments=CheckAvailabilityArguments(category="chair"),
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            correlation_id="capability-disabled",
            activity=None,
        )
        self.assertEqual("REJECTED", observation.status)
        self.assertEqual("CAPABILITY_DISABLED", observation.reason)
        self.assertEqual(0, product.calls)

        _, policy_observation = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), capability_boundary=boundary
        ).validate(
            ToolProposal(
                callId="disabled-1",
                tool="check_availability",
                arguments={"category": "chair"},
            ),
            step=1,
        )
        self.assertEqual("CAPABILITY_DISABLED", policy_observation.reason)


if __name__ == "__main__":
    unittest.main()
