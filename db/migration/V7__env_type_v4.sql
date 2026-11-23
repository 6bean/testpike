-- =====================================================================
--
-- =====================================================================

ALTER TABLE tp_project_env DROP CONSTRAINT IF EXISTS ck_project_env_type;
ALTER TABLE tp_project_env ADD CONSTRAINT ck_project_env_type
    CHECK (env_type = ANY (ARRAY[1, 2, 3, 4]));

ALTER TABLE tp_project_env DROP CONSTRAINT IF EXISTS uq_project_env_type;
ALTER TABLE tp_project_env ADD CONSTRAINT uq_project_env_type UNIQUE (project_id, env_type);

