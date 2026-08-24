CREATE TABLE case_enforcement_proposals (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    effective_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    expected_target_version BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    dry_run_validated_at DATETIME(6) NULL,
    dry_run_target_version BIGINT NULL,
    dry_run_result JSON NULL,
    resulting_enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_error_summary VARCHAR(500) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_case_enforcement_proposals_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT uk_case_enforcement_proposal_result UNIQUE (resulting_enforcement_action_id),
    CONSTRAINT chk_case_enforcement_proposal_target CHECK (target_type IN ('USER', 'BUSINESS', 'LISTING')),
    CONSTRAINT chk_case_enforcement_proposal_action CHECK (action_type IN ('RESTRICT', 'SUSPEND', 'BAN')),
    CONSTRAINT chk_case_enforcement_proposal_status CHECK (status IN (
        'DRAFT', 'VALIDATED', 'EXECUTED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT chk_case_enforcement_proposal_version CHECK (version >= 0),
    CONSTRAINT chk_case_enforcement_target_version CHECK (expected_target_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_case_enforcement_proposals_case
    ON case_enforcement_proposals (case_id, created_at, id);

CREATE TABLE case_enforcement_proposal_scopes (
    proposal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (proposal_id, scope),
    CONSTRAINT fk_case_enforcement_proposal_scopes_proposal FOREIGN KEY (proposal_id)
        REFERENCES case_enforcement_proposals (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE case_enforcement_links (
    case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proposal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enforcement_action_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    executed_at DATETIME(6) NOT NULL,
    executed_by_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (case_id, proposal_id),
    CONSTRAINT uk_case_enforcement_link_action UNIQUE (target_type, enforcement_action_id),
    CONSTRAINT fk_case_enforcement_links_case FOREIGN KEY (case_id)
        REFERENCES investigation_cases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_enforcement_links_proposal FOREIGN KEY (proposal_id)
        REFERENCES case_enforcement_proposals (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE investigation_case_events
    DROP CHECK chk_investigation_case_event_type,
    ADD CONSTRAINT chk_investigation_case_event_type CHECK (event_type IN (
        'CASE_CREATED', 'CASE_CLAIMED', 'CASE_RELEASED', 'INVESTIGATION_STARTED',
        'REPORT_LINKED', 'REPORT_UNLINKED', 'TARGET_LINKED', 'TARGET_UNLINKED',
        'NOTE_ADDED', 'EVIDENCE_ADDED', 'SEVERITY_CHANGED', 'STATUS_CHANGED',
        'MARKED_READY_FOR_ACTION', 'CLOSED_NO_ACTION',
        'ENFORCEMENT_PROPOSAL_CREATED', 'ENFORCEMENT_PROPOSAL_UPDATED',
        'ENFORCEMENT_PROPOSAL_CANCELLED', 'ENFORCEMENT_DRY_RUN_SUCCEEDED',
        'ENFORCEMENT_DRY_RUN_FAILED', 'ENFORCEMENT_EXECUTION_STARTED',
        'ENFORCEMENT_EXECUTED', 'ENFORCEMENT_EXECUTION_FAILED', 'ENFORCEMENT_RETRY',
        'CASE_CLOSED_ACTIONED'
    ));
