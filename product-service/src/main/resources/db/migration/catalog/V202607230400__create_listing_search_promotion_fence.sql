ALTER TABLE listing_search_projection_work
    ADD COLUMN work_sequence BIGINT NOT NULL AUTO_INCREMENT,
    ADD CONSTRAINT uk_listing_search_projection_sequence UNIQUE (work_sequence);

ALTER TABLE listing_discovery_embedding_receipts
    ADD COLUMN receipt_sequence BIGINT NOT NULL AUTO_INCREMENT,
    ADD CONSTRAINT uk_listing_discovery_embedding_receipt_sequence UNIQUE (receipt_sequence);

CREATE TABLE listing_search_projection_coordination (
    coordination_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fence_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (coordination_key),
    CONSTRAINT chk_listing_search_projection_coordination_key
        CHECK (coordination_key = 'PUBLIC_LISTING'),
    CONSTRAINT chk_listing_search_projection_fence_version
        CHECK (fence_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO listing_search_projection_coordination (
    coordination_key,
    fence_version,
    updated_at
) VALUES (
    'PUBLIC_LISTING',
    0,
    UTC_TIMESTAMP(6)
);

CREATE TABLE listing_search_rebuild_runs (
    run_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_generation VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_read_generation VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_write_generation VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_identity VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_model VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    embedding_dimensions INT NOT NULL,
    start_work_sequence BIGINT NOT NULL,
    receipt_watermark_sequence BIGINT NULL,
    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN state IN (
                'PREPARING',
                'DUAL_WRITE',
                'BACKFILLING',
                'CATCHING_UP',
                'PROMOTION_FENCED',
                'ROLLBACK_REQUIRED'
            ) THEN 1
            ELSE NULL
        END
    ) STORED,
    authoritative_document_count INT NOT NULL DEFAULT 0,
    vector_document_count INT NOT NULL DEFAULT 0,
    catch_up_work_count INT NOT NULL DEFAULT 0,
    deferred_receipt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    promoted_at DATETIME(6) NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT uk_listing_search_rebuild_candidate UNIQUE (candidate_generation),
    CONSTRAINT uk_listing_search_rebuild_active UNIQUE (active_slot),
    CONSTRAINT chk_listing_search_rebuild_state CHECK (
        state IN (
            'PREPARING',
            'DUAL_WRITE',
            'BACKFILLING',
            'CATCHING_UP',
            'PROMOTION_FENCED',
            'PROMOTED',
            'ROLLBACK_REQUIRED',
            'FAILED'
        )
    ),
    CONSTRAINT chk_listing_search_rebuild_identity CHECK (
        schema_identity = 'marketplace-public-listing-v2-vector'
        AND embedding_provider = 'openai'
        AND embedding_model = 'text-embedding-3-small'
        AND embedding_dimensions = 1536
    ),
    CONSTRAINT chk_listing_search_rebuild_sequences CHECK (
        start_work_sequence >= 0
        AND (receipt_watermark_sequence IS NULL OR receipt_watermark_sequence >= 0)
    ),
    CONSTRAINT chk_listing_search_rebuild_counts CHECK (
        authoritative_document_count >= 0
        AND vector_document_count >= 0
        AND catch_up_work_count >= 0
        AND deferred_receipt_count >= 0
    ),
    CONSTRAINT chk_listing_search_rebuild_promotion CHECK (
        (state = 'PROMOTED' AND promoted_at IS NOT NULL)
        OR (state <> 'PROMOTED' AND promoted_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_search_rebuild_recovery
    ON listing_search_rebuild_runs (state, updated_at, run_id);
