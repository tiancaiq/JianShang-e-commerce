ALTER TABLE payment_refunds
    DROP CHECK chk_payment_refund_status,
    MODIFY provider_reference VARCHAR(128) NULL,
    MODIFY completed_at DATETIME(6) NULL,
    ADD COLUMN failed_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER failed_at,
    ADD COLUMN next_reconcile_at DATETIME(6) NULL AFTER safe_failure_code,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER next_reconcile_at,
    ADD CONSTRAINT chk_payment_refund_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    ADD CONSTRAINT chk_payment_refund_terminal_time CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'FAILED' AND failed_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL AND failed_at IS NULL)
    );

ALTER TABLE payment_refund_status_history
    DROP CHECK chk_payment_refund_history_status,
    ADD CONSTRAINT chk_payment_refund_history_status CHECK (
        to_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
        AND (from_status IS NULL OR from_status IN ('PENDING', 'PROCESSING'))
    );

ALTER TABLE payment_refund_attempts
    DROP CHECK chk_payment_refund_attempt_operation,
    DROP CHECK chk_payment_refund_attempt_outcome,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER provider_reference,
    ADD CONSTRAINT chk_payment_refund_attempt_operation CHECK (
        operation IN ('FULL_REFUND', 'RETRIEVE_REFUND')
    ),
    ADD CONSTRAINT chk_payment_refund_attempt_outcome CHECK (
        outcome IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'ERROR')
    );

ALTER TABLE payment_return_refunds
    DROP CHECK chk_payment_return_refund_status,
    MODIFY provider_reference VARCHAR(128) NULL,
    MODIFY completed_at DATETIME(6) NULL,
    ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER status,
    ADD COLUMN failed_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER failed_at,
    ADD COLUMN next_reconcile_at DATETIME(6) NULL AFTER safe_failure_code,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) AFTER next_reconcile_at,
    ADD CONSTRAINT chk_payment_return_refund_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')
    ),
    ADD CONSTRAINT chk_payment_return_refund_terminal_time CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'FAILED' AND failed_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL AND failed_at IS NULL)
    );

ALTER TABLE payment_return_refund_attempts
    DROP CHECK chk_payment_return_refund_attempt_outcome,
    ADD COLUMN operation VARCHAR(32) NOT NULL DEFAULT 'PARTIAL_REFUND' AFTER attempt_number,
    ADD COLUMN provider_reference VARCHAR(128) NULL AFTER outcome,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER provider_reference,
    ADD CONSTRAINT chk_payment_return_refund_attempt_operation CHECK (
        operation IN ('PARTIAL_REFUND', 'RETRIEVE_REFUND')
    ),
    ADD CONSTRAINT chk_payment_return_refund_attempt_outcome CHECK (
        outcome IN ('PROCESSING', 'SUCCEEDED', 'FAILED', 'ERROR')
    );

ALTER TABLE payment_provider_events
    DROP CHECK chk_payment_provider_event_type,
    DROP CHECK chk_payment_provider_event_outcome,
    ADD COLUMN refund_id CHAR(26) NULL AFTER payment_intent_id,
    ADD CONSTRAINT chk_payment_provider_event_outcome CHECK (
        processing_outcome IN (
            'APPLIED', 'UNKNOWN_INTENT', 'UNKNOWN_REFERENCE',
            'IGNORED_LATE_EVENT', 'IGNORED_UNSUPPORTED_EVENT',
            'REJECTED_ILLEGAL_TRANSITION'
        )
    );

CREATE INDEX idx_payment_refund_reconcile
    ON payment_refunds (status, next_reconcile_at, id);

CREATE INDEX idx_payment_return_refund_reconcile
    ON payment_return_refunds (status, next_reconcile_at, id);
