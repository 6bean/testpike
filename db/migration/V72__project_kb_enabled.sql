
ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS kb_enabled SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tp_project.kb_enabled IS '知识库开关 ：1=开（审批通过自动入库）0=关（新入库豁免，历史保留）';
