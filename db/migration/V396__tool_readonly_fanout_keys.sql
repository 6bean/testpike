-- Migration: read-only tool fan-out tuning keys (behavior-kernel tenet 10).
-- Semantic: when one conversation round asks for several independent read-only
-- lookups (HTTP reads and analysis functions), those lookups now run at the same
-- time instead of one after another, so the round waits for the slowest one only.
--   ai.tool.fanout-max-parallel   = how many such lookups may run together;
--                                   1 turns the mechanism off (one at a time).
--   ai.tool.fanout-target-ratio   = expected payoff line: with three or more
--                                   lookups in one round the concurrent pass is
--                                   expected to finish within this fraction of
--                                   the one-at-a-time pass; a round that misses
--                                   it is written to the service log with its
--                                   real numbers so the two values above can be
--                                   judged on facts.
-- Both values are read fresh on every round (hot reload, no restart needed);
-- blank / unparsable / out-of-range input falls back to the shipped default.
-- Applied manually to the dev DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.tool.fanout-max-parallel', '4', '系统', 'int', 0, '同一轮里多个只读查询最多几个同时进行（默认 4；填 1 表示恢复逐个查询，填 0 或负数按默认 4 处理，修改即时生效）'),
    ('ai.tool.fanout-target-ratio', '0.6', '系统', 'string', 0, '并行查询耗时相对逐个查询的目标倍数（默认 0.6，即三个及以上只读查询时并行耗时期望不超过逐个耗时的 60%；取值须大于 0 且不超过 1，超出范围按默认 0.6 处理；未达标只记录服务日志供判断，不中断回答，修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
