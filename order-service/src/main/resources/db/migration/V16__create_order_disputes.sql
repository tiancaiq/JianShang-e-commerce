CREATE TABLE order_disputes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    business_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    buyer_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opened_by_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    opened_by_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    resolution_reason VARCHAR(2000) NULL,
    recommended_refund_amount DECIMAL(19,4) NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    return_instructions VARCHAR(2000) NULL,
    return_deadline TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_business_order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN status IN (
            'RESOLVED_NO_ACTION','RETURN_APPROVED','REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED'
        ) THEN NULL ELSE business_order_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dispute_active_group (active_business_order_id),
    KEY idx_order_dispute_queue (status, priority, created_at, id),
    KEY idx_order_dispute_assignment (assigned_admin_id, status, updated_at, id),
    KEY idx_order_dispute_order (order_id, id),
    KEY idx_order_dispute_buyer (buyer_user_id, created_at, id),
    KEY idx_order_dispute_business (business_id, created_at, id),
    CONSTRAINT fk_order_dispute_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_dispute_group FOREIGN KEY (business_order_id) REFERENCES business_orders(id),
    CONSTRAINT chk_order_dispute_opener CHECK (opened_by_type IN ('BUYER','SELLER_BUSINESS')),
    CONSTRAINT chk_order_dispute_reason CHECK (reason_code IN (
        'ITEM_NOT_RECEIVED','ITEM_DAMAGED','ITEM_NOT_AS_DESCRIBED','WRONG_ITEM',
        'COUNTERFEIT_OR_INAUTHENTIC','MISSING_PARTS','QUANTITY_INCORRECT',
        'SELLER_FAILED_TO_FULFILL','RETURN_DISAGREEMENT','OTHER')),
    CONSTRAINT chk_order_dispute_status CHECK (status IN (
        'OPEN','WAITING_FOR_BUYER','WAITING_FOR_SELLER','UNDER_ADMIN_REVIEW','READY_FOR_DECISION',
        'RESOLVED_NO_ACTION','RETURN_APPROVED','REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')),
    CONSTRAINT chk_order_dispute_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_order_dispute_resolution CHECK (
        (status NOT IN ('RESOLVED_NO_ACTION','RETURN_APPROVED','REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')
            AND resolved_at IS NULL AND resolution_type IS NULL AND resolution_reason_code IS NULL
            AND resolution_reason IS NULL AND recommended_refund_amount IS NULL)
        OR
        (status IN ('RESOLVED_NO_ACTION','RETURN_APPROVED','REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')
            AND resolved_at IS NOT NULL AND resolution_type = status AND resolution_reason_code IS NOT NULL
            AND resolution_reason IS NOT NULL)),
    CONSTRAINT chk_order_dispute_refund_amount CHECK (
        (resolution_type IN ('REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')
            AND recommended_refund_amount IS NOT NULL AND recommended_refund_amount > 0)
        OR
        (resolution_type NOT IN ('REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')
            AND recommended_refund_amount IS NULL)
        OR resolution_type IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_items (
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_item_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (dispute_id, order_item_id),
    CONSTRAINT fk_order_dispute_item_dispute FOREIGN KEY (dispute_id) REFERENCES order_disputes(id),
    CONSTRAINT fk_order_dispute_item_order_item FOREIGN KEY (order_item_id) REFERENCES order_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_statements (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    statement_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_dispute_statement_time (dispute_id, created_at, id),
    CONSTRAINT fk_order_dispute_statement_dispute FOREIGN KEY (dispute_id) REFERENCES order_disputes(id),
    CONSTRAINT chk_order_dispute_statement_author CHECK (author_type IN ('BUYER','SELLER_BUSINESS','ADMIN')),
    CONSTRAINT chk_order_dispute_statement_type CHECK (statement_type IN ('STATEMENT','INFORMATION_REQUEST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_evidence (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_label VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dispute_evidence_ref (dispute_id, reference_type, reference_id),
    CONSTRAINT fk_order_dispute_evidence_dispute FOREIGN KEY (dispute_id) REFERENCES order_disputes(id),
    CONSTRAINT chk_order_dispute_evidence_author CHECK (author_type IN ('BUYER','SELLER_BUSINESS')),
    CONSTRAINT chk_order_dispute_evidence_type CHECK (reference_type IN ('ORDER_ITEM','LISTING_IMAGE','SHIPMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_admin_notes (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_admin_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    author_display_name VARCHAR(200) NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_dispute_note_time (dispute_id, created_at, id),
    CONSTRAINT fk_order_dispute_note_dispute FOREIGN KEY (dispute_id) REFERENCES order_disputes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_events (
    event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    actor_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_display_name VARCHAR(200) NULL,
    previous_state VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_state VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(2000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    safe_metadata JSON NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_order_dispute_event_request (request_id),
    KEY idx_order_dispute_event_time (dispute_id, occurred_at, event_id),
    CONSTRAINT fk_order_dispute_event_dispute FOREIGN KEY (dispute_id) REFERENCES order_disputes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_dispute_commands (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dispute_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dispute_command_retry (actor_type, actor_id, operation, idempotency_key),
    KEY idx_order_dispute_command_expiry (expires_at, id),
    CONSTRAINT chk_order_dispute_command_state CHECK (state IN ('IN_PROGRESS','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
