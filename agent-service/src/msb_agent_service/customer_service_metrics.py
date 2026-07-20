from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


class CustomerServiceApiMetrics:
    """Records bounded API outcomes without actor, listing, or message labels."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self.requests = Counter(
            "agent_customer_service_api_requests_total",
            "Customer-service API requests by bounded route and result.",
            ("route", "result"),
            registry=registry,
        )
        self.duration = Histogram(
            "agent_customer_service_api_duration_seconds",
            "Customer-service API latency by bounded route.",
            ("route",),
            registry=registry,
        )

    def record(self, route: str, result: str, duration_seconds: float) -> None:
        self.requests.labels(route=route, result=result).inc()
        self.duration.labels(route=route).observe(max(0.0, duration_seconds))
