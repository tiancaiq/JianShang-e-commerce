from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from msb_agent_service.cli import main as cli_main
from msb_agent_service.config import Settings
from msb_agent_service.marketplace_agent_v2.capabilities import (
    CUSTOMER_CAPABILITIES,
    CapabilityFamily,
    MarketplaceCustomerCapabilityBoundary,
)
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2OrchestrationFailure,
    MarketplaceAgentV2Orchestrator,
)
from msb_agent_service.marketplace_agent_v2.provider import (
    OpenAIMarketplaceAgentV2Model,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    AgentContext,
    ModelDecision,
    SkillSelection,
    ToolObservation,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.skill_registry import (
    SkillRegistry,
    SkillValidationError,
)


KNOWN_TOOLS = tuple(item.name for item in CUSTOMER_CAPABILITIES)


def _write_skill(
    root: Path,
    directory: str,
    *,
    name: str | None = None,
    tools: tuple[str, ...] = ("search_listings",),
    body: str = "# Test\n\nUse the registered tools safely.",
) -> None:
    target = root / directory
    target.mkdir()
    tool_lines = "\n".join(f"  - {tool}" for tool in tools)
    (target / "SKILL.md").write_text(
        "\n".join((
            "---",
            f"name: {name or directory}",
            f"description: Help with {directory}.",
            "version: 1",
            "surface: customer",
            "allowed_tools:",
            tool_lines,
            "---",
            body,
        )),
        encoding="utf-8",
    )


class SkillRegistryTest(unittest.TestCase):
    def test_discovers_loads_and_compacts_valid_skill(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_skill(root, "find-things")
            registry = SkillRegistry.discover(root, known_tools=KNOWN_TOOLS)

            skill = registry.get("find-things")
            self.assertIn("Use the registered tools safely", skill.instructions)
            self.assertEqual("find-things", skill.compact()["name"])
            self.assertNotIn("instructions", skill.compact())

    def test_rejects_malformed_skill(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "broken"
            target.mkdir()
            (target / "SKILL.md").write_text("# no metadata", encoding="utf-8")
            with self.assertRaisesRegex(SkillValidationError, "front matter"):
                SkillRegistry.discover(root, known_tools=KNOWN_TOOLS)

    def test_rejects_duplicate_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_skill(root, "one", name="same")
            _write_skill(root, "two", name="same")
            with self.assertRaises(SkillValidationError) as raised:
                SkillRegistry.discover(root, known_tools=KNOWN_TOOLS)
            self.assertIn("duplicate skill name: same", raised.exception.issues)

    def test_rejects_unknown_tool_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_skill(root, "unsafe", tools=("run_shell",))
            with self.assertRaisesRegex(SkillValidationError, "unknown tool"):
                SkillRegistry.discover(root, known_tools=KNOWN_TOOLS)

    def test_disabled_tool_keeps_skill_out_of_runtime_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_skill(root, "cart-help", tools=("get_my_cart",))
            registry = SkillRegistry.discover(root, known_tools=KNOWN_TOOLS)
            self.assertEqual(
                (), registry.available(surface="customer", enabled_tools=("search_listings",))
            )


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[Any] = []
        self.tool_sets: list[tuple[str, ...]] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        self.tool_sets.append(tuple(item["name"] for item in kwargs["tools"]))
        return self.decisions.pop(0)

    async def close(self) -> None:
        return None


class _Registry:
    names = ("check_availability", "search_listings", "get_listing")

    def __init__(self) -> None:
        self.executions = 0

    def provider_schemas(self) -> tuple[dict[str, object], ...]:
        return (
            {"name": "check_availability"},
            {"name": "search_listings"},
            {"name": "get_listing"},
        )

    async def execute(self, **_: Any) -> ToolObservation:
        self.executions += 1
        return ToolObservation(
            tool="search_listings",
            status="SUCCEEDED",
            reason="FILTERS_TOO_STRICT",
            normalizedQuery="mechanical keyboard",
            resultCount=0,
            broadInventoryCount=2,
        )


class SkillAgentIntegrationTest(unittest.IsolatedAsyncioTestCase):
    def _registry(self) -> SkillRegistry:
        return SkillRegistry.default(known_tools=KNOWN_TOOLS)

    async def test_model_selects_skill_then_uses_only_its_existing_tools(self) -> None:
        model = _Model([
            ModelDecision(skillSelection=SkillSelection(name="marketplace-discovery")),
            ModelDecision(toolProposal=ToolProposal(
                callId="search-1",
                tool="search_listings",
                arguments={"query": "mechanical keyboard"},
            )),
            ModelDecision(content="I couldn't find a current match for those filters."),
        ])
        tools = _Registry()
        result = await MarketplaceAgentV2Orchestrator(
            model, tools, skill_registry=self._registry()
        ).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Find me a mechanical keyboard under $100.",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="skill-selection-test",
        )

        self.assertEqual(3, result.decision_count)
        self.assertEqual(1, tools.executions)
        self.assertEqual(
            ("check_availability", "search_listings", "get_listing", "load_skill"),
            model.tool_sets[0],
        )
        self.assertEqual(
            ("check_availability", "search_listings", "get_listing"),
            model.tool_sets[1],
        )
        self.assertEqual("marketplace-discovery", model.contexts[1].active_skill.name)
        self.assertEqual((), model.contexts[1].available_skills)

    async def test_unrelated_conversation_does_not_load_skill(self) -> None:
        model = _Model([ModelDecision(content="You're welcome.")])
        result = await MarketplaceAgentV2Orchestrator(
            model, _Registry(), skill_registry=self._registry()
        ).run(
            actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
            current_message="Thanks",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="skill-no-load-test",
        )
        self.assertEqual(1, result.decision_count)
        self.assertIsNone(model.contexts[0].active_skill)

    async def test_unknown_skill_fails_closed(self) -> None:
        model = _Model([
            ModelDecision(skillSelection=SkillSelection(name="missing-skill"))
        ])
        with self.assertRaisesRegex(
            MarketplaceAgentV2OrchestrationFailure, "UNKNOWN_SKILL"
        ):
            await MarketplaceAgentV2Orchestrator(
                model, _Registry(), skill_registry=self._registry()
            ).run(
                actor_user_id="01ARZ3NDEKTSV4RRFFQ69G5FAV",
                current_message="Find a keyboard",
                recent_messages=(),
                referenced_listings=(),
                correlation_id="unknown-skill-test",
            )

    def test_skill_cannot_bypass_confirmation_policy(self) -> None:
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            required_grounding="PRIVATE_TOOL",
            skill_allowed_tools=frozenset({"submit_my_checkout"}),
            capability_boundary=MarketplaceCustomerCapabilityBoundary(frozenset({
                CapabilityFamily.CUSTOMER_CHECKOUT,
            })),
        )
        _, rejection = policy.validate(ToolProposal(
            callId="submit",
            tool="submit_my_checkout",
            arguments={"checkoutId": "01ARZ3NDEKTSV4RRFFQ69G5FAV"},
        ), step=2)
        self.assertIsNotNone(rejection)
        self.assertEqual("CONFIRMATION_REQUIRED", rejection.reason)


class _ProviderResponses:
    def stream(self, **_: object) -> object:
        response = SimpleNamespace(
            status="completed",
            output=(SimpleNamespace(
                type="function_call",
                call_id="skill-1",
                name="load_skill",
                arguments='{"name":"marketplace-discovery"}',
            ),),
            output_text="",
            usage=None,
        )

        class Stream:
            def __init__(self) -> None:
                self.sent = False

            def __aiter__(self) -> object:
                return self

            async def __anext__(self) -> object:
                if self.sent:
                    raise StopAsyncIteration
                self.sent = True
                return SimpleNamespace(type="response.completed")

            async def get_final_response(self) -> object:
                return response

        class Manager:
            async def __aenter__(self) -> object:
                return Stream()

            async def __aexit__(self, *_: object) -> None:
                return None

        return Manager()


class SkillProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_provider_parses_load_skill_as_selection_not_tool(self) -> None:
        model = OpenAIMarketplaceAgentV2Model(
            Settings(openai_api_key="offline-placeholder"),
            SimpleNamespace(responses=_ProviderResponses()),
            allowed_tool_names=("search_listings",),
        )
        decision = await model.decide(
            context=AgentContext(currentMessage="Find a keyboard"),
            tools=(
                {"name": "search_listings"},
                {"name": "load_skill"},
            ),
            correlation_id="provider-skill-selection",
            on_text_delta=None,
            timeout_seconds=5,
        )
        self.assertEqual("marketplace-discovery", decision.skill_selection.name)
        self.assertIsNone(decision.tool_proposal)


class SkillCliTest(unittest.TestCase):
    def test_list_show_and_validate(self) -> None:
        output = io.StringIO()
        self.assertEqual(0, cli_main(["skills", "list"], stdout=output))
        self.assertIn("purchase-item", output.getvalue())
        output = io.StringIO()
        self.assertEqual(
            0, cli_main(["skills", "show", "purchase-item"], stdout=output)
        )
        self.assertIn("source path:", output.getvalue())
        self.assertEqual(0, cli_main(["skills", "validate"], stdout=io.StringIO()))

    def test_invalid_library_returns_nonzero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _write_skill(root, "bad", tools=("missing_tool",))
            errors = io.StringIO()
            status = cli_main(
                ["skills", "--skills-dir", str(root), "validate"],
                stdout=io.StringIO(),
                stderr=errors,
            )
            self.assertEqual(1, status)
            self.assertIn("unknown tool", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
