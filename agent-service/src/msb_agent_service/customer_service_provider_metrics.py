from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


class CustomerServiceProviderMetrics:
    """Records bounded adapter results and usage without content or identity."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self.requests = Counter(
            "agent_customer_service_provider_requests_total",
            "Customer-service provider adapter requests by bounded result.",
            ("result",),
            registry=self.registry,
        )
        self.duration = Histogram(
            "agent_customer_service_provider_duration_seconds",
            "Customer-service provider adapter latency.",
            registry=self.registry,
        )
        self.redactions = Counter(
            "agent_customer_service_provider_redactions_total",
            "Pre-provider privacy redactions by bounded field class.",
            ("field",),
            registry=self.registry,
        )
        self.tokens = Histogram(
            "agent_customer_service_provider_tokens",
            "Customer-service provider token usage by direction.",
            ("direction",),
            buckets=(0, 50, 100, 250, 500, 1_000, 2_000, 5_000, 10_000),
            registry=self.registry,
        )

