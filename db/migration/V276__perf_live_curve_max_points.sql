-- Migration: GA-3 — K8 seed for the live-curve point ceiling.
-- The running page polls the curve every 2s; without an incremental cursor + server-side
-- downsampling a 4h soak re-ships up to 14400 raw seconds per poll. The ceiling below is
-- the max points one live-curve response may carry — above it the server collapses seconds
-- into buckets (p95 = bucket max, error rate = request-weighted) and honestly annotates
-- sampled=true. Report pages keep reading the full unsampled series.
-- Values: <10 or >10000 or non-numeric falls back to 600 in PerfConfigReader (a dirty
-- value can never flood the poll nor starve the curve below one screen).
-- Idempotent: ON CONFLICT DO NOTHING.
-- Usage: docker cp db/migration/V276__perf_live_curve_max_points.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V276__perf_live_curve_max_points.sql
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.live-curve.max-points', '600', '系统', 'int', 0,
     '压测实况曲线单次返回最大点数（超出=服务端按秒窗聚合降采样，峰值时延保留并如实标注采样；报告页始终全量口径；非法值回退 600）')
ON CONFLICT (config_key) DO NOTHING;
