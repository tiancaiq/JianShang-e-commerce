CREATE TABLE listing_discovery_embedding_receipts (
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    document_schema_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_schema_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalizer_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    redactor_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_model VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_dimensions INT NOT NULL,
    vector_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    vector_bytes VARBINARY(6144) NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT fk_listing_discovery_embedding_receipt_request
        FOREIGN KEY (request_id)
        REFERENCES listing_discovery_embedding_requests (request_id),
    CONSTRAINT chk_listing_discovery_embedding_receipt_version
        CHECK (listing_version >= 1),
    CONSTRAINT chk_listing_discovery_embedding_receipt_hashes CHECK (
        document_hash REGEXP '^[0-9a-f]{64}$'
        AND embedding_input_hash REGEXP '^[0-9a-f]{64}$'
        AND vector_hash REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_listing_discovery_embedding_receipt_schema CHECK (
        document_schema_version = 'MARKETPLACE_LISTING_DISCOVERY_V2'
        AND embedding_input_schema_version = 'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1'
        AND normalizer_version = 'NFKC_WHITESPACE_V1'
        AND redactor_version = 'PUBLIC_CONTACT_REDACTION_V1'
        AND language = 'und'
    ),
    CONSTRAINT chk_listing_discovery_embedding_receipt_identity CHECK (
        embedding_provider = 'openai'
        AND embedding_model = 'text-embedding-3-small'
        AND embedding_dimensions = 1536
    ),
    CONSTRAINT chk_listing_discovery_embedding_receipt_vector
        CHECK (OCTET_LENGTH(vector_bytes) = 6144)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_discovery_embedding_receipt_rebuild
    ON listing_discovery_embedding_receipts (
        listing_id,
        listing_version,
        document_hash,
        embedding_input_hash,
        embedding_provider,
        embedding_model,
        embedding_dimensions
    );
