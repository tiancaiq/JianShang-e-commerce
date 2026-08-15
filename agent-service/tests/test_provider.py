from __future__ import annotations

import unittest
import asyncio
from types import SimpleNamespace
from typing import Any

from msb_agent_service.config import Settings
from msb_agent_service.customer_service_orchestration import ModelAnswerCandidate
from msb_agent_service.errors import LlmProviderError, ProviderErrorCode
from msb_agent_service.provider import DEMO_LISTING_ID, OpenAIProvider
from msb_agent_service.schemas import ImageSmokeResult, TextSmokeResult, ToolSmokeResult


class FakeFunctionCall:
    type = "function_call"
    name = "get_demo_listing"
    arguments = f'{{"listing_id":"{DEMO_LISTING_ID}"}}'
    call_id = "call-demo-1"

    def model_dump(self, mode: str = "python") -> dict[str, Any]:
        return {
            "type": self.type,
            "name": self.name,
            "arguments": self.arguments,
            "call_id": self.call_id,
        }


class FakeResponses:
    def __init__(
        self,
        parsed_results: list[Any] | None = None,
        *,
        create_output: list[Any] | None = None,
        output_text: str = "",
    ) -> None:
        self.parsed_results = parsed_results or []
        self.create_output = create_output
        self.output_text = output_text
        self.parse_calls: list[dict[str, Any]] = []
        self.create_calls: list[dict[str, Any]] = []
        self.status: str | None = None
        self.stream_calls: list[dict[str, Any]] = []
        self.stream_events: list[Any] = []
        self.stream_closed = False

    async def parse(self, **kwargs: Any) -> Any:
        self.parse_calls.append(kwargs)
        parsed = self.parsed_results.pop(0)
        return SimpleNamespace(
            output_parsed=parsed,
            usage=None,
            status=self.status,
        )

    async def create(self, **kwargs: Any) -> Any:
        self.create_calls.append(kwargs)
        return SimpleNamespace(
            output=self.create_output or [FakeFunctionCall()],
            output_text=self.output_text,
            usage=None,
            status="completed",
        )

    def stream(self, **kwargs: Any) -> Any:
        self.stream_calls.append(kwargs)
        owner = self

        class Stream:
            def __aiter__(self) -> Any:
                return self

            async def __anext__(self) -> Any:
                if not owner.stream_events:
                    raise StopAsyncIteration
                return owner.stream_events.pop(0)

            async def get_final_response(self) -> Any:
                return SimpleNamespace(
                    status="completed",
                    usage=None,
                    output=owner.create_output or [],
                    output_text=owner.output_text,
                )

        class Manager:
            async def __aenter__(self) -> Any:
                return Stream()

            async def __aexit__(self, *_: Any) -> None:
                owner.stream_closed = True

        return Manager()


class FakeClient:
    def __init__(self, responses: FakeResponses) -> None:
        self.responses = responses


async def _append_delta(target: list[str], delta: str) -> None:
    target.append(delta)


class OpenAIProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_discovery_final_answer_stream_forwards_only_output_text(self) -> None:
        responses = FakeResponses()
        responses.stream_events = [
            SimpleNamespace(type="response.reasoning_text.delta", delta="private"),
            SimpleNamespace(type="response.output_text.delta", delta="Verified "),
            SimpleNamespace(type="response.output_text.delta", delta="matches."),
            SimpleNamespace(type="response.output_text.done", text="Verified matches."),
            SimpleNamespace(type="response.completed"),
        ]
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )
        deltas: list[str] = []

        result = await provider.discovery_answer_stream(
            instructions="Write only from validated public facts.",
            facts={"outcome": "NO_RESULTS", "recommendations": []},
            maximum_output_tokens=800,
            correlation_id="disc-final-stream-1",
            on_text_delta=lambda delta: _append_delta(deltas, delta),
            timeout_seconds=4.0,
        )

        self.assertEqual("Verified matches.", result.text)
        self.assertEqual(["Verified ", "matches."], deltas)
        self.assertNotIn("private", "".join(deltas))
        self.assertTrue(responses.stream_closed)
        self.assertFalse(responses.stream_calls[0]["store"])
        self.assertNotIn("tools", responses.stream_calls[0])

    async def test_discovery_final_answer_stream_closes_on_cancellation(self) -> None:
        responses = FakeResponses()
        responses.stream_events = [
            SimpleNamespace(type="response.output_text.delta", delta="Partial"),
        ]
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        async def cancel(_: str) -> None:
            raise asyncio.CancelledError

        with self.assertRaises(asyncio.CancelledError):
            await provider.discovery_answer_stream(
                instructions="Write only from validated public facts.",
                facts={"outcome": "NO_RESULTS", "recommendations": []},
                maximum_output_tokens=800,
                correlation_id="disc-final-stream-cancel",
                on_text_delta=cancel,
            )
        self.assertTrue(responses.stream_closed)

    async def test_discovery_chat_uses_strict_stored_disabled_tool_boundary(
        self,
    ) -> None:
        function_call = SimpleNamespace(
            type="function_call",
            name="SEARCH_INDIVIDUAL",
            arguments='{"q":"pillow","limit":20}',
            call_id="discovery-call-1",
        )
        responses = FakeResponses(create_output=[function_call])
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        result = await provider.discovery_chat(
            instructions="Use only the supplied public marketplace tools.",
            input_items=[{"role": "user", "content": "Find a pillow"}],
            tools=[
                {
                    "type": "function",
                    "name": "SEARCH_INDIVIDUAL",
                    "description": "Search public individual listings.",
                    "parameters": {
                        "type": "object",
                        "properties": {"q": {"type": "string"}},
                        "required": ["q"],
                        "additionalProperties": False,
                    },
                    "strict": True,
                }
            ],
            maximum_output_tokens=800,
            maximum_tool_calls=6,
            correlation_id="disc-provider-1",
            timeout_seconds=4.5,
        )

        self.assertEqual("SEARCH_INDIVIDUAL", result.tool_calls[0].name)
        call = responses.create_calls[0]
        self.assertFalse(call["store"])
        self.assertEqual("disabled", call["truncation"])
        self.assertEqual("auto", call["tool_choice"])
        self.assertEqual({"effort": "minimal"}, call["reasoning"])
        self.assertEqual(800, call["max_output_tokens"])
        self.assertEqual(6, call["max_tool_calls"])
        self.assertEqual(4.5, call["timeout"])

    async def test_discovery_chat_rejects_invalid_request_timeout(self) -> None:
        responses = FakeResponses(
            create_output=[
                SimpleNamespace(
                    type="function_call",
                    name="SEARCH_INDIVIDUAL",
                    arguments='{"q":"chair","limit":20}',
                    call_id="discovery-call-timeout-1",
                )
            ]
        )
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        with self.assertRaises(LlmProviderError) as raised:
            await provider.discovery_chat(
                instructions="Use only the supplied public marketplace tools.",
                input_items=[{"role": "user", "content": "Find a chair"}],
                tools=[
                    {
                        "type": "function",
                        "name": "SEARCH_INDIVIDUAL",
                        "parameters": {
                            "type": "object",
                            "properties": {"q": {"type": "string"}},
                            "required": ["q"],
                            "additionalProperties": False,
                        },
                        "strict": True,
                    }
                ],
                maximum_output_tokens=800,
                maximum_tool_calls=6,
                correlation_id="disc-provider-timeout-invalid",
                timeout_seconds=0,
            )

        self.assertEqual(ProviderErrorCode.INVALID_RESPONSE, raised.exception.code)
        self.assertEqual([], responses.create_calls)

    async def test_discovery_omits_reasoning_for_non_reasoning_model(self) -> None:
        responses = FakeResponses(
            create_output=[
                SimpleNamespace(
                    type="function_call",
                    name="SEARCH_INDIVIDUAL",
                    arguments='{"q":"chair","limit":20}',
                    call_id="discovery-call-fast-1",
                )
            ]
        )
        provider = OpenAIProvider(
            Settings(
                openai_api_key="offline-test-placeholder",
                openai_model="gpt-4.1-mini",
            ),
            FakeClient(responses),
        )

        await provider.discovery_chat(
            instructions="Use only the supplied public marketplace tools.",
            input_items=[{"role": "user", "content": "Find a chair"}],
            tools=[
                {
                    "type": "function",
                    "name": "SEARCH_INDIVIDUAL",
                    "parameters": {
                        "type": "object",
                        "properties": {"q": {"type": "string"}},
                        "required": ["q"],
                        "additionalProperties": False,
                    },
                    "strict": True,
                }
            ],
            maximum_output_tokens=800,
            maximum_tool_calls=6,
            correlation_id="disc-provider-fast-1",
        )

        self.assertNotIn("reasoning", responses.create_calls[0])

    async def test_discovery_accepts_message_only_for_application_fallback(self) -> None:
        responses = FakeResponses(
            create_output=[SimpleNamespace(type="message")],
            output_text="Untrusted provider prose",
        )
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        result = await provider.discovery_chat(
            instructions="Use only the supplied public marketplace tools.",
            input_items=[{"role": "user", "content": "Find a chair"}],
            tools=[
                {
                    "type": "function",
                    "name": "SEARCH_INDIVIDUAL",
                    "parameters": {
                        "type": "object",
                        "properties": {"q": {"type": "string"}},
                        "required": ["q"],
                        "additionalProperties": False,
                    },
                    "strict": True,
                }
            ],
            maximum_output_tokens=800,
            maximum_tool_calls=6,
            correlation_id="disc-provider-message-1",
        )

        self.assertEqual((), result.tool_calls)
        self.assertEqual("Untrusted provider prose", result.content)

    async def test_discovery_chat_streams_natural_content_without_reasoning(self) -> None:
        responses = FakeResponses(output_text="Hello from the marketplace assistant.")
        responses.stream_events = [
            SimpleNamespace(type="response.reasoning_text.delta", delta="private"),
            SimpleNamespace(type="response.output_text.delta", delta="Hello from "),
            SimpleNamespace(type="response.output_text.delta", delta="the marketplace assistant."),
            SimpleNamespace(type="response.completed"),
        ]
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )
        deltas: list[str] = []

        result = await provider.discovery_chat(
            instructions="Answer naturally or call exactly one registered tool.",
            input_items=[{"role": "user", "content": "hi"}],
            tools=[{
                "type": "function",
                "name": "CHECK_AVAILABILITY",
                "parameters": {
                    "type": "object",
                    "properties": {"category": {"type": "string"}},
                    "required": ["category"],
                    "additionalProperties": False,
                },
                "strict": True,
            }],
            maximum_output_tokens=800,
            maximum_tool_calls=1,
            correlation_id="disc-provider-natural-stream",
            on_text_delta=lambda delta: _append_delta(deltas, delta),
        )

        self.assertEqual("Hello from the marketplace assistant.", result.content)
        self.assertEqual(["Hello from ", "the marketplace assistant."], deltas)
        self.assertNotIn("private", "".join(deltas))
        self.assertEqual("auto", responses.stream_calls[0]["tool_choice"])

    async def test_customer_service_answer_is_typed_bounded_and_not_stored(
        self,
    ) -> None:
        candidate = ModelAnswerCandidate(
            body="The listing describes a recently replaced chain.",
            resolutionType="ANSWERED",
            sources=[
                {
                    "sourceType": "LISTING",
                    "sourceId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
                    "sourceVersion": "12",
                    "label": "Current listing",
                }
            ],
            actions=[],
        )
        responses = FakeResponses([candidate])
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        result = await provider.customer_service_answer(
            instructions="Use only the supplied listing context.",
            input_items=[
                {
                    "role": "user",
                    "content": [{"type": "input_text", "text": "{}"}],
                }
            ],
            result_type=ModelAnswerCandidate,
            maximum_output_tokens=400,
            correlation_id="corr-cs-1",
        )

        self.assertEqual(candidate, result.value)
        call = responses.parse_calls[0]
        self.assertIs(ModelAnswerCandidate, call["text_format"])
        self.assertEqual(400, call["max_output_tokens"])
        self.assertEqual("disabled", call["truncation"])
        self.assertFalse(call["store"])
        self.assertNotIn("tools", call)

    async def test_customer_service_malformed_output_is_invalid_response(
        self,
    ) -> None:
        responses = FakeResponses(
            [
                {
                    "body": "Unsupported",
                    "resolutionType": "ANSWERED",
                    "sources": [],
                    "actions": [],
                    "unexpected": True,
                }
            ]
        )
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        with self.assertRaises(LlmProviderError) as raised:
            await provider.customer_service_answer(
                instructions="Use supplied sources.",
                input_items=[
                    {
                        "role": "user",
                        "content": [{"type": "input_text", "text": "{}"}],
                    }
                ],
                result_type=ModelAnswerCandidate,
                maximum_output_tokens=400,
                correlation_id="corr-cs-invalid",
            )

        self.assertEqual(
            ProviderErrorCode.INVALID_RESPONSE,
            raised.exception.code,
        )

    async def test_customer_service_incomplete_response_fails_closed(
        self,
    ) -> None:
        responses = FakeResponses([candidate := ModelAnswerCandidate(
            body="Incomplete",
            resolutionType="UNKNOWN",
            sources=[
                {
                    "sourceType": "LISTING",
                    "sourceId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
                    "sourceVersion": "12",
                    "label": "Current listing",
                }
            ],
            actions=[],
        )])
        responses.status = "incomplete"
        provider = OpenAIProvider(
            Settings(openai_api_key="offline-test-placeholder"),
            FakeClient(responses),
        )

        with self.assertRaises(LlmProviderError) as raised:
            await provider.customer_service_answer(
                instructions="Use supplied sources.",
                input_items=[
                    {
                        "role": "user",
                        "content": [{"type": "input_text", "text": "{}"}],
                    }
                ],
                result_type=type(candidate),
                maximum_output_tokens=400,
                correlation_id="corr-cs-incomplete",
            )

        self.assertEqual(
            ProviderErrorCode.INVALID_RESPONSE,
            raised.exception.code,
        )

    async def test_missing_key_fails_without_touching_client(self) -> None:
        responses = FakeResponses([])
        provider = OpenAIProvider(Settings(openai_api_key=None), FakeClient(responses))

        with self.assertRaises(LlmProviderError) as raised:
            await provider.structured_text("hello", correlation_id="corr-1")

        self.assertEqual(ProviderErrorCode.NOT_CONFIGURED, raised.exception.code)
        self.assertEqual([], responses.parse_calls)

    async def test_structured_text_uses_responses_parse_and_disables_storage(self) -> None:
        expected = TextSmokeResult(language="English", summary="A short summary.")
        responses = FakeResponses([expected])
        provider = OpenAIProvider(
            Settings(openai_api_key="configured-for-test"), FakeClient(responses)
        )

        actual = await provider.structured_text("hello", correlation_id="corr-2")

        self.assertEqual(expected, actual)
        self.assertFalse(responses.parse_calls[0]["store"])
        self.assertIs(TextSmokeResult, responses.parse_calls[0]["text_format"])

    async def test_structured_image_sends_image_as_multimodal_input(self) -> None:
        expected = ImageSmokeResult(
            visible_objects=["desk"], uncertainties=[], warnings=[]
        )
        responses = FakeResponses([expected])
        provider = OpenAIProvider(
            Settings(openai_api_key="configured-for-test"), FakeClient(responses)
        )
        data_url = "data:image/png;base64,aGVsbG8="

        actual = await provider.structured_image(data_url, correlation_id="corr-3")

        self.assertEqual(expected, actual)
        content = responses.parse_calls[0]["input"][0]["content"]
        self.assertEqual("input_image", content[1]["type"])
        self.assertEqual(data_url, content[1]["image_url"])

    async def test_function_tool_executes_only_fixed_allowlisted_tool(self) -> None:
        expected = ToolSmokeResult(
            listing_id=DEMO_LISTING_ID,
            answer="Demo walnut writing desk; the price is negotiable.",
            source="get_demo_listing",
        )
        responses = FakeResponses([expected])
        provider = OpenAIProvider(
            Settings(openai_api_key="configured-for-test"), FakeClient(responses)
        )

        actual = await provider.function_tool(correlation_id="corr-4")

        self.assertEqual(expected, actual)
        self.assertEqual("required", responses.create_calls[0]["tool_choice"])
        follow_up = responses.parse_calls[0]["input"]
        tool_outputs = [item for item in follow_up if item.get("type") == "function_call_output"]
        self.assertEqual(1, len(tool_outputs))
        self.assertIn(DEMO_LISTING_ID, tool_outputs[0]["output"])
        self.assertEqual("none", responses.parse_calls[0]["tool_choice"])


if __name__ == "__main__":
    unittest.main()
