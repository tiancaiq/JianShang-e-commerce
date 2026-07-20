ALTER TABLE knowledge_deletion_jobs
    DROP CHECK chk_knowledge_deletion_source_type,
    ADD CONSTRAINT chk_knowledge_deletion_source_type
        CHECK (source_type IN ('LISTING', 'CATEGORY_GUIDANCE'));

ALTER TABLE knowledge_rebuild_runs
    DROP CHECK chk_knowledge_rebuild_source_type,
    ADD CONSTRAINT chk_knowledge_rebuild_source_type
        CHECK (source_type IN ('LISTING', 'PUBLIC_KNOWLEDGE'));

CREATE TABLE knowledge_rebuild_sources (
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    sanitizer_version VARCHAR(80) NOT NULL,
    chunker_version VARCHAR(80) NOT NULL,
    export_cursor VARCHAR(1200) NULL,
    export_watermark DATETIME(6) NULL,
    export_complete BOOLEAN NOT NULL DEFAULT FALSE,
    expected_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    processed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    skipped_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    tombstoned_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id, source_type),
    KEY idx_knowledge_rebuild_sources_progress (
        source_type,
        export_complete,
        updated_at,
        run_id
    ),
    CONSTRAINT fk_knowledge_rebuild_sources_run
        FOREIGN KEY (run_id)
        REFERENCES knowledge_rebuild_runs (run_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_knowledge_rebuild_sources_type
        CHECK (source_type IN ('LISTING', 'CATEGORY_GUIDANCE')),
    CONSTRAINT chk_knowledge_rebuild_sources_completion
        CHECK (
            (export_complete = TRUE AND export_cursor IS NULL)
            OR export_complete = FALSE
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
