INSERT INTO roles (id, name, description)
VALUES ('CATALOG_ADMIN', 'CATALOG_ADMIN', 'Catalog and marketplace policy administration')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.catalog.read', 'Inspect catalog configuration and history', FALSE),
    ('admin.catalog.category.manage', 'Create, edit, move, enable, disable, and deprecate categories', FALSE),
    ('admin.catalog.attribute.manage', 'Create and manage category attributes and options', FALSE),
    ('admin.catalog.policy.manage', 'Publish listing eligibility, validation, and seller-guidance policy', FALSE);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('SUPER_ADMIN', 'admin.catalog.read'),
    ('SUPER_ADMIN', 'admin.catalog.category.manage'),
    ('SUPER_ADMIN', 'admin.catalog.attribute.manage'),
    ('SUPER_ADMIN', 'admin.catalog.policy.manage'),
    ('PLATFORM_ADMIN', 'admin.catalog.read'),
    ('PLATFORM_ADMIN', 'admin.catalog.category.manage'),
    ('PLATFORM_ADMIN', 'admin.catalog.attribute.manage'),
    ('PLATFORM_ADMIN', 'admin.catalog.policy.manage'),
    ('CATALOG_ADMIN', 'admin.dashboard.read'),
    ('CATALOG_ADMIN', 'admin.audit.read'),
    ('CATALOG_ADMIN', 'admin.catalog.read'),
    ('CATALOG_ADMIN', 'admin.catalog.category.manage'),
    ('CATALOG_ADMIN', 'admin.catalog.attribute.manage'),
    ('CATALOG_ADMIN', 'admin.catalog.policy.manage'),
    ('AUDITOR', 'admin.catalog.read');
