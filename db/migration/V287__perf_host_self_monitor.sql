-- Migration V287: host self-monitor landing column + strain threshold key (B-5).
-- Before this segment the report could only blame the target for a flattening
-- curve: nothing recorded whether the load-engine host itself was saturated
-- while pressing, so "driver bottleneck" vs "target bottleneck" was guesswork.
-- The executor now samples its own host (load1 / cores / loadRatio / memAvailMb,
-- and rides the sample onto the flush-window frames; this column
-- persists it per frame. NULL = executor shipped before the sample or the host
-- was unreadable — the boundary note degrades to the honest absence, never a
-- fabricated reading. The K8 key sets the strain ceiling compared against the
-- sampled load ratio (hot-updatable, no hardcoded threshold at the call site).
-- Flyway is not enabled: apply manually to the dev database.
-- Usage: docker cp db/migration/V287__perf_host_self_monitor.sql testpike-postgres:/tmp/ && docker exec -e PGPASSWORD testpike-postgres psql -U testpike -d testpike -f /tmp/V287__perf_host_self_monitor.sql

ALTER TABLE tp_perf_metric ADD COLUMN IF NOT EXISTS host_stats JSONB;

COMMENT ON COLUMN tp_perf_metric.host_stats IS 'Host sample of the flush window that shipped this frame (B-5 self-monitor: {load1,cores,loadRatio,memAvailMb}); NULL = executor predating the sample or unreadable host - the report boundary note degrades honestly instead of guessing.';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.self-monitor.load-ratio', '0.85', '系统', 'string', 0,
     '压测机负载比可疑阈值（默认 0.85，区间 0 以上，非法回退 0.85；压测期间压测机采集到的负载比超过该值时，报告标注驱动侧瓶颈可疑，实测上限可能来自引擎侧而非被测上限；热更即生效）')
ON CONFLICT (config_key) DO NOTHING;
