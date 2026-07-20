CREATE TABLE payment_provider_events (
    id CHAR(26) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128) NOT NULL,
    payment_intent_id CHAR(26) NULL,
    payload_hash CHAR(64) NOT NULL,
    signature_timestamp DATETIME(6) NOT NULL,
    provider_occurred_at DATETIME(6) NOT NULL,
    processing_outcome VARCHAR(64) NOT NULL,
    resulting_status VARCHAR(32) NULL,
    safe_error_code VARCHAR(64) NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_provider_event (provider, provider_event_id),
    KEY idx_payment_provider_event_intent (payment_intent_id, created_at, id),
    KEY idx_payment_provider_event_outcome (processing_outcome, created_at, id),
    CONSTRAINT fk_payment_provider_event_intent
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id),
    CONSTRAINT chk_payment_provider_event_type CHECK (
        event_type IN ('payment_intent.succeeded', 'payment_intent.failed')
    ),
    CONSTRAINT chk_payment_provider_event_outcome CHECK (
        processing_outcome IN (
            'APPLIED',
            'UNKNOWN_INTENT',
            'IGNORED_LATE_EVENT',
            'REJECTED_ILLEGAL_TRANSITION'
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_outbox_events (
    id CHAR(26) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(26) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_version INT UNSIGNED NOT NULL,
    producer VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_outbox_causation_type (causation_id, event_type),
    KEY idx_payment_outbox_publication (published_at, created_at, id),
    CONSTRAINT chk_payment_outbox_event_type CHECK (
        event_type IN ('payment.succeeded', 'payment.failed')
    ),
    CONSTRAINT chk_payment_outbox_event_version CHECK (event_version = 1),
    CONSTRAINT fk_payment_outbox_intent
        FOREIGN KEY (aggregate_id) REFERENCES payment_intents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
