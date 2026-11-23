-- Project-set lens foundation (cluster Y, : three annotation tables only.
--
-- Why these tables exist:
--   Authorizing a newcomer across N projects today means N rows in tp_user_project.
--   A program (project set) is a grouping lens over existing projects: one membership
--   row in tp_user_program, joined through tp_program_project, expands into project
--   access at evaluation time. The lens holds no business data — zero program_id on
--   any business table, every write path still lands on a project, H1 project
--   isolation is untouched, and deleting a program detaches nothing that matters.
--
-- Expansion contract (enforced by ProjectRoleExpansion in core, the single point):
--   effective project role = max(direct tp_user_project role, 1 if the user sits in
--   any active program that includes the project). Program owner (role 2) expands to
--   member level only — it never upgrades to project owner.
--
-- Idempotent via IF NOT EXISTS (re-run safe).
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_program (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    state       SMALLINT     NOT NULL DEFAULT 1,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_program_name UNIQUE (name)
);
COMMENT ON TABLE  tp_program IS 'Project set (lens over projects, holds no business data)';
COMMENT ON COLUMN tp_program.state IS '1 = active, 0 = archived (archived programs stop expanding access)';
COMMENT ON COLUMN tp_program.created_by IS 'Creator user id (SSO logical key)';

CREATE TABLE IF NOT EXISTS tp_program_project (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id BIGINT   NOT NULL REFERENCES tp_program(id),
    project_id BIGINT   NOT NULL REFERENCES tp_project(id),
    created_by BIGINT   NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_program_project UNIQUE (program_id, project_id)
);
COMMENT ON TABLE  tp_program_project IS 'Program-to-project membership (many to many; a project may sit in several programs)';
COMMENT ON COLUMN tp_program_project.project_id IS 'Existing project id; the row never copies project data, it only names the lens member';

CREATE TABLE IF NOT EXISTS tp_user_program (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id BIGINT   NOT NULL REFERENCES tp_program(id),
    user_id    BIGINT   NOT NULL,
    role       SMALLINT NOT NULL DEFAULT 1,
    created_by BIGINT   NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_program UNIQUE (program_id, user_id),
    CONSTRAINT ck_user_program_role CHECK (role IN (1, 2))
);
COMMENT ON TABLE  tp_user_program IS 'Group-level grant: one row covers every project inside the program';
COMMENT ON COLUMN tp_user_program.user_id IS 'SSO user id (logical key, same convention as tp_user_project.user_id)';
COMMENT ON COLUMN tp_user_program.role IS '1 = program member, 2 = program owner (expands to project role 1 at most)';

-- Expansion lookups: project -> its programs, and user -> their programs.
CREATE INDEX IF NOT EXISTS idx_program_project_project ON tp_program_project (project_id);
CREATE INDEX IF NOT EXISTS idx_user_program_user ON tp_user_program (user_id);
