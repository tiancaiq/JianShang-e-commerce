import unittest

import httpx
from openai import APITimeoutError, AuthenticationError, RateLimitError

from msb_agent_service.errors import ProviderErrorCode, classify_openai_error


class ProviderErrorClassificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.request = httpx.Request(
            "POST", "https://api.openai.com/v1/responses"
        )

    def test_timeout_is_retryable(self) -> None:
        actual = classify_openai_error(APITimeoutError(self.request))

        self.assertEqual(ProviderErrorCode.TIMED_OUT, actual.code)
        self.assertTrue(actual.retryable)
        self.assertEqual(504, actual.status_code)

    def test_authentication_failure_is_not_retryable(self) -> None:
        response = httpx.Response(401, request=self.request)
        error = AuthenticationError("invalid", response=response, body={})

        actual = classify_openai_error(error)

        self.assertEqual(ProviderErrorCode.AUTHENTICATION_FAILED, actual.code)
        self.assertFalse(actual.retryable)
        self.assertNotIn("invalid", str(actual))

    def test_quota_and_rate_limit_have_distinct_codes(self) -> None:
        response = httpx.Response(429, request=self.request)
        quota_error = RateLimitError(
            "quota",
            response=response,
            body={"code": "insufficient_quota"},
        )
        rate_error = RateLimitError("busy", response=response, body={})

        quota = classify_openai_error(quota_error)
        rate = classify_openai_error(rate_error)

        self.assertEqual(ProviderErrorCode.QUOTA_EXHAUSTED, quota.code)
        self.assertFalse(quota.retryable)
        self.assertEqual(ProviderErrorCode.RATE_LIMITED, rate.code)
        self.assertTrue(rate.retryable)


if __name__ == "__main__":
    unittest.main()

