-- Migration: response plane of the hit ledger .
-- The inspector so far only saw the request side of a mocked call; answering
-- "what did the mock actually reply" needs the served status/headers/body.
-- response_snapshot is a nullable jsonb {status,headers,body}: NULL means no
-- response facets were captured on that row (legacy rows and honest misses),
-- deliberately NOT '{}' so "absent" and "empty document" stay tellable apart.
-- No new tp_sys_config keys here (the body-truncation key ships with .
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

ALTER TABLE tp_mock_hit_log ADD COLUMN IF NOT EXISTS response_snapshot JSONB;
COMMENT ON COLUMN tp_mock_hit_log.response_snapshot IS 'Masked stub response as jsonb {status,headers,body} (sensitive response headers star-valued and body masked BEFORE leaving the plane — same PII red line as request_snapshot; NULL = no response captured)';
