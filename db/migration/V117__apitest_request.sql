
CREATE TABLE IF NOT EXISTS tp_apitest_request (
    id               bigserial PRIMARY KEY,
    collection_id    bigint        NOT NULL REFERENCES tp_apitest_collection (id),
    name             varchar(200)  NOT NULL,
    method           varchar(10)   NOT NULL DEFAULT 'GET',
    url_path         varchar(1000) NOT NULL,
    headers_json     jsonb         NOT NULL DEFAULT '[]'::jsonb,
    body_text        text          NOT NULL DEFAULT '',
    auth_json        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    assertions_json  jsonb         NOT NULL DEFAULT '[]'::jsonb,
    promoted_case_id bigint,
    created_by       bigint,
    created_at       timestamp     NOT NULL DEFAULT now(),
    updated_at       timestamp     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_apitest_request_collection
    ON tp_apitest_request (collection_id);
