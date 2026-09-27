from __future__ import annotations

import json
import unittest
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from msb_agent_service.marketplace_agent_v2.confirmations import (
    ConfirmationExecution,
    ConfirmationFinancialFact,
    ConfirmationRecord,
    ConfirmationState,
    ConfirmationTarget,
    PrepareConfirmation,
    _execution_mismatch,
    action_fingerprint,
    canonical_arguments,
    expiry_from_ttl,
)


ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
SESSION = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
ORIGIN = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
CONSUMER = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
CONFIRMATION = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
NOW = datetime(2026, 9, 2, 12, 0, tzinfo=UTC)


class ConsequentialConfirmationContractTest(unittest.TestCase):
    def test_normalized_arguments_are_order_independent_and_typed(self) -> None:
        first, first_json = canonical_arguments({
            "quantity": 2,
            "checkout": {"currency": "USD", "amount": Decimal("49.990")},
        })
        second, second_json = canonical_arguments({
            "checkout": {"amount": Decimal("49.99"), "currency": "USD"},
            "quantity": 2,
        })

        self.assertEqual(first, second)
        self.assertEqual(first_json, second_json)

    def test_action_fingerprint_binds_capability_arguments_target_and_money(self) -> None:
        target = ConfirmationTarget("CHECKOUT", "CHK-123", version_token="17")
        money = ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "USD")
        baseline = action_fingerprint(
            capability="place_order",
            capability_version="v1",
            action_name="PLACE_ORDER",
            normalized_arguments={"checkoutId": "CHK-123", "quantity": 1},
            targets=(target,),
            financial_facts=(money,),
        )

        variants = (
            {"capability": "cancel_order"},
            {"arguments": {"checkoutId": "CHK-999", "quantity": 1}},
            {"targets": (ConfirmationTarget("CHECKOUT", "CHK-123", version_token="18"),)},
            {"financial_facts": (
                ConfirmationFinancialFact("TOTAL", Decimal("69.99"), "USD"),
            )},
            {"financial_facts": (
                ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "EUR"),
            )},
        )
        for change in variants:
            with self.subTest(change=change):
                self.assertNotEqual(
                    baseline,
                    action_fingerprint(
                        capability=change.get("capability", "place_order"),
                        capability_version="v1",
                        action_name="PLACE_ORDER",
                        normalized_arguments=change.get(
                            "arguments", {"checkoutId": "CHK-123", "quantity": 1}
                        ),
                        targets=change.get("targets", (target,)),
                        financial_facts=change.get("financial_facts", (money,)),
                    ),
                )

    def test_consequential_confirmation_requires_backend_expiry(self) -> None:
        with self.assertRaises(ValueError):
            self._prepare(expires_at=None)

        expiry = expiry_from_ttl(now=NOW, ttl_seconds=900)
        prepared = self._prepare(expires_at=expiry)
        self.assertEqual(NOW + timedelta(minutes=15), prepared.expires_at)

    def test_execution_comparison_rejects_every_mutable_binding(self) -> None:
        record = self._record()
        valid = self._execution()
        self.assertIsNone(_execution_mismatch(record, valid))

        cases = (
            (valid.__class__(**{**valid.__dict__, "capability": "cancel_order"}), "ACTION_MISMATCH"),
            (valid.__class__(**{**valid.__dict__, "normalized_arguments": {"checkoutId": "CHK-999"}}), "ARGUMENT_MISMATCH"),
            (valid.__class__(**{**valid.__dict__, "targets": (ConfirmationTarget("CHECKOUT", "CHK-999", "17"),)}), "TARGET_MISMATCH"),
            (valid.__class__(**{**valid.__dict__, "financial_facts": (ConfirmationFinancialFact("TOTAL", Decimal("69.99"), "USD"),)}), "FINANCIAL_FACT_MISMATCH"),
            (valid.__class__(**{**valid.__dict__, "current_resource_versions": {"CHECKOUT:CHK-123": "18"}}), "RESOURCE_VERSION_MISMATCH"),
            (valid.__class__(**{**valid.__dict__, "current_resource_versions": None}), "RESOURCE_VERSION_MISMATCH"),
        )
        for execution, code in cases:
            with self.subTest(code=code):
                self.assertEqual(code, _execution_mismatch(record, execution).value)

    def test_action_key_and_contract_are_not_model_selected(self) -> None:
        record = self._record()
        self.assertEqual(f"agent-action-{CONFIRMATION}", record.action_key)
        self.assertNotIn("actor", record.normalized_arguments)
        self.assertNotIn("actionKey", record.normalized_arguments)

    def test_snapshot_binding_requires_current_owner_snapshot(self) -> None:
        snapshot = "a" * 64
        target = ConfirmationTarget(
            "CHECKOUT", "CHK-123", snapshot_fingerprint=snapshot
        )
        record = self._record().__class__(
            **{**self._record().__dict__, "targets": (target,)}
        )
        execution = self._execution().__class__(
            **{
                **self._execution().__dict__,
                "targets": (target,),
                "current_resource_versions": None,
                "current_resource_snapshots": {
                    "CHECKOUT:CHK-123": snapshot,
                },
            }
        )
        self.assertIsNone(_execution_mismatch(record, execution))
        missing = execution.__class__(
            **{**execution.__dict__, "current_resource_snapshots": None}
        )
        self.assertEqual(
            "RESOURCE_VERSION_MISMATCH", _execution_mismatch(record, missing).value
        )

    def test_ai_conf_eval_fixture_covers_required_terminal_scenarios(self) -> None:
        fixture = json.loads(
            (
                Path(__file__).parents[1]
                / "evals"
                / "ai_conf_01_consequential_confirmation_v1.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual([], fixture["productionConsequentialTools"])
        cases = {case["id"]: case for case in fixture["cases"]}
        self.assertEqual(
            {
                "normal-exact-confirmation",
                "explicit-cancellation",
                "changed-request",
                "backend-expiry",
                "resource-version-change",
                "duplicate-confirmation",
                "cross-user-confirmation",
                "unsafe-interruption",
                "capability-disabled",
            },
            set(cases),
        )
        self.assertTrue(
            all(case["expectedProductionTools"] == [] for case in cases.values())
        )

    def _prepare(self, *, expires_at: datetime | None) -> PrepareConfirmation:
        return PrepareConfirmation(
            actor_user_id=ACTOR,
            session_id=SESSION,
            originating_invocation_id=ORIGIN,
            capability="place_order",
            capability_version="v1",
            action_name="PLACE_ORDER",
            normalized_arguments={"checkoutId": "CHK-123"},
            targets=(ConfirmationTarget("CHECKOUT", "CHK-123", "17"),),
            financial_facts=(
                ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "USD"),
            ),
            human_summary="Place the prepared order for $49.99 USD.",
            risk_level=3,
            expires_at=expires_at,
            correlation_id="ai-conf-contract-test",
        )

    def _record(self) -> ConfirmationRecord:
        target = ConfirmationTarget("CHECKOUT", "CHK-123", "17")
        money = ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "USD")
        arguments = {"checkoutId": "CHK-123"}
        return ConfirmationRecord(
            confirmation_id=CONFIRMATION,
            actor_user_id=ACTOR,
            session_id=SESSION,
            originating_invocation_id=ORIGIN,
            workflow_id=None,
            capability="place_order",
            capability_version="v1",
            action_name="PLACE_ORDER",
            normalized_arguments=arguments,
            targets=(target,),
            financial_facts=(money,),
            action_fingerprint=action_fingerprint(
                capability="place_order",
                capability_version="v1",
                action_name="PLACE_ORDER",
                normalized_arguments=arguments,
                targets=(target,),
                financial_facts=(money,),
            ),
            human_summary="Place the prepared order for $49.99 USD.",
            risk_level=3,
            state=ConfirmationState.CONFIRMED,
            created_at=NOW,
            expires_at=NOW + timedelta(minutes=15),
            action_key=f"agent-action-{CONFIRMATION}",
            confirmed_at=NOW,
            confirmed_by_invocation_id=CONSUMER,
        )

    def _execution(self) -> ConfirmationExecution:
        return ConfirmationExecution(
            confirmation_id=CONFIRMATION,
            actor_user_id=ACTOR,
            session_id=SESSION,
            consuming_invocation_id=CONSUMER,
            capability="place_order",
            capability_version="v1",
            action_name="PLACE_ORDER",
            normalized_arguments={"checkoutId": "CHK-123"},
            targets=(ConfirmationTarget("CHECKOUT", "CHK-123", "17"),),
            financial_facts=(
                ConfirmationFinancialFact("TOTAL", Decimal("49.99"), "USD"),
            ),
            current_resource_versions={"CHECKOUT:CHK-123": "17"},
            correlation_id="ai-conf-execution-test",
        )


if __name__ == "__main__":
    unittest.main()
