-- Migration V283: scenario trend read window key perf.trend.max-runs .
-- Trend read window (K8 discipline, hot-updatable): how many newest terminal runs one
-- scenario-trend request covers. Default 200 bounds a thousand-run scenario from exploding
-- the read; values below 1, dirty or missing rows fall back to 200 (a mistyped value can
-- never switch the window off).
-- Usage: docker cp db/migration/V283__perf_trend_max_runs_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V283__perf_trend_max_runs_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.trend.max-runs', '200', '系统', 'int', 0,
     '压测场景趋势读数窗口（默认 200，区间 1 以上，非法回退 200；趋势页只取最近 N 次终态运行，更早的运行不显示；热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
