from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Sequence

from msb_agent_service.agent_persistence import (
    AgentMessage,
    AgentMessageRole,
    MessageCursor,
)

from .schemas import (
    ActiveSkill,
    AgentContext,
    ExecutedSearchSnapshot,
    ListingAttachment,
    MarketplaceAgentV2ActiveWorkflow,
    MarketplaceAgentV2ContextualRefinement,
    MarketplaceAgentV2PendingInteraction,
    MarketplaceScopeResult,
    SkillSummary,
    ToolObservation,
)


CONTEXT_SCHEMA_VERSION = "ctx-01-v2"


@dataclass(frozen=True)
class ContextIdentity:
    actor_user_id: str
    session_id: str
    invocation_id: str
    current_user_message_id: str
    before: MessageCursor


@dataclass(frozen=True)
class MarketplaceTurnContext:
    """Actor-owned, selected V2 context sources for one accepted turn."""

    identity: ContextIdentity | None
    current_message: str
    source_message_ids: tuple[str, ...]
    recent_messages: tuple[tuple[str, str], ...]
    referenced_listings: tuple[ListingAttachment, ...]
    result_source_message_id: str | None
    prior_observations: tuple[ToolObservation, ...]
    latest_search: ExecutedSearchSnapshot | None
    pending_interaction: MarketplaceAgentV2PendingInteraction | None
    active_workflow: MarketplaceAgentV2ActiveWorkflow | None
    considered_message_count: int = 0
    excluded_out_of_scope_count: int = 0


@dataclass(frozen=True)
class ContextTrace:
    """Ephemeral selection metadata; never serialize or log with model context."""

    schema_version: str
    source_message_ids: tuple[str, ...]
    considered_message_count: int
    selected_dialogue_count: int
    excluded_out_of_scope_count: int
    result_source_message_id: str | None
    selected_listing_count: int
    prior_observation_count: int
    decision_observation_count: int
    pending_included: bool
    workflow_included: bool
    scope: str
    eligible_skill_names: tuple[str, ...]
    active_skill_name: str | None
    active_skill_version: int | None
    exposed_tool_names: tuple[str, ...]
    selection_reasons: tuple[str, ...]


@dataclass(frozen=True)
class MarketplaceContextPacket:
    """One decision's internal selection, projected only through AgentContext."""

    schema_version: str
    turn: MarketplaceTurnContext
    scope_result: MarketplaceScopeResult
    effective_pending_interaction: MarketplaceAgentV2PendingInteraction | None
    effective_active_workflow: MarketplaceAgentV2ActiveWorkflow | None
    decision_observations: tuple[ToolObservation, ...]
    contextual_refinement: MarketplaceAgentV2ContextualRefinement | None
    available_skills: tuple[SkillSummary, ...]
    active_skill: ActiveSkill | None
    suppress_prior_listings: bool
    commerce_listing_ids: tuple[str, ...]
    trace: ContextTrace

    def to_agent_context(self) -> AgentContext:
        """Project bounded V2 context without exposing trace or raw search cards."""

        return AgentContext(
            currentMessage=self.turn.current_message,
            recentMessages=tuple(
                {"role": role, "content": content}
                for role, content in self.turn.recent_messages[-12:]
            ),
            referencedListingIds=(
                () if self.suppress_prior_listings else tuple(dict.fromkeys(
                    tuple(item.listing_id for item in self.turn.referenced_listings)
                    + self.commerce_listing_ids
                ))
            ),
            referencedListings=(
                () if self.suppress_prior_listings else self.turn.referenced_listings
            ),
            observations=tuple(
                _customer_observation(item)
                for item in self.decision_observations[-5:]
            ),
            latestSearch=(
                None if self.suppress_prior_listings else self.turn.latest_search
            ),
            pendingInteraction=self.effective_pending_interaction,
            activeWorkflow=self.effective_active_workflow,
            contextualRefinement=self.contextual_refinement,
            scopeResult=self.scope_result,
            availableSkills=(self.available_skills if self.active_skill is None else ()),
            activeSkill=self.active_skill,
        )


class ContextBuilder:
    """Select existing V2 evidence without I/O, authority, or model decisions."""

    def build_turn(
        self,
        *,
        actor_user_id: str,
        session_id: str,
        invocation_id: str,
        current_user_message: AgentMessage,
        current_message: str,
        messages: tuple[AgentMessage, ...],
        session_state: dict[str, object],
    ) -> MarketplaceTurnContext:
        if (
            current_user_message.role != AgentMessageRole.USER
            or current_user_message.actor_user_id != actor_user_id
            or current_user_message.session_id != session_id
            or any(
                item.actor_user_id != actor_user_id or item.session_id != session_id
                for item in messages
            )
        ):
            raise ValueError("Marketplace Agent V2 context ownership mismatch")
        selected = _selected_context_messages(
            messages, current_user_message_id=current_user_message.message_id
        )
        listings, source_id = _referenced_listings_with_source(messages)
        return MarketplaceTurnContext(
            identity=ContextIdentity(
                actor_user_id=actor_user_id,
                session_id=session_id,
                invocation_id=invocation_id,
                current_user_message_id=current_user_message.message_id,
                before=MessageCursor(
                    created_at=current_user_message.created_at,
                    message_id=current_user_message.message_id,
                ),
            ),
            current_message=current_message,
            source_message_ids=tuple(item.message_id for item in messages),
            recent_messages=tuple(
                (item.role.value, item.body) for item in selected[-12:]
            ),
            referenced_listings=listings,
            result_source_message_id=source_id,
            prior_observations=_recent_observations(messages),
            latest_search=_latest_search_from_messages(messages),
            pending_interaction=_pending_from_state(session_state),
            active_workflow=_workflow_from_state(session_state),
            considered_message_count=len(messages),
            excluded_out_of_scope_count=sum(
                1 for item in messages
                if item.message_id != current_user_message.message_id
            ) - len(selected),
        )

    def from_existing_inputs(
        self,
        *,
        current_message: str,
        recent_messages: Sequence[tuple[str, str]],
        referenced_listings: Sequence[ListingAttachment],
        prior_observations: Sequence[ToolObservation],
        pending_interaction: MarketplaceAgentV2PendingInteraction | None,
        active_workflow: MarketplaceAgentV2ActiveWorkflow | None,
    ) -> MarketplaceTurnContext:
        """Adapt direct orchestrator callers to the same per-decision builder path."""

        return MarketplaceTurnContext(
            identity=None,
            current_message=current_message,
            source_message_ids=(),
            recent_messages=tuple(recent_messages),
            referenced_listings=tuple(referenced_listings),
            result_source_message_id=None,
            prior_observations=tuple(prior_observations),
            latest_search=_latest_search_from_observations(prior_observations),
            pending_interaction=pending_interaction,
            active_workflow=active_workflow,
        )

    def for_decision(
        self,
        *,
        turn: MarketplaceTurnContext,
        scope_result: MarketplaceScopeResult,
        pending_interaction: MarketplaceAgentV2PendingInteraction | None,
        active_workflow: MarketplaceAgentV2ActiveWorkflow | None,
        observations: Sequence[ToolObservation],
        contextual_refinement: MarketplaceAgentV2ContextualRefinement | None,
        available_skills: Sequence[SkillSummary],
        active_skill: ActiveSkill | None,
        suppress_prior_listings: bool,
        commerce_listing_ids: Sequence[str],
        exposed_tool_names: Sequence[str],
    ) -> MarketplaceContextPacket:
        selected_skills = tuple(available_skills) if active_skill is None else ()
        reasons = ["LATEST_100", "LAST_12_DIALOGUE", "LAST_5_OBSERVATIONS"]
        if turn.result_source_message_id is not None:
            reasons.append("LATEST_RESULT_BEARING")
        if suppress_prior_listings:
            reasons.append("CURRENT_SEARCH_SUPERSEDES_PRIOR_LISTINGS")
        if active_skill is not None:
            reasons.append("SKILL_LOADED")
        return MarketplaceContextPacket(
            schema_version=CONTEXT_SCHEMA_VERSION,
            turn=turn,
            scope_result=scope_result,
            effective_pending_interaction=pending_interaction,
            effective_active_workflow=active_workflow,
            decision_observations=tuple(observations[-5:]),
            contextual_refinement=contextual_refinement,
            available_skills=selected_skills,
            active_skill=active_skill,
            suppress_prior_listings=suppress_prior_listings,
            commerce_listing_ids=tuple(commerce_listing_ids),
            trace=ContextTrace(
                schema_version=CONTEXT_SCHEMA_VERSION,
                source_message_ids=turn.source_message_ids,
                considered_message_count=turn.considered_message_count,
                selected_dialogue_count=len(turn.recent_messages),
                excluded_out_of_scope_count=turn.excluded_out_of_scope_count,
                result_source_message_id=turn.result_source_message_id,
                selected_listing_count=(
                    0 if suppress_prior_listings else len(turn.referenced_listings)
                ),
                prior_observation_count=len(turn.prior_observations),
                decision_observation_count=min(len(observations), 5),
                pending_included=pending_interaction is not None,
                workflow_included=active_workflow is not None,
                scope=scope_result.scope,
                eligible_skill_names=tuple(skill.name for skill in selected_skills),
                active_skill_name=None if active_skill is None else active_skill.name,
                active_skill_version=(
                    None if active_skill is None else active_skill.version
                ),
                exposed_tool_names=tuple(exposed_tool_names),
                selection_reasons=tuple(reasons),
            ),
        )


def _selected_context_messages(
    messages: tuple[AgentMessage, ...], *, current_user_message_id: str
) -> tuple[AgentMessage, ...]:
    """Exclude the current USER and completed out-of-scope pairs exactly once."""

    excluded: set[str] = {current_user_message_id}
    for message in messages:
        if message.role != AgentMessageRole.ASSISTANT:
            continue
        scope_action = next(
            (
                action for action in message.actions
                if action.get("type") == "MARKETPLACE_AGENT_V2_SCOPE"
            ),
            None,
        )
        if scope_action is None or scope_action.get("scope") != "OUT_OF_SCOPE":
            continue
        excluded.add(message.message_id)
        if message.invocation_user_message_id is not None:
            excluded.add(message.invocation_user_message_id)
    return tuple(message for message in messages if message.message_id not in excluded)


def _marketplace_context_messages(
    messages: tuple[AgentMessage, ...], *, current_user_message_id: str
) -> tuple[tuple[str, str], ...]:
    return tuple(
        (message.role.value, message.body)
        for message in _selected_context_messages(
            messages, current_user_message_id=current_user_message_id
        )
    )


def _referenced_listings_with_source(
    messages: tuple[AgentMessage, ...],
) -> tuple[tuple[ListingAttachment, ...], str | None]:
    # Only the most recent result-bearing assistant message supplies active ordinals.
    for message in reversed(messages):
        result: list[ListingAttachment] = []
        for source in message.sources:
            try:
                attachment = ListingAttachment.model_validate_json(json.dumps(source))
            except Exception:
                continue
            result.append(attachment)
        if result:
            return tuple(result[:20]), message.message_id
    return (), None


def _referenced_listings(messages: tuple[AgentMessage, ...]) -> tuple[ListingAttachment, ...]:
    return _referenced_listings_with_source(messages)[0]


def _recent_observations(messages: tuple[AgentMessage, ...]) -> tuple[ToolObservation, ...]:
    """Restore bounded safe V2 observations, leaving display activity unchanged."""

    result: list[ToolObservation] = []
    for message in messages:
        for action in message.actions:
            if action.get("type") != "MARKETPLACE_AGENT_V2_OBSERVATION":
                continue
            payload = {key: value for key, value in action.items() if key != "type"}
            try:
                result.append(ToolObservation.model_validate_json(json.dumps(payload)))
            except Exception:
                continue
    return tuple(result[-12:])


def _latest_search_from_messages(
    messages: tuple[AgentMessage, ...],
) -> ExecutedSearchSnapshot | None:
    """Select the newest executed search independently of the last-12 observation cap."""

    for message in reversed(messages):
        for action in reversed(message.actions):
            if action.get("type") != "MARKETPLACE_AGENT_V2_OBSERVATION":
                continue
            if action.get("tool") != "search_listings":
                continue
            try:
                observation = ToolObservation.model_validate_json(json.dumps({
                    key: value for key, value in action.items() if key != "type"
                }))
            except Exception:
                return None
            return _fresh_search_snapshot(observation)
    return None


def _latest_search_from_observations(
    observations: Sequence[ToolObservation],
) -> ExecutedSearchSnapshot | None:
    for observation in reversed(observations):
        if observation.tool == "search_listings":
            return _fresh_search_snapshot(observation)
    return None


def _fresh_search_snapshot(
    observation: ToolObservation,
) -> ExecutedSearchSnapshot | None:
    snapshot = observation.applied_search
    if (
        observation.status != "SUCCEEDED"
        or snapshot is None
        or snapshot.expires_at <= datetime.now(UTC)
    ):
        return None
    return snapshot


def _pending_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2PendingInteraction | None:
    raw = state.get("pendingInteraction")
    if not isinstance(raw, dict):
        return None
    try:
        return MarketplaceAgentV2PendingInteraction.model_validate_json(json.dumps(raw))
    except Exception:
        return None


def _workflow_from_state(
    state: dict[str, object],
) -> MarketplaceAgentV2ActiveWorkflow | None:
    raw = state.get("activeWorkflow")
    if not isinstance(raw, dict):
        return None
    try:
        return MarketplaceAgentV2ActiveWorkflow.model_validate_json(json.dumps(raw))
    except Exception:
        return None


def _customer_observation(observation: ToolObservation) -> ToolObservation:
    """Keep search counts while withholding cards and internal ranking metadata."""

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
        "applied_search": None,
    })
