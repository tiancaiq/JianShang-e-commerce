INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.appeal.read', 'Read marketplace appeals and appeal audit history', FALSE),
    ('admin.appeal.assign', 'Claim and release marketplace appeals', FALSE),
    ('admin.appeal.review', 'Review marketplace appeals and record recommendations', FALSE),
    ('admin.appeal.resolve', 'Execute approved appeal outcomes', TRUE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.appeal.read'),
    ('SUPER_ADMIN', 'admin.appeal.assign'),
    ('SUPER_ADMIN', 'admin.appeal.review'),
    ('SUPER_ADMIN', 'admin.appeal.resolve'),
    ('PLATFORM_ADMIN', 'admin.appeal.read'),
    ('PLATFORM_ADMIN', 'admin.appeal.assign'),
    ('PLATFORM_ADMIN', 'admin.appeal.review'),
    ('PLATFORM_ADMIN', 'admin.appeal.resolve'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.appeal.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.appeal.assign'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.appeal.review'),
    ('AUDITOR', 'admin.appeal.read');

CREATE TABLE appeals (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_target_label VARCHAR(200) NOT NULL,
    appellant_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appellant_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    explanation VARCHAR(2000) NULL,
    safe_evidence_references JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    assigned_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    review_started_at DATETIME(6) NULL,
    reviewed_at DATETIME(6) NULL,
    review_outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    review_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    review_reason VARCHAR(2000) NULL,
    replacement_action_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    replacement_expires_at DATETIME(6) NULL,
    replacement_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    replacement_reason VARCHAR(1000) NULL,
    replacement_expected_target_version BIGINT NULL,
    original_case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_enforcement_version BIGINT NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_appeals_enforcement_action UNIQUE (enforcement_action_id),
    CONSTRAINT chk_appeals_target_type CHECK (target_type IN ('USER', 'BUSINESS', 'LISTING')),
    CONSTRAINT chk_appeals_appellant_type CHECK (appellant_type IN ('USER', 'BUSINESS_REPRESENTATIVE', 'LISTING_OWNER')),
    CONSTRAINT chk_appeals_status CHECK (status IN (
        'SUBMITTED', 'UNDER_REVIEW', 'UPHOLD_RECOMMENDED', 'MODIFY_RECOMMENDED', 'REVOKE_RECOMMENDED'
    )),
    CONSTRAINT chk_appeals_reason CHECK (reason_code IN (
        'DECISION_INCORRECT', 'NEW_EVIDENCE', 'ACCOUNT_COMPROMISED', 'MISIDENTIFICATION',
        'ACTION_TOO_SEVERE', 'POLICY_MISAPPLIED', 'OTHER'
    )),
    CONSTRAINT chk_appeals_version CHECK (version >= 0),
    CONSTRAINT chk_appeals_enforcement_version CHECK (original_enforcement_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_appeals_inbox ON appeals (status, assigned_admin_id, submitted_at, id);
CREATE INDEX idx_appeals_target ON appeals (target_type, target_id, submitted_at, id);
CREATE INDEX idx_appeals_appellant ON appeals (appellant_user_id, submitted_at, id);
CREATE INDEX idx_appeals_case ON appeals (original_case_id, submitted_at, id);

CREATE TABLE appeal_replacement_scopes (
    appeal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (appeal_id, scope),
    CONSTRAINT fk_appeal_replacement_scopes_appeal FOREIGN KEY (appeal_id)
        REFERENCES appeals (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appeal_review_notes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appeal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body VARCHAR(4000) NOT NULL,
    author_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_display_name VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_appeal_note_retry UNIQUE (appeal_id, author_admin_id, idempotency_key),
    CONSTRAINT fk_appeal_review_notes_appeal FOREIGN KEY (appeal_id)
        REFERENCES appeals (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_appeal_review_notes_time ON appeal_review_notes (appeal_id, created_at, id);

CREATE TABLE appeal_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    appeal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(2000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_appeal_events_request UNIQUE (request_id),
    CONSTRAINT fk_appeal_events_appeal FOREIGN KEY (appeal_id) REFERENCES appeals (id) ON DELETE RESTRICT,
    CONSTRAINT chk_appeal_events_type CHECK (event_type IN (
        'APPEAL_SUBMITTED', 'APPEAL_CLAIMED', 'APPEAL_RELEASED', 'APPEAL_REVIEW_STARTED',
        'APPEAL_NOTE_ADDED', 'APPEAL_UPHOLD_RECOMMENDED', 'APPEAL_MODIFY_RECOMMENDED',
        'APPEAL_REVOKE_RECOMMENDED'
    )),
    CONSTRAINT chk_appeal_events_actor CHECK (actor_type IN ('MARKETPLACE_USER', 'PLATFORM_ADMIN')),
    CONSTRAINT chk_appeal_events_source CHECK (source IN ('MARKETPLACE', 'HUMAN_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_appeal_events_time ON appeal_events (appeal_id, occurred_at, event_id);
