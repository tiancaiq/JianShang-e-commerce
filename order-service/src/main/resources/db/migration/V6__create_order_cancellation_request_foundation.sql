ALTER TABLE platform_policy_versions
    ADD COLUMN paid_order_cancellation_mode VARCHAR(32) NOT NULL DEFAULT 'NOT_ALLOWED',
    ADD CONSTRAINT chk_platform_policy_cancellation_mode CHECK (
        paid_order_cancellation_mode IN ('NOT_ALLOWED', 'BEFORE_FULFILLMENT')
    );

ALTER TABLE checkout_policy_snapshots
    ADD COLUMN paid_order_cancellation_mode VARCHAR(32) NOT NULL DEFAULT 'NOT_ALLOWED',
    ADD CONSTRAINT chk_checkout_policy_cancellation_mode CHECK (
        paid_order_cancellation_mode IN ('NOT_ALLOWED', 'BEFORE_FULFILLMENT')
    );

INSERT INTO platform_policy_versions (
    id, version_code, source, shipping_text, cancellation_text, return_text,
    effective_from, effective_to, created_at, paid_order_cancellation_mode
) VALUES (
    '01999999999999999999999991',
    'LOCAL_DEMO_CANCELLATION_TEST_V1',
    'PLATFORM_DEFAULT',
    'Local demo checkout does not include a carrier or delivery-date promise.',
    'A paid order may request cancellation before fulfillment begins.',
    'Automated returns are unavailable in this local demo and require a later approved workflow.',
    '2037-01-01 00:00:00.000000',
    NULL,
    '2026-07-20 00:00:00.000000',
    'BEFORE_FULFILLMENT'
);

ALTER TABLE orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    DROP CHECK chk_order_status,
    ADD CONSTRAINT chk_order_version CHECK (version >= 0),
    ADD CONSTRAINT chk_order_status CHECK (
        status IN ('CONFIRMED', 'CANCELLATION_REQUESTED')
    );

ALTER TABLE business_orders
    ADD COLUMN cancellation_cutoff_at TIMESTAMP(6) NULL,
    DROP CHECK chk_business_order_cancellation,
    ADD CONSTRAINT chk_business_order_cancellation CHECK (
        cancellation_status IN ('NONE', 'CANCELLATION_PENDING')
    );

CREATE TABLE order_cancellation_requests (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_order_version BIGINT NOT NULL,
    resulting_order_version BIGINT NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_cancellation_request_order (order_id),
    KEY idx_order_cancellation_request_buyer (buyer_id, requested_at, id),
    CONSTRAINT fk_order_cancellation_request_order FOREIGN KEY (order_id)
        REFERENCES orders(id),
    CONSTRAINT chk_order_cancellation_request_status CHECK (status = 'PENDING'),
    CONSTRAINT chk_order_cancellation_request_versions CHECK (
        expected_order_version >= 0
        AND resulting_order_version = expected_order_version + 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_cancellation_request_groups (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_policy_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_mode VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_fulfillment_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_cancellation_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_cancellation_cutoff_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_cancellation_request_group (
        cancellation_request_id, business_order_id
    ),
    KEY idx_order_cancellation_group_order (business_order_id, created_at, id),
    CONSTRAINT fk_order_cancellation_group_request FOREIGN KEY (cancellation_request_id)
        REFERENCES order_cancellation_requests(id),
    CONSTRAINT fk_order_cancellation_group_order FOREIGN KEY (business_order_id)
        REFERENCES business_orders(id),
    CONSTRAINT fk_order_cancellation_group_policy FOREIGN KEY (checkout_policy_snapshot_id)
        REFERENCES checkout_policy_snapshots(id),
    CONSTRAINT chk_order_cancellation_group_mode CHECK (
        policy_mode = 'BEFORE_FULFILLMENT'
    ),
    CONSTRAINT chk_order_cancellation_group_original_cancel CHECK (
        original_cancellation_status = 'NONE'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_cancellation_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_order_version BIGINT NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    http_status INT NULL,
    response_json JSON NULL,
    response_etag VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_cancellation_command_key (
        buyer_id, operation, idempotency_key
    ),
    KEY idx_order_cancellation_command_expiry (expires_at, id),
    KEY idx_order_cancellation_command_order (order_id, created_at, id),
    CONSTRAINT fk_order_cancellation_command_order FOREIGN KEY (order_id)
        REFERENCES orders(id),
    CONSTRAINT fk_order_cancellation_command_request FOREIGN KEY (cancellation_request_id)
        REFERENCES order_cancellation_requests(id),
    CONSTRAINT chk_order_cancellation_command_operation CHECK (
        operation = 'REQUEST_ORDER_CANCELLATION'
    ),
    CONSTRAINT chk_order_cancellation_command_state CHECK (
        state IN ('IN_PROGRESS', 'COMPLETED')
    ),
    CONSTRAINT chk_order_cancellation_command_version CHECK (expected_order_version >= 0),
    CONSTRAINT chk_order_cancellation_command_result CHECK (
        (state = 'IN_PROGRESS'
            AND cancellation_request_id IS NULL
            AND http_status IS NULL
            AND response_json IS NULL
            AND response_etag IS NULL)
        OR
        (state = 'COMPLETED'
            AND http_status = 200
            AND response_json IS NOT NULL
            AND response_etag IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_order_cancellation_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_cancellation_history_request (
        business_order_id, cancellation_request_id
    ),
    KEY idx_business_order_cancellation_history (
        business_order_id, created_at, id
    ),
    CONSTRAINT fk_business_order_cancellation_history_order FOREIGN KEY (business_order_id)
        REFERENCES business_orders(id),
    CONSTRAINT fk_business_order_cancellation_history_request FOREIGN KEY (cancellation_request_id)
        REFERENCES order_cancellation_requests(id),
    CONSTRAINT chk_business_order_cancellation_history_transition CHECK (
        from_status = 'NONE' AND to_status = 'CANCELLATION_PENDING'
    ),
    CONSTRAINT chk_business_order_cancellation_history_reason CHECK (
        reason_code = 'BUYER_CANCELLATION_REQUESTED'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
