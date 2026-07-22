ALTER TABLE business_orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER platform_fee_projection,
    ADD CONSTRAINT chk_business_order_version CHECK (version >= 0),
    DROP CHECK chk_business_order_fulfillment,
    ADD CONSTRAINT chk_business_order_fulfillment CHECK (
        fulfillment_status IN ('PENDING_ACCEPTANCE', 'ACCEPTED')
    );

CREATE TABLE business_order_acceptance_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NULL,
    result_updated_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_acceptance_idempotency (
        actor_user_id, business_id, operation, idempotency_key
    ),
    KEY idx_business_order_acceptance_expiry (expires_at, id),
    CONSTRAINT chk_business_order_acceptance_operation CHECK (
        operation = 'ACCEPT_BUSINESS_ORDER'
    ),
    CONSTRAINT chk_business_order_acceptance_state CHECK (
        state IN ('IN_PROGRESS', 'COMPLETED')
    ),
    CONSTRAINT chk_business_order_acceptance_result CHECK (
        (state = 'IN_PROGRESS' AND result_version IS NULL AND result_updated_at IS NULL)
        OR
        (state = 'COMPLETED' AND result_version IS NOT NULL
            AND result_version >= 0 AND result_updated_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_order_status_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_version BIGINT NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_history_version (
        business_order_id, business_order_version
    ),
    KEY idx_business_order_history (
        business_order_id, created_at, id
    ),
    CONSTRAINT fk_business_order_history_group FOREIGN KEY (business_order_id)
        REFERENCES business_orders(id),
    CONSTRAINT chk_business_order_history_version CHECK (
        business_order_version > 0
    ),
    CONSTRAINT chk_business_order_history_transition CHECK (
        from_status = 'PENDING_ACCEPTANCE'
        AND to_status = 'ACCEPTED'
        AND reason_code = 'BUSINESS_ACCEPTED'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
