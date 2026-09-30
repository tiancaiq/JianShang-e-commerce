CREATE TABLE large_catalog_seed_entities (
    seed_namespace VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    entity_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (seed_namespace, entity_type, entity_id),
    CONSTRAINT uk_large_catalog_seed_entity UNIQUE (entity_type, entity_id),
    CONSTRAINT chk_large_catalog_seed_entity_type CHECK (
        entity_type IN ('USER', 'INDIVIDUAL_PROFILE', 'BUSINESS_APPLICATION', 'BUSINESS', 'STORE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_large_catalog_seed_namespace_type
    ON large_catalog_seed_entities (seed_namespace, entity_type);
