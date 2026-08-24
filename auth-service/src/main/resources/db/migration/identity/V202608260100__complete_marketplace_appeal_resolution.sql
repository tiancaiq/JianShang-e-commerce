UPDATE admin_permissions
SET description = 'Execute recommended marketplace appeal outcomes', reserved = FALSE
WHERE id = 'admin.appeal.resolve';

ALTER TABLE appeals
    DROP CHECK chk_appeals_status;

ALTER TABLE appeals
    ADD COLUMN resolved_at DATETIME(6) NULL AFTER reviewed_at,
    ADD COLUMN resolved_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER resolved_at,
    ADD COLUMN resolved_by_admin_display_name VARCHAR(200) NULL AFTER resolved_by_admin_id,
    ADD COLUMN resolution_summary VARCHAR(500) NULL AFTER resolved_by_admin_display_name,
    ADD COLUMN resolution_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER resolution_summary,
    ADD COLUMN resolution_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER resolution_idempotency_key,
    ADD COLUMN replacement_enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER resolution_request_hash,
    ADD COLUMN resolution_original_enforcement_version BIGINT NULL AFTER replacement_enforcement_action_id,
    ADD COLUMN resolution_target_version BIGINT NULL AFTER resolution_original_enforcement_version,
    ADD CONSTRAINT chk_appeals_status CHECK (status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'UPHOLD_RECOMMENDED', 'MODIFY_RECOMMENDED', 'REVOKE_RECOMMENDED',
        'UPHELD', 'MODIFIED', 'REVOKED'
    )),
    ADD CONSTRAINT chk_appeals_resolution_versions CHECK (
        (resolution_original_enforcement_version IS NULL OR resolution_original_enforcement_version >= 0)
        AND (resolution_target_version IS NULL OR resolution_target_version >= 0)
    ),
    ADD CONSTRAINT uk_appeals_resolution_idempotency UNIQUE (resolution_idempotency_key);

CREATE INDEX idx_appeals_resolved
    ON appeals (resolved_at, status, id);

ALTER TABLE appeal_events
    DROP CHECK chk_appeal_events_type;

ALTER TABLE appeal_events
    ADD CONSTRAINT chk_appeal_events_type CHECK (event_type IN (
        'APPEAL_SUBMITTED', 'APPEAL_CLAIMED', 'APPEAL_RELEASED', 'APPEAL_REVIEW_STARTED',
        'APPEAL_NOTE_ADDED', 'APPEAL_UPHOLD_RECOMMENDED', 'APPEAL_MODIFY_RECOMMENDED',
        'APPEAL_REVOKE_RECOMMENDED', 'APPEAL_UPHELD', 'APPEAL_ENFORCEMENT_REVOKED',
        'APPEAL_ENFORCEMENT_MODIFIED', 'APPEAL_RESOLUTION_FAILED'
    ));

CREATE TABLE appeal_resolution_previews (
    preview_token CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appeal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    executor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_confirmation_token VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (preview_token),
    CONSTRAINT fk_appeal_resolution_previews_appeal FOREIGN KEY (appeal_id)
        REFERENCES appeals (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_appeal_resolution_previews_expiry
    ON appeal_resolution_previews (appeal_id, executor_admin_id, expires_at, preview_token);
