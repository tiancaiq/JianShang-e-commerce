from __future__ import annotations

import unittest
from pathlib import Path

from msb_agent_service.marketplace_multiconcept_evaluation import (
    evaluate_multiconcept_fixture,
)


class MarketplaceMulticonceptEvaluationTest(unittest.TestCase):
    def test_fixture_is_deterministic_complete_and_never_displays_missing_core(self) -> None:
        fixture = Path(__file__).parents[1] / "evals" / (
            "marketplace_agent_v2_multiconcept_retrieval_v1.json"
        )

        first = evaluate_multiconcept_fixture(fixture)
        second = evaluate_multiconcept_fixture(fixture)

        self.assertEqual(first, second)
        self.assertEqual(10, first.caseCount)
        self.assertEqual(1.0, first.precisionAt3)
        self.assertEqual(0.6, first.precisionAt5)
        self.assertEqual(1.0, first.recallAt10)
        self.assertEqual(1.0, first.meanReciprocalRank)
        self.assertEqual(1.0, first.ndcgAt5)
        self.assertEqual(0.0, first.displayedMissingCorePercentage)
        self.assertEqual(0, first.externalProviderRequests)
        self.assertEqual(0, first.externalNetworkCalls)
        self.assertEqual(64, len(first.digest))


if __name__ == "__main__":
    unittest.main()
