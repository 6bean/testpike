ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS ai_page_inspect_enabled SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN tp_project.ai_page_inspect_enabled IS 'G4 页面探查项目开关 0=关(默认) 1=开（TC-G4-01 成本闸，按项目配）';
