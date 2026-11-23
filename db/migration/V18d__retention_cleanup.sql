
CREATE TABLE IF NOT EXISTS tp_training_sample (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id        BIGINT NOT NULL,
    sample_type    VARCHAR(20) NOT NULL,
    sample_json    JSONB,
    source_version INT,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE tp_training_sample IS 'AI 质量反馈训练样本（01 ；脱敏后逐条；Q6 按 retention.training-sample-days 清理）';
COMMENT ON COLUMN tp_training_sample.sample_type IS '样本类型：编辑/审批/反馈（01）';
CREATE INDEX IF NOT EXISTS idx_training_sample_case    ON tp_training_sample (case_id);
CREATE INDEX IF NOT EXISTS idx_training_sample_created ON tp_training_sample (created_at);

CREATE INDEX IF NOT EXISTS idx_exec_task_cleanup
    ON tp_exec_task (created_at) WHERE status IN (3, 4, 5, 6);

CREATE TABLE IF NOT EXISTS tp_audit_retention_break (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    segment_from_id  BIGINT NOT NULL,
    removed_through_id BIGINT NOT NULL,
    new_head_hash    CHAR(64) NOT NULL,
    removed_count    INT NOT NULL,
    cutoff_at        TIMESTAMP NOT NULL,
    before_count     BIGINT NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE tp_audit_retention_break IS '审计日志保留清理断点账本（Q6；前缀删除登记，链校验起点感知）';
CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_retention_break_through
    ON tp_audit_retention_break (removed_through_id);

CREATE OR REPLACE FUNCTION tp_audit_log_no_modify() RETURNS trigger AS $$
BEGIN
    IF current_setting('tp.audit_retention_allowed', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'tp_audit_log is append-only: UPDATE forbidden / DELETE only via retention cleanup (operator_id=%, action=%)',
            OLD.operator_id, OLD.action
            USING ERRCODE = '2F005';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
