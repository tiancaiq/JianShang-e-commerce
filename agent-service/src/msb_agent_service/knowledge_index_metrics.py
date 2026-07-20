from __future__ import annotations

import time
from datetime import datetime

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram


class KnowledgeIndexMetrics:
    """Owns low-cardinality metrics for the knowledge index foundation."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        self._latest_indexed_timestamp: float | None = None
        self.operations = Counter(
            "agent_knowledge_index_operation_total",
            "Knowledge index operations by result.",
            ("operation", "status"),
            registry=self.registry,
        )
        self.operation_duration = Histogram(
            "agent_knowledge_index_operation_duration_seconds",
            "Knowledge index operation latency.",
            ("operation",),
            registry=self.registry,
        )
        self.ready = Gauge(
            "agent_knowledge_index_ready",
            "Whether the enabled knowledge index is ready.",
            registry=self.registry,
        )
        self.alias_valid = Gauge(
            "agent_knowledge_index_alias_valid",
            "Whether read and write aliases satisfy their invariants.",
            registry=self.registry,
        )
        self.documents = Gauge(
            "agent_knowledge_index_documents",
            "Documents visible through the read alias.",
            registry=self.registry,
        )
        self.last_success = Gauge(
            "agent_knowledge_index_last_success_timestamp_seconds",
            "Unix timestamp of the latest successful index operation.",
            registry=self.registry,
        )
        self.freshness = Gauge(
            "agent_knowledge_index_freshness_seconds",
            "Seconds since the newest indexedAt value when documents exist.",
            registry=self.registry,
        )
        self.freshness.set_function(self._freshness_seconds)

    def observe_indexed_at(self, indexed_at: datetime | float) -> None:
        """Record the newest indexed document timestamp without source labels."""

        timestamp = (
            indexed_at.timestamp()
            if isinstance(indexed_at, datetime)
            else float(indexed_at)
        )
        if (
            self._latest_indexed_timestamp is None
            or timestamp > self._latest_indexed_timestamp
        ):
            self._latest_indexed_timestamp = timestamp

    def _freshness_seconds(self) -> float:
        if self._latest_indexed_timestamp is None:
            return 0.0
        return max(0.0, time.time() - self._latest_indexed_timestamp)
