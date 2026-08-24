ALTER TABLE admin_role_assignments
    ADD COLUMN revocation_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER revocation_reason,
    ADD COLUMN revocation_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER revocation_idempotency_key,
    ADD CONSTRAINT uk_admin_role_assignment_revocation UNIQUE (revoked_by_admin_id, revocation_idempotency_key);

ALTER TABLE admin_approval_requests
    ADD COLUMN cancellation_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER request_idempotency_key,
    ADD COLUMN cancellation_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER cancellation_idempotency_key,
    ADD CONSTRAINT uk_admin_approval_cancellation UNIQUE (requester_admin_id, cancellation_idempotency_key);
