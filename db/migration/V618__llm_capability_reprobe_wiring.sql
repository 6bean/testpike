-- Capability-ledger reprobe wiring (ticket-② : the ledger write-back
-- SQL shipped in already stamps last_probed_at on every probe result
-- (promotion and re-confirmation), but the V615 table has no such column --
-- without it those writes fail soft and the reprobe round cannot tell which
-- rows fell out of its interval window. This column makes the promotion loop
-- schedulable: rows at enforced = 0 become due after the interval elapses.
--
-- The two seed keys feed the reprobe scheduler's cost guard: the interval
-- decides when an unenforced row is asked again, the daily budget bounds real
-- provider calls per day. Both are read every tick (hot update, no restart).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

ALTER TABLE tp_llm_output_capability
    ADD COLUMN IF NOT EXISTS last_probed_at timestamp with time zone;

COMMENT ON COLUMN tp_llm_output_capability.last_probed_at IS
    'When a live probe last checked this row (NULL = learned from traffic, never probed); the reprobe scheduler steers on it.';

CREATE INDEX IF NOT EXISTS idx_llm_capability_reprobe_due
    ON tp_llm_output_capability (enforced, last_probed_at);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.llm.capability-reprobe-interval-hours', '24', '模型', 'int', 0,
     '结构化输出能力复探间隔小时数（默认 24，区间 1~168；能力台账中「接受但不强制」的行超过该时长后由系统定时向端点复核一次，复核通过自动转正，无需人工操作；修改即时生效，无需重启）'),
    ('ai.llm.capability-reprobe-daily-max', '20', '模型', 'int', 0,
     '结构化输出能力复探每日调用上限（默认 20，区间 1~200；控制每天向模型端点发起的真实复核调用数量，达到上限后未轮到的行顺延次日，防止额外模型开销；修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
