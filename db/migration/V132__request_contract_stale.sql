ALTER TABLE tp_apitest_request
    ADD COLUMN contract_stale boolean NOT NULL DEFAULT false;

CREATE INDEX idx_apitest_request_stale
    ON tp_apitest_request (collection_id) WHERE contract_stale;
