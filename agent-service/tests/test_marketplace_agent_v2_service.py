from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from msb_agent_service.agent_persistence import (
    AgentInvocation,
    AgentInvocationStatus,
    AgentMessage,
    AgentMessageRole,
    AgentResolutionType,
    AgentSessionStatus,
    BeginInvocation,
    BeginInvocationResult,
    MessagePage,
)
from msb_agent_service.marketplace_agent_v2.persistence import (
    MarketplaceAgentV2Session,
    SellerFieldResolution,
    _pending_state,
)
from msb_agent_service.marketplace_agent_v2.orchestrator import (
    MarketplaceAgentV2OrchestrationFailure,
)
from msb_agent_service.marketplace_agent_v2.schemas import (
    EvidenceReference,
    ListingAttachment,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2Message,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceAgentV2Refinement,
    OrchestrationResult,
    ToolFacets,
    ToolObservation,
)
from msb_agent_service.marketplace_agent_v2.seller_workflow import (
    apply_field_answer,
    apply_field_resolution,
    cancel_create_listing_workflow,
    resolve_pending_field_reply,
    response_for_replayed_field,
    restore_rejected_item_pending,
    start_create_listing_workflow,
)
from msb_agent_service.marketplace_agent_v2.service import (
    MarketplaceAgentV2Service,
    _assistant_message,
    _marketplace_context_messages,
    _referenced_listings,
    _workflow_from_state,
)


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
USER = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
ASSISTANT = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
INVOCATION = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
CLIENT = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
NOW = datetime.now(UTC)


def _invocation(status: AgentInvocationStatus, assistant_id: str | None = None) -> AgentInvocation:
    return AgentInvocation(
        invocation_id=INVOCATION, session_id=SESSION, actor_user_id=ACTOR,
        user_message_id=USER, assistant_message_id=assistant_id,
        client_message_id=CLIENT, request_hash="a" * 64, result_status=status,
        error_code=None, prompt_version="p", model_provider="openai", model_name="m",
        schema_version="s", tool_registry_version="t", policy_version="p",
        input_tokens=0, output_tokens=0, latency_ms=None, estimated_cost=Decimal("0"),
        retry_count=0, correlation_id="c", created_at=NOW, updated_at=NOW,
        completed_at=NOW if status != AgentInvocationStatus.PENDING else None,
        optimistic_version=0,
    )


def _user() -> AgentMessage:
    return AgentMessage(
        message_id=USER, session_id=SESSION, actor_user_id=ACTOR,
        role=AgentMessageRole.USER, body="hi", resolution_type=None,
        sources=(), actions=(), created_at=NOW, client_message_id=CLIENT,
        invocation_id=INVOCATION, invocation_status=AgentInvocationStatus.PENDING,
    )


def _assistant() -> AgentMessage:
    return AgentMessage(
        message_id=ASSISTANT, session_id=SESSION, actor_user_id=ACTOR,
        role=AgentMessageRole.ASSISTANT, body="Hello!", resolution_type=AgentResolutionType.ANSWERED,
        sources=(), actions=(), created_at=NOW, invocation_id=INVOCATION,
        invocation_status=AgentInvocationStatus.SUCCEEDED,
    )


class _Conversation:
    def __init__(self) -> None:
        self.completions = 0
        self.failures = 0
        self.failure_kwargs: dict[str, object] | None = None
        self.completion_kwargs: dict[str, object] | None = None
        self.tool_calls = 0

    @property
    def maximum_failed_retries(self) -> int:
        return 2

    async def append_tool_call(self, **_: Any) -> None:
        self.tool_calls += 1
        return None

    async def complete_invocation(self, **kwargs: Any) -> tuple[AgentInvocation, AgentMessage]:
        self.completions += 1
        self.completion_kwargs = kwargs
        return _invocation(AgentInvocationStatus.SUCCEEDED, ASSISTANT), _assistant()

    async def fail_invocation_with_assistant(self, **kwargs: Any) -> tuple[AgentInvocation, AgentMessage]:
        self.failures += 1
        self.failure_kwargs = kwargs
        return _invocation(AgentInvocationStatus.FAILED, ASSISTANT), _assistant()

    async def get_invocation_for_user_message(self, **_: Any) -> tuple[AgentInvocation, AgentMessage]:
        return _invocation(AgentInvocationStatus.FAILED, ASSISTANT), _user()

    async def get_invocation_for_client_message(self, **_: Any) -> tuple[AgentInvocation, AgentMessage]:
        return _invocation(AgentInvocationStatus.FAILED, ASSISTANT), _user()


class _Persistence:
    def __init__(self) -> None:
        self.conversation = _Conversation()
        self.begin_result = BeginInvocationResult.CREATED
        self.messages = (_user(),)
        self.pending: MarketplaceAgentV2PendingInteraction | None = None
        self.workflow: MarketplaceAgentV2ActiveWorkflow | None = None
        self.consume_calls = 0
        self.cancel_calls = 0
        self.seller_resolve_calls = 0
        self.workflow_writes = 0

    async def get(self, **_: Any) -> MarketplaceAgentV2Session:
        return MarketplaceAgentV2Session(
            session_id=SESSION, actor_user_id=ACTOR, status=AgentSessionStatus.OPEN,
            created_at=NOW, updated_at=NOW, last_activity_at=NOW,
            preference_state={
                **({} if self.pending is None else {
                    "pendingInteraction": self.pending.model_dump(
                        mode="json", by_alias=True
                    )
                }),
                **({} if self.workflow is None else {
                    "activeWorkflow": self.workflow.model_dump(
                        mode="json", by_alias=True, exclude_none=True
                    )
                }),
            },
        )

    async def begin(self, **_: Any) -> BeginInvocation:
        return BeginInvocation(
            result=self.begin_result,
            invocation=_invocation(
                AgentInvocationStatus.SUCCEEDED
                if self.begin_result == BeginInvocationResult.DEDUPLICATED_SUCCEEDED
                else AgentInvocationStatus.PENDING,
                ASSISTANT if self.begin_result == BeginInvocationResult.DEDUPLICATED_SUCCEEDED else None,
            ),
            user_message=_user(),
        )

    async def list_messages(self, **_: Any) -> MessagePage:
        return MessagePage(messages=self.messages, next_cursor=None)

    async def consume_pending_interaction(self, **kwargs: Any) -> object | None:
        self.consume_calls += 1
        if self.pending is None or self.pending.status != "WAITING":
            return None
        self.pending = self.pending.model_copy(update={
            "status": "CONSUMED" if kwargs["accepted"] else "CANCELLED"
        })
        return self.pending

    async def cancel_pending_interaction(self, **_: Any) -> object | None:
        self.cancel_calls += 1
        if self.pending is None or self.pending.status != "WAITING":
            return None
        self.pending = self.pending.model_copy(update={"status": "CANCELLED"})
        return self.pending

    async def set_pending_interaction(self, **kwargs: Any) -> object:
        self.pending = kwargs["interaction"]
        return self.pending

    async def set_workflow_state(self, **kwargs: Any) -> None:
        self.workflow_writes += 1
        self.workflow = kwargs["workflow"]
        self.pending = kwargs["pending_interaction"]

    async def resolve_seller_field_answer(self, **kwargs: Any) -> SellerFieldResolution | None:
        self.seller_resolve_calls += 1
        if self.workflow is None:
            return None
        self.workflow, self.pending = restore_rejected_item_pending(
            self.workflow, self.pending, now=kwargs["now"]
        )
        if self.workflow.last_resolved_user_message_id == kwargs["user_message_id"]:
            return SellerFieldResolution(
                workflow=self.workflow,
                pending_interaction=self.pending,
                response=response_for_replayed_field(self.workflow, self.pending),
                replayed=True,
            )
        if self.pending is None or self.pending.type != "ANSWER_FIELD":
            return None
        semantic = resolve_pending_field_reply(
            pending=self.pending, answer=kwargs["answer"]
        )
        if semantic.resolution == "UNRELATED_OR_NEW_INTENT":
            return None
        self.workflow, self.pending, response = apply_field_resolution(
            workflow=self.workflow,
            pending=self.pending,
            user_message_id=kwargs["user_message_id"],
            resolution=semantic,
            now=kwargs["now"],
        )
        return SellerFieldResolution(
            workflow=self.workflow,
            pending_interaction=self.pending,
            response=response,
            replayed=False,
            resolution=semantic.resolution,
        )

    async def cancel_seller_workflow(self, **kwargs: Any) -> MarketplaceAgentV2ActiveWorkflow | None:
        self.cancel_calls += 1
        if self.workflow is None:
            return None
        self.workflow = cancel_create_listing_workflow(
            self.workflow, now=kwargs["now"]
        )
        self.pending = None
        return self.workflow


class _Orchestrator:
    def __init__(self, *, fail: bool = False) -> None:
        self.calls = 0
        self.fail = fail
        self.kwargs: dict[str, object] | None = None

    async def run(self, **kwargs: Any) -> OrchestrationResult:
        self.calls += 1
        self.kwargs = kwargs
        if self.fail:
            raise RuntimeError("provider payload must stay private")
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(content="Hello!"), decisionCount=1,
            scopeResult=kwargs["scope_result"],
        )


class _PendingOrchestrator(_Orchestrator):
    async def run(self, **kwargs: Any) -> OrchestrationResult:
        self.calls += 1
        self.kwargs = kwargs
        pending = MarketplaceAgentV2PendingInteraction(
            id="01ARZ3NDEKTSV4RRFFQ69G5FB5", type="CONFIRM_ACTION",
            action="RUN_REFINED_SEARCH", arguments={"query": "lamp under 25", "limit": 5},
            status="WAITING", createdAt=NOW,
        )
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(
                content="Would you like me to run that narrower search?",
                pendingInteraction=pending,
            ),
            decisionCount=1,
            pendingInteraction=pending,
        )


class _ObservationOrchestrator(_Orchestrator):
    async def run(self, **kwargs: Any) -> OrchestrationResult:
        self.calls += 1
        self.kwargs = kwargs
        observation = ToolObservation(
            tool="search_listings", status="SUCCEEDED",
            reason="RESULTS_AVAILABLE", normalizedQuery="chair",
            broadInventoryCount=25, exactMatchCount=5, resultCount=5,
            totalMatches=25, relevantMatchCount=25, retrievalConfidence="HIGH",
            presentationHint="RESULTS_WITH_REFINEMENT",
            facets=ToolFacets(subtype=(
                {"value": "Dining Chair", "count": 12},
                {"value": "Gaming Chair", "count": 8},
                {"value": "Folding Chair", "count": 5},
            )),
        )
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(
                content="Here are current chair listings to start with.",
                refinement=MarketplaceAgentV2Refinement(
                    question="Would you like me to focus on one of these available types?",
                    options=(
                        {"facet": "SUBTYPE", "value": "Dining Chair", "count": 12},
                        {"facet": "SUBTYPE", "value": "Gaming Chair", "count": 8},
                    ),
                ),
            ),
            decisionCount=2,
            observations=(observation,),
        )


class _SellerWorkflowOrchestrator(_Orchestrator):
    async def run(self, **kwargs: Any) -> OrchestrationResult:
        self.calls += 1
        self.kwargs = kwargs
        workflow, pending = start_create_listing_workflow(now=NOW)
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(
                content="Great. What are you selling?",
                pendingInteraction=pending,
            ),
            decisionCount=2,
            pendingInteraction=pending,
            activeWorkflow=workflow,
            scopeResult=kwargs["scope_result"],
        )


class _NoScopeAfterPending:
    def classify(self, **_: Any) -> object:
        raise AssertionError("A seller field answer must resolve before scope classification")


class _EvidenceOrchestrator(_Orchestrator):
    async def run(self, **kwargs: Any) -> OrchestrationResult:
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(content="This listing is current."),
            decisionCount=1,
            evidence=(EvidenceReference(
                sourceType="LISTING", sourceId="01ARZ3NDEKTSV4RRFFQ69G5FB1",
                version="a" * 64, retrievedAt=NOW,
            ),),
            scopeResult=kwargs["scope_result"],
        )


class _BlockingOrchestrator:
    def __init__(self, *, after_delta: bool) -> None:
        self.after_delta = after_delta
        self.started = asyncio.Event()

    async def run(self, **kwargs: Any) -> OrchestrationResult:
        if self.after_delta:
            await kwargs["text_delta"]("Partial answer")
        self.started.set()
        await asyncio.Event().wait()
        raise AssertionError("unreachable")


class MarketplaceAgentV2ServiceTest(unittest.IsolatedAsyncioTestCase):
    def test_seller_workflow_state_round_trips_through_session_json(self) -> None:
        workflow, _ = start_create_listing_workflow(now=NOW)
        state = {
            "activeWorkflow": workflow.model_dump(
                mode="json", by_alias=True, exclude_none=True
            )
        }

        restored = _workflow_from_state(state)

        self.assertEqual("CREATE_LISTING", restored.type)
        self.assertEqual("COLLECTING_INFORMATION", restored.status)

    def test_only_latest_recommendation_set_is_active_context(self) -> None:
        first = ListingAttachment(
            listingId="01ARZ3NDEKTSV4RRFFQ69G5FB1", title="Key organizer",
            categoryName="Organizers", condition="GOOD", priceAmount=Decimal("15.00"),
            currency="USD", publicCity="Irvine", publicRegion="Orange County",
            checkedAt=NOW, responseHash="a" * 64,
        )
        latest = ListingAttachment(
            listingId="01ARZ3NDEKTSV4RRFFQ69G5FB2", title="Desk lamp",
            categoryName="Lamps", condition="NEW", priceAmount=Decimal("25.00"),
            currency="USD", publicCity="Irvine", publicRegion="Orange County",
            checkedAt=NOW, responseHash="b" * 64,
        )
        messages = (
            replace(_assistant(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB3",
                    sources=(first.model_dump(mode="json", by_alias=True),)),
            replace(_assistant(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB4",
                    sources=(latest.model_dump(mode="json", by_alias=True),)),
        )

        active = _referenced_listings(messages)

        self.assertEqual((latest.listing_id,), tuple(item.listing_id for item in active))

    def test_history_decodes_persisted_iso_activity_timestamp(self) -> None:
        persisted = replace(
            _assistant(),
            actions=(
                {
                    "tool": "check_availability",
                    "status": "SUCCEEDED",
                    "reason": "CATEGORY_UNAVAILABLE",
                    "observedAt": "2026-07-30T06:06:55.568853Z",
                },
            ),
        )

        restored = _assistant_message(persisted)

        self.assertEqual("check_availability", restored.tool_activity[0].tool)
        self.assertEqual(UTC, restored.tool_activity[0].observed_at.tzinfo)

    async def test_safe_observation_is_persisted_and_restored_into_next_turn(self) -> None:
        persistence = _Persistence()
        first = _ObservationOrchestrator()
        service = MarketplaceAgentV2Service(
            persistence, first, provider_name="openai", model_name="test"
        )

        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="chair", correlation_id="v2-observation-first",
        )
        actions = persistence.conversation.completion_kwargs["actions"]
        snapshot = next(
            action for action in actions
            if action.get("type") == "MARKETPLACE_AGENT_V2_OBSERVATION"
        )
        persistence.messages = (
            _user(),
            replace(_assistant(), actions=(snapshot,)),
        )
        second = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, second, provider_name="openai", model_name="test"
        )

        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="under 200", correlation_id="v2-observation-second",
        )

        restored = second.kwargs["prior_observations"]
        self.assertEqual("chair", restored[0].normalized_query)
        self.assertEqual("RESULTS_AVAILABLE", restored[0].reason)
        self.assertEqual("RESULTS_WITH_REFINEMENT", restored[0].presentation_hint)
        self.assertEqual(
            ("Dining Chair", "Gaming Chair", "Folding Chair"),
            tuple(item.value for item in restored[0].facets.subtype),
        )
        persisted_refinement = next(
            action for action in actions
            if action.get("type") == "MARKETPLACE_AGENT_V2_REFINEMENT"
        )
        restored_message = _assistant_message(replace(
            _assistant(), actions=(persisted_refinement,)
        ))
        self.assertEqual(
            ("Dining Chair", "Gaming Chair"),
            tuple(item.value for item in restored_message.refinement.options),
        )

    async def test_committed_user_gets_exactly_one_assistant_completion(self) -> None:
        persistence = _Persistence()
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        response = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="hi", correlation_id="v2-service",
        )

        self.assertEqual("Hello!", response.message.content)
        self.assertEqual(1, orchestrator.calls)
        self.assertEqual(1, persistence.conversation.completions)
        self.assertEqual(0, persistence.conversation.failures)

    async def test_seller_workflow_start_is_persisted_with_item_type_pending(self) -> None:
        persistence = _Persistence()
        orchestrator = _SellerWorkflowOrchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT,
            body="I want to sell an item.",
            correlation_id="v2-seller-start",
        )

        self.assertEqual("CREATE_LISTING", persistence.workflow.type)
        self.assertEqual("ITEM_TYPE", persistence.pending.field)
        self.assertEqual(1, persistence.workflow_writes)
        self.assertEqual("Great. What are you selling?", response.message.content)
        self.assertEqual(0, persistence.conversation.tool_calls)

    async def test_seller_field_answer_is_consumed_before_scope_and_without_tools(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(now=NOW)
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence,
            orchestrator,
            provider_name="openai",
            model_name="test",
            scope_classifier=_NoScopeAfterPending(),  # type: ignore[arg-type]
        )

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT,
            body="a book",
            correlation_id="v2-seller-book",
        )

        self.assertEqual("book", persistence.workflow.collected_fields.item_type.value)
        self.assertEqual("TITLE", persistence.pending.field)
        self.assertEqual(
            "Great—a book. What title would you like to use for the listing?",
            response.message.content,
        )
        self.assertEqual(0, response.decision_count)
        self.assertEqual(0, orchestrator.calls)
        self.assertEqual(0, persistence.conversation.tool_calls)
        self.assertEqual(1, persistence.conversation.completions)

    async def test_retry_does_not_consume_the_next_seller_field(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(now=NOW)
        service = MarketplaceAgentV2Service(
            persistence,
            _Orchestrator(),
            provider_name="openai",
            model_name="test",
            scope_classifier=_NoScopeAfterPending(),  # type: ignore[arg-type]
        )

        first = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="book", correlation_id="v2-seller-book-first",
        )
        replay = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="book", correlation_id="v2-seller-book-retry",
        )

        self.assertEqual(first.message.content, replay.message.content)
        self.assertEqual("book", persistence.workflow.collected_fields.item_type.value)
        self.assertEqual("MISSING", persistence.workflow.collected_fields.title.status)
        self.assertEqual("TITLE", persistence.pending.field)
        self.assertEqual(2, persistence.seller_resolve_calls)

    async def test_explicit_similar_listing_request_does_not_consume_seller_field(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(now=NOW)
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="show me similar books", correlation_id="v2-seller-comparison",
        )

        self.assertEqual(0, persistence.seller_resolve_calls)
        self.assertEqual("ITEM_TYPE", persistence.pending.field)
        self.assertEqual("CREATE_LISTING", orchestrator.kwargs["active_workflow"].type)

    async def test_never_mind_cancels_seller_pending_without_tool(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(now=NOW)
        service = MarketplaceAgentV2Service(
            persistence, _Orchestrator(), provider_name="openai", model_name="test"
        )

        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="never mind", correlation_id="v2-seller-cancel",
        )

        self.assertEqual("CANCELLED", persistence.workflow.status)
        self.assertIsNone(persistence.pending)
        self.assertEqual(0, persistence.conversation.tool_calls)

    async def test_unknown_title_persists_help_state_and_replays_without_advancing(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(now=NOW)
        persistence.workflow, persistence.pending, _ = apply_field_answer(
            workflow=persistence.workflow,
            pending=persistence.pending,
            user_message_id=USER[:-1] + "W",
            answer="book",
            now=NOW,
        )
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence,
            orchestrator,
            provider_name="openai",
            model_name="test",
            scope_classifier=_NoScopeAfterPending(),  # type: ignore[arg-type]
        )

        first = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="I don't know yet.", correlation_id="v2-seller-title-unknown",
        )
        restored = _workflow_from_state((await persistence.get()).preference_state)
        replay = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="I don't know yet.", correlation_id="v2-seller-title-unknown-retry",
        )

        self.assertEqual("NEEDS_HELP", persistence.workflow.collected_fields.title.status)
        self.assertIsNone(persistence.workflow.collected_fields.title.value)
        self.assertEqual("TITLE", persistence.pending.field)
        self.assertEqual("NEEDS_HELP", restored.collected_fields.title.status)
        self.assertEqual(first.message.content, replay.message.content)
        self.assertIn("help create a title", first.message.content)
        self.assertNotIn("condition", first.message.content.casefold())
        self.assertEqual(0, orchestrator.calls)
        self.assertEqual(2, persistence.seller_resolve_calls)

    async def test_rejected_item_replacement_resolves_before_scope_or_orchestrator(self) -> None:
        persistence = _Persistence()
        persistence.workflow, persistence.pending = start_create_listing_workflow(
            initial_item_type="house", now=NOW
        )
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence,
            orchestrator,
            provider_name="openai",
            model_name="test",
            scope_classifier=_NoScopeAfterPending(),  # type: ignore[arg-type]
        )

        response = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT,
            body="How about a phone?",
            correlation_id="v2-seller-item-replacement",
        )
        restored = _workflow_from_state((await persistence.get()).preference_state)
        replay = await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT,
            body="How about a phone?",
            correlation_id="v2-seller-item-replacement-retry",
        )

        self.assertEqual(
            "A phone works. What title would you like to use for the listing?",
            response.message.content,
        )
        self.assertEqual("PROVIDED", restored.collected_fields.item_type.status)
        self.assertEqual("phone", restored.collected_fields.item_type.value)
        self.assertEqual("TITLE", persistence.pending.field)
        self.assertEqual(response.message.content, replay.message.content)
        self.assertEqual(0, orchestrator.calls)
        self.assertEqual(0, persistence.conversation.tool_calls)

    async def test_legacy_terminal_rejection_is_recovered_for_replacement(self) -> None:
        persistence = _Persistence()
        persistence.workflow = MarketplaceAgentV2ActiveWorkflow(
            status="UNSUPPORTED",
            collectedFields={"itemType": "house"},
            itemTypeEligibility="UNSUPPORTED",
            lastUpdatedAt=NOW,
        )
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test",
            scope_classifier=_NoScopeAfterPending(),  # type: ignore[arg-type]
        )

        response = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="Actually, a laptop.", correlation_id="v2-legacy-replacement",
        )

        self.assertEqual("PROVIDED", persistence.workflow.collected_fields.item_type.status)
        self.assertEqual("laptop", persistence.workflow.collected_fields.item_type.value)
        self.assertEqual("TITLE", persistence.pending.field)
        self.assertIn("laptop works", response.message.content)
        self.assertEqual(0, orchestrator.calls)

    async def test_replacement_marker_persists_privately_and_stays_off_public_message(self) -> None:
        _, pending = start_create_listing_workflow(
            initial_item_type="house", now=NOW
        )

        stored = _pending_state(pending)
        restored = MarketplaceAgentV2PendingInteraction.model_validate_json(
            json.dumps(stored)
        )
        public = pending.model_dump(mode="json", by_alias=True, exclude_none=True)

        self.assertIs(True, stored["acceptsReplacement"])
        self.assertTrue(restored.accepts_replacement)
        self.assertNotIn("acceptsReplacement", public)

    async def test_internal_grounding_evidence_is_persisted_without_entering_message_body(self) -> None:
        persistence = _Persistence()
        service = MarketplaceAgentV2Service(
            persistence, _EvidenceOrchestrator(),
            provider_name="openai", model_name="test",
        )

        response = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="check this listing", correlation_id="v2-evidence",
        )

        evidence = next(
            action for action in persistence.conversation.completion_kwargs["actions"]
            if action.get("type") == "MARKETPLACE_AGENT_V2_EVIDENCE"
        )
        self.assertEqual("LISTING", evidence["sourceType"])
        self.assertNotIn(evidence["sourceId"], response.message.content)

    async def test_out_of_scope_turn_preserves_pending_state_and_persists_scope_once(self) -> None:
        persistence = _Persistence()
        persistence.pending = MarketplaceAgentV2PendingInteraction(
            id="01ARZ3NDEKTSV4RRFFQ69G5FB5",
            type="CONFIRM_ACTION",
            action="RUN_REFINED_SEARCH",
            arguments={"query": "lamp under 25", "limit": 5},
            status="WAITING",
            createdAt=NOW,
        )
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        await service.send_message(
            actor_user_id=ACTOR,
            session_id=SESSION,
            client_message_id=CLIENT,
            body="write a python scipt",
            correlation_id="v2-scope-preserve",
        )

        self.assertEqual("WAITING", persistence.pending.status)
        self.assertEqual(0, persistence.cancel_calls)
        self.assertEqual("OUT_OF_SCOPE", orchestrator.kwargs["scope_result"].scope)
        actions = persistence.conversation.completion_kwargs["actions"]
        scopes = [
            item for item in actions
            if item.get("type") == "MARKETPLACE_AGENT_V2_SCOPE"
        ]
        self.assertEqual(1, len(scopes))
        self.assertEqual("OUT_OF_SCOPE", scopes[0]["scope"])
        self.assertEqual("UNRELATED_CODE_REQUEST", scopes[0]["reasonCode"])

    def test_out_of_scope_pair_is_excluded_without_losing_active_recommendations(self) -> None:
        listing = ListingAttachment(
            listingId="01ARZ3NDEKTSV4RRFFQ69G5FB1",
            title="Desk lamp", categoryName="Lamp", condition="GOOD",
            priceAmount=Decimal("20.00"), currency="USD", checkedAt=NOW,
            responseHash="b" * 64,
        )
        lamp_user = replace(
            _user(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB2", body="Show me lamps."
        )
        lamp_assistant = replace(
            _assistant(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB3",
            body="I found current lamp listings.",
            sources=(listing.model_dump(mode="json", by_alias=True),),
            invocation_user_message_id=lamp_user.message_id,
        )
        unrelated_user = replace(
            _user(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB4",
            body="Write Python code.",
        )
        unrelated_assistant = replace(
            _assistant(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB5",
            body="I'm focused on marketplace help.",
            actions=({
                "type": "MARKETPLACE_AGENT_V2_SCOPE",
                "scope": "OUT_OF_SCOPE",
                "confidence": "HIGH",
                "marketplaceContextUsed": False,
                "reasonCode": "UNRELATED_CODE_REQUEST",
            },),
            invocation_user_message_id=unrelated_user.message_id,
        )
        current = replace(
            _user(), message_id="01ARZ3NDEKTSV4RRFFQ69G5FB6",
            body="What about the lamp?",
        )
        messages = (
            lamp_user, lamp_assistant, unrelated_user, unrelated_assistant, current,
        )

        context = _marketplace_context_messages(
            messages, current_user_message_id=current.message_id
        )

        self.assertEqual(
            (("USER", "Show me lamps."), ("ASSISTANT", "I found current lamp listings.")),
            context,
        )
        self.assertEqual((listing,), _referenced_listings(messages))

    async def test_pending_confirmation_is_persisted_and_consumed_exactly_once(self) -> None:
        persistence = _Persistence()
        first = _PendingOrchestrator()
        service = MarketplaceAgentV2Service(
            persistence, first, provider_name="openai", model_name="test"
        )

        response = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="Could you narrow these further?", correlation_id="v2-pending-create",
        )

        self.assertEqual("WAITING", response.message.pending_interaction.status)
        self.assertEqual("WAITING", persistence.pending.status)

        confirmed = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, confirmed, provider_name="openai", model_name="test"
        )
        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION,
            client_message_id="01ARZ3NDEKTSV4RRFFQ69G5FB6",
            body="yes", correlation_id="v2-pending-consume",
        )
        self.assertEqual("CONSUMED", confirmed.kwargs["confirmed_interaction"].status)

        repeated = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, repeated, provider_name="openai", model_name="test"
        )
        await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION,
            client_message_id="01ARZ3NDEKTSV4RRFFQ69G5FB7",
            body="yes", correlation_id="v2-pending-repeat",
        )

        self.assertIsNone(repeated.kwargs["confirmed_interaction"])
        self.assertEqual(2, persistence.consume_calls)

    async def test_history_overlays_consumed_pending_state_without_duplicate_action(self) -> None:
        persistence = _Persistence()
        pending = MarketplaceAgentV2PendingInteraction(
            id="01ARZ3NDEKTSV4RRFFQ69G5FB5", type="CONFIRM_ACTION",
            action="RUN_REFINED_SEARCH", arguments={"query": "lamp", "limit": 5},
            status="CONSUMED", createdAt=NOW,
        )
        persistence.pending = pending
        persisted_waiting = pending.model_copy(update={"status": "WAITING"})
        persistence.messages = (replace(
            _assistant(),
            actions=({
                "type": "MARKETPLACE_AGENT_V2_PENDING_INTERACTION",
                "interaction": persisted_waiting.model_dump(mode="json", by_alias=True),
            },),
        ),)
        service = MarketplaceAgentV2Service(
            persistence, _Orchestrator(), provider_name="openai", model_name="test"
        )

        history = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION
        )

        self.assertEqual(1, len(history.data))
        self.assertEqual(
            "CONSUMED", history.data[0].message.pending_interaction.status
        )

    async def test_provider_failure_persists_a_retryable_terminal_assistant(self) -> None:
        persistence = _Persistence()
        service = MarketplaceAgentV2Service(
            persistence, _Orchestrator(fail=True), provider_name="openai", model_name="test"
        )

        with self.assertRaises(RuntimeError):
            await service.send_message(
                actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
                body="chair", correlation_id="v2-service-failure",
            )

        self.assertEqual(1, persistence.conversation.failures)
        self.assertEqual(0, persistence.conversation.completions)

    async def test_unsupported_greeting_persists_guarded_retryable_assistant(self) -> None:
        class UnsupportedGreetingOrchestrator:
            async def run(self, **_: Any) -> OrchestrationResult:
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_RESPONSE_UNSUPPORTED"
                )

        persistence = _Persistence()
        service = MarketplaceAgentV2Service(
            persistence, UnsupportedGreetingOrchestrator(),
            provider_name="openai", model_name="test",
        )

        with self.assertRaises(MarketplaceAgentV2OrchestrationFailure):
            await service.send_message(
                actor_user_id=ACTOR, session_id=SESSION,
                client_message_id=CLIENT, body="hi",
                correlation_id="v2-unsupported-greeting",
            )

        failure = persistence.conversation.failure_kwargs
        self.assertEqual(
            "MARKETPLACE_AGENT_V2_MODEL_RESPONSE_UNSUPPORTED",
            failure["error_code"],
        )
        self.assertEqual(AgentResolutionType.PARTIAL, failure["resolution_type"])
        self.assertIn("retry without sending your message again", failure["body"])
        self.assertEqual(
            ({"type": "MARKETPLACE_AGENT_V2_FAILURE", "retryable": True},),
            failure["actions"],
        )

    async def test_validation_failure_is_not_labeled_as_service_unavailability(self) -> None:
        class ValidationOrchestrator:
            async def run(self, **_: Any) -> OrchestrationResult:
                raise ValueError("private validation detail")

        persistence = _Persistence()
        service = MarketplaceAgentV2Service(
            persistence, ValidationOrchestrator(),
            provider_name="openai", model_name="test",
        )

        with self.assertRaises(ValueError):
            await service.send_message(
                actor_user_id=ACTOR, session_id=SESSION,
                client_message_id=CLIENT, body="ambiguous",
                correlation_id="v2-validation-copy",
            )

        body = persistence.conversation.failure_kwargs["body"]
        self.assertIn("couldn't safely complete", body)
        self.assertNotIn("service was temporarily unavailable", body)

    async def test_successful_idempotent_replay_does_zero_model_work(self) -> None:
        persistence = _Persistence()
        persistence.begin_result = BeginInvocationResult.DEDUPLICATED_SUCCEEDED
        persistence.messages = (_user(), _assistant())
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        response = await service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="hi", correlation_id="v2-service-replay",
        )

        self.assertEqual("Hello!", response.message.content)
        self.assertEqual(0, orchestrator.calls)
        self.assertEqual(0, persistence.conversation.completions)

    async def test_stop_after_delta_persists_exact_partial_and_retry_identity(self) -> None:
        persistence = _Persistence()
        orchestrator = _BlockingOrchestrator(after_delta=True)
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="chair", correlation_id="v2-cancel",
        ))
        await orchestrator.started.wait()

        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

        self.assertEqual(1, persistence.conversation.failures)
        self.assertEqual("Partial answer", persistence.conversation.failure_kwargs["body"])
        self.assertEqual(
            "MARKETPLACE_AGENT_V2_STREAM_CANCELLED",
            persistence.conversation.failure_kwargs["error_code"],
        )

    async def test_stop_before_first_delta_persists_zero_text_terminal_state(self) -> None:
        persistence = _Persistence()
        orchestrator = _BlockingOrchestrator(after_delta=False)
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )
        task = asyncio.create_task(service.send_message(
            actor_user_id=ACTOR, session_id=SESSION, client_message_id=CLIENT,
            body="chair", correlation_id="v2-cancel-zero",
        ))
        await orchestrator.started.wait()

        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

        self.assertEqual(1, persistence.conversation.failures)
        self.assertEqual(
            "Response stopped before the answer began.",
            persistence.conversation.failure_kwargs["body"],
        )
        self.assertEqual(AgentResolutionType.PARTIAL,
                         persistence.conversation.failure_kwargs["resolution_type"])

    async def test_history_exposes_orphaned_failed_user_as_response_retry(self) -> None:
        persistence = _Persistence()
        persistence.messages = (replace(
            _user(),
            invocation_status=AgentInvocationStatus.FAILED,
            invocation_retry_count=0,
            invocation_assistant_message_id=None,
        ),)
        service = MarketplaceAgentV2Service(
            persistence, _Orchestrator(), provider_name="openai", model_name="test"
        )

        history = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION
        )

        self.assertEqual(1, len(history.data))
        self.assertTrue(history.data[0].retryable)
        self.assertEqual(USER, history.data[0].response_retry_user_message_id)

    async def test_history_restores_failed_assistant_once_with_response_retry(self) -> None:
        persistence = _Persistence()
        persistence.messages = (_user(), replace(
            _assistant(),
            body="I couldn't safely complete this response.",
            resolution_type=AgentResolutionType.PARTIAL,
            invocation_status=AgentInvocationStatus.FAILED,
            invocation_retry_count=0,
            invocation_user_message_id=USER,
        ))
        service = MarketplaceAgentV2Service(
            persistence, _Orchestrator(), provider_name="openai", model_name="test"
        )

        history = await service.list_messages(
            actor_user_id=ACTOR, session_id=SESSION
        )

        assistants = [item for item in history.data if item.role == "ASSISTANT"]
        self.assertEqual(1, len(assistants))
        self.assertTrue(assistants[0].retryable)
        self.assertEqual(USER, assistants[0].response_retry_user_message_id)
        self.assertEqual(
            "I couldn't safely complete this response.",
            assistants[0].message.content,
        )

    async def test_response_retry_reuses_committed_user_and_client_identity(self) -> None:
        persistence = _Persistence()
        orchestrator = _Orchestrator()
        service = MarketplaceAgentV2Service(
            persistence, orchestrator, provider_name="openai", model_name="test"
        )

        response = await service.retry_response(
            actor_user_id=ACTOR, session_id=SESSION, user_message_id=USER,
            client_message_id=CLIENT, correlation_id="v2-retry",
        )

        self.assertEqual(USER, response.user_message.id)
        self.assertEqual(1, persistence.conversation.completions)
        self.assertEqual(1, orchestrator.calls)


if __name__ == "__main__":
    unittest.main()
