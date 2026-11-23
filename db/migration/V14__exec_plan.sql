-- =====================================================================
-- =====================================================================

CREATE TABLE IF NOT EXISTS tp_exec_plan (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id          BIGINT       NOT NULL,
    name                VARCHAR(200) NOT NULL,
    target_version      VARCHAR(50),
    status              SMALLINT     NOT NULL DEFAULT 1,
    owner_id            BIGINT,
    env_ids_json        TEXT,
    case_scope_json     TEXT,
    entry_criteria_json TEXT,
    exit_criteria_json  TEXT,
    plan_start          VARCHAR(10),
    plan_end            VARCHAR(10),
    created_by          BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_exec_plan_status CHECK (status BETWEEN 1 AND 5)
);
CREATE INDEX IF NOT EXISTS idx_exec_plan_project ON tp_exec_plan (project_id, id);
COMMENT ON TABLE  tp_exec_plan IS '测试执行计划（L1 组织层）';
COMMENT ON COLUMN tp_exec_plan.status IS '1草稿 2已发布 3执行中 4已完成 5已取消';

CREATE TABLE IF NOT EXISTS tp_plan_case (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id        BIGINT       NOT NULL,
    case_id        BIGINT       NOT NULL,
    assignee_id    BIGINT,
    status         SMALLINT     NOT NULL DEFAULT 1,
    batch_case_ref BIGINT,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_plan_case UNIQUE (plan_id, case_id),
    CONSTRAINT ck_plan_case_status CHECK (status BETWEEN 1 AND 5)
);
CREATE INDEX IF NOT EXISTS idx_plan_case_plan ON tp_plan_case (plan_id);
CREATE INDEX IF NOT EXISTS idx_plan_case_case ON tp_plan_case (case_id);
COMMENT ON TABLE  tp_plan_case IS '计划-用例明细（L1 人员分配+进度聚合）';
COMMENT ON COLUMN tp_plan_case.status IS '1未执行 2执行中 3通过 4失败 5跳过';
COMMENT ON COLUMN tp_plan_case.batch_case_ref IS '生成批次后回填 tp_exec_task.id（执行结果反查回写状态）';

ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS plan_id BIGINT;
COMMENT ON COLUMN tp_exec_batch.plan_id IS '来源执行计划（L1；非计划批次为 NULL）';
CREATE INDEX IF NOT EXISTS idx_exec_batch_plan ON tp_exec_batch (plan_id) WHERE plan_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS tp_defect (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    case_id       BIGINT,
    plan_id       BIGINT,
    title         VARCHAR(200) NOT NULL,
    description   TEXT,
    severity      SMALLINT     NOT NULL DEFAULT 2,
    status        SMALLINT     NOT NULL DEFAULT 1,
    assignee_id   BIGINT,
    reporter_id   BIGINT       NOT NULL,
    steps_json    TEXT,
    external_key  VARCHAR(50),
    closed_reason VARCHAR(500),
    version       INT          NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_defect_severity CHECK (severity BETWEEN 1 AND 4),
    CONSTRAINT ck_defect_status  CHECK (status BETWEEN 1 AND 6)
);
CREATE INDEX IF NOT EXISTS idx_defect_project ON tp_defect (project_id, id);
CREATE INDEX IF NOT EXISTS idx_defect_case    ON tp_defect (case_id) WHERE case_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_defect_plan    ON tp_defect (plan_id) WHERE plan_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_defect_assignee ON tp_defect (assignee_id) WHERE assignee_id IS NOT NULL;
COMMENT ON TABLE  tp_defect IS '内置缺陷库（L2 缺陷主表，独立开源无 Jira 闭环）';
COMMENT ON COLUMN tp_defect.severity IS '1P0 2P1 3P2 4P3';
COMMENT ON COLUMN tp_defect.status IS '1新建 2处理中 3已修复 4验证中 5已关闭 6重新打开';
