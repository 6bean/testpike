ALTER TABLE tp_tool DROP CONSTRAINT IF EXISTS ck_tool_exec_kind;
ALTER TABLE tp_tool ADD CONSTRAINT ck_tool_exec_kind CHECK (exec_kind IN (1, 2, 3, 4));
