from __future__ import annotations

import asyncio
import os
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from testcontainers.core.container import DockerContainer

from msb_agent_service.agent_persistence import AgentPersistenceRepository
from msb_agent_service.config import AgentPersistenceSettings
from msb_agent_service.marketplace_agent_v2.confirmations import (
    ConfirmationDecisionCode,
    ConfirmationExecution,
    ConfirmationFinancialFact,
    ConfirmationState,
    ConfirmationTarget,
    ConsequentialConfirmationRepository,
    PrepareConfirmation,
)
from msb_agent_service.marketplace_agent_v2.persistence import (
    MarketplaceAgentV2Persistence,
)


RUN_INTEGRATION = os.getenv("RUN_MYSQL_INTEGRATION") == "1"
ACTOR_A = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
ACTOR_B = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
OTHER_SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAA"
NOW = datetime(2026, 9, 2, 12, 0, tzinfo=UTC)


@unittest.skipUnless(
    RUN_INTEGRATION,
    "set RUN_MYSQL_INTEGRATION=1 to run MySQL confirmation integration tests",
)
class ConsequentialConfirmationMySqlIntegrationTest(
    unittest.IsolatedAsyncioTestCase
):
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
            mysql_pool_max_size=8,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.container.stop()

    async def asyncSetUp(self) -> None:
        self.repository = await self._connect_repository()
        await self._apply_migrations_once()
        self.v2 = MarketplaceAgentV2Persistence(
            self.repository, clock=lambda: NOW
        )
        await self.v2.validate_schema()
        self.confirmations = ConsequentialConfirmationRepository(
            self.repository.pool
        )
        await self._clear()
        self.session_a, _ = await self.v2.create_or_resume(
            actor_user_id=ACTOR_A, new_conversation=False, now=NOW
        )
        self.session_b, _ = await self.v2.create_or_resume(
            actor_user_id=ACTOR_B, new_conversation=False, now=NOW
        )

    async def asyncTearDown(self) -> None:
        await self.repository.close()

    async def test_binding_expiry_cancellation_and_actor_isolation(self) -> None:
        origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FAX",
            body="Prepare test action",
        )
        record = await self._prepare(
            origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
        )

        self.assertEqual(ConfirmationState.PENDING, record.state)
        self.assertEqual(ACTOR_A, record.actor_user_id)
        self.assertEqual(self.session_a.session_id, record.session_id)
        self.assertEqual(origin.invocation.invocation_id, record.originating_invocation_id)
        self.assertEqual("place_order", record.capability)
        self.assertEqual("17", record.targets[0].version_token)
        self.assertEqual(Decimal("49.99"), record.financial_facts[0].amount)
        self.assertEqual(f"agent-action-{record.confirmation_id}", record.action_key)
        self.assertIsNone(await self.confirmations.get_owned(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_B,
            session_id=self.session_b.session_id,
        ))
        self.assertIsNone(await self.confirmations.get_owned(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=OTHER_SESSION,
        ))

        retry = await self._prepare(
            origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
        )
        self.assertEqual(record.confirmation_id, retry.confirmation_id)
        self.assertEqual(record.action_key, retry.action_key)

        confirming = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FAY",
            body="Yes",
            now=NOW + timedelta(seconds=1),
        )
        confirmed = await self.confirmations.confirm(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=confirming.invocation.invocation_id,
            correlation_id="confirm-owned",
            now=NOW + timedelta(seconds=1),
        )
        self.assertTrue(confirmed.allowed)
        cancelled = await self.confirmations.cancel(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            invocation_id=confirming.invocation.invocation_id,
            correlation_id="cancel-owned",
            now=NOW + timedelta(seconds=2),
        )
        self.assertEqual(ConfirmationDecisionCode.CANCELLED, cancelled.code)
        revived = await self.confirmations.confirm(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=confirming.invocation.invocation_id,
            correlation_id="confirm-after-cancel",
            now=NOW + timedelta(seconds=3),
        )
        self.assertFalse(revived.allowed)
        self.assertEqual(ConfirmationDecisionCode.CANCELLED, revived.code)

        expired_origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FAZ",
            body="Prepare expiring action",
            now=NOW + timedelta(seconds=4),
        )
        expired = await self._prepare(
            expired_origin.invocation.invocation_id,
            expires_at=NOW + timedelta(seconds=5),
            now=NOW + timedelta(seconds=4),
        )
        expiring_v2 = MarketplaceAgentV2Persistence(
            self.repository, clock=lambda: NOW + timedelta(seconds=6)
        )
        refreshed = await expiring_v2.get(
            session_id=self.session_a.session_id,
            actor_user_id=ACTOR_A,
        )
        self.assertEqual(
            "EXPIRED",
            refreshed.preference_state["pendingInteraction"]["status"],
        )
        stored_expired = await self.confirmations.get_owned(
            confirmation_id=expired.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
        )
        self.assertEqual(ConfirmationState.EXPIRED, stored_expired.state)

    async def test_concurrent_consume_is_atomic_and_single_use(self) -> None:
        origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB0",
            body="Prepare concurrent action",
        )
        record = await self._prepare(
            origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
        )
        first = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB1",
            body="Yes",
            now=NOW + timedelta(seconds=1),
        )
        second = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB2",
            body="Yes",
            now=NOW + timedelta(seconds=1),
        )
        await self.confirmations.confirm(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=first.invocation.invocation_id,
            correlation_id="concurrent-confirm",
            now=NOW + timedelta(seconds=1),
        )

        def execution(invocation_id: str, correlation: str) -> ConfirmationExecution:
            return ConfirmationExecution(
                confirmation_id=record.confirmation_id,
                actor_user_id=ACTOR_A,
                session_id=self.session_a.session_id,
                consuming_invocation_id=invocation_id,
                capability=record.capability,
                capability_version=record.capability_version,
                action_name=record.action_name,
                normalized_arguments=record.normalized_arguments,
                targets=record.targets,
                financial_facts=record.financial_facts,
                current_resource_versions={"CHECKOUT:CHK-123": "17"},
                correlation_id=correlation,
            )

        outcomes = await asyncio.gather(
            self.confirmations.consume(
                execution(first.invocation.invocation_id, "consume-a"),
                now=NOW + timedelta(seconds=2),
            ),
            self.confirmations.consume(
                execution(second.invocation.invocation_id, "consume-b"),
                now=NOW + timedelta(seconds=2),
            ),
        )
        self.assertEqual(1, sum(item.allowed for item in outcomes))
        self.assertEqual(
            {ConfirmationDecisionCode.ALLOWED, ConfirmationDecisionCode.ALREADY_CONSUMED},
            {item.code for item in outcomes},
        )
        stored = await self.confirmations.get_owned(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
        )
        self.assertEqual(ConfirmationState.CONSUMED, stored.state)

    async def test_changed_resource_version_policy_and_authorization_fail_closed(self) -> None:
        origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB3",
            body="Prepare stale action",
        )
        record = await self._prepare(
            origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
        )
        confirming = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB4",
            body="Confirm",
            now=NOW + timedelta(seconds=1),
        )
        await self.confirmations.confirm(
            confirmation_id=record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=confirming.invocation.invocation_id,
            correlation_id="stale-confirm",
            now=NOW + timedelta(seconds=1),
        )
        stale = await self.confirmations.consume(
            ConfirmationExecution(
                confirmation_id=record.confirmation_id,
                actor_user_id=ACTOR_A,
                session_id=self.session_a.session_id,
                consuming_invocation_id=confirming.invocation.invocation_id,
                capability=record.capability,
                capability_version=record.capability_version,
                action_name=record.action_name,
                normalized_arguments=record.normalized_arguments,
                targets=record.targets,
                financial_facts=record.financial_facts,
                current_resource_versions={"CHECKOUT:CHK-123": "18"},
                correlation_id="stale-consume",
            ),
            now=NOW + timedelta(seconds=2),
        )
        self.assertFalse(stale.allowed)
        self.assertEqual(
            ConfirmationDecisionCode.RESOURCE_VERSION_MISMATCH, stale.code
        )
        self.assertEqual(ConfirmationState.INVALIDATED, stale.confirmation.state)

        policy_origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB6",
            body="Prepare disabled action",
            now=NOW + timedelta(seconds=3),
        )
        policy_record = await self._prepare(
            policy_origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
            now=NOW + timedelta(seconds=3),
        )
        policy_confirm = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB7",
            body="Go ahead",
            now=NOW + timedelta(seconds=4),
        )
        await self.confirmations.confirm(
            confirmation_id=policy_record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=policy_confirm.invocation.invocation_id,
            correlation_id="policy-confirm",
            now=NOW + timedelta(seconds=4),
        )
        denied = await self.confirmations.consume(
            ConfirmationExecution(
                confirmation_id=policy_record.confirmation_id,
                actor_user_id=ACTOR_A,
                session_id=self.session_a.session_id,
                consuming_invocation_id=policy_confirm.invocation.invocation_id,
                capability=policy_record.capability,
                capability_version=policy_record.capability_version,
                action_name=policy_record.action_name,
                normalized_arguments=policy_record.normalized_arguments,
                targets=policy_record.targets,
                financial_facts=policy_record.financial_facts,
                policy_allowed=False,
                correlation_id="policy-disabled-consume",
            ),
            now=NOW + timedelta(seconds=5),
        )
        self.assertEqual(ConfirmationDecisionCode.POLICY_DENIED, denied.code)
        self.assertEqual(ConfirmationState.INVALIDATED, denied.confirmation.state)

        authorization_origin = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB8",
            body="Prepare unauthorized action",
            now=NOW + timedelta(seconds=6),
        )
        authorization_record = await self._prepare(
            authorization_origin.invocation.invocation_id,
            expires_at=NOW + timedelta(minutes=15),
            now=NOW + timedelta(seconds=6),
        )
        authorization_confirm = await self._begin(
            actor=ACTOR_A,
            session_id=self.session_a.session_id,
            client_id="01ARZ3NDEKTSV4RRFFQ69G5FB9",
            body="Do it",
            now=NOW + timedelta(seconds=7),
        )
        await self.confirmations.confirm(
            confirmation_id=authorization_record.confirmation_id,
            actor_user_id=ACTOR_A,
            session_id=self.session_a.session_id,
            confirming_invocation_id=authorization_confirm.invocation.invocation_id,
            correlation_id="authorization-confirm",
            now=NOW + timedelta(seconds=7),
        )
        unauthorized = await self.confirmations.consume(
            ConfirmationExecution(
                confirmation_id=authorization_record.confirmation_id,
                actor_user_id=ACTOR_A,
                session_id=self.session_a.session_id,
                consuming_invocation_id=authorization_confirm.invocation.invocation_id,
                capability=authorization_record.capability,
                capability_version=authorization_record.capability_version,
                action_name=authorization_record.action_name,
                normalized_arguments=authorization_record.normalized_arguments,
                targets=authorization_record.targets,
                financial_facts=authorization_record.financial_facts,
                authorization_allowed=False,
                correlation_id="authorization-denied-consume",
            ),
            now=NOW + timedelta(seconds=8),
        )
        self.assertEqual(
            ConfirmationDecisionCode.AUTHORIZATION_DENIED, unauthorized.code
        )
        self.assertEqual(
            ConfirmationState.INVALIDATED, unauthorized.confirmation.state
        )

    async def _prepare(
        self,
        origin_invocation_id: str,
        *,
        expires_at: datetime,
        now: datetime = NOW,
    ):
        confirmation_id = "01ARZ3NDEKTSV4RRFFQ69G5FB5"
        return await self.confirmations.prepare(
            PrepareConfirmation(
                confirmation_id=None,
                actor_user_id=ACTOR_A,
                session_id=self.session_a.session_id,
                originating_invocation_id=origin_invocation_id,
                workflow_id="TEST_ONLY_CONSEQUENTIAL_HARNESS",
                capability="place_order",
                capability_version="test-v1",
                action_name="PLACE_ORDER",
                normalized_arguments={"checkoutId": "CHK-123", "quantity": 1},
                targets=(ConfirmationTarget("CHECKOUT", "CHK-123", "17"),),
                financial_facts=(
                    ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "USD"),
                ),
                human_summary="Place the prepared test order for $49.99 USD.",
                risk_level=3,
                expires_at=expires_at,
                correlation_id="confirmation-prepare",
            ),
            pending_interaction={
                "id": confirmation_id,
                "confirmationId": confirmation_id,
                "type": "CONFIRM_ACTION",
                "action": "RUN_REFINED_SEARCH",
                "arguments": {},
                "summary": "Prepared test action.",
                "status": "WAITING",
                "createdAt": now.isoformat(),
            },
            now=now,
        )

    async def _begin(
        self,
        *,
        actor: str,
        session_id: str,
        client_id: str,
        body: str,
        now: datetime = NOW,
    ):
        return await self.repository.begin_invocation(
            session_id=session_id,
            actor_user_id=actor,
            client_message_id=client_id,
            body=body,
            prompt_version="ai-conf-test",
            model_provider="fake",
            model_name="fake",
            schema_version="ai-conf-01-v1",
            tool_registry_version="no-production-test-tool",
            policy_version="ai-conf-01-v1",
            correlation_id="confirmation-integration",
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

    async def _apply_migrations_once(self) -> None:
        async with self.repository.pool.acquire() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    "WHERE table_schema = DATABASE() AND table_name = 'agent_confirmations'"
                )
                if int((await cursor.fetchone())[0]) == 1:
                    return
                migration_directory = Path(__file__).parents[1] / "db" / "migration"
                for migration_path in sorted(
                    migration_directory.glob("V*.sql"),
                    key=lambda path: int(path.name.split("__", 1)[0][1:]),
                ):
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

    async def _clear(self) -> None:
        async with self.repository.pool.acquire() as connection:
            async with connection.cursor() as cursor:
                for table in (
                    "agent_confirmation_transitions",
                    "agent_confirmations",
                    "agent_tool_calls",
                    "agent_invocations",
                    "agent_messages",
                    "agent_sessions",
                ):
                    await cursor.execute(f"DELETE FROM {table}")
            await connection.commit()


if __name__ == "__main__":
    unittest.main()
