ALTER TABLE inventory_idempotency_records
    MODIFY result_resource_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE inventory_reservations (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    committed_at TIMESTAMP(6) NULL,
    released_at TIMESTAMP(6) NULL,
    release_reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_checkout_purpose UNIQUE (checkout_id, purpose),
    CONSTRAINT chk_inventory_reservation_purpose
        CHECK (purpose IN ('CHECKOUT', 'PAYMENT_RECOVERY')),
    CONSTRAINT chk_inventory_reservation_status
        CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_inventory_reservation_terminal_time CHECK (
        (status = 'ACTIVE' AND committed_at IS NULL AND released_at IS NULL)
        OR (status = 'COMMITTED' AND committed_at IS NOT NULL AND released_at IS NULL)
        OR (status IN ('RELEASED', 'EXPIRED') AND committed_at IS NULL AND released_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_reservation_expiry
    ON inventory_reservations (status, expires_at, id);
CREATE INDEX idx_inventory_reservation_checkout
    ON inventory_reservations (checkout_id, created_at, id);

CREATE TABLE inventory_reservation_items (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    inventory_item_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    on_hand_after_reserve INT NOT NULL,
    reserved_after_reserve INT NOT NULL,
    item_version_after_reserve BIGINT NOT NULL,
    on_hand_after_terminal INT NULL,
    reserved_after_terminal INT NULL,
    item_version_after_terminal BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_item UNIQUE (reservation_id, inventory_item_id),
    CONSTRAINT uk_inventory_reservation_listing UNIQUE (reservation_id, listing_id),
    CONSTRAINT fk_inventory_reservation_item_reservation
        FOREIGN KEY (reservation_id) REFERENCES inventory_reservations (id),
    CONSTRAINT fk_inventory_reservation_item_inventory
        FOREIGN KEY (inventory_item_id) REFERENCES inventory_items (id),
    CONSTRAINT chk_inventory_reservation_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_inventory_reservation_item_reserve_balances CHECK (
        on_hand_after_reserve >= 0
        AND reserved_after_reserve >= 0
        AND reserved_after_reserve <= on_hand_after_reserve
    ),
    CONSTRAINT chk_inventory_reservation_item_terminal_balances CHECK (
        (on_hand_after_terminal IS NULL
            AND reserved_after_terminal IS NULL
            AND item_version_after_terminal IS NULL)
        OR (on_hand_after_terminal >= 0
            AND reserved_after_terminal >= 0
            AND reserved_after_terminal <= on_hand_after_terminal
            AND item_version_after_terminal IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_reservation_item_inventory
    ON inventory_reservation_items (inventory_item_id, reservation_id);

CREATE TABLE inventory_reservation_history (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checkout_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deduplication_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_reservation_history_command UNIQUE (command_id),
    CONSTRAINT uk_inventory_reservation_history_dedup UNIQUE (deduplication_key),
    CONSTRAINT fk_inventory_reservation_history_reservation
        FOREIGN KEY (reservation_id) REFERENCES inventory_reservations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_reservation_history_reservation
    ON inventory_reservation_history (reservation_id, created_at, id);
