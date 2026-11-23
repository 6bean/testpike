-- V551__web_element_repair_suggestion.sql — Web-shape element repair suggestions
-- (WE batch S2 . The A9 suggestion board gains an object dimension:
--   * APP rows keep proposing a step-target patch inside one case script;
--   * WEB rows propose a replacement locator anchor for one tp_web_element
--     entry, so one approved anchor fix serves every referencing case
--     (design 2.3 red line: equivalent re-location only, never an action edit).
-- Column defaults keep every existing row and caller on the APP shape
-- (zero migration, zero drift on the A9 board).
ALTER TABLE tp_appauto_step_suggestion ADD COLUMN IF NOT EXISTS object_type VARCHAR(8) NOT NULL DEFAULT 'APP';
ALTER TABLE tp_appauto_step_suggestion ADD COLUMN IF NOT EXISTS element_id BIGINT NULL;
ALTER TABLE tp_appauto_step_suggestion ADD COLUMN IF NOT EXISTS adopted_element_revision INT NULL;

-- WEB rows carry no action change, so proposed_action turns nullable and the
-- per-dimension shape rule moves into one paired CHECK (single gate, DB side).
ALTER TABLE tp_appauto_step_suggestion ALTER COLUMN proposed_action DROP NOT NULL;

ALTER TABLE tp_appauto_step_suggestion DROP CONSTRAINT IF EXISTS ck_step_suggestion_object_type;
ALTER TABLE tp_appauto_step_suggestion ADD CONSTRAINT ck_step_suggestion_object_type
    CHECK (object_type IN ('APP', 'WEB'));

ALTER TABLE tp_appauto_step_suggestion DROP CONSTRAINT IF EXISTS ck_step_suggestion_shape;
ALTER TABLE tp_appauto_step_suggestion ADD CONSTRAINT ck_step_suggestion_shape CHECK (
    (object_type = 'APP' AND proposed_action IS NOT NULL AND proposed_target IS NOT NULL)
    OR (object_type = 'WEB' AND element_id IS NOT NULL AND proposed_target IS NOT NULL));

-- Provenance domain gains the Web-family value "dom" (anchor rebuilt from a
-- fresh DOM snapshot after a redesign). Java single source =
-- AppautoAiSampleServiceImpl.HIT_SOURCES; the sample board column hit_source
-- carries no DB CHECK (domain is service-side only), so only the suggestion
-- table CHECK is extended here.
ALTER TABLE tp_appauto_step_suggestion DROP CONSTRAINT IF EXISTS ck_appauto_step_suggestion_source;
ALTER TABLE tp_appauto_step_suggestion ADD CONSTRAINT ck_appauto_step_suggestion_source
    CHECK (source IN ('vision', 'a11y', 'cache', 'manual', 'dom'));

COMMENT ON COLUMN tp_appauto_step_suggestion.object_type IS 'Object dimension: APP step-target patch / WEB element anchor replacement (WE S2)';
COMMENT ON COLUMN tp_appauto_step_suggestion.element_id IS 'WEB rows: target tp_web_element id (soft reference, no FK, same posture as case_id)';
COMMENT ON COLUMN tp_appauto_step_suggestion.adopted_element_revision IS 'WEB rows: ledger revision after the approved anchor was merged in (optimistic-lock trail, mirrors adopted_script_version)';

-- Board queries now filter by dimension; keep the old case board index untouched.
CREATE INDEX IF NOT EXISTS ix_appauto_step_suggestion_object_board
    ON tp_appauto_step_suggestion (project_id, object_type, status, created_at DESC);

-- auto-apply when strong anchor equivalence AND the visual similarity of the
-- hit screenshot against the ledger evidence crop both pass; this key is the
-- similarity floor. Consumed by the dispatch envelope (WE .
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.re-anchor.similarity-floor', '0.85', '系统', 'string', 0, '自动直更新锚所需的视觉相似度下限（默认 0.85，取值范围 0~1，填 1 表示禁止一切自动直更、全部走元素修复建议单）。重锚命中元素截图与基线裁剪区相似度达到该值且锚身份等价两项同时满足才免批直更，任一项不足一律出建议单等人工批准；值超出 0~1 或不是数字时保存会被拒绝，配置缺失或非法时按默认 0.85 执行；保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
