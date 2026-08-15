CREATE TABLE listing_embedding_request_backfill_runs (
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upper_bound_listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_processed_listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN state IN ('PENDING', 'RUNNING', 'FAILED') THEN 1 ELSE NULL END
    ) STORED,
    page_count INT NOT NULL DEFAULT 0,
    processed_count INT NOT NULL DEFAULT 0,
    created_count INT NOT NULL DEFAULT 0,
    already_present_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    started_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT uk_listing_embedding_backfill_active UNIQUE (active_slot),
    CONSTRAINT chk_listing_embedding_backfill_state
        CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_listing_embedding_backfill_cursor
        CHECK (
            (upper_bound_listing_id IS NULL AND last_processed_listing_id IS NULL)
            OR (
                upper_bound_listing_id IS NOT NULL
                AND (
                    last_processed_listing_id IS NULL
                    OR last_processed_listing_id <= upper_bound_listing_id
                )
            )
        ),
    CONSTRAINT chk_listing_embedding_backfill_counts
        CHECK (
            page_count >= 0
            AND processed_count >= 0
            AND created_count >= 0
            AND already_present_count >= 0
            AND skipped_count >= 0
            AND failed_count >= 0
            AND processed_count = created_count + already_present_count + skipped_count
        ),
    CONSTRAINT chk_listing_embedding_backfill_lease
        CHECK (
            (state = 'RUNNING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (state <> 'RUNNING' AND lease_token IS NULL AND lease_expires_at IS NULL)
        ),
    CONSTRAINT chk_listing_embedding_backfill_completion
        CHECK (
            (state = 'COMPLETED' AND completed_at IS NOT NULL AND last_error_code IS NULL)
            OR (state <> 'COMPLETED' AND completed_at IS NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_embedding_backfill_recovery
    ON listing_embedding_request_backfill_runs (state, lease_expires_at, updated_at, run_id);

ALTER TABLE listing_search_operator_audit
    ADD COLUMN embedding_backfill_run_id
        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER run_id,
    ADD CONSTRAINT fk_listing_search_operator_audit_embedding_backfill
        FOREIGN KEY (embedding_backfill_run_id)
        REFERENCES listing_embedding_request_backfill_runs (run_id);

ALTER TABLE listing_search_operator_audit
    DROP CHECK chk_listing_search_operator_audit_action,
    ADD CONSTRAINT chk_listing_search_operator_audit_action
        CHECK (action IN (
            'PREPARE',
            'CATCH_UP',
            'PROMOTE',
            'RECOVER',
            'EMBEDDING_START',
            'EMBEDDING_RESUME'
        ));

ALTER TABLE listing_search_operator_audit
    DROP CHECK chk_listing_search_operator_audit_states,
    ADD CONSTRAINT chk_listing_search_operator_audit_states
        CHECK (
            (from_state IS NULL OR from_state IN (
                'PREPARING', 'DUAL_WRITE', 'BACKFILLING', 'CATCHING_UP',
                'PROMOTION_FENCED', 'PROMOTED', 'ROLLBACK_REQUIRED', 'FAILED',
                'PENDING', 'RUNNING', 'COMPLETED'
            ))
            AND (to_state IS NULL OR to_state IN (
                'PREPARING', 'DUAL_WRITE', 'BACKFILLING', 'CATCHING_UP',
                'PROMOTION_FENCED', 'PROMOTED', 'ROLLBACK_REQUIRED', 'FAILED',
                'PENDING', 'RUNNING', 'COMPLETED'
            ))
        );

CREATE INDEX idx_listing_search_operator_audit_embedding_backfill
    ON listing_search_operator_audit (
        embedding_backfill_run_id,
        occurred_at,
        audit_id
    );
