from __future__ import annotations

import unittest
from datetime import UTC, datetime
from typing import Any

from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2Orchestrator,
    _explicit_seller_comparison_request,
)
from msb_agent_service.marketplace_agent_v2.policy import MarketplaceAgentV2ToolPolicy
from msb_agent_service.marketplace_agent_v2.schemas import (
    CollectListingInformationArguments,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceAgentV2SellerFields,
    ModelDecision,
    ToolProposal,
)
from msb_agent_service.marketplace_agent_v2.scope import MarketplaceScopeClassifier
from msb_agent_service.marketplace_agent_v2.seller_workflow import (
    apply_field_answer,
    apply_field_resolution,
    cancel_create_listing_workflow,
    resolve_pending_field_reply,
    response_for_replayed_field,
    restore_rejected_item_pending,
    start_create_listing_workflow,
)
from msb_agent_service.marketplace_agent_v2.tools import MarketplaceAgentV2ToolRegistry


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
USER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
NOW = datetime.now(UTC)


class _NoProductCalls:
    def __init__(self) -> None:
        self.calls = 0

    def __getattr__(self, _: str) -> Any:
        async def unexpected(**__: Any) -> object:
            self.calls += 1
            raise AssertionError("Seller field collection must not call Product")

        return unexpected


class _Model:
    def __init__(self, decisions: list[ModelDecision]) -> None:
        self.decisions = decisions
        self.contexts: list[object] = []

    async def decide(self, **kwargs: Any) -> ModelDecision:
        self.contexts.append(kwargs["context"])
        decision = self.decisions.pop(0)
        if decision.content is not None and kwargs["on_text_delta"] is not None:
            await kwargs["on_text_delta"](decision.content)
        return decision

    async def close(self) -> None:
        return None


class SellerWorkflowTest(unittest.IsolatedAsyncioTestCase):
    async def test_sell_intent_starts_typed_workflow_without_product_work(self) -> None:
        product = _NoProductCalls()
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="seller-start",
                tool="collect_listing_information",
                arguments={"field": "ITEM_TYPE"},
            )),
            ModelDecision(content="Great. What are you selling?"),
        ])

        result = await MarketplaceAgentV2Orchestrator(
            model, MarketplaceAgentV2ToolRegistry(product)  # type: ignore[arg-type]
        ).run(
            actor_user_id=ACTOR,
            current_message="I want to sell an item.",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="seller-start",
        )

        self.assertEqual(2, result.decision_count)
        self.assertEqual("CREATE_LISTING", result.active_workflow.type)
        self.assertEqual("ITEM_TYPE", result.pending_interaction.field)
        self.assertEqual("WAITING", result.pending_interaction.status)
        self.assertEqual(0, product.calls)
        self.assertEqual((), result.message.tool_activity)
        self.assertEqual("CREATE_LISTING", model.contexts[-1].active_workflow.type)

    async def test_sell_house_extracts_item_type_before_the_next_question(self) -> None:
        product = _NoProductCalls()
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="seller-house-start",
                tool="collect_listing_information",
                arguments={"field": "ITEM_TYPE"},
            )),
            ModelDecision(content=(
                "This marketplace doesn’t support real-estate listings. "
                "You can list another type of item here."
            )),
        ])

        result = await MarketplaceAgentV2Orchestrator(
            model, MarketplaceAgentV2ToolRegistry(product)  # type: ignore[arg-type]
        ).run(
            actor_user_id=ACTOR,
            current_message="I want to sell my house.",
            recent_messages=(),
            referenced_listings=(),
            correlation_id="seller-house-start",
        )

        self.assertEqual(2, result.decision_count)
        self.assertEqual("COLLECTING_INFORMATION", result.active_workflow.status)
        self.assertEqual("REJECTED", result.active_workflow.collected_fields.item_type.status)
        self.assertEqual("house", result.active_workflow.collected_fields.item_type.value)
        self.assertEqual("UNSUPPORTED_CATEGORY", result.active_workflow.collected_fields.item_type.reason)
        self.assertEqual("ITEM_TYPE", result.pending_interaction.field)
        self.assertTrue(result.pending_interaction.accepts_replacement)
        self.assertNotIn("what are you selling", result.message.content.casefold())
        self.assertEqual(0, product.calls)

    async def test_book_answer_advances_to_title_without_any_tool(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)

        updated, next_pending, response = apply_field_answer(
            workflow=workflow,
            pending=pending,
            user_message_id=USER,
            answer="a book",
            now=NOW,
        )

        self.assertEqual("book", updated.collected_fields.item_type.value)
        self.assertEqual("PROVIDED", updated.collected_fields.item_type.status)
        self.assertEqual("TITLE", next_pending.field)
        self.assertEqual(
            "Great—a book. What title would you like to use for the listing?",
            response,
        )
        self.assertEqual(USER, updated.last_resolved_user_message_id)

    async def test_replay_reconstructs_next_question_without_consuming_it(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        updated, next_pending, expected = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        self.assertEqual(expected, response_for_replayed_field(updated, next_pending))
        self.assertEqual("TITLE", next_pending.field)

    async def test_never_mind_cancels_workflow_without_tool(self) -> None:
        workflow, _ = start_create_listing_workflow(now=NOW)
        cancelled = cancel_create_listing_workflow(workflow, now=NOW)

        self.assertEqual("CANCELLED", cancelled.status)

    async def test_active_workflow_blocks_implicit_search_but_allows_explicit_comparison(self) -> None:
        workflow, _ = start_create_listing_workflow(now=NOW)
        proposal = ToolProposal(
            callId="seller-search", tool="search_listings",
            arguments={"query": "book", "limit": 5},
        )
        blocked = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            active_workflow=workflow,
            seller_search_allowed=False,
        )
        allowed = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(),
            active_workflow=workflow,
            seller_search_allowed=True,
        )

        _, rejection = blocked.validate(proposal, step=1)
        arguments, allowed_rejection = allowed.validate(proposal, step=1)

        self.assertEqual("TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW", rejection.reason)
        self.assertIsNotNone(arguments)
        self.assertIsNone(allowed_rejection)
        self.assertFalse(_explicit_seller_comparison_request("a book"))
        self.assertTrue(_explicit_seller_comparison_request("show me similar books"))

    async def test_fresh_book_is_not_a_seller_field_answer(self) -> None:
        classifier = MarketplaceScopeClassifier()
        result = classifier.classify(
            current_message="a book",
            recent_messages=(),
            referenced_listings=(),
            pending_interaction=None,
            preference_state={},
        )

        self.assertEqual("SHORT_PRODUCT_PHRASE", result.reason_code)

    async def test_buying_question_keeps_book_in_discovery_context(self) -> None:
        classifier = MarketplaceScopeClassifier()
        result = classifier.classify(
            current_message="a book",
            recent_messages=(("ASSISTANT", "What item do you want to buy?"),),
            referenced_listings=(),
            pending_interaction=None,
            preference_state={},
        )

        self.assertEqual("LISTING_DATA", result.required_grounding)

    async def test_registry_has_state_collection_but_no_create_or_publish_integration(self) -> None:
        registry = MarketplaceAgentV2ToolRegistry(_NoProductCalls())  # type: ignore[arg-type]

        self.assertIn("collect_listing_information", registry.names)
        self.assertNotIn("create_listing", registry.names)
        self.assertNotIn("publish_listing", registry.names)

    async def test_completed_collection_states_the_publish_limitation(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        response = ""
        for index, answer in enumerate(
            ("book", "My Book", "good", "$10", "Hardcover", "Irvine", "pickup")
        ):
            workflow, pending, response = apply_field_answer(
                workflow=workflow,
                pending=pending,
                user_message_id=(USER[:-1] + str((index + 1) % 10)),
                answer=answer,
                now=NOW,
            )

        self.assertEqual("READY_FOR_REVIEW", workflow.status)
        self.assertIsNone(pending)
        self.assertIn("Publishing is not connected", response)

    async def test_unknown_title_reply_remains_pending_for_help(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        workflow, pending, _ = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        updated, next_pending, response = apply_field_answer(
            workflow=workflow, pending=pending,
            user_message_id=USER[:-1] + "1", answer="I don't know yet.", now=NOW,
        )

        self.assertEqual(
            {"status": "NEEDS_HELP", "value": None, "reason": None},
            updated.collected_fields.model_dump(mode="json", by_alias=True)["title"],
        )
        self.assertEqual("TITLE", next_pending.field)
        self.assertIn("help create a title", response)
        self.assertNotIn("condition", response.casefold())

    async def test_deferred_title_advances_without_storing_control_text(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        workflow, pending, _ = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        updated, next_pending, response = apply_field_answer(
            workflow=workflow, pending=pending,
            user_message_id=USER[:-1] + "2", answer="skip for now", now=NOW,
        )

        self.assertEqual(
            {"status": "DEFERRED", "value": None, "reason": None},
            updated.collected_fields.model_dump(mode="json", by_alias=True)["title"],
        )
        self.assertEqual("CONDITION", next_pending.field)
        self.assertIn("come back", response)

    async def test_title_help_request_does_not_advance(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        workflow, pending, _ = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        updated, next_pending, response = apply_field_answer(
            workflow=workflow, pending=pending,
            user_message_id=USER[:-1] + "3", answer="suggest one", now=NOW,
        )

        self.assertEqual("NEEDS_HELP", updated.collected_fields.title.status)
        self.assertEqual("TITLE", next_pending.field)
        self.assertIn("detail", response.casefold())

    async def test_house_rejection_keeps_replacement_pending(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)

        updated, next_pending, response = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="house", now=NOW,
        )

        self.assertEqual("COLLECTING_INFORMATION", updated.status)
        self.assertEqual("REJECTED", updated.collected_fields.item_type.status)
        self.assertEqual("house", updated.collected_fields.item_type.value)
        self.assertEqual(
            "UNSUPPORTED_CATEGORY", updated.collected_fields.item_type.reason
        )
        self.assertEqual("ITEM_TYPE", next_pending.field)
        self.assertTrue(next_pending.accepts_replacement)
        self.assertIn("not real estate", response.casefold())
        self.assertNotIn("title", response.casefold())

    async def test_contextual_replacement_advances_without_a_product_tool(self) -> None:
        workflow, pending = start_create_listing_workflow(
            initial_item_type="house", now=NOW
        )

        resolution = resolve_pending_field_reply(
            pending=pending, answer="How about a phone?"
        )
        updated, next_pending, response = apply_field_resolution(
            workflow=workflow,
            pending=pending,
            user_message_id=USER,
            resolution=resolution,
            now=NOW,
        )

        self.assertEqual("REPLACE_FIELD_VALUE", resolution.resolution)
        self.assertEqual("PROVIDED", updated.collected_fields.item_type.status)
        self.assertEqual("phone", updated.collected_fields.item_type.value)
        self.assertIsNone(updated.collected_fields.item_type.reason)
        self.assertEqual("SUPPORTED", updated.item_type_eligibility)
        self.assertEqual("TITLE", next_pending.field)
        self.assertEqual(
            "A phone works. What title would you like to use for the listing?",
            response,
        )

    async def test_replacement_phrases_are_typed_only_for_rejected_item_type(self) -> None:
        workflow, pending = start_create_listing_workflow(
            initial_item_type="house", now=NOW
        )
        del workflow

        for phrase, expected in (
            ("what about a phone", "phone"),
            ("then a phone", "phone"),
            ("maybe a phone instead", "phone"),
            ("use phone", "phone"),
            ("change it to phone", "phone"),
            ("actually, a laptop", "laptop"),
            ("那手机呢", "手机"),
            ("换成手机", "手机"),
            ("那就卖手机", "手机"),
        ):
            resolved = resolve_pending_field_reply(pending=pending, answer=phrase)
            self.assertEqual("REPLACE_FIELD_VALUE", resolved.resolution)
            self.assertEqual(expected, resolved.normalized_value)

    async def test_second_unsupported_replacement_keeps_workflow_and_new_pending(self) -> None:
        workflow, pending = start_create_listing_workflow(
            initial_item_type="house", now=NOW
        )

        resolution = resolve_pending_field_reply(
            pending=pending, answer="How about an apartment?"
        )
        updated, next_pending, response = apply_field_resolution(
            workflow=workflow, pending=pending, user_message_id=USER,
            resolution=resolution, now=NOW,
        )

        self.assertEqual("COLLECTING_INFORMATION", updated.status)
        self.assertEqual("REJECTED", updated.collected_fields.item_type.status)
        self.assertEqual("apartment", updated.collected_fields.item_type.value)
        self.assertEqual("UNSUPPORTED_CATEGORY", updated.collected_fields.item_type.reason)
        self.assertEqual("ITEM_TYPE", next_pending.field)
        self.assertTrue(next_pending.accepts_replacement)
        self.assertIn("another type of item", response)

    async def test_legacy_house_rejection_restores_replacement_state_on_next_reply(self) -> None:
        legacy = MarketplaceAgentV2ActiveWorkflow(
            status="UNSUPPORTED",
            collectedFields={"itemType": "house"},
            itemTypeEligibility="UNSUPPORTED",
            lastUpdatedAt=NOW,
        )

        restored, pending = restore_rejected_item_pending(
            legacy, None, now=NOW
        )

        self.assertEqual("COLLECTING_INFORMATION", restored.status)
        self.assertEqual("REJECTED", restored.collected_fields.item_type.status)
        self.assertEqual("UNSUPPORTED_CATEGORY", restored.collected_fields.item_type.reason)
        self.assertEqual("ITEM_TYPE", pending.field)
        self.assertTrue(pending.accepts_replacement)

    async def test_supported_item_named_in_initial_sell_request_skips_item_question(self) -> None:
        product = _NoProductCalls()
        model = _Model([
            ModelDecision(toolProposal=ToolProposal(
                callId="seller-phone-start",
                tool="collect_listing_information",
                arguments={"field": "ITEM_TYPE"},
            )),
            ModelDecision(content=(
                "A phone works. What title would you like to use for the listing?"
            )),
        ])

        result = await MarketplaceAgentV2Orchestrator(
            model, MarketplaceAgentV2ToolRegistry(product)  # type: ignore[arg-type]
        ).run(
            actor_user_id=ACTOR,
            current_message="I want to sell a phone.",
            recent_messages=(), referenced_listings=(),
            correlation_id="seller-phone-start",
        )

        self.assertEqual("phone", result.active_workflow.collected_fields.item_type.value)
        self.assertEqual("TITLE", result.pending_interaction.field)
        self.assertNotIn("what are you selling", result.message.content.casefold())
        self.assertEqual(0, product.calls)

    async def test_legacy_unsupported_state_rejects_each_restart_proposal(self) -> None:
        """Records why the old state could consume all five model decisions."""

        workflow = MarketplaceAgentV2ActiveWorkflow(
            status="UNSUPPORTED",
            collectedFields={"itemType": "house"},
            itemTypeEligibility="UNSUPPORTED",
            lastUpdatedAt=NOW,
        )
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(), active_workflow=workflow
        )
        reasons: list[str] = []
        for step in range(1, 6):
            _, rejection = policy.validate(ToolProposal(
                callId=f"restart-{step}",
                tool="collect_listing_information",
                arguments=CollectListingInformationArguments().model_dump(
                    mode="json", by_alias=True
                ),
            ), step=step)
            reasons.append(rejection.reason)

        self.assertEqual(
            ["TOOL_NOT_ALLOWED_FOR_ACTIVE_WORKFLOW"] * 5,
            reasons,
        )

    async def test_valid_title_is_provided_and_advances(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        workflow, pending, _ = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        updated, next_pending, _ = apply_field_answer(
            workflow=workflow, pending=pending,
            user_message_id=USER[:-1] + "4",
            answer="Modern Three-Bedroom Home", now=NOW,
        )

        self.assertEqual("PROVIDED", updated.collected_fields.title.status)
        self.assertEqual(
            "Modern Three-Bedroom Home", updated.collected_fields.title.value
        )
        self.assertEqual("CONDITION", next_pending.field)

    async def test_cancel_reply_closes_pending_without_storing_it(self) -> None:
        workflow, pending = start_create_listing_workflow(now=NOW)
        workflow, pending, _ = apply_field_answer(
            workflow=workflow, pending=pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        updated, next_pending, response = apply_field_answer(
            workflow=workflow, pending=pending,
            user_message_id=USER[:-1] + "5", answer="never mind", now=NOW,
        )

        self.assertEqual("CANCELLED", updated.status)
        self.assertIsNone(next_pending)
        self.assertEqual("MISSING", updated.collected_fields.title.status)
        self.assertIn("stopped", response)

    async def test_multilingual_pending_control_phrases_are_typed(self) -> None:
        workflow, item_pending = start_create_listing_workflow(now=NOW)
        workflow, title_pending, _ = apply_field_answer(
            workflow=workflow, pending=item_pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        for phrase in ("不知道", "还没想好", "不确定"):
            resolved = resolve_pending_field_reply(
                pending=title_pending, answer=phrase
            )
            self.assertEqual("UNKNOWN", resolved.resolution)
            self.assertFalse(resolved.consume_pending_interaction)
        for phrase in ("先跳过", "以后再填"):
            resolved = resolve_pending_field_reply(
                pending=title_pending, answer=phrase
            )
            self.assertEqual("DEFER", resolved.resolution)
            self.assertTrue(resolved.consume_pending_interaction)
        for phrase in ("帮我写", "给我建议"):
            resolved = resolve_pending_field_reply(
                pending=title_pending, answer=phrase
            )
            self.assertEqual("REQUEST_HELP", resolved.resolution)
            self.assertFalse(resolved.consume_pending_interaction)

    async def test_legacy_string_fields_restore_as_provided(self) -> None:
        fields = MarketplaceAgentV2SellerFields.model_validate({
            "itemType": "book", "title": None,
        })

        self.assertEqual("PROVIDED", fields.item_type.status)
        self.assertEqual("book", fields.item_type.value)
        self.assertEqual("MISSING", fields.title.status)

    async def test_nonprovided_field_cannot_carry_literal_control_text(self) -> None:
        with self.assertRaises(ValueError):
            MarketplaceAgentV2SellerFields.model_validate({
                "title": {"status": "NEEDS_HELP", "value": "I don't know yet"}
            })

    async def test_unrelated_question_does_not_consume_pending_title(self) -> None:
        workflow, item_pending = start_create_listing_workflow(now=NOW)
        _, title_pending, _ = apply_field_answer(
            workflow=workflow, pending=item_pending, user_message_id=USER,
            answer="book", now=NOW,
        )

        resolved = resolve_pending_field_reply(
            pending=title_pending, answer="What is your return policy?"
        )

        self.assertEqual("UNRELATED_OR_NEW_INTENT", resolved.resolution)
        self.assertFalse(resolved.consume_pending_interaction)


if __name__ == "__main__":
    unittest.main()
