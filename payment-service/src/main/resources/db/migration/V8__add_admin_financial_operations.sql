ALTER TABLE payment_refunds
    DROP INDEX uk_payment_refund_intent,
    DROP INDEX uk_payment_refund_order,
    DROP CHECK chk_payment_refund_status,
    MODIFY cancellation_request_id CHAR(26) NULL,
    MODIFY provider_reference VARCHAR(128) NULL,
    MODIFY completed_at DATETIME(6) NULL,
    ADD COLUMN dispute_id CHAR(26) NULL AFTER order_id,
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'ORDER_CANCELLATION' AFTER dispute_id,
    ADD COLUMN refund_type VARCHAR(16) NOT NULL DEFAULT 'FULL' AFTER source,
    ADD COLUMN reason_code VARCHAR(64) NOT NULL DEFAULT 'ORDER_CANCELLATION' AFTER refund_type,
    ADD COLUMN reason VARCHAR(1000) NULL AFTER reason_code,
    ADD COLUMN initiated_by_admin_id CHAR(26) NULL AFTER reason,
    ADD COLUMN initiated_by_admin_name VARCHAR(200) NULL AFTER initiated_by_admin_id,
    ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN reconciliation_state VARCHAR(32) NOT NULL DEFAULT 'IN_SYNC' AFTER version,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER reconciliation_state,
    ADD COLUMN safe_failure_summary VARCHAR(255) NULL AFTER safe_failure_code,
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at,
    ADD KEY idx_payment_refund_intent_status (payment_intent_id, status, created_at, id),
    ADD KEY idx_payment_refund_order_created (order_id, created_at, id),
    ADD KEY idx_payment_refund_dispute (dispute_id, created_at, id),
    ADD KEY idx_payment_refund_status_created (status, created_at, id);

UPDATE payment_refunds SET updated_at = COALESCE(completed_at, created_at);

ALTER TABLE payment_refunds
    MODIFY updated_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT chk_payment_refund_status CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    ADD CONSTRAINT chk_payment_refund_source CHECK (source IN ('ORDER_CANCELLATION','HUMAN_ADMIN')),
    ADD CONSTRAINT chk_payment_refund_type CHECK (refund_type IN ('FULL','PARTIAL')),
    ADD CONSTRAINT chk_payment_refund_completion CHECK (
        (status = 'SUCCEEDED' AND completed_at IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND completed_at IS NULL)
    );

ALTER TABLE payment_refund_attempts
    DROP CHECK chk_payment_refund_attempt_operation,
    DROP CHECK chk_payment_refund_attempt_outcome,
    ADD COLUMN safe_failure_code VARCHAR(64) NULL AFTER provider_reference,
    ADD COLUMN safe_failure_summary VARCHAR(255) NULL AFTER safe_failure_code,
    ADD CONSTRAINT chk_payment_refund_attempt_operation CHECK (operation IN ('FULL_REFUND','PARTIAL_REFUND','RETRY')),
    ADD CONSTRAINT chk_payment_refund_attempt_outcome CHECK (outcome IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','UNCERTAIN'));

ALTER TABLE payment_refund_status_history
    DROP INDEX uk_payment_refund_history_status,
    DROP CHECK chk_payment_refund_history_status,
    ADD KEY idx_payment_refund_history_time (refund_id, created_at, id),
    ADD CONSTRAINT chk_payment_refund_history_status CHECK (
        to_status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')
        AND (from_status IS NULL OR from_status IN ('PENDING','PROCESSING','FAILED'))
    );

CREATE TABLE payment_admin_refund_commands (
    id CHAR(26) NOT NULL,
    admin_actor_id CHAR(26) NOT NULL,
    payment_intent_id CHAR(26) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    refund_id CHAR(26) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_admin_refund_command (admin_actor_id, operation, idempotency_key),
    KEY idx_payment_admin_refund_command_payment (payment_intent_id, created_at, id),
    KEY idx_payment_admin_refund_command_expiry (expires_at, id),
    CONSTRAINT fk_payment_admin_refund_command_intent FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id),
    CONSTRAINT fk_payment_admin_refund_command_refund FOREIGN KEY (refund_id) REFERENCES payment_refunds(id),
    CONSTRAINT chk_payment_admin_refund_command_state CHECK (state IN ('IN_PROGRESS','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
