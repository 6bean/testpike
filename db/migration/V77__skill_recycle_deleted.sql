
ALTER TABLE tp_skill
    ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_skill DROP CONSTRAINT IF EXISTS ck_skill_deleted;
ALTER TABLE tp_skill
    ADD CONSTRAINT ck_skill_deleted CHECK (deleted IN (0, 1));
CREATE INDEX IF NOT EXISTS idx_skill_recycle
    ON tp_skill (project_id, id) WHERE deleted = 1;
COMMENT ON COLUMN tp_skill.deleted IS '软删标记 0=活跃 1=回收站（仿 P1 tp_test_case.deleted 同型）';
