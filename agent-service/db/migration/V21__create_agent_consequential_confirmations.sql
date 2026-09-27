CREATE TABLE agent_confirmations (
    confirmation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    originating_invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workflow_id VARCHAR(80) NULL,
    capability VARCHAR(80) NOT NULL,
    capability_version VARCHAR(80) NOT NULL,
    action_name VARCHAR(80) NOT NULL,
    risk_level TINYINT UNSIGNED NOT NULL,
    normalized_arguments_json JSON NOT NULL,
    targets_json JSON NOT NULL,
    financial_facts_json JSON NOT NULL,
    action_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    human_summary VARCHAR(500) NOT NULL,
    state VARCHAR(20) NOT NULL,
    active_conversation_marker TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN state IN ('PENDING', 'CONFIRMED') THEN 1 ELSE NULL END
        ) STORED,
    action_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    confirmed_at DATETIME(6) NULL,
    confirmed_by_invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    consumed_at DATETIME(6) NULL,
    consumed_by_invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cancelled_at DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    invalidated_reason VARCHAR(80) NULL,
    result_category VARCHAR(80) NULL,
    updated_at DATETIME(6) NOT NULL,
    optimistic_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (confirmation_id),
    UNIQUE KEY uq_agent_confirmations_origin (originating_invocation_id),
    UNIQUE KEY uq_agent_confirmations_action_key (action_key),
    UNIQUE KEY uq_agent_confirmations_active_conversation (
        session_id,
        actor_user_id,
        active_conversation_marker
    ),
    KEY idx_agent_confirmations_actor_session (
        actor_user_id,
        session_id,
        created_at,
        confirmation_id
    ),
    KEY idx_agent_confirmations_expiry (
        state,
        expires_at,
        confirmation_id
    ),
    KEY idx_agent_confirmations_fingerprint (
        action_fingerprint,
        created_at,
        confirmation_id
    ),
    CONSTRAINT fk_agent_confirmations_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_confirmations_origin_invocation
        FOREIGN KEY (originating_invocation_id)
        REFERENCES agent_invocations (invocation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_confirmations_confirm_invocation
        FOREIGN KEY (confirmed_by_invocation_id)
        REFERENCES agent_invocations (invocation_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_confirmations_consume_invocation
        FOREIGN KEY (consumed_by_invocation_id)
        REFERENCES agent_invocations (invocation_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_confirmations_state
        CHECK (
            state IN (
                'PENDING', 'CONFIRMED', 'CONSUMED', 'CANCELLED',
                'EXPIRED', 'INVALIDATED'
            )
        ),
    CONSTRAINT chk_agent_confirmations_risk
        CHECK (risk_level BETWEEN 0 AND 5),
    CONSTRAINT chk_agent_confirmations_expiry
        CHECK (risk_level < 3 OR expires_at IS NOT NULL),
    CONSTRAINT chk_agent_confirmations_json
        CHECK (
            JSON_TYPE(normalized_arguments_json) = 'OBJECT'
            AND JSON_TYPE(targets_json) = 'ARRAY'
            AND JSON_TYPE(financial_facts_json) = 'ARRAY'
        ),
    CONSTRAINT chk_agent_confirmations_fingerprint
        CHECK (action_fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_confirmations_summary
        CHECK (
            CHAR_LENGTH(TRIM(human_summary)) BETWEEN 1 AND 500
            AND CHAR_LENGTH(TRIM(capability)) BETWEEN 1 AND 80
            AND CHAR_LENGTH(TRIM(capability_version)) BETWEEN 1 AND 80
            AND CHAR_LENGTH(TRIM(action_name)) BETWEEN 1 AND 80
        ),
    CONSTRAINT chk_agent_confirmations_state_shape
        CHECK (
            (state = 'PENDING'
                AND confirmed_at IS NULL AND consumed_at IS NULL
                AND cancelled_at IS NULL AND invalidated_at IS NULL)
            OR (state = 'CONFIRMED'
                AND confirmed_at IS NOT NULL
                AND confirmed_by_invocation_id IS NOT NULL
                AND consumed_at IS NULL AND cancelled_at IS NULL
                AND invalidated_at IS NULL)
            OR (state = 'CONSUMED'
                AND confirmed_at IS NOT NULL
                AND confirmed_by_invocation_id IS NOT NULL
                AND consumed_at IS NOT NULL
                AND consumed_by_invocation_id IS NOT NULL
                AND cancelled_at IS NULL AND invalidated_at IS NULL)
            OR (state = 'CANCELLED'
                AND cancelled_at IS NOT NULL AND consumed_at IS NULL
                AND invalidated_at IS NULL)
            OR (state IN ('EXPIRED', 'INVALIDATED')
                AND invalidated_at IS NOT NULL
                AND invalidated_reason IS NOT NULL
                AND consumed_at IS NULL AND cancelled_at IS NULL)
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_confirmation_transitions (
    transition_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confirmation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_state VARCHAR(20) NULL,
    to_state VARCHAR(20) NOT NULL,
    invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    correlation_id VARCHAR(128) NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    policy_result VARCHAR(24) NOT NULL,
    result_category VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (transition_id),
    KEY idx_agent_confirmation_transitions_confirmation (
        confirmation_id,
        created_at,
        transition_id
    ),
    KEY idx_agent_confirmation_transitions_actor (
        actor_user_id,
        created_at,
        transition_id
    ),
    CONSTRAINT fk_agent_confirmation_transitions_confirmation
        FOREIGN KEY (confirmation_id)
        REFERENCES agent_confirmations (confirmation_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_confirmation_transitions_states
        CHECK (
            (from_state IS NULL OR from_state IN (
                'PENDING', 'CONFIRMED', 'CONSUMED', 'CANCELLED',
                'EXPIRED', 'INVALIDATED'
            ))
            AND to_state IN (
                'PENDING', 'CONFIRMED', 'CONSUMED', 'CANCELLED',
                'EXPIRED', 'INVALIDATED'
            )
        ),
    CONSTRAINT chk_agent_confirmation_transitions_codes
        CHECK (
            reason_code REGEXP '^[A-Z][A-Z0-9_]{0,79}$'
            AND policy_result IN ('ALLOWED', 'DENIED', 'NOT_APPLICABLE')
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
