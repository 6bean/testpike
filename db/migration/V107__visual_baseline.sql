CREATE TABLE IF NOT EXISTS tp_visual_baseline (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id          BIGINT        NOT NULL,
    step_key            VARCHAR(200)  NOT NULL,
    source_task_id      BIGINT,
    source_artifact_key TEXT,
    baseline_key        TEXT,
    status              SMALLINT      NOT NULL DEFAULT 1,
    diff_tolerance      DOUBLE PRECISION,
    created_by          BIGINT,
    confirmed_by        BIGINT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,
    CONSTRAINT ck_visual_baseline_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_visual_baseline_tolerance
        CHECK (diff_tolerance IS NULL OR (diff_tolerance >= 0 AND diff_tolerance <= 1))
);
CREATE INDEX IF NOT EXISTS idx_visual_baseline_step
    ON tp_visual_baseline (project_id, step_key, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_visual_baseline_confirmed
    ON tp_visual_baseline (project_id, step_key) WHERE status = 2;
CREATE UNIQUE INDEX IF NOT EXISTS uq_visual_baseline_pending
    ON tp_visual_baseline (project_id, step_key) WHERE status = 1;

COMMENT ON TABLE tp_visual_baseline IS 'B7 视觉回归基线：首建 pending→人工确认 confirmed 状态机；双态存储经项目路由';
COMMENT ON COLUMN tp_visual_baseline.step_key            IS '稳定键=用例+步骤号+截图名（执行器 visual.assert 读基线定位锚）';
COMMENT ON COLUMN tp_visual_baseline.source_artifact_key IS 'pending 阶段执行截图对象键（exec-artifact 规范 key，确认后留证不删）';
COMMENT ON COLUMN tp_visual_baseline.baseline_key        IS '确认后的基线稳定对象键（visual-baseline 场景；pending 阶段为空）';
COMMENT ON COLUMN tp_visual_baseline.status              IS '1=pending 待人工确认 2=confirmed 当前基线 3=superseded 被取代';
COMMENT ON COLUMN tp_visual_baseline.diff_tolerance      IS '基线级容差覆写（0-1 差异像素占比；NULL=用动作 JSON tolerance）';
