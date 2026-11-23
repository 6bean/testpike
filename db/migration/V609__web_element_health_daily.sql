-- Migration: WE-S7 8.3 step-health account (package 1 of the observation
-- close-out). tp_web_element_anchor_stat (V550) is a cumulative account, so
-- a rolling time window needs its own daily rollup: the result-relay hook
-- UPSERTs today's (element, viewport) deltas here and prunes rows past the
-- account horizon in the same step (R57 — creation point paired with a
-- bounded lifetime: horizon = window-days * 4, covering the three windows
-- the drift ladder reads plus one day of slack). Health badges and the
-- "about to break" warning are pure SQL reads over this table, zero model
-- calls. The thresholds and the window size are hot keys in tp_sys_config
-- (live source; read with clamp + fallback, never hardcoded elsewhere).
CREATE TABLE IF NOT EXISTS tp_web_element_health_daily (
    bucket_date DATE        NOT NULL,
    element_id  BIGINT      NOT NULL REFERENCES tp_web_element (id) ON DELETE CASCADE,
    viewport    VARCHAR(32) NOT NULL,
    hits        BIGINT      NOT NULL DEFAULT 0,
    misses      BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT pk_web_element_health_daily PRIMARY KEY (bucket_date, element_id, viewport),
    CONSTRAINT ck_health_daily_hits CHECK (hits >= 0),
    CONSTRAINT ck_health_daily_misses CHECK (misses >= 0)
);

CREATE INDEX IF NOT EXISTS idx_web_element_health_daily_element
    ON tp_web_element_health_daily (element_id, bucket_date DESC);

COMMENT ON TABLE tp_web_element_health_daily IS 'Daily anchor hit/miss rollup per (element, viewport bucket), written by the executor result relay; rows older than the health horizon are pruned by the same hook (WE S7 8.3).';
COMMENT ON COLUMN tp_web_element_health_daily.bucket_date IS 'UTC-free server date bucket (CURRENT_DATE at relay time); the health service reads rolling windows from this column only.';
COMMENT ON COLUMN tp_web_element_health_daily.viewport IS 'Execution bucket label, same vocabulary as tp_web_element_anchor_stat (columnar rule: buckets never merge on write; the health read merges per element by design §8.3).';

-- Hot-updatable health knobs (idempotent replay; tp_sys_config is the live source).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.health.miss-warn', '20', '系统', 'int', 0, '健康列黄色档位起点：最近统计窗口内未命中比例达到该百分比时该行标黄（可设 0~100，保存即生效；低于该值标绿）'),
('element.health.miss-bad', '50', '系统', 'int', 0, '健康列红色档位起点：最近统计窗口内未命中比例达到该百分比时该行标红（可设 0~100，保存即生效；设置低于黄色起点时按黄色起点处理）'),
('element.health.window-days', '7', '系统', 'int', 0, '健康度统计的天数窗口（可设 1~30，保存即生效；未命中比例与连续上升预警都按该窗口分段计算）')
ON CONFLICT (config_key) DO NOTHING;
