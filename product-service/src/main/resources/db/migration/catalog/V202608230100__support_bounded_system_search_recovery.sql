ALTER TABLE listing_search_projection_work
    DROP INDEX uk_listing_search_projection_version,
    ADD COLUMN correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER last_error_code;

CREATE INDEX idx_listing_search_projection_listing_version
    ON listing_search_projection_work (listing_id, listing_version, created_at DESC, work_id DESC);

CREATE INDEX idx_listing_search_projection_failures
    ON listing_search_projection_work (state, attempt_count, next_attempt_at, created_at, work_id);

CREATE INDEX idx_product_outbox_operations
    ON outbox_events (published_at, retry_count, next_attempt_at, created_at, event_id);
