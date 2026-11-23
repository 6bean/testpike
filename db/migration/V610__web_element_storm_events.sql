-- Migration: WE-S7 A7 revision-storm ledger (package 2 of the observation
-- close-out). An element entering STALE from the single write choke point
-- (WebElementServiceImpl.changeState) drops one row here. tp_web_element
-- .updated_at cannot carry the "went stale at" fact (any unrelated edit
-- re-stamps it), so the storm window is computed over this table only.
-- R57 bounded lifetime at the creation point: every insert prunes rows
-- older than window-hours * 4 in the same call (no scheduler, no growth).
-- The window size and the entry count are hot keys in tp_sys_config
-- (live source; read with clamp + fallback, never hardcoded elsewhere).
CREATE TABLE IF NOT EXISTS tp_web_element_stale_event (
    id           BIGSERIAL PRIMARY KEY,
    element_id   BIGINT      NOT NULL REFERENCES tp_web_element (id) ON DELETE CASCADE,
    project_id   BIGINT      NOT NULL REFERENCES tp_project (id),
    page_key     VARCHAR(200) NOT NULL,
    occurred_at  TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_web_element_stale_event_page
    ON tp_web_element_stale_event (project_id, page_key, occurred_at DESC);

COMMENT ON TABLE tp_web_element_stale_event IS 'One row per element entering the stale state, written by the state-change choke point; rows past the storm horizon are pruned by the same insert (revision-storm aggregation, WE S7 A7).';
COMMENT ON COLUMN tp_web_element_stale_event.occurred_at IS 'Server timestamp of the stale transition; the rolling storm window filters on this column only.';

-- Hot-updatable storm knobs (idempotent replay; tp_sys_config is the live source).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.storm.window-hours', '24', '系统', 'int', 0, '改版事件聚合的时间窗口（小时，可设 1~168，保存即生效）：窗口内同一页面进入陈旧的条目达到门槛数量时视为一次改版事件，并在页面元素库看板给出批量试运行建议'),
('element.storm.entry-min', '3', '系统', 'int', 0, '改版事件判定门槛（可设 2~50，保存即生效）：同一统计窗口或同一统计周期内，同页进入陈旧（或标记将坏）的条目数达到该值才聚合为改版事件，低于该值不预警')
ON CONFLICT (config_key) DO NOTHING;
