-- =====================================================================
--               / tp_test_case / tp_case_step / tp_script / tp_audit_log / tp_ai_call_log
--   docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V1__init.sql
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_project (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    code             VARCHAR(20)  NOT NULL UNIQUE,
    default_lang     VARCHAR(10)  NOT NULL DEFAULT 'zh-CN',
    default_priority VARCHAR(8),
    status           SMALLINT     NOT NULL DEFAULT 1,
    budget_monthly   DECIMAL(12,2),
    template_pack_id BIGINT,
    created_by       BIGINT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  tp_project IS '项目';
COMMENT ON COLUMN tp_project.status IS '1=活跃 2=已归档（软状态，不物理删）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_project_env (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT        NOT NULL REFERENCES tp_project(id),
    name             VARCHAR(50)   NOT NULL,                -- dev/test/staging
    env_type         SMALLINT      NOT NULL DEFAULT 1,
    base_url         VARCHAR(500),
    config_json      JSONB,
    end_types        VARCHAR(50)   NOT NULL DEFAULT 'web',
    browsers         VARCHAR(50)   NOT NULL DEFAULT 'chromium',
    health_status    SMALLINT      NOT NULL DEFAULT 0,
    version_snapshot VARCHAR(100),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_env_name UNIQUE (project_id, name),
    CONSTRAINT ck_project_env_type CHECK (env_type IN (1, 2)),
    CONSTRAINT ck_project_env_health CHECK (health_status IN (0, 1, 2))
);
COMMENT ON TABLE  tp_project_env IS '项目环境';
COMMENT ON COLUMN tp_project_env.env_type IS '1=测试 2=生产（生产默认禁执行，执行前强校验 base_url 与生产域名黑名单）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_user_project (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT   NOT NULL REFERENCES tp_project(id),
    user_id    BIGINT   NOT NULL,
    role       SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_project UNIQUE (project_id, user_id),
    CONSTRAINT ck_user_project_role CHECK (role IN (1, 2))
);
COMMENT ON TABLE  tp_user_project IS '用户-项目-角色';
COMMENT ON COLUMN tp_user_project.role IS '1=成员 2=项目负责人';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_requirement (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id          BIGINT       NOT NULL REFERENCES tp_project(id),
    source              SMALLINT     NOT NULL DEFAULT 3,
    source_id           VARCHAR(100),
    title               VARCHAR(300) NOT NULL,
    acceptance_criteria TEXT,
    status              SMALLINT     NOT NULL DEFAULT 1,
    chapter             VARCHAR(100),
    created_by          BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_requirement_source CHECK (source IN (1, 2, 3)),
    CONSTRAINT ck_requirement_status CHECK (status IN (1, 2, 3))
);
CREATE INDEX IF NOT EXISTS idx_requirement_project_status ON tp_requirement (project_id, status);
COMMENT ON TABLE  tp_requirement IS '需求点（覆盖矩阵行基础）';
COMMENT ON COLUMN tp_requirement.source IS '1=Jira 2=文档提取(F2) 3=手工';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_test_case (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_no             VARCHAR(40)  NOT NULL UNIQUE,
    project_id          BIGINT       NOT NULL REFERENCES tp_project(id),
    title               VARCHAR(300),
    precondition        TEXT,
    expected_result     TEXT,
    priority            SMALLINT     NOT NULL DEFAULT 1,    -- 0=P0 1=P1 2=P2
    confidence          SMALLINT     NOT NULL DEFAULT 2,
    end_type            VARCHAR(10)  NOT NULL DEFAULT 'web',-- web/mobile/pos/multi
    status              SMALLINT     NOT NULL DEFAULT 1,
    version             INTEGER      NOT NULL DEFAULT 1,
    assignee_id         BIGINT,
    source              SMALLINT     NOT NULL DEFAULT 1,
    source_ref          VARCHAR(100),
    template_modified   SMALLINT     NOT NULL DEFAULT 0,
    last_exec_result    SMALLINT,
    last_exec_time      TIMESTAMP,
    review_fail_cnt     INTEGER      NOT NULL DEFAULT 0,
    steps_text          TEXT,
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    created_by          BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_test_case_priority   CHECK (priority IN (0, 1, 2)),
    CONSTRAINT ck_test_case_confidence CHECK (confidence IN (1, 2, 3)),
    CONSTRAINT ck_test_case_status     CHECK (status BETWEEN 1 AND 9),
    CONSTRAINT ck_test_case_deleted    CHECK (deleted IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_test_case_project_status ON tp_test_case (project_id, status);
CREATE INDEX IF NOT EXISTS idx_test_case_status        ON tp_test_case (status);
CREATE INDEX IF NOT EXISTS idx_test_case_confidence    ON tp_test_case (confidence);
CREATE INDEX IF NOT EXISTS idx_test_case_assignee      ON tp_test_case (assignee_id) WHERE assignee_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_test_case_steps_trgm ON tp_test_case USING GIN (steps_text gin_trgm_ops);
COMMENT ON TABLE  tp_test_case IS '用例（核心）';
COMMENT ON COLUMN tp_test_case.status IS '9 态：1待评审 2评审中 3已通过 4需修改 5已拒绝 6执行中 7执行失败 8回归失败 9已归档';
COMMENT ON COLUMN tp_test_case.confidence IS 'AI 自评置信度：1=高 2=中 3=低（质量门）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_case_step (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id     BIGINT      NOT NULL REFERENCES tp_test_case(id),
    case_version INTEGER    NOT NULL DEFAULT 1,
    step_no     INTEGER     NOT NULL,
    description VARCHAR(500) NOT NULL,
    is_assert   SMALLINT    NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_case_step UNIQUE (case_id, case_version, step_no),
    CONSTRAINT ck_case_step_assert CHECK (is_assert IN (0, 1))
);
COMMENT ON TABLE  tp_case_step IS '用例步骤';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_script (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id           BIGINT      NOT NULL UNIQUE REFERENCES tp_test_case(id),
    schema_version    VARCHAR(8)  NOT NULL DEFAULT 'v1',
    action_json       JSONB,
    status            SMALLINT    NOT NULL DEFAULT 1,
    generated_by      SMALLINT    NOT NULL DEFAULT 1,
    last_trial_result SMALLINT,
    version           INTEGER     NOT NULL DEFAULT 1,
    created_at        TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_script_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_script_generated_by CHECK (generated_by IN (1, 2, 3))
);
COMMENT ON TABLE  tp_script IS '脚本（动作 JSON，与用例 1:1）';
COMMENT ON COLUMN tp_script.status IS '1=可用 2=待更新（编辑后失效） 3=生成中';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id BIGINT,
    project_id  BIGINT,
    object_type VARCHAR(50)  NOT NULL,
    object_id   VARCHAR(64)  NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    before_json JSONB,
    after_json  JSONB,
    prev_hash   CHAR(64)     NOT NULL DEFAULT '',
    cur_hash    CHAR(64)     NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_operator ON tp_audit_log (operator_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_object   ON tp_audit_log (project_id, object_type, object_id);
COMMENT ON TABLE tp_audit_log IS '审计日志：只追加，禁止 UPDATE/DELETE（DB trigger 强制）';

CREATE OR REPLACE FUNCTION tp_audit_log_no_modify() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tp_audit_log is append-only: UPDATE/DELETE forbidden (operator_id=%, action=%)',
        OLD.operator_id, OLD.action;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_append_only ON tp_audit_log;
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON tp_audit_log
    FOR EACH ROW EXECUTE FUNCTION tp_audit_log_no_modify();

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_ai_call_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id        BIGINT,
    operator_id       BIGINT,
    provider          VARCHAR(30)  NOT NULL,
    model             VARCHAR(50)  NOT NULL,
    function          VARCHAR(50)  NOT NULL,
    prompt_tokens     INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER      NOT NULL DEFAULT 0,
    cost              DECIMAL(10,4) NOT NULL DEFAULT 0,
    latency_ms        INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ai_call_log_project ON tp_ai_call_log (project_id, created_at);
COMMENT ON TABLE tp_ai_call_log IS 'AI 调用记录（Token 成本，E3 预算依据）';

-- =====================================================================
-- =====================================================================
