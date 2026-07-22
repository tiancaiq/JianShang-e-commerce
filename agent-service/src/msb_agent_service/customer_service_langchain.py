from __future__ import annotations

import asyncio
import re
from decimal import Decimal
from typing import Any

from langchain_core.runnables import Runnable, RunnableLambda, RunnableSequence
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .agent_persistence import AgentResolutionType, BeginInvocation
from .customer_service_api import (
    AnswerDraft,
    AnswererMetadata,
    ListingContext,
    QuestionAnswerer,
)

_ULID_PATTERN = re.compile(r"^[0-9A-Z]{26}$")
_CORRELATION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_LISTING_VERSION_PATTERN = re.compile(r"^(?:0|[1-9][0-9]{0,19})$")


class LangChainAnswerInput(BaseModel):
    """Carries only trusted application context into the internal Runnable graph."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        arbitrary_types_allowed=True,
    )

    begin: BeginInvocation
    actor_user_id: str = Field(min_length=26, max_length=26)
    listing: ListingContext
    correlation_id: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_trusted_context(self) -> "LangChainAnswerInput":
        if (
            _ULID_PATTERN.fullmatch(self.actor_user_id) is None
            or self.begin.invocation.actor_user_id != self.actor_user_id
            or _ULID_PATTERN.fullmatch(self.listing.listing_id) is None
            or _LISTING_VERSION_PATTERN.fullmatch(self.listing.source_version) is None
            or not 1 <= len(self.listing.title) <= 180
            or not 1 <= len(self.listing.transaction_notice) <= 1_000
            or _CORRELATION_PATTERN.fullmatch(self.correlation_id) is None
        ):
            raise ValueError("LangChain answer context is inconsistent")
        return self


class LangChainQuestionAnswerer:
    """Runs an application-owned answerer through a typed, memory-free Runnable."""

    def __init__(self, delegate: QuestionAnswerer) -> None:
        self._delegate = delegate
        self.metadata: AnswererMetadata = delegate.metadata
        validate_input = RunnableLambda(
            self._validate_input,
            name="validate_application_context",
        ).with_types(
            input_type=LangChainAnswerInput,
            output_type=LangChainAnswerInput,
        )
        invoke_delegate = RunnableLambda(
            self._invoke_delegate,
            name="invoke_application_rag",
        ).with_types(
            input_type=LangChainAnswerInput,
            output_type=AnswerDraft,
        )
        validate_output = RunnableLambda(
            self._validate_output,
            name="validate_application_answer",
        ).with_types(
            input_type=AnswerDraft,
            output_type=AnswerDraft,
        )
        self._runnable: Runnable[LangChainAnswerInput, AnswerDraft] = (
            RunnableSequence(
                validate_input,
                invoke_delegate,
                validate_output,
                name="listing_customer_service_rag_v1",
            ).with_types(
                input_type=LangChainAnswerInput,
                output_type=AnswerDraft,
            )
        )

    @property
    def runnable(self) -> Runnable[LangChainAnswerInput, AnswerDraft]:
        """Expose the typed internal graph for schema and offline parity tests."""

        return self._runnable

    async def answer(
        self,
        *,
        begin: BeginInvocation,
        actor_user_id: str,
        listing: ListingContext,
        correlation_id: str,
    ) -> AnswerDraft:
        """Invoke the graph without memory, tools, tracing transport, or side effects."""

        request = LangChainAnswerInput(
            begin=begin,
            actor_user_id=actor_user_id,
            listing=listing,
            correlation_id=correlation_id,
        )
        try:
            return await self._runnable.ainvoke(
                request,
                config={
                    "callbacks": [],
                    "tags": ["listing-customer-service", "langchain-core"],
                    "metadata": {
                        "adapter": "langchain-core",
                        "schema": "ai-cs-runnable-v1",
                    },
                    "run_name": "listing_customer_service_rag_v1",
                },
            )
        except asyncio.CancelledError:
            raise

    @staticmethod
    def _validate_input(value: LangChainAnswerInput) -> LangChainAnswerInput:
        return LangChainAnswerInput.model_validate(value)

    async def _invoke_delegate(
        self,
        value: LangChainAnswerInput,
    ) -> AnswerDraft:
        return await self._delegate.answer(
            begin=value.begin,
            actor_user_id=value.actor_user_id,
            listing=value.listing,
            correlation_id=value.correlation_id,
        )

    @staticmethod
    def _validate_output(value: Any) -> AnswerDraft:
        if (
            not isinstance(value, AnswerDraft)
            or not 1 <= len(value.body) <= 12_000
            or not isinstance(value.resolution_type, AgentResolutionType)
            or len(value.sources) > 8
            or len(value.actions) > 3
            or not all(isinstance(item, dict) for item in value.sources)
            or not all(isinstance(item, dict) for item in value.actions)
            or isinstance(value.input_tokens, bool)
            or not isinstance(value.input_tokens, int)
            or not 0 <= value.input_tokens <= 1_000_000
            or isinstance(value.output_tokens, bool)
            or not isinstance(value.output_tokens, int)
            or not 0 <= value.output_tokens <= 1_000_000
            or isinstance(value.latency_ms, bool)
            or not isinstance(value.latency_ms, int)
            or not 0 <= value.latency_ms <= 120_000
            or not isinstance(value.estimated_cost, Decimal)
            or not value.estimated_cost.is_finite()
            or value.estimated_cost < 0
        ):
            raise ValueError("LangChain answer output is invalid")
        return value
