CREATE TABLE notification_source_events (
    consumer_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_version INT UNSIGNED NOT NULL,
    source_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    safe_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_occurred_at TIMESTAMP(6) NOT NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT UNSIGNED NOT NULL DEFAULT 1,
    processed_at TIMESTAMP(6) NULL,
    retention_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (consumer_name, source_event_id),
    KEY idx_notification_source_outcome (state, updated_at, source_event_id),
    CONSTRAINT chk_notification_source_version CHECK (source_event_version > 0),
    CONSTRAINT chk_notification_source_attempt CHECK (attempt_count > 0),
    CONSTRAINT chk_notification_source_state CHECK (
        state IN ('PROCESSING', 'COMPLETED', 'REJECTED')
    ),
    CONSTRAINT chk_notification_source_result CHECK (
        (state = 'PROCESSING' AND outcome IS NULL AND processed_at IS NULL)
        OR
        (state = 'COMPLETED' AND outcome = 'CREATED' AND processed_at IS NOT NULL)
        OR
        (state = 'REJECTED' AND outcome IN ('REJECTED', 'POISON')
            AND safe_error_code IS NOT NULL AND processed_at IS NOT NULL)
    ),
    CONSTRAINT chk_notification_source_retention CHECK (retention_until > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipient_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_args_json JSON NOT NULL,
    route VARCHAR(512) NOT NULL,
    source_consumer_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_version INT UNSIGNED NOT NULL,
    source_occurred_at TIMESTAMP(6) NOT NULL,
    source_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    read_at TIMESTAMP(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    retention_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_recipient_source_type (
        recipient_user_id, source_event_id, type
    ),
    KEY idx_notification_recipient_created (
        recipient_user_id, created_at DESC, id DESC
    ),
    KEY idx_notification_recipient_unread (
        recipient_user_id, read_at, created_at DESC, id DESC
    ),
    CONSTRAINT fk_notification_source FOREIGN KEY (
        source_consumer_name, source_event_id
    ) REFERENCES notification_source_events (consumer_name, source_event_id),
    CONSTRAINT chk_notification_type CHECK (type = 'ORDER_CONFIRMED'),
    CONSTRAINT chk_notification_message_key CHECK (message_key = 'ORDER_CONFIRMED_V1'),
    CONSTRAINT chk_notification_route CHECK (route = '/account'),
    CONSTRAINT chk_notification_projection_source_version CHECK (source_event_version = 2),
    CONSTRAINT chk_notification_args CHECK (
        JSON_TYPE(message_args_json) = 'OBJECT'
        AND JSON_LENGTH(message_args_json) = 1
        AND JSON_UNQUOTE(JSON_EXTRACT(message_args_json, '$.orderId')) IS NOT NULL
    ),
    CONSTRAINT chk_notification_retention CHECK (retention_until > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
