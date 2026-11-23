-- Migration: CF-1 companion — seed the SLO-safe watermark ratio key for the
-- ceiling-search report. A LADDER (staircase) run groups its seconds back into steps,
-- judges each step with the three SLA gates, and recommends the last passing step's
-- nominal rate x this ratio as the safe operating watermark ("back off 20% from
-- proven-good load" at the default). Semantics: ratio in (0,1]; dirty/out-of-range
-- values fall back to 0.8 (a mistyped value can never recommend operating at or above
-- the load that just breached). Idempotent: ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V280__perf_ceiling_watermark_ratio_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V280__perf_ceiling_watermark_ratio_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.ceiling.safe-watermark-ratio', '0.8', '系统', 'string', 0,
     '寻顶报告 SLO 安全水位系数（区间 0~1，默认 0.8=末级通过台阶名义速率×本系数作为建议运行水位；非法值回退 0.8，热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
