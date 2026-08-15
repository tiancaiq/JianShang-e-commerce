CREATE TABLE listing_search_projection_work (
    work_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_version BIGINT NOT NULL,
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_id),
    CONSTRAINT uk_listing_search_projection_version UNIQUE (listing_id, listing_version),
    CONSTRAINT chk_listing_search_projection_version CHECK (listing_version >= 0),
    CONSTRAINT chk_listing_search_projection_operation CHECK (operation IN ('UPSERT', 'DELETE')),
    CONSTRAINT chk_listing_search_projection_state CHECK (state IN ('PENDING', 'APPLIED', 'TERMINAL')),
    CONSTRAINT chk_listing_search_projection_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_listing_search_projection_claim CHECK (
        (claim_token IS NULL AND claim_expires_at IS NULL)
        OR (claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
    ),
    CONSTRAINT chk_listing_search_projection_completion CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state IN ('APPLIED', 'TERMINAL') AND completed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_search_projection_claim
    ON listing_search_projection_work (state, next_attempt_at, claim_expires_at, created_at, work_id);

CREATE INDEX idx_listing_search_projection_listing
    ON listing_search_projection_work (listing_id, listing_version, state);
