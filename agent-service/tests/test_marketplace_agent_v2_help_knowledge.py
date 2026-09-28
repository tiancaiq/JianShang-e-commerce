from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from typing import Any

from msb_agent_service.marketplace_agent_v2.capabilities import (
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.help_knowledge import (
    HelpKnowledgeConfigurationError,
    HelpKnowledgeRetriever,
)
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    MarketplaceScopeResult,
    ModelDecision,
    RetrieveHelpArguments,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


REPOSITORY = Path(__file__).parents[2]
HELP_CORPUS = REPOSITORY / "docs" / "help"
ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"


class _UnusedProduct:
    async def probe_availability(self, **_: Any) -> object:
        raise AssertionError("help retrieval must not call Product")


def _boundary() -> MarketplaceCustomerCapabilityBoundary:
    return MarketplaceCustomerCapabilityBoundary(frozenset({
        CapabilityFamily.MARKETPLACE_READ,
        CapabilityFamily.CUSTOMER_WORKFLOW_CONTROL,
        CapabilityFamily.CUSTOMER_KNOWLEDGE_READ,
    }))


class _HelpModel:
    def __init__(self) -> None:
        self.calls = 0

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.calls += 1
        if self.calls == 1:
            self.asserted_tools = tuple(item["name"] for item in kwargs["tools"])
            return ModelDecision(toolProposal=ToolProposal(
                callId="help-1",
                tool="retrieve_help",
                arguments={"query": "save favorite listings", "limit": 2},
            ))
        if self.calls == 2:
            passages = kwargs["context"].observations[-1].knowledge_passages
            if not passages or passages[0].article_id != "HELP-FAVORITES-001":
                raise AssertionError("model did not receive approved help evidence")
            return ModelDecision(
                content=(
                    "Sign in, open a listing, and use its favorite control. "
                    "You can revisit saved listings from your account."
                )
            )
        raise AssertionError("unexpected extra model decision")

    async def close(self) -> None:
        return None


class HelpKnowledgeRetrieverTest(unittest.TestCase):
    def test_loads_public_articles_and_excludes_internal_files(self) -> None:
        retriever = HelpKnowledgeRetriever(HELP_CORPUS)

        self.assertEqual(15, retriever.article_count)
        result = retriever.retrieve("How do I save a favorite listing?")
        self.assertTrue(result)
        self.assertEqual("HELP-FAVORITES-001", result[0].article_id)
        self.assertEqual("Save a listing", result[0].section)
        self.assertNotIn(
            "HELP-INTERNAL-COVERAGE-001",
            {item.article_id for item in retriever.retrieve("coverage code evidence")},
        )
        self.assertRegex(result[0].version, r"^[0-9a-f]{64}$")

    def test_rejects_duplicate_public_article_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            article = (
                "---\narticle_id: HELP-TEST-001\ntitle: Test help\n"
                "audience: customers\navailability: core\n---\n\n# Test help\nBody."
            )
            (root / "one.md").write_text(article, encoding="utf-8")
            (root / "two.md").write_text(article, encoding="utf-8")
            with self.assertRaises(HelpKnowledgeConfigurationError):
                HelpKnowledgeRetriever(root)


class HelpKnowledgeToolTest(unittest.IsolatedAsyncioTestCase):
    async def test_tool_is_gated_and_returns_bounded_public_passages(self) -> None:
        registry = MarketplaceAgentV2ToolRegistry(
            _UnusedProduct(),
            help_knowledge=HelpKnowledgeRetriever(HELP_CORPUS),
            capability_boundary=_boundary(),
        )

        self.assertIn("retrieve_help", registry.names)
        schema = next(
            item for item in registry.provider_schemas()
            if item["name"] == "retrieve_help"
        )
        self.assertNotIn("actor", str(schema).casefold())
        self.assertEqual(
            [1, 2, 3],
            schema["parameters"]["properties"]["limit"]["enum"],
        )
        observation = await registry.execute(
            tool="retrieve_help",
            arguments=RetrieveHelpArguments(query="How does demo checkout work?"),
            actor_user_id=ACTOR,
            correlation_id="help-tool",
            activity=None,
        )

        self.assertEqual("SUCCEEDED", observation.status)
        self.assertEqual("KNOWLEDGE_AVAILABLE", observation.reason)
        self.assertLessEqual(len(observation.knowledge_passages), 3)
        self.assertEqual("HELP-CHECKOUT-001", observation.knowledge_passages[0].article_id)

    async def test_policy_allows_help_only_for_knowledge_grounding(self) -> None:
        proposal = ToolProposal(
            callId="help-policy",
            tool="retrieve_help",
            arguments={"query": "marketplace favorites", "limit": 2},
        )
        allowed, rejection = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="KNOWLEDGE_RAG",
            capability_boundary=_boundary(),
        ).validate(proposal, step=1)
        self.assertIsInstance(allowed, RetrieveHelpArguments)
        self.assertIsNone(rejection)

        _, rejection = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="LISTING_DATA",
            capability_boundary=_boundary(),
        ).validate(proposal, step=1)
        self.assertEqual("GROUNDING_TOOL_REQUIRED", rejection.reason)

    async def test_orchestrator_persists_evidence_and_returns_citations(self) -> None:
        model = _HelpModel()
        registry = MarketplaceAgentV2ToolRegistry(
            _UnusedProduct(),
            help_knowledge=HelpKnowledgeRetriever(HELP_CORPUS),
            capability_boundary=_boundary(),
        )
        result = await MarketplaceAgentV2Orchestrator(model, registry).run(
            actor_user_id=ACTOR,
            current_message="How do I save a favorite listing?",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="help-orchestrator",
            scope_result=MarketplaceScopeResult(
                scope="IN_SCOPE",
                requiredGrounding="KNOWLEDGE_RAG",
                confidence="HIGH",
                marketplaceContextUsed=True,
                reasonCode="MARKETPLACE_GUIDANCE",
            ),
        )

        self.assertEqual(2, result.decision_count)
        self.assertEqual("retrieve_help", result.observations[0].tool)
        self.assertTrue(result.message.citations)
        self.assertIn("HELP-FAVORITES-001", result.message.citations[0])
        self.assertEqual("KNOWLEDGE_DOCUMENT", result.evidence[0].source_type)
        self.assertEqual("HELP-FAVORITES-001", result.evidence[0].source_id)


if __name__ == "__main__":
    unittest.main()
