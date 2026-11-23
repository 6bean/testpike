
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_explore_session (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT      NOT NULL REFERENCES tp_project(id),
    goal_text       TEXT        NOT NULL,
    start_url       TEXT        NOT NULL,
    status          SMALLINT    NOT NULL DEFAULT 1,                -- 1=running 2=done 3=budget-stop 4=partial
    budget_snapshot JSONB       NOT NULL,
    steps_used      INT         NOT NULL DEFAULT 0,
    pages_visited   INT         NOT NULL DEFAULT 0,
    cost_cents      BIGINT      NOT NULL DEFAULT 0,
    report_md       TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_explore_session_status CHECK (status IN (1, 2, 3, 4))
);
COMMENT ON TABLE  tp_explore_session IS '自主探索会话（v2.26 B9）：budget_snapshot=创建时刻 ai.explore.* 快照（热改不影响运行中会话，IT-B9-02）';
COMMENT ON COLUMN tp_explore_session.status IS '1=running 2=done 3=budget-stop 4=partial；预算耗尽≠失败（partial 报告仍出，PRD 9.3.12.5）';
COMMENT ON COLUMN tp_explore_session.budget_snapshot IS 'JSONB：{maxSteps,maxPages,noProgressLimit,budgetConfirm,store[,estimateCostCents 包②]}——运行态判据唯一来源';

CREATE INDEX IF NOT EXISTS idx_explore_session_project ON tp_explore_session (project_id, id DESC);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_explore_step (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id       BIGINT   NOT NULL REFERENCES tp_explore_session(id),
    seq              INT      NOT NULL,
    step_action      JSONB,
    step_result      JSONB,
    screenshot_key   VARCHAR(500),
    dom_snapshot_key VARCHAR(500),
    llm_think_md     TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_explore_step_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_explore_step_seq CHECK (seq >= 1)
);
COMMENT ON TABLE tp_explore_step IS '探索步骤留痕（v2.26 B9）：预签名取证仅成员可取（IT-B9-04，读侧归包② admin 桥）';

CREATE INDEX IF NOT EXISTS idx_explore_step_session ON tp_explore_step (session_id, seq);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.explore.max-steps',     '128',    '系统', 'int',  0, '探索单次最大步数上限（默认 30；预算快照来源，B9/T209）'),
('ai.explore.max-pages',     '64',    '系统', 'int',  0, '探索访问页面上限（默认 10；无进展/预算终止判据，B9/T209）'),
('ai.explore.budget-confirm','true',  '系统', 'bool', 0, '探索创建需预算确认二次提交开关（默认 true；confirm 门归包②，TC-B9-01）'),
('ai.explore.snapshot-store','explore','系统', 'string', 0, '探索留证存储 scene（K11 StorageRouter 路由键，包③消费）')
ON CONFLICT (config_key) DO NOTHING;
