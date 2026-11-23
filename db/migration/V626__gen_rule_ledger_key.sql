-- Migration: rule-ledger reconciliation hot switch .
--
-- Why this file exists:
--   The stage-1 faithfulness gate only guards against written WRONG (claims
--   that contradict the source); a section rule table can still be written
--   MISSING entirely and the draft passes. This key opens the per-item
--   rule ledger: anchored table-row rules are mechanically extracted from
--   the SAME original-text window the prompt injects, reconciled item by
--   item against the generated case text by semantic similarity, and the
--   uncovered ones become named disputes ("rule item not covered, source
--   chunk #N") that ride the existing regeneration feedback — never a
--   second loop. Factory OFF: an absent or false row keeps the outbound
--   prompts byte-for-byte the legacy shape with zero extra reads; storing
--   "true" enables, flipping back restores the old behaviour immediately.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.rule-ledger.enabled', 'false', '系统', 'bool', 0, '用例生成规则账本对账开关（默认关闭；开启后逐条核对需求原文中的字段规则表是否在生成用例里落地，未覆盖的规则会点名并要求补充操作步骤与断言步骤，已覆盖的零影响；改回 false 立即恢复旧生成行为，不额外读取原文分块）')
ON CONFLICT (config_key) DO NOTHING;
