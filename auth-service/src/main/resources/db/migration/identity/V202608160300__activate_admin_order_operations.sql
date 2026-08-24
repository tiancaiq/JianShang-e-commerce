INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.order.read', 'Search and inspect marketplace business orders', FALSE),
    ('admin.order.cancel', 'Preview and request safe administrative order cancellation', FALSE),
    ('admin.order.manage', 'Reserved broader order operations permission', TRUE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.order.read'),
    ('SUPER_ADMIN', 'admin.order.cancel'),
    ('SUPER_ADMIN', 'admin.order.manage'),
    ('PLATFORM_ADMIN', 'admin.order.read'),
    ('PLATFORM_ADMIN', 'admin.order.cancel'),
    ('PLATFORM_ADMIN', 'admin.order.manage'),
    ('SUPPORT_ADMIN', 'admin.order.read'),
    ('AUDITOR', 'admin.order.read');
