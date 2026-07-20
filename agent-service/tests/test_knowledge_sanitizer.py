from __future__ import annotations

import unittest

from msb_agent_service.knowledge_sanitizer import (
    KnowledgeContentError,
    canonical_decimal,
    canonical_listing_source_hash,
    sanitize_listing,
    verify_listing_source_hash,
)
from msb_agent_service.knowledge_source_client import ListingKnowledgeSource

LISTING_ID = "01L00000000000000000000001"


def source(**content_overrides: str) -> ListingKnowledgeSource:
    content = {
        "title": "Café Desk",
        "description": "Line 1",
        "price": {"amount": "250.0000", "currency": "USD"},
        "publicLocation": {"city": "Irvine", "region": "CA"},
    }
    content.update(content_overrides)
    return ListingKnowledgeSource.model_validate(
        {
            "sourceType": "LISTING",
            "sourceId": LISTING_ID,
            "sourceVersion": "1",
            "supersedesVersion": None,
            "lifecycle": "ACTIVE",
            "visibility": "PUBLIC",
            "language": "und",
            "effectiveFrom": "2026-07-19T00:00:00Z",
            "invalidatedAt": None,
            "sourcePublishedAt": "2026-07-19T00:00:00Z",
            "contentHash": "0" * 64,
            "content": content,
        }
    )


class ListingKnowledgeSanitizerTest(unittest.TestCase):
    def test_hash_matches_product_service_ordered_raw_contract(self) -> None:
        listing = source()

        self.assertEqual(
            "9e15cc5327915dc8367b8d997fba1eaf"
            "39d361a19e9d4e2d2fcb0fa93c7b0e34",
            canonical_listing_source_hash(listing),
        )
        self.assertEqual("250", canonical_decimal("250.0000"))
        self.assertEqual("0", canonical_decimal("0.000"))

    def test_hash_is_verified_before_sanitized_text_changes(self) -> None:
        listing = source(title="Ｆｕｌｌ\u200b Width")
        raw_hash = canonical_listing_source_hash(listing)
        verified = listing.model_copy(update={"content_hash": raw_hash})

        self.assertEqual(raw_hash, verify_listing_source_hash(verified))
        self.assertEqual("Full Width", sanitize_listing(verified).title)

        with self.assertRaisesRegex(
            KnowledgeContentError,
            "SOURCE_CONTENT_HASH_MISMATCH",
        ):
            verify_listing_source_hash(
                verified.model_copy(update={"content_hash": "f" * 64})
            )

    def test_sanitizer_normalizes_controls_and_whitespace(self) -> None:
        listing = source(
            title="  Desk\u202e\t Lamp  ",
            description="First\r\n  line\u200b \t here\r\n\r\n\r\nSecond",
        )

        sanitized = sanitize_listing(listing)

        self.assertEqual("Desk Lamp", sanitized.title)
        self.assertEqual("First\nline here\n\nSecond", sanitized.description)


if __name__ == "__main__":
    unittest.main()
