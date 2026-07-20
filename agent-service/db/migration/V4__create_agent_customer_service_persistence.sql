CREATE TABLE agent_sessions (
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_type VARCHAR(40) NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_type VARCHAR(20) NOT NULL,
    subject_listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    open_session_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END
        ) STORED,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    content_purged_at DATETIME(6) NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id),
    UNIQUE KEY uq_agent_sessions_id_actor (session_id, actor_user_id),
    UNIQUE KEY uq_agent_sessions_open (
        actor_user_id,
        session_type,
        subject_type,
        subject_listing_id,
        open_session_marker
    ),
    KEY idx_agent_sessions_actor (
        actor_user_id,
        updated_at,
        session_id
    ),
    KEY idx_agent_sessions_retention (
        content_purged_at,
        last_activity_at,
        session_id
    ),
    CONSTRAINT chk_agent_sessions_type
        CHECK (session_type = 'LISTING_CUSTOMER_SERVICE'),
    CONSTRAINT chk_agent_sessions_subject
        CHECK (subject_type = 'LISTING'),
    CONSTRAINT chk_agent_sessions_status
        CHECK (status IN ('OPEN', 'READ_ONLY', 'CLOSED')),
    CONSTRAINT chk_agent_sessions_closed_shape
        CHECK (
            (status = 'CLOSED' AND closed_at IS NOT NULL)
            OR (status <> 'CLOSED' AND closed_at IS NULL)
        ),
    CONSTRAINT chk_agent_sessions_purge_shape
        CHECK (content_purged_at IS NULL OR status = 'CLOSED')
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_messages (
    message_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(20) NOT NULL,
    body VARCHAR(12000) NOT NULL,
    resolution_type VARCHAR(24) NULL,
    sources_json JSON NULL,
    actions_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_agent_messages_page (
        session_id,
        created_at,
        message_id
    ),
    KEY idx_agent_messages_actor (
        actor_user_id,
        created_at,
        message_id
    ),
    CONSTRAINT fk_agent_messages_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_messages_role
        CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_agent_messages_body
        CHECK (
            CHAR_LENGTH(body) BETWEEN 1 AND 12000
            AND CHAR_LENGTH(TRIM(body)) > 0
        ),
    CONSTRAINT chk_agent_messages_shape
        CHECK (
            (
                role = 'USER'
                AND resolution_type IS NULL
                AND sources_json IS NULL
                AND actions_json IS NULL
            )
            OR (
                role = 'ASSISTANT'
                AND resolution_type IN (
                    'ANSWERED',
                    'PARTIAL',
                    'UNKNOWN',
                    'CONTACT_SELLER',
                    'REFUSED'
                )
                AND sources_json IS NOT NULL
                AND actions_json IS NOT NULL
                AND JSON_TYPE(sources_json) = 'ARRAY'
                AND JSON_TYPE(actions_json) = 'ARRAY'
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_invocations (
    invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_message_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    assistant_message_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    client_message_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_status VARCHAR(20) NOT NULL,
    error_code VARCHAR(80) NULL,
    prompt_version VARCHAR(80) NOT NULL,
    model_provider VARCHAR(80) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    tool_registry_version VARCHAR(80) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    input_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    output_tokens BIGINT UNSIGNED NOT NULL DEFAULT 0,
    latency_ms BIGINT UNSIGNED NULL,
    estimated_cost DECIMAL(18, 8) NOT NULL DEFAULT 0,
    retry_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    correlation_id VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (invocation_id),
    UNIQUE KEY uq_agent_invocations_retry (
        session_id,
        actor_user_id,
        client_message_id
    ),
    UNIQUE KEY uq_agent_invocations_user_message (user_message_id),
    UNIQUE KEY uq_agent_invocations_assistant_message (assistant_message_id),
    KEY idx_agent_invocations_session (
        session_id,
        created_at,
        invocation_id
    ),
    KEY idx_agent_invocations_result (
        result_status,
        created_at,
        invocation_id
    ),
    KEY idx_agent_invocations_correlation (
        correlation_id,
        created_at,
        invocation_id
    ),
    CONSTRAINT fk_agent_invocations_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_invocations_user_message
        FOREIGN KEY (user_message_id)
        REFERENCES agent_messages (message_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_invocations_assistant_message
        FOREIGN KEY (assistant_message_id)
        REFERENCES agent_messages (message_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_invocations_status
        CHECK (result_status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_agent_invocations_request_hash
        CHECK (
            (client_message_id IS NULL AND request_hash IS NULL)
            OR (
                client_message_id IS NOT NULL
                AND request_hash IS NOT NULL
                AND request_hash REGEXP '^[0-9a-f]{64}$'
            )
        ),
    CONSTRAINT chk_agent_invocations_versions
        CHECK (
            CHAR_LENGTH(TRIM(prompt_version)) > 0
            AND CHAR_LENGTH(TRIM(model_provider)) > 0
            AND CHAR_LENGTH(TRIM(model_name)) > 0
            AND CHAR_LENGTH(TRIM(schema_version)) > 0
            AND CHAR_LENGTH(TRIM(tool_registry_version)) > 0
            AND CHAR_LENGTH(TRIM(policy_version)) > 0
        ),
    CONSTRAINT chk_agent_invocations_cost
        CHECK (estimated_cost >= 0),
    CONSTRAINT chk_agent_invocations_result_shape
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
                AND assistant_message_id IS NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_tool_calls (
    tool_call_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_number SMALLINT UNSIGNED NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    argument_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_refs_json JSON NULL,
    result_status VARCHAR(20) NOT NULL,
    error_code VARCHAR(80) NULL,
    latency_ms BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (tool_call_id),
    UNIQUE KEY uq_agent_tool_calls_sequence (
        invocation_id,
        sequence_number
    ),
    KEY idx_agent_tool_calls_result (
        result_status,
        created_at,
        tool_call_id
    ),
    KEY idx_agent_tool_calls_name (
        tool_name,
        created_at,
        tool_call_id
    ),
    CONSTRAINT fk_agent_tool_calls_invocation
        FOREIGN KEY (invocation_id)
        REFERENCES agent_invocations (invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_agent_tool_calls_sequence
        CHECK (sequence_number > 0),
    CONSTRAINT chk_agent_tool_calls_name
        CHECK (tool_name IN ('getListing', 'retrieveKnowledge')),
    CONSTRAINT chk_agent_tool_calls_argument_hash
        CHECK (argument_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_tool_calls_result_hash
        CHECK (
            result_hash IS NULL
            OR result_hash REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_agent_tool_calls_source_refs
        CHECK (
            source_refs_json IS NULL
            OR JSON_TYPE(source_refs_json) = 'ARRAY'
        ),
    CONSTRAINT chk_agent_tool_calls_status
        CHECK (result_status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_agent_tool_calls_result_shape
        CHECK (
            (
                result_status = 'PENDING'
                AND completed_at IS NULL
                AND error_code IS NULL
                AND result_hash IS NULL
            )
            OR (
                result_status = 'SUCCEEDED'
                AND completed_at IS NOT NULL
                AND error_code IS NULL
                AND result_hash IS NOT NULL
            )
            OR (
                result_status = 'FAILED'
                AND completed_at IS NOT NULL
                AND error_code IS NOT NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
