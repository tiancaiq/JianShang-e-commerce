INSERT INTO roles (id, name, description)
VALUES ('USER_RESTRICTOR', 'USER_RESTRICTOR', 'Least-privileged user restriction administration')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('USER_RESTRICTOR', 'admin.dashboard.read'),
    ('USER_RESTRICTOR', 'admin.audit.read'),
    ('USER_RESTRICTOR', 'admin.user.read'),
    ('USER_RESTRICTOR', 'admin.user.restrict'),
    ('USER_RESTRICTOR', 'admin.user.reinstate');
