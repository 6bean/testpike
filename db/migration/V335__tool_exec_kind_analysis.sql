-- tp_tool.exec_kind value domain extended with the read-only ANALYSIS kind (5).
-- the AI conversation analysis channel registers pure-read
-- analysis functions (coverage summary / assertion density) as ToolDefinition
-- rows so approval, marketplace and invocation auditing stay single-sourced;
-- they are dispatched in the analysis path only and are refused by template
-- evaluation (same fail-closed posture as the GENKIT family).
ALTER TABLE tp_tool DROP CONSTRAINT IF EXISTS ck_tool_exec_kind;
ALTER TABLE tp_tool ADD CONSTRAINT ck_tool_exec_kind CHECK (exec_kind IN (1, 2, 3, 4, 5));
