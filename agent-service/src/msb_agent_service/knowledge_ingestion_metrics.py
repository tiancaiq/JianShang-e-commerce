from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram


class KnowledgeIngestionMetrics:
    """Owns low-cardinality Kafka intake, job, and source-client metrics."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self.kafka_events = Counter(
            "agent_knowledge_kafka_events_total",
            "Listing knowledge Kafka records by durable intake result.",
            ("result",),
            registry=registry,
        )
        self.kafka_commits = Counter(
            "agent_knowledge_kafka_commits_total",
            "Manual Kafka offset commit attempts by result.",
            ("result",),
            registry=registry,
        )
        self.job_transitions = Counter(
            "agent_knowledge_job_transitions_total",
            "Durable ingestion job transitions by bounded status and result.",
            ("status", "result"),
            registry=registry,
        )
        self.jobs = Gauge(
            "agent_knowledge_jobs",
            "Current durable ingestion jobs by bounded status.",
            ("status",),
            registry=registry,
        )
        self.oldest_pending_age = Gauge(
            "agent_knowledge_oldest_pending_age_seconds",
            "Age of the oldest nonterminal ingestion job.",
            registry=registry,
        )
        self.deletion_jobs = Gauge(
            "agent_knowledge_deletion_jobs",
            "Current exact physical deletion jobs by bounded status.",
            ("status",),
            registry=registry,
        )
        self.rebuild_runs = Gauge(
            "agent_knowledge_rebuild_runs",
            "Current listing rebuild runs by bounded status.",
            ("status",),
            registry=registry,
        )
        self.source_fetches = Counter(
            "agent_knowledge_source_fetch_total",
            "Exact Product Service source fetches by result.",
            ("result",),
            registry=registry,
        )
        self.source_fetch_duration = Histogram(
            "agent_knowledge_source_fetch_duration_seconds",
            "Exact Product Service source fetch latency.",
            registry=registry,
        )
        self.embedding_requests = Counter(
            "agent_knowledge_embedding_requests_total",
            "Embedding requests by bounded result.",
            ("result",),
            registry=registry,
        )
        self.embedding_duration = Histogram(
            "agent_knowledge_embedding_duration_seconds",
            "Embedding request latency.",
            registry=registry,
        )
        self.embedding_input_tokens = Counter(
            "agent_knowledge_embedding_input_tokens_total",
            "Provider-reported embedding input tokens.",
            registry=registry,
        )
        for status in (
            "PENDING",
            "PROCESSING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "DEAD_LETTER",
        ):
            self.jobs.labels(status=status).set(0)
            self.deletion_jobs.labels(status=status).set(0)
        for status in (
            "RUNNING",
            "FAILED",
            "READY_TO_PROMOTE",
            "PROMOTED",
            "ROLLED_BACK",
        ):
            self.rebuild_runs.labels(status=status).set(0)

    def record_event(self, result: str) -> None:
        self.kafka_events.labels(result=result).inc()

    def record_commit(self, result: str) -> None:
        self.kafka_commits.labels(result=result).inc()

    def record_job_transition(self, status: str, result: str) -> None:
        self.job_transitions.labels(status=status, result=result).inc()

    def record_source_fetch(
        self,
        result: str,
        duration_seconds: float | None = None,
    ) -> None:
        self.source_fetches.labels(result=result).inc()
        if duration_seconds is not None:
            self.source_fetch_duration.observe(duration_seconds)

    def record_embedding(
        self,
        result: str,
        duration_seconds: float,
        *,
        input_tokens: int = 0,
    ) -> None:
        self.embedding_requests.labels(result=result).inc()
        self.embedding_duration.observe(duration_seconds)
        if input_tokens > 0:
            self.embedding_input_tokens.inc(input_tokens)

    def update_job_backlog(
        self,
        counts: dict[str, int],
        oldest_pending_age_seconds: float,
    ) -> None:
        for status in (
            "PENDING",
            "PROCESSING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "DEAD_LETTER",
        ):
            self.jobs.labels(status=status).set(counts.get(status, 0))
        self.oldest_pending_age.set(max(0.0, oldest_pending_age_seconds))

    def update_operation_backlog(
        self,
        deletion_counts: dict[str, int],
        rebuild_counts: dict[str, int],
    ) -> None:
        for status in (
            "PENDING",
            "PROCESSING",
            "RETRY_WAIT",
            "SUCCEEDED",
            "DEAD_LETTER",
        ):
            self.deletion_jobs.labels(status=status).set(
                deletion_counts.get(status, 0)
            )
        for status in (
            "RUNNING",
            "FAILED",
            "READY_TO_PROMOTE",
            "PROMOTED",
            "ROLLED_BACK",
        ):
            self.rebuild_runs.labels(status=status).set(
                rebuild_counts.get(status, 0)
            )
