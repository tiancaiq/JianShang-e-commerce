INSERT INTO categories (
    id, parent_id, slug, name, description, status, seller_eligibility,
    listing_creation_allowed, listing_submission_allowed, replacement_category_id,
    display_order, current_rule_version, version, created_at, updated_at
)
VALUES
    ('01KCAT00000000000000000001', '01K00000000000000000000002', 'phones-accessories', 'Phones & Accessories', 'Phones, cases, chargers, and mobile accessories.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000002', '01K00000000000000000000002', 'computers', 'Computers', 'Desktop, laptop, and computer peripheral categories.', 'ACTIVE', 'BOTH', false, false, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000003', '01KCAT00000000000000000002', 'laptops', 'Laptops', 'Portable computers and notebooks.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000004', '01KCAT00000000000000000002', 'desktop-computers', 'Desktop Computers', 'Desktop computer systems and workstations.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000005', '01KCAT00000000000000000002', 'keyboards', 'Keyboards', 'Computer keyboards and keyboard accessories.', 'ACTIVE', 'BOTH', true, true, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000006', '01KCAT00000000000000000002', 'monitors', 'Monitors', 'Computer displays and monitors.', 'ACTIVE', 'BOTH', true, true, null, 40, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000007', '01KCAT00000000000000000002', 'computer-accessories', 'Computer Accessories', 'Mice, hubs, stands, cables, and related peripherals.', 'ACTIVE', 'BOTH', true, true, null, 50, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000008', '01K00000000000000000000002', 'audio', 'Audio', 'Headphones, speakers, and personal audio equipment.', 'ACTIVE', 'BOTH', false, false, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000009', '01KCAT00000000000000000008', 'headphones', 'Headphones', 'Wired and wireless headphones and earbuds.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000A', '01KCAT00000000000000000008', 'speakers', 'Speakers', 'Portable, home, and computer speakers.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000B', '01K00000000000000000000002', 'cameras', 'Cameras', 'Cameras, lenses, and photography accessories.', 'ACTIVE', 'BOTH', true, true, null, 40, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000C', '01K00000000000000000000002', 'gaming-electronics', 'Gaming Electronics', 'Game consoles, controllers, and gaming accessories.', 'ACTIVE', 'BOTH', true, true, null, 50, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000D', '01K00000000000000000000002', 'tv-home-theater', 'TV & Home Theater', 'Televisions, projectors, and home-theater equipment.', 'ACTIVE', 'BOTH', true, true, null, 60, 1, 0, current_timestamp(6), current_timestamp(6)),

    ('01KCAT0000000000000000000E', '01K00000000000000000000003', 'furniture', 'Furniture', 'Home and office furniture.', 'ACTIVE', 'BOTH', false, false, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000F', '01KCAT0000000000000000000E', 'desks-tables', 'Desks & Tables', 'Desks, dining tables, side tables, and work surfaces.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000G', '01KCAT0000000000000000000E', 'chairs', 'Chairs', 'Office, dining, lounge, and accent chairs.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000H', '01KCAT0000000000000000000E', 'storage-furniture', 'Storage Furniture', 'Shelving, cabinets, dressers, and storage furniture.', 'ACTIVE', 'BOTH', true, true, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000J', '01K00000000000000000000003', 'kitchen-dining', 'Kitchen & Dining', 'Cookware, tableware, and kitchen accessories.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000K', '01K00000000000000000000003', 'home-decor', 'Home Decor', 'Lighting, bedding, decoration, and home organization.', 'ACTIVE', 'BOTH', true, true, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000M', '01K00000000000000000000003', 'tools-home-improvement', 'Tools & Home Improvement', 'Hand tools, power tools, fixtures, and home improvement supplies.', 'ACTIVE', 'BOTH', true, true, null, 40, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000N', '01K00000000000000000000003', 'patio-garden', 'Patio & Garden', 'Patio, lawn, gardening, and outdoor-living products.', 'ACTIVE', 'BOTH', true, true, null, 50, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000P', '01K00000000000000000000003', 'sports-fitness', 'Sports & Fitness', 'Exercise, outdoor recreation, and sports equipment.', 'ACTIVE', 'BOTH', true, true, null, 60, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000Q', '01K00000000000000000000003', 'pet-supplies', 'Pet Supplies', 'Food, toys, care, and accessories for pets.', 'ACTIVE', 'BOTH', true, true, null, 70, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000R', '01K00000000000000000000003', 'toys-games', 'Toys & Games', 'Toys, board games, puzzles, and hobby products.', 'ACTIVE', 'BOTH', true, true, null, 80, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000S', '01K00000000000000000000003', 'musical-instruments', 'Musical Instruments', 'Instruments, recording gear, and music accessories.', 'ACTIVE', 'BOTH', true, true, null, 90, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000T', '01K00000000000000000000003', 'office-supplies', 'Office Supplies', 'Office equipment, stationery, and organization products.', 'ACTIVE', 'BOTH', true, true, null, 100, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000V', '01K00000000000000000000003', 'appliances', 'Appliances', 'Small and large household appliances.', 'ACTIVE', 'BOTH', true, true, null, 110, 1, 0, current_timestamp(6), current_timestamp(6)),

    ('01KCAT0000000000000000000W', '01K00000000000000000000004', 'womens-clothing', 'Women\'s Clothing', 'Women\'s apparel and outerwear.', 'ACTIVE', 'BOTH', false, false, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000X', '01KCAT0000000000000000000W', 'dresses', 'Dresses', 'Casual, formal, and occasion dresses.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000Y', '01KCAT0000000000000000000W', 'womens-tops-bottoms', 'Women\'s Tops & Bottoms', 'Women\'s shirts, blouses, skirts, and trousers.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT0000000000000000000Z', '01K00000000000000000000004', 'mens-clothing', 'Men\'s Clothing', 'Men\'s apparel and outerwear.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000010', '01K00000000000000000000004', 'shoes', 'Shoes', 'Athletic, casual, and formal footwear.', 'ACTIVE', 'BOTH', true, true, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000011', '01K00000000000000000000004', 'bags-accessories', 'Bags & Accessories', 'Bags, wallets, jewelry, watches, and fashion accessories.', 'ACTIVE', 'BOTH', true, true, null, 40, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000012', '01K00000000000000000000004', 'beauty-personal-care', 'Beauty & Personal Care', 'Beauty, grooming, and personal-care products.', 'ACTIVE', 'BOTH', true, true, null, 50, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000013', '01K00000000000000000000004', 'baby-kids', 'Baby & Kids', 'Baby products and children\'s clothing and accessories.', 'ACTIVE', 'BOTH', true, true, null, 60, 1, 0, current_timestamp(6), current_timestamp(6)),

    ('01KCAT00000000000000000014', '01K00000000000000000000005', 'books', 'Books', 'Printed books and other physical reading material.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000015', '01K00000000000000000000005', 'movies-tv', 'Movies & TV', 'Movies and television on physical media.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000016', '01K00000000000000000000005', 'music', 'Music', 'Vinyl records, CDs, and other physical music media.', 'ACTIVE', 'BOTH', true, true, null, 30, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000017', '01K00000000000000000000005', 'video-games', 'Video Games', 'Physical console and computer games.', 'ACTIVE', 'BOTH', true, true, null, 40, 1, 0, current_timestamp(6), current_timestamp(6)),

    ('01KCAT00000000000000000018', '01K00000000000000000000001', 'automotive', 'Automotive', 'Vehicle parts, accessories, care, and tools.', 'ACTIVE', 'BOTH', true, true, null, 10, 1, 0, current_timestamp(6), current_timestamp(6)),
    ('01KCAT00000000000000000019', '01K00000000000000000000001', 'arts-crafts-sewing', 'Arts, Crafts & Sewing', 'Art materials, craft supplies, and sewing products.', 'ACTIVE', 'BOTH', true, true, null, 20, 1, 0, current_timestamp(6), current_timestamp(6))
ON DUPLICATE KEY UPDATE
    parent_id = values(parent_id), name = values(name), description = values(description),
    status = 'ACTIVE', seller_eligibility = values(seller_eligibility),
    listing_creation_allowed = values(listing_creation_allowed),
    listing_submission_allowed = values(listing_submission_allowed),
    display_order = values(display_order), updated_at = current_timestamp(6);

INSERT INTO category_rule_versions (
    id, category_id, version_number, configuration_json, configuration_hash,
    reason, created_by_admin_id, created_at
)
SELECT
    CONCAT('01KCR', RIGHT(CONCAT(REPEAT('0', 21), ROW_NUMBER() OVER (ORDER BY c.id)), 21)),
    c.id,
    1,
    JSON_OBJECT(
        'status', c.status,
        'sellerEligibility', c.seller_eligibility,
        'listingCreationAllowed', c.listing_creation_allowed,
        'listingSubmissionAllowed', c.listing_submission_allowed,
        'attributes', JSON_ARRAY()
    ),
    SHA2(CONCAT_WS('|', c.id, c.status, c.seller_eligibility,
        c.listing_creation_allowed, c.listing_submission_allowed), 256),
    'Bootstrap JianShang marketplace catalog hierarchy',
    NULL,
    current_timestamp(6)
FROM categories c
WHERE c.id LIKE '01KCAT%'
ON DUPLICATE KEY UPDATE category_id = values(category_id);
