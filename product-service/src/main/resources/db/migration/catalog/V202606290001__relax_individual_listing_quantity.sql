ALTER TABLE listings DROP CHECK chk_listings_individual_rules;

ALTER TABLE listings
    ADD CONSTRAINT chk_listings_individual_rules CHECK (
        seller_type <> 'INDIVIDUAL'
        OR (
            quantity >= 1
            AND sku IS NULL
            AND public_city IS NOT NULL
            AND public_region IS NOT NULL
        )
    );
