INSERT INTO roles (id, name, description)
VALUES ('GOVERNANCE_ADMIN', 'GOVERNANCE_ADMIN', 'Admin role and sensitive-action governance')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.governance.read', 'Read admin governance summaries', FALSE),
    ('admin.governance.roles.read', 'Read admin roles and assignments', FALSE),
    ('admin.governance.roles.manage', 'Grant and revoke assignable admin roles', FALSE),
    ('admin.governance.elevation.manage', 'Grant and revoke temporary admin elevation', FALSE),
    ('admin.governance.approval.read', 'Read sensitive-action approvals', FALSE),
    ('admin.governance.approval.request', 'Request a governed sensitive action', FALSE),
    ('admin.governance.approval.review', 'Approve or reject governed sensitive actions', FALSE),
    ('admin.governance.audit.read', 'Read governance audit history', FALSE),
    ('admin.governance.policy.manage', 'Reserved sensitive-action policy management', TRUE)
ON DUPLICATE KEY UPDATE description = VALUES(description), reserved = VALUES(reserved);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.governance.read'),
    ('SUPER_ADMIN', 'admin.governance.roles.read'),
    ('SUPER_ADMIN', 'admin.governance.roles.manage'),
    ('SUPER_ADMIN', 'admin.governance.elevation.manage'),
    ('SUPER_ADMIN', 'admin.governance.approval.read'),
    ('SUPER_ADMIN', 'admin.governance.approval.request'),
    ('SUPER_ADMIN', 'admin.governance.approval.review'),
    ('SUPER_ADMIN', 'admin.governance.audit.read'),
    ('PLATFORM_ADMIN', 'admin.governance.read'),
    ('PLATFORM_ADMIN', 'admin.governance.roles.read'),
    ('PLATFORM_ADMIN', 'admin.governance.approval.read'),
    ('PLATFORM_ADMIN', 'admin.governance.approval.request'),
    ('PLATFORM_ADMIN', 'admin.governance.approval.review'),
    ('PLATFORM_ADMIN', 'admin.governance.audit.read'),
    ('GOVERNANCE_ADMIN', 'admin.dashboard.read'),
    ('GOVERNANCE_ADMIN', 'admin.audit.read'),
    ('GOVERNANCE_ADMIN', 'admin.governance.read'),
    ('GOVERNANCE_ADMIN', 'admin.governance.roles.read'),
    ('GOVERNANCE_ADMIN', 'admin.governance.roles.manage'),
    ('GOVERNANCE_ADMIN', 'admin.governance.elevation.manage'),
    ('GOVERNANCE_ADMIN', 'admin.governance.approval.read'),
    ('GOVERNANCE_ADMIN', 'admin.governance.approval.request'),
    ('GOVERNANCE_ADMIN', 'admin.governance.approval.review'),
    ('GOVERNANCE_ADMIN', 'admin.governance.audit.read'),
    ('CATALOG_ADMIN', 'admin.governance.approval.read'),
    ('CATALOG_ADMIN', 'admin.governance.approval.request'),
    ('AUDITOR', 'admin.governance.read'),
    ('AUDITOR', 'admin.governance.roles.read'),
    ('AUDITOR', 'admin.governance.approval.read'),
    ('AUDITOR', 'admin.governance.audit.read')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

CREATE TABLE admin_governance_state (
    id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO admin_governance_state (id, version, updated_at)
VALUES ('ADMIN_AUTHORITY', 0, CURRENT_TIMESTAMP(6));

CREATE TABLE admin_role_assignments (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    admin_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    granted_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revocation_reason VARCHAR(1000) NULL,
    request_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_admin_role_assignment_user FOREIGN KEY (admin_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_role_assignment_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_role_assignment_granter FOREIGN KEY (granted_by_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_role_assignment_revoker FOREIGN KEY (revoked_by_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uk_admin_role_assignment_request UNIQUE (granted_by_admin_id, request_idempotency_key),
    CONSTRAINT chk_admin_role_assignment_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT chk_admin_role_assignment_window CHECK (expires_at IS NULL OR expires_at > effective_at),
    CONSTRAINT chk_admin_role_assignment_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by_admin_id IS NULL AND revocation_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by_admin_id IS NOT NULL AND revocation_reason IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_admin_role_assignment_effective
    ON admin_role_assignments (admin_user_id, status, effective_at, expires_at);
CREATE INDEX idx_admin_role_assignment_role_effective
    ON admin_role_assignments (role_id, status, effective_at, expires_at);
CREATE INDEX idx_admin_role_assignment_recent
    ON admin_role_assignments (admin_user_id, created_at DESC, id DESC);

INSERT INTO admin_role_assignments (
    id, admin_user_id, role_id, status, effective_at, expires_at,
    granted_by_admin_id, reason, revoked_at, revoked_by_admin_id,
    revocation_reason, request_idempotency_key, request_hash,
    correlation_id, version, created_at, updated_at
)
SELECT CONCAT('0', SUBSTRING(UPPER(SHA2(CONCAT(ur.user_id, '|', ur.role_id), 256)), 1, 25)),
       ur.user_id, ur.role_id, 'ACTIVE', ur.granted_at, NULL,
       ur.granted_by, 'Migrated from the existing application admin role assignment.',
       NULL, NULL, NULL, NULL, NULL, 'admin-governance-migration',
       ur.version, ur.granted_at, ur.updated_at
FROM user_roles ur
WHERE ur.role_id IN (
    'PLATFORM_ADMIN', 'SUPER_ADMIN', 'TRUST_AND_SAFETY_ADMIN',
    'BUSINESS_REVIEWER', 'LISTING_MODERATOR', 'SUPPORT_ADMIN',
    'USER_RESTRICTOR', 'BUSINESS_RESTRICTOR', 'CATALOG_ADMIN',
    'OPERATIONS_ADMIN', 'AUDITOR', 'AI_ADMIN_AGENT', 'GOVERNANCE_ADMIN'
)
ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE sensitive_action_policies (
    action_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    required_approvals INT NOT NULL,
    requester_may_approve BOOLEAN NOT NULL,
    approval_expires_after_minutes INT NOT NULL,
    required_requester_permission VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    required_approver_permission VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    threshold_value DECIMAL(19,4) NULL,
    threshold_currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    enabled BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (action_type),
    CONSTRAINT chk_sensitive_action_risk CHECK (risk_level IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_sensitive_action_mode CHECK (approval_mode IN ('NONE','SINGLE_APPROVAL','DUAL_APPROVAL')),
    CONSTRAINT chk_sensitive_action_approvals CHECK (
        (approval_mode = 'NONE' AND required_approvals = 0)
        OR (approval_mode = 'SINGLE_APPROVAL' AND required_approvals = 1)
        OR (approval_mode = 'DUAL_APPROVAL' AND required_approvals = 2)
    ),
    CONSTRAINT chk_sensitive_action_expiry CHECK (approval_expires_after_minutes BETWEEN 5 AND 10080)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sensitive_action_policies (
    action_type, risk_level, approval_mode, required_approvals,
    requester_may_approve, approval_expires_after_minutes,
    required_requester_permission, required_approver_permission,
    threshold_value, threshold_currency, enabled, version, updated_at
)
VALUES
    ('ADMIN_ROLE_GRANT_SUPER_ADMIN', 'CRITICAL', 'DUAL_APPROVAL', 2, FALSE, 1440,
     'admin.governance.roles.manage', 'admin.governance.approval.review', NULL, NULL, TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('ADMIN_ROLE_REVOKE_SUPER_ADMIN', 'CRITICAL', 'DUAL_APPROVAL', 2, FALSE, 1440,
     'admin.governance.roles.manage', 'admin.governance.approval.review', NULL, NULL, TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('LARGE_REFUND', 'HIGH', 'SINGLE_APPROVAL', 1, FALSE, 240,
     'admin.refund.execute', 'admin.refund.execute', 1000.0000, 'USD', TRUE, 0, CURRENT_TIMESTAMP(6)),
    ('HIGH_IMPACT_CATALOG_CATEGORY_DISABLE', 'HIGH', 'SINGLE_APPROVAL', 1, FALSE, 480,
     'admin.catalog.policy.manage', 'admin.catalog.policy.manage', 1000.0000, NULL, TRUE, 0, CURRENT_TIMESTAMP(6));

CREATE TABLE admin_approval_requests (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_action_summary VARCHAR(1000) NOT NULL,
    safe_action_payload JSON NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    risk_level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    required_approvals INT NOT NULL,
    policy_version BIGINT NOT NULL,
    expected_target_version BIGINT NULL,
    request_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    executor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_failure_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_failure_summary VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    executed_at DATETIME(6) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_approval_request UNIQUE (requester_admin_id, action_type, request_idempotency_key),
    CONSTRAINT fk_admin_approval_policy FOREIGN KEY (action_type) REFERENCES sensitive_action_policies (action_type) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_approval_requester FOREIGN KEY (requester_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_approval_executor FOREIGN KEY (executor_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_admin_approval_status CHECK (status IN (
        'PENDING','APPROVED','REJECTED','EXPIRED','EXECUTING','EXECUTED',
        'FAILED','CANCELLED','INVALIDATED'
    )),
    CONSTRAINT chk_admin_approval_window CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_admin_approval_inbox ON admin_approval_requests (status, created_at DESC, id DESC);
CREATE INDEX idx_admin_approval_action ON admin_approval_requests (action_type, status, created_at DESC);
CREATE INDEX idx_admin_approval_requester ON admin_approval_requests (requester_admin_id, created_at DESC);
CREATE INDEX idx_admin_approval_expiry ON admin_approval_requests (status, expires_at);

CREATE TABLE admin_approval_decisions (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approver_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_approval_decision_actor UNIQUE (approval_request_id, approver_admin_id),
    CONSTRAINT uk_admin_approval_decision_key UNIQUE (approver_admin_id, idempotency_key),
    CONSTRAINT fk_admin_approval_decision_request FOREIGN KEY (approval_request_id)
        REFERENCES admin_approval_requests (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_approval_decision_actor FOREIGN KEY (approver_admin_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_admin_approval_decision CHECK (decision IN ('APPROVE','REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_admin_approval_decision_request
    ON admin_approval_decisions (approval_request_id, created_at, id);

CREATE TABLE admin_governance_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(72) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    approval_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_admin_governance_event_actor FOREIGN KEY (actor_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_governance_event_subject FOREIGN KEY (subject_admin_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_admin_governance_event_approval FOREIGN KEY (approval_request_id)
        REFERENCES admin_approval_requests (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_admin_governance_event_recent ON admin_governance_events (created_at DESC, id DESC);
CREATE INDEX idx_admin_governance_event_subject ON admin_governance_events (subject_admin_id, created_at DESC);
CREATE INDEX idx_admin_governance_event_approval ON admin_governance_events (approval_request_id, created_at, id);
