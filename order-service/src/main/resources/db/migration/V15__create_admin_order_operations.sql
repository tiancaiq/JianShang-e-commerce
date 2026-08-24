CREATE INDEX idx_orders_admin_created ON orders (created_at, id);
CREATE INDEX idx_orders_admin_status_created ON orders (status, created_at, id);
CREATE INDEX idx_orders_admin_payment_created ON orders (payment_status, created_at, id);
CREATE INDEX idx_business_orders_admin_business_order ON business_orders (business_id, order_id);
CREATE INDEX idx_order_items_admin_listing_order ON order_items (listing_id, order_id);

ALTER TABLE order_cancellation_requests
    ADD COLUMN request_actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'BUYER' AFTER buyer_id,
    ADD COLUMN request_actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER request_actor_type,
    ADD COLUMN admin_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER request_actor_id,
    ADD COLUMN admin_reason VARCHAR(1000) NULL AFTER admin_reason_code,
    ADD CONSTRAINT chk_order_cancellation_request_actor CHECK (
        (request_actor_type = 'BUYER' AND admin_reason_code IS NULL AND admin_reason IS NULL)
        OR
        (request_actor_type = 'PLATFORM_ADMIN' AND request_actor_id IS NOT NULL
            AND admin_reason_code IS NOT NULL)
    );

UPDATE order_cancellation_requests
SET request_actor_id = buyer_id
WHERE request_actor_type = 'BUYER' AND request_actor_id IS NULL;

ALTER TABLE business_order_cancellation_history
    DROP CHECK chk_business_order_cancellation_history_transition,
    ADD CONSTRAINT chk_business_order_cancellation_history_transition CHECK (
        (from_status = 'NONE' AND to_status = 'CANCELLATION_PENDING'
            AND reason_code IN ('BUYER_CANCELLATION_REQUESTED', 'ADMIN_CANCELLATION_REQUESTED'))
        OR (from_status = 'CANCELLATION_PENDING' AND to_status = 'CANCELLED'
            AND reason_code = 'AUTO_APPROVED_BEFORE_FULFILLMENT')
    );

CREATE TABLE admin_order_cancellation_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_order_version BIGINT NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_order_cancel_retry (actor_admin_id, idempotency_key),
    KEY idx_admin_order_cancel_expiry (expires_at, id),
    CONSTRAINT fk_admin_order_cancel_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_admin_order_cancel_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_admin_order_cancel_result CHECK (
        (state = 'IN_PROGRESS' AND cancellation_request_id IS NULL AND response_json IS NULL)
        OR
        (state = 'COMPLETED' AND cancellation_request_id IS NOT NULL AND response_json IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_admin_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    actor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NULL,
    previous_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_order_admin_event_request (request_id),
    KEY idx_order_admin_events_time (order_id, occurred_at, event_id),
    CONSTRAINT fk_order_admin_event_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_order_admin_event_type CHECK (event_type = 'ADMIN_ORDER_CANCELLATION_REQUESTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
