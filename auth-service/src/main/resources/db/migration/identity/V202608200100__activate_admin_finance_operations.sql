INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.finance.read', 'Search and inspect payment administration records', FALSE),
    ('admin.refund.read', 'Search and inspect refund administration records', FALSE),
    ('admin.refund.execute', 'Preview and execute controlled refunds', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.finance.read'),
    ('SUPER_ADMIN', 'admin.refund.read'),
    ('SUPER_ADMIN', 'admin.refund.execute'),
    ('PLATFORM_ADMIN', 'admin.finance.read'),
    ('PLATFORM_ADMIN', 'admin.refund.read'),
    ('PLATFORM_ADMIN', 'admin.refund.execute'),
    ('SUPPORT_ADMIN', 'admin.finance.read'),
    ('SUPPORT_ADMIN', 'admin.refund.read'),
    ('AUDITOR', 'admin.finance.read'),
    ('AUDITOR', 'admin.refund.read');
