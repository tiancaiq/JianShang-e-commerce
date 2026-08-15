from __future__ import annotations

import re
from collections.abc import Awaitable, Callable, Sequence
from decimal import Decimal

from .policy import MarketplaceAgentV2ToolPolicy
from .provider import MarketplaceAgentV2Model, TextDeltaCallback
from .schemas import (
    AgentContext,
    CollectListingInformationArguments,
    EvidenceReference,
    ListingAttachment,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2ContextualRefinement,
    MarketplaceAgentV2Refinement,
    MarketplaceAgentV2RefinementOption,
    MarketplaceAgentV2Message,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    OrchestrationResult,
    ToolActivity,
    ToolObservation,
    ToolProposal,
)
from .seller_workflow import extract_initial_item_type
from .scope import (
    MARKETPLACE_SCOPE_BOUNDARY_RESPONSE,
    MarketplaceScopeClassifier,
    hard_safety_response,
)
from .tools import ActivityCallback, MarketplaceAgentV2ToolRegistry


MAX_AGENT_STEPS = 5
ToolCompletedCallback = Callable[[ToolObservation], Awaitable[None]]


class MarketplaceAgentV2OrchestrationFailure(RuntimeError):
    """Carries only a stable failure category across the privacy boundary."""

    def __init__(self, kind: str) -> None:
        super().__init__(kind)
        self.kind = kind


class MarketplaceAgentV2Orchestrator:
    """Runs an isolated five-decision model-first loop over the strict registry."""

    def __init__(
        self,
        model: MarketplaceAgentV2Model,
        registry: MarketplaceAgentV2ToolRegistry,
        *,
        model_timeout_seconds: float = 10.0,
    ) -> None:
        self._model = model
        self._registry = registry
        self._model_timeout_seconds = model_timeout_seconds

    async def close(self) -> None:
        await self._model.close()

    async def run(
        self,
        *,
        actor_user_id: str,
        current_message: str,
        recent_messages: Sequence[tuple[str, str]],
        referenced_listings: Sequence[ListingAttachment],
        prior_observations: Sequence[ToolObservation] = (),
        pending_interaction: MarketplaceAgentV2PendingInteraction | None = None,
        active_workflow: MarketplaceAgentV2ActiveWorkflow | None = None,
        confirmed_interaction: MarketplaceAgentV2PendingInteraction | None = None,
        correlation_id: str,
        activity: ActivityCallback | None = None,
        tool_completed: ToolCompletedCallback | None = None,
        text_delta: TextDeltaCallback | None = None,
        scope_result: MarketplaceScopeResult | None = None,
    ) -> OrchestrationResult:
        selected_scope = scope_result or MarketplaceScopeClassifier().classify(
            current_message=current_message,
            recent_messages=recent_messages,
            referenced_listings=referenced_listings,
            pending_interaction=pending_interaction,
            preference_state={},
        )
        if selected_scope.scope == "UNSAFE":
            safety = hard_safety_response(current_message)
            content = (
                safety[1] if safety is not None
                else "I can't help with that request. I can help with safe marketplace questions."
            )
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        if selected_scope.scope == "OUT_OF_SCOPE":
            if text_delta is not None:
                await text_delta(MARKETPLACE_SCOPE_BOUNDARY_RESPONSE)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(
                    content=MARKETPLACE_SCOPE_BOUNDARY_RESPONSE
                ),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        if current_message.strip().casefold() in {"never mind", "nevermind"}:
            content = "Okay — I’ll stop here. No marketplace tool was run."
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        if (
            confirmed_interaction is not None
            and confirmed_interaction.status == "CANCELLED"
        ):
            content = "Okay — I won’t run that refined search."
            if text_delta is not None:
                await text_delta(content)
            return OrchestrationResult(
                message=MarketplaceAgentV2Message(content=content),
                decisionCount=0,
                scopeResult=selected_scope,
            )
        observations: list[ToolObservation] = list(prior_observations[-5:])
        turn_observations: list[ToolObservation] = []
        attachments: dict[str, ListingAttachment] = {}
        activities: list[ToolActivity] = []
        input_tokens = 0
        output_tokens = 0
        contextual_refinement = _contextual_refinement_selection(
            current_message, prior_observations
        )
        comparison_context = _is_comparison_request(
            current_message, referenced_listings
        )
        contextual_attachments = _contextual_response_attachments(
            current_message, referenced_listings
        )
        recommendation_context = bool(contextual_attachments) or comparison_context
        malformed_input = _looks_like_unintelligible_input(current_message)
        policy = MarketplaceAgentV2ToolPolicy(
            referenced_listing_ids=frozenset(item.listing_id for item in referenced_listings),
            prior_observations=tuple(prior_observations[-5:]),
            comparison_context=recommendation_context,
            confirmation_without_pending=(
                _confirmation_answer(current_message) is not None
                and confirmed_interaction is None
            ),
            message_scope=selected_scope.scope,
            required_grounding=selected_scope.required_grounding,
            active_workflow=active_workflow,
            seller_search_allowed=_explicit_seller_comparison_request(current_message),
            required_search_query=(
                contextual_refinement.search_query
                if contextual_refinement is not None else None
            ),
            required_search_category_name=(
                contextual_refinement.value
                if contextual_refinement is not None
                and contextual_refinement.facet == "CATEGORY"
                else None
            ),
        )
        context_messages = tuple(
            {"role": role, "content": content}
            for role, content in recent_messages[-12:]
        )
        schemas = self._registry.provider_schemas()
        if tuple(item["name"] for item in schemas) != self._registry.names:
            raise RuntimeError("Marketplace Agent V2 registry is inconsistent")

        new_pending: MarketplaceAgentV2PendingInteraction | None = None
        new_active_workflow = active_workflow
        if confirmed_interaction is not None:
            confirmed = ToolProposal(
                callId=confirmed_interaction.id,
                tool="search_listings",
                arguments=confirmed_interaction.arguments,
            )
            confirmed_arguments, rejection = policy.validate(confirmed, step=1)
            if rejection is not None:
                observations.append(rejection)
                turn_observations.append(rejection)
            else:
                observation = await self._registry.execute(
                    tool="search_listings", arguments=confirmed_arguments,
                    actor_user_id=actor_user_id, correlation_id=correlation_id,
                    activity=activity,
                )
                observations.append(observation)
                turn_observations.append(observation)
                policy.record(observation)
                if tool_completed is not None:
                    await tool_completed(observation)
                activities.append(ToolActivity(
                    tool="search_listings", status=observation.status,
                    reason=observation.reason, observedAt=observation.observed_at,
                ))
                for attachment in observation.attachments:
                    attachments[attachment.listing_id] = attachment

        for step in range(1, MAX_AGENT_STEPS + 1):
            current_search_executed = any(
                item.tool == "search_listings"
                and item.status in {"SUCCEEDED", "FAILED"}
                for item in turn_observations
            )
            current_search_results = any(
                item.tool == "search_listings" and item.attachments
                for item in turn_observations
            )
            context = AgentContext(
                currentMessage=current_message,
                recentMessages=context_messages,
                referencedListingIds=(
                    () if current_search_results else
                    tuple(item.listing_id for item in referenced_listings)
                ),
                referencedListings=(
                    () if current_search_results else tuple(referenced_listings)
                ),
                observations=tuple(
                    _customer_observation(item) for item in observations[-5:]
                ),
                pendingInteraction=(
                    new_pending or pending_interaction or confirmed_interaction
                ),
                activeWorkflow=new_active_workflow,
                contextualRefinement=contextual_refinement,
                scopeResult=selected_scope,
            )
            grounding_available = _has_required_grounding(
                selected_scope.required_grounding,
                referenced_listings=referenced_listings,
                current_attachments=tuple(attachments.values()),
                observations=tuple(turn_observations),
            )
            stream_provider_text = bool(
                text_delta is not None
                and not comparison_context
                and not malformed_input
                and grounding_available
                and (contextual_refinement is None or current_search_executed)
            )
            safe_stream = _CustomerTextStream(
                (
                    text_delta if stream_provider_text else None
                ),
                forbidden_ids=tuple(
                    {item.listing_id for item in referenced_listings}
                    | set(attachments)
                ),
                remove_display_confirmation=current_search_results,
                suppress_result_questions=current_search_results,
            )
            try:
                decision = await self._model.decide(
                    context=context,
                    # A Product search is the only inventory action for this turn.
                    # The following bounded decision can explain its observation,
                    # but cannot start a second differently-shaped search loop.
                    tools=() if current_search_executed else schemas,
                    correlation_id=correlation_id,
                    on_text_delta=safe_stream.push if text_delta is not None else None,
                    timeout_seconds=self._model_timeout_seconds,
                )
            except MarketplaceAgentV2OrchestrationFailure:
                raise
            except ValueError as error:
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_DECISION_INVALID"
                ) from error
            except Exception as error:
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_PROVIDER_UNAVAILABLE"
                ) from error
            input_tokens += decision.input_tokens
            output_tokens += decision.output_tokens
            if decision.content is not None:
                if contextual_refinement is not None and not current_search_executed:
                    # A Product-grounded facet selection advances the active search;
                    # prose alone would lose the user's committed refinement.
                    rejection = ToolObservation(
                        tool="DIRECT_RESPONSE",
                        status="REJECTED",
                        reason="GROUNDING_TOOL_REQUIRED",
                        normalizedQuery=contextual_refinement.search_query,
                        filterCategories=(contextual_refinement.facet,),
                    )
                    observations.append(rejection)
                    turn_observations.append(rejection)
                    continue
                if text_delta is not None:
                    if not safe_stream.raw_text:
                        await safe_stream.push(decision.content)
                    elif safe_stream.raw_text != decision.content:
                        raise MarketplaceAgentV2OrchestrationFailure(
                            "MODEL_DECISION_INVALID"
                        )
                    await safe_stream.finish()
                    content = safe_stream.text
                else:
                    content = _customer_safe_text(
                        decision.content,
                        tuple(
                            {item.listing_id for item in referenced_listings}
                            | set(attachments)
                        ),
                        remove_display_confirmation=current_search_results,
                        suppress_result_questions=current_search_results,
                    )
                if current_search_results and not content.strip():
                    content = _step_limit_content(
                        turn_observations,
                        tuple(attachments.values()),
                        required_grounding=selected_scope.required_grounding,
                    )
                    if text_delta is not None:
                        await text_delta(content)
                try:
                    _validate_grounding(
                        required_grounding=selected_scope.required_grounding,
                        current_message=current_message,
                        content=content,
                        referenced_listings=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                    )
                    _validate_terminal_response(
                        current_message=current_message,
                        content=content,
                        active_recommendations=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                        has_waiting_interaction=any(
                            item is not None and item.status == "WAITING"
                            for item in (new_pending, pending_interaction)
                        ),
                    )
                except MarketplaceAgentV2OrchestrationFailure as error:
                    if error.kind == "GROUNDING_REQUIRED":
                        rejection = ToolObservation(
                            tool="DIRECT_RESPONSE",
                            status="REJECTED",
                            reason="GROUNDING_REQUIRED",
                        )
                        observations.append(rejection)
                        turn_observations.append(rejection)
                        continue
                    if error.kind != "MODEL_RESPONSE_UNSUPPORTED":
                        raise
                    if recommendation_context:
                        content = _grounded_recommendation_content(
                            current_message, referenced_listings
                        )
                    elif malformed_input:
                        content = (
                            "I didn't understand that. "
                            "What marketplace item or question can I help with?"
                        )
                    else:
                        raise
                    _validate_terminal_response(
                        current_message=current_message,
                        content=content,
                        active_recommendations=tuple(referenced_listings),
                        current_attachments=tuple(attachments.values()),
                        observations=tuple(turn_observations),
                        has_waiting_interaction=any(
                            item is not None and item.status == "WAITING"
                            for item in (new_pending, pending_interaction)
                        ),
                    )
                if text_delta is not None and not stream_provider_text:
                    await text_delta(content)
                response_attachments = (
                    tuple(attachments.values()) or contextual_attachments
                )
                evidence = _evidence_references(
                    referenced_listings=tuple(referenced_listings),
                    current_attachments=response_attachments,
                )
                effective_pending = _effective_workflow_pending(
                    new_pending, pending_interaction, new_active_workflow
                )
                return OrchestrationResult(
                    message=MarketplaceAgentV2Message(
                        content=_safe_content(content),
                        attachments=response_attachments,
                        refinement=_refinement(turn_observations),
                        pendingInteraction=effective_pending,
                        toolActivity=tuple(activities),
                        inputTokens=input_tokens,
                        outputTokens=output_tokens,
                    ),
                    decisionCount=step,
                    observations=tuple(turn_observations[-5:]),
                    pendingInteraction=effective_pending,
                    activeWorkflow=new_active_workflow,
                    evidence=evidence,
                    scopeResult=selected_scope,
                )
            proposal = decision.tool_proposal
            assert proposal is not None
            arguments, rejection = policy.validate(proposal, step=step)
            if rejection is not None:
                observations.append(rejection)
                turn_observations.append(rejection)
                if proposal.tool in self._registry.names and proposal.tool not in {
                    "request_confirmation", "collect_listing_information",
                }:
                    activities.append(ToolActivity(
                        tool=proposal.tool,
                        status=rejection.status,
                        reason=rejection.reason,
                        observedAt=rejection.observed_at,
                    ))
                continue
            if (
                proposal.tool == "collect_listing_information"
                and isinstance(arguments, CollectListingInformationArguments)
                and arguments.item_type is None
            ):
                # The model selected seller collection; preserve an explicit object
                # already present in that request so the workflow cannot ask it again.
                extracted_item_type = extract_initial_item_type(current_message)
                if extracted_item_type is not None:
                    arguments = arguments.model_copy(update={
                        "item_type": extracted_item_type
                    })
            observation = await self._registry.execute(
                tool=proposal.tool,
                arguments=arguments,
                actor_user_id=actor_user_id,
                correlation_id=correlation_id,
                activity=activity,
            )
            observations.append(observation)
            turn_observations.append(observation)
            policy.record(observation)
            if tool_completed is not None and proposal.tool not in {
                "request_confirmation", "collect_listing_information",
            }:
                await tool_completed(observation)
            if proposal.tool not in {
                "request_confirmation", "collect_listing_information",
            }:
                activities.append(ToolActivity(
                    tool=proposal.tool,
                    status=observation.status,
                    reason=observation.reason,
                    observedAt=observation.observed_at,
                ))
            for attachment in observation.attachments:
                attachments[attachment.listing_id] = attachment
            if observation.pending_interaction is not None:
                new_pending = observation.pending_interaction
            if observation.active_workflow is not None:
                new_active_workflow = observation.active_workflow

        fallback = _step_limit_content(
            turn_observations,
            tuple(attachments.values()),
            required_grounding=selected_scope.required_grounding,
        )
        if text_delta is not None:
            await text_delta(fallback)
        effective_pending = _effective_workflow_pending(
            new_pending, pending_interaction, new_active_workflow
        )
        return OrchestrationResult(
            message=MarketplaceAgentV2Message(
                content=fallback,
                attachments=tuple(attachments.values()),
                refinement=_refinement(turn_observations),
                pendingInteraction=effective_pending,
                toolActivity=tuple(activities),
                inputTokens=input_tokens,
                outputTokens=output_tokens,
            ),
            decisionCount=MAX_AGENT_STEPS,
            observations=tuple(turn_observations[-5:]),
            pendingInteraction=effective_pending,
            activeWorkflow=new_active_workflow,
            evidence=_evidence_references(
                referenced_listings=tuple(referenced_listings),
                current_attachments=tuple(attachments.values()),
            ),
            scopeResult=selected_scope,
        )


def _explicit_seller_comparison_request(value: str) -> bool:
    """Allows inventory only when a seller explicitly asks for market comparison."""

    normalized = " ".join(value.casefold().split())
    return bool(re.search(
        r"\b(?:show|find|search|compare|check)\b.{0,40}\b(?:similar|comparable)\b|"
        r"\b(?:what price|how much)\b.{0,40}\b(?:similar|listings?|market)\b|"
        r"\bwhat price should i (?:use|set|ask)\b|"
        r"\bhow much should i (?:list|sell|ask)\b",
        normalized,
    ))


def _effective_workflow_pending(
    new_pending: MarketplaceAgentV2PendingInteraction | None,
    current_pending: MarketplaceAgentV2PendingInteraction | None,
    workflow: MarketplaceAgentV2ActiveWorkflow | None,
) -> MarketplaceAgentV2PendingInteraction | None:
    """Keeps a seller question active across an explicit comparison side trip."""

    if new_pending is not None:
        return new_pending
    if (
        workflow is not None
        and workflow.type == "CREATE_LISTING"
        and workflow.status == "COLLECTING_INFORMATION"
        and current_pending is not None
        and current_pending.type == "ANSWER_FIELD"
        and current_pending.status == "WAITING"
    ):
        return current_pending
    return None


def _safe_content(value: str) -> str:
    # Validate without normalizing: persisted terminal content must exactly match
    # the bytes already emitted by the provider-backed SSE stream.
    if not value.strip() or len(value) > 12_000:
        raise ValueError("Invalid Marketplace Agent V2 assistant content")
    lowered = value.casefold()
    if "chain-of-thought" in lowered or "system prompt" in lowered:
        raise ValueError("Unsafe Marketplace Agent V2 assistant content")
    return value


def _looks_like_unintelligible_input(value: str) -> bool:
    """Recognize only obvious keyboard-noise input for one safe post-model fallback."""

    normalized = " ".join(value.casefold().split())
    return bool(re.search(r"\b(?:asdf(?:gh)?|qwerty|zxcv(?:bnm)?|hjkl)\b", normalized))


_CUSTOMER_REPLACEMENTS = (
    ("provisional preview", "current matches"),
    ("low-confidence result set", "closest current matches"),
    ("facets from current inventory", "ways to narrow these results"),
    ("retrieval confidence", "match quality"),
    ("seller contact/purchase page", "listing page"),
    ("purchase page", "listing page"),
)

_DISPLAY_CONFIRMATION_REPLACEMENTS = (
    # Cards are already attached to this answer, so a display-permission question
    # is removed rather than creating another customer turn or pending action.
    (" Would you like to view these results?", ""),
    (" Would you like to view the results?", ""),
    (" Would you like me to show these results?", ""),
)

_BLOCKED_STREAM_ACTION_PHRASES = (
    "opening the photo gallery",
    "opening a photo gallery",
    "starting a purchase",
    "starting the purchase",
)


class _CustomerTextStream:
    """Redacts bounded private/internal terms before any model text reaches SSE."""

    def __init__(
        self,
        callback: TextDeltaCallback | None,
        *,
        forbidden_ids: Sequence[str],
        remove_display_confirmation: bool = False,
        suppress_result_questions: bool = False,
    ) -> None:
        self._callback = callback
        self._replacements = (
            _CUSTOMER_REPLACEMENTS
            + (_DISPLAY_CONFIRMATION_REPLACEMENTS if remove_display_confirmation else ())
            + tuple(
            (listing_id, "this listing") for listing_id in forbidden_ids
            )
        )
        self._blocked_phrases = _BLOCKED_STREAM_ACTION_PHRASES
        self._suppress_result_questions = suppress_result_questions
        self._pending = ""
        self._sentence_pending = ""
        self._raw: list[str] = []
        self._safe: list[str] = []

    @property
    def raw_text(self) -> str:
        return "".join(self._raw)

    @property
    def text(self) -> str:
        return "".join(self._safe)

    async def push(self, delta: str) -> None:
        self._raw.append(delta)
        self._pending += delta
        await self._drain(final=False)

    async def finish(self) -> None:
        await self._drain(final=True)
        await self._drain_sentences(final=True)

    async def _drain(self, *, final: bool) -> None:
        while self._pending:
            lowered = self._pending.casefold()
            if any(
                phrase.casefold() in lowered for phrase in self._blocked_phrases
            ):
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_RESPONSE_UNSUPPORTED"
                )
            matches = [
                (lowered.find(needle.casefold()), needle, replacement)
                for needle, replacement in self._replacements
                if lowered.find(needle.casefold()) >= 0
            ]
            if matches:
                index, needle, replacement = min(matches, key=lambda item: item[0])
                await self._emit(self._pending[:index])
                await self._emit(replacement)
                self._pending = self._pending[index + len(needle):]
                continue
            if final:
                await self._emit(self._pending)
                self._pending = ""
                return
            retained = max(
                (
                    size
                    for needle in (
                        tuple(item[0] for item in self._replacements)
                        + self._blocked_phrases
                    )
                    for size in range(1, min(len(needle), len(self._pending) + 1))
                    if lowered.endswith(needle.casefold()[:size])
                ),
                default=0,
            )
            safe_length = len(self._pending) - retained
            if safe_length == 0:
                return
            await self._emit(self._pending[:safe_length])
            self._pending = self._pending[safe_length:]

    async def _emit(self, value: str) -> None:
        if not value:
            return
        if self._suppress_result_questions:
            self._sentence_pending += value
            await self._drain_sentences(final=False)
            return
        await self._emit_safe(value)

    async def _drain_sentences(self, *, final: bool) -> None:
        """Streams declarative result prose while retaining questions for removal."""

        while self._sentence_pending:
            boundary = _sentence_boundary(self._sentence_pending, final=final)
            if boundary is None:
                return
            sentence = self._sentence_pending[:boundary]
            self._sentence_pending = self._sentence_pending[boundary:]
            if "?" not in sentence:
                await self._emit_safe(sentence)

    async def _emit_safe(self, value: str) -> None:
        if not value:
            return
        self._safe.append(value)
        if self._callback is not None:
            await self._callback(value)


def _sentence_boundary(value: str, *, final: bool) -> int | None:
    """Find a completed sentence without treating decimal points as boundaries."""

    for index, character in enumerate(value):
        if character not in ".!?":
            continue
        if index + 1 < len(value) and value[index + 1].isspace():
            return index + 1
        if final and index + 1 == len(value):
            return index + 1
    if final:
        return len(value)
    return None


def _customer_safe_text(
    value: str,
    forbidden_ids: Sequence[str],
    *,
    remove_display_confirmation: bool = False,
    suppress_result_questions: bool = False,
) -> str:
    safe = value
    replacements = (
        _CUSTOMER_REPLACEMENTS
        + (_DISPLAY_CONFIRMATION_REPLACEMENTS if remove_display_confirmation else ())
        + tuple((listing_id, "this listing") for listing_id in forbidden_ids)
    )
    for needle, replacement in replacements:
        start = safe.casefold().find(needle.casefold())
        while start >= 0:
            safe = safe[:start] + replacement + safe[start + len(needle):]
            start = safe.casefold().find(needle.casefold(), start + len(replacement))
    if suppress_result_questions:
        safe = _without_question_sentences(safe)
    return safe


def _without_question_sentences(value: str) -> str:
    """Keep result introductions declarative; structured actions render after cards."""

    result: list[str] = []
    pending = value
    while pending:
        boundary = _sentence_boundary(pending, final=True)
        assert boundary is not None
        sentence = pending[:boundary]
        pending = pending[boundary:]
        if "?" not in sentence:
            result.append(sentence)
    return "".join(result).strip()


def _customer_observation(observation: ToolObservation) -> ToolObservation:
    """Keeps authoritative counts but withholds card facts and internal ranking metadata."""

    if observation.tool != "search_listings":
        return observation
    return observation.model_copy(update={
        "reason": (
            "RESULTS_AVAILABLE" if observation.attachments else observation.reason
        ),
        "retrieval_confidence": None,
        "presentation_hint": None,
        "facets": None,
        "attachments": (),
    })


def _contextual_refinement_selection(
    current_message: str,
    prior_observations: Sequence[ToolObservation],
) -> MarketplaceAgentV2ContextualRefinement | None:
    """Resolve an exact short reply only against the latest Product-owned facets."""

    selected = " ".join(current_message.casefold().strip().rstrip(".!?").split())
    if not selected:
        return None
    for observation in reversed(prior_observations):
        if observation.tool != "search_listings":
            continue
        if (
            observation.status != "SUCCEEDED"
            or not observation.attachments
            or observation.normalized_query is None
        ):
            return None
        choices: list[tuple[str, str]] = []
        if observation.facets is not None:
            choices.extend(
                ("SUBTYPE", option.value)
                for option in observation.facets.subtype
            )
        # Product V4 may omit subtype facets while each revalidated attachment
        # still carries its authoritative public category. An exact short reply
        # may select that category, but no unobserved category is inferred.
        choices.extend(
            ("CATEGORY", attachment.category_name)
            for attachment in observation.attachments
        )
        seen: set[tuple[str, str]] = set()
        for facet, value in choices:
            normalized_option = " ".join(value.casefold().split())
            identity = (facet, normalized_option)
            if identity in seen:
                continue
            seen.add(identity)
            if selected == normalized_option:
                return MarketplaceAgentV2ContextualRefinement(
                    facet=facet,
                    value=value,
                    activeQuery=observation.normalized_query,
                    searchQuery=(
                        observation.normalized_query
                        if facet == "CATEGORY"
                        else f"{observation.normalized_query} {value}"
                    )[:200],
                )
        return None
    return None


def _refinement(
    observations: Sequence[ToolObservation],
) -> MarketplaceAgentV2Refinement | None:
    """Builds prose-free suggested actions from current Product-owned facet facts."""

    observation = next(
        (
            item
            for item in reversed(observations)
            if item.tool == "search_listings"
            and item.status == "SUCCEEDED"
            and item.attachments
            and item.facets is not None
        ),
        None,
    )
    if observation is None or observation.facets is None:
        return None
    options: list[MarketplaceAgentV2RefinementOption] = []
    exact = observation.exact_match_count or 0
    related = observation.related_match_count or 0
    if exact > 0 and related > 0:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="MATCH_SCOPE", value="Exact matches only", count=exact
        ))
    prices = tuple(
        item.price_amount for item in observation.attachments
        if item.currency == "USD"
    )
    for threshold in (Decimal("20"), Decimal("50"), Decimal("100"), Decimal("200")):
        if len(prices) != len(observation.attachments):
            break
        count = sum(price < threshold for price in prices)
        if 0 < count < len(prices):
            options.append(MarketplaceAgentV2RefinementOption(
                facet="MAXIMUM_PRICE",
                value=f"Under ${threshold:.0f}",
                count=count,
            ))
            break
    new_condition = next(
        (item for item in observation.facets.condition if item.value.casefold() == "new"),
        None,
    )
    if new_condition is not None:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="CONDITION", value="New condition", count=new_condition.count
        ))
    location = next(iter(observation.facets.location), None)
    if location is not None:
        options.append(MarketplaceAgentV2RefinementOption(
            facet="LOCATION", value=f"Near {location.value}", count=location.count
        ))
    options = options[:4]
    if len(options) < 2:
        return None
    return MarketplaceAgentV2Refinement(
        options=tuple(options),
    )


_ORDINALS = {
    "first": 1, "second": 2, "third": 3, "fourth": 4,
    "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8,
}


def _confirmation_answer(value: str) -> bool | None:
    normalized = value.strip().casefold().rstrip(".!?")
    if normalized in {"yes", "y", "yes please", "sure", "okay", "ok"}:
        return True
    if normalized in {"no", "n", "no thanks", "no thank you"}:
        return False
    return None


def _is_comparison_request(
    value: str,
    recommendations: Sequence[ListingAttachment],
) -> bool:
    """Recognize only comparison language to constrain tools, never to choose prose."""

    if not recommendations:
        return False
    normalized = " ".join(value.casefold().split())
    return bool(re.search(
        r"\b(compare|comparison|cheaper|cheapest|best value|best overall|"
        r"which one.{0,24}best|first\s+(?:versus|vs\.?|and)\s+second|"
        r"(?:what about\s+)?(?:the\s+)?(?:first|second|third|fourth|fifth)\s+one)\b",
        normalized,
    ))


def _contextual_response_attachments(
    value: str,
    recommendations: Sequence[ListingAttachment],
) -> tuple[ListingAttachment, ...]:
    """Attach the active card selected by a grounded ordinal or price follow-up."""

    if not recommendations:
        return ()
    normalized = " ".join(value.casefold().split())
    if re.search(r"\b(?:cheaper|cheapest)\b", normalized):
        return (min(recommendations, key=lambda item: item.price_amount),)
    if "compare" in normalized or "comparison" in normalized:
        return ()
    mentioned = [
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", normalized)
    ]
    if len(mentioned) != 1 or mentioned[0] > len(recommendations):
        return ()
    return (recommendations[mentioned[0] - 1],)


def _mentioned_active_titles(
    content: str,
    recommendations: Sequence[ListingAttachment],
) -> set[str]:
    """Resolve only unambiguous active-card title prefixes used in model prose."""

    content_words = tuple(re.findall(r"[^\W_]+", content.casefold()))
    searchable_content = f" {' '.join(content_words)} "
    title_words = {
        item.listing_id: tuple(re.findall(r"[^\W_]+", item.title.casefold()))
        for item in recommendations
    }
    mentioned: set[str] = set()
    for item in recommendations:
        words = title_words[item.listing_id]
        full_title = " ".join(words)
        if full_title and f" {full_title} " in searchable_content:
            mentioned.add(item.listing_id)
            continue
        # Customer-facing prose may omit card suffixes; accept a three-or-more-word
        # prefix only when that prefix identifies exactly one active recommendation.
        for length in range(min(len(words), 8), 2, -1):
            prefix_words = words[:length]
            if sum(
                candidate[:length] == prefix_words
                for candidate in title_words.values()
            ) != 1:
                continue
            prefix = " ".join(prefix_words)
            if f" {prefix} " in searchable_content:
                mentioned.add(item.listing_id)
                break
    return mentioned


def _has_explicit_best_selection(content: str) -> bool:
    """Require a present-tense winner instead of another preference interrogation."""

    normalized = " ".join(content.casefold().split())
    if re.search(r"\b(best overall|my pick|i recommend|my recommendation)\b", normalized):
        return True
    subject = r"(?:first|second|third|fourth|fifth|[a-z0-9][a-z0-9 -]{2,80})"
    return bool(re.search(
        rf"\b{subject}\b.{{0,60}}\b(?:is|as)\s+(?:the\s+)?(?:best(?: value)?|pick)\b",
        normalized,
    ))


def _grounded_recommendation_content(
    request: str,
    recommendations: Sequence[ListingAttachment],
) -> str:
    """Compose a bounded selection/comparison from ordered validated public facts."""

    if not recommendations:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    lowered = request.casefold()
    if "cheaper" in lowered or "cheapest" in lowered or "best" in lowered:
        if len(recommendations) < 2:
            raise MarketplaceAgentV2OrchestrationFailure(
                "MODEL_RESPONSE_UNSUPPORTED"
            )
        index = min(
            range(len(recommendations)),
            key=lambda item: recommendations[item].price_amount,
        )
        listing = recommendations[index]
        ordinal = tuple(_ORDINALS)[index] if index < len(_ORDINALS) else str(index + 1)
        return (
            f"The {ordinal} listing, {listing.title}, is the best value because it "
            f"is the lowest-priced current option at {_customer_price(listing)}. "
            f"It is listed in "
            f"{_customer_condition(listing.condition)} condition"
            f"{_customer_location_suffix(listing)}."
        )
    mentioned = [
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", lowered)
    ]
    if "compare" not in lowered and len(mentioned) == 1:
        ordinal = mentioned[0]
        if ordinal > len(recommendations):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
        listing = recommendations[ordinal - 1]
        word = tuple(_ORDINALS)[ordinal - 1]
        return (
            f"The {word} listing is {listing.title} at {_customer_price(listing)}. "
            f"It is listed in {_customer_condition(listing.condition)} condition"
            f"{_customer_location_suffix(listing)}."
        )
    if len(recommendations) < 2:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    first, second = recommendations[:2]
    return (
        f"The first listing, {first.title}, is {_customer_price(first)} and is listed "
        f"in {_customer_condition(first.condition)} condition"
        f"{_customer_location_suffix(first)}. "
        f"The second listing, {second.title}, is {_customer_price(second)} and is "
        f"listed in {_customer_condition(second.condition)} condition"
        f"{_customer_location_suffix(second)}."
    )


def _customer_price(listing: ListingAttachment) -> str:
    amount = f"{listing.price_amount:.2f}"
    return f"${amount}" if listing.currency == "USD" else f"{amount} {listing.currency}"


def _customer_condition(value: str) -> str:
    labels = {
        "NEW": "New",
        "OPEN_BOX": "Open Box",
        "LIKE_NEW": "Like New",
        "GOOD": "Good",
        "FAIR": "Fair",
        "FOR_PARTS": "For Parts",
    }
    return labels.get(value, value.replace("_", " ").title())


def _customer_location_suffix(listing: ListingAttachment) -> str:
    location = listing.public_city or listing.public_region
    return "" if location is None else f" in {location}"


def _active_title_position(
    content: str,
    listing: ListingAttachment,
) -> int | None:
    """Find a customer-visible title or stable title prefix for order validation."""

    searchable = " ".join(re.findall(r"[^\W_]+", content.casefold()))
    title_words = tuple(re.findall(r"[^\W_]+", listing.title.casefold()))
    for length in range(len(title_words), 2, -1):
        position = searchable.find(" ".join(title_words[:length]))
        if position >= 0:
            return position
    return None


def _has_required_grounding(
    required_grounding: str,
    *,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
) -> bool:
    """Confirm required facts came from an approved observation, never model memory."""

    if required_grounding == "NONE":
        return True
    if required_grounding == "LISTING_DATA":
        return bool(referenced_listings or current_attachments) or any(
            item.status == "SUCCEEDED"
            and item.tool in {"check_availability", "search_listings", "get_listing"}
            for item in observations
        )
    # V2 currently has no runtime policy-document or actor-private tool. These
    # requirements remain false until an executable registry capability supplies
    # an allowlisted observation carrying that evidence.
    return False


def _is_focused_clarification(content: str) -> bool:
    normalized = " ".join(content.casefold().split())
    return bool(
        len(content) <= 400
        and content.count("?") == 1
        and re.match(
            r"^(?:do you mean|which|what|could you|can you clarify|"
            r"are you looking|would you|please clarify)",
            normalized,
        )
    )


def _is_safe_grounding_abstention(required_grounding: str, content: str) -> bool:
    normalized = " ".join(content.casefold().split())
    if required_grounding == "KNOWLEDGE_RAG":
        return normalized == (
            "i couldn't find an official marketplace document that answers that clearly."
        )
    if required_grounding == "PRIVATE_TOOL":
        return normalized == (
            "i can't verify that account-specific status here. please use the relevant "
            "account page or contact marketplace support."
        )
    if required_grounding == "LISTING_DATA":
        return _is_focused_clarification(content)
    return False


def _validate_grounding(
    *,
    required_grounding: str,
    current_message: str,
    content: str,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
) -> None:
    """Reject unsupported terminal prose before any ungrounded bytes reach SSE."""

    if _looks_like_unintelligible_input(current_message) or _has_required_grounding(
        required_grounding,
        referenced_listings=referenced_listings,
        current_attachments=current_attachments,
        observations=observations,
    ) or _is_safe_grounding_abstention(required_grounding, content):
        return
    raise MarketplaceAgentV2OrchestrationFailure("GROUNDING_REQUIRED")


def _evidence_references(
    *,
    referenced_listings: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
) -> tuple[EvidenceReference, ...]:
    """Record internal listing evidence while keeping identifiers out of SSE text."""

    result: list[EvidenceReference] = []
    seen: set[tuple[str, str]] = set()
    for source_type, listings in (
        ("LISTING", current_attachments),
        ("EXISTING_OBSERVATION", referenced_listings),
    ):
        for listing in listings:
            identity = (source_type, listing.listing_id)
            if identity in seen:
                continue
            seen.add(identity)
            result.append(EvidenceReference(
                sourceType=source_type,
                sourceId=listing.listing_id,
                version=listing.response_hash,
                retrievedAt=listing.checked_at,
            ))
    return tuple(result[:20])


def _validate_terminal_response(
    *,
    current_message: str,
    content: str,
    active_recommendations: Sequence[ListingAttachment],
    current_attachments: Sequence[ListingAttachment],
    observations: Sequence[ToolObservation],
    has_waiting_interaction: bool,
) -> None:
    """Reject unsupported comparison/result claims before canonical persistence."""

    lowered = content.casefold()
    if re.search(
        r"\b(?:open_box|like_new|for_parts)\b|"
        r"\bmatch\s*:\s*(?:exact|related)\b|\bmatch_quality\b",
        lowered,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    question_limit = 2 if _is_unambiguous_greeting(current_message) else 1
    if (
        content.count("?") > question_limit
        or "\nrefine:" in lowered
        or lowered.startswith("refine:")
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if current_attachments and "?" in content:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if not has_waiting_interaction and re.search(
        r"\byes\s*(?:/|or)\s*no\b", lowered
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if _claims_or_offers_unavailable_action(lowered):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    evidence = tuple(current_attachments) or tuple(active_recommendations)
    for item in evidence:
        identifier_prefix = item.listing_id.casefold()[:6]
        if identifier_prefix and re.search(
            rf"\b{re.escape(identifier_prefix)}[0-9a-z]*\b", lowered
        ):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if evidence:
        for word, ordinal in _ORDINALS.items():
            if re.search(rf"\b{word}\b", lowered) and ordinal > len(evidence):
                raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    exact_claim = re.search(r"\b(\d{1,2})\s+exact\s+match(?:es)?\b", lowered)
    if exact_claim is not None:
        supported = next(
            (item.exact_match_count for item in reversed(observations)
             if item.exact_match_count is not None),
            sum(item.match_quality == "EXACT" for item in evidence),
        )
        if int(exact_claim.group(1)) != supported:
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    # Result-presentation claims require cards on this response, while ordinary
    # comparison prose such as "Here are the differences" may reuse prior cards.
    presents_results = bool(
        re.search(r"\bi found\b", lowered)
        or re.search(
            r"\bhere are\s+(?:the\s+)?(?:(?:\d+|some|current|closest|top)\s+)?"
            r"(?:listings?|matches?|results?)\b",
            lowered,
        )
        or re.search(
            r"\bshowing\b.{0,80}\b(?:listings?|matches?|results?)\b",
            lowered,
        )
        or re.search(r"\b(?:closest\s+)?matches\s+are\s+shown\b", lowered)
    )
    if presents_results and not current_attachments:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if (
        _confirmation_answer(current_message) is True
        and any(
            item.tool == "search_listings" and item.status == "SUCCEEDED"
            for item in observations
        )
        and (
            "yes/no" in lowered
            or re.search(r"\b(?:run|start)\s+(?:that|the|this)?\s*search\b", lowered)
        )
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if not _is_comparison_request(current_message, active_recommendations):
        return
    inference_text = lowered.replace("match quality", "")
    if re.search(
        r"\b(quality|reliability|reliable|longevity|durability|durable|likely|"
        r"implies?|suggests?|possibly|may|might|could|portable|simpler|premium|appearance|"
        r"aesthetic|sleek(?:er|est)?|larger|bigger|smaller|higher?[- ]?end|"
        r"feature(?:d|ful)?|"
        r"hidden wear|expected wear|"
        r"build quality)\b",
        inference_text,
    ):
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    mentioned_titles = _mentioned_active_titles(content, active_recommendations)
    mentioned_ordinals = {
        ordinal for word, ordinal in _ORDINALS.items()
        if re.search(rf"\b{word}\b", lowered)
    }
    if not mentioned_titles and not mentioned_ordinals:
        raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    request = current_message.casefold()
    if "compare" in request or re.search(r"\bfirst\s+(?:versus|vs\.?|and)\s+second\b", request):
        if not ({1, 2} <= mentioned_ordinals or len(mentioned_titles) >= 2):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
        if len(active_recommendations) >= 2:
            first_position = _active_title_position(
                content, active_recommendations[0]
            )
            second_position = _active_title_position(
                content, active_recommendations[1]
            )
            if (
                first_position is not None
                and second_position is not None
                and first_position > second_position
            ):
                raise MarketplaceAgentV2OrchestrationFailure(
                    "MODEL_RESPONSE_UNSUPPORTED"
                )
    if "best" in request or "cheaper" in request or "cheapest" in request:
        supported_criteria = (
            "price", "cost", "condition", "new", "like new", "match", "location",
        )
        if not any(item in lowered for item in supported_criteria):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if ("which one" in request and "best" in request) or "best overall" in request:
        if not _has_explicit_best_selection(content):
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")
    if "cheaper" in request or "cheapest" in request:
        cheapest_index = min(
            range(len(active_recommendations)),
            key=lambda index: active_recommendations[index].price_amount,
        ) + 1
        cheapest = active_recommendations[cheapest_index - 1]
        if cheapest.listing_id not in mentioned_titles and cheapest_index not in mentioned_ordinals:
            raise MarketplaceAgentV2OrchestrationFailure("MODEL_RESPONSE_UNSUPPORTED")


def _claims_or_offers_unavailable_action(lowered: str) -> bool:
    """Blocks promises for capabilities that are absent from the V2 registry."""

    patterns = (
        r"(?:^|[.!?]\s*)(?:opening|opened)\s+(?:the|a)\s+photo gallery\b",
        r"\b(?:i(?:'m| am)|we(?:'re| are))\s+opening\s+(?:the|a)\s+photo gallery\b",
        r"\b(?:i(?:'m| am)|we(?:'re| are))\s+starting\s+(?:a|the)\s+purchase\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:start|complete|make)\s+(?:a|the)\s+purchase\b",
        r"\b(?:would you like me to|i can|we can|shall i|let me)\s+"
        r"(?:show|retrieve|access)\b.{0,80}\bseller(?:'s|’s)?\b.{0,80}"
        r"\b(?:pickup|payment)\b.{0,40}\binstructions\b",
    )
    return any(re.search(pattern, lowered) for pattern in patterns)


def _is_unambiguous_greeting(value: str) -> bool:
    """Recognize only a greeting so common provider prose shapes are not rejected."""

    normalized = " ".join(value.casefold().strip().rstrip(".!?").split())
    return normalized in {
        "hi", "hello", "hey", "good morning", "good afternoon", "good evening",
    }


def _step_limit_content(
    observations: Sequence[ToolObservation],
    attachments: Sequence[ListingAttachment] = (),
    *,
    required_grounding: str = "NONE",
) -> str:
    """Complete a bounded turn from current authoritative tool facts at step five."""

    result = next(
        (
            item for item in reversed(observations)
            if item.tool == "search_listings"
            and item.status == "SUCCEEDED"
            and item.reason == "RESULTS_AVAILABLE"
            and item.attachments
        ),
        None,
    )
    verified = tuple(attachments) or (() if result is None else result.attachments)
    if result is not None and verified:
        count = len(verified)
        filters = _customer_filter_summary(result.filter_categories)
        scope = "your request" if not filters else f"your {filters} filters"
        noun = "listing" if count == 1 else "listings"
        exact = result.exact_match_count or 0
        related = result.related_match_count or 0
        fit = (
            " The closest matches are shown first, followed by related alternatives."
            if exact > 0 and related > 0
            else " The verified option is shown below."
            if count == 1
            else " The verified options are shown below."
        )
        return f"I found {count} current {noun} matching {scope}.{fit}"
    unavailable = next(
        (item for item in reversed(observations) if item.reason == "CATEGORY_UNAVAILABLE"),
        None,
    )
    if unavailable is not None:
        category = unavailable.normalized_query or "that category"
        return f'I checked current availability for "{category}" and found no active listings.'
    if any(item.reason == "FILTERS_TOO_STRICT" for item in observations):
        return "I did not find a verified current match in this search. You can adjust the request or ask me to check again."
    if any(item.status == "FAILED" for item in observations):
        return "I could not complete the marketplace check because the service is temporarily unavailable. Please try again."
    if required_grounding == "KNOWLEDGE_RAG":
        return "I couldn't find an official marketplace document that answers that clearly."
    if required_grounding == "PRIVATE_TOOL":
        return (
            "I can't verify that account-specific status here. Please use the relevant "
            "account page or contact marketplace support."
        )
    if required_grounding == "LISTING_DATA" and any(
        item.reason == "GROUNDING_REQUIRED" for item in observations
    ):
        return "What marketplace listing or product would you like me to check?"
    return "I could not complete that request within this turn. Please ask a more focused marketplace question."


def _customer_filter_summary(categories: Sequence[str]) -> str:
    """Translate allowlisted filter categories without exposing raw tool arguments."""

    labels: list[str] = []
    for category in categories:
        label = {
            "CATEGORY": "category",
            "CONDITION": "condition",
            "MINIMUM_PRICE": "price",
            "MAXIMUM_PRICE": "price",
            "CITY": "location",
            "COUNTY": "location",
        }.get(category)
        if label is not None and label not in labels:
            labels.append(label)
    if len(labels) < 2:
        return "" if not labels else labels[0]
    if len(labels) == 2:
        return f"{labels[0]} and {labels[1]}"
    return f"{', '.join(labels[:-1])}, and {labels[-1]}"
