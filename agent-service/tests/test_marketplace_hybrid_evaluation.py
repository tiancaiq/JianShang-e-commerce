from __future__ import annotations

import unittest
from pathlib import Path

from msb_agent_service.marketplace_hybrid_evaluation import (
    evaluate_hybrid_fixture,
)


class MarketplaceHybridEvaluationTest(unittest.TestCase):
    def test_report_is_deterministic_non_production_and_release_blocked(self) -> None:
        fixture = (
            Path(__file__).parents[1]
            / "evals"
            / "ai_disc_search_p0_07_hybrid_baseline_v1.json"
        )

        first = evaluate_hybrid_fixture(fixture)
        second = evaluate_hybrid_fixture(fixture)

        self.assertEqual(first, second)
        self.assertEqual(4, first.caseCount)
        self.assertGreater(first.hybridRecall, first.lexicalRecall)
        self.assertTrue(first.hybridNonRegression)
        self.assertEqual(0, first.externalProviderRequests)
        self.assertEqual(0, first.externalNetworkCalls)
        self.assertEqual("NON_PRODUCTION_SYNTHETIC", first.latencyEvidence)
        self.assertEqual("BLOCKED", first.releaseDecision)
        self.assertFalse(first.releaseAuthorized)
        self.assertEqual(64, len(first.digest))


if __name__ == "__main__":
    unittest.main()
