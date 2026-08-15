ALTER TABLE business_verification_events
    DROP CHECK chk_business_verification_events_source,
    ADD COLUMN previous_state VARCHAR(64) NULL AFTER outcome,
    ADD COLUMN new_state VARCHAR(64) NULL AFTER previous_state,
    ADD COLUMN correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER actor_user_id,
    ADD CONSTRAINT chk_business_verification_events_source
        CHECK (source IN ('PROVIDER', 'ADMIN', 'APPLICANT', 'SYSTEM'));

CREATE INDEX idx_business_verification_events_application_timeline
    ON business_verification_events (application_id, created_at, id);
