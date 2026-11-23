-- V631: seed the executor-pool autoscaler guardrail keys (fleet scale decision inputs).
-- Every row is an operator-tunable shipped default consumed at call time by the scale
-- settings reader; retuning needs no rebuild. The reader's compiled fallbacks equal these
-- values on purpose, so a database without the rows behaves exactly like a fresh install.
-- Idempotent: ON CONFLICT DO NOTHING. Manual apply (Flyway not enabled) via
-- tools/ops/apply_slot_migration.sh; regenerate db/schema-init.sql afterwards.
-- value_type int rows parse as integers; the utilization bound is a 0.01..1 decimal.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('cluster.autoscale-capacity-per-executor', '2', 'APP自动化', 'int', 0,
     '单个执行器并发任务数（期望副本测算的分母；默认 2，与执行器安装声明容量一致）'),
    ('cluster.autoscale-min-replicas', '1', 'APP自动化', 'int', 0,
     '池最少副本数（默认 1；调 0 允许空闲时缩到零实例，恢复派发时会自动拉起）'),
    ('cluster.autoscale-max-replicas', '8', 'APP自动化', 'int', 0,
     '池最多副本数（默认 8；期望副本数不会越过该上限）'),
    ('cluster.autoscale-p95-wait-threshold-seconds', '300', 'APP自动化', 'int', 0,
     '待派发等待时长 P95 加压线秒（默认 300；等待超过该线才按加压档追加副本）'),
    ('cluster.autoscale-cooldown-seconds', '120', 'APP自动化', 'int', 0,
     '两次伸缩动作之间的冷却秒（默认 120；冷却窗内不再重复伸缩，调 0 关闭冷却）'),
    ('cluster.autoscale-paused', '0', 'APP自动化', 'int', 0,
     '自动伸缩暂停开关（0=自动伸缩开启 1=保持当前副本不自动伸缩；暂停决定会写进伸缩事件原因）'),
    ('cluster.budget-max-utilization', '0.85', 'APP自动化', 'float', 0,
     '预算水位上限比例（0.01~1 小数，默认 0.85；用量超过该线时自动伸缩不再加副本）'),
    ('cluster.budget-pause-enabled', '1', 'APP自动化', 'int', 0,
     '预算保护开关（1=预算超限阻止扩容并写明原因 0=不阻止；默认开启）')
ON CONFLICT (config_key) DO NOTHING;
