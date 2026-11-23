-- Migration: hot-update keys for the live process-event stream (tail-follow face).
--
-- Why this file exists:
-- segment 2b opens one SSE stream per open drawer so a multi-hour extraction
--   reads like a live log. The pump's pacing (heartbeat frames, cursor polls,
--   task-state probe, quiet give-up) must be tunable without a rebuild, on the
--   same hot-key surface as ai.stream.chunk-size seeded by V460.
-- Behaviour notes:
--   * every reader falls back to the same default when the row is missing or
--     malformed, so these seeds only make the knobs visible and editable in the
--     configuration page — no second default-value source;
--   * the idle timeout is a last-resort guard: a run that legitimately stays
--     quiet (one long model call) simply reopens from the last cursor it saw.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.stream.heartbeat-seconds', '10', '系统', 'int', 0, '实时过程详情的保活帧间隔秒数（默认 10，允许 2~60）。静默期按该间隔发送保活帧，界面据此判断连接仍在工作。保存即生效，无需重启'),
    ('ai.stream.poll-interval-ms', '200', '系统', 'int', 0, '实时过程详情读取新事件的间隔毫秒数（默认 200，允许 50~5000）。数值越小越即时、请求越频繁。保存即生效，无需重启'),
    ('ai.stream.status-probe-interval-ms', '1000', '系统', 'int', 0, '实时过程详情核对任务状态的间隔毫秒数（默认 1000，允许 200~10000）。任务结束后实时通道自动收尾关闭。保存即生效，无需重启'),
    ('ai.stream.idle-timeout-seconds', '300', '系统', 'int', 0, '实时过程详情连续无新事件的放弃秒数（默认 300，允许 5~3600）。超时后通道提示断开并关闭，重新打开可从上次读到的位置继续。保存即生效，无需重启')
ON CONFLICT (config_key) DO NOTHING;
