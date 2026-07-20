INSERT INTO listings (
    id, seller_type, individual_seller_user_id, business_id, store_id,
    category_id, title, description, condition_code, condition_notes,
    price_amount, currency, negotiable, sku, quantity, public_city, public_region,
    status, moderation_status, publication_source, published_at,
    version, created_at, updated_at
)
VALUES (
    '01D00000000000000000000101',
    'INDIVIDUAL',
    '01D00000000000000000000001',
    NULL,
    NULL,
    '01K00000000000000000000002',
    'Walnut desktop radio with warm dial light',
    'A compact tabletop radio in a walnut-finish cabinet with a softly illuminated tuning dial, two front controls, and a woven speaker grille. The radio has been used as a desk companion and remains clean, stable, and pleasant to operate. The tuning knob moves smoothly, the volume control responds evenly, and the case has only light surface wear from normal handling. It is a practical display piece for a study, bedroom, or reading corner and includes the matching power cable shown during pickup. This is an individual local trade, so the buyer and seller will arrange payment and delivery directly after discussing the details in chat.',
    'GOOD',
    'Tested for power, tuning, and volume. Light cosmetic wear is visible near the rear edge.',
    38.00,
    'USD',
    TRUE,
    NULL,
    1,
    'Irvine',
    'Orange County',
    'ACTIVE',
    'APPROVED',
    'ADMIN_REVIEW',
    CURRENT_TIMESTAMP(6),
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
)
ON DUPLICATE KEY UPDATE
    individual_seller_user_id = VALUES(individual_seller_user_id),
    title = VALUES(title),
    description = VALUES(description),
    condition_code = VALUES(condition_code),
    condition_notes = VALUES(condition_notes),
    price_amount = VALUES(price_amount),
    currency = VALUES(currency),
    negotiable = VALUES(negotiable),
    quantity = VALUES(quantity),
    public_city = VALUES(public_city),
    public_region = VALUES(public_region),
    status = 'ACTIVE',
    moderation_status = 'APPROVED',
    publication_source = 'ADMIN_REVIEW',
    published_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO listing_engagement_stats (listing_id, visit_count, like_count, updated_at)
VALUES ('01D00000000000000000000101', 0, 0, CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(6);
