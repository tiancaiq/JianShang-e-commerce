from __future__ import annotations

import json
import unittest
from pathlib import Path

from msb_agent_service.api import create_app
from msb_agent_service.config import MarketplaceAgentV2Settings, Settings
from msb_agent_service.marketplace_agent_v2.api import stream_event


class MarketplaceAgentV2ContractTest(unittest.TestCase):
    def test_v17_is_forward_only_and_preserves_legacy_session_and_tool_values(self) -> None:
        migration = (
            Path(__file__).resolve().parents[1]
            / "db" / "migration"
            / "V17__create_parallel_marketplace_agent_v2_boundary.sql"
        ).read_text(encoding="utf-8")

        for value in (
            "LISTING_CUSTOMER_SERVICE", "MARKETPLACE_DISCOVERY",
            "MARKETPLACE_AGENT_V2", "CHECK_AVAILABILITY", "SEARCH_INDIVIDUAL",
            "GET_LISTING", "search_listings", "get_listing",
        ):
            self.assertIn(value, migration)
        self.assertNotIn("DROP TABLE", migration.upper())

    def test_routes_are_parallel_and_legacy_route_remains_registered(self) -> None:
        paths = create_app(Settings()).openapi()["paths"]

        self.assertIn("/api/v1/agent/discovery/sessions/{sessionId}/messages/stream", paths)
        self.assertIn("/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/stream", paths)
        self.assertIn(
            "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/{clientMessageId}/stop",
            paths,
        )
        self.assertIn(
            "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/{userMessageId}/response-retry/stream",
            paths,
        )

    def test_stream_serializer_is_strict_single_data_line_sse(self) -> None:
        frame = stream_event(1, "text_delta", delta="café 🪑")
        lines = frame.splitlines()

        self.assertEqual("event: text_delta", lines[0])
        self.assertTrue(lines[1].startswith("data: "))
        self.assertEqual([""], lines[2:])
        payload = json.loads(lines[1][6:])
        self.assertEqual("MARKETPLACE_AGENT_V2_STREAM_EVENT_V1", payload["schemaVersion"])
        self.assertEqual("café 🪑", payload["delta"])

    def test_v2_feature_is_default_off_and_rejects_partial_activation(self) -> None:
        self.assertFalse(Settings().marketplace_agent_v2.enabled)
        with self.assertRaisesRegex(ValueError, "API_ENABLED"):
            MarketplaceAgentV2Settings(provider_enabled=True).validate(
                persistence_enabled=True,
                provider_configured=True,
            )

    def test_v19_is_forward_only_and_allows_only_read_commerce_tool_audits(self) -> None:
        migration = (
            Path(__file__).resolve().parents[1]
            / "db" / "migration"
            / "V19__allow_marketplace_agent_v2_commerce_read_tool_audit.sql"
        ).read_text(encoding="utf-8")

        for value in ("get_my_cart", "list_my_orders", "get_my_order"):
            self.assertIn(f"'{value}'", migration)
        for forbidden in ("add_to_cart", "cancel_order", "refund_order", "DROP TABLE"):
            self.assertNotIn(forbidden, migration)

    def test_v20_is_forward_only_and_narrowly_allows_cart_mutation_audits(self) -> None:
        migration = (
            Path(__file__).resolve().parents[1]
            / "db" / "migration"
            / "V20__allow_marketplace_agent_v2_cart_mutation_tool_audit.sql"
        ).read_text(encoding="utf-8")

        for value in (
            "add_to_my_cart", "update_my_cart_quantity", "remove_from_my_cart",
        ):
            self.assertIn(f"'{value}'", migration)
        for forbidden in (
            "checkout", "payment", "cancel_order", "refund_order", "admin", "DROP TABLE",
        ):
            self.assertNotIn(forbidden.casefold(), migration.casefold())

    def test_v22_is_forward_only_and_narrowly_allows_checkout_audits(self) -> None:
        migration = (
            Path(__file__).resolve().parents[1]
            / "db" / "migration"
            / "V22__allow_marketplace_agent_v2_checkout_tool_audit.sql"
        ).read_text(encoding="utf-8")

        for value in (
            "prepare_my_checkout", "get_my_checkout", "submit_my_checkout",
        ):
            self.assertIn(f"'{value}'", migration)
        for forbidden in (
            "collect_payment_credentials", "cancel_order", "refund_order",
            "admin_action", "DROP TABLE", "TRUNCATE",
        ):
            self.assertNotIn(forbidden.casefold(), migration.casefold())


if __name__ == "__main__":
    unittest.main()
