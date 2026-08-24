INSERT INTO roles (id, name, description)
VALUES ('OPERATIONS_ADMIN', 'OPERATIONS_ADMIN', 'Marketplace runtime operations and bounded recovery')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.system.read', 'Inspect safe marketplace runtime signals', FALSE),
    ('admin.system.retry', 'Request bounded retries through existing workers', FALSE),
    ('admin.search.maintenance', 'Request bounded listing search recovery', FALSE),
    ('admin.feature.read', 'Inspect curated non-secret feature state', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.system.read'),
    ('SUPER_ADMIN', 'admin.system.retry'),
    ('SUPER_ADMIN', 'admin.search.maintenance'),
    ('SUPER_ADMIN', 'admin.feature.read'),
    ('PLATFORM_ADMIN', 'admin.system.read'),
    ('PLATFORM_ADMIN', 'admin.system.retry'),
    ('PLATFORM_ADMIN', 'admin.search.maintenance'),
    ('PLATFORM_ADMIN', 'admin.feature.read'),
    ('OPERATIONS_ADMIN', 'admin.dashboard.read'),
    ('OPERATIONS_ADMIN', 'admin.audit.read'),
    ('OPERATIONS_ADMIN', 'admin.system.read'),
    ('OPERATIONS_ADMIN', 'admin.system.retry'),
    ('OPERATIONS_ADMIN', 'admin.search.maintenance'),
    ('OPERATIONS_ADMIN', 'admin.feature.read'),
    ('AUDITOR', 'admin.system.read'),
    ('AUDITOR', 'admin.feature.read');

CREATE TABLE system_operation_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_service VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_system_operation_command UNIQUE (actor_user_id, command_type, idempotency_key),
    CONSTRAINT fk_system_operation_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_system_operation_state CHECK (state IN ('IN_PROGRESS','COMPLETED','REJECTED')),
    CONSTRAINT chk_system_operation_result CHECK (
        result IS NULL OR result IN ('ACCEPTED','REJECTED','ALREADY_COMPLETED','NOT_RETRYABLE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_system_operation_recent
    ON system_operation_commands (created_at DESC, id DESC);
CREATE INDEX idx_system_operation_target
    ON system_operation_commands (target_type, target_id, created_at DESC);

CREATE TABLE system_operation_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_system_operation_event_request UNIQUE (command_id, request_id),
    CONSTRAINT fk_system_operation_event_command FOREIGN KEY (command_id)
        REFERENCES system_operation_commands (id) ON DELETE RESTRICT,
    CONSTRAINT fk_system_operation_event_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_system_operation_event_time
    ON system_operation_events (created_at DESC, event_id DESC);
