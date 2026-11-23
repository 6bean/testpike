
CREATE TABLE IF NOT EXISTS tp_step_alias (
    id          bigserial PRIMARY KEY,
    project_id  bigint NOT NULL REFERENCES tp_project (id),
    alias_name  varchar(64) NOT NULL,
    kind        varchar(8)  NOT NULL,
    target      varchar(256) NOT NULL,
    owner_ref   varchar(128),
    created_by  bigint,
    updated_at  timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_step_alias UNIQUE (project_id, alias_name),
    CONSTRAINT ck_step_alias_kind CHECK (kind IN ('path', 'step'))
);

CREATE INDEX IF NOT EXISTS idx_step_alias_project ON tp_step_alias (project_id);
