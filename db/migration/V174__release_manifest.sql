
CREATE TABLE IF NOT EXISTS tp_release_manifest (
    id                  BIGSERIAL    PRIMARY KEY,
    version             VARCHAR(60)  NOT NULL,
    component_matrix    JSONB        NOT NULL DEFAULT '[]',
    breaking_changes    JSONB        NOT NULL DEFAULT '[]',
    executor_version_min VARCHAR(60),
    executor_version_max VARCHAR(60),
    suggested_path      VARCHAR(500),
    source              VARCHAR(20)  NOT NULL DEFAULT 'manual',
    registered_at       TIMESTAMP    NOT NULL DEFAULT now(),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_manifest_version UNIQUE (version),
    CONSTRAINT ck_release_manifest_source CHECK (source IN ('manual', 'import')),
    CONSTRAINT ck_release_manifest_matrix CHECK (jsonb_typeof(component_matrix) = 'array'),
    CONSTRAINT ck_release_manifest_changes CHECK (jsonb_typeof(breaking_changes) = 'array')
);

COMMENT ON TABLE tp_release_manifest IS '发布清单（升级权威；每次发版一条=部件矩阵+破坏性变更+执行器协议兼容区间+建议升级路径）';
COMMENT ON COLUMN tp_release_manifest.version IS 'testPike 发版本号（唯一行，重登=覆盖幂等）';
COMMENT ON COLUMN tp_release_manifest.component_matrix IS '部件矩阵 jsonb 数组（[{component,support,pinned}]：本版本各部件支持区间+钉版；对账沿台账判定谱禁第二谱）';
COMMENT ON COLUMN tp_release_manifest.breaking_changes IS '破坏性变更清单 jsonb 数组（可读逐条，changelog 抽屉+升级顾问消费源）';
COMMENT ON COLUMN tp_release_manifest.executor_version_min IS '兼容执行器协议版本下界（闭区间；NULL=不设下界=闸不拦）';
COMMENT ON COLUMN tp_release_manifest.executor_version_max IS '兼容执行器协议版本上界（闭区间；NULL=不设上界）';
COMMENT ON COLUMN tp_release_manifest.suggested_path IS '建议升级路径（跳版限制可读文本，升级引导单消费）';
COMMENT ON COLUMN tp_release_manifest.source IS '登记来源：manual=人工/企业版内部清单登记，import=开源仓库发布件导入';
COMMENT ON COLUMN tp_release_manifest.registered_at IS '发版登记时间（横幅「这个版本会带来什么」呈现时点）';
