-- Migration: K8 config key group for the scheduled MCP knowledge sync .
--
-- Why this file exists:
--   The knowledge sync loop needs operator-tunable budgets that never require a
--   redeploy: the master switch in front of every scheduled scan, the default
--   cadence applied to a subscription saved without an explicit interval, the
--   per-round cap that keeps one scan from flooding the landing pipeline, and
--   the consecutive-failure count after which a subscription is auto-paused
--   with an admin alert (K2). All four are hot-updated from the config page;
--   the write side guards intervals/bools in AiMcpSyncKeyRanges (K8 convention:
--   no DB CHECK, the service layer table is the single source) and readers
--   fall back to these seeded defaults on missing/garbage values (fail-closed).
--   enabled ships true (user ruling R32, 2026-10-26): sync works out of the
--   box; landed entries still enter the ledger as pending-activation rows, so
--   the manual-confirm red line for AI writes is untouched by the automation.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.mcp.sync.enabled', 'true', '系统', 'bool', 0, 'MCP 知识库定时同步总开关（默认开启；关闭后到期订阅不再自动同步，可在系统设置→系统配置页将本项改回 true 即时生效，无需重启）'),
    ('ai.mcp.sync.interval-minutes', '1440', '系统', 'int', 0, '新建同步订阅的默认间隔分钟数（默认 1440；区间 1~43200，热更即生效；订阅表单未显式填写间隔时取本值落库）'),
    ('ai.mcp.sync.max-entries-per-round', '200', '系统', 'int', 0, '单轮同步扫描最多执行的订阅数（默认 200；区间 1~5000，热更即生效；超出上限的到期订阅顺延到下一轮执行）'),
    ('ai.mcp.sync.failure-threshold', '3', '系统', 'int', 0, '连续失败触发告警的轮数阈值（默认 3；区间 1~20，热更即生效；订阅连续失败达到该轮数后自动暂停并通知管理员，排查后可在订阅列表手动恢复）')
ON CONFLICT (config_key) DO NOTHING;
