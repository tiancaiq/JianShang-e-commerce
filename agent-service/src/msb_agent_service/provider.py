from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from collections.abc import Awaitable, Callable
from typing import Any, Generic, TypeVar

from openai import AsyncOpenAI
from pydantic import BaseModel, ValidationError

from .config import Settings
from .errors import (
    LlmProviderError,
    classify_openai_error,
    invalid_provider_response,
    provider_not_configured,
)
from .schemas import (
    DemoListingArguments,
    ImageSmokeResult,
    TextSmokeResult,
    ToolSmokeResult,
)

LOGGER = logging.getLogger(__name__)
StructuredResultT = TypeVar("StructuredResultT", bound=BaseModel)
_DISCOVERY_TOOL_NAMES = {
    "CHECK_AVAILABILITY",
    "SEARCH_INDIVIDUAL",
    "GET_LISTING",
}


@dataclass(frozen=True)
class DiscoveryProviderToolCall:
    """Carries one validated provider tool request without SDK types."""

    call_id: str
    name: str
    arguments: dict[str, object]


@dataclass(frozen=True)
class DiscoveryProviderResult:
    """Carries one bounded discovery model turn behind the provider boundary."""

    content: str
    tool_calls: tuple[DiscoveryProviderToolCall, ...]
    input_tokens: int
    output_tokens: int
    latency_ms: int


@dataclass(frozen=True)
class DiscoveryAnswerStreamResult:
    """Summarizes one completed user-facing stream without retaining internals."""

    text: str
    input_tokens: int
    output_tokens: int
    latency_ms: int

DEMO_LISTING_ID = "demo-listing-1"
DEMO_LISTING_TOOL = {
    "type": "function",
    "name": "get_demo_listing",
    "description": "Return the fixed non-production listing used by the provider smoke test.",
    "parameters": {
        "type": "object",
        "properties": {
            "listing_id": {
                "type": "string",
                "description": f"The fixed smoke listing ID: {DEMO_LISTING_ID}",
            }
        },
        "required": ["listing_id"],
        "additionalProperties": False,
    },
    "strict": True,
}


@dataclass(frozen=True)
class StructuredProviderResult(Generic[StructuredResultT]):
    """Returns one parsed provider result with bounded usage metadata."""

    value: StructuredResultT
    input_tokens: int
    output_tokens: int
    latency_ms: int


class OpenAIProvider:
    """Owns Responses API calls and keeps provider details out of agent flows."""

    def __init__(self, settings: Settings, client: Any | None = None) -> None:
        self._settings = settings
        self._client = client
        self._owns_client = client is None

    @property
    def provider_name(self) -> str:
        return "openai"

    @property
    def model_name(self) -> str:
        return self._settings.openai_model

    async def close(self) -> None:
        """Close only the lazily-created provider client owned by this adapter."""

        if self._client is None or not self._owns_client:
            return
        close = getattr(self._client, "close", None)
        if close is not None:
            await close()
        self._client = None

    async def customer_service_answer(
        self,
        *,
        instructions: str,
        input_items: list[dict[str, object]],
        result_type: type[StructuredResultT],
        maximum_output_tokens: int,
        correlation_id: str,
    ) -> StructuredProviderResult[StructuredResultT]:
        """Run one stored-disabled, tool-free structured customer-service request."""

        if (
            not isinstance(instructions, str)
            or not instructions.strip()
            or len(instructions) > 8_000
            or not 64 <= maximum_output_tokens <= 2_000
            or not isinstance(result_type, type)
            or not issubclass(result_type, BaseModel)
        ):
            raise invalid_provider_response()
        try:
            encoded_input = json.dumps(
                input_items,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
        except (TypeError, ValueError) as error:
            raise invalid_provider_response() from error
        if not 1 <= len(encoded_input.encode("utf-8")) <= 64_000:
            raise invalid_provider_response()

        started = time.monotonic()
        operation = "customer_service_answer"
        try:
            response = await self._responses().parse(
                model=self._settings.openai_model,
                instructions=instructions,
                input=input_items,
                text_format=result_type,
                max_output_tokens=maximum_output_tokens,
                truncation="disabled",
                store=False,
            )
            value = _parsed_result(response, result_type)
            input_tokens, output_tokens = _bounded_usage(response)
            if output_tokens > maximum_output_tokens:
                raise invalid_provider_response()
            latency_ms = max(0, round((time.monotonic() - started) * 1_000))
            self._log_success(operation, correlation_id, started, response)
            return StructuredProviderResult(
                value=value,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                latency_ms=latency_ms,
            )
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

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
    ) -> DiscoveryProviderResult:
        """Run one stored-disabled discovery ReAct model step with allowlisted tools."""

        if (
            not isinstance(instructions, str)
            or not instructions.strip()
            or len(instructions.encode("utf-8")) > 16_000
            or not 64 <= maximum_output_tokens <= 800
            or not 1 <= maximum_tool_calls <= 6
            or not tools
        ):
            raise invalid_provider_response()
        try:
            encoded_input = json.dumps(
                input_items,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            encoded_tools = json.dumps(
                tools,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
        except (TypeError, ValueError) as error:
            raise invalid_provider_response() from error
        if (
            not 1 <= len(encoded_input.encode("utf-8")) <= 64_000
            or not 1 <= len(encoded_tools.encode("utf-8")) <= 32_000
        ):
            raise invalid_provider_response()

        allowed_tool_names = {
            item.get("name")
            for item in tools
            if item.get("type") == "function"
            and item.get("strict") is True
            and isinstance(item.get("parameters"), dict)
            and item.get("name") in _DISCOVERY_TOOL_NAMES
        }
        if len(allowed_tool_names) != len(tools):
            raise invalid_provider_response()

        started = time.monotonic()
        operation = "marketplace_discovery_turn"
        try:
            request_timeout = _bounded_discovery_timeout(
                timeout_seconds,
                fallback=self._settings.openai_timeout_seconds,
            )
            request: dict[str, object] = {
                "model": self._settings.openai_model,
                "instructions": instructions,
                "input": input_items,
                "tools": tools,
                "tool_choice": "auto",
                "max_output_tokens": maximum_output_tokens,
                "max_tool_calls": maximum_tool_calls,
                "truncation": "disabled",
                "store": False,
                "timeout": request_timeout,
            }
            if self._settings.openai_model.startswith("gpt-5"):
                # Discovery model calls are tightly budgeted; minimal reasoning
                # keeps tool selection inside that application-owned ceiling.
                request["reasoning"] = {"effort": "minimal"}
            streamed_text: str | None = None
            if on_text_delta is None:
                response = await self._responses().create(**request)
            else:
                parts: list[str] = []
                completed = False
                async with self._responses().stream(**request) as stream:
                    async for event in stream:
                        event_type = getattr(event, "type", None)
                        if event_type == "response.output_text.delta":
                            delta = getattr(event, "delta", None)
                            if not isinstance(delta, str) or not delta:
                                raise invalid_provider_response()
                            parts.append(delta)
                            if len("".join(parts).encode("utf-8")) > 8_000:
                                raise invalid_provider_response()
                            await on_text_delta(delta)
                        elif event_type == "response.completed":
                            completed = True
                        elif event_type in {"response.failed", "response.incomplete", "error"}:
                            raise invalid_provider_response()
                        # Reasoning and lifecycle events are intentionally ignored.
                    response = await stream.get_final_response()
                if not completed:
                    raise invalid_provider_response()
                streamed_text = "".join(parts)
            if getattr(response, "status", "completed") != "completed":
                raise invalid_provider_response()
            tool_calls: list[DiscoveryProviderToolCall] = []
            for item in getattr(response, "output", ()):
                if getattr(item, "type", None) != "function_call":
                    continue
                name = getattr(item, "name", None)
                call_id = getattr(item, "call_id", None)
                raw_arguments = getattr(item, "arguments", None)
                if (
                    name not in allowed_tool_names
                    or not isinstance(call_id, str)
                    or not 1 <= len(call_id) <= 200
                    or not isinstance(raw_arguments, str)
                    or len(raw_arguments.encode("utf-8")) > 16_000
                ):
                    raise invalid_provider_response()
                try:
                    arguments = json.loads(raw_arguments)
                except (TypeError, ValueError) as error:
                    raise invalid_provider_response() from error
                if not isinstance(arguments, dict):
                    raise invalid_provider_response()
                tool_calls.append(
                    DiscoveryProviderToolCall(
                        call_id=call_id,
                        name=name,
                        arguments=arguments,
                    )
                )
            content = getattr(response, "output_text", "") or ""
            if not isinstance(content, str) or len(content.encode("utf-8")) > 8_000:
                raise invalid_provider_response()
            if (
                len(tool_calls) > maximum_tool_calls
                or (not tool_calls and not content.strip())
                or (tool_calls and content.strip())
                or (streamed_text is not None and streamed_text != content)
            ):
                raise invalid_provider_response()
            input_tokens, output_tokens = _bounded_usage(response)
            if output_tokens > maximum_output_tokens:
                raise invalid_provider_response()
            result = DiscoveryProviderResult(
                content=content,
                tool_calls=tuple(tool_calls),
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                latency_ms=max(0, round((time.monotonic() - started) * 1_000)),
            )
            self._log_success(operation, correlation_id, started, response)
            return result
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

    async def discovery_answer_stream(
        self,
        *,
        instructions: str,
        facts: dict[str, object],
        maximum_output_tokens: int,
        correlation_id: str,
        on_text_delta: Callable[[str], Awaitable[None]],
        timeout_seconds: float | None = None,
    ) -> DiscoveryAnswerStreamResult:
        """Stream only a designated tool-free answer over already-validated facts."""

        if (
            not isinstance(instructions, str)
            or not instructions.strip()
            or len(instructions.encode("utf-8")) > 8_000
            or not isinstance(facts, dict)
            or not 64 <= maximum_output_tokens <= 800
            or not callable(on_text_delta)
        ):
            raise invalid_provider_response()
        try:
            encoded_facts = json.dumps(
                facts,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
        except (TypeError, ValueError) as error:
            raise invalid_provider_response() from error
        if not 2 <= len(encoded_facts.encode("utf-8")) <= 32_000:
            raise invalid_provider_response()

        started = time.monotonic()
        operation = "marketplace_discovery_final_stream"
        try:
            request: dict[str, object] = {
                "model": self._settings.openai_model,
                "instructions": instructions,
                "input": [{"role": "user", "content": encoded_facts}],
                "max_output_tokens": maximum_output_tokens,
                "truncation": "disabled",
                "store": False,
                "timeout": _bounded_discovery_timeout(
                    timeout_seconds,
                    fallback=self._settings.openai_timeout_seconds,
                ),
            }
            if self._settings.openai_model.startswith("gpt-5"):
                request["reasoning"] = {"effort": "minimal"}
            text_parts: list[str] = []
            done_text: str | None = None
            completed = False
            async with self._responses().stream(**request) as stream:
                async for event in stream:
                    event_type = getattr(event, "type", None)
                    if event_type == "response.output_text.delta":
                        delta = getattr(event, "delta", None)
                        if not isinstance(delta, str) or not delta:
                            raise invalid_provider_response()
                        text_parts.append(delta)
                        if len("".join(text_parts)) > 800:
                            raise invalid_provider_response()
                        await on_text_delta(delta)
                    elif event_type == "response.output_text.done":
                        value = getattr(event, "text", None)
                        if not isinstance(value, str):
                            raise invalid_provider_response()
                        done_text = value
                    elif event_type == "response.completed":
                        completed = True
                    elif event_type in {
                        "response.failed",
                        "response.incomplete",
                        "error",
                    }:
                        raise invalid_provider_response()
                    # All reasoning, summaries, tool, and lifecycle details are private.
                response = await stream.get_final_response()
            text = "".join(text_parts)
            if (
                not completed
                or not text.strip()
                or done_text != text
                or getattr(response, "status", "completed") != "completed"
            ):
                raise invalid_provider_response()
            input_tokens, output_tokens = _bounded_usage(response)
            if output_tokens > maximum_output_tokens:
                raise invalid_provider_response()
            self._log_success(operation, correlation_id, started, response)
            return DiscoveryAnswerStreamResult(
                text=text,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                latency_ms=max(0, round((time.monotonic() - started) * 1_000)),
            )
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

    async def structured_text(
        self, prompt: str, *, correlation_id: str
    ) -> TextSmokeResult:
        """Verify strict structured text output without exposing a public endpoint."""

        started = time.monotonic()
        operation = "structured_text"
        try:
            response = await self._responses().parse(
                model=self._settings.openai_model,
                instructions=(
                    "Return a concise summary and identify the input language. "
                    "Do not add facts that are absent from the input."
                ),
                input=prompt,
                text_format=TextSmokeResult,
                store=False,
            )
            result = _parsed_result(response, TextSmokeResult)
            self._log_success(operation, correlation_id, started, response)
            return result
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

    async def structured_image(
        self, image_data_url: str, *, correlation_id: str
    ) -> ImageSmokeResult:
        """Verify vision input with conservative, uncertainty-aware output."""

        started = time.monotonic()
        operation = "structured_image"
        try:
            response = await self._responses().parse(
                model=self._settings.openai_model,
                instructions=(
                    "Describe only clearly visible objects. Put uncertain details in "
                    "uncertainties and safety or authenticity concerns in warnings."
                ),
                input=[
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "input_text",
                                "text": "Inspect this non-production listing image.",
                            },
                            {"type": "input_image", "image_url": image_data_url},
                        ],
                    }
                ],
                text_format=ImageSmokeResult,
                store=False,
            )
            result = _parsed_result(response, ImageSmokeResult)
            self._log_success(operation, correlation_id, started, response)
            return result
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

    async def function_tool(self, *, correlation_id: str) -> ToolSmokeResult:
        """Verify one strictly allowlisted function call against fixed demo data."""

        started = time.monotonic()
        operation = "function_tool"
        try:
            initial_input: list[Any] = [
                {
                    "role": "user",
                    "content": (
                        f"Use get_demo_listing for {DEMO_LISTING_ID}, then state its "
                        "title and whether its price is negotiable."
                    ),
                }
            ]
            first_response = await self._responses().create(
                model=self._settings.openai_model,
                input=initial_input,
                tools=[DEMO_LISTING_TOOL],
                tool_choice="required",
                max_tool_calls=1,
                store=False,
            )
            function_calls = [
                item
                for item in first_response.output
                if getattr(item, "type", None) == "function_call"
            ]
            if len(function_calls) != 1:
                raise invalid_provider_response()

            function_call = function_calls[0]
            if function_call.name != "get_demo_listing":
                raise invalid_provider_response()
            try:
                arguments = DemoListingArguments.model_validate_json(
                    function_call.arguments
                )
            except ValueError as error:
                raise invalid_provider_response() from error
            if arguments.listing_id != DEMO_LISTING_ID:
                raise invalid_provider_response()

            tool_output = {
                "id": DEMO_LISTING_ID,
                "title": "Demo walnut writing desk",
                "price": {"amount": "125.00", "currency": "USD"},
                "negotiable": True,
                "status": "ACTIVE",
            }
            follow_up_input = [
                *initial_input,
                *[_serialize_output_item(item) for item in first_response.output],
                {
                    "type": "function_call_output",
                    "call_id": function_call.call_id,
                    "output": json.dumps(tool_output),
                },
            ]
            final_response = await self._responses().parse(
                model=self._settings.openai_model,
                input=follow_up_input,
                tools=[DEMO_LISTING_TOOL],
                tool_choice="none",
                text_format=ToolSmokeResult,
                store=False,
            )
            result = _parsed_result(final_response, ToolSmokeResult)
            if result.listing_id != DEMO_LISTING_ID:
                raise invalid_provider_response()
            self._log_success(operation, correlation_id, started, final_response)
            return result
        except LlmProviderError as error:
            self._log_failure(operation, correlation_id, started, error)
            raise
        except Exception as error:
            mapped = classify_openai_error(error)
            self._log_failure(operation, correlation_id, started, mapped)
            raise mapped from error

    def _responses(self) -> Any:
        if not self._settings.openai_configured:
            raise provider_not_configured()
        if self._client is None:
            self._client = AsyncOpenAI(
                api_key=self._settings.openai_api_key,
                timeout=self._settings.openai_timeout_seconds,
                max_retries=self._settings.openai_max_retries,
            )
        return self._client.responses

    def _log_success(
        self, operation: str, correlation_id: str, started: float, response: Any
    ) -> None:
        input_tokens, output_tokens, total_tokens = _token_usage(response)
        LOGGER.info(
            "OpenAI request completed",
            extra={
                "event": "openai_request_completed",
                "operation": operation,
                "model": self._settings.openai_model,
                "correlation_id": correlation_id,
                "latency_ms": round((time.monotonic() - started) * 1000),
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "total_tokens": total_tokens,
            },
        )

    def _log_failure(
        self,
        operation: str,
        correlation_id: str,
        started: float,
        error: LlmProviderError,
    ) -> None:
        LOGGER.warning(
            "OpenAI request failed",
            extra={
                "event": "openai_request_failed",
                "operation": operation,
                "model": self._settings.openai_model,
                "correlation_id": correlation_id,
                "latency_ms": round((time.monotonic() - started) * 1000),
                "error_code": error.code.value,
                "retryable": error.retryable,
            },
        )


def _parsed_result(response: Any, result_type: type[Any]) -> Any:
    status = getattr(response, "status", None)
    if status is not None and status != "completed":
        raise invalid_provider_response()
    parsed = getattr(response, "output_parsed", None)
    if parsed is None:
        raise invalid_provider_response()
    try:
        return result_type.model_validate(parsed)
    except ValidationError as error:
        raise invalid_provider_response() from error


def _serialize_output_item(item: Any) -> Any:
    if hasattr(item, "model_dump"):
        return item.model_dump(mode="json")
    return item


def _bounded_discovery_timeout(
    timeout_seconds: float | None,
    *,
    fallback: float,
) -> float:
    """Keep provider transport timeouts aligned with the discovery model budget."""

    value = fallback if timeout_seconds is None else timeout_seconds
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise invalid_provider_response()
    timeout = float(value)
    if not 0 < timeout <= fallback <= 60:
        raise invalid_provider_response()
    return timeout


def _token_usage(response: Any) -> tuple[int | None, int | None, int | None]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return None, None, None
    return (
        getattr(usage, "input_tokens", None),
        getattr(usage, "output_tokens", None),
        getattr(usage, "total_tokens", None),
    )


def _bounded_usage(response: Any) -> tuple[int, int]:
    input_tokens, output_tokens, _ = _token_usage(response)
    normalized: list[int] = []
    for value in (input_tokens, output_tokens):
        if value is None:
            normalized.append(0)
        elif (
            isinstance(value, bool)
            or not isinstance(value, int)
            or not 0 <= value <= 1_000_000
        ):
            raise invalid_provider_response()
        else:
            normalized.append(value)
    return normalized[0], normalized[1]
