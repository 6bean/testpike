-- =====================================================================
-- =====================================================================
CREATE TABLE IF NOT EXISTS tp_case_defect (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id       BIGINT       NOT NULL REFERENCES tp_test_case(id),
    defect_key    VARCHAR(50)  NOT NULL,
    defect_status VARCHAR(20)  NOT NULL DEFAULT 'open',
    status        SMALLINT     NOT NULL DEFAULT 1,
    version       INTEGER      NOT NULL DEFAULT 1,
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_defect_rel UNIQUE (case_id, defect_key),
    CONSTRAINT ck_defect_rel_status CHECK (status IN (1, 2)),
    CONSTRAINT ck_defect_rel_defect_status CHECK (defect_status IN ('open', 'in_progress', 'closed'))
);
CREATE INDEX IF NOT EXISTS idx_defect_rel_case ON tp_case_defect (case_id);
CREATE INDEX IF NOT EXISTS idx_defect_rel_key  ON tp_case_defect (defect_key);
COMMENT ON TABLE  tp_case_defect IS '用例-缺陷关联（J2 缺陷闭环）';
COMMENT ON COLUMN tp_case_defect.defect_status IS 'Jira 缺陷状态回填：open/in_progress/closed（Jira 自动同步留 M2）';
COMMENT ON COLUMN tp_case_defect.status IS '闭环状态：1开启 2已关闭（关闭→DEFECT_CLOSED 事件重跑提示）';
