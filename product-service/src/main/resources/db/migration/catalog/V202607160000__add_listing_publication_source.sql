ALTER TABLE listings
    ADD COLUMN publication_source VARCHAR(32) NULL AFTER moderation_status,
    ADD CONSTRAINT chk_listings_publication_source
        CHECK (publication_source IN ('ADMIN_REVIEW', 'BUSINESS_SELF_PUBLISHED'));

UPDATE listings
SET publication_source = 'ADMIN_REVIEW'
WHERE status = 'ACTIVE'
  AND moderation_status = 'APPROVED'
  AND published_at IS NOT NULL
  AND publication_source IS NULL;

CREATE INDEX idx_listings_public_visibility
    ON listings (seller_type, status, publication_source, moderation_status, published_at, id);
