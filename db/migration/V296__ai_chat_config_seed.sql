-- Migration: K8 config group for the AI dialog transport .
--
-- Why this file exists:
--   The streamed dialog channel needs operator-tunable budgets that never
--   require a redeploy: how long the user may wait for the first frame before
--   the channel reports a degraded error instead of a white screen, how often
--   heartbeat frames keep an otherwise-silent stream visibly alive, the token
--   budget ceiling of one conversation session (E9 accounting reads it), the
--   idle window after which a live stream is considered broken, and the master
--   switch in front of every execution-class dialog action. All five are hot-
--   updated from the config page; the write side guards the intervals in
--   AiChatKeyRanges (K8 convention: no DB CHECK, service layer is the single
--   source) and readers fall back to these seeded defaults on missing/garbage
--   values. exec-enabled ships true (user ruling R32, 2026-10-26/10-29): the
--   dialog is live out of the box; write-class actions still confirm one by one.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.first-frame-budget-seconds', '30', '系统', 'int', 0, '对话首帧等待预算秒数（默认 5；区间 2~30，热更即生效；预算内未收到首个响应帧时连接以错误提示结束，不会无限等待）'),
    ('ai.chat.heartbeat-seconds', '10', '系统', 'int', 0, '对话心跳帧间隔秒数（默认 10；区间 2~60，热更即生效；回答静默期按该间隔发送保活帧，界面据此判断连接仍在工作）'),
    ('ai.chat.session-budget-tokens', '2000000', '系统', 'int', 0, '单个对话会话的 token 预算上限（默认 200000，热更即生效；会话内累计用量超出后本轮不再继续调用模型，用量中心可查已消耗部分）'),
    ('ai.chat.stream-idle-timeout-seconds', '60', '系统', 'int', 0, '对话流空闲判定秒数（默认 60，热更即生效；连续无帧超过该时长即判定本次回答中断，界面给出可重试提示）'),
    ('ai.chat.exec-enabled', 'true', '系统', 'bool', 0, '在线 AI 对话功能总闸（默认开启；关闭后对话中的提问不再回答与执行，在本页打开即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
