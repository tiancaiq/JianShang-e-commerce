from __future__ import annotations

import asyncio
import os
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import aiomysql
from testcontainers.core.container import DockerContainer

from msb_agent_service.agent_persistence import (
    AgentInvocationStatus,
    AgentPersistenceError,
    AgentPersistenceErrorCode,
    AgentPersistenceRepository,
    AgentResolutionType,
    BeginInvocationResult,
)
from msb_agent_service.config import AgentPersistenceSettings
from msb_agent_service.discovery_persistence import DiscoveryPersistenceRepository
from msb_agent_service.marketplace_discovery import (
    DiscoveryPreferenceState,
    DiscoveryProvenance,
    DiscoveryRecommendation,
    DiscoveryTurnResponse,
)

RUN_INTEGRATION = os.getenv("RUN_MYSQL_INTEGRATION") == "1"
ACTOR_A = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
ACTOR_B = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
NOW = datetime(2026, 7, 21, 12, 0, tzinfo=UTC)


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_MYSQL_INTEGRATION=1 to run MySQL integration tests",
)
class DiscoveryPersistenceIntegrationTest(unittest.IsolatedAsyncioTestCase):
    """Proves the V7 discovery contract against a real MySQL transaction engine."""

    container: DockerContainer
    settings: AgentPersistenceSettings
    v7_upgrade_observed = False

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
            mysql_pool_max_size=8,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    async def asyncSetUp(self) -> None:
        self.repository = await self._connect_repository()
        try:
            await self.repository.validate_schema()
        except AgentPersistenceError:
            await self._apply_migrations(through_version=6)
            await self.repository.validate_schema()
            pre_v7 = DiscoveryPersistenceRepository(self.repository)
            with self.assertRaises(AgentPersistenceError) as missing:
                await pre_v7.validate_schema()
            self.assertEqual(
                AgentPersistenceErrorCode.SCHEMA_NOT_MIGRATED,
                missing.exception.code,
            )
            await self._apply_migrations(from_version=7)
            type(self).v7_upgrade_observed = True
        self.discovery = DiscoveryPersistenceRepository(self.repository)
        await self.discovery.validate_schema()
        await self._clear_agent_state()

    async def asyncTearDown(self) -> None:
        await self.repository.close()

    async def test_v7_applies_after_v1_through_v6_and_validates_schema(self) -> None:
        self.assertTrue(type(self).v7_upgrade_observed)
        async with self.repository.pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_sessions'
                          AND column_name IN (
                              'preference_state_json',
                              'preference_version',
                              'clarification_turn_count',
                              'clarification_question_count',
                              'discovery_open_marker'
                          )
                        """
                    )
                    columns = {str(row[0]) for row in await cursor.fetchall()}
                    await cursor.execute(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name = 'agent_discovery_recommendations'
                        """
                    )
                    recommendation_table = int((await cursor.fetchone())[0])
            finally:
                await connection.rollback()

        self.assertEqual(
            {
                "preference_state_json",
                "preference_version",
                "clarification_turn_count",
                "clarification_question_count",
                "discovery_open_marker",
            },
            columns,
        )
        self.assertEqual(1, recommendation_table)

    async def test_one_open_session_new_search_and_actor_isolation_are_atomic(
        self,
    ) -> None:
        first, second = await asyncio.gather(
            self.discovery.create_or_resume(
                actor_user_id=ACTOR_A,
                new_search=False,
                now=NOW,
            ),
            self.discovery.create_or_resume(
                actor_user_id=ACTOR_A,
                new_search=False,
                now=NOW,
            ),
        )
        (first_session, first_created), (second_session, second_created) = (
            first,
            second,
        )

        self.assertEqual(first_session.session_id, second_session.session_id)
        self.assertEqual(1, sum((first_created, second_created)))
        self.assertIsNone(
            await self.discovery.get(
                session_id=first_session.session_id,
                actor_user_id=ACTOR_B,
            )
        )

        replacement, created = await self.discovery.create_or_resume(
            actor_user_id=ACTOR_A,
            new_search=True,
            now=NOW + timedelta(seconds=1),
        )
        self.assertTrue(created)
        self.assertNotEqual(first_session.session_id, replacement.session_id)
        closed = await self.discovery.get(
            session_id=first_session.session_id,
            actor_user_id=ACTOR_A,
        )
        assert closed is not None
        self.assertEqual("CLOSED", closed.status.value)

        counts = await self._rows(
            """
            SELECT status, COUNT(*)
            FROM agent_sessions
            WHERE actor_user_id = %s
              AND session_type = 'MARKETPLACE_DISCOVERY'
            GROUP BY status
            """,
            (ACTOR_A,),
        )
        self.assertEqual({"CLOSED": 1, "OPEN": 1}, dict(counts))

    async def test_create_message_idempotency_hash_conflict_and_concurrent_same_key(
        self,
    ) -> None:
        session = await self._session()
        client_message_id = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
        first, second = await asyncio.gather(
            self._begin(session.session_id, client_message_id, "Find a desk lamp"),
            self._begin(session.session_id, client_message_id, "Find a desk lamp"),
        )

        self.assertEqual(
            {BeginInvocationResult.CREATED, BeginInvocationResult.DEDUPLICATED_PENDING},
            {first.result, second.result},
        )
        self.assertEqual(first.invocation.invocation_id, second.invocation.invocation_id)
        self.assertEqual(
            1,
            await self._scalar(
                "SELECT COUNT(*) FROM agent_messages WHERE session_id = %s",
                (session.session_id,),
            ),
        )

        with self.assertRaises(AgentPersistenceError) as conflict:
            await self._begin(
                session.session_id,
                client_message_id,
                "Find a different item",
            )
        self.assertEqual(
            AgentPersistenceErrorCode.REQUEST_HASH_CONFLICT,
            conflict.exception.code,
        )
        with self.assertRaises(AgentPersistenceError) as hidden:
            await self.repository.begin_invocation(
                session_id=session.session_id,
                actor_user_id=ACTOR_B,
                client_message_id="01ARZ3NDEKTSV4RRFFQ69G5FB2",
                body="Cross actor attempt",
                prompt_version="marketplace-discovery-react-v1",
                model_provider="injected",
                model_name="injected",
                schema_version="discovery-turn-v1",
                tool_registry_version="marketplace-discovery-tools-v1",
                policy_version="ai-disc-01a",
                correlation_id="discovery-mysql-hidden",
                now=NOW,
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_NOT_FOUND,
            hidden.exception.code,
        )

    async def test_complete_turn_is_atomic_and_recommendations_survive_restart(
        self,
    ) -> None:
        session = await self._session()
        begun = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB3",
            "Find sleep-comfort products in Irvine",
        )
        response = _recommendation_response("A")

        updated, assistant = await self.discovery.complete_turn(
            invocation_id=begun.invocation.invocation_id,
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            expected_preference_version=0,
            response=response,
            resolution_type=AgentResolutionType.RECOMMEND,
            clarification_turn_increment=0,
            clarification_question_increment=0,
            latency_ms=125,
            now=NOW + timedelta(seconds=1),
        )

        self.assertEqual(1, updated.preference_version)
        self.assertEqual("ASSISTANT", assistant.role.value)
        self.assertEqual(
            3,
            await self._scalar(
                """
                SELECT COUNT(*)
                FROM agent_discovery_recommendations
                WHERE invocation_id = %s
                """,
                (begun.invocation.invocation_id,),
            ),
        )
        self.assertEqual(
            "SUCCEEDED",
            await self._scalar(
                "SELECT result_status FROM agent_invocations WHERE invocation_id = %s",
                (begun.invocation.invocation_id,),
            ),
        )

        await self.repository.close()
        self.repository = await self._connect_repository()
        self.discovery = DiscoveryPersistenceRepository(self.repository)
        await self.discovery.validate_schema()
        restarted = await self.discovery.get(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
        )
        assert restarted is not None
        self.assertEqual(1, restarted.preference_version)
        messages = await self.repository.list_messages(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            limit=10,
        )
        self.assertEqual(("USER", "ASSISTANT"), tuple(m.role.value for m in messages.messages))

    async def test_concurrent_preference_version_allows_exactly_one_completion(
        self,
    ) -> None:
        session = await self._session()
        first = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB4",
            "Find lamps",
        )
        second = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB5",
            "Prefer compact lamps",
        )
        results = await asyncio.gather(
            self.discovery.complete_turn(
                invocation_id=first.invocation.invocation_id,
                session_id=session.session_id,
                actor_user_id=ACTOR_A,
                expected_preference_version=0,
                response=_recommendation_response("B"),
                resolution_type=AgentResolutionType.RECOMMEND,
                clarification_turn_increment=0,
                clarification_question_increment=0,
                latency_ms=100,
                now=NOW + timedelta(seconds=1),
            ),
            self.discovery.complete_turn(
                invocation_id=second.invocation.invocation_id,
                session_id=session.session_id,
                actor_user_id=ACTOR_A,
                expected_preference_version=0,
                response=_recommendation_response("C"),
                resolution_type=AgentResolutionType.RECOMMEND,
                clarification_turn_increment=0,
                clarification_question_increment=0,
                latency_ms=101,
                now=NOW + timedelta(seconds=1),
            ),
            return_exceptions=True,
        )

        failures = [item for item in results if isinstance(item, Exception)]
        successes = [item for item in results if not isinstance(item, Exception)]
        self.assertEqual(1, len(successes))
        self.assertEqual(1, len(failures))
        self.assertIsInstance(failures[0], AgentPersistenceError)
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_VERSION_CONFLICT,
            failures[0].code,
        )
        self.assertEqual(
            {"PENDING": 1, "SUCCEEDED": 1},
            dict(
                await self._rows(
                    """
                    SELECT result_status, COUNT(*)
                    FROM agent_invocations
                    GROUP BY result_status
                    """
                )
            ),
        )
        self.assertEqual(
            3,
            await self._scalar("SELECT COUNT(*) FROM agent_discovery_recommendations"),
        )

    async def test_failed_recommendation_insert_rolls_back_entire_turn(self) -> None:
        session = await self._session()
        begun = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB6",
            "Find matching products",
        )
        valid = _recommendations("D")
        invalid = DiscoveryTurnResponse(
            outcome="RECOMMEND",
            message="These items match the request.",
            recommendations=(valid[0], valid[0], valid[1]),
            preferenceState=DiscoveryPreferenceState(query="sleep comfort"),
        )

        with self.assertRaises(aiomysql.IntegrityError):
            await self.discovery.complete_turn(
                invocation_id=begun.invocation.invocation_id,
                session_id=session.session_id,
                actor_user_id=ACTOR_A,
                expected_preference_version=0,
                response=invalid,
                resolution_type=AgentResolutionType.RECOMMEND,
                clarification_turn_increment=0,
                clarification_question_increment=0,
                latency_ms=100,
                now=NOW + timedelta(seconds=1),
            )

        unchanged = await self.discovery.get(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
        )
        assert unchanged is not None
        self.assertEqual(0, unchanged.preference_version)
        self.assertEqual(
            "PENDING",
            await self._scalar(
                "SELECT result_status FROM agent_invocations WHERE invocation_id = %s",
                (begun.invocation.invocation_id,),
            ),
        )
        self.assertEqual(
            1,
            await self._scalar(
                "SELECT COUNT(*) FROM agent_messages WHERE session_id = %s",
                (session.session_id,),
            ),
        )
        self.assertEqual(
            0,
            await self._scalar("SELECT COUNT(*) FROM agent_discovery_recommendations"),
        )

    async def test_history_cursor_and_retention_purge_discovery_content(self) -> None:
        old = NOW - timedelta(days=91)
        session, _ = await self.discovery.create_or_resume(
            actor_user_id=ACTOR_A,
            new_search=False,
            now=old,
        )
        first = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB7",
            "Find sleep-comfort products",
            now=old,
        )
        await self.discovery.complete_turn(
            invocation_id=first.invocation.invocation_id,
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            expected_preference_version=0,
            response=_recommendation_response("E"),
            resolution_type=AgentResolutionType.RECOMMEND,
            clarification_turn_increment=0,
            clarification_question_increment=0,
            latency_ms=90,
            now=old + timedelta(seconds=1),
        )
        second = await self._begin(
            session.session_id,
            "01ARZ3NDEKTSV4RRFFQ69G5FB8",
            "Only in Irvine",
            now=old + timedelta(seconds=2),
        )
        clarify = DiscoveryTurnResponse(
            outcome="ASK_CLARIFY",
            message="What budget should I use?",
            questions=("What budget should I use?",),
            preferenceState=DiscoveryPreferenceState(
                query="sleep comfort",
                city="Irvine",
            ),
        )
        await self.discovery.complete_turn(
            invocation_id=second.invocation.invocation_id,
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
            expected_preference_version=1,
            response=clarify,
            resolution_type=AgentResolutionType.CLARIFY,
            clarification_turn_increment=1,
            clarification_question_increment=1,
            latency_ms=80,
            now=old + timedelta(seconds=3),
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
        self.assertTrue(
            set(message.message_id for message in first_page.messages).isdisjoint(
                message.message_id for message in second_page.messages
            )
        )
        with self.assertRaises(AgentPersistenceError) as hidden:
            await self.repository.list_messages(
                session_id=session.session_id,
                actor_user_id=ACTOR_B,
                limit=2,
            )
        self.assertEqual(
            AgentPersistenceErrorCode.SESSION_NOT_FOUND,
            hidden.exception.code,
        )

        retained = await self.repository.apply_retention(now=NOW)
        self.assertEqual(1, retained.sessions_purged)
        self.assertEqual(4, retained.messages_deleted)
        self.assertEqual(3, retained.discovery_recommendations_deleted)
        self.assertEqual(2, retained.deduplication_keys_redacted)
        purged = await self.discovery.get(
            session_id=session.session_id,
            actor_user_id=ACTOR_A,
        )
        assert purged is not None
        self.assertEqual("CLOSED", purged.status.value)
        self.assertEqual(
            0,
            await self._scalar(
                "SELECT COUNT(*) FROM agent_messages WHERE session_id = %s",
                (session.session_id,),
            ),
        )

    async def _session(self):
        session, created = await self.discovery.create_or_resume(
            actor_user_id=ACTOR_A,
            new_search=False,
            now=NOW,
        )
        self.assertTrue(created)
        return session

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
            prompt_version="marketplace-discovery-react-v1",
            model_provider="injected",
            model_name="injected",
            schema_version="discovery-turn-v1",
            tool_registry_version="marketplace-discovery-tools-v1",
            policy_version="ai-disc-01a",
            correlation_id="discovery-mysql",
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

    async def _apply_migrations(
        self,
        *,
        from_version: int = 1,
        through_version: int = 7,
    ) -> None:
        """Apply the same forward migration sequence used by Agent CI fixtures."""

        migration_directory = Path(__file__).parents[1] / "db" / "migration"
        migrations = sorted(
            migration_directory.glob("V*.sql"),
            key=lambda path: int(path.name.split("__", 1)[0][1:]),
        )
        selected = [
            path
            for path in migrations
            if from_version
            <= int(path.name.split("__", 1)[0][1:])
            <= through_version
        ]
        async with self.repository.pool.acquire() as connection:
            async with connection.cursor() as cursor:
                for migration_path in selected:
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

    async def _clear_agent_state(self) -> None:
        async with self.repository.pool.acquire() as connection:
            async with connection.cursor() as cursor:
                for table in (
                    "agent_discovery_recommendations",
                    "agent_listing_proposal_dismissals",
                    "agent_listing_proposals",
                    "agent_listing_proposal_claims",
                    "agent_tool_calls",
                    "agent_invocations",
                    "agent_messages",
                    "agent_sessions",
                ):
                    await cursor.execute(f"DELETE FROM {table}")
            await connection.commit()

    async def _scalar(
        self,
        query: str,
        parameters: tuple[object, ...] = (),
    ) -> object:
        rows = await self._rows(query, parameters)
        return rows[0][0]

    async def _rows(
        self,
        query: str,
        parameters: tuple[object, ...] = (),
    ) -> tuple[tuple[object, ...], ...]:
        async with self.repository.pool.acquire() as connection:
            try:
                async with connection.cursor() as cursor:
                    await cursor.execute(query, parameters)
                    return tuple(await cursor.fetchall())
            finally:
                await connection.rollback()


def _recommendation_response(suffix: str) -> DiscoveryTurnResponse:
    return DiscoveryTurnResponse(
        outcome="RECOMMEND",
        message="These detail-checked listings match the request.",
        recommendations=_recommendations(suffix),
        preferenceState=DiscoveryPreferenceState(
            query="sleep comfort",
            city="Irvine",
        ),
        inputTokens=0,
        outputTokens=0,
        estimatedCost=Decimal("0"),
    )


def _recommendations(suffix: str) -> tuple[DiscoveryRecommendation, ...]:
    return tuple(
        _recommendation(
            listing_id=f"01ARZ3NDEKTSV4RRFFQ69{suffix}{rank:04d}",
            category_id=f"01ARZ3NDEKTSV4RRFFQ68{suffix}{rank:04d}",
            rank=rank,
        )
        for rank in range(1, 4)
    )


def _recommendation(
    *,
    listing_id: str,
    category_id: str,
    rank: int,
) -> DiscoveryRecommendation:
    return DiscoveryRecommendation(
        listingId=listing_id,
        title=f"Comfort item {rank}",
        categoryId=category_id,
        categoryName="Home comfort",
        condition="GOOD",
        priceAmount=Decimal("25.00") + rank,
        currency="USD",
        publicCity="Irvine",
        publicRegion="Orange County",
        matchReason="Matches the stated city and comfort preference.",
        constraintCoverage=("QUERY", "CITY"),
        provenance=DiscoveryProvenance(
            listingId=listing_id,
            checkedAt=NOW,
            responseHash=(str(rank) * 64),
        ),
    )


if __name__ == "__main__":
    unittest.main()
