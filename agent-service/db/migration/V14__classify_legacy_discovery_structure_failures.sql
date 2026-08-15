UPDATE agent_invocations i
JOIN agent_messages m
  ON m.message_id = i.assistant_message_id
SET i.result_status = 'FAILED',
    i.error_code = 'DISCOVERY_ORCHESTRATOR_RUN_FAILED_STRUCTURED_RESPONSE_PARSE_RESPONSE_SCHEMA',
    i.optimistic_version = i.optimistic_version + 1
WHERE i.result_status = 'SUCCEEDED'
  AND i.error_code IS NULL
  AND m.role = 'ASSISTANT'
  AND m.resolution_type = 'HANDOFF'
  AND m.body = 'I could not safely structure the discovery result. Please refine the search.';
