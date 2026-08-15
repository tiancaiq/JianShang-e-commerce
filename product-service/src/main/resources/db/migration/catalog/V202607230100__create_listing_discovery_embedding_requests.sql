CREATE TABLE listing_discovery_embedding_requests (
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REQUESTED',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_listing_discovery_embedding_event UNIQUE (event_id),
    CONSTRAINT uk_listing_discovery_embedding_identity UNIQUE (
        listing_id,
        listing_version,
        document_hash,
        embedding_input_hash,
        embedding_provider,
        embedding_model,
        embedding_dimensions
    ),
    CONSTRAINT chk_listing_discovery_embedding_version CHECK (listing_version >= 0),
    CONSTRAINT chk_listing_discovery_embedding_hashes CHECK (
        document_hash REGEXP '^[0-9a-f]{64}$'
        AND embedding_input_hash REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_listing_discovery_embedding_language CHECK (language = 'und'),
    CONSTRAINT chk_listing_discovery_embedding_identity CHECK (
        embedding_provider = 'openai'
        AND embedding_model = 'text-embedding-3-small'
        AND embedding_dimensions = 1536
    ),
    CONSTRAINT chk_listing_discovery_embedding_state CHECK (state = 'REQUESTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_discovery_embedding_listing
    ON listing_discovery_embedding_requests (listing_id, listing_version, created_at);

CREATE INDEX idx_listing_discovery_embedding_state
    ON listing_discovery_embedding_requests (state, created_at, request_id);
