CREATE TABLE payment_refund_attempts (
    id CHAR(26) NOT NULL,
    refund_id CHAR(26) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    operation VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_refund_attempt_number (refund_id, attempt_number),
    CONSTRAINT fk_payment_refund_attempt_refund FOREIGN KEY (refund_id)
        REFERENCES payment_refunds(id),
    CONSTRAINT chk_payment_refund_attempt_operation CHECK (operation = 'FULL_REFUND'),
    CONSTRAINT chk_payment_refund_attempt_outcome CHECK (outcome = 'SUCCEEDED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
