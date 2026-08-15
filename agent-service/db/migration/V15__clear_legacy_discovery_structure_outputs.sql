DELETE t
FROM agent_tool_calls t
JOIN agent_invocations i ON i.invocation_id = t.invocation_id
JOIN agent_messages m ON m.message_id = i.assistant_message_id
WHERE i.result_status = 'FAILED'
  AND i.error_code = 'DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA'
  AND m.role = 'ASSISTANT'
  AND m.resolution_type = 'HANDOFF'
  AND m.body = 'I could not safely structure the discovery result. Please refine the search.';

DELETE r
FROM agent_discovery_recommendations r
JOIN agent_invocations i ON i.invocation_id = r.invocation_id
JOIN agent_messages m ON m.message_id = i.assistant_message_id
WHERE i.result_status = 'FAILED'
  AND i.error_code = 'DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA'
  AND m.role = 'ASSISTANT'
  AND m.resolution_type = 'HANDOFF'
  AND m.body = 'I could not safely structure the discovery result. Please refine the search.';

UPDATE agent_invocations i
JOIN agent_messages m ON m.message_id = i.assistant_message_id
SET i.assistant_message_id = NULL,
    i.optimistic_version = i.optimistic_version + 1
WHERE i.result_status = 'FAILED'
  AND i.error_code = 'DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA'
  AND m.role = 'ASSISTANT'
  AND m.resolution_type = 'HANDOFF'
  AND m.body = 'I could not safely structure the discovery result. Please refine the search.';

DELETE m
FROM agent_messages m
LEFT JOIN agent_invocations i ON i.assistant_message_id = m.message_id
WHERE i.invocation_id IS NULL
  AND m.role = 'ASSISTANT'
  AND m.resolution_type = 'HANDOFF'
  AND m.body = 'I could not safely structure the discovery result. Please refine the search.';
