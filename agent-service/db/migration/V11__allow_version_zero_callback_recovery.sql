ALTER TABLE discovery_embedding_recovery_commands
    DROP CHECK chk_discovery_embedding_recovery_counts;

ALTER TABLE discovery_embedding_recovery_commands
    ADD CONSTRAINT chk_discovery_embedding_recovery_counts
        CHECK (
            recovered_count <= expected_count
            AND (
                (
                    outcome = 'RECOVERED'
                    AND expected_count > 0
                    AND recovered_count = expected_count
                    AND original_attempt_count_min IS NOT NULL
                    AND original_attempt_count_max IS NOT NULL
                    AND original_error_code IN (
                        'MAX_ATTEMPTS_EXHAUSTED',
                        'CALLBACK_INVALID_RESPONSE'
                    )
                )
                OR (
                    outcome = 'COUNT_MISMATCH'
                    AND recovered_count = 0
                )
            )
        );
