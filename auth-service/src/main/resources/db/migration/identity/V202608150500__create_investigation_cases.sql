INSERT INTO admin_permissions (id, description, reserved)
VALUES ('admin.report.investigate', 'Investigate marketplace reports in admin cases', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.report.investigate'),
    ('PLATFORM_ADMIN', 'admin.report.investigate'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.report.investigate');

ALTER TABLE report_events
    DROP CHECK chk_report_events_type,
    ADD CONSTRAINT chk_report_events_type CHECK (event_type IN (
        'REPORT_SUBMITTED', 'REPORT_CLAIMED', 'REPORT_RELEASED', 'REPORT_SEVERITY_CHANGED',
        'REPORT_DISMISSED', 'REPORT_MARKED_READY_FOR_INVESTIGATION',
        'REPORT_LINKED_TO_CASE', 'REPORT_UNLINKED_FROM_CASE'
    ));

CREATE TABLE investigation_cases (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    severity VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    primary_target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    primary_target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_primary_target_label VARCHAR(200) NOT NULL,
    assigned_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conclusion_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    conclusion_reason VARCHAR(1000) NULL,
    ready_for_action_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_investigation_case_status CHECK (status IN (
        'OPEN', 'UNDER_INVESTIGATION', 'READY_FOR_ACTION', 'CLOSED_NO_ACTION', 'CLOSED_ACTIONED'
    )),
    CONSTRAINT chk_investigation_case_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_investigation_case_target_type CHECK (primary_target_type IN ('USER', 'BUSINESS', 'LISTING')),
    CONSTRAINT chk_investigation_case_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_investigation_cases_inbox
    ON investigation_cases (status, assigned_admin_id, updated_at, id);
CREATE INDEX idx_investigation_cases_severity
    ON investigation_cases (severity, updated_at, id);
CREATE INDEX idx_investigation_cases_primary_target
    ON investigation_cases (primary_target_type, primary_target_id, updated_at, id);

CREATE TABLE investigation_case_reports (
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    linked_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (case_id, report_id),
    CONSTRAINT uk_investigation_case_report UNIQUE (report_id),
    CONSTRAINT fk_investigation_case_reports_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_investigation_case_reports_report FOREIGN KEY (report_id)
        REFERENCES reports (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE investigation_case_targets (
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_target_label VARCHAR(200) NOT NULL,
    relationship_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    linked_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (case_id, target_type, target_id),
    CONSTRAINT fk_investigation_case_targets_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT chk_investigation_case_link_target_type CHECK (target_type IN ('USER', 'BUSINESS', 'LISTING')),
    CONSTRAINT chk_investigation_case_relationship CHECK (relationship_type IN ('PRIMARY', 'RELATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_investigation_case_targets_target
    ON investigation_case_targets (target_type, target_id, case_id);

CREATE TABLE investigation_case_notes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body VARCHAR(4000) NOT NULL,
    author_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_display_name VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_investigation_case_note_retry UNIQUE (case_id, author_admin_id, idempotency_key),
    CONSTRAINT fk_investigation_case_notes_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_investigation_case_notes_time
    ON investigation_case_notes (case_id, created_at, id);

CREATE TABLE investigation_case_evidence (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    label VARCHAR(200) NOT NULL,
    snapshot_metadata JSON NOT NULL,
    added_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_investigation_case_evidence_reference UNIQUE (
        case_id, evidence_type, reference_type, reference_id
    ),
    CONSTRAINT fk_investigation_case_evidence_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT chk_investigation_case_evidence_type CHECK (evidence_type IN (
        'REPORT_SNAPSHOT', 'CURRENT_TARGET_SNAPSHOT', 'EXISTING_ENFORCEMENT'
    )),
    CONSTRAINT chk_investigation_case_reference_type CHECK (reference_type IN ('REPORT', 'CASE_TARGET'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_investigation_case_evidence_time
    ON investigation_case_evidence (case_id, created_at, id);

CREATE TABLE investigation_case_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_investigation_case_event_request UNIQUE (request_id),
    CONSTRAINT fk_investigation_case_events_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT chk_investigation_case_event_type CHECK (event_type IN (
        'CASE_CREATED', 'CASE_CLAIMED', 'CASE_RELEASED', 'INVESTIGATION_STARTED',
        'REPORT_LINKED', 'REPORT_UNLINKED', 'TARGET_LINKED', 'TARGET_UNLINKED',
        'NOTE_ADDED', 'EVIDENCE_ADDED', 'SEVERITY_CHANGED', 'STATUS_CHANGED',
        'MARKED_READY_FOR_ACTION', 'CLOSED_NO_ACTION'
    )),
    CONSTRAINT chk_investigation_case_event_actor CHECK (actor_type = 'PLATFORM_ADMIN'),
    CONSTRAINT chk_investigation_case_event_source CHECK (source = 'HUMAN_ADMIN')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_investigation_case_events_time
    ON investigation_case_events (case_id, occurred_at, event_id);
