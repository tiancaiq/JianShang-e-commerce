from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from typing import Any, Protocol, Sequence

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
from pydantic import ConfigDict, Field

from .config import Settings
from .marketplace_discovery import (
    DiscoveryLimits,
    DiscoveryProductTool,
    MarketplaceDiscoveryOrchestrator,
    ProductMarketplaceDiscoveryClient,
)
from .provider import DiscoveryProviderResult, OpenAIProvider

_SAFE_CORRELATION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
_DISCOVERY_TOOL_NAMES = {
    "SEARCH_INDIVIDUAL",
    "GET_LISTING",
    "DiscoveryTurnResult",
}


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
    ) -> DiscoveryProviderResult: ...

    async def close(self) -> None: ...


class OpenAIDiscoveryChatModel(BaseChatModel):
    """Adapts the Agent-owned provider boundary to LangChain's chat interface."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    provider: Any = Field(exclude=True)
    provider_model_name: str
    correlation_id: str = Field(default="", exclude=True)
    maximum_output_tokens: int = Field(default=800, ge=64, le=800)
    maximum_tool_calls: int = Field(default=6, ge=1, le=6)
    provider_timeout_seconds: float = Field(default=8.0, gt=0, le=8.0)
    bound_tools: tuple[dict[str, object], ...] = Field(default=(), exclude=True)

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

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | Any | BaseTool],
        *,
        tool_choice: str | None = None,
        **_: Any,
    ) -> BaseChatModel:
        """Bind only the two application tools and strict final-result tool."""

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
            raise RuntimeError("Discovery model is not request-scoped and tool-bound")
        instructions, input_items = _responses_input(messages)
        provider_result = await asyncio.wait_for(
            self.provider.discovery_chat(
                instructions=instructions,
                input_items=input_items,
                tools=[dict(item) for item in self.bound_tools],
                maximum_output_tokens=self.maximum_output_tokens,
                maximum_tool_calls=self.maximum_tool_calls,
                correlation_id=self.correlation_id,
            ),
            timeout=self.provider_timeout_seconds,
        )
        message = AIMessage(
            # Free-form provider text is never authoritative or persisted; only
            # strict tool calls can influence the deterministic final guard.
            content="",
            tool_calls=[
                {
                    "id": item.call_id,
                    "name": item.name,
                    "args": item.arguments,
                    "type": "tool_call",
                }
                for item in provider_result.tool_calls
            ],
            usage_metadata={
                "input_tokens": provider_result.input_tokens,
                "output_tokens": provider_result.output_tokens,
                "total_tokens": (
                    provider_result.input_tokens + provider_result.output_tokens
                ),
            },
            response_metadata={
                "provider": "openai",
                "latency_ms": provider_result.latency_ms,
                "store": False,
            },
        )
        return ChatResult(generations=[ChatGeneration(message=message)])


@dataclass
class MarketplaceDiscoveryRuntime:
    """Owns production discovery composition and its provider resource."""

    orchestrator: MarketplaceDiscoveryOrchestrator
    provider: DiscoveryChatProvider

    async def close(self) -> None:
        await self.provider.close()


def build_marketplace_discovery_runtime(
    settings: Settings,
    *,
    provider: DiscoveryChatProvider | None = None,
    product: DiscoveryProductTool | None = None,
) -> MarketplaceDiscoveryRuntime:
    """Compose Discovery only after its complete application-owned gate passes."""

    if (
        not settings.discovery_api.generation_enabled
        or not settings.openai_configured
        or settings.discovery_api.product_service_url is None
    ):
        raise RuntimeError(
            "Enabled marketplace discovery requires configured provider and "
            "Product public tools"
        )
    limits = DiscoveryLimits(
        product_timeout_seconds=settings.discovery_api.dependency_timeout_seconds,
        provider_timeout_seconds=min(settings.openai_timeout_seconds, 8.0),
    )
    runtime_provider = provider or OpenAIProvider(settings)
    runtime_product = product or ProductMarketplaceDiscoveryClient(
        settings.discovery_api.product_service_url,
        timeout_seconds=limits.product_timeout_seconds,
    )
    model = OpenAIDiscoveryChatModel(
        provider=runtime_provider,
        provider_model_name=runtime_provider.model_name,
        maximum_output_tokens=limits.maximum_output_tokens,
        maximum_tool_calls=limits.maximum_tool_calls,
        provider_timeout_seconds=limits.provider_timeout_seconds,
    )
    return MarketplaceDiscoveryRuntime(
        orchestrator=MarketplaceDiscoveryOrchestrator(
            model,
            runtime_product,
            limits=limits,
        ),
        provider=runtime_provider,
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
