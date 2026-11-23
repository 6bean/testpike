
CREATE TABLE IF NOT EXISTS tp_project_template (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    industry    VARCHAR(50),
    content     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_template_name UNIQUE (name)
);
COMMENT ON TABLE tp_project_template IS 'H7 行业模板（content=四件套 JSONB：skillIds/tags/envs/roleHints；套用=fail-closed 同事务）';
COMMENT ON COLUMN tp_project_template.content IS '四件套 {skillIds[系统级 published SKILL id],tags[{name,type}],envs[{name,envType,baseUrl,endTypes,browsers}],roleHints[仅回显建议]}';
COMMENT ON COLUMN tp_project_template.industry IS '行业标识（向导分组展示，可空）';

COMMENT ON COLUMN tp_project.template_pack_id IS '来源行业模板 id（H7 T268：tp_project_template.id 逻辑引用，不设 FK；null=未套模板）';
