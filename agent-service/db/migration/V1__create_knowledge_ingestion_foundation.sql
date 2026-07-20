CREATE TABLE processed_events (
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_version SMALLINT UNSIGNED NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accepted_at DATETIME(6) NOT NULL,
    correlation_id VARCHAR(100) NULL,
    PRIMARY KEY (consumer_name, event_id),
    KEY idx_processed_events_accepted (accepted_at, event_id),
    CONSTRAINT chk_processed_events_version
        CHECK (event_version > 0),
    CONSTRAINT chk_processed_events_payload_hash
        CHECK (payload_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_ingestion_jobs (
    job_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    source_version BIGINT UNSIGNED NOT NULL,
    language VARCHAR(16) NOT NULL,
    lifecycle VARCHAR(20) NOT NULL,
    superseded_version BIGINT UNSIGNED NULL,
    event_occurred_at DATETIME(6) NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_owner VARCHAR(100) NULL,
    claim_expires_at DATETIME(6) NULL,
    last_error_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (job_id),
    UNIQUE KEY uq_knowledge_ingestion_jobs_event (event_id),
    KEY idx_knowledge_jobs_claim (
        status,
        next_attempt_at,
        created_at,
        job_id
    ),
    KEY idx_knowledge_jobs_source (
        source_type,
        source_id,
        source_version
    ),
    KEY idx_knowledge_jobs_expired_claim (
        claim_expires_at,
        status
    ),
    CONSTRAINT chk_knowledge_jobs_lifecycle
        CHECK (lifecycle IN ('ACTIVE', 'INVALIDATED')),
    CONSTRAINT chk_knowledge_jobs_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'RETRY_WAIT',
                'SUCCEEDED',
                'DEAD_LETTER'
            )
        ),
    CONSTRAINT chk_knowledge_jobs_claim_shape
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
    CONSTRAINT chk_knowledge_jobs_completion_shape
        CHECK (
            (
                status IN ('SUCCEEDED', 'DEAD_LETTER')
                AND completed_at IS NOT NULL
            )
            OR (
                status NOT IN ('SUCCEEDED', 'DEAD_LETTER')
                AND completed_at IS NULL
            )
        ),
    CONSTRAINT chk_knowledge_jobs_payload_hash
        CHECK (payload_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_knowledge_jobs_superseded_version
        CHECK (
            superseded_version IS NULL
            OR superseded_version < source_version
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_source_state (
    source_type VARCHAR(40) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    language VARCHAR(16) NOT NULL,
    latest_observed_version BIGINT UNSIGNED NOT NULL,
    latest_indexed_version BIGINT UNSIGNED NULL,
    latest_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_superseded_version BIGINT UNSIGNED NULL,
    state VARCHAR(20) NOT NULL,
    chunker_version VARCHAR(80) NULL,
    embedding_provider VARCHAR(80) NULL,
    embedding_model VARCHAR(160) NULL,
    embedding_dimensions SMALLINT UNSIGNED NULL,
    source_event_occurred_at DATETIME(6) NOT NULL,
    indexed_at DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    last_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_failure_code VARCHAR(80) NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (source_type, source_id, language),
    KEY idx_knowledge_source_state_status (
        state,
        updated_at,
        source_id
    ),
    CONSTRAINT chk_knowledge_source_state_status
        CHECK (state IN ('ACTIVE', 'TOMBSTONED', 'FAILED')),
    CONSTRAINT chk_knowledge_source_state_hash
        CHECK (
            latest_content_hash IS NULL
            OR latest_content_hash REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_knowledge_source_state_embedding
        CHECK (
            (
                embedding_provider IS NULL
                AND embedding_model IS NULL
                AND embedding_dimensions IS NULL
            )
            OR (
                embedding_provider IS NOT NULL
                AND embedding_model IS NOT NULL
                AND embedding_dimensions IS NOT NULL
                AND embedding_dimensions > 0
            )
        ),
    CONSTRAINT chk_knowledge_source_state_versions
        CHECK (
            latest_indexed_version IS NULL
            OR latest_indexed_version <= latest_observed_version
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
