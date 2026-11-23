
CREATE TABLE IF NOT EXISTS tp_appauto_step_suggestion (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    case_id       BIGINT       NOT NULL,
    source_task_id BIGINT      NULL,
    step_no       INT          NOT NULL,
    failure_class SMALLINT     NOT NULL,
    anchor        VARCHAR(500) NOT NULL,
    source        VARCHAR(16)  NOT NULL,
    proposed_action VARCHAR(64) NOT NULL,
    proposed_target TEXT       NOT NULL,
    reason        TEXT         NULL,
    x             INT          NULL,
    y             INT          NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    adopted_script_version INT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_step_suggestion_class CHECK (failure_class IN (1, 2, 3)),
    CONSTRAINT ck_appauto_step_suggestion_source CHECK (
        source IN ('vision', 'a11y', 'cache', 'manual')),
    CONSTRAINT ck_appauto_step_suggestion_status CHECK (status IN (1, 2, 3))
);

COMMENT ON TABLE tp_appauto_step_suggestion IS 'APP 步骤自愈建议单（-A9；人批才落，禁直写用例脚本）';
COMMENT ON COLUMN tp_appauto_step_suggestion.failure_class IS '失败归因：1 产品缺陷/2 UI 变更嫌疑/3 环境（仅 2 进建议单，闸在服务层）';
COMMENT ON COLUMN tp_appauto_step_suggestion.source IS '候选定位出处（值域同 A2 样本 hit_source）';
COMMENT ON COLUMN tp_appauto_step_suggestion.proposed_target IS '候选定位策略 JSON（人批采纳后合入 action_json 对应步）';
COMMENT ON COLUMN tp_appauto_step_suggestion.status IS '1 待批/2 已采纳/3 已驳回';
COMMENT ON COLUMN tp_appauto_step_suggestion.adopted_script_version IS '采纳落版时的脚本序（乐观锁链留痕）';

CREATE INDEX IF NOT EXISTS ix_appauto_step_suggestion_board
    ON tp_appauto_step_suggestion (project_id, case_id, status, created_at DESC);
