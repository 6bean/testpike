-- Migration: per-domain hot switches for the exhaustive-recall clause block
-- on the four remaining enumeration faces : spec-contract
-- extraction, judge question generation, stage-0 scenario enumeration and
-- mock-draft generation. The requirement-extraction face already ships its
-- switch (ai.doc.recall-directive.enabled, V492); this seed makes the four
-- new knobs visible and editable on the configuration page — it is not a
-- second default-value source (the readers stay fail-soft ON in code).
--
-- Behaviour notes (same posture as V492):
--   * a missing, blank or illegal value keeps the switch on and logs a warn
--     naming the key; only false/0 turn the recall section off;
--   * flipping a switch changes the prompt text on that face;
--   * each domain owns its own key — values are never borrowed across
--     domains (A3 rule of the reuse contract).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.spec.recall-directive.enabled', 'true', '系统', 'bool', 0, '接口契约提取的穷尽召回指令开关（默认开启；开启后提示要求逐块挖掘每一条可测试行为与约束、不把独立条目合并成要点、条数不设上限、无可提取内容时显式声明空块。值缺失或不合规时按开启处理并在日志点名本键。改为 false 后提示恢复旧口径。保存即生效，无需重启）'),
    ('ai.judge.recall-directive.enabled', 'true', '系统', 'bool', 0, '评测集造题的穷尽召回指令开关（默认开启；开启后提示要求逐块挖掘每一条可出题行为、不把独立知识点合并、题量不设上限、无题可出时显式声明空块。值缺失或不合规时按开启处理并在日志点名本键。改为 false 后提示恢复旧口径。保存即生效，无需重启）'),
    ('ai.gen.scenario-recall-directive.enabled', 'true', '系统', 'bool', 0, '场景枚举的穷尽召回指令开关（默认开启；开启后提示要求逐条挖掘需求的可测行为并独立成场景、场景数不设上限、无场景可枚举时显式声明空块。值缺失或不合规时按开启处理并在日志点名本键。改为 false 后提示恢复旧口径。保存即生效，无需重启）'),
    ('ai.mock.recall-directive.enabled', 'true', '系统', 'bool', 0, 'Mock 规则起草的穷尽召回指令开关（默认开启；开启后提示要求逐条挖掘需求描述内的接口行为并独立成规则、规则数不设上限、无可起草内容时显式声明空块。值缺失或不合规时按开启处理并在日志点名本键。改为 false 后提示恢复旧口径。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
