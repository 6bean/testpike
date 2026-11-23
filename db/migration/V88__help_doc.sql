
CREATE TABLE IF NOT EXISTS tp_help_doc (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    domain_code VARCHAR(10)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body_md     TEXT         NOT NULL,
    sort_no     INT          NOT NULL DEFAULT 1,
    version     VARCHAR(20)  NOT NULL DEFAULT '2.40',
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_help_doc_domain_sort UNIQUE (domain_code, sort_no)
);
CREATE INDEX IF NOT EXISTS idx_help_doc_domain ON tp_help_doc (domain_code, sort_no);

COMMENT ON TABLE tp_help_doc IS '帮助中心章节表（迁移件/；21 域索引+跨功能全链路章，Markdown 正文随迁移种子，版本随 PRD 登记行同步=红线 8 新条款）';
COMMENT ON COLUMN tp_help_doc.domain_code IS '功能域字母码（A~V 21 域 + LINK=跨功能全链路章；前端 i18n help.domain.<code> 映射显示名）';
COMMENT ON COLUMN tp_help_doc.version IS '内容版本（红线 8：功能更新必同步帮助文档，与 help.doc-version 全局键对账）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('help.doc-version', '2.40', '帮助', 'string', 0, '帮助文档全局版本（V12/T241；随 PRD 登记行 bump，默认 2.40=v2.40 V 域登记；PUT /internal/help/docs/version 热更即时生效）')
ON CONFLICT (config_key) DO NOTHING;
