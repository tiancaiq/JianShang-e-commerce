CREATE TABLE inventory_cancellation_restocks (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancellation_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    restored_quantity INT NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_cancellation_restock_request (cancellation_request_id),
    UNIQUE KEY uk_inventory_cancellation_restock_order (order_id),
    UNIQUE KEY uk_inventory_cancellation_restock_reservation (reservation_id),
    UNIQUE KEY uk_inventory_cancellation_restock_key (idempotency_key),
    CONSTRAINT fk_inventory_cancellation_restock_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservations(id),
    CONSTRAINT chk_inventory_cancellation_restock_status CHECK (status = 'COMPLETED'),
    CONSTRAINT chk_inventory_cancellation_restock_quantity CHECK (restored_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
