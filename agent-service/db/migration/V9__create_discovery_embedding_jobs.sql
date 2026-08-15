CREATE TABLE discovery_embedding_jobs (
    job_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT UNSIGNED NOT NULL,
    document_schema_version VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_schema_version VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalizer_version VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    redactor_version VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    language VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_provider VARCHAR(32)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_model VARCHAR(80)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_dimensions SMALLINT UNSIGNED NOT NULL,
    event_occurred_at DATETIME(6) NOT NULL,
    correlation_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_payload_hash CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (job_id),
    UNIQUE KEY uq_discovery_embedding_event (event_id),
    UNIQUE KEY uq_discovery_embedding_request (request_id),
    KEY idx_discovery_embedding_due (
        status,
        next_attempt_at,
        claim_expires_at,
        job_id
    ),
    KEY idx_discovery_embedding_listing_version (
        listing_id,
        listing_version,
        created_at
    ),
    CONSTRAINT chk_discovery_embedding_listing_version
        CHECK (listing_version > 0),
    CONSTRAINT chk_discovery_embedding_hashes
        CHECK (
            document_hash REGEXP '^[0-9a-f]{64}$'
            AND embedding_input_hash REGEXP '^[0-9a-f]{64}$'
            AND event_payload_hash REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_discovery_embedding_schemas
        CHECK (
            document_schema_version = 'MARKETPLACE_LISTING_DISCOVERY_V2'
            AND embedding_input_schema_version =
                'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1'
            AND normalizer_version = 'NFKC_WHITESPACE_V1'
            AND redactor_version = 'PUBLIC_CONTACT_REDACTION_V1'
            AND language = 'und'
        ),
    CONSTRAINT chk_discovery_embedding_identity
        CHECK (
            embedding_provider = 'openai'
            AND embedding_model = 'text-embedding-3-small'
            AND embedding_dimensions = 1536
        ),
    CONSTRAINT chk_discovery_embedding_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'RETRY_WAIT',
                'SUCCEEDED',
                'DEAD_LETTER'
            )
        ),
    CONSTRAINT chk_discovery_embedding_claim
        CHECK (
            (
                status = 'PROCESSING'
                AND claim_owner IS NOT NULL
                AND claim_expires_at IS NOT NULL
                AND completed_at IS NULL
            )
            OR (
                status IN ('PENDING', 'RETRY_WAIT')
                AND claim_owner IS NULL
                AND claim_expires_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                status IN ('SUCCEEDED', 'DEAD_LETTER')
                AND claim_owner IS NULL
                AND claim_expires_at IS NULL
                AND completed_at IS NOT NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
