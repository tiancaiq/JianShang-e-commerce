CREATE TABLE payment_intents (
    id CHAR(26) NOT NULL,
    checkout_id CHAR(26) NOT NULL,
    checkout_version BIGINT UNSIGNED NOT NULL,
    checkout_snapshot_hash CHAR(64) NOT NULL,
    buyer_id CHAR(26) NOT NULL,
    caller_scope VARCHAR(64) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    payment_method_type VARCHAR(32) NOT NULL,
    capture_method VARCHAR(32) NOT NULL,
    merchant_of_record VARCHAR(32) NOT NULL,
    funds_flow VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128) NULL,
    provider_action_type VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    safe_error_code VARCHAR(64) NULL,
    safe_error_message VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_intents_checkout (checkout_id),
    UNIQUE KEY uk_payment_intents_provider_reference (provider, provider_reference),
    KEY idx_payment_intents_buyer_created (buyer_id, created_at, id),
    KEY idx_payment_intents_status_expiry (status, expires_at, id),
    CONSTRAINT chk_payment_intents_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_intents_currency CHECK (currency = 'USD'),
    CONSTRAINT chk_payment_method_type CHECK (payment_method_type = 'CARD'),
    CONSTRAINT chk_payment_capture_method CHECK (capture_method = 'AUTOMATIC'),
    CONSTRAINT chk_payment_merchant_of_record CHECK (merchant_of_record = 'PLATFORM'),
    CONSTRAINT chk_payment_funds_flow CHECK (funds_flow = 'SEPARATE_CHARGE_TRANSFER'),
    CONSTRAINT chk_payment_intents_status CHECK (
        status IN ('CREATED', 'REQUIRES_ACTION', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_intent_business_scopes (
    payment_intent_id CHAR(26) NOT NULL,
    business_id CHAR(26) NOT NULL,
    scope_order SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (payment_intent_id, business_id),
    UNIQUE KEY uk_payment_intent_business_order (payment_intent_id, scope_order),
    KEY idx_payment_intent_business_scope (business_id, payment_intent_id),
    CONSTRAINT fk_payment_intent_business_payment
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_attempts (
    id CHAR(26) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    provider VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(128) NULL,
    safe_error_code VARCHAR(64) NULL,
    safe_error_message VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_attempt_number (payment_intent_id, attempt_number),
    CONSTRAINT fk_payment_attempt_payment
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_status_history (
    id CHAR(26) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_scope VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payment_status_history (payment_intent_id, created_at, id),
    CONSTRAINT fk_payment_status_history_payment
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment_idempotency_records (
    id CHAR(26) NOT NULL,
    caller_scope VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    http_status SMALLINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_idempotency_scope_key (caller_scope, idempotency_key),
    KEY idx_payment_idempotency_expiry (expires_at),
    CONSTRAINT chk_payment_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT fk_payment_idempotency_payment
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
