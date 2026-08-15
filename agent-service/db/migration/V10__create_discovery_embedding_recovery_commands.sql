ALTER TABLE discovery_embedding_jobs
    DROP CHECK chk_discovery_embedding_listing_version;

ALTER TABLE discovery_embedding_jobs
    ADD CONSTRAINT chk_discovery_embedding_listing_version
        CHECK (listing_version >= 0);

CREATE TABLE discovery_embedding_recovery_commands (
    recovery_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recovery_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_count INT UNSIGNED NOT NULL,
    recovered_count INT UNSIGNED NOT NULL,
    original_attempt_count_min SMALLINT UNSIGNED NULL,
    original_attempt_count_max SMALLINT UNSIGNED NULL,
    original_error_code VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recovery_id),
    UNIQUE KEY uq_discovery_embedding_recovery_key (recovery_key_hash),
    KEY idx_discovery_embedding_recovery_created (created_at),
    CONSTRAINT chk_discovery_embedding_recovery_hashes
        CHECK (
            recovery_key_hash REGEXP '^[0-9a-f]{64}$'
            AND command_hash REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_discovery_embedding_recovery_counts
        CHECK (
            recovered_count <= expected_count
            AND (
                (
                    outcome = 'RECOVERED'
                    AND expected_count > 0
                    AND recovered_count = expected_count
                    AND original_attempt_count_min IS NOT NULL
                    AND original_attempt_count_max IS NOT NULL
                    AND original_error_code = 'MAX_ATTEMPTS_EXHAUSTED'
                )
                OR (
                    outcome = 'COUNT_MISMATCH'
                    AND recovered_count = 0
                )
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
