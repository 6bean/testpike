-- Migration: K8 config key for the dialog case-write draft .
--
-- Why this file exists:
--   When the dialog model asks for a case write (the case.create / case.update
--   tools), the chain never executes it — the user gets a PROPOSAL card under
--   the answer instead. Confirming the card through the shared door is what
--   creates or edits the case. This key gates only the card emission, so it
--   ships ON per the factory-default-open rule for AI features (a draft lands
--   nothing by itself; no production-change exemption applies). The separate
--   repair-draft key stays independent: the two features have different cost
--   faces (an extra LLM call vs. a pure transcript projection) and closing
--   one must not silence the other. When switched off, the answer still
--   arrives and the refusal line names this setting and key (guidance rule).
--   The write side guards the bool face in AiChatKeyRanges (K8 convention:
--   no DB CHECK, service layer is the single source); readers treat anything
--   but "true" as off (fail-closed on spend, honest guidance on closure).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.case-draft.enabled', 'true', '系统', 'bool', 0, 'AI 用例草案开关（默认开启；对话中 AI 提出新建或修改用例时附上草案卡，草案需人工确认才会写入用例库；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
