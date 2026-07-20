from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


class CustomerServiceOrchestrationMetrics:
    """Records low-cardinality orchestration outcomes without content or identity."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.requests = Counter(
            "agent_customer_service_orchestration_requests_total",
            "Listing customer-service orchestration requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.duration = Histogram(
            "agent_customer_service_orchestration_duration_seconds",
            "Listing customer-service orchestration latency.",
            registry=self.registry,
        )
        self.tool_calls = Counter(
            "agent_customer_service_tool_calls_total",
            "Allowlisted customer-service tool calls by name and bounded result.",
            ("tool", "result"),
            registry=self.registry,
        )
        self.tool_duration = Histogram(
            "agent_customer_service_tool_duration_seconds",
            "Allowlisted customer-service tool latency by name.",
            ("tool",),
            registry=self.registry,
        )
        self.model_requests = Counter(
            "agent_customer_service_model_requests_total",
            "Customer-service model requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.guardrails = Counter(
            "agent_customer_service_guardrail_total",
            "Deterministic guardrail outcomes by bounded rule.",
            ("rule",),
            registry=self.registry,
        )

