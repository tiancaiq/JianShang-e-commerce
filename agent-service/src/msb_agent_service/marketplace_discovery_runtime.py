from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Protocol, Sequence

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import BaseTool
from langchain_core.utils.function_calling import convert_to_openai_tool
from prometheus_client import CollectorRegistry, Counter, Histogram
from pydantic import ConfigDict, Field

from .config import Settings
from .discovery_embedding_contract import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from .embedding_provider import EmbeddingProvider, OpenAIEmbeddingProvider
from .marketplace_discovery import (
    DiscoveryFailureStage,
    DiscoveryLimits,
    DiscoveryProductTool,
    DiscoveryProviderFailureKind,
    DiscoveryStageError,
    DiscoveryTurnResult,
    MarketplaceDiscoveryOrchestrator,
    ProductMarketplaceDiscoveryClient,
)
from .errors import LlmProviderError, ProviderErrorCode, ProviderFailureKind
from .provider import DiscoveryProviderResult, DiscoveryProviderToolCall, OpenAIProvider
from .marketplace_listing_retrieval import (
    HybridMarketplaceDiscoveryClient,
)

_SAFE_CORRELATION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
_DISCOVERY_TOOL_NAMES = {
    "CHECK_AVAILABILITY",
    "SEARCH_INDIVIDUAL",
    "GET_LISTING",
    # Accepted only from legacy offline providers; the live runtime never binds it.
    "DiscoveryTurnResult",
}
LOGGER = logging.getLogger(__name__)


class DiscoveryChatProvider(Protocol):
    """Keeps the LangChain bridge independent from provider SDK types."""

    @property
    def model_name(self) -> str: ...

    async def discovery_chat(
        self,
        *,
        instructions: str,
        input_items: list[dict[str, object]],
        tools: list[dict[str, object]],
        maximum_output_tokens: int,
        maximum_tool_calls: int,
        correlation_id: str,
        timeout_seconds: float | None = None,
        on_text_delta: Callable[[str], Awaitable[None]] | None = None,
    ) -> DiscoveryProviderResult: ...

    async def close(self) -> None: ...


class OpenAIDiscoveryChatModel(BaseChatModel):
    """Adapts the Agent-owned provider boundary to LangChain's chat interface."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    provider: Any = Field(exclude=True)
    provider_model_name: str
    correlation_id: str = Field(default="", exclude=True)
    maximum_output_tokens: int = Field(default=800, ge=64, le=800)
    maximum_tool_calls: int = Field(default=1, ge=1, le=1)
    provider_timeout_seconds: float = Field(default=10.0, gt=0, le=10.0)
    turn_deadline_monotonic: float | None = Field(default=None, exclude=True)
    bound_tools: tuple[dict[str, object], ...] = Field(default=(), exclude=True)
    text_delta_callback: Any = Field(default=None, exclude=True)

    @property
    def _llm_type(self) -> str:
        return "msb-openai-discovery-responses"

    @property
    def _identifying_params(self) -> dict[str, Any]:
        return {
            "provider": "openai",
            "model": self.provider_model_name,
            "store": False,
        }

    def for_request(self, correlation_id: str) -> "OpenAIDiscoveryChatModel":
        """Return an immutable request-scoped copy so concurrent IDs cannot mix."""

        if _SAFE_CORRELATION.fullmatch(correlation_id) is None:
            raise ValueError("Invalid discovery correlation ID")
        return self.model_copy(update={"correlation_id": correlation_id})

    def with_turn_deadline(self, deadline_monotonic: float) -> "OpenAIDiscoveryChatModel":
        """Clip provider waits to the authoritative whole-turn deadline."""

        if not isinstance(deadline_monotonic, (int, float)) or deadline_monotonic <= 0:
            raise ValueError("Invalid discovery turn deadline")
        return self.model_copy(update={"turn_deadline_monotonic": float(deadline_monotonic)})

    def with_text_delta(
        self,
        callback: Callable[[str], Awaitable[None]] | None,
    ) -> "OpenAIDiscoveryChatModel":
        """Attach the request-owned SSE sink for natural terminal content only."""

        if callback is not None and not callable(callback):
            raise ValueError("Invalid discovery text-delta callback")
        return self.model_copy(update={"text_delta_callback": callback})

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | Any | BaseTool],
        *,
        tool_choice: str | None = None,
        **_: Any,
    ) -> BaseChatModel:
        """Bind exactly the live application-tool registry for one agent decision."""

        if tool_choice not in {None, "any", "required"}:
            raise ValueError("Discovery requires model-selected allowlisted tools")
        converted = tuple(_responses_tool(tool) for tool in tools)
        names = {str(item["name"]) for item in converted}
        if not converted or not names.issubset(_DISCOVERY_TOOL_NAMES):
            raise ValueError("Discovery attempted to bind a non-allowlisted tool")
        return self.model_copy(update={"bound_tools": converted})

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        del messages, stop, run_manager, kwargs
        raise RuntimeError("Discovery provider execution is async-only")

    async def _agenerate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        """Execute one bounded provider step without provider or LangChain memory."""

        del stop, run_manager, kwargs
        if not self.correlation_id or not self.bound_tools:
            raise DiscoveryStageError(
                DiscoveryFailureStage.PROVIDER_PREFLIGHT,
                kind=DiscoveryProviderFailureKind.REQUEST_CONFIGURATION,
            )
        try:
            instructions, input_items = _responses_input(messages)
        except DiscoveryStageError:
            raise
        except Exception as exc:
            raise DiscoveryStageError(
                DiscoveryFailureStage.PROVIDER_PREFLIGHT,
                kind=_message_serialization_failure_kind(messages),
            ) from exc
        provider_timeout, turn_deadline = _remaining_provider_timeout(
            self.turn_deadline_monotonic,
            self.provider_timeout_seconds,
        )
        provider_tools = [dict(item) for item in self.bound_tools]
        try:
            provider_arguments: dict[str, object] = {
                "instructions": instructions,
                "input_items": input_items,
                "tools": provider_tools,
                "maximum_output_tokens": self.maximum_output_tokens,
                "maximum_tool_calls": self.maximum_tool_calls,
                "correlation_id": self.correlation_id,
                "timeout_seconds": provider_timeout,
            }
            if self.text_delta_callback is not None:
                provider_arguments["on_text_delta"] = self.text_delta_callback
            provider_result = await asyncio.wait_for(
                self.provider.discovery_chat(**provider_arguments),
                timeout=provider_timeout,
            )
        except asyncio.TimeoutError as exc:
            stage = (
                DiscoveryFailureStage.GRAPH_TIMEOUT
                if turn_deadline
                else DiscoveryFailureStage.PROVIDER_TIMEOUT
            )
            kind = (
                None
                if stage == DiscoveryFailureStage.GRAPH_TIMEOUT
                else DiscoveryProviderFailureKind.TIMEOUT
            )
            raise DiscoveryStageError(stage, kind=kind) from exc
        except asyncio.CancelledError as exc:
            raise DiscoveryStageError(
                DiscoveryFailureStage.PROVIDER_CANCEL,
                kind=DiscoveryProviderFailureKind.CANCEL,
            ) from exc
        except DiscoveryStageError:
            raise
        except LlmProviderError as exc:
            raise _provider_stage_error(exc) from exc
        except Exception as exc:
            raise DiscoveryStageError(
                DiscoveryFailureStage.PROVIDER_REQUEST_START,
                kind=DiscoveryProviderFailureKind.REQUEST_CONSTRUCTION,
            ) from exc
        try:
            tool_calls = _provider_tool_calls(provider_result, messages=messages)
            if len(tool_calls) > 1:
                raise DiscoveryStageError(
                    DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
                    kind=DiscoveryProviderFailureKind.TOOL_CALL_PARSING,
                )
            # The real provider rejects mixed content/tool responses. Legacy test
            # adapters predate that boundary and their incidental prose is discarded.
            content = "" if tool_calls else provider_result.content
            if bool(tool_calls) == bool(content.strip()):
                raise DiscoveryStageError(
                    DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
                    kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
                )
            message = AIMessage(
                # Natural assistant content is a valid terminal decision. Tool calls
                # remain private until the backend validates and executes one of them.
                content=content,
                tool_calls=tool_calls,
                usage_metadata=_provider_usage(provider_result),
                response_metadata={
                    "provider": "openai",
                    "latency_ms": _bounded_nonnegative_int(
                        provider_result.latency_ms,
                        maximum=120_000,
                    ),
                    "store": False,
                },
            )
        except DiscoveryStageError:
            raise
        except Exception as exc:
            raise DiscoveryStageError(
                DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
                kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
            ) from exc
        return ChatResult(generations=[ChatGeneration(message=message)])


def _safe_terminal_tool_call(messages: Sequence[BaseMessage]) -> dict[str, object]:
    """Retain the legacy deterministic recovery helper for stored-history compatibility."""

    search_count: int | None = None
    eligible_listing_ids: list[str] = []
    for message in messages:
        if not isinstance(message, ToolMessage):
            continue
        try:
            value = json.loads(_message_text(message.content, 32_000))
        except (TypeError, ValueError):
            continue
        if not isinstance(value, dict):
            continue
        if message.name == "SEARCH_INDIVIDUAL" and isinstance(value.get("count"), int):
            search_count = int(value["count"])
        elif message.name == "GET_LISTING" and value.get("eligible") is True:
            listing_id = value.get("listingId")
            if isinstance(listing_id, str) and listing_id not in eligible_listing_ids:
                eligible_listing_ids.append(listing_id)
    if eligible_listing_ids:
        result = DiscoveryTurnResult(
            outcome="RECOMMEND",
            message="I verified current public listings against the selected constraints.",
            selections=tuple(
                {
                    "listingId": listing_id,
                    "matchReason": "Current Product facts were revalidated.",
                }
                for listing_id in eligible_listing_ids[:5]
            ),
        )
    elif search_count == 0:
        result = DiscoveryTurnResult(
            outcome="NO_RESULTS",
            message="I couldn't verify a current listing for those constraints.",
        )
    else:
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
        )
    return {
        "id": "application-safe-terminal-result",
        "name": "DiscoveryTurnResult",
        "args": result.model_dump(mode="json", by_alias=True),
        "type": "tool_call",
    }


@dataclass
class MarketplaceDiscoveryRuntime:
    """Owns production discovery composition and its provider resource."""

    orchestrator: MarketplaceDiscoveryOrchestrator
    provider: DiscoveryChatProvider
    embedding_provider: EmbeddingProvider | None = None

    async def close(self) -> None:
        try:
            if self.embedding_provider is not None:
                await self.embedding_provider.close()
        finally:
            await self.provider.close()


def _remaining_provider_timeout(
    deadline_monotonic: float | None,
    provider_timeout_seconds: float,
) -> tuple[float, bool]:
    """Respect the whole-turn deadline while preserving the provider step cap."""

    if deadline_monotonic is None:
        return provider_timeout_seconds, False
    remaining = deadline_monotonic - time.monotonic()
    if remaining <= 0:
        raise DiscoveryStageError(DiscoveryFailureStage.GRAPH_TIMEOUT)
    return min(provider_timeout_seconds, remaining), remaining <= provider_timeout_seconds


def _message_serialization_failure_kind(
    messages: Sequence[BaseMessage],
) -> DiscoveryProviderFailureKind:
    """Classify LangChain-to-Responses conversion without inspecting content."""

    if any(isinstance(message, ToolMessage) for message in messages):
        return DiscoveryProviderFailureKind.TOOL_MESSAGE_CONVERSION
    return DiscoveryProviderFailureKind.MESSAGE_SERIALIZATION


def _provider_stage_error(error: LlmProviderError) -> DiscoveryStageError:
    """Translate provider-safe error metadata into discovery stage telemetry."""

    failure_kind = getattr(error, "failure_kind", None)
    if error.code == ProviderErrorCode.TIMED_OUT or failure_kind == ProviderFailureKind.TIMEOUT:
        return DiscoveryStageError(
            DiscoveryFailureStage.PROVIDER_TIMEOUT,
            kind=DiscoveryProviderFailureKind.TIMEOUT,
        )
    if (
        error.code == ProviderErrorCode.INVALID_RESPONSE
        or failure_kind == ProviderFailureKind.RESPONSE_SCHEMA
    ):
        return DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
        )
    if failure_kind == ProviderFailureKind.CONFIGURATION:
        return DiscoveryStageError(
            DiscoveryFailureStage.PROVIDER_PREFLIGHT,
            kind=DiscoveryProviderFailureKind.REQUEST_CONFIGURATION,
        )
    if failure_kind == ProviderFailureKind.STATUS:
        return DiscoveryStageError(
            DiscoveryFailureStage.PROVIDER_REQUEST_START,
            kind=DiscoveryProviderFailureKind.STATUS,
        )
    if failure_kind == ProviderFailureKind.TRANSPORT:
        return DiscoveryStageError(
            DiscoveryFailureStage.PROVIDER_REQUEST_START,
            kind=DiscoveryProviderFailureKind.TRANSPORT,
        )
    return DiscoveryStageError(
        DiscoveryFailureStage.PROVIDER_REQUEST_START,
        kind=DiscoveryProviderFailureKind.REQUEST_CONSTRUCTION,
    )


def _provider_tool_calls(
    provider_result: DiscoveryProviderResult,
    *,
    messages: Sequence[BaseMessage] = (),
) -> list[dict[str, object]]:
    """Validate provider tool calls before LangChain sees them again."""

    content = provider_result.content
    if not isinstance(content, str) or len(content.encode("utf-8")) > 8_000:
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_CONTENT,
        )
    calls = provider_result.tool_calls
    if not isinstance(calls, tuple) or len(calls) > 6:
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.TOOL_CALL_PARSING,
        )
    converted: list[dict[str, object]] = []
    for item in calls:
        if (
            not isinstance(item, DiscoveryProviderToolCall)
            or not isinstance(item.call_id, str)
            or not 1 <= len(item.call_id) <= 200
            or item.name not in _DISCOVERY_TOOL_NAMES
            or not isinstance(item.arguments, dict)
        ):
            raise DiscoveryStageError(
                DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
                kind=DiscoveryProviderFailureKind.TOOL_CALL_PARSING,
            )
        if item.name == "DiscoveryTurnResult":
            try:
                DiscoveryTurnResult.model_validate(item.arguments)
            except Exception:
                return [_safe_terminal_tool_call(messages)]
        converted.append(
            {
                "id": item.call_id,
                "name": item.name,
                "args": item.arguments,
                "type": "tool_call",
            }
        )
    if not converted and not content.strip():
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_CONTENT,
        )
    return converted


def _provider_usage(provider_result: DiscoveryProviderResult) -> dict[str, int]:
    """Bound usage metadata before adding it to LangChain message metadata."""

    input_tokens = _bounded_nonnegative_int(
        provider_result.input_tokens,
        maximum=1_000_000,
    )
    output_tokens = _bounded_nonnegative_int(
        provider_result.output_tokens,
        maximum=1_000_000,
    )
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": input_tokens + output_tokens,
    }


def _bounded_nonnegative_int(value: object, *, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise DiscoveryStageError(
            DiscoveryFailureStage.STRUCTURED_RESPONSE_PARSE,
            kind=DiscoveryProviderFailureKind.RESPONSE_SCHEMA,
        )
    return value


class _Utf8ByteEncoding:
    """Bounds discovery embedding input without downloading tokenizer assets."""

    @staticmethod
    def encode(value: str) -> bytes:
        return value.encode("utf-8")


class DiscoveryEmbeddingMetrics:
    """Records low-cardinality discovery embedding usage without query labels."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self._requests = Counter(
            "agent_discovery_embedding_requests_total",
            "Discovery query embedding requests by bounded result.",
            ("result",),
            registry=registry,
        )
        self._duration = Histogram(
            "agent_discovery_embedding_duration_seconds",
            "Discovery query embedding latency.",
            registry=registry,
        )
        self._tokens = Counter(
            "agent_discovery_embedding_input_tokens_total",
            "Provider-reported discovery embedding input tokens.",
            registry=registry,
        )

    def record_embedding(
        self,
        result: str,
        duration_seconds: float,
        *,
        input_tokens: int = 0,
    ) -> None:
        self._requests.labels(result=result).inc()
        self._duration.observe(duration_seconds)
        if input_tokens > 0:
            self._tokens.inc(input_tokens)


def build_marketplace_discovery_runtime(
    settings: Settings,
    *,
    provider: DiscoveryChatProvider | None = None,
    product: DiscoveryProductTool | None = None,
    embedding_provider: EmbeddingProvider | None = None,
    product_http_client: httpx.AsyncClient | None = None,
    registry: CollectorRegistry | None = None,
) -> MarketplaceDiscoveryRuntime:
    """Compose Discovery only after its complete application-owned gate passes."""

    if (
        not settings.discovery_api.generation_enabled
        or not settings.openai_configured
        or settings.discovery_api.product_service_url is None
        or (product is None and settings.discovery_api.product_service_token is None)
    ):
        raise RuntimeError(
            "Enabled marketplace discovery requires configured provider and "
            "Product authenticated tools"
        )
    limits = DiscoveryLimits(
        product_timeout_seconds=settings.discovery_api.dependency_timeout_seconds,
        # Model calls have their own Discovery budget; query embeddings keep the
        # older 8s dependency cap so Product tool execution still has room.
        provider_timeout_seconds=min(
            settings.openai_timeout_seconds,
            settings.discovery_api.model_call_timeout_seconds,
        ),
        query_embedding_timeout_seconds=min(settings.openai_timeout_seconds, 8.0),
    )
    runtime_provider = provider or OpenAIProvider(settings)
    runtime_product = product or ProductMarketplaceDiscoveryClient(
        settings.discovery_api.product_service_url,
        timeout_seconds=limits.product_timeout_seconds,
        internal_service_token=settings.discovery_api.product_service_token,
    )
    runtime_embedding_provider: EmbeddingProvider | None = None
    if settings.discovery_api.hybrid_retrieval_enabled:
        if (
            not settings.discovery_api.query_embedding_enabled
            or settings.discovery_api.product_service_token is None
        ):
            raise RuntimeError(
                "Enabled discovery hybrid retrieval requires query embedding "
                "and the Product service token"
            )
        runtime_registry = registry or CollectorRegistry()
        runtime_embedding_provider = embedding_provider
        if runtime_embedding_provider is None:
            runtime_embedding_provider = OpenAIEmbeddingProvider(
                api_key=str(settings.openai_api_key),
                model=EMBEDDING_MODEL,
                dimensions=EMBEDDING_DIMENSIONS,
                timeout_seconds=settings.openai_timeout_seconds,
                # Query search owns exactly one deadline-aware timeout retry.
                max_retries=0,
                maximum_inputs=1,
                maximum_input_tokens=512,
                maximum_total_tokens=512,
                metrics=DiscoveryEmbeddingMetrics(runtime_registry),
                encoding=_Utf8ByteEncoding(),
            )
        runtime_product = HybridMarketplaceDiscoveryClient(
            product=runtime_product,
            product_base_url=settings.discovery_api.product_service_url,
            product_service_token=settings.discovery_api.product_service_token,
            embedding_provider=runtime_embedding_provider,
            timeout_seconds=limits.product_timeout_seconds,
            query_timeout_seconds=limits.query_embedding_timeout_seconds,
            response_schema_version="MARKETPLACE_HYBRID_SEARCH_RESPONSE_V2",
            client=product_http_client,
        )
    model = OpenAIDiscoveryChatModel(
        provider=runtime_provider,
        provider_model_name=runtime_provider.model_name,
        maximum_output_tokens=limits.maximum_output_tokens,
        maximum_tool_calls=1,
        provider_timeout_seconds=limits.provider_timeout_seconds,
    )
    return MarketplaceDiscoveryRuntime(
        orchestrator=MarketplaceDiscoveryOrchestrator(
            model,
            runtime_product,
            limits=limits,
        ),
        provider=runtime_provider,
        embedding_provider=runtime_embedding_provider,
    )


def _responses_tool(tool: dict[str, Any] | type | Any | BaseTool) -> dict[str, object]:
    """Convert LangChain tools to the strict Responses function-tool shape."""

    converted = convert_to_openai_tool(tool, strict=True)
    function = converted.get("function")
    if converted.get("type") != "function" or not isinstance(function, dict):
        raise ValueError("Discovery supports strict function tools only")
    name = function.get("name")
    parameters = function.get("parameters")
    if name not in _DISCOVERY_TOOL_NAMES or not isinstance(parameters, dict):
        raise ValueError("Discovery tool schema is not allowlisted")
    result: dict[str, object] = {
        "type": "function",
        "name": name,
        "parameters": parameters,
        "strict": True,
    }
    description = function.get("description")
    if isinstance(description, str) and description:
        result["description"] = description[:1_000]
    return result


def _responses_input(
    messages: Sequence[BaseMessage],
) -> tuple[str, list[dict[str, object]]]:
    """Translate trusted LangChain messages without retaining hidden reasoning."""

    instructions: list[str] = []
    items: list[dict[str, object]] = []
    for message in messages:
        if isinstance(message, SystemMessage):
            instructions.append(_message_text(message.content, 16_000))
            continue
        if isinstance(message, HumanMessage):
            items.append({"role": "user", "content": _message_text(message.content)})
            continue
        if isinstance(message, AIMessage):
            content = _message_text(message.content, 8_000, allow_empty=True)
            if content:
                items.append({"role": "assistant", "content": content})
            for call in message.tool_calls:
                call_id = call.get("id")
                name = call.get("name")
                arguments = call.get("args")
                if (
                    not isinstance(call_id, str)
                    or not 1 <= len(call_id) <= 200
                    or name not in _DISCOVERY_TOOL_NAMES
                    or not isinstance(arguments, dict)
                ):
                    raise ValueError("Invalid stored discovery tool-call shape")
                items.append(
                    {
                        "type": "function_call",
                        "call_id": call_id,
                        "name": name,
                        "arguments": json.dumps(
                            arguments,
                            ensure_ascii=False,
                            separators=(",", ":"),
                            sort_keys=True,
                        ),
                    }
                )
            continue
        if isinstance(message, ToolMessage):
            if not isinstance(message.tool_call_id, str):
                raise ValueError("Invalid discovery tool result identity")
            items.append(
                {
                    "type": "function_call_output",
                    "call_id": message.tool_call_id,
                    "output": _message_text(message.content, 32_000),
                }
            )
            continue
        raise ValueError("Unsupported discovery message type")
    if not instructions or not items:
        raise ValueError("Discovery model input is incomplete")
    return "\n\n".join(instructions), items


def _message_text(
    content: str | list[str | dict[str, Any]],
    maximum_bytes: int = 48_000,
    *,
    allow_empty: bool = False,
) -> str:
    if isinstance(content, str):
        value = content
    elif isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and item.get("type") in {"text", "input_text"}:
                text = item.get("text")
                if not isinstance(text, str):
                    raise ValueError("Invalid discovery text block")
                parts.append(text)
            else:
                raise ValueError("Non-text discovery message content is forbidden")
        value = "\n".join(parts)
    else:
        raise ValueError("Invalid discovery message content")
    if (not value and not allow_empty) or len(value.encode("utf-8")) > maximum_bytes:
        raise ValueError("Discovery message content exceeds its bound")
    return value
