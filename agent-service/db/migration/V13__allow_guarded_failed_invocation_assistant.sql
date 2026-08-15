ALTER TABLE agent_invocations
    DROP CHECK chk_agent_invocations_result_shape,
    ADD CONSTRAINT chk_agent_invocations_result_shape
        CHECK (
            (
                result_status = 'PENDING'
                AND completed_at IS NULL
                AND error_code IS NULL
                AND assistant_message_id IS NULL
            )
            OR (
                result_status = 'SUCCEEDED'
                AND completed_at IS NOT NULL
                AND error_code IS NULL
            )
            OR (
                result_status = 'FAILED'
                AND completed_at IS NOT NULL
                AND error_code IS NOT NULL
            )
        );
