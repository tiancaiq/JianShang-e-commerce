CREATE TABLE knowledge_rebuild_runs (
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    target_generation VARCHAR(160) NOT NULL,
    previous_read_generation VARCHAR(160) NOT NULL,
    embedding_provider VARCHAR(80) NOT NULL,
    embedding_model VARCHAR(160) NOT NULL,
    embedding_dimensions SMALLINT UNSIGNED NOT NULL,
    chunker_version VARCHAR(80) NOT NULL,
    export_cursor VARCHAR(1200) NULL,
    export_watermark DATETIME(6) NULL,
    export_complete BOOLEAN NOT NULL DEFAULT FALSE,
    expected_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    processed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    skipped_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    tombstoned_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL,
    last_error_code VARCHAR(80) NULL,
    initiated_by VARCHAR(100) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    promoted_at DATETIME(6) NULL,
    rolled_back_at DATETIME(6) NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id),
    UNIQUE KEY uq_knowledge_rebuild_target (target_generation),
    KEY idx_knowledge_rebuild_status (status, updated_at, run_id),
    CONSTRAINT chk_knowledge_rebuild_source_type
        CHECK (source_type = 'LISTING'),
    CONSTRAINT chk_knowledge_rebuild_status
        CHECK (
            status IN (
                'RUNNING',
                'FAILED',
                'READY_TO_PROMOTE',
                'PROMOTED',
                'ROLLED_BACK'
            )
        ),
    CONSTRAINT chk_knowledge_rebuild_dimensions
        CHECK (embedding_dimensions > 0),
    CONSTRAINT chk_knowledge_rebuild_completion
        CHECK (
            (
                export_complete = TRUE
                AND export_cursor IS NULL
                AND completed_at IS NOT NULL
            )
            OR (
                export_complete = FALSE
                AND completed_at IS NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_deletion_jobs (
    deletion_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    source_version BIGINT UNSIGNED NOT NULL,
    language VARCHAR(16) NOT NULL,
    invalidated_at DATETIME(6) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_owner VARCHAR(100) NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (deletion_id),
    UNIQUE KEY uq_knowledge_deletion_source (
        source_type,
        source_id,
        source_version,
        language
    ),
    KEY idx_knowledge_deletion_claim (
        status,
        next_attempt_at,
        created_at,
        deletion_id
    ),
    KEY idx_knowledge_deletion_expired_claim (
        claim_expires_at,
        status
    ),
    CONSTRAINT chk_knowledge_deletion_source_type
        CHECK (source_type = 'LISTING'),
    CONSTRAINT chk_knowledge_deletion_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'RETRY_WAIT',
                'SUCCEEDED',
                'DEAD_LETTER'
            )
        ),
    CONSTRAINT chk_knowledge_deletion_claim_shape
        CHECK (
            (
                status = 'PROCESSING'
                AND claim_owner IS NOT NULL
                AND claim_expires_at IS NOT NULL
            )
            OR (
                status <> 'PROCESSING'
                AND claim_owner IS NULL
                AND claim_expires_at IS NULL
            )
        ),
    CONSTRAINT chk_knowledge_deletion_completion_shape
        CHECK (
            (
                status IN ('SUCCEEDED', 'DEAD_LETTER')
                AND completed_at IS NOT NULL
            )
            OR (
                status NOT IN ('SUCCEEDED', 'DEAD_LETTER')
                AND completed_at IS NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
