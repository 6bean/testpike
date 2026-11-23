-- Over-budget self-heal keys (dialog context safety net): the switch arms the
-- pre-flight fold line and the provider-400 summary retry ring; the ratio sets
-- how early the fold fires against the routed endpoint's prompt limit. Both are
-- read per call (hot update, no restart). Ships ON per the AI-feature default
-- posture — with the switch off, oversized packets ride the legacy hard-limit
-- behavior unchanged.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.ctx.over-budget-heal.enabled', 'true', '系统', 'string', 0, '对话上下文自愈开关（默认开）：开启后，达到所选模型提示词上限预设比例的对话轮次会先把更早内容折叠为要点再发送；供应商以400拒绝超长时自动折叠重试一次。关闭时按原上限直发，超长请求会被供应商拒绝。修改即时生效'),
    ('ai.ctx.heal-preflight-ratio', '95', '系统', 'int', 0, '上下文自愈预拦线：对话包达到所选模型提示词上限该百分比时即先行折叠更早轮次（默认 95；区间 10~100；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
