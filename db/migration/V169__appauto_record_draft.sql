
CREATE TABLE IF NOT EXISTS tp_appauto_record_draft (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    case_id       BIGINT       NOT NULL,
    source_task_id BIGINT      NULL,
    title         VARCHAR(200) NULL,
    event_source  VARCHAR(16)  NOT NULL,
    items         JSONB        NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 1,
    script_version INT         NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_record_draft_source CHECK (event_source IN ('native', 'scrcpy')),
    CONSTRAINT ck_appauto_record_draft_status CHECK (status IN (1, 2, 3))
);

COMMENT ON TABLE tp_appauto_record_draft IS 'APP 录制转流程草稿（-A4；两层质量门，人批勾选才入库）';
COMMENT ON COLUMN tp_appauto_record_draft.event_source IS '采集档位：native 引擎原生事件流/scrcpy 安卓回落输入注入流（三级链单点在执行器录制装配层）';
COMMENT ON COLUMN tp_appauto_record_draft.items IS '草稿步数组 JSONB（每元素 {stepNo,action,target,reason}，action 须 app.* IR 基线动词，闸在服务层）';
COMMENT ON COLUMN tp_appauto_record_draft.status IS '1 待审/2 已入库/3 已驳回';
COMMENT ON COLUMN tp_appauto_record_draft.script_version IS '入库落版时的脚本序（乐观锁链留痕）';

CREATE INDEX IF NOT EXISTS ix_appauto_record_draft_board
    ON tp_appauto_record_draft (case_id, status, created_at DESC);
