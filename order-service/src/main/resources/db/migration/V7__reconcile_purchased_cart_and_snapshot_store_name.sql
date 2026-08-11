ALTER TABLE checkout_items
    ADD COLUMN store_name VARCHAR(160) NULL AFTER store_id;

ALTER TABLE business_orders
    ADD COLUMN store_name VARCHAR(160) NULL AFTER store_id;

CREATE TABLE checkout_cart_reconciliations (
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cart_owner_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cart_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    reconciled_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (checkout_id),
    KEY idx_cart_reconciliation_work (state, next_attempt_at, checkout_id),
    CONSTRAINT fk_cart_reconciliation_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_sessions(id),
    CONSTRAINT chk_cart_reconciliation_version CHECK (cart_version >= 0),
    CONSTRAINT chk_cart_reconciliation_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_cart_reconciliation_state CHECK (state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT chk_cart_reconciliation_result CHECK (
        (state = 'PENDING' AND reconciled_at IS NULL)
        OR (state = 'COMPLETED' AND reconciled_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkout_cart_reconciliation_items (
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cart_line_identity VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (checkout_id, listing_id),
    CONSTRAINT fk_cart_reconciliation_item_checkout FOREIGN KEY (checkout_id)
        REFERENCES checkout_cart_reconciliations(checkout_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
