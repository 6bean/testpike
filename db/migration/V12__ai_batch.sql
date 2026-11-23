-- =====================================================================
-- =====================================================================
ALTER TABLE tp_ai_task ADD COLUMN IF NOT EXISTS batch_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_ai_task_batch ON tp_ai_task (batch_id);

CREATE TABLE IF NOT EXISTS tp_ai_batch (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    total       INT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE  tp_ai_batch IS 'AI 批量生成批次（进度由子任务状态实时聚合）';
COMMENT ON COLUMN tp_ai_task.batch_id IS '批量归属：tp_ai_batch.id，NULL=单条生成（A2）';
