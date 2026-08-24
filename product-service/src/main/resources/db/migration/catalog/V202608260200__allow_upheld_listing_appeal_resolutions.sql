ALTER TABLE listing_appeal_resolution_commands
    DROP CHECK chk_listing_appeal_resolution_outcome;

ALTER TABLE listing_appeal_resolution_commands
    ADD CONSTRAINT chk_listing_appeal_resolution_outcome
        CHECK (outcome IN ('UPHELD', 'REVOKED', 'MODIFIED'));
