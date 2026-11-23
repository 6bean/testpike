-- Migration: A-14 perf run budget margin key seed (K8 hot-updatable, default 10 minutes).
-- Semantics: the dispatch-envelope budget = scenario run duration + this margin; claim hands
--   it to the executor as timeoutMs for task_type=5, and the timeout sweep judges perf rows
--   by it. 0 = no margin (raw duration); negative / non-numeric / missing falls back to 10
--   in PerfConfigReader.budgetMarginMinutes (single point).
-- Idempotent re-run: ON CONFLICT (config_key) DO NOTHING.
-- Usage: docker cp db/migration/V272__perf_budget_margin_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V272__perf_budget_margin_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.budget-margin-minutes', '10', '系统', 'int', 0,
     '压测预算宽限分钟（长跑预算=场景时长+本宽限；0=不加宽限，非法回退 10，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
