CREATE TABLE large_catalog_seed_listings (
    seed_namespace VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_identity VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_domain VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (seed_namespace, source_identity),
    CONSTRAINT uk_large_catalog_seed_listing UNIQUE (listing_id),
    CONSTRAINT fk_large_catalog_seed_listing FOREIGN KEY (listing_id) REFERENCES listings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_large_catalog_seed_namespace_listing
    ON large_catalog_seed_listings (seed_namespace, listing_id);
