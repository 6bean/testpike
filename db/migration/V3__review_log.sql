-- ============================================================
-- ============================================================

CREATE TABLE IF NOT EXISTS tp_review_log (
    id         BIGSERIAL PRIMARY KEY,
    case_id    BIGINT      NOT NULL REFERENCES tp_test_case(id),
    user_id    BIGINT      NOT NULL,
    action     VARCHAR(20) NOT NULL,
    remark     VARCHAR(500),
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_review_log IS '用例评审记录（评审留痕，PRD 4.2.2）';
COMMENT ON COLUMN tp_review_log.action IS '动作：通过/需修改/拒绝/编辑/克隆/回滚';
COMMENT ON COLUMN tp_review_log.remark IS '评审意见（need_change/reject 必填，review.remark-required 配置化）';

CREATE INDEX IF NOT EXISTS idx_review_log_case ON tp_review_log (case_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_review_log_user ON tp_review_log (user_id);
