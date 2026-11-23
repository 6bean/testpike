
ALTER TABLE tp_perf_scenario ADD COLUMN IF NOT EXISTS sla_json JSONB;

COMMENT ON COLUMN tp_perf_scenario.sla_json IS 'SLA 三门禁+熔断阈值四键（p95Ms 毫秒/errorRate 比率 0-1/minRps req-s/breakerErrorRate 比率 0-1；可子集，NULL=未设=对应判定跳过）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.breaker-error-rate', '0.05', '系统', 'string', 0,
     '压测运行中熔断错误率阈值（比率 0-1，0.05=5%；场景 sla_json.breakerErrorRate 覆写优先）')
ON CONFLICT (config_key) DO NOTHING;
