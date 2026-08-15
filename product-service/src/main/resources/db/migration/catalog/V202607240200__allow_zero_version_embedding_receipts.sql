ALTER TABLE listing_discovery_embedding_receipts
    DROP CHECK chk_listing_discovery_embedding_receipt_version;

ALTER TABLE listing_discovery_embedding_receipts
    ADD CONSTRAINT chk_listing_discovery_embedding_receipt_version
        CHECK (listing_version >= 0);

ALTER TABLE listing_search_vector_apply_work
    DROP CHECK chk_listing_search_vector_apply_version;

ALTER TABLE listing_search_vector_apply_work
    ADD CONSTRAINT chk_listing_search_vector_apply_version
        CHECK (listing_version >= 0);
