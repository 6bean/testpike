-- Migration: dead-letter auto-replay loop + backlog alert config keys .
-- Semantic: the auto-replay circuit (whitelist failure classes PROVIDER_TRANSIENT /
-- CONFIG_MISSING / LLM_TIMEOUT) and the K2 backlog alert are driven by five hot-update
-- keys, re-read fresh every round by DeadLetterAutoReplayScheduler:
--   mq.dlq.auto-replay.enabled             master switch, seeds OFF — a fresh install
--                                          never reposts anything until an operator
--                                          turns it on deliberately (replay re-executes
--                                          real business work for rows older than 24h).
--   mq.dlq.auto-replay.interval-seconds    cadence floor and the window in which a new
--                                          dead-letter of a class counts as "that
--                                          upstream is still failing" (0 reposts for it).
--   mq.dlq.auto-replay.batch-limit         hard cap of reposts per round (anti replay-storm).
--   mq.dlq.auto-replay.max-rounds          rows replayed this many times that dead-letter
--                                          again are auto-voided, never reposted again.
--   mq.dlq.backlog-alert-threshold         pending rows in the ledger strictly above this
--                                          value fan one MQ_DLQ_BACKLOG letter out per
--                                          system admin per hour.
-- The intervals above are the single source DlqAutoReplayKeyRanges (write guard in
-- ConfigServiceImpl); out-of-range or unparsable values are rejected on write and the
-- reader still falls back to the shipped defaults on absent keys (fail-closed).
-- Backlog counting basis = ledger rows with status pending, not the RabbitMQ queue depth.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('mq.dlq.auto-replay.enabled', '0', '系统', 'int', 0, '死信自动补投回路总开关（默认 0=关：关闭时不自动补投、不自动作废；仅 0 或 1；打开后白名单失败类型（供应商瞬态/配置缺失/LLM 超时）在上游恢复后自动补回原队列；修改即时生效）'),
    ('mq.dlq.auto-replay.interval-seconds', '300', '系统', 'int', 0, '自动补投的最小间隔秒数，同时是上游恢复观察窗：该秒数内同一失败类型仍有新死信则本轮跳过该类型不补投（默认 300 秒；区间 60~86400；修改即时生效）'),
    ('mq.dlq.auto-replay.batch-limit', '50', '系统', 'int', 0, '单轮最多自动补投的消息条数，防止补投风暴（默认 50；区间 1~500；超出部分留待下一轮，修改即时生效）'),
    ('mq.dlq.auto-replay.max-rounds', '3', '系统', 'int', 0, '同一消息自动补投的最大轮次：达到该轮次后再次死信即自动作废并通知管理员，不再补投（默认 3；区间 1~10；修改即时生效）'),
    ('mq.dlq.backlog-alert-threshold', '500', '系统', 'int', 0, '待处理死信（账本未处理数，非队列深度）超过该条数时通知系统管理员，每小时最多一封（默认 500；区间 1~100000；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
