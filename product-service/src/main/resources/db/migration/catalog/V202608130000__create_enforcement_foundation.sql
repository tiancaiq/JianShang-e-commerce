CREATE TABLE enforcement_actions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    effective_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_version_at_decision BIGINT NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_actor_display_name VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoked_by_actor_display_name VARCHAR(200) NULL,
    revoke_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoke_reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revoke_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revoke_command_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_enforcement_actions_parent FOREIGN KEY (parent_enforcement_action_id)
        REFERENCES enforcement_actions (id),
    CONSTRAINT fk_enforcement_actions_listing FOREIGN KEY (target_id)
        REFERENCES listings (id),
    CONSTRAINT chk_enforcement_actions_target CHECK (target_type = 'LISTING'),
    CONSTRAINT chk_enforcement_actions_action CHECK (action_type IN ('RESTRICT', 'SUSPEND', 'BAN')),
    CONSTRAINT chk_enforcement_actions_source CHECK (source IN ('HUMAN_ADMIN', 'SYSTEM', 'AI_AGENT')),
    CONSTRAINT chk_enforcement_actions_expiry CHECK (expires_at IS NULL OR expires_at > effective_at),
    CONSTRAINT chk_enforcement_actions_version CHECK (version >= 0),
    CONSTRAINT uk_enforcement_actions_request UNIQUE (request_id),
    CONSTRAINT uk_enforcement_actions_create_key UNIQUE (idempotency_key),
    CONSTRAINT uk_enforcement_actions_revoke_key UNIQUE (revoke_idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_enforcement_actions_target_active
    ON enforcement_actions (target_type, target_id, revoked_at, effective_at, expires_at);

CREATE TABLE enforcement_action_scopes (
    enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (enforcement_action_id, scope),
    CONSTRAINT fk_enforcement_action_scopes_action FOREIGN KEY (enforcement_action_id)
        REFERENCES enforcement_actions (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_enforcement_action_scopes_scope
    ON enforcement_action_scopes (scope, enforcement_action_id);

CREATE TABLE enforcement_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_enforcement_events_action FOREIGN KEY (enforcement_action_id)
        REFERENCES enforcement_actions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_enforcement_events_type CHECK (event_type IN ('CREATED', 'REVOKED')),
    CONSTRAINT chk_enforcement_events_state CHECK (new_state IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    CONSTRAINT uk_enforcement_events_request UNIQUE (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_enforcement_events_action_time
    ON enforcement_events (enforcement_action_id, occurred_at, event_id);

CREATE TABLE enforcement_command_idempotency (
    command_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (command_type, idempotency_key),
    CONSTRAINT fk_enforcement_idempotency_action FOREIGN KEY (enforcement_action_id)
        REFERENCES enforcement_actions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_enforcement_idempotency_command CHECK (command_type IN ('CREATE', 'REVOKE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
