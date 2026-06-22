INSERT INTO categories (id, slug, name, parent_id, status, display_order, created_at, updated_at)
VALUES
    ('01K00000000000000000000001', 'general', 'General', NULL, 'ACTIVE', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('01K00000000000000000000002', 'electronics', 'Electronics', NULL, 'ACTIVE', 10, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('01K00000000000000000000003', 'home-garden', 'Home & Garden', NULL, 'ACTIVE', 20, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('01K00000000000000000000004', 'clothing', 'Clothing', NULL, 'ACTIVE', 30, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
    ('01K00000000000000000000005', 'books-media', 'Books & Media', NULL, 'ACTIVE', 40, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    status = VALUES(status),
    display_order = VALUES(display_order),
    updated_at = CURRENT_TIMESTAMP(6);
