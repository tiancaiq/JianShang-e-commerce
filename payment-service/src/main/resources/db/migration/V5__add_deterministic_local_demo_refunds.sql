ALTER TABLE payment_outbox_events
    DROP CHECK chk_payment_outbox_event_type,
    ADD CONSTRAINT chk_payment_outbox_event_type CHECK (
        event_type IN ('payment.succeeded', 'payment.failed', 'payment.refunded')
    );

CREATE TABLE payment_refunds (
    id CHAR(26) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    cancellation_request_id CHAR(26) NOT NULL,
    order_id CHAR(26) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_refund_intent (payment_intent_id),
    UNIQUE KEY uk_payment_refund_request (cancellation_request_id),
    UNIQUE KEY uk_payment_refund_order (order_id),
    UNIQUE KEY uk_payment_refund_key (idempotency_key),
    UNIQUE KEY uk_payment_refund_provider_reference (provider, provider_reference),
    CONSTRAINT fk_payment_refund_intent FOREIGN KEY (payment_intent_id)
        REFERENCES payment_intents(id),
    CONSTRAINT chk_payment_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_refund_currency CHECK (currency = 'USD'),
    CONSTRAINT chk_payment_refund_status CHECK (status = 'SUCCEEDED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_refund_status_history (
    id CHAR(26) NOT NULL,
    refund_id CHAR(26) NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_refund_history_status (refund_id, to_status),
    CONSTRAINT fk_payment_refund_history_refund FOREIGN KEY (refund_id)
        REFERENCES payment_refunds(id),
    CONSTRAINT chk_payment_refund_history_status CHECK (
        from_status IS NULL AND to_status = 'SUCCEEDED'
            AND reason_code = 'LOCAL_DEMO_FULL_REFUND'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
