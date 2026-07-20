from __future__ import annotations

import asyncio
import os
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from testcontainers.core.container import DockerContainer
from prometheus_client import CollectorRegistry

from msb_agent_service.agent_persistence import (
    AgentInvocationStatus,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentPersistenceRepository,
    AgentResolutionType,
    AgentSessionStatus,
    AgentToolCallStatus,
    BeginInvocationResult,
)
from msb_agent_service.config import AgentPersistenceSettings
from msb_agent_service.listing_content_proposal import (
    ListingContentProposal,
    UnknownField,
    VISION_INSTRUCTION_VERSION,
)
from msb_agent_service.listing_proposal_review import (
    CreateListingProposalRequest,
    GeneratedListingProposal,
    ListingProposalMetrics,
    ListingProposalRepository,
    ListingProposalResultMetadata,
    ProposalReservationResult,
    SourceMediaEvidence,
)

RUN_INTEGRATION = os.getenv("RUN_MYSQL_INTEGRATION") == "1"
ACTOR_A = "01A00000000000000000000001"
ACTOR_B = "01A00000000000000000000002"
LISTING_ID = "01L00000000000000000000001"
NOW = datetime(2026, 7, 19, 10, 0, tzinfo=UTC)


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_MYSQL_INTEGRATION=1 to run MySQL integration tests",
)
class AgentPersistenceRepositoryIntegrationTest(unittest.IsolatedAsyncioTestCase):
    container: DockerContainer
    settings: AgentPersistenceSettings

    @classmethod
    def setUpClass(cls) -> None:
        cls.container = (
            DockerContainer("mysql:8.4")
            .with_env("MYSQL_ROOT_PASSWORD", "root-test-password")
            .with_env("MYSQL_DATABASE", "agent")
            .with_env("MYSQL_USER", "agent")
            .with_env("MYSQL_PASSWORD", "agent-test-password")
            .with_exposed_ports(3306)
        )
        cls.container.start()
        cls.settings = AgentPersistenceSettings(
            enabled=True,
            mysql_host=cls.container.get_container_host_ip(),
            mysql_port=int(cls.container.get_exposed_port(3306)),
            mysql_database="agent",
            mysql_username="agent",
            mysql_password="agent-test-password",
            mysql_pool_min_size=1,
            mysql_pool_max_size=5,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    async def asyncSetUp(self) -> None:
        self.repository = await self._connect_repository()
        try:
            await self.repository.validate_schema()
        except AgentPersistenceError:
            await self._apply_migrations()
            await self.repository.validate_schema()
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute("DELETE FROM agent_listing_proposal_dismissals")
                await cursor.execute("DELETE FROM agent_listing_proposals")
                await cursor.execute("DELETE FROM agent_listing_proposal_claims")
                await cursor.execute("DELETE FROM agent_tool_calls")
                await cursor.execute("DELETE FROM agent_invocations")
                await cursor.execute("DELETE FROM agent_messages")
                await cursor.execute("DELETE FROM agent_sessions")
            await connection.commit()

    async def asyncTearDown(self) -> None:
        await self.repository.close()

    async def test_open_session_is_atomic_unique_and_actor_isolated(self) -> None:
        first, second = await asyncio.gather(
            self.repository.create_or_resume_session(
                actor_user_id=ACTOR_A,
                subject_listing_id=LISTING_ID,
                now=NOW,
                correlation_id="session-race-a",
            ),
            self.repository.create_or_resume_session(
                actor_user_id=ACTOR_A,
                subject_listing_id=LISTING_ID,
                now=NOW,
                correlation_id="session-race-b",
            ),
        )
        (session_a, created_a), (session_b, created_b) = first, second

        self.assertEqual(session_a.session_id, session_b.session_id)
        self.assertEqual(1, sum((created_a, created_b)))
        self.assertIsNone(
            await self.repository.get_session(session_a.session_id, ACTOR_B)
        )
        with self.assertRaises(AgentPersistenceError) as captured:
            await self.repository.close_session(
                session_id=session_a.session_id,
                actor_user_id=ACTOR_B,
                expected_version=session_a.optimistic_version,
                now=NOW + timedelta(seconds=1),
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_NOT_FOUND,
            captured.exception.code,
        )
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT COUNT(*)
                    FROM agent_sessions
                    WHERE actor_user_id = %s
                      AND subject_listing_id = %s
                      AND status = 'OPEN'
                    """,
                    (ACTOR_A, LISTING_ID),
                )
                self.assertEqual(1, (await cursor.fetchone())[0])

    async def test_schema_validation_requires_v5_correlation_width(self) -> None:
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        ALTER TABLE agent_invocations
                        MODIFY COLUMN correlation_id VARCHAR(100) NULL
                        """
                    )
                await connection.commit()
                with self.assertRaises(AgentPersistenceError) as captured:
                    await self.repository.validate_schema()
                self.assertEqual(
                    AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED,
                    captured.exception.code,
                )
            finally:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        ALTER TABLE agent_invocations
                        MODIFY COLUMN correlation_id VARCHAR(128) NULL
                        """
                    )
                await connection.commit()
        await self.repository.validate_schema()

    async def test_retry_deduplication_hash_conflict_and_retry_bound(self) -> None:
        session, _ = await self._session()
        created = await self._begin(
            session.session_id,
            "01C00000000000000000000001",
            "Is the price negotiable?",
        )
        duplicate = await self._begin(
            session.session_id,
            "01C00000000000000000000001",
            " Is the price negotiable? ",
        )

        self.assertEqual(BeginInvocationResult.CREATED, created.result)
        self.assertEqual(
            BeginInvocationResult.DEDUPLICATED_PENDING,
            duplicate.result,
        )
        self.assertEqual(
            created.invocation.invocation_id,
            duplicate.invocation.invocation_id,
        )
        with self.assertRaises(AgentPersistenceError) as captured:
            await self._begin(
                session.session_id,
                "01C00000000000000000000001",
                "A different question",
            )
        self.assertEqual(
            AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT,
            captured.exception.code,
        )

        failed = await self.repository.fail_invocation(
            invocation_id=created.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            error_code="OPENAI_UNAVAILABLE",
            input_tokens=0,
            output_tokens=0,
            latency_ms=25,
            estimated_cost=Decimal("0"),
            now=NOW + timedelta(seconds=1),
        )
        self.assertEqual(AgentInvocationStatus.FAILED, failed.result_status)
        retry = await self._begin(
            session.session_id,
            "01C00000000000000000000001",
            "Is the price negotiable?",
            now=NOW + timedelta(seconds=2),
        )
        self.assertEqual(BeginInvocationResult.RETRY_STARTED, retry.result)
        self.assertEqual(1, retry.invocation.retry_count)
        await self.repository.fail_invocation(
            invocation_id=created.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            error_code="OPENAI_UNAVAILABLE",
            input_tokens=0,
            output_tokens=0,
            latency_ms=30,
            estimated_cost=Decimal("0"),
            now=NOW + timedelta(seconds=3),
        )
        exhausted = await self._begin(
            session.session_id,
            "01C00000000000000000000001",
            "Is the price negotiable?",
            now=NOW + timedelta(seconds=4),
        )
        self.assertEqual(BeginInvocationResult.RETRY_EXHAUSTED, exhausted.result)

        page = await self.repository.list_messages(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            limit=10,
        )
        self.assertEqual(1, len(page.messages))
        self.assertEqual("USER", page.messages[0].role.value)

    async def test_success_tool_audit_pagination_and_read_only_guard(self) -> None:
        session, _ = await self._session()
        first = await self._begin(
            session.session_id,
            "01C00000000000000000000011",
            "What should I inspect?",
        )
        tool = await self.repository.append_tool_call(
            invocation_id=first.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            sequence_number=1,
            tool_name="retrieveKnowledge",
            argument_hash="a" * 64,
            result_hash="b" * 64,
            source_refs=(
                {
                    "sourceType": "CATEGORY_GUIDANCE",
                    "sourceId": "01K00000000000000000000002",
                    "sourceVersion": "1",
                },
            ),
            result_status=AgentToolCallStatus.SUCCEEDED,
            error_code=None,
            latency_ms=12,
            now=NOW + timedelta(seconds=1),
        )
        replayed_tool = await self.repository.append_tool_call(
            invocation_id=first.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            sequence_number=1,
            tool_name="retrieveKnowledge",
            argument_hash="a" * 64,
            result_hash="b" * 64,
            source_refs=(
                {
                    "sourceType": "CATEGORY_GUIDANCE",
                    "sourceId": "01K00000000000000000000002",
                    "sourceVersion": "1",
                },
            ),
            result_status=AgentToolCallStatus.SUCCEEDED,
            error_code=None,
            latency_ms=12,
            now=NOW + timedelta(seconds=2),
        )
        self.assertEqual(tool.tool_call_id, replayed_tool.tool_call_id)

        completed, assistant = await self.repository.complete_invocation(
            invocation_id=first.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            body="Inspect the model and visible condition.",
            resolution_type=AgentResolutionType.ANSWERED,
            sources=(
                {
                    "sourceType": "CATEGORY_GUIDANCE",
                    "sourceId": "01K00000000000000000000002",
                    "sourceVersion": "1",
                    "label": "Electronics guidance",
                },
            ),
            actions=(),
            input_tokens=25,
            output_tokens=12,
            latency_ms=50,
            estimated_cost=Decimal("0.00001000"),
            now=NOW + timedelta(seconds=3),
        )
        replayed, replayed_assistant = await self.repository.complete_invocation(
            invocation_id=first.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            body="Ignored on idempotent replay.",
            resolution_type=AgentResolutionType.UNKNOWN,
            sources=(),
            actions=(),
            input_tokens=0,
            output_tokens=0,
            latency_ms=0,
            estimated_cost=Decimal("0"),
            now=NOW + timedelta(seconds=4),
        )
        self.assertEqual(AgentInvocationStatus.SUCCEEDED, completed.result_status)
        self.assertEqual(completed.invocation_id, replayed.invocation_id)
        self.assertEqual(assistant.message_id, replayed_assistant.message_id)
        stored_user, stored_assistant = await self.repository.get_invocation_messages(
            invocation_id=first.invocation.invocation_id,
            actor_user_id=ACTOR_A,
        )
        self.assertEqual(first.user_message.message_id, stored_user.message_id)
        self.assertEqual(assistant.message_id, stored_assistant.message_id)

        second = await self._begin(
            session.session_id,
            "01C00000000000000000000012",
            "Can the seller hold it?",
            now=NOW + timedelta(seconds=5),
        )
        await self.repository.complete_invocation(
            invocation_id=second.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            body="Only the seller can confirm that.",
            resolution_type=AgentResolutionType.CONTACT_SELLER,
            sources=(),
            actions=(
                {
                    "type": "MESSAGE_SELLER",
                    "listingId": LISTING_ID,
                },
            ),
            input_tokens=20,
            output_tokens=9,
            latency_ms=40,
            estimated_cost=Decimal("0.00000800"),
            now=NOW + timedelta(seconds=6),
        )

        first_page = await self.repository.list_messages(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            limit=2,
        )
        self.assertEqual(2, len(first_page.messages))
        self.assertIsNotNone(first_page.next_cursor)
        second_page = await self.repository.list_messages(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            limit=2,
            after=first_page.next_cursor,
        )
        self.assertEqual(2, len(second_page.messages))
        self.assertIsNone(second_page.next_cursor)
        with self.assertRaises(AgentPersistenceError) as captured:
            await self.repository.list_messages(
                session_id=session.session_id,
                actor_user_id=ACTOR_B,
                limit=2,
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_NOT_FOUND,
            captured.exception.code,
        )
        with self.assertRaises(AgentPersistenceError) as captured:
            await self.repository.fail_invocation(
                invocation_id=second.invocation.invocation_id,
                actor_user_id=ACTOR_B,
                error_code="OPENAI_UNAVAILABLE",
                input_tokens=0,
                output_tokens=0,
                latency_ms=1,
                estimated_cost=Decimal("0"),
                now=NOW + timedelta(seconds=7),
            )
        self.assertEqual(
            AgentPersistenceErrorCode.INVOCATION_NOT_FOUND,
            captured.exception.code,
        )

        current = await self.repository.get_session(session.session_id, ACTOR_A)
        assert current is not None
        read_only = await self.repository.mark_session_read_only(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            expected_version=current.optimistic_version,
            now=NOW + timedelta(seconds=7),
        )
        self.assertEqual(AgentSessionStatus.READ_ONLY, read_only.status)
        with self.assertRaises(AgentPersistenceError) as captured:
            await self.repository.close_session(
                session_id=session.session_id,
                actor_user_id=ACTOR_A,
                expected_version=current.optimistic_version,
                now=NOW + timedelta(seconds=8),
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT,
            captured.exception.code,
        )
        with self.assertRaises(AgentPersistenceError) as captured:
            await self._begin(
                session.session_id,
                "01C00000000000000000000013",
                "Another question",
                now=NOW + timedelta(seconds=9),
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_NOT_OPEN,
            captured.exception.code,
        )

    async def test_retention_separates_content_and_safe_audit_windows(self) -> None:
        old = NOW - timedelta(days=100)
        session, _ = await self._session(now=old)
        begun = await self._begin(
            session.session_id,
            "01C00000000000000000000021",
            "Is it available?",
            now=old,
        )
        await self.repository.append_tool_call(
            invocation_id=begun.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            sequence_number=1,
            tool_name="getListing",
            argument_hash="c" * 64,
            result_hash="d" * 64,
            source_refs=(),
            result_status=AgentToolCallStatus.SUCCEEDED,
            error_code=None,
            latency_ms=10,
            now=old,
        )
        await self.repository.complete_invocation(
            invocation_id=begun.invocation.invocation_id,
            actor_user_id=ACTOR_A,
            body="The current listing is active.",
            resolution_type=AgentResolutionType.ANSWERED,
            sources=(),
            actions=(),
            input_tokens=10,
            output_tokens=5,
            latency_ms=20,
            estimated_cost=Decimal("0.00000100"),
            now=old,
        )

        first = await self.repository.apply_retention(now=NOW)
        self.assertEqual(1, first.sessions_purged)
        self.assertEqual(2, first.messages_deleted)
        self.assertEqual(1, first.deduplication_keys_redacted)
        self.assertEqual(0, first.audit_invocations_deleted)
        retained = await self.repository.get_session(session.session_id, ACTOR_A)
        assert retained is not None
        self.assertEqual(AgentSessionStatus.CLOSED, retained.status)
        self.assertIsNotNone(retained.content_purged_at)

        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute(
                    """
                    SELECT user_message_id, assistant_message_id,
                           client_message_id, request_hash
                    FROM agent_invocations
                    WHERE invocation_id = %s
                    """,
                    (begun.invocation.invocation_id,),
                )
                self.assertEqual((None, None, None, None), await cursor.fetchone())
                await cursor.execute(
                    """
                    UPDATE agent_invocations
                    SET created_at = %s
                    WHERE invocation_id = %s
                    """,
                    (
                        (NOW - timedelta(days=400)).replace(tzinfo=None),
                        begun.invocation.invocation_id,
                    ),
                )
            await connection.commit()

        second = await self.repository.apply_retention(now=NOW)
        self.assertEqual(1, second.audit_invocations_deleted)
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                await cursor.execute("SELECT COUNT(*) FROM agent_tool_calls")
                self.assertEqual(0, (await cursor.fetchone())[0])

    async def test_listing_proposal_claim_replay_isolation_and_retention(self) -> None:
        proposals = ListingProposalRepository(
            self.repository.pool,
            ListingProposalMetrics(CollectorRegistry()),
        )
        await proposals.validate_schema()
        create = CreateListingProposalRequest(
            schemaVersion="LISTING_PROPOSAL_V1",
            listingId=LISTING_ID,
            expectedListingVersion=12,
            mediaIds=("01M00000000000000000000001",),
            clientRequestId="mysql-request-001",
        )
        first, second = await asyncio.gather(
            proposals.reserve_create(
                actor_user_id=ACTOR_A,
                request=create,
                now=NOW,
            ),
            proposals.reserve_create(
                actor_user_id=ACTOR_A,
                request=create,
                now=NOW,
            ),
        )
        owner = first if first.result == ProposalReservationResult.OWNER else second
        other = second if owner is first else first
        self.assertEqual(ProposalReservationResult.OWNER, owner.result)
        self.assertEqual(ProposalReservationResult.IN_PROGRESS, other.result)
        assert owner.claim_token is not None
        stored = await proposals.complete_create(
            actor_user_id=ACTOR_A,
            request=create,
            claim_token=owner.claim_token,
            generated=GeneratedListingProposal(
                sourceListingVersion=12,
                sourceMediaEvidence=(
                    SourceMediaEvidence(
                        mediaId="01M00000000000000000000001",
                        evidenceId="E1",
                        sha256="a" * 64,
                        actualMime="image/png",
                        byteSize=100,
                    ),
                ),
                proposal=ListingContentProposal(
                    unknown_fields=tuple(UnknownField),
                ),
                resultMetadata=ListingProposalResultMetadata(
                    instructionVersion=VISION_INSTRUCTION_VERSION,
                    schemaVersion="ai-list-proposal-v1",
                    providerMode="FAKE",
                    resultCode="SUCCEEDED",
                    latencyMs=1,
                    inputTokens=0,
                    outputTokens=0,
                ),
            ),
            now=NOW,
        )
        replay = await proposals.reserve_create(
            actor_user_id=ACTOR_A,
            request=create,
            now=NOW + timedelta(seconds=1),
        )
        self.assertEqual(ProposalReservationResult.REPLAY, replay.result)
        self.assertEqual(stored.proposal_id, replay.proposal.proposal_id)
        self.assertIsNone(
            await proposals.get_owned(
                proposal_id=stored.proposal_id,
                actor_user_id=ACTOR_B,
                now=NOW,
            )
        )

        dismissed = await proposals.dismiss(
            proposal_id=stored.proposal_id,
            actor_user_id=ACTOR_A,
            idempotency_key="mysql-dismiss-001",
            now=NOW + timedelta(minutes=1),
        )
        assert dismissed is not None
        self.assertEqual("DISMISSED", dismissed.status)
        self.assertIsNone(dismissed.proposal)
        retried = await proposals.dismiss(
            proposal_id=stored.proposal_id,
            actor_user_id=ACTOR_A,
            idempotency_key="mysql-dismiss-001",
            now=NOW + timedelta(minutes=2),
        )
        self.assertEqual(dismissed.proposal_version, retried.proposal_version)

        await proposals.apply_retention(
            now=NOW + timedelta(days=91),
            batch_size=100,
        )
        self.assertIsNone(
            await proposals.get_owned(
                proposal_id=stored.proposal_id,
                actor_user_id=ACTOR_A,
                now=NOW + timedelta(days=91),
            )
        )

    async def _session(self, now: datetime = NOW):
        return await self.repository.create_or_resume_session(
            actor_user_id=ACTOR_A,
            subject_listing_id=LISTING_ID,
            now=now,
            correlation_id="integration-session",
        )

    async def _begin(
        self,
        session_id: str,
        client_message_id: str,
        body: str,
        *,
        now: datetime = NOW,
    ):
        return await self.repository.begin_invocation(
            session_id=session_id,
            actor_user_id=ACTOR_A,
            client_message_id=client_message_id,
            body=body,
            prompt_version="listing-customer-service-v1",
            model_provider="openai",
            model_name="gpt-5-mini",
            schema_version="agent-answer-v1",
            tool_registry_version="listing-read-tools-v1",
            policy_version="customer-service-policy-v1",
            correlation_id="integration-question",
            now=now,
        )

    async def _connect_repository(self) -> AgentPersistenceRepository:
        deadline = asyncio.get_running_loop().time() + 60
        while True:
            try:
                return await AgentPersistenceRepository.create(self.settings)
            except Exception:
                if asyncio.get_running_loop().time() >= deadline:
                    raise
                await asyncio.sleep(1)

    async def _apply_migrations(self) -> None:
        migration_directory = Path(__file__).parents[1] / "db" / "migration"
        async with self.repository._pool.acquire() as connection:  # noqa: SLF001
            async with connection.cursor() as cursor:
                for migration_path in sorted(migration_directory.glob("V*.sql")):
                    statements = [
                        statement.strip()
                        for statement in migration_path.read_text(
                            encoding="utf-8"
                        ).split(";")
                        if statement.strip()
                    ]
                    for statement in statements:
                        await cursor.execute(statement)
            await connection.commit()


if __name__ == "__main__":
    unittest.main()
