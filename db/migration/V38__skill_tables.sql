
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_skill (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT REFERENCES tp_project(id),
    name            VARCHAR(100) NOT NULL,
    type            SMALLINT     NOT NULL,
    status          SMALLINT     NOT NULL DEFAULT 1,          -- 1=draft 2=pending_approval 3=published 4=rejected 5=retired
    body            TEXT,
    frontmatter     JSONB,
    current_version INT          NOT NULL DEFAULT 1,
    version         INT          NOT NULL DEFAULT 0,
    created_by      BIGINT,
    approved_by     BIGINT,
    approved_at     TIMESTAMP,
    reject_reason   VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_skill_type   CHECK (type BETWEEN 1 AND 5),
    CONSTRAINT ck_skill_status CHECK (status BETWEEN 1 AND 5)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_project_name ON tp_skill (COALESCE(project_id, 0), name);
CREATE INDEX IF NOT EXISTS idx_skill_status      ON tp_skill (status);
CREATE INDEX IF NOT EXISTS idx_skill_project     ON tp_skill (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_skill_type        ON tp_skill (type);
CREATE INDEX IF NOT EXISTS idx_skill_body_trgm ON tp_skill USING GIN (COALESCE(body, '') gin_trgm_ops);
COMMENT ON TABLE tp_skill IS 'SKILL 定义（S1/S2；5 类型；系统级 project_id NULL；status 5 态审批链）';
COMMENT ON COLUMN tp_skill.status IS 'S2 状态机：1=draft 2=pending_approval 3=published 4=rejected 5=retired（仅 published 可被项目绑定）';
COMMENT ON COLUMN tp_skill.type   IS '1=用例检查 2=需求补齐 3=脚本开发规范 4=测试执行规范 5=自由';
COMMENT ON COLUMN tp_skill.version IS '乐观锁版本号（E10：并发审批后提交方 409；与 current_version 职责分离）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_skill_version (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skill_id     BIGINT       NOT NULL REFERENCES tp_skill(id) ON DELETE CASCADE,
    version      INT          NOT NULL,
    body         TEXT,
    frontmatter  JSONB,
    status_at_snapshot SMALLINT NOT NULL DEFAULT 1,
    reason       VARCHAR(200),
    approved_at  TIMESTAMP,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_skill_version UNIQUE (skill_id, version)
);
COMMENT ON TABLE tp_skill_version IS 'SKILL 版本链（B5 同构；UNIQUE(skill_id,version)；body/frontmatter 快照）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_skill_tag (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skill_id   BIGINT      NOT NULL REFERENCES tp_skill(id) ON DELETE CASCADE,
    tag_id     BIGINT      NOT NULL REFERENCES tp_tag_dict(id),
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_skill_tag UNIQUE (skill_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_skill_tag_tag ON tp_skill_tag (tag_id);
COMMENT ON TABLE tp_skill_tag IS 'SKILL-标签关联（S3；UNIQUE 幂等；标签字典 type 扩展 5=SKILL）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_project_skill (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES tp_project(id),
    skill_id   BIGINT NOT NULL REFERENCES tp_skill(id),
    role       SMALLINT NOT NULL DEFAULT 3,
    priority   INT    NOT NULL DEFAULT 0,
    enabled    SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_skill UNIQUE (project_id, skill_id),
    CONSTRAINT ck_project_skill_role    CHECK (role IN (1, 2, 3)),
    CONSTRAINT ck_project_skill_enabled CHECK (enabled IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_project_skill_skill ON tp_project_skill (skill_id);
COMMENT ON TABLE tp_project_skill IS '项目 SKILL 绑定（S4；UNIQUE(project,skill)；role+优先级+启停）';
COMMENT ON COLUMN tp_project_skill.role IS '1=脚本开发 SKILL→stage2 注入 2=测试 SKILL→执行校验注入 3=通用→全链路（9.3.11(1) 装配规则键）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_skill_usage (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       BIGINT,
    skill_id      BIGINT NOT NULL REFERENCES tp_skill(id) ON DELETE CASCADE,
    skill_version INT,
    stage         SMALLINT NOT NULL,
    dropped       SMALLINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_skill_time ON tp_skill_usage (skill_id, created_at);
CREATE INDEX IF NOT EXISTS idx_skill_usage_task ON tp_skill_usage (task_id) WHERE task_id IS NOT NULL;
COMMENT ON TABLE tp_skill_usage IS 'SKILL 消费留痕（S5；task/skill/version/阶段；市场用量聚合源）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
COMMENT ON COLUMN tp_tag_dict.type IS '1=模块 2=功能 3=优先级 4=自由标签 5=SKILL（S3；6=TOOL 随 启用）';
