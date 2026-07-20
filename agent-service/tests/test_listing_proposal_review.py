from __future__ import annotations

import asyncio
import hashlib
import logging
import unittest
from datetime import UTC, datetime, timedelta

import httpx
from prometheus_client import CollectorRegistry

from msb_agent_service.agent_persistence import AgentPersistenceError
from msb_agent_service.api import create_app
from msb_agent_service.config import (
    AgentPersistenceSettings,
    ListingProposalApiSettings,
    Settings,
)
from msb_agent_service.customer_service_api import AgentApiError, AgentApiErrorCode
from msb_agent_service.listing_content_proposal import (
    ListingMediaContent,
    ListingContentProposal,
    ListingVisionCandidate,
    ListingVisionProviderResult,
    OwnedDraftMediaContext,
    ProposalEvidence,
    UnknownField,
    VISION_INSTRUCTION_VERSION,
)
from msb_agent_service.listing_proposal_review import (
    CreateListingProposalRequest,
    GeneratedListingProposal,
    ListingProposalApiError,
    ListingProposalApiErrorCode,
    ListingProposalMetrics,
    OwnedMediaListingProposalGenerator,
    ListingProposalResponse,
    ListingProposalResultMetadata,
    ListingProposalReviewService,
    ProposalReservation,
    ProposalReservationResult,
    SourceMediaEvidence,
)

ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
OTHER = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
LISTING = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
MEDIA = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
PROPOSAL = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
CLIENT_REQUEST = "request-00000001"
NOW = datetime(2026, 7, 20, 12, 0, tzinfo=UTC)


def request(**changes) -> CreateListingProposalRequest:
    payload = {
        "schemaVersion": "LISTING_PROPOSAL_V1",
        "listingId": LISTING,
        "expectedListingVersion": 12,
        "mediaIds": [MEDIA],
        "clientRequestId": CLIENT_REQUEST,
    }
    payload.update(changes)
    return CreateListingProposalRequest.model_validate(payload)


def generated(
    *,
    listing_version: int = 12,
    media_ids: tuple[str, ...] = (MEDIA,),
) -> GeneratedListingProposal:
    return GeneratedListingProposal(
        sourceListingVersion=listing_version,
        sourceMediaEvidence=tuple(
            SourceMediaEvidence(
                mediaId=media_id,
                evidenceId=f"E{index}",
                sha256=f"{index}" * 64,
                actualMime="image/png",
                byteSize=128,
            )
            for index, media_id in enumerate(media_ids, 1)
        ),
        proposal=ListingContentProposal(
            unknown_fields=tuple(UnknownField),
        ),
        resultMetadata=ListingProposalResultMetadata(
            instructionVersion=VISION_INSTRUCTION_VERSION,
            schemaVersion="ai-list-proposal-v1",
            providerMode="FAKE",
            resultCode="SUCCEEDED",
            latencyMs=10,
            inputTokens=0,
            outputTokens=0,
        ),
    )


class FakeIdentityClient:
    async def resolve(self, authorization, correlation_id):
        if authorization == "Bearer actor-token":
            return ACTOR
        if authorization == "Bearer other-token":
            return OTHER
        raise AgentApiError(
            AgentApiErrorCode.UNAUTHORIZED,
            401,
            "Authentication is required.",
        )


class FakeAgentRepository:
    def __init__(self) -> None:
        self.validated = False
        self.closed = False

    async def validate_schema(self) -> None:
        self.validated = True

    async def close(self) -> None:
        self.closed = True


class FakeProposalRepository:
    def __init__(self) -> None:
        self.rows: dict[str, ListingProposalResponse] = {}
        self.keys: dict[tuple[str, str], tuple[str, str]] = {}
        self.claims: dict[tuple[str, str], tuple[str, str]] = {}
        self.validated = False
        self.released = 0

    async def validate_schema(self) -> None:
        self.validated = True

    async def reserve_create(self, *, actor_user_id, request, now):
        key = (actor_user_id, request.client_request_id)
        existing = self.keys.get(key)
        if existing is not None:
            request_hash, proposal_id = existing
            if request_hash != request.canonical_request_hash:
                return ProposalReservation(ProposalReservationResult.CONFLICT)
            return ProposalReservation(
                ProposalReservationResult.REPLAY,
                proposal=self.rows[proposal_id],
            )
        claim = self.claims.get(key)
        if claim is not None:
            if claim[0] != request.canonical_request_hash:
                return ProposalReservation(ProposalReservationResult.CONFLICT)
            return ProposalReservation(ProposalReservationResult.IN_PROGRESS)
        self.claims[key] = (request.canonical_request_hash, "a" * 64)
        return ProposalReservation(
            ProposalReservationResult.OWNER,
            claim_token="a" * 64,
        )

    async def find_by_key(
        self, *, actor_user_id, client_request_id, request_hash, now
    ):
        existing = self.keys.get((actor_user_id, client_request_id))
        if existing is None:
            return None
        if existing[0] != request_hash:
            raise ListingProposalApiError(
                ListingProposalApiErrorCode.IDEMPOTENCY_CONFLICT,
                409,
                "conflict",
            )
        return self.rows[existing[1]]

    async def complete_create(
        self, *, actor_user_id, request, claim_token, generated, now
    ):
        key = (actor_user_id, request.client_request_id)
        assert self.claims[key][1] == claim_token
        row = ListingProposalResponse(
            proposalId=PROPOSAL,
            status="READY",
            proposalVersion=1,
            schemaVersion="LISTING_PROPOSAL_V1",
            listingId=request.listing_id,
            sourceListingVersion=generated.source_listing_version,
            sourceMediaEvidence=generated.source_media_evidence,
            proposal=generated.proposal,
            proposalOnly=True,
            requiresSellerConfirmation=True,
            createdAt=now,
            expiresAt=now + timedelta(hours=24),
            resultMetadata=generated.result_metadata,
        )
        self.rows[PROPOSAL] = row
        self.keys[key] = (request.canonical_request_hash, PROPOSAL)
        self.claims.pop(key)
        return row

    async def release_claim(
        self, *, actor_user_id, client_request_id, claim_token
    ):
        self.claims.pop((actor_user_id, client_request_id), None)
        self.released += 1

    async def get_owned(self, *, proposal_id, actor_user_id, now):
        row = self.rows.get(proposal_id)
        if row is None or actor_user_id != ACTOR:
            return None
        if row.status == "READY" and now >= row.expires_at:
            row = row.model_copy(
                update={
                    "status": "EXPIRED",
                    "proposal_version": row.proposal_version + 1,
                    "source_media_evidence": None,
                    "proposal": None,
                    "proposal_only": None,
                    "requires_seller_confirmation": None,
                    "result_metadata": None,
                    "content_purged_at": now,
                }
            )
            self.rows[proposal_id] = row
        return row

    async def dismiss(
        self, *, proposal_id, actor_user_id, idempotency_key, now
    ):
        row = await self.get_owned(
            proposal_id=proposal_id,
            actor_user_id=actor_user_id,
            now=now,
        )
        if row is None or row.status != "READY":
            return row
        row = row.model_copy(
            update={
                "status": "DISMISSED",
                "proposal_version": row.proposal_version + 1,
                "source_media_evidence": None,
                "proposal": None,
                "proposal_only": None,
                "requires_seller_confirmation": None,
                "result_metadata": None,
                "dismissed_at": now,
                "content_purged_at": now,
            }
        )
        self.rows[proposal_id] = row
        return row

    async def apply_retention(self, *, now, batch_size=100):
        return (0, 0, 0)


class FakeGenerator:
    def __init__(self, result=None) -> None:
        self.result = result or generated()
        self.calls = 0

    async def generate(self, **kwargs):
        self.calls += 1
        return self.result


class CancelledGenerator:
    async def generate(self, **kwargs):
        raise asyncio.CancelledError


class FakeOwnedMediaTool:
    def __init__(self) -> None:
        self.calls = 0

    async def read_owned_draft_media(self, **kwargs):
        self.calls += 1
        content = b"\x89PNG\r\n\x1a\nsafe-image"
        return OwnedDraftMediaContext(
            listing_id=kwargs["listing_id"],
            listing_version="12",
            eligibility="OWNED_DRAFT",
            media=(
                ListingMediaContent(
                    media_id=kwargs["media_ids"][0],
                    content_type="image/png",
                    byte_size=len(content),
                    sha256=hashlib.sha256(content).hexdigest(),
                    source_version="3",
                    content=content,
                ),
            ),
        )


class FakeVisionProvider:
    def __init__(self) -> None:
        self.calls = 0

    async def propose_listing_content(self, request, *, correlation_id):
        self.calls += 1
        return ListingVisionProviderResult(
            candidate=ListingVisionCandidate(
                evidence=(
                    ProposalEvidence(
                        evidence_id="E1",
                        media_id=request.untrusted_media[0].media_id,
                        observation="A visible item",
                    ),
                ),
                unknown_fields=tuple(UnknownField),
            ),
            input_tokens=12,
            output_tokens=5,
            latency_ms=8,
        )


def proposal_settings(*, generation: bool) -> ListingProposalApiSettings:
    return ListingProposalApiSettings(
        enabled=True,
        auth_service_url="http://auth-service:8085",
        orchestration_enabled=generation,
        media_tool_enabled=generation,
        product_service_url=(
            "http://product-service:8091" if generation else None
        ),
        product_service_token="internal-secret" if generation else None,
        multimodal_provider_enabled=generation,
        multimodal_provider_configured=generation,
    )


class ListingProposalContractTest(unittest.TestCase):
    def test_request_is_strict_and_hashes_sorted_media(self) -> None:
        second = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
        left = request(mediaIds=[second, MEDIA])
        right = request(mediaIds=[MEDIA, second])

        self.assertEqual(left.canonical_request_hash, right.canonical_request_hash)
        self.assertEqual((MEDIA, second), left.canonical_media_ids)
        with self.assertRaises(ValueError):
            request(mediaIds=[MEDIA, MEDIA])
        with self.assertRaises(ValueError):
            request(actorUserId=ACTOR)
        with self.assertRaises(ValueError):
            request(clientRequestId="too-short")


class ListingProposalServiceTest(unittest.IsolatedAsyncioTestCase):
    async def test_success_log_contains_hashes_not_identity_or_content(self) -> None:
        repository = FakeProposalRepository()
        service = ListingProposalReviewService(
            repository,
            proposal_settings(generation=True),
            ListingProposalMetrics(CollectorRegistry()),
            FakeGenerator(),
        )
        with self.assertLogs(
            "msb_agent_service.listing_proposal_review",
            level=logging.INFO,
        ) as captured:
            await service.create(
                actor_user_id=ACTOR,
                request=request(),
                correlation_id="private-correlation",
            )

        output = "\n".join(captured.output)
        for forbidden in (ACTOR, LISTING, PROPOSAL, "private-correlation"):
            self.assertNotIn(forbidden, output)
        self.assertIn(hashlib.sha256(ACTOR.encode()).hexdigest(), output)

    async def test_generator_reuses_owned_media_and_vision_boundaries_once(self) -> None:
        media_tool = FakeOwnedMediaTool()
        provider = FakeVisionProvider()
        adapter = OwnedMediaListingProposalGenerator(
            media_tool=media_tool,
            vision_provider=provider,
            provider_mode="FAKE",
        )

        result = await adapter.generate(
            actor_user_id=ACTOR,
            listing_id=LISTING,
            expected_listing_version=12,
            media_ids=(MEDIA,),
            client_request_id=CLIENT_REQUEST,
            correlation_id="adapter-test",
        )

        self.assertEqual(1, media_tool.calls)
        self.assertEqual(1, provider.calls)
        self.assertEqual(12, result.source_listing_version)
        self.assertEqual(MEDIA, result.source_media_evidence[0].media_id)
        self.assertEqual("E1", result.source_media_evidence[0].evidence_id)
        self.assertNotIn(
            "safe-image",
            result.model_dump_json(by_alias=True),
        )

    async def test_replay_precedes_disabled_generation_and_conflict(self) -> None:
        repository = FakeProposalRepository()
        generator = FakeGenerator()
        enabled = ListingProposalReviewService(
            repository,
            proposal_settings(generation=True),
            ListingProposalMetrics(CollectorRegistry()),
            generator,
        )
        created, is_new = await enabled.create(
            actor_user_id=ACTOR,
            request=request(),
            correlation_id="proposal-test",
        )
        disabled = ListingProposalReviewService(
            repository,
            proposal_settings(generation=False),
            ListingProposalMetrics(CollectorRegistry()),
            None,
        )
        replay, replay_is_new = await disabled.create(
            actor_user_id=ACTOR,
            request=request(),
            correlation_id="proposal-replay",
        )

        self.assertTrue(is_new)
        self.assertFalse(replay_is_new)
        self.assertEqual(created.proposal_id, replay.proposal_id)
        self.assertEqual(1, generator.calls)
        fetched = await disabled.get(
            actor_user_id=ACTOR,
            proposal_id=created.proposal_id,
        )
        dismissed = await disabled.dismiss(
            actor_user_id=ACTOR,
            proposal_id=created.proposal_id,
            idempotency_key="disabled-dismiss-01",
        )
        self.assertEqual("READY", fetched.status)
        self.assertEqual("DISMISSED", dismissed.status)
        with self.assertRaises(ListingProposalApiError) as captured:
            await disabled.create(
                actor_user_id=ACTOR,
                request=request(expectedListingVersion=13),
                correlation_id="proposal-conflict",
            )
        self.assertEqual(
            ListingProposalApiErrorCode.IDEMPOTENCY_CONFLICT,
            captured.exception.code,
        )
        self.assertEqual(1, generator.calls)

    async def test_gate_failure_and_source_conflict_leave_no_row(self) -> None:
        repository = FakeProposalRepository()
        disabled_generator = FakeGenerator()
        service = ListingProposalReviewService(
            repository,
            proposal_settings(generation=False),
            ListingProposalMetrics(CollectorRegistry()),
            disabled_generator,
        )
        with self.assertRaises(ListingProposalApiError) as captured:
            await service.create(
                actor_user_id=ACTOR,
                request=request(),
                correlation_id="disabled",
            )
        self.assertEqual(ListingProposalApiErrorCode.UNAVAILABLE, captured.exception.code)
        self.assertEqual(0, disabled_generator.calls)
        self.assertFalse(repository.rows)

    async def test_expiry_purges_content_and_cancellation_releases_claim(self) -> None:
        repository = FakeProposalRepository()
        service = ListingProposalReviewService(
            repository,
            proposal_settings(generation=True),
            ListingProposalMetrics(CollectorRegistry()),
            FakeGenerator(),
        )
        stored, _ = await service.create(
            actor_user_id=ACTOR,
            request=request(),
            correlation_id="expiry",
        )
        repository.rows[PROPOSAL] = stored.model_copy(
            update={"expires_at": datetime.now(UTC) - timedelta(seconds=1)}
        )
        with self.assertRaises(ListingProposalApiError) as captured:
            await service.get(actor_user_id=ACTOR, proposal_id=PROPOSAL)
        self.assertEqual(ListingProposalApiErrorCode.EXPIRED, captured.exception.code)
        self.assertIsNone(repository.rows[PROPOSAL].proposal)

        cancelled_repository = FakeProposalRepository()
        cancelled = ListingProposalReviewService(
            cancelled_repository,
            proposal_settings(generation=True),
            ListingProposalMetrics(CollectorRegistry()),
            CancelledGenerator(),
        )
        with self.assertRaises(asyncio.CancelledError):
            await cancelled.create(
                actor_user_id=ACTOR,
                request=request(),
                correlation_id="cancelled",
            )
        self.assertEqual(1, cancelled_repository.released)
        self.assertFalse(cancelled_repository.rows)

        mismatch_repository = FakeProposalRepository()
        mismatched = ListingProposalReviewService(
            mismatch_repository,
            proposal_settings(generation=True),
            ListingProposalMetrics(CollectorRegistry()),
            FakeGenerator(generated(listing_version=13)),
        )
        with self.assertRaises(ListingProposalApiError) as captured:
            await mismatched.create(
                actor_user_id=ACTOR,
                request=request(),
                correlation_id="stale",
            )
        self.assertEqual(
            ListingProposalApiErrorCode.SOURCE_VERSION_CONFLICT,
            captured.exception.code,
        )
        self.assertFalse(mismatch_repository.rows)


class ListingProposalApiTest(unittest.IsolatedAsyncioTestCase):
    async def client(self):
        agent_repository = FakeAgentRepository()
        proposal_repository = FakeProposalRepository()
        generator = FakeGenerator()

        async def persistence_factory(settings, metrics):
            return agent_repository

        async def proposal_factory(repository, metrics):
            return proposal_repository

        app = create_app(
            Settings(
                agent_persistence=AgentPersistenceSettings(
                    enabled=True,
                    mysql_password="database-secret",
                ),
                listing_proposal_api=proposal_settings(generation=True),
            ),
            persistence_repository_factory=persistence_factory,
            listing_proposal_repository_factory=proposal_factory,
            identity_client=FakeIdentityClient(),
            listing_proposal_generator=generator,
        )
        lifespan = app.router.lifespan_context(app)
        await lifespan.__aenter__()
        self.addAsyncCleanup(lifespan.__aexit__, None, None, None)
        return (
            httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app),
                base_url="http://test",
            ),
            proposal_repository,
            generator,
        )

    async def test_create_replay_hidden_access_and_dismiss(self) -> None:
        client, repository, generator = await self.client()
        payload = request().model_dump(mode="json", by_alias=True)
        async with client:
            created = await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                json=payload,
            )
            replay = await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                json=payload,
            )
            hidden = await client.get(
                f"/api/v1/agent/listing-proposals/{PROPOSAL}",
                headers={"Authorization": "Bearer other-token"},
            )
            dismissed = await client.post(
                f"/api/v1/agent/listing-proposals/{PROPOSAL}/dismiss",
                headers={
                    "Authorization": "Bearer actor-token",
                    "Idempotency-Key": "dismiss-0001",
                },
            )
            retried = await client.post(
                f"/api/v1/agent/listing-proposals/{PROPOSAL}/dismiss",
                headers={
                    "Authorization": "Bearer actor-token",
                    "Idempotency-Key": "dismiss-0001",
                },
            )

        self.assertEqual(201, created.status_code)
        self.assertEqual(200, replay.status_code)
        self.assertEqual(1, generator.calls)
        self.assertEqual(404, hidden.status_code)
        self.assertEqual(
            "AGENT_LISTING_PROPOSAL_NOT_FOUND",
            hidden.json()["error"]["code"],
        )
        self.assertEqual("DISMISSED", dismissed.json()["status"])
        self.assertNotIn("proposal", dismissed.json())
        self.assertEqual(dismissed.json(), retried.json())
        self.assertIsNone(repository.rows[PROPOSAL].proposal)

    async def test_auth_schema_payload_and_idempotency_conflict_errors(self) -> None:
        client, _, generator = await self.client()
        payload = request().model_dump(mode="json", by_alias=True)
        async with client:
            guest = await client.post(
                "/api/v1/agent/listing-proposals",
                json=payload,
            )
            spoofed = await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                json={**payload, "actorUserId": OTHER},
            )
            too_large = await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                content=b"{" + b"x" * 8_192 + b"}",
            )
            await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                json=payload,
            )
            conflict = await client.post(
                "/api/v1/agent/listing-proposals",
                headers={"Authorization": "Bearer actor-token"},
                json={**payload, "expectedListingVersion": 13},
            )

        self.assertEqual("AUTHENTICATION_REQUIRED", guest.json()["error"]["code"])
        self.assertEqual("INVALID_REQUEST", spoofed.json()["error"]["code"])
        self.assertEqual(413, too_large.status_code)
        self.assertEqual("PAYLOAD_TOO_LARGE", too_large.json()["error"]["code"])
        self.assertEqual(409, conflict.status_code)
        self.assertEqual(
            "AI_PROPOSAL_IDEMPOTENCY_CONFLICT",
            conflict.json()["error"]["code"],
        )
        self.assertEqual(1, generator.calls)

    async def test_default_off_precedes_auth_and_body_parsing(self) -> None:
        app = create_app(Settings())
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://test",
        ) as client:
            response = await client.post(
                "/api/v1/agent/listing-proposals",
                content=b"not-json",
            )

        self.assertEqual(404, response.status_code)
        self.assertEqual("FEATURE_DISABLED", response.json()["error"]["code"])


if __name__ == "__main__":
    unittest.main()
