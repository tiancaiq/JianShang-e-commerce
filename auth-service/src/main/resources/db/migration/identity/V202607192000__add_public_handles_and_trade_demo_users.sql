ALTER TABLE users
    ADD COLUMN public_handle VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER display_name;

UPDATE users
SET public_handle = CONCAT('member-', LOWER(id))
WHERE public_handle IS NULL;

ALTER TABLE users
    MODIFY public_handle VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT uk_users_public_handle UNIQUE (public_handle);

INSERT INTO users (
    id, keycloak_sub, email, email_verified, display_name, public_handle,
    phone, phone_verified, avatar_url, status, version, created_at, updated_at
)
VALUES
    (
        '01D00000000000000000000001',
        '11111111-1111-4111-8111-111111111111',
        'trade.seller@msb.local',
        TRUE,
        'Mira Chen',
        'mira-trades',
        NULL,
        FALSE,
        NULL,
        'ACTIVE',
        0,
        CURRENT_TIMESTAMP(6),
        CURRENT_TIMESTAMP(6)
    ),
    (
        '01D00000000000000000000002',
        '22222222-2222-4222-8222-222222222222',
        'trade.buyer@msb.local',
        TRUE,
        'Jon Bell',
        'jon-buys',
        NULL,
        FALSE,
        NULL,
        'ACTIVE',
        0,
        CURRENT_TIMESTAMP(6),
        CURRENT_TIMESTAMP(6)
    )
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    public_handle = VALUES(public_handle),
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO user_roles (user_id, role_id, granted_by, granted_at)
VALUES
    ('01D00000000000000000000001', 'BUYER', NULL, CURRENT_TIMESTAMP(6)),
    ('01D00000000000000000000001', 'INDIVIDUAL_SELLER', NULL, CURRENT_TIMESTAMP(6)),
    ('01D00000000000000000000002', 'BUYER', NULL, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE granted_at = granted_at;

INSERT INTO individual_seller_profiles (
    id, user_id, public_city, public_region, terms_version, status,
    completed_sales_count, version, created_at, updated_at
)
VALUES (
    '01D00000000000000000000201',
    '01D00000000000000000000001',
    'Irvine',
    'Orange County',
    '2026-07',
    'ACTIVE',
    0,
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    public_city = VALUES(public_city),
    public_region = VALUES(public_region),
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP(6);
