CREATE TABLE admin_permissions (
    id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(255) NOT NULL,
    reserved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_role_permissions (
    role_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    permission_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_admin_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_admin_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES admin_permissions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE user_roles
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER granted_at,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) AFTER version;

INSERT INTO roles (id, name, description)
VALUES
    ('SUPER_ADMIN', 'SUPER_ADMIN', 'Full platform administration access'),
    ('TRUST_AND_SAFETY_ADMIN', 'TRUST_AND_SAFETY_ADMIN', 'Trust and safety administration'),
    ('BUSINESS_REVIEWER', 'BUSINESS_REVIEWER', 'Business application review'),
    ('LISTING_MODERATOR', 'LISTING_MODERATOR', 'Listing moderation'),
    ('SUPPORT_ADMIN', 'SUPPORT_ADMIN', 'Read-only support administration'),
    ('AUDITOR', 'AUDITOR', 'Read-only administration audit access'),
    ('AI_ADMIN_AGENT', 'AI_ADMIN_AGENT', 'Reserved inactive AI administration role')
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description);

INSERT INTO admin_permissions (id, description, reserved)
VALUES
    ('admin.dashboard.read', 'Read the admin dashboard', FALSE),
    ('admin.audit.read', 'Read admin workflow audit history', FALSE),
    ('admin.business.application.read', 'Read business applications', FALSE),
    ('admin.business.application.decide', 'Decide business applications', FALSE),
    ('admin.listing.moderation.read', 'Read listing moderation work', FALSE),
    ('admin.listing.moderation.claim', 'Claim and release listing moderation cases', FALSE),
    ('admin.listing.moderation.resolve', 'Resolve listing moderation cases', FALSE),
    ('admin.listing.edit', 'Edit active approved listings', FALSE),
    ('admin.listing.remove', 'Remove active approved listings', FALSE),
    ('admin.user.read', 'Reserved user administration read access', TRUE),
    ('admin.user.restrict', 'Reserved user restriction access', TRUE),
    ('admin.user.suspend', 'Reserved user suspension access', TRUE),
    ('admin.user.ban', 'Reserved user ban access', TRUE),
    ('admin.user.reinstate', 'Reserved user reinstatement access', TRUE),
    ('admin.user.pii.read', 'Reserved sensitive user identity read access', TRUE),
    ('admin.business.read', 'Reserved business administration read access', TRUE),
    ('admin.business.restrict', 'Reserved business restriction access', TRUE),
    ('admin.business.suspend', 'Reserved business suspension access', TRUE),
    ('admin.business.ban', 'Reserved business ban access', TRUE),
    ('admin.business.reinstate', 'Reserved business reinstatement access', TRUE),
    ('admin.listing.suspend', 'Reserved listing suspension access', TRUE),
    ('admin.listing.reinstate', 'Reserved listing reinstatement access', TRUE),
    ('admin.report.read', 'Reserved report read access', TRUE),
    ('admin.report.assign', 'Reserved report assignment access', TRUE),
    ('admin.report.resolve', 'Reserved report resolution access', TRUE),
    ('admin.role.read', 'Reserved admin role read access', TRUE),
    ('admin.role.manage', 'Reserved admin role management access', TRUE);

INSERT INTO admin_role_permissions (role_id, permission_id)
SELECT 'SUPER_ADMIN', id FROM admin_permissions;

INSERT INTO admin_role_permissions (role_id, permission_id)
SELECT 'PLATFORM_ADMIN', id FROM admin_permissions;

INSERT INTO admin_role_permissions (role_id, permission_id)
VALUES
    ('TRUST_AND_SAFETY_ADMIN', 'admin.dashboard.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.audit.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.moderation.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.moderation.claim'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.moderation.resolve'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.edit'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.remove'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.user.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.user.restrict'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.user.suspend'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.user.ban'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.user.reinstate'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.business.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.business.restrict'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.business.suspend'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.business.ban'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.business.reinstate'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.suspend'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.listing.reinstate'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.report.read'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.report.assign'),
    ('TRUST_AND_SAFETY_ADMIN', 'admin.report.resolve'),
    ('BUSINESS_REVIEWER', 'admin.dashboard.read'),
    ('BUSINESS_REVIEWER', 'admin.audit.read'),
    ('BUSINESS_REVIEWER', 'admin.business.application.read'),
    ('BUSINESS_REVIEWER', 'admin.business.application.decide'),
    ('BUSINESS_REVIEWER', 'admin.business.read'),
    ('LISTING_MODERATOR', 'admin.dashboard.read'),
    ('LISTING_MODERATOR', 'admin.audit.read'),
    ('LISTING_MODERATOR', 'admin.listing.moderation.read'),
    ('LISTING_MODERATOR', 'admin.listing.moderation.claim'),
    ('LISTING_MODERATOR', 'admin.listing.moderation.resolve'),
    ('LISTING_MODERATOR', 'admin.listing.edit'),
    ('LISTING_MODERATOR', 'admin.listing.remove'),
    ('SUPPORT_ADMIN', 'admin.dashboard.read'),
    ('SUPPORT_ADMIN', 'admin.audit.read'),
    ('SUPPORT_ADMIN', 'admin.user.read'),
    ('SUPPORT_ADMIN', 'admin.business.read'),
    ('SUPPORT_ADMIN', 'admin.listing.moderation.read'),
    ('SUPPORT_ADMIN', 'admin.report.read'),
    ('AUDITOR', 'admin.dashboard.read'),
    ('AUDITOR', 'admin.audit.read'),
    ('AUDITOR', 'admin.business.application.read'),
    ('AUDITOR', 'admin.listing.moderation.read'),
    ('AUDITOR', 'admin.user.read'),
    ('AUDITOR', 'admin.business.read'),
    ('AUDITOR', 'admin.report.read'),
    ('AUDITOR', 'admin.role.read');

INSERT IGNORE INTO user_roles (user_id, role_id, granted_by, granted_at)
SELECT user_id, 'SUPER_ADMIN', granted_by, granted_at
FROM user_roles
WHERE role_id = 'PLATFORM_ADMIN';
