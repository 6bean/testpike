-- Migration: independent hot budget for the original-text block injected into
-- case generation (EE batch — the injection stops borrowing the
-- extraction summary envelope).
--
-- Why this file exists:
--   The injected block used to be clamped by BudgetPolicy.summaryBudgetTokens
--   derived from the model window, which produced a 3,750-char envelope on the
--   is an extraction-plane concern; case generation needs its own dial. The
--   reader (OriginalTextBlockAssembler.tokensOrNull) fails closed to this
--   factory value and the core write side validates the same 256~100,000
--   window, so a bad edit is rejected at save time instead of silently
--   shrinking the injected evidence.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.original-block-max-tokens', '8000', '系统', 'string', 0, '生成用例时注入的需求点来源原文最大内容量（token，默认 8000，允许 256~100000；原文超出预算时按上限截断并在用例生成提示中标注截断。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
