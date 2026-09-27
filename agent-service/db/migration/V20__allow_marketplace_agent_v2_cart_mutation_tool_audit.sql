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
                'check_availability',
                'search_listings',
                'get_listing',
                'get_my_cart',
                'list_my_orders',
                'get_my_order',
                'add_to_my_cart',
                'update_my_cart_quantity',
                'remove_from_my_cart'
            )
        );
