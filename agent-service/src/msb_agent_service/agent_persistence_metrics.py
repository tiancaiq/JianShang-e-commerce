from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


class AgentPersistenceMetrics:
    """Owns low-cardinality persistence, retry, and retention metrics."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        metric_registry = registry or CollectorRegistry()
        self.session_operations = Counter(
            "agent_persistence_session_operations_total",
            "Agent session persistence operations by bounded result.",
            ("operation", "result"),
            registry=metric_registry,
        )
        self.invocation_operations = Counter(
            "agent_persistence_invocation_operations_total",
            "Agent invocation persistence operations by bounded result.",
            ("operation", "result"),
            registry=metric_registry,
        )
        self.tool_call_operations = Counter(
            "agent_persistence_tool_call_operations_total",
            "Agent tool-call audit operations by bounded result.",
            ("operation", "result"),
            registry=metric_registry,
        )
        self.retention_rows = Counter(
            "agent_persistence_retention_rows_total",
            "Agent persistence rows affected by bounded retention category.",
            ("category",),
            registry=metric_registry,
        )
        self.operation_duration = Histogram(
            "agent_persistence_operation_duration_seconds",
            "Agent persistence operation latency by bounded operation.",
            ("operation",),
            registry=metric_registry,
        )

    def record_session(self, operation: str, result: str) -> None:
        self.session_operations.labels(operation=operation, result=result).inc()

    def record_invocation(self, operation: str, result: str) -> None:
        self.invocation_operations.labels(operation=operation, result=result).inc()

    def record_tool_call(self, operation: str, result: str) -> None:
        self.tool_call_operations.labels(operation=operation, result=result).inc()

    def record_retention(self, category: str, count: int) -> None:
        if count > 0:
            self.retention_rows.labels(category=category).inc(count)

    def observe_duration(self, operation: str, duration_seconds: float) -> None:
        self.operation_duration.labels(operation=operation).observe(
            max(0.0, duration_seconds)
        )
