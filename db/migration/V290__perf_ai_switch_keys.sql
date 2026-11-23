-- AI load-test drafting switches (B-11): one K8 key per application point, both default off.
--
-- Why this file exists:
--   The perf domain gains two AI points — drafting a scenario from a sentence
--   (wizard prefill, human-confirmed before anything is saved) and a plain-word
--   insight read-out on a finished run's attribution. Cost discipline (7a):
--   every point earns its own switch, closed by default, so no token is spent
--   until an operator opens that exact point. The switch matrix itself lives in
--   core (PerfAiSwitchGate); this file only seeds the rows.
--
-- Value shape: bool '0'/'1'-style rows read as text by the gate — only an
-- explicit "true" opens a point (missing/blank/dirty rows keep it closed).
-- Idempotent via ON CONFLICT DO NOTHING (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.perf.scenario-draft', 'true', 'AI 配置', 'bool', 0,
     '压测 AI 草拟场景分开关：默认关；开启后一句话可生成向导预填草稿，草稿须经人工确认才会保存，调用模型会产生费用'),
    ('ai.perf.insight', 'true', 'AI 配置', 'bool', 0,
     '压测 AI 归因解读分开关：默认关；开启后已完成的压测报告可请求模型生成通俗解读，调用模型会产生费用')
ON CONFLICT (config_key) DO NOTHING;
