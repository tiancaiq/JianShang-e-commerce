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


if __name__ == "__main__":
    unittest.main()
