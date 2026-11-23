
CREATE TABLE IF NOT EXISTS tp_script_repair (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT       NOT NULL,
    case_id          BIGINT       NOT NULL,
    source_task_id   BIGINT,
    trial_task_id    BIGINT,
    round            SMALLINT     NOT NULL,
    proposal_summary TEXT,
    outcome          SMALLINT     NOT NULL,
    fail_note        TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_script_repair_case_time ON tp_script_repair (case_id, created_at DESC);

COMMENT ON TABLE  tp_script_repair IS 'B4 失败自动修复记录（T261）：提案→试运行验证决策留痕；轮次硬闸 24h 窗读路径同源';
COMMENT ON COLUMN tp_script_repair.source_task_id IS '触发修复的失败执行任务 ID（tp_exec_task.id）';
COMMENT ON COLUMN tp_script_repair.trial_task_id  IS '验证修复提案的试运行任务 ID（闸外跳过链路可空）';
COMMENT ON COLUMN tp_script_repair.round          IS '该用例第几轮修复（24h 窗内序；达 max-rounds=硬停转人工）';
COMMENT ON COLUMN tp_script_repair.outcome        IS '1=过已落新版本(版本线 reason=AI修复) 2=未过回滚交人工 3=闸外跳过留痕';
COMMENT ON COLUMN tp_script_repair.fail_note      IS '失败/跳过写明原因（交人工排查源；超长截断写入侧保证）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('ai.repair.enabled', '1', '系统', 'int', 0, '失败自动修复主开关（默认开启；关闭后不再产生修复提案，也不改动脚本版本；仅支持 0/1，在本页打开即时生效）')
ON CONFLICT (config_key) DO NOTHING;

UPDATE tp_sys_config
SET description = 'B4 单用例 24 小时修复轮次上限（默认 3=PRD 9.3.3 修复循环轮次上限；达限=硬停转人工留痕；≥1 写侧校验，读侧非法回退默认 3）'
WHERE config_key = 'ai.repair.rounds';
