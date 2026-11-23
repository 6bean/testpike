
ALTER TABLE tp_apitest_collection
    ADD COLUMN IF NOT EXISTS vars_json jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS tp_apitest_global_var (
    id          bigserial PRIMARY KEY,
    project_id  bigint NOT NULL REFERENCES tp_project (id),
    var_key     varchar(64) NOT NULL,
    var_value   text NOT NULL,
    updated_at  timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_apitest_global_var UNIQUE (project_id, var_key)
);

ALTER TABLE tp_apitest_request
    DROP COLUMN IF EXISTS vars_json;
