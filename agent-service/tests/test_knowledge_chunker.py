from __future__ import annotations

import unittest

from msb_agent_service.knowledge_chunker import (
    ListingKnowledgeChunker,
    deterministic_chunk_id,
)
from msb_agent_service.knowledge_sanitizer import (
    KnowledgeContentError,
    SanitizedListing,
)


class FakeEncoding:
    def encode(self, text: str) -> list[int]:
        return list(text.encode("utf-8"))


class ListingKnowledgeChunkerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.listing = SanitizedListing(
            title="Walnut standing desk",
            description=" ".join(
                f"Sentence {index} describes the adjustable desk."
                for index in range(12)
            ),
            price_amount="250",
            currency="USD",
            public_city="Irvine",
            public_region="CA",
        )
        self.chunker = ListingKnowledgeChunker(
            model="text-embedding-3-small",
            maximum_tokens=128,
            overlap_tokens=10,
            maximum_chunks=16,
            encoding=FakeEncoding(),
        )

    def test_chunks_are_deterministic_bounded_and_summary_first(self) -> None:
        first = self.chunker.chunk(
            self.listing,
            source_id="01L00000000000000000000001",
            source_version=2,
            language="und",
            content_hash="a" * 64,
        )
        replay = self.chunker.chunk(
            self.listing,
            source_id="01L00000000000000000000001",
            source_version=2,
            language="und",
            content_hash="a" * 64,
        )

        self.assertEqual(first, replay)
        self.assertGreater(len(first), 2)
        self.assertEqual("Listing summary", first[0].section_label)
        self.assertIn("Price: 250 USD", first[0].text)
        self.assertEqual(list(range(len(first))), [item.ordinal for item in first])
        self.assertTrue(all(item.token_count <= 128 for item in first))
        self.assertEqual(len(first), len({item.chunk_id for item in first}))

    def test_chunk_identity_changes_with_immutable_source_identity(self) -> None:
        base = dict(
            source_type="LISTING",
            source_id="01L00000000000000000000001",
            source_version=1,
            language="und",
            ordinal=0,
            content_hash="a" * 64,
        )

        first = deterministic_chunk_id(**base)
        second = deterministic_chunk_id(**{**base, "source_version": 2})

        self.assertEqual(64, len(first))
        self.assertNotEqual(first, second)

    def test_excessive_description_fails_instead_of_truncating(self) -> None:
        chunker = ListingKnowledgeChunker(
            model="text-embedding-3-small",
            maximum_tokens=64,
            overlap_tokens=8,
            maximum_chunks=2,
            encoding=FakeEncoding(),
        )

        with self.assertRaisesRegex(
            KnowledgeContentError,
            "CHUNK_COUNT_LIMIT_EXCEEDED",
        ):
            chunker.chunk(
                self.listing,
                source_id="01L00000000000000000000001",
                source_version=1,
                language="und",
                content_hash="a" * 64,
            )


if __name__ == "__main__":
    unittest.main()
