CREATE TABLE payment_return_refunds (
    id CHAR(26) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    return_id CHAR(26) NOT NULL,
    order_id CHAR(26) NOT NULL,
    business_order_id CHAR(26) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_return_refund_return (return_id),
    UNIQUE KEY uk_payment_return_refund_group (business_order_id),
    UNIQUE KEY uk_payment_return_refund_key (idempotency_key),
    UNIQUE KEY uk_payment_return_refund_provider (provider,provider_reference),
    CONSTRAINT fk_payment_return_refund_intent FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id),
    CONSTRAINT chk_payment_return_refund_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_return_refund_currency CHECK (currency='USD'),
    CONSTRAINT chk_payment_return_refund_status CHECK (status='SUCCEEDED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_return_refund_attempts (
    id CHAR(26) NOT NULL,
    refund_id CHAR(26) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_payment_return_refund_attempt (refund_id,attempt_number),
    CONSTRAINT fk_payment_return_refund_attempt FOREIGN KEY(refund_id) REFERENCES payment_return_refunds(id),
    CONSTRAINT chk_payment_return_refund_attempt_outcome CHECK(outcome='SUCCEEDED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
