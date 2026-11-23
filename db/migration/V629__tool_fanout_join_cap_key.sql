-- Migration: read-only tool fan-out join watchdog key (defect A close).
-- Semantic: when the concurrent read-only dispatch route waits for one tool
--   call, it used to wait forever — a hung lookup pinned the whole conversation
--   round with no feedback. Now the wait has an upper bound: past it the single
--   call is cancelled and surfaces as a readable rejection naming the tool and
--   the bound; the other calls' results are returned as usual.
--   ai.tool.fanout-join-cap-seconds = max seconds one fan-out call may occupy
--   the waiting round; values outside 30~7200 fall back to the shipped 600.
-- Read fresh on every round (hot reload, no restart needed), same posture as
-- the two tuning keys seeded by V396.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.tool.fanout-join-cap-seconds', '600', '系统', 'int', 0, '并发只读查询中单个查询的最长等待秒数（默认 600；区间 30~7200，超出按默认 600 处理；超过等待上限的查询会被取消并提示，其余查询结果照常返回，修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
