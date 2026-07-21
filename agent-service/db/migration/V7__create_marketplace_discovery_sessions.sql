ALTER TABLE agent_sessions
    DROP CHECK chk_agent_sessions_type,
    DROP CHECK chk_agent_sessions_subject,
    MODIFY COLUMN subject_type VARCHAR(20) NULL,
    MODIFY COLUMN subject_listing_id
        CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN preference_state_json JSON NULL AFTER subject_listing_id,
    ADD COLUMN preference_version BIGINT UNSIGNED NOT NULL DEFAULT 0
        AFTER preference_state_json,
    ADD COLUMN clarification_turn_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        AFTER preference_version,
    ADD COLUMN clarification_question_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        AFTER clarification_turn_count,
    ADD COLUMN discovery_open_marker TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN session_type = 'MARKETPLACE_DISCOVERY'
                     AND status = 'OPEN'
                THEN 1
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uq_agent_discovery_open_actor (
        actor_user_id,
        discovery_open_marker
    ),
    ADD CONSTRAINT chk_agent_sessions_type
        CHECK (
            session_type IN (
                'LISTING_CUSTOMER_SERVICE',
                'MARKETPLACE_DISCOVERY'
            )
        ),
    ADD CONSTRAINT chk_agent_sessions_subject
        CHECK (
            (
                session_type = 'LISTING_CUSTOMER_SERVICE'
                AND subject_type = 'LISTING'
                AND subject_listing_id IS NOT NULL
                AND preference_state_json IS NULL
                AND preference_version = 0
                AND clarification_turn_count = 0
                AND clarification_question_count = 0
            )
            OR (
                session_type = 'MARKETPLACE_DISCOVERY'
                AND subject_type IS NULL
                AND subject_listing_id IS NULL
                AND preference_state_json IS NOT NULL
                AND JSON_TYPE(preference_state_json) = 'OBJECT'
                AND clarification_turn_count <= 3
                AND clarification_question_count <= 5
            )
        );

ALTER TABLE agent_messages
    DROP CHECK chk_agent_messages_shape,
    ADD CONSTRAINT chk_agent_messages_shape
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
                    'REFUSED',
                    'CLARIFY',
                    'RECOMMEND',
                    'NO_RESULTS',
                    'HANDOFF'
                )
                AND sources_json IS NOT NULL
                AND actions_json IS NOT NULL
                AND JSON_TYPE(sources_json) = 'ARRAY'
                AND JSON_TYPE(actions_json) = 'ARRAY'
            )
        );

ALTER TABLE agent_tool_calls
    DROP CHECK chk_agent_tool_calls_name,
    ADD CONSTRAINT chk_agent_tool_calls_name
        CHECK (
            tool_name IN (
                'getListing',
                'retrieveKnowledge',
                'SEARCH_INDIVIDUAL',
                'GET_LISTING'
            )
        );

CREATE TABLE agent_discovery_recommendations (
    recommendation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invocation_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recommendation_rank SMALLINT UNSIGNED NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    response_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    match_reason VARCHAR(300) NOT NULL,
    constraint_coverage_json JSON NOT NULL,
    listing_snapshot_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (recommendation_id),
    UNIQUE KEY uq_agent_discovery_recommendation_rank (
        invocation_id,
        recommendation_rank
    ),
    UNIQUE KEY uq_agent_discovery_recommendation_listing (
        invocation_id,
        listing_id
    ),
    KEY idx_agent_discovery_recommendation_session (
        session_id,
        created_at,
        recommendation_id
    ),
    CONSTRAINT fk_agent_discovery_recommendation_invocation
        FOREIGN KEY (invocation_id)
        REFERENCES agent_invocations (invocation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_agent_discovery_recommendation_session_actor
        FOREIGN KEY (session_id, actor_user_id)
        REFERENCES agent_sessions (session_id, actor_user_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_agent_discovery_recommendation_rank
        CHECK (recommendation_rank BETWEEN 1 AND 5),
    CONSTRAINT chk_agent_discovery_recommendation_hash
        CHECK (response_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_agent_discovery_recommendation_reason
        CHECK (
            CHAR_LENGTH(TRIM(match_reason)) BETWEEN 1 AND 300
        ),
    CONSTRAINT chk_agent_discovery_recommendation_json
        CHECK (
            JSON_TYPE(constraint_coverage_json) = 'ARRAY'
            AND JSON_TYPE(listing_snapshot_json) = 'OBJECT'
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
