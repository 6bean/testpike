-- Migration: K8 config key for the dialog repair-proposal draft .
--
-- Why this file exists:
--   When a question arrives through a failure badge and the diagnosis is on
--   the table, the dialog turn now attaches a repair PROPOSAL card (draft
--   frame) under the answer. The card itself lands nothing — writing to the
--   script needs the confirmation chain. This key gates only the proposal
--   generation (one extra LLM call per diagnosed question), so it ships ON
--   per the factory-default-open rule for AI features; the cost stays
--   observable in the call ledger. When switched off, badge questions are
--   still answered, and the refusal line names this setting and key so the
--   admin can find it.
--   The write side guards the bool face in AiChatKeyRanges (K8 convention:
--   no DB CHECK, service layer is the single source); the reader falls back
--   to "off is the only special value" — any missing/garbage value reads as
--   not-true and takes the honest guidance path (fail-closed on spend).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.repair-draft.enabled', 'true', '系统', 'bool', 0, 'AI 失败修复草案开关（默认开启；从失败徽标进入的提问在给出分析的同时附上一张修复草案卡，草案需人工确认才会写入脚本新版本；关闭时提问照常回答，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
