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
                'remove_from_my_cart',
                'prepare_my_checkout',
                'get_my_checkout',
                'submit_my_checkout',
                'preview_my_order_cancellation',
                'cancel_my_order',
                'get_my_return',
                'prepare_my_return_request',
                'submit_my_return_request',
                'retrieve_help'
            )
        );
