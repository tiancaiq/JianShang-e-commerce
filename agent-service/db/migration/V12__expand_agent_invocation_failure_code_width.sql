ALTER TABLE agent_invocations
    MODIFY COLUMN error_code VARCHAR(120) NULL;

ALTER TABLE agent_tool_calls
    MODIFY COLUMN error_code VARCHAR(120) NULL;
