
ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS vars_json jsonb NOT NULL DEFAULT '{}'::jsonb;
