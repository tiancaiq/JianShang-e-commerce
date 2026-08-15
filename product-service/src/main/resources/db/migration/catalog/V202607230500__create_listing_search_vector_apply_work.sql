CREATE TABLE listing_search_vector_apply_work (
    work_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_sequence BIGINT NOT NULL AUTO_INCREMENT,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_id),
    CONSTRAINT uk_listing_search_vector_apply_sequence UNIQUE (work_sequence),
    CONSTRAINT uk_listing_search_vector_apply_request UNIQUE (request_id),
    CONSTRAINT fk_listing_search_vector_apply_receipt
        FOREIGN KEY (request_id)
        REFERENCES listing_discovery_embedding_receipts (request_id),
    CONSTRAINT chk_listing_search_vector_apply_version CHECK (listing_version >= 1),
    CONSTRAINT chk_listing_search_vector_apply_state
        CHECK (state IN ('PENDING', 'APPLIED', 'STALE', 'TERMINAL')),
    CONSTRAINT chk_listing_search_vector_apply_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_listing_search_vector_apply_claim CHECK (
        (claim_token IS NULL AND claim_expires_at IS NULL)
        OR (claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
    ),
    CONSTRAINT chk_listing_search_vector_apply_completion CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state IN ('APPLIED', 'STALE', 'TERMINAL') AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_search_vector_apply_claim
    ON listing_search_vector_apply_work (
        state,
        next_attempt_at,
        claim_expires_at,
        work_sequence
    );

CREATE INDEX idx_listing_search_vector_apply_listing
    ON listing_search_vector_apply_work (
        listing_id,
        listing_version,
        state
    );
