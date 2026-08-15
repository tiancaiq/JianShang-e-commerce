ALTER TABLE business_orders
    DROP CHECK chk_business_order_fulfillment,
    ADD CONSTRAINT chk_business_order_fulfillment CHECK (
        fulfillment_status IN (
            'PENDING_ACCEPTANCE', 'ACCEPTED', 'PROCESSING', 'SHIPPED', 'DELIVERED'
        )
    );

ALTER TABLE business_order_status_history
    DROP CHECK chk_business_order_history_transition,
    ADD CONSTRAINT chk_business_order_history_transition CHECK (
        (from_status = 'PENDING_ACCEPTANCE' AND to_status = 'ACCEPTED'
            AND reason_code = 'BUSINESS_ACCEPTED')
        OR (from_status = 'ACCEPTED' AND to_status = 'PROCESSING'
            AND reason_code = 'BUSINESS_PROCESSING_STARTED')
        OR (from_status = 'PROCESSING' AND to_status = 'SHIPPED'
            AND reason_code = 'MANUAL_SHIPMENT_CREATED')
        OR (from_status = 'SHIPPED' AND to_status = 'DELIVERED'
            AND reason_code = 'LOCAL_DEMO_DELIVERY_RECORDED')
    );

CREATE TABLE business_order_fulfillment_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NULL,
    result_updated_at TIMESTAMP(6) NULL,
    result_shipment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_fulfillment_idempotency (
        actor_user_id, business_id, operation, idempotency_key
    ),
    KEY idx_business_order_fulfillment_expiry (expires_at, id),
    CONSTRAINT chk_business_order_fulfillment_operation CHECK (
        operation IN (
            'PROCESS_BUSINESS_ORDER',
            'CREATE_MANUAL_SHIPMENT',
            'DELIVER_MANUAL_SHIPMENT'
        )
    ),
    CONSTRAINT chk_business_order_fulfillment_command_state CHECK (
        state IN ('IN_PROGRESS', 'COMPLETED')
    ),
    CONSTRAINT chk_business_order_fulfillment_result CHECK (
        (state = 'IN_PROGRESS' AND result_version IS NULL
            AND result_updated_at IS NULL AND result_shipment_id IS NULL)
        OR (state = 'COMPLETED' AND result_version IS NOT NULL
            AND result_version >= 0 AND result_updated_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipments (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    carrier_display_name VARCHAR(80) NOT NULL,
    service_display_name VARCHAR(80) NOT NULL,
    tracking_number VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL,
    shipped_at TIMESTAMP(6) NOT NULL,
    delivered_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_business_order (business_order_id),
    UNIQUE KEY uk_shipment_business_tracking (
        business_id, carrier_display_name, tracking_number
    ),
    CONSTRAINT fk_shipment_business_order FOREIGN KEY (business_order_id)
        REFERENCES business_orders(id),
    CONSTRAINT chk_shipment_source CHECK (source = 'LOCAL_DEMO_MANUAL'),
    CONSTRAINT chk_shipment_status CHECK (status IN ('SHIPPED', 'DELIVERED')),
    CONSTRAINT chk_shipment_version CHECK (version >= 0),
    CONSTRAINT chk_shipment_delivery CHECK (
        (status = 'SHIPPED' AND delivered_at IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL
            AND delivered_at >= shipped_at)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shipment_status_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shipment_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    to_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shipment_version BIGINT NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_history_version (shipment_id, shipment_version),
    KEY idx_shipment_history (shipment_id, created_at, id),
    CONSTRAINT fk_shipment_history_shipment FOREIGN KEY (shipment_id)
        REFERENCES shipments(id),
    CONSTRAINT chk_shipment_history_transition CHECK (
        (from_status = 'NOT_CREATED' AND to_status = 'SHIPPED'
            AND shipment_version = 0 AND reason_code = 'MANUAL_SHIPMENT_CREATED')
        OR (from_status = 'SHIPPED' AND to_status = 'DELIVERED'
            AND shipment_version = 1 AND reason_code = 'LOCAL_DEMO_DELIVERY_RECORDED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
