
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_plugin (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    industry    VARCHAR(40)  NOT NULL,
    version     VARCHAR(20)  NOT NULL,
    manifest    JSONB        NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    source      SMALLINT     NOT NULL DEFAULT 2,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_plugin_name_version UNIQUE (name, version)
);
COMMENT ON TABLE tp_plugin IS '行业插件描述符注册（H8；声明式七键，禁可执行代码=安全底线）';
COMMENT ON COLUMN tp_plugin.manifest IS '描述符全量解析 JSONB（name/industry/version/content_pack/default_skills/param_template/project_types）；提升列与之同源单写';
COMMENT ON COLUMN tp_plugin.source   IS '1=内置样例（随迁移 seed，只读改拒）2=ADMIN 上传（schema 校验入库）';
CREATE INDEX IF NOT EXISTS idx_plugin_industry ON tp_plugin (industry);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_plugin (name, industry, version, manifest, enabled, source)
SELECT 'pos-retail', 'POS', '1.0.0',
       '{"name": "pos-retail", "industry": "POS", "version": "1.0.0", "content_pack": "pos-retail-pack", "default_skills": ["POS 收银主流程用例规范", "POS 支付断言规范", "POS 小票核验规范"], "param_template": {"pos_terminal_id": "T01", "currency": "TWD"}, "project_types": ["pos"]}'::jsonb,
       true, 1
WHERE NOT EXISTS (SELECT 1 FROM tp_plugin WHERE name = 'pos-retail' AND version = '1.0.0');

INSERT INTO tp_plugin (name, industry, version, manifest, enabled, source)
SELECT 'erp-supplychain', 'ERP', '1.0.0',
       '{"name": "erp-supplychain", "industry": "ERP", "version": "1.0.0", "content_pack": "erp-supplychain-pack", "default_skills": ["ERP 单据流转断言规范", "ERP 库存对账用例规范", "ERP 审批链用例规范"], "param_template": {"doc_series": "STD", "warehouse": "WH01"}, "project_types": ["web"]}'::jsonb,
       true, 1
WHERE NOT EXISTS (SELECT 1 FROM tp_plugin WHERE name = 'erp-supplychain' AND version = '1.0.0');
