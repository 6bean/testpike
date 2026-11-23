
CREATE TABLE IF NOT EXISTS tp_tag_dict (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT       NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    type       SMALLINT     NOT NULL DEFAULT 4,
    created_by BIGINT,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_tag_dict_project_name UNIQUE (project_id, name)
);
COMMENT ON TABLE tp_tag_dict IS '标签字典（项目级；M3 用例标签，type 扩展自由标签）';
COMMENT ON COLUMN tp_tag_dict.type IS '1=模块 2=功能 3=优先级 4=自由标签';
COMMENT ON CONSTRAINT uk_tag_dict_project_name ON tp_tag_dict IS '同项目标签名唯一（幂等依赖）';

CREATE TABLE IF NOT EXISTS tp_case_tag (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id    BIGINT      NOT NULL,
    tag_id     BIGINT      NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uk_case_tag UNIQUE (case_id, tag_id)
);
COMMENT ON TABLE tp_case_tag IS '用例-标签关联（M3 用例多标签）';
COMMENT ON CONSTRAINT uk_case_tag ON tp_case_tag IS '同用例同标签幂等';

CREATE INDEX IF NOT EXISTS idx_case_tag_tag ON tp_case_tag (tag_id);
CREATE INDEX IF NOT EXISTS idx_tag_dict_project ON tp_tag_dict (project_id);
