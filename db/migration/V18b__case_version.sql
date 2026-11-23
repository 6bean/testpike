
CREATE TABLE IF NOT EXISTS tp_case_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id       BIGINT      NOT NULL,
    version       INTEGER     NOT NULL,
    snapshot_json JSONB       NOT NULL,
    change_type   SMALLINT    NOT NULL,
    operator_id   BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_case_version UNIQUE (case_id, version),
    CONSTRAINT ck_case_version_change_type CHECK (change_type IN (1, 2, 3, 4))
);
COMMENT ON TABLE tp_case_version IS '用例版本快照（Q1；编辑/评审状态变更/回滚即快照，历史不可变——禁 UPDATE/DELETE）';
COMMENT ON COLUMN tp_case_version.version IS '快照版本号 = 变更后的 tp_test_case.version（回滚写入新号，不篡改历史）';
COMMENT ON COLUMN tp_case_version.snapshot_json IS '完整快照 {title,precondition,expectedResult,priority,confidence,endType,phase,status,assigneeId,steps[],script}（脚本随版本快照，B5 语义）';
COMMENT ON COLUMN tp_case_version.change_type IS '1=编辑 2=评审状态变更 3=草稿 4=回滚（01）';
COMMENT ON COLUMN tp_case_version.operator_id IS '操作人（列表显示名 display_name 回退 username，与评审历史同语义）';
CREATE INDEX IF NOT EXISTS idx_case_version_case ON tp_case_version (case_id, version DESC);
