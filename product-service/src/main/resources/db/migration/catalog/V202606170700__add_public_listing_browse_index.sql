CREATE INDEX idx_listings_public_browse
    ON listings (status, moderation_status, published_at, updated_at, id);
