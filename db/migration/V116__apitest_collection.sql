
CREATE TABLE IF NOT EXISTS tp_apitest_collection (
    id          bigserial PRIMARY KEY,
    project_id  bigint       NOT NULL REFERENCES tp_project (id),
    parent_id   bigint       NOT NULL DEFAULT 0,
    name        varchar(100) NOT NULL,
    tags        varchar(500) NOT NULL DEFAULT '',
    created_by  bigint,
    created_at  timestamp    NOT NULL DEFAULT now(),
    updated_at  timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_apitest_collection_project
    ON tp_apitest_collection (project_id, parent_id);
