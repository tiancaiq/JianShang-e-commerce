CREATE TABLE business_order_returns (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    buyer_comment VARCHAR(500) NULL,
    policy_version VARCHAR(64) NOT NULL,
    policy_window_days SMALLINT UNSIGNED NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    window_expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    refund_status VARCHAR(24) NOT NULL,
    inventory_disposition VARCHAR(24) NULL,
    received_at TIMESTAMP(6) NULL,
    refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_amount DECIMAL(19,4) NULL,
    currency CHAR(3) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    processing_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    processing_next_attempt_at TIMESTAMP(6) NULL,
    processing_last_error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_return_group (business_order_id),
    KEY idx_business_order_return_buyer (buyer_id, order_id),
    KEY idx_business_order_return_business (business_id, created_at, id),
    KEY idx_business_order_return_processing (status, refund_status, processing_next_attempt_at, id),
    CONSTRAINT fk_business_order_return_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_business_order_return_group FOREIGN KEY (business_order_id) REFERENCES business_orders(id),
    CONSTRAINT chk_business_order_return_reason CHECK (reason_code IN (
        'NO_LONGER_NEEDED','NOT_AS_EXPECTED','DAMAGED','WRONG_ITEM','OTHER')),
    CONSTRAINT chk_business_order_return_status CHECK (status IN (
        'RETURN_REQUESTED','RETURN_AUTHORIZED','RETURN_IN_TRANSIT','RETURN_RECEIVED','RETURN_COMPLETED')),
    CONSTRAINT chk_business_order_return_refund CHECK (refund_status IN (
        'NONE','PENDING','PROCESSING','SUCCEEDED')),
    CONSTRAINT chk_business_order_return_disposition CHECK (
        inventory_disposition IS NULL OR inventory_disposition IN ('RESTOCK_SELLABLE','DO_NOT_RESTOCK')),
    CONSTRAINT chk_business_order_return_window CHECK (policy_window_days = 30),
    CONSTRAINT chk_business_order_return_amount CHECK (refund_amount IS NULL OR refund_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE business_order_return_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    return_version BIGINT UNSIGNED NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_return_history_version (return_id, return_version),
    CONSTRAINT fk_business_order_return_history_return FOREIGN KEY (return_id)
        REFERENCES business_order_returns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE business_order_return_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT UNSIGNED NULL,
    state VARCHAR(16) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_return_command_key
        (actor_user_id, business_id, operation, idempotency_key),
    KEY idx_business_order_return_command_expiry (expires_at, id),
    CONSTRAINT chk_business_order_return_command_state CHECK (state IN ('IN_PROGRESS','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE business_order_return_shipments (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    carrier_display_name VARCHAR(80) NOT NULL,
    tracking_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    in_transit_at TIMESTAMP(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_return_shipment_return (return_id),
    UNIQUE KEY uk_business_order_return_shipment_tracking (tracking_reference),
    CONSTRAINT fk_business_order_return_shipment_return FOREIGN KEY (return_id)
        REFERENCES business_order_returns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE order_outbox_events
SET notification_next_attempt_at = created_at
WHERE event_type IN ('return.requested','return.authorized','return.received','return.refund_completed');
