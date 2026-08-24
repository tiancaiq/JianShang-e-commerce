UPDATE admin_permissions
SET reserved = FALSE,
    description = CASE id
        WHEN 'admin.report.read' THEN 'Read marketplace reports and report audit history'
        WHEN 'admin.report.assign' THEN 'Claim and release marketplace reports'
        WHEN 'admin.report.resolve' THEN 'Triage and resolve marketplace reports'
        ELSE description
    END
WHERE id IN ('admin.report.read', 'admin.report.assign', 'admin.report.resolve');

CREATE TABLE reports (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_target_label VARCHAR(200) NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(2000) NULL,
    severity VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_snapshot JSON NOT NULL,
    evidence_metadata JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    triaged_at DATETIME(6) NULL,
    triaged_by CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    disposition_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    disposition_reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_reports_target_type CHECK (target_type IN ('USER', 'BUSINESS', 'LISTING', 'ORDER', 'MESSAGE', 'REVIEW')),
    CONSTRAINT chk_reports_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_reports_status CHECK (status IN ('SUBMITTED', 'UNDER_TRIAGE', 'DISMISSED', 'READY_FOR_INVESTIGATION', 'LINKED_TO_CASE', 'ACTIONED', 'WITHDRAWN')),
    CONSTRAINT chk_reports_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_reports_inbox ON reports (status, assigned_admin_id, created_at, id);
CREATE INDEX idx_reports_target ON reports (target_type, target_id, created_at, id);
CREATE INDEX idx_reports_reason_severity ON reports (reason_code, severity, created_at, id);
CREATE INDEX idx_reports_reporter_created ON reports (reporter_user_id, created_at, id);

CREATE TABLE report_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NOT NULL,
    source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_report_events_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE RESTRICT,
    CONSTRAINT uk_report_events_request UNIQUE (request_id),
    CONSTRAINT chk_report_events_type CHECK (event_type IN ('REPORT_SUBMITTED', 'REPORT_CLAIMED', 'REPORT_RELEASED', 'REPORT_SEVERITY_CHANGED', 'REPORT_DISMISSED', 'REPORT_MARKED_READY_FOR_INVESTIGATION')),
    CONSTRAINT chk_report_events_actor CHECK (actor_type IN ('MARKETPLACE_USER', 'PLATFORM_ADMIN')),
    CONSTRAINT chk_report_events_source CHECK (source IN ('MARKETPLACE', 'HUMAN_ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_report_events_report_time ON report_events (report_id, occurred_at, event_id);

CREATE TABLE report_submission_dedup (
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reporter_user_id, target_type, target_id, reason_code),
    CONSTRAINT fk_report_submission_dedup_report FOREIGN KEY (report_id) REFERENCES reports (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_report_submission_dedup_expiry ON report_submission_dedup (expires_at);

CREATE TABLE report_rate_limit_buckets (
    reporter_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    accepted_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reporter_user_id, bucket_type, bucket_start),
    CONSTRAINT chk_report_rate_bucket_type CHECK (bucket_type IN ('HOUR', 'DAY')),
    CONSTRAINT chk_report_rate_bucket_count CHECK (accepted_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_report_rate_limit_expiry ON report_rate_limit_buckets (expires_at);
