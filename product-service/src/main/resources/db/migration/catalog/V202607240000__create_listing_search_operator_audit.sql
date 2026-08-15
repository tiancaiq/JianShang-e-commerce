CREATE TABLE listing_search_operator_audit (
    audit_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admin_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    from_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (audit_id),
    CONSTRAINT fk_listing_search_operator_audit_run
        FOREIGN KEY (run_id) REFERENCES listing_search_rebuild_runs (run_id),
    CONSTRAINT chk_listing_search_operator_audit_action
        CHECK (action IN ('PREPARE', 'CATCH_UP', 'PROMOTE', 'RECOVER')),
    CONSTRAINT chk_listing_search_operator_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'REPLAYED', 'FAILED')),
    CONSTRAINT chk_listing_search_operator_audit_states
        CHECK (
            (from_state IS NULL OR from_state IN (
                'PREPARING', 'DUAL_WRITE', 'BACKFILLING', 'CATCHING_UP',
                'PROMOTION_FENCED', 'PROMOTED', 'ROLLBACK_REQUIRED', 'FAILED'
            ))
            AND (to_state IS NULL OR to_state IN (
                'PREPARING', 'DUAL_WRITE', 'BACKFILLING', 'CATCHING_UP',
                'PROMOTION_FENCED', 'PROMOTED', 'ROLLBACK_REQUIRED', 'FAILED'
            ))
        ),
    CONSTRAINT chk_listing_search_operator_audit_result
        CHECK (
            (outcome IN ('SUCCEEDED', 'REPLAYED') AND error_code IS NULL)
            OR (outcome = 'FAILED' AND error_code IS NOT NULL)
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_search_operator_audit_run
    ON listing_search_operator_audit (run_id, occurred_at, audit_id);

CREATE INDEX idx_listing_search_operator_audit_admin
    ON listing_search_operator_audit (admin_user_id, occurred_at, audit_id);
