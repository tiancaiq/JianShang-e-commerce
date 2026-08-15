ALTER TABLE agent_tool_calls
    DROP CHECK chk_agent_tool_calls_name,
    ADD CONSTRAINT chk_agent_tool_calls_name
        CHECK (
            tool_name IN (
                'getListing',
                'retrieveKnowledge',
                'CHECK_AVAILABILITY',
                'SEARCH_INDIVIDUAL',
                'GET_LISTING'
            )
        );
