from __future__ import annotations

import json
import unittest

from msb_agent_service.knowledge_events import (
    KnowledgeEventErrorCode,
    KnowledgeEventValidationError,
    parse_listing_knowledge_event,
)

LISTING_ID = "01L00000000000000000000001"
EVENT_ID = "01E00000000000000000000001"


def event_body(**payload_overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "listingId": LISTING_ID,
        "listingVersion": "12",
        "knowledgeLifecycle": "ACTIVE",
        "supersedesVersion": "11",
        "language": "und",
    }
    payload.update(payload_overrides)
    return {
        "eventId": EVENT_ID,
        "eventType": "listing.updated",
        "eventVersion": 1,
        "occurredAt": "2026-07-19T01:00:00Z",
        "producer": "product-service",
        "aggregateType": "listing",
        "aggregateId": LISTING_ID,
        "correlationId": "01C00000000000000000000001",
        "payload": payload,
    }


class ListingKnowledgeEventTest(unittest.TestCase):
    def test_strict_contract_accepts_additive_fields_and_hashes_known_payload(
        self,
    ) -> None:
        body = event_body()
        body["futureEnvelopeField"] = "ignored"
        body["payload"]["futurePayloadField"] = "ignored"  # type: ignore[index]

        event = parse_listing_knowledge_event(
            json.dumps(body).encode(),
            LISTING_ID.encode(),
        )
        same_without_additive = parse_listing_knowledge_event(
            json.dumps(event_body()).encode(),
            LISTING_ID.encode(),
        )

        self.assertEqual(12, event.source_version)
        self.assertEqual(11, event.superseded_version)
        self.assertEqual(event.payload_hash(), same_without_additive.payload_hash())
        self.assertEqual(64, len(event.payload_hash()))

    def test_message_key_must_equal_listing_id(self) -> None:
        with self.assertRaises(KnowledgeEventValidationError) as captured:
            parse_listing_knowledge_event(
                json.dumps(event_body()).encode(),
                EVENT_ID.encode(),
            )

        self.assertEqual(
            KnowledgeEventErrorCode.INVALID_MESSAGE_KEY,
            captured.exception.code,
        )

    def test_event_type_and_lifecycle_must_agree(self) -> None:
        body = event_body(knowledgeLifecycle="INVALIDATED")

        with self.assertRaises(KnowledgeEventValidationError) as captured:
            parse_listing_knowledge_event(
                json.dumps(body).encode(),
                LISTING_ID.encode(),
            )

        self.assertEqual(
            KnowledgeEventErrorCode.INVALID_SCHEMA,
            captured.exception.code,
        )

    def test_invalid_json_and_utf8_have_safe_classifications(self) -> None:
        with self.assertRaises(KnowledgeEventValidationError) as malformed:
            parse_listing_knowledge_event(b"{", LISTING_ID.encode())
        self.assertEqual(KnowledgeEventErrorCode.INVALID_JSON, malformed.exception.code)

        with self.assertRaises(KnowledgeEventValidationError) as invalid_utf8:
            parse_listing_knowledge_event(b"\xff", LISTING_ID.encode())
        self.assertEqual(
            KnowledgeEventErrorCode.INVALID_UTF8,
            invalid_utf8.exception.code,
        )

    def test_unknown_event_version_is_rejected(self) -> None:
        body = event_body()
        body["eventVersion"] = 2

        with self.assertRaises(KnowledgeEventValidationError) as captured:
            parse_listing_knowledge_event(
                json.dumps(body).encode(),
                LISTING_ID.encode(),
            )

        self.assertEqual(
            KnowledgeEventErrorCode.INVALID_SCHEMA,
            captured.exception.code,
        )


if __name__ == "__main__":
    unittest.main()
