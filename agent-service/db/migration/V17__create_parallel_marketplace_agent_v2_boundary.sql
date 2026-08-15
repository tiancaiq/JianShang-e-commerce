ALTER TABLE agent_sessions
    DROP CHECK chk_agent_sessions_type,
    DROP CHECK chk_agent_sessions_subject,
    ADD COLUMN marketplace_agent_v2_open_marker TINYINT
        GENERATED ALWAYS AS (
            CASE
                WHEN session_type = 'MARKETPLACE_AGENT_V2' AND status = 'OPEN'
                THEN 1
                ELSE NULL
            END
        ) STORED AFTER discovery_open_marker,
    ADD UNIQUE KEY uq_agent_marketplace_v2_open_actor (
        actor_user_id,
        marketplace_agent_v2_open_marker
    ),
    ADD CONSTRAINT chk_agent_sessions_type
        CHECK (
            session_type IN (
                'LISTING_CUSTOMER_SERVICE',
                'MARKETPLACE_DISCOVERY',
                'MARKETPLACE_AGENT_V2'
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
            OR (
                session_type = 'MARKETPLACE_AGENT_V2'
                AND subject_type IS NULL
                AND subject_listing_id IS NULL
                AND preference_state_json IS NOT NULL
                AND JSON_TYPE(preference_state_json) = 'OBJECT'
                AND clarification_turn_count = 0
                AND clarification_question_count = 0
            )
        );

ALTER TABLE agent_tool_calls
    DROP CHECK chk_agent_tool_calls_name,
    ADD CONSTRAINT chk_agent_tool_calls_name
        CHECK (
            tool_name IN (
                'getListing',
                'retrieveKnowledge',
                'CHECK_AVAILABILITY',
                'SEARCH_INDIVIDUAL',
                'GET_LISTING',
                'search_listings',
                'get_listing'
            )
        );
