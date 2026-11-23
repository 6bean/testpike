-- Migration: A-12 companion — seed the VU-budget coefficient key. Generated k6
-- scripts size preAllocatedVUs as ceil(rate x this) for every arrival-rate executor;
-- the code-side default (PerfConfigReader.DEFAULTS) stays 1 = the historical 1:1 shape,
-- so existing scenarios compile identically until an operator raises the factor.
-- Semantics: virtual users pre-allocated per arrival-rate unit (RPS). Range 1..10;
-- a dirty/out-of-range value falls back to 1 (a mistyped ceiling can never explode
-- press-box memory nor under-provision throttled traffic that fakes the declared rate).
-- Idempotent: ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V278__perf_vu_per_rps_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V278__perf_vu_per_rps_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.vu-per-rps', '1', '系统', 'int', 0,
     '压测虚拟用户系数（每 1 RPS 预分配的虚拟用户数，区间 1~10，非法回退 1；生成脚本按速率×系数预置 VU，恒定/爬坡/脉冲三形态同源生效）')
ON CONFLICT (config_key) DO NOTHING;
