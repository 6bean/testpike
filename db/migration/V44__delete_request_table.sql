
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_delete_request (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type   SMALLINT     NOT NULL,
    target_id     BIGINT       NOT NULL,
    project_id    BIGINT       NOT NULL,
    target_summary VARCHAR(200) NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    reject_reason VARCHAR(500),
    applicant_id  BIGINT       NOT NULL REFERENCES tp_user (id),
    status        SMALLINT     NOT NULL DEFAULT 2,
    approver_id   BIGINT       REFERENCES tp_user (id),
    handled_at    TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_delete_request_type CHECK (target_type IN (1, 2, 3)),
    CONSTRAINT ck_delete_request_status CHECK (status IN (2, 3, 4))
);
CREATE INDEX IF NOT EXISTS idx_delete_request_project ON tp_delete_request (project_id, status);
CREATE INDEX IF NOT EXISTS idx_delete_request_pending ON tp_delete_request (target_type, target_id);
COMMENT ON TABLE tp_delete_request IS '删除审批单（G6；三类 target，pending→approve 执行软删衔接 P1 回收站/rejected 零副作用）';
COMMENT ON COLUMN tp_delete_request.status        IS 'ApprovalStatus 码复用：2=待审批 3=已批准(approve 终态，执行软删) 4=已拒绝(可再提=新单)；retire/draft 不适用';
COMMENT ON COLUMN tp_delete_request.target_summary IS '创建时点对象摘要物化（用例=编号「标题」）；改名不回刷=申请时点语义';
COMMENT ON COLUMN tp_delete_request.project_id     IS '裁定②冗余列：H1 隔离+项目负责人解析+通知载荷三处消费';
