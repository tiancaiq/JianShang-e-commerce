ALTER TABLE moderation_cases
    ADD COLUMN active_listing_review_key CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE
                WHEN case_type = 'LISTING_REVIEW' AND status IN ('OPEN', 'CLAIMED')
                    THEN subject_listing_id
                ELSE NULL
            END
        ) STORED;

CREATE UNIQUE INDEX uk_moderation_cases_active_listing_review
    ON moderation_cases (active_listing_review_key);
