from __future__ import annotations

import unittest
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock

from pydantic import ValidationError

from msb_agent_service.config import KnowledgeIndexSettings
from msb_agent_service.knowledge_index import KnowledgeIndexWriter
from msb_agent_service.knowledge_index_client import (
    KnowledgeIndexError,
    KnowledgeIndexErrorCode,
)
from msb_agent_service.knowledge_index_documents import (
    KnowledgeChunkDocument,
    fixed_metadata_filters,
)
from msb_agent_service.knowledge_index_mapping import (
    DISTANCE_SPACE,
    KNOWLEDGE_INDEX_SCHEMA_VERSION,
    knowledge_index_definition,
    mapping_is_compatible,
    physical_index_name,
    settings_are_compatible,
    validate_physical_index_name,
)


def settings() -> KnowledgeIndexSettings:
    return KnowledgeIndexSettings(
        enabled=True,
        url="http://localhost:9201",
        embedding_provider="synthetic",
        embedding_model="test-model",
        embedding_dimensions=3,
    )


def listing_document(**overrides: object) -> KnowledgeChunkDocument:
    values: dict[str, object] = {
        "chunkId": "chunk-1",
        "sourceType": "LISTING",
        "sourceId": "listing-1",
        "sourceVersion": "7",
        "contentHash": "a" * 64,
        "listingId": "listing-1",
        "visibility": "PUBLIC",
        "language": "EN",
        "effectiveFrom": datetime(2026, 1, 1, tzinfo=UTC),
        "indexedAt": datetime(2026, 7, 18, tzinfo=UTC),
        "ordinal": 0,
        "sectionLabel": "Description",
        "text": "A blue commuter bicycle.",
        "embedding": [1.0, 0.0, 0.0],
    }
    values.update(overrides)
    return KnowledgeChunkDocument.model_validate(values)


class KnowledgeIndexMappingTest(unittest.TestCase):
    def test_mapping_is_strict_and_records_embedding_identity(self) -> None:
        definition = knowledge_index_definition(settings())
        mappings = definition["mappings"]
        vector = mappings["properties"]["embedding"]

        self.assertEqual("strict", mappings["dynamic"])
        self.assertEqual(KNOWLEDGE_INDEX_SCHEMA_VERSION, mappings["_meta"]["schemaVersion"])
        self.assertEqual(3, vector["dimension"])
        self.assertEqual(DISTANCE_SPACE, vector["method"]["space_type"])
        self.assertEqual("lucene", vector["method"]["engine"])
        self.assertTrue(definition["settings"]["index"]["knn"])

    def test_mapping_compatibility_checks_exact_metadata_and_properties(self) -> None:
        configured = settings()
        index_name = physical_index_name(configured, 1)
        definition = knowledge_index_definition(configured)
        response = {index_name: {"mappings": definition["mappings"]}}

        self.assertTrue(mapping_is_compatible(configured, index_name, response))
        response[index_name]["mappings"]["_meta"]["embeddingDimensions"] = 4
        self.assertFalse(mapping_is_compatible(configured, index_name, response))

    def test_index_setting_compatibility_handles_open_search_strings(self) -> None:
        configured = settings()
        index_name = physical_index_name(configured, 1)
        response = {
            index_name: {
                "settings": {
                    "index": {
                        "knn": "true",
                        "number_of_shards": "1",
                        "number_of_replicas": "0",
                    }
                }
            }
        }

        self.assertTrue(settings_are_compatible(configured, index_name, response))
        response[index_name]["settings"]["index"]["knn"] = "false"
        self.assertFalse(settings_are_compatible(configured, index_name, response))

    def test_physical_name_rejects_other_prefix_and_schema(self) -> None:
        configured = settings()
        name = physical_index_name(configured, 12)

        self.assertEqual("msb-agent-knowledge-v0001-000012", name)
        self.assertEqual(12, validate_physical_index_name(configured, name))
        with self.assertRaises(ValueError):
            validate_physical_index_name(configured, "msb-public-listings")


class KnowledgeChunkDocumentTest(unittest.TestCase):
    def test_listing_document_serializes_strict_camel_case_fields(self) -> None:
        document = listing_document()

        self.assertEqual("en", document.language)
        self.assertEqual("chunk-1", document.open_search_source()["chunkId"])
        self.assertNotIn("listing_id", document.open_search_source())

    def test_non_listing_document_rejects_listing_scope(self) -> None:
        with self.assertRaises(ValidationError):
            listing_document(
                sourceType="MARKETPLACE_FAQ",
                listingId="listing-1",
            )

    def test_wrong_embedding_dimensions_are_rejected(self) -> None:
        document = listing_document()

        with self.assertRaisesRegex(ValueError, "exactly 4"):
            document.validate_embedding_dimensions(4)

    def test_invalid_hash_and_time_window_are_rejected(self) -> None:
        with self.assertRaises(ValidationError):
            listing_document(contentHash="not-a-hash")
        with self.assertRaises(ValidationError):
            listing_document(
                effectiveTo=datetime(2025, 1, 1, tzinfo=UTC),
            )
        with self.assertRaises(ValidationError):
            listing_document(
                invalidatedAt=datetime(2026, 7, 17, tzinfo=UTC),
            )
        with self.assertRaises(ValidationError):
            listing_document(indexedAt=datetime(2026, 7, 18))

    def test_fixed_filters_include_public_scope_and_invalidation(self) -> None:
        now = datetime.now(UTC)
        filters = fixed_metadata_filters(
            source_types=["LISTING"],
            listing_id="listing-1",
            language="EN",
            effective_at=now + timedelta(seconds=1),
        )

        self.assertIn({"term": {"visibility": "PUBLIC"}}, filters)
        self.assertIn({"term": {"listingId": "listing-1"}}, filters)
        self.assertIn({"term": {"language": "en"}}, filters)
        self.assertEqual(
            {"bool": {"must_not": {"exists": {"field": "invalidatedAt"}}}},
            filters[-1],
        )

    def test_fixed_filters_reject_naive_effective_time(self) -> None:
        with self.assertRaisesRegex(ValueError, "UTC offset"):
            fixed_metadata_filters(effective_at=datetime(2026, 7, 18))

    def test_fixed_filters_support_bounded_language_fallback(self) -> None:
        filters = fixed_metadata_filters(languages=["EN", "und", "en"])

        self.assertIn({"terms": {"language": ["en", "und"]}}, filters)
        with self.assertRaisesRegex(ValueError, "mutually exclusive"):
            fixed_metadata_filters(language="en", languages=["und"])


class KnowledgeIndexWriterTest(unittest.IsolatedAsyncioTestCase):
    async def test_live_mirroring_targets_each_distinct_alias_generation(
        self,
    ) -> None:
        client = AsyncMock()
        read_index = physical_index_name(settings(), 1)
        write_index = physical_index_name(settings(), 2)
        client.indices.get_alias.return_value = {
            read_index: {
                "aliases": {settings().read_alias: {}},
            },
            write_index: {
                "aliases": {
                    settings().write_alias: {"is_write_index": True}
                },
            },
        }
        client.bulk.return_value = {
            "errors": False,
            "items": [
                {"index": {"_id": "chunk-1", "status": 201}},
                {"index": {"_id": "chunk-1", "status": 201}},
            ],
        }
        writer = KnowledgeIndexWriter(
            client,
            settings(),
            mirror_live_generations=True,
        )

        await writer.upsert([listing_document()])

        operations = client.bulk.await_args.kwargs["body"]
        targets = {
            operations[0]["index"]["_index"],
            operations[2]["index"]["_index"],
        }
        self.assertEqual({read_index, write_index}, targets)

    async def test_upsert_rejects_duplicate_ids_before_network_call(self) -> None:
        client = AsyncMock()
        writer = KnowledgeIndexWriter(client, settings())
        duplicate = listing_document()

        with self.assertRaises(KnowledgeIndexError) as raised:
            await writer.upsert([duplicate, duplicate])

        self.assertEqual(
            KnowledgeIndexErrorCode.DOCUMENT_INVALID,
            raised.exception.code,
        )
        client.bulk.assert_not_awaited()

    async def test_partial_bulk_failure_exposes_only_failed_ids(self) -> None:
        client = AsyncMock()
        client.bulk.return_value = {
            "errors": True,
            "items": [
                {"index": {"_id": "chunk-1", "status": 201}},
                {
                    "index": {
                        "_id": "chunk-2",
                        "status": 429,
                        "error": {"reason": "must not escape"},
                    }
                },
            ],
        }
        writer = KnowledgeIndexWriter(client, settings())

        with self.assertRaises(KnowledgeIndexError) as raised:
            await writer.upsert(
                [
                    listing_document(),
                    listing_document(
                        chunkId="chunk-2",
                        sourceId="listing-2",
                        listingId="listing-2",
                        contentHash="b" * 64,
                    ),
                ]
            )

        self.assertEqual(
            KnowledgeIndexErrorCode.PARTIAL_FAILURE,
            raised.exception.code,
        )
        self.assertEqual(("chunk-2",), raised.exception.failed_ids)
        self.assertNotIn("must not escape", str(raised.exception))

    async def test_invalidation_rejects_naive_timestamp_before_network_call(
        self,
    ) -> None:
        client = AsyncMock()
        writer = KnowledgeIndexWriter(client, settings())

        with self.assertRaises(KnowledgeIndexError) as raised:
            await writer.invalidate_source(
                "LISTING",
                "listing-1",
                "1",
                datetime(2026, 7, 18),
            )

        self.assertEqual(
            KnowledgeIndexErrorCode.DOCUMENT_INVALID,
            raised.exception.code,
        )
        client.update_by_query.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
