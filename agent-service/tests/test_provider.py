from __future__ import annotations

import unittest
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
    def __init__(self, parsed_results: list[Any] | None = None) -> None:
        self.parsed_results = parsed_results or []
        self.parse_calls: list[dict[str, Any]] = []
        self.create_calls: list[dict[str, Any]] = []
        self.status: str | None = None

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
        return SimpleNamespace(output=[FakeFunctionCall()], usage=None)


class FakeClient:
    def __init__(self, responses: FakeResponses) -> None:
        self.responses = responses


class OpenAIProviderTest(unittest.IsolatedAsyncioTestCase):
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
