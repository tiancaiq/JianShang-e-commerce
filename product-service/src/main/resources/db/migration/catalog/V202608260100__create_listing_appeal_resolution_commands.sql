CREATE TABLE listing_appeal_resolution_commands (
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appeal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    replacement_enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_enforcement_version BIGINT NOT NULL,
    expected_target_version BIGINT NOT NULL,
    executed_by_actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    executed_by_actor_display_name VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (idempotency_key),
    CONSTRAINT uk_listing_appeal_resolution_appeal UNIQUE (appeal_id),
    CONSTRAINT fk_listing_appeal_resolution_original FOREIGN KEY (original_enforcement_action_id)
        REFERENCES enforcement_actions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_listing_appeal_resolution_replacement FOREIGN KEY (replacement_enforcement_action_id)
        REFERENCES enforcement_actions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_listing_appeal_resolution_outcome CHECK (outcome IN ('REVOKED', 'MODIFIED')),
    CONSTRAINT chk_listing_appeal_resolution_enforcement_version CHECK (expected_enforcement_version >= 0),
    CONSTRAINT chk_listing_appeal_resolution_target_version CHECK (expected_target_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_listing_appeal_resolution_original
    ON listing_appeal_resolution_commands (original_enforcement_action_id, completed_at);
