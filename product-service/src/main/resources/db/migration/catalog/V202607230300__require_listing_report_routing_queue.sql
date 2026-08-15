ALTER TABLE moderation_cases
    DROP CHECK chk_moderation_cases_subject_shape,
    ADD CONSTRAINT chk_moderation_cases_subject_shape
        CHECK (
            subject_listing_id IS NOT NULL
            AND (
                (case_type = 'LISTING_REVIEW' AND routing_queue IS NULL)
                OR
                (
                    case_type = 'LISTING_REPORT'
                    AND routing_queue IS NOT NULL
                    AND routing_queue IN (
                        'TRUST_SAFETY_LEGAL',
                        'TRUST_SAFETY',
                        'MARKETPLACE_INTEGRITY',
                        'LEGAL_IP',
                        'PRIVACY'
                    )
                )
            )
        );
