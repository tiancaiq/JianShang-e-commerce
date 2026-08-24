INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.support.read', 'Search and inspect support tickets', FALSE),
    ('admin.support.assign', 'Claim and release support tickets', FALSE),
    ('admin.support.respond', 'Respond, request information, add notes, and reprioritize support tickets', FALSE),
    ('admin.support.resolve', 'Resolve assigned support tickets', FALSE),
    ('admin.support.escalate', 'Link assigned support tickets to validated specialized workflows', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.support.read'),
    ('SUPER_ADMIN', 'admin.support.assign'),
    ('SUPER_ADMIN', 'admin.support.respond'),
    ('SUPER_ADMIN', 'admin.support.resolve'),
    ('SUPER_ADMIN', 'admin.support.escalate'),
    ('PLATFORM_ADMIN', 'admin.support.read'),
    ('PLATFORM_ADMIN', 'admin.support.assign'),
    ('PLATFORM_ADMIN', 'admin.support.respond'),
    ('PLATFORM_ADMIN', 'admin.support.resolve'),
    ('PLATFORM_ADMIN', 'admin.support.escalate'),
    ('SUPPORT_ADMIN', 'admin.support.read'),
    ('SUPPORT_ADMIN', 'admin.support.assign'),
    ('SUPPORT_ADMIN', 'admin.support.respond'),
    ('SUPPORT_ADMIN', 'admin.support.resolve'),
    ('SUPPORT_ADMIN', 'admin.support.escalate'),
    ('AUDITOR', 'admin.support.read');

CREATE TABLE support_tickets (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject VARCHAR(160) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolved_at DATETIME(6) NULL,
    resolution_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_reason VARCHAR(1000) NULL,
    duplicate_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_support_ticket_requester FOREIGN KEY (requester_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_support_ticket_category CHECK (category IN (
        'ACCOUNT_HELP','ORDER_HELP','PAYMENT_HELP','REFUND_HELP','SELLER_HELP',
        'BUSINESS_VERIFICATION','LISTING_HELP','TECHNICAL_ISSUE','OTHER'
    )),
    CONSTRAINT chk_support_ticket_status CHECK (status IN (
        'OPEN','ASSIGNED','UNDER_REVIEW','WAITING_FOR_USER','RESOLVED'
    )),
    CONSTRAINT chk_support_ticket_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT')),
    CONSTRAINT chk_support_ticket_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_ticket_requester ON support_tickets (requester_user_id, created_at DESC, id DESC);
CREATE INDEX idx_support_ticket_inbox ON support_tickets (status, assigned_admin_id, priority, updated_at DESC, id DESC);
CREATE INDEX idx_support_ticket_category ON support_tickets (category, updated_at DESC, id DESC);
CREATE INDEX idx_support_ticket_duplicate ON support_tickets (requester_user_id, duplicate_fingerprint, created_at DESC);

CREATE TABLE support_messages (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body VARCHAR(4000) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_message_retry UNIQUE (ticket_id, author_user_id, idempotency_key),
    CONSTRAINT fk_support_message_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE RESTRICT,
    CONSTRAINT chk_support_message_author CHECK (author_type IN ('REQUESTER','SUPPORT_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_message_time ON support_messages (ticket_id, created_at, id);

CREATE TABLE support_internal_notes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_display_name VARCHAR(200) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_note_retry UNIQUE (ticket_id, author_admin_id, idempotency_key),
    CONSTRAINT fk_support_note_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_note_time ON support_internal_notes (ticket_id, created_at, id);

CREATE TABLE support_ticket_links (
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    relation_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_label VARCHAR(200) NOT NULL,
    admin_path VARCHAR(240) NULL,
    linked_by_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    linked_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (ticket_id, target_type, target_id),
    CONSTRAINT fk_support_link_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE RESTRICT,
    CONSTRAINT chk_support_link_target CHECK (target_type IN (
        'USER','ORDER','BUSINESS','LISTING','PAYMENT','REFUND','DISPUTE',
        'TRUST_AND_SAFETY_REPORT','INVESTIGATION_CASE'
    )),
    CONSTRAINT chk_support_link_relation CHECK (relation_type IN ('SUBMITTED_WITH','RELATED','ESCALATION')),
    CONSTRAINT chk_support_link_actor CHECK (linked_by_type IN ('REQUESTER','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_link_target ON support_ticket_links (target_type, target_id, ticket_id);

CREATE TABLE support_escalations (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_escalation_retry UNIQUE (ticket_id, created_by_admin_id, idempotency_key),
    CONSTRAINT fk_support_escalation_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE RESTRICT,
    CONSTRAINT chk_support_escalation_destination CHECK (destination_type IN (
        'DISPUTE','TRUST_AND_SAFETY','FINANCE','ORDER_OPERATIONS'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_escalation_time ON support_escalations (ticket_id, created_at, id);

CREATE TABLE support_ticket_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL,
    source VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_support_event_request UNIQUE (ticket_id, request_id),
    CONSTRAINT fk_support_event_ticket FOREIGN KEY (ticket_id)
        REFERENCES support_tickets (id) ON DELETE RESTRICT,
    CONSTRAINT chk_support_event_type CHECK (event_type IN (
        'SUPPORT_TICKET_CREATED','SUPPORT_TICKET_CLAIMED','SUPPORT_TICKET_RELEASED',
        'SUPPORT_MESSAGE_SENT','USER_MESSAGE_RECEIVED','INFORMATION_REQUESTED',
        'PRIORITY_CHANGED','INTERNAL_NOTE_ADDED','ENTITY_LINKED','ENTITY_UNLINKED',
        'ESCALATION_CREATED','SUPPORT_TICKET_RESOLVED'
    )),
    CONSTRAINT chk_support_event_actor CHECK (actor_type IN ('REQUESTER','SUPPORT_ADMIN')),
    CONSTRAINT chk_support_event_source CHECK (source IN ('USER_PORTAL','HUMAN_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_event_time ON support_ticket_events (ticket_id, occurred_at, event_id);

CREATE TABLE support_command_idempotency (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_support_command_retry UNIQUE (actor_user_id, operation, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_support_command_expiry ON support_command_idempotency (expires_at, id);
