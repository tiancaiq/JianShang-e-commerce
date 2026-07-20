ALTER TABLE checkout_sessions
    DROP INDEX uk_checkout_active_buyer,
    DROP COLUMN active_buyer_id,
    DROP CHECK chk_checkout_status;

ALTER TABLE checkout_sessions
    ADD COLUMN active_buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN (
                    'RESERVING',
                    'PENDING_PAYMENT',
                    'PAYMENT_PROCESSING',
                    'PAYMENT_REVIEW'
                ) THEN buyer_id
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_checkout_active_buyer (active_buyer_id),
    ADD CONSTRAINT chk_checkout_status CHECK (
        status IN (
            'RESERVING',
            'PENDING_PAYMENT',
            'PAYMENT_PROCESSING',
            'PAYMENT_REVIEW',
            'REFUND_REQUIRED',
            'COMPLETED',
            'FAILED',
            'CANCELLED',
            'EXPIRED'
        )
    );

CREATE TABLE checkout_payment_intents (
    payment_intent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_version BIGINT NOT NULL,
    checkout_snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_ids_json JSON NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (payment_intent_id),
    UNIQUE KEY uk_checkout_payment_intent_checkout (checkout_id),
    CONSTRAINT fk_checkout_payment_intent_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT chk_checkout_payment_intent_amount CHECK (amount > 0),
    CONSTRAINT chk_checkout_payment_intent_businesses CHECK (
        JSON_TYPE(business_ids_json) = 'ARRAY'
        AND JSON_LENGTH(business_ids_json) BETWEEN 1 AND 50
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE processed_payment_events (
    consumer_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_intent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_expires_at TIMESTAMP(6) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    KEY idx_processed_payment_event_claim (state, claim_expires_at),
    KEY idx_processed_payment_event_checkout (checkout_id, event_id),
    CONSTRAINT chk_processed_payment_event_state CHECK (
        state IN ('PROCESSING', 'RETRYABLE', 'COMPLETED', 'REJECTED')
    ),
    CONSTRAINT chk_processed_payment_event_claim CHECK (
        (state = 'PROCESSING' AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR
        (state <> 'PROCESSING' AND claim_token IS NULL AND claim_expires_at IS NULL)
    ),
    CONSTRAINT chk_processed_payment_event_result CHECK (
        (state IN ('COMPLETED', 'REJECTED') AND outcome IS NOT NULL AND processed_at IS NOT NULL)
        OR
        (state IN ('PROCESSING', 'RETRYABLE') AND outcome IS NULL AND processed_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE orders (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_intent_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_number CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtotal DECIMAL(19,4) NOT NULL,
    shipping DECIMAL(19,4) NOT NULL,
    tax DECIMAL(19,4) NOT NULL,
    discount DECIMAL(19,4) NOT NULL,
    total DECIMAL(19,4) NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_checkout (checkout_id),
    UNIQUE KEY uk_order_payment_intent (payment_intent_id),
    UNIQUE KEY uk_order_number (order_number),
    KEY idx_order_buyer_created (buyer_id, created_at, id),
    CONSTRAINT fk_order_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT chk_order_amounts CHECK (
        subtotal >= 0 AND shipping >= 0 AND tax >= 0 AND discount >= 0 AND total > 0
    ),
    CONSTRAINT chk_order_total CHECK (total = subtotal + shipping + tax - discount),
    CONSTRAINT chk_order_payment_status CHECK (payment_status = 'SUCCEEDED'),
    CONSTRAINT chk_order_status CHECK (status = 'CONFIRMED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_orders (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_order_number CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fulfillment_status VARCHAR(32) NOT NULL,
    cancellation_status VARCHAR(32) NOT NULL,
    subtotal DECIMAL(19,4) NOT NULL,
    shipping DECIMAL(19,4) NOT NULL,
    tax DECIMAL(19,4) NOT NULL,
    discount DECIMAL(19,4) NOT NULL,
    total DECIMAL(19,4) NOT NULL,
    platform_fee_projection DECIMAL(19,4) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_business_order_group (order_id, business_id),
    UNIQUE KEY uk_business_order_number (seller_order_number),
    KEY idx_business_order_queue (business_id, fulfillment_status, created_at, id),
    CONSTRAINT fk_business_order_order FOREIGN KEY (order_id)
        REFERENCES orders(id),
    CONSTRAINT chk_business_order_amounts CHECK (
        subtotal >= 0 AND shipping >= 0 AND tax >= 0 AND discount >= 0 AND total >= 0
        AND platform_fee_projection IS NULL
    ),
    CONSTRAINT chk_business_order_total CHECK (
        total = subtotal + shipping + tax - discount
    ),
    CONSTRAINT chk_business_order_fulfillment CHECK (
        fulfillment_status = 'PENDING_ACCEPTANCE'
    ),
    CONSTRAINT chk_business_order_cancellation CHECK (cancellation_status = 'NONE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_number INT NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    catalog_version BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    sku VARCHAR(100) NULL,
    item_condition VARCHAR(32) NOT NULL,
    thumbnail_url VARCHAR(2048) NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    line_subtotal DECIMAL(19,4) NOT NULL,
    shipping_allocation DECIMAL(19,4) NOT NULL,
    tax_allocation DECIMAL(19,4) NOT NULL,
    discount_allocation DECIMAL(19,4) NOT NULL,
    line_total DECIMAL(19,4) NOT NULL,
    policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_line (order_id, line_number),
    UNIQUE KEY uk_order_item_listing (order_id, listing_id),
    KEY idx_order_item_business_order (business_order_id, line_number),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_item_business_order FOREIGN KEY (business_order_id)
        REFERENCES business_orders(id),
    CONSTRAINT chk_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_item_amounts CHECK (
        unit_price >= 0 AND line_subtotal >= 0 AND shipping_allocation >= 0
        AND tax_allocation >= 0 AND discount_allocation >= 0 AND line_total >= 0
    ),
    CONSTRAINT chk_order_item_total CHECK (
        line_total = line_subtotal + shipping_allocation + tax_allocation - discount_allocation
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_addresses (
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    address_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_address_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version BIGINT NOT NULL,
    label VARCHAR(40) NULL,
    recipient_name VARCHAR(120) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    line1 VARCHAR(200) NOT NULL,
    line2 VARCHAR(200) NULL,
    city VARCHAR(100) NOT NULL,
    region VARCHAR(100) NOT NULL,
    postal_code VARCHAR(32) NOT NULL,
    country_code CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshotted_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (order_id, address_type),
    CONSTRAINT fk_order_address_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT chk_order_address_type CHECK (address_type = 'SHIPPING')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_status_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_history_causation (order_id, causation_id, to_status),
    KEY idx_order_history (order_id, created_at, id),
    CONSTRAINT fk_order_history_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
