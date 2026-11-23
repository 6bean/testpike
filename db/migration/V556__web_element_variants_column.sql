-- V556__web_element_variants_column.sql — cross-viewport variants column (WE batch S4 prep, design 8.2).
-- A ledger row's top-level anchors stay the any/default viewport set; this
-- column carries per-viewport-class anchor sets so a responsive page keeps
-- one asset per element instead of one row per breakpoint class.
--   variants: [{ "viewport": "mobile|tablet|desktop", "anchors": [ ... ] }]
--   Empty array = single-viewport asset: every existing read/write path and
--   the dispatch snapshot behave exactly as before (zero behaviour drift for
--   stored rows).
ALTER TABLE tp_web_element
    ADD COLUMN IF NOT EXISTS variants JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE tp_web_element
    DROP CONSTRAINT IF EXISTS ck_web_element_variants_array;
ALTER TABLE tp_web_element
    ADD CONSTRAINT ck_web_element_variants_array
    CHECK (jsonb_typeof(variants) = 'array');

COMMENT ON COLUMN tp_web_element.variants IS 'Per-viewport-class anchor sets: [{viewport: mobile|tablet|desktop, anchors: ordered anchor array}]; empty array means the element is a single-viewport asset and the top-level anchors apply to every viewport.';

-- Hot-updatable key for the survey collection classes (design 8.2 collection
-- rule). The survey step re-collects the candidate pool once per declared
-- class; an environment profile that declares no viewport class keeps the
-- default tier only and stores an empty variants array.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.viewport-classes', 'mobile,tablet,desktop', '系统', 'string', 0, '整页测绘时逐档重采并装配变体锚集的视口档清单（逗号分隔，可填 mobile、tablet、desktop 的组合；被测环境档案未声明视口档时只采当前默认档，变体清单保持为空；改档后下一次测绘即生效）')
ON CONFLICT (config_key) DO NOTHING;
