ALTER TABLE payment_outbox_events
    ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER retry_count,
    ADD COLUMN next_attempt_at DATETIME(6) NULL AFTER attempt_count,
    ADD COLUMN last_attempt_at DATETIME(6) NULL AFTER next_attempt_at,
    ADD COLUMN claim_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER last_attempt_at,
    ADD COLUMN claimed_at DATETIME(6) NULL AFTER claim_token,
    ADD COLUMN claim_expires_at DATETIME(6) NULL AFTER claimed_at,
    ADD COLUMN last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER claim_expires_at,
    ADD COLUMN last_error_message VARCHAR(240) NULL AFTER last_error_code,
    ADD COLUMN terminal_failure_at DATETIME(6) NULL AFTER last_error_message;

UPDATE payment_outbox_events
SET next_attempt_at = created_at
WHERE published_at IS NULL;

CREATE INDEX idx_payment_outbox_dispatch_due
    ON payment_outbox_events (
        published_at,
        terminal_failure_at,
        next_attempt_at,
        created_at,
        id
    );

CREATE INDEX idx_payment_outbox_claim_expiry
    ON payment_outbox_events (claim_expires_at, id);

ALTER TABLE payment_outbox_events
    ADD CONSTRAINT chk_payment_outbox_terminal_state CHECK (
        NOT (published_at IS NOT NULL AND terminal_failure_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_payment_outbox_claim_state CHECK (
        (claim_token IS NULL AND claimed_at IS NULL AND claim_expires_at IS NULL)
        OR
        (claim_token IS NOT NULL AND claimed_at IS NOT NULL AND claim_expires_at IS NOT NULL)
    );
