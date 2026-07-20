CREATE TABLE platform_policy_versions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(32) NOT NULL,
    shipping_text VARCHAR(500) NOT NULL,
    cancellation_text VARCHAR(500) NOT NULL,
    return_text VARCHAR(500) NOT NULL,
    effective_from TIMESTAMP(6) NOT NULL,
    effective_to TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_policy_version_code (version_code),
    CONSTRAINT chk_platform_policy_source CHECK (source = 'PLATFORM_DEFAULT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO platform_policy_versions (
    id, version_code, source, shipping_text, cancellation_text, return_text,
    effective_from, effective_to, created_at
) VALUES (
    '01KXQCHKPOLICYLOCALDEMOV10',
    'LOCAL_DEMO_V1',
    'PLATFORM_DEFAULT',
    'Local demo checkout does not include a carrier or delivery-date promise.',
    'An unpaid checkout may be cancelled before expiry. Paid-order cancellation is not available in this local demo.',
    'Automated returns are unavailable in this local demo and require a later approved workflow.',
    '2026-07-19 00:00:00.000000',
    NULL,
    '2026-07-19 00:00:00.000000'
);

CREATE TABLE checkout_sessions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    cart_version BIGINT NOT NULL,
    cart_snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subtotal DECIMAL(19,4) NOT NULL,
    shipping DECIMAL(19,4) NOT NULL,
    tax DECIMAL(19,4) NOT NULL,
    discount DECIMAL(19,4) NOT NULL,
    total DECIMAL(19,4) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reservation_status VARCHAR(32) NULL,
    reservation_version BIGINT NULL,
    release_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    last_release_error_code VARCHAR(64) NULL,
    release_attempts INT NOT NULL DEFAULT 0,
    failure_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    active_buyer_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('RESERVING', 'PENDING_PAYMENT') THEN buyer_id ELSE NULL END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_active_buyer (active_buyer_id),
    UNIQUE KEY uk_checkout_reservation (reservation_id),
    KEY idx_checkout_buyer_created (buyer_id, created_at, id),
    KEY idx_checkout_expiry (status, expires_at, id),
    KEY idx_checkout_recovery (status, updated_at, id),
    KEY idx_checkout_release (release_status, updated_at, id),
    CONSTRAINT chk_checkout_status CHECK (
        status IN ('RESERVING', 'PENDING_PAYMENT', 'FAILED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT chk_checkout_release_status CHECK (
        release_status IN ('NOT_REQUIRED', 'PENDING', 'COMPLETE')
    ),
    CONSTRAINT chk_checkout_amounts CHECK (
        subtotal >= 0 AND shipping >= 0 AND tax >= 0 AND discount >= 0 AND total >= 0
    ),
    CONSTRAINT chk_checkout_total CHECK (total = subtotal + shipping + tax - discount),
    CONSTRAINT chk_checkout_cart_version CHECK (cart_version >= 0),
    CONSTRAINT chk_checkout_release_attempts CHECK (release_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_addresses (
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
    PRIMARY KEY (checkout_id),
    CONSTRAINT fk_checkout_address_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_policy_snapshots (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_policy_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(32) NOT NULL,
    version_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shipping_text VARCHAR(500) NOT NULL,
    cancellation_text VARCHAR(500) NOT NULL,
    return_text VARCHAR(500) NOT NULL,
    snapshotted_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_policy_business (checkout_id, business_id),
    CONSTRAINT fk_checkout_policy_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT fk_checkout_policy_source FOREIGN KEY (source_policy_id)
        REFERENCES platform_policy_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_items (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
    policy_snapshot_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_item_listing (checkout_id, listing_id),
    UNIQUE KEY uk_checkout_item_line (checkout_id, line_number),
    CONSTRAINT fk_checkout_item_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT fk_checkout_item_policy FOREIGN KEY (policy_snapshot_id)
        REFERENCES checkout_policy_snapshots(id),
    CONSTRAINT chk_checkout_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_checkout_item_amounts CHECK (
        unit_price >= 0 AND line_subtotal >= 0 AND shipping_allocation >= 0
        AND tax_allocation >= 0 AND discount_allocation >= 0 AND line_total >= 0
    ),
    CONSTRAINT chk_checkout_item_total CHECK (
        line_total = line_subtotal + shipping_allocation + tax_allocation - discount_allocation
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_shipping_quotes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    method_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    adapter VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_reference VARCHAR(200) NULL,
    quoted_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_shipping_business (checkout_id, business_id),
    CONSTRAINT fk_checkout_shipping_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT chk_checkout_shipping_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_tax_quotes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    adapter VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_reference VARCHAR(200) NULL,
    quoted_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_tax_checkout (checkout_id),
    CONSTRAINT fk_checkout_tax_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT chk_checkout_tax_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_status_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) NULL,
    causation_id VARCHAR(128) NULL,
    details_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_history_command (command_id),
    KEY idx_checkout_history (checkout_id, created_at, id),
    CONSTRAINT fk_checkout_history_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_idempotency_records (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caller_scope VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    http_status INT NULL,
    response_json LONGTEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_idempotency_scope_key (caller_scope, idempotency_key),
    KEY idx_order_idempotency_expiry (expires_at, id),
    CONSTRAINT chk_order_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT fk_order_idempotency_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_outbox_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL,
    payload_json JSON NOT NULL,
    correlation_id VARCHAR(128) NULL,
    causation_id VARCHAR(128) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_order_outbox_unpublished (published_at, created_at, id),
    CONSTRAINT chk_order_outbox_retry CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
