CREATE TABLE agent_discovery_exclusions (
    exclusion_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(32) NULL,
    excluded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (exclusion_id),
    UNIQUE KEY uq_agent_discovery_exclusion_listing (
        session_id,
        actor_user_id,
        listing_id
    ),
    KEY idx_agent_discovery_exclusion_session (
        session_id,
        excluded_at,
        exclusion_id
    ),
    CONSTRAINT fk_agent_discovery_exclusion_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_discovery_exclusion_reason
        CHECK (
            reason_code IS NULL
            OR reason_code IN (
                'NOT_RELEVANT',
                'TOO_EXPENSIVE',
                'TOO_FAR',
                'WRONG_CONDITION',
                'ALREADY_HAVE',
                'OTHER'
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_discovery_exclusion_commands (
    command_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_reason_code VARCHAR(32) NULL,
    response_outcome VARCHAR(24) NOT NULL,
    response_preference_version BIGINT UNSIGNED NOT NULL,
    response_excluded_count SMALLINT UNSIGNED NOT NULL,
    response_updated_at DATETIME(6) NOT NULL,
    content_redacted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (command_id),
    UNIQUE KEY uq_agent_discovery_exclusion_command_key (
        session_id,
        actor_user_id,
        idempotency_key_hash
    ),
    KEY idx_agent_discovery_exclusion_command_expiry (
        expires_at,
        command_id
    ),
    CONSTRAINT fk_agent_discovery_exclusion_command_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_discovery_exclusion_command_hashes
        CHECK (
            idempotency_key_hash REGEXP '^[0-9a-f]{64}$'
            AND request_hash REGEXP '^[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_agent_discovery_exclusion_command_outcome
        CHECK (response_outcome IN ('EXCLUDED', 'ALREADY_EXCLUDED')),
    CONSTRAINT chk_agent_discovery_exclusion_command_count
        CHECK (response_excluded_count BETWEEN 1 AND 20),
    CONSTRAINT chk_agent_discovery_exclusion_command_content
        CHECK (
            (
                content_redacted_at IS NULL
                AND response_listing_id IS NOT NULL
            )
            OR (
                content_redacted_at IS NOT NULL
                AND response_listing_id IS NULL
                AND response_reason_code IS NULL
            )
        ),
    CONSTRAINT chk_agent_discovery_exclusion_command_reason
        CHECK (
            response_reason_code IS NULL
            OR response_reason_code IN (
                'NOT_RELEVANT',
                'TOO_EXPENSIVE',
                'TOO_FAR',
                'WRONG_CONDITION',
                'ALREADY_HAVE',
                'OTHER'
            )
        ),
    CONSTRAINT chk_agent_discovery_exclusion_command_retention
        CHECK (expires_at > created_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
