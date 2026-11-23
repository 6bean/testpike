-- Migration: golden-set drift verdict ledger + promotion admission (behavior
-- kernel second round, item 4). Before a prompt-template version reaches full
-- release it must have survived a golden replay: the domain's fixed golden pack
-- (judge eval-set rows, version pinned by snapshot digest) pushed through the
-- control and the candidate version of the SAME model. The recorded drift rate
-- (share of samples whose normalized output digest changed, percentage points)
-- is weighed against ai.behavior.drift-block-threshold: above the bar — or with
-- no verdict at all — the promotion path refuses. This is the pre-release twin
-- of tp_ai_behavior_outcome (first-round item 7: the after-go-live scorecard);
-- neither ledger ever flips a template status by itself (humans stay in the loop).
--   ai.behavior.drift-block-threshold = how many percentage points of output
--     drift block promoting a template version to full release (default 15;
--     range 1~50; values outside the range fall back to the default on read;
--     hot-reloaded per call, no restart).
-- Append-only history: re-running a replay records a newer row; the newest row
-- governs the gate while older rows stay auditable.
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration
-- is reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_ai_golden_drift_verdict (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_domain     VARCHAR(50)  NOT NULL,
    template_name       VARCHAR(100) NOT NULL,
    candidate_version_id BIGINT      NOT NULL,
    control_version_id  BIGINT       NOT NULL,
    pack_name           VARCHAR(100) NOT NULL,
    pack_version        INT,
    snapshot_digest     CHAR(64)     NOT NULL,
    sample_total        INT          NOT NULL,
    drifted_count       INT          NOT NULL,
    drift_percent_pp    NUMERIC(5,2) NOT NULL,
    threshold_pp        INT          NOT NULL,
    pass_flag           SMALLINT     NOT NULL,
    note                VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_golden_drift_totals CHECK (sample_total > 0 AND drifted_count >= 0
        AND drifted_count <= sample_total),
    CONSTRAINT ck_golden_drift_rate CHECK (drift_percent_pp >= 0 AND drift_percent_pp <= 100),
    CONSTRAINT ck_golden_drift_threshold CHECK (threshold_pp >= 1 AND threshold_pp <= 50),
    CONSTRAINT ck_golden_drift_pass CHECK (pass_flag IN (0, 1)),
    CONSTRAINT ck_golden_drift_two_versions CHECK (candidate_version_id <> control_version_id)
);

CREATE INDEX IF NOT EXISTS idx_golden_drift_candidate
    ON tp_ai_golden_drift_verdict (template_domain, candidate_version_id, id DESC);

COMMENT ON TABLE tp_ai_golden_drift_verdict IS '黄金样本集回放结论账（T527 二轮 4：同模型双版本对固定输入回放，漂移率拦晋升；只登记结论不改状态）';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.snapshot_digest IS '本次回放所用黄金包版本的 SHA-256 小写 hex（钉住跑了哪一套样本，与 tp_judge_sample 快照锚同式）';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.drift_percent_pp IS '输出归一后摘要不一致的样本占比（百分点，0~100，两位小数）';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.threshold_pp IS '登记时生效的拦截阈值（ai.behavior.drift-block-threshold 快照，判定按登记时值留痕，晋升按最新行）';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.pass_flag IS '1=漂移率未超阈值（放行晋升） 0=超阈值（拦晋升）；无结论行同样拦（fail-closed）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.behavior.drift-block-threshold', '15', '系统', 'int', 0, '模板新版本黄金回放漂移率拦截阈值（百分点，1~50，出厂 15）：新版本与现全量版本在同一模型下对固定黄金样本回放，输出变化比例超过该值时不允许晋升全量；未回放过的新版本同样不能晋升。修改即时生效，无需重启；灰度观察与人工回滚不受影响')
ON CONFLICT (config_key) DO NOTHING;

-- Governance ledger rows for the two new internal endpoints (coverage contract:
-- every catalog entry carries an explicit verdict row; both faces stay closed to
-- the dialog surface — writes keep their human-only page path, the read is not
-- yet scope-aligned).
INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/behavior/golden-drift/latest', 0, 0, NULL, '该查询的数据可见范围尚未与页面对齐，暂未开放到对话，请在对应功能页面查看'),
    ('POST /internal/behavior/golden-drift/verdict', 0, 0, NULL, '变更类操作暂未开放到对话，请在对应功能页面执行')
ON CONFLICT (endpoint_key) DO NOTHING;
