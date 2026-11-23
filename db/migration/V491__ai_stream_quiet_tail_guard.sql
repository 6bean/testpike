-- Migration: honest quiet guards for the live process-event stream (tail face).
--
-- Why this file exists:
--   The tail pump used to give up the moment the event ledger stayed quiet
--   past ai.stream.idle-timeout-seconds and told the viewer the stream had
--   broken. Long model calls legitimately idle for minutes, so that frame
--   was a false disconnect. The pump now treats quiet as a milestone: it
--   lands a step-level "still running" frame and keeps tailing, bounded by
--   a per-window cap so an abandoned drawer cannot park threads forever.
-- Behaviour notes:
--   * readers fall back to the same default when the row is missing or
--     malformed — these seeds only make the knobs visible and editable on
--     the configuration page (no second default-value source);
--   * the window cap is per connection, not per job: reconnecting starts a
--     fresh window from the last cursor the viewer already saw.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.stream.tail-max-seconds', '3600', '系统', 'int', 0, '实时过程详情单次打开后持续跟随的总时长上限秒数（默认 3600，允许 10~86400）。达到上限后本窗提示结束并关闭，重新打开可从上次读到的位置继续跟随。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;

UPDATE tp_sys_config SET description =
    '实时过程详情连续无新事件时的里程碑提示间隔秒数（默认 300，允许 5~3600）。任务未结束而通道持续静默达到该间隔时，只提示一条「任务仍在进行」并继续跟随，不会因此断开。保存即生效，无需重启'
    WHERE config_key = 'ai.stream.idle-timeout-seconds';
