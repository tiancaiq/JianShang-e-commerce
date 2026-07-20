from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
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
