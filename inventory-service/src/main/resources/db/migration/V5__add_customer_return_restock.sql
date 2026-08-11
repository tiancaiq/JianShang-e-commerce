CREATE TABLE inventory_return_restocks (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    restored_quantity INT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_return_restock_return (return_id),
    UNIQUE KEY uk_inventory_return_restock_key (idempotency_key),
    CONSTRAINT fk_inventory_return_restock_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservations(id),
    CONSTRAINT chk_inventory_return_restock_quantity CHECK (restored_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
