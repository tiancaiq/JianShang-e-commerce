INSERT INTO platform_policy_versions (
    id, version_code, source, shipping_text, cancellation_text, return_text,
    effective_from, effective_to, created_at, paid_order_cancellation_mode
) VALUES (
    '01999999999999999999999992',
    'LOCAL_DEMO_CANCELLATION_V1',
    'PLATFORM_DEFAULT',
    'Local demo checkout does not include a carrier or delivery-date promise.',
    'A paid order may be cancelled only before any fulfillment group is accepted.',
    'Automated returns are unavailable in this local demo and require a later approved workflow.',
    '2026-08-01 00:00:00.000000',
    NULL,
    '2026-08-01 00:00:00.000000',
    'BEFORE_FULFILLMENT'
);

ALTER TABLE orders
    DROP CHECK chk_order_status,
    ADD CONSTRAINT chk_order_status CHECK (
        status IN ('CONFIRMED', 'CANCELLATION_REQUESTED', 'CANCELLED')
    );

ALTER TABLE business_orders
    DROP CHECK chk_business_order_cancellation,
    ADD CONSTRAINT chk_business_order_cancellation CHECK (
        cancellation_status IN ('NONE', 'CANCELLATION_PENDING', 'CANCELLED')
    );

ALTER TABLE order_cancellation_requests
    ADD COLUMN decision_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN decided_at TIMESTAMP(6) NULL,
    ADD COLUMN completed_at TIMESTAMP(6) NULL,
    DROP CHECK chk_order_cancellation_request_status,
    ADD CONSTRAINT chk_order_cancellation_request_status CHECK (
        status IN ('PENDING', 'AUTO_APPROVED', 'COMPLETED')
    ),
    ADD CONSTRAINT chk_order_cancellation_request_decision CHECK (
        (status = 'PENDING' AND decision_type IS NULL AND decided_at IS NULL AND completed_at IS NULL)
        OR (status = 'AUTO_APPROVED' AND decision_type = 'AUTO_BEFORE_FULFILLMENT'
            AND decided_at IS NOT NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND decision_type = 'AUTO_BEFORE_FULFILLMENT'
            AND decided_at IS NOT NULL AND completed_at IS NOT NULL
            AND completed_at >= decided_at)
    );

ALTER TABLE business_order_cancellation_history
    DROP INDEX uk_business_order_cancellation_history_request,
    ADD UNIQUE KEY uk_business_order_cancellation_history_request_status (
        business_order_id, cancellation_request_id, to_status
    ),
    DROP CHECK chk_business_order_cancellation_history_transition,
    DROP CHECK chk_business_order_cancellation_history_reason,
    ADD CONSTRAINT chk_business_order_cancellation_history_transition CHECK (
        (from_status = 'NONE' AND to_status = 'CANCELLATION_PENDING'
            AND reason_code = 'BUYER_CANCELLATION_REQUESTED')
        OR (from_status = 'CANCELLATION_PENDING' AND to_status = 'CANCELLED'
            AND reason_code = 'AUTO_APPROVED_BEFORE_FULFILLMENT')
    );

CREATE TABLE order_cancellation_compensations (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_intent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    inventory_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    refund_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_cancellation_compensation_request (cancellation_request_id),
    UNIQUE KEY uk_order_cancellation_compensation_order (order_id),
    KEY idx_order_cancellation_compensation_work (
        inventory_status, refund_status, next_attempt_at, id
    ),
    CONSTRAINT fk_order_cancellation_compensation_request FOREIGN KEY (cancellation_request_id)
        REFERENCES order_cancellation_requests(id),
    CONSTRAINT fk_order_cancellation_compensation_order FOREIGN KEY (order_id)
        REFERENCES orders(id),
    CONSTRAINT chk_order_cancellation_compensation_amount CHECK (amount > 0),
    CONSTRAINT chk_order_cancellation_compensation_currency CHECK (currency = 'USD'),
    CONSTRAINT chk_order_cancellation_compensation_inventory CHECK (
        inventory_status IN ('PENDING', 'SUCCEEDED')
    ),
    CONSTRAINT chk_order_cancellation_compensation_refund CHECK (
        refund_status IN ('PENDING', 'SUCCEEDED')
    ),
    CONSTRAINT chk_order_cancellation_compensation_completion CHECK (
        (completed_at IS NULL)
        OR (inventory_status = 'SUCCEEDED' AND refund_status = 'SUCCEEDED'
            AND refund_id IS NOT NULL AND refund_reference IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
