CREATE INDEX idx_businesses_created_id ON businesses (created_at, id);
CREATE INDEX idx_businesses_updated_id ON businesses (updated_at, id);

UPDATE admin_permissions
SET reserved = FALSE,
    description = CASE id
        WHEN 'admin.business.read' THEN 'Read active and historical business administration records'
        WHEN 'admin.business.restrict' THEN 'Restrict selected operational business capabilities'
        WHEN 'admin.business.suspend' THEN 'Suspend selected operational business capabilities'
        WHEN 'admin.business.ban' THEN 'Apply the marketplace business ban policy profile'
        WHEN 'admin.business.reinstate' THEN 'Revoke one business enforcement action'
        ELSE description
    END
WHERE id IN (
    'admin.business.read',
    'admin.business.restrict',
    'admin.business.suspend',
    'admin.business.ban',
    'admin.business.reinstate'
);

INSERT INTO roles (id, name, description)
VALUES ('BUSINESS_RESTRICTOR', 'BUSINESS_RESTRICTOR', 'Least-privileged business restriction administration')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('BUSINESS_RESTRICTOR', 'admin.dashboard.read'),
    ('BUSINESS_RESTRICTOR', 'admin.audit.read'),
    ('BUSINESS_RESTRICTOR', 'admin.business.read'),
    ('BUSINESS_RESTRICTOR', 'admin.business.restrict'),
    ('BUSINESS_RESTRICTOR', 'admin.business.reinstate');
