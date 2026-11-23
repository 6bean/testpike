-- V550__web_element_anchor_stat.sql — per-(viewport, anchor-type) anchor hit
-- ledger statistics (WE batch S1 . The executor reports one outcome
-- row per ordered anchor try (03 2.4 result trailing key elementAnchorHits);
-- core keeps counts columnar per bucket+type so a desktop hit can never
-- inflate a broken mobile anchor (design §8 columnar rule, two-weights
-- discipline), and recomputes tp_web_element.confidence as the smoothed hit
-- rate (hits + w/2) / (hits + misses + w) — prior weight w from the hot key
-- below; prior mean 0.5 equals the ledger's neutral starting confidence, so
-- young rows without feedback keep their 0.5. R57: rows are bounded by the
-- element FK (ON DELETE CASCADE) — retiring an element drops its stat rows.
CREATE TABLE IF NOT EXISTS tp_web_element_anchor_stat (
    element_id  BIGINT       NOT NULL REFERENCES tp_web_element (id) ON DELETE CASCADE,
    viewport    VARCHAR(32)  NOT NULL,
    anchor_type VARCHAR(32)  NOT NULL,
    hits        BIGINT       NOT NULL DEFAULT 0,
    misses      BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT pk_web_element_anchor_stat PRIMARY KEY (element_id, viewport, anchor_type),
    CONSTRAINT ck_anchor_stat_hits CHECK (hits >= 0),
    CONSTRAINT ck_anchor_stat_misses CHECK (misses >= 0)
);

COMMENT ON TABLE tp_web_element_anchor_stat IS 'Anchor hit/miss counts per (element, viewport bucket, anchor type), fed by executor result relays (WE slice 3b).';
COMMENT ON COLUMN tp_web_element_anchor_stat.viewport IS 'Execution bucket label (e.g. 1440x900); columnar accounting keeps buckets from mixing.';
COMMENT ON COLUMN tp_web_element_anchor_stat.anchor_type IS 'Anchor class tried (css|xpath|text|placeholder|alt|testid|rel); __truncated__ sentinel rows are never stored.';

-- Hot-updatable smoothing prior (idempotent replay; tp_sys_config is the live source).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.confidence-smooth-prior-weight', '4', '系统', 'int', 0, '页面元素库置信度的平滑权重（取值范围 0-1000；数值越大历史先验影响越久，数值越小近期命中与失手越快改变置信度；置信度=按该权重平滑后的命中率，无人工反馈时保持缺省 0.5）')
ON CONFLICT (config_key) DO NOTHING;
