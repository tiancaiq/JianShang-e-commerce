from __future__ import annotations

import json
import unittest
from copy import deepcopy
from pathlib import Path

from pydantic import ValidationError

from msb_agent_service.discovery_embedding_contract import (
    EMBEDDING_DIMENSIONS,
    EVENT_TOPIC,
    DiscoveryEmbeddingContractError,
    DiscoveryEmbeddingContractErrorCode,
    DiscoveryEmbeddingResult,
    EmbeddingIdentity,
    parse_discovery_embedding_event,
)

EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB"
LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC"


def event_payload() -> dict[str, object]:
    return {
        "eventId": EVENT_ID,
        "eventType": "listing.discovery.embedding-requested",
        "eventVersion": 1,
        "occurredAt": "2026-07-23T10:00:00Z",
        "producer": "product-service",
        "aggregateType": "listing",
        "aggregateId": LISTING_ID,
        "correlationId": "correlation-04b",
        "payload": {
            "requestId": REQUEST_ID,
            "listingId": LISTING_ID,
            "listingVersion": 7,
            "documentSchemaVersion": "MARKETPLACE_LISTING_DISCOVERY_V2",
            "documentHash": "a" * 64,
            "embeddingInputSchemaVersion":
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
            "embeddingInputHash": "b" * 64,
            "normalizerVersion": "NFKC_WHITESPACE_V1",
            "redactorVersion": "PUBLIC_CONTACT_REDACTION_V1",
            "language": "und",
            "embeddingIdentity": {
                "provider": "openai",
                "model": "text-embedding-3-small",
                "dimensions": 1536,
            },
        },
    }


def parse(value: dict[str, object] | None = None):
    body = json.dumps(value or event_payload()).encode("utf-8")
    return parse_discovery_embedding_event(
        topic=EVENT_TOPIC,
        message_key=LISTING_ID.encode("ascii"),
        body=body,
    )


class DiscoveryEmbeddingContractTests(unittest.TestCase):
    def test_accepts_exact_product_04a_event_and_hash_is_deterministic(self) -> None:
        first = parse()
        second = parse()

        self.assertEqual(EVENT_ID, first.event_id)
        self.assertEqual(REQUEST_ID, first.payload.request_id)
        self.assertEqual(LISTING_ID, first.aggregate_id)
        self.assertEqual(first.payload_hash(), second.payload_hash())

    def test_rejects_unknown_fields_wrong_topic_and_message_key(self) -> None:
        with self.subTest("extra"):
            value = event_payload()
            value["privateSeller"] = "not-allowed"
            with self.assertRaises(DiscoveryEmbeddingContractError) as raised:
                parse(value)
            self.assertEqual(
                DiscoveryEmbeddingContractErrorCode.INVALID_SCHEMA,
                raised.exception.code,
            )

        with self.subTest("topic"):
            with self.assertRaises(DiscoveryEmbeddingContractError) as raised:
                parse_discovery_embedding_event(
                    topic="listing-knowledge-v1",
                    message_key=LISTING_ID,
                    body=json.dumps(event_payload()).encode(),
                )
            self.assertEqual(
                DiscoveryEmbeddingContractErrorCode.INVALID_TOPIC,
                raised.exception.code,
            )

        with self.subTest("key"):
            with self.assertRaises(DiscoveryEmbeddingContractError) as raised:
                parse_discovery_embedding_event(
                    topic=EVENT_TOPIC,
                    message_key=REQUEST_ID,
                    body=json.dumps(event_payload()).encode(),
                )
            self.assertEqual(
                DiscoveryEmbeddingContractErrorCode.INVALID_MESSAGE_KEY,
                raised.exception.code,
            )

    def test_rejects_every_identity_hash_aggregate_and_timestamp_drift(self) -> None:
        mutations = (
            ("eventType", "listing.discovery.embedding-requested.v1"),
            ("eventVersion", 2),
            ("producer", "agent-service"),
            ("aggregateType", "knowledge"),
            ("occurredAt", "2026-07-23T10:00:00"),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                candidate = event_payload()
                candidate[field] = value
                with self.assertRaises(DiscoveryEmbeddingContractError):
                    parse(candidate)

        payload_mutations = (
            ("requestId", "lowercase-not-allowed"),
            ("listingVersion", -1),
            ("documentHash", "A" * 64),
            ("embeddingInputHash", "b" * 63),
            ("documentSchemaVersion", "MARKETPLACE_LISTING_DISCOVERY_V1"),
            ("embeddingInputSchemaVersion", "OTHER"),
            ("normalizerVersion", "OTHER"),
            ("redactorVersion", "OTHER"),
            ("language", "en"),
        )
        for field, value in payload_mutations:
            with self.subTest(field=field):
                candidate = event_payload()
                candidate_payload = candidate["payload"]
                assert isinstance(candidate_payload, dict)
                candidate_payload[field] = value
                with self.assertRaises(DiscoveryEmbeddingContractError):
                    parse(candidate)

        candidate = event_payload()
        candidate["aggregateId"] = REQUEST_ID
        with self.assertRaises(DiscoveryEmbeddingContractError):
            parse(candidate)

        for field, value in (
            ("provider", "synthetic"),
            ("model", "text-embedding-3-large"),
            ("dimensions", 1024),
        ):
            with self.subTest(identity=field):
                candidate = deepcopy(event_payload())
                identity = candidate["payload"]["embeddingIdentity"]  # type: ignore[index]
                identity[field] = value  # type: ignore[index]
                with self.assertRaises(DiscoveryEmbeddingContractError):
                    parse(candidate)

    def test_result_requires_exact_finite_1536_vector(self) -> None:
        base = {
            "listingVersion": 7,
            "documentSchemaVersion": "MARKETPLACE_LISTING_DISCOVERY_V2",
            "documentHash": "a" * 64,
            "embeddingInputSchemaVersion":
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
            "embeddingInputHash": "b" * 64,
            "embeddingIdentity": EmbeddingIdentity(
                provider="openai",
                model="text-embedding-3-small",
                dimensions=1536,
            ),
        }
        accepted = DiscoveryEmbeddingResult(
            **base,
            vector=[0.25] * EMBEDDING_DIMENSIONS,
        )
        self.assertEqual(EMBEDDING_DIMENSIONS, len(accepted.vector))

        with self.assertRaises(ValidationError):
            DiscoveryEmbeddingResult(**base, vector=[0.25] * 1535)
        with self.assertRaises(ValidationError):
            DiscoveryEmbeddingResult(
                **base,
                vector=[float("nan")] + [0.25] * 1535,
            )

    def test_v9_migration_is_forward_only_and_stores_no_content_or_vector(self) -> None:
        migration = (
            Path(__file__).parents[1]
            / "db"
            / "migration"
            / "V9__create_discovery_embedding_jobs.sql"
        ).read_text(encoding="utf-8")
        lowered = migration.lower()
        self.assertIn("create table discovery_embedding_jobs", lowered)
        self.assertIn("unique key uq_discovery_embedding_event", lowered)
        self.assertIn("unique key uq_discovery_embedding_request", lowered)
        self.assertIn("idx_discovery_embedding_due", lowered)
        for forbidden_column in (
            "embedding_text",
            "raw_event",
            "provider_response",
            "actor_user_id",
            "seller_id",
            "prompt",
            "vector",
        ):
            self.assertNotRegex(
                lowered,
                rf"(?m)^\s+{forbidden_column}\s+[a-z]",
            )
        self.assertNotIn("drop table", lowered)
        self.assertNotIn("truncate", lowered)


if __name__ == "__main__":
    unittest.main()
