-- Migration: GA-3 companion — seed the coverage-tolerance K8 key that was hand-added
-- to the fresh-install bootstrap in an earlier segment without a migration file (drift found
-- while regenerating db/schema-init.sql for V276). Seeding it here puts the key back on the
-- authoritative migration stream so new installs and this dev database agree with the
-- code-side default (PerfConfigReader.DEFAULTS) instead of silently relying on fallback.
-- Semantics: how many tail seconds of a finished run may be un-stored before the terminal
-- coverage check annotates the curve with tail loss — the flush window still in flight when
-- the engine exits is not data loss. Range 0..600; a dirty value falls back to 60.
-- Idempotent: ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V277__perf_coverage_tolerance_key.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V277__perf_coverage_tolerance_key.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.frames-coverage-tolerance-seconds', '60', '系统', 'int', 0,
     '压测运行终态尾秒容差秒（回传收尾在途秒数不计为采样缺失；超出即曲线标注尾部缺失，区间 0~600，非法回退 60）')
ON CONFLICT (config_key) DO NOTHING;
