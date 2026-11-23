-- Flip the failed-case auto-repair master switch to the shipped default "on".
-- Every AI-driven feature switch is enabled out of the box so a fresh install
-- needs zero configuration; the off-state path keeps its honest guidance
-- behaviour (rejection carries the settings location and the parameter name).
-- Idempotent: only rows still holding a factory-off value are touched, user
-- settings stored in any other form are left as they are.
UPDATE tp_sys_config
SET config_value = '1'
WHERE config_key = 'ai.repair.enabled'
  AND config_value IN ('0', 'false');

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
SELECT 'ai.repair.enabled', '1', '系统', 'int', 0,
       '失败自动修复主开关（默认开启；关闭后不再产生修复提案，也不改动脚本版本；仅支持 0/1，在本页打开即时生效）'
WHERE NOT EXISTS (SELECT 1 FROM tp_sys_config WHERE config_key = 'ai.repair.enabled');
