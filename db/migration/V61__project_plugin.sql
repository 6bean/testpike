
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_project_plugin (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     BIGINT      NOT NULL REFERENCES tp_project(id),
    plugin_id      BIGINT      NOT NULL REFERENCES tp_plugin(id),
    param_snapshot JSONB       NOT NULL,
    applied_by     BIGINT,
    applied_at     TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_plugin UNIQUE (project_id, plugin_id)
);
COMMENT ON TABLE  tp_project_plugin IS '项目插件装配记录（v2.26 H8；IT-H8-02 装配记录可查，卸载=摘行不动已生成资产）';
COMMENT ON COLUMN tp_project_plugin.param_snapshot IS '装配时参数模板快照 JSONB（manifest.param_template 同源单写，插件改版不回写已装配快照）';
CREATE INDEX IF NOT EXISTS idx_project_plugin_plugin ON tp_project_plugin (plugin_id);
