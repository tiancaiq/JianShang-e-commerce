from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


CREATE_SESSION_VALIDATION_ROUTE = "create_session"
VALIDATION_ERROR_RESULT = "VALIDATION_ERROR"
VALIDATION_FIELD_CATEGORIES = (
    "session_type",
    "subject",
    "subject_type",
    "subject_id",
    "top_level_extra",
    "subject_extra",
    "body_shape",
    "unknown",
)
VALIDATION_ERROR_CATEGORIES = (
    "missing",
    "literal_mismatch",
    "pattern_mismatch",
    "type_mismatch",
    "extra_forbidden",
    "invalid_json",
    "invalid_body",
    "other",
)


class CustomerServiceApiMetrics:
    """Records bounded API outcomes without actor, listing, or message labels."""

    _VALIDATION_ROUTES = frozenset({CREATE_SESSION_VALIDATION_ROUTE})
    _VALIDATION_FIELDS = frozenset(VALIDATION_FIELD_CATEGORIES)
    _VALIDATION_ERRORS = frozenset(VALIDATION_ERROR_CATEGORIES)
    _VALIDATION_RESULTS = frozenset({VALIDATION_ERROR_RESULT})

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
        self.validation_errors = Counter(
            "agent_customer_service_api_validation_errors_total",
            "Customer-service request validation failures by fixed category.",
            ("route", "field_category", "error_category", "result"),
            registry=registry,
        )

    def record(self, route: str, result: str, duration_seconds: float) -> None:
        self.requests.labels(route=route, result=result).inc()
        self.duration.labels(route=route).observe(max(0.0, duration_seconds))

    def record_validation(
        self,
        route: str,
        field_category: str,
        error_category: str,
        result: str,
    ) -> None:
        """Record only fixed validation categories, never submitted values."""

        if (
            route not in self._VALIDATION_ROUTES
            or field_category not in self._VALIDATION_FIELDS
            or error_category not in self._VALIDATION_ERRORS
            or result not in self._VALIDATION_RESULTS
        ):
            raise ValueError("Validation metric labels must use fixed categories")
        self.validation_errors.labels(
            route=route,
            field_category=field_category,
            error_category=error_category,
            result=result,
        ).inc()
