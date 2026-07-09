ALTER TABLE business_applications
    ADD COLUMN active_business_account_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
        CASE
            WHEN status <> 'REJECTED' THEN applicant_user_id
            ELSE NULL
        END
    ) STORED;

CREATE UNIQUE INDEX uk_business_applications_one_active_account
    ON business_applications (active_business_account_user_id);
