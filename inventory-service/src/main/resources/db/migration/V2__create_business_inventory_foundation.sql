CREATE TABLE inventory_items (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sku_snapshot VARCHAR(120) NOT NULL,
    catalog_version_snapshot BIGINT NOT NULL,
    on_hand INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    initialized_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_items_listing UNIQUE (listing_id),
    CONSTRAINT chk_inventory_items_on_hand CHECK (on_hand >= 0),
    CONSTRAINT chk_inventory_items_reserved CHECK (reserved >= 0),
    CONSTRAINT chk_inventory_items_available CHECK (reserved <= on_hand)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_items_business_updated
    ON inventory_items (business_id, updated_at, id);
CREATE INDEX idx_inventory_items_business_sku
    ON inventory_items (business_id, sku_snapshot);

CREATE TABLE inventory_idempotency_records (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    caller_scope VARCHAR(220) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_resource_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    http_status SMALLINT NOT NULL,
    response_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_idempotency_scope_key UNIQUE (caller_scope, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_idempotency_expiry
    ON inventory_idempotency_records (expires_at);

CREATE TABLE inventory_movements (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    inventory_item_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity_delta INT NOT NULL,
    on_hand_before INT NOT NULL,
    on_hand_after INT NOT NULL,
    reserved_snapshot INT NOT NULL,
    note VARCHAR(500) NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_movements_command UNIQUE (command_id),
    CONSTRAINT fk_inventory_movements_item
        FOREIGN KEY (inventory_item_id) REFERENCES inventory_items (id),
    CONSTRAINT chk_inventory_movements_operation
        CHECK (operation IN ('INITIALIZE', 'SET', 'ADJUST')),
    CONSTRAINT chk_inventory_movements_balances
        CHECK (on_hand_before >= 0 AND on_hand_after >= 0 AND reserved_snapshot >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_movements_item_created
    ON inventory_movements (inventory_item_id, created_at, id);
CREATE INDEX idx_inventory_movements_business_created
    ON inventory_movements (business_id, created_at, id);

CREATE TABLE inventory_outbox_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_version INT NOT NULL,
    payload JSON NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    causation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_inventory_outbox_publication
    ON inventory_outbox_events (published_at, created_at);
