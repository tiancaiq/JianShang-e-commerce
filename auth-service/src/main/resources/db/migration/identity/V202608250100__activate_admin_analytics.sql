INSERT INTO admin_permissions (id, description, reserved)
VALUES ('admin.analytics.read', 'Read bounded aggregate marketplace analytics', FALSE)
ON DUPLICATE KEY UPDATE description = VALUES(description), reserved = VALUES(reserved);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.analytics.read'),
    ('PLATFORM_ADMIN', 'admin.analytics.read'),
    ('OPERATIONS_ADMIN', 'admin.analytics.read'),
    ('AUDITOR', 'admin.analytics.read')
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);

CREATE INDEX idx_reports_created_status
    ON reports (created_at, status, id);
CREATE INDEX idx_report_events_type_time
    ON report_events (event_type, occurred_at, event_id);
CREATE INDEX idx_investigation_cases_created
    ON investigation_cases (created_at, id);
CREATE INDEX idx_investigation_cases_closed
    ON investigation_cases (closed_at, status, id);
CREATE INDEX idx_enforcement_actions_created_target
    ON enforcement_actions (created_at, target_type, action_type, id);
CREATE INDEX idx_appeals_submitted
    ON appeals (submitted_at, id);
CREATE INDEX idx_appeals_reviewed
    ON appeals (reviewed_at, status, id);
CREATE INDEX idx_support_tickets_created
    ON support_tickets (created_at, id);
CREATE INDEX idx_support_tickets_resolved
    ON support_tickets (resolved_at, id);
CREATE INDEX idx_support_messages_first_admin_response
    ON support_messages (ticket_id, author_type, created_at, id);
