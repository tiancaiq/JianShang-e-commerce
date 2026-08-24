INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.dispute.read', 'Search and inspect transaction disputes', FALSE),
    ('admin.dispute.assign', 'Claim and release transaction disputes', FALSE),
    ('admin.dispute.investigate', 'Investigate disputes and request participant information', FALSE),
    ('admin.dispute.resolve', 'Record non-financial dispute resolution decisions', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.dispute.read'),
    ('SUPER_ADMIN', 'admin.dispute.assign'),
    ('SUPER_ADMIN', 'admin.dispute.investigate'),
    ('SUPER_ADMIN', 'admin.dispute.resolve'),
    ('PLATFORM_ADMIN', 'admin.dispute.read'),
    ('PLATFORM_ADMIN', 'admin.dispute.assign'),
    ('PLATFORM_ADMIN', 'admin.dispute.investigate'),
    ('PLATFORM_ADMIN', 'admin.dispute.resolve'),
    ('SUPPORT_ADMIN', 'admin.dispute.read'),
    ('SUPPORT_ADMIN', 'admin.dispute.assign'),
    ('SUPPORT_ADMIN', 'admin.dispute.investigate'),
    ('AUDITOR', 'admin.dispute.read');
