CREATE TABLE listing_knowledge_versions (
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version BIGINT NOT NULL,
    supersedes_version BIGINT NULL,
    lifecycle VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seller_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language VARCHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(180) NULL,
    description TEXT NULL,
    price_amount DECIMAL(19, 4) NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    public_city VARCHAR(120) NULL,
    public_region VARCHAR(120) NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    effective_from DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    source_published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (listing_id, source_version),
    CONSTRAINT chk_listing_knowledge_version_positive CHECK (source_version >= 0),
    CONSTRAINT chk_listing_knowledge_supersedes CHECK (
        supersedes_version IS NULL OR supersedes_version < source_version
    ),
    CONSTRAINT chk_listing_knowledge_lifecycle CHECK (lifecycle IN ('ACTIVE', 'INVALIDATED')),
    CONSTRAINT chk_listing_knowledge_seller_type CHECK (seller_type = 'INDIVIDUAL'),
    CONSTRAINT chk_listing_knowledge_visibility CHECK (visibility = 'PUBLIC'),
    CONSTRAINT chk_listing_knowledge_language CHECK (language = 'und'),
    CONSTRAINT chk_listing_knowledge_shape CHECK (
        (
            lifecycle = 'ACTIVE'
            AND title IS NOT NULL
            AND description IS NOT NULL
            AND price_amount IS NOT NULL
            AND currency IS NOT NULL
            AND public_city IS NOT NULL
            AND public_region IS NOT NULL
            AND content_hash IS NOT NULL
            AND effective_from IS NOT NULL
            AND invalidated_at IS NULL
        )
        OR
        (
            lifecycle = 'INVALIDATED'
            AND supersedes_version IS NOT NULL
            AND title IS NULL
            AND description IS NULL
            AND price_amount IS NULL
            AND currency IS NULL
            AND public_city IS NULL
            AND public_region IS NULL
            AND content_hash IS NULL
            AND effective_from IS NULL
            AND invalidated_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_knowledge_active_export
    ON listing_knowledge_versions (lifecycle, listing_id, source_version);
CREATE INDEX idx_listing_knowledge_listing_lifecycle
    ON listing_knowledge_versions (listing_id, lifecycle, source_version);
CREATE INDEX idx_listing_knowledge_created
    ON listing_knowledge_versions (created_at, listing_id, source_version);

CREATE TABLE outbox_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_type VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_version SMALLINT NOT NULL,
    producer VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    correlation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json JSON NOT NULL,
    deduplication_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_outbox_events_deduplication_key UNIQUE (deduplication_key),
    CONSTRAINT chk_outbox_event_version CHECK (event_version > 0),
    CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0),
    CONSTRAINT chk_outbox_claim_shape CHECK (
        (claim_token IS NULL AND claim_expires_at IS NULL)
        OR (claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_outbox_events_publication
    ON outbox_events (published_at, next_attempt_at, created_at, event_id);
CREATE INDEX idx_outbox_events_claim
    ON outbox_events (claim_expires_at, published_at);
