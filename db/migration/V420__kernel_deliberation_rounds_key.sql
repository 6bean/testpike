-- Deliberation rounds key (reasoning confirmation ring): how many
-- propose → verify → revise passes a reasoning judgement runs through before
-- it is surfaced as a draft or preview. Ships at 2, range 1..3; each feature
-- may clamp it down but never above this base. Read per call (hot update, no
-- restart). Cost note: batch estimates multiply this value into their token
-- projection, and the session budget guard keeps multi-round spend visible.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.kernel.deliberation-rounds', '2', '系统', 'int', 0, '推理确认轮数：推理判断先经「提议→逐断言核对证据→定向修订」多轮确认再出草案，此处设置核对轮数（默认 2；区间 1~3；各功能只能向下调；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
