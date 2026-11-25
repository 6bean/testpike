-- AI pre-adjudication layer for the contract-bulk import wizard (MJ P3):
-- one stored verdict row per (spec, space, endpoint) carrying the recommendation
-- (recommend / avoid), the one-sentence business reason, the conflict handling
-- action, an impact reading (hit count of the conflicting rule etc.), the data
-- realism score and the confidence of the suggestion. Rows are proposals shown
-- on screen before any write happens — filing rules still goes through the
-- single confirm gate (the human one-click never disappears here).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_mock_spec_import_verdict (
    id              BIGSERIAL    PRIMARY KEY,
    spec_id         BIGINT       NOT NULL,
    space_id        BIGINT       NOT NULL,
    endpoint_key    VARCHAR(300) NOT NULL,
    verdict         VARCHAR(16)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    conflict_action VARCHAR(16),
    impact_json     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    realism         SMALLINT     NOT NULL,
    confidence      SMALLINT     NOT NULL,
    origin          VARCHAR(8)   NOT NULL DEFAULT 'rule',
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_msv_verdict CHECK (verdict IN ('recommend', 'avoid')),
    CONSTRAINT ck_msv_action CHECK (conflict_action IS NULL
        OR conflict_action IN ('skip', 'override', 'rename')),
    CONSTRAINT ck_msv_origin CHECK (origin IN ('rule', 'ai')),
    CONSTRAINT uk_msv_key UNIQUE (spec_id, space_id, endpoint_key)
);
CREATE INDEX IF NOT EXISTS idx_msv_created ON tp_mock_spec_import_verdict (created_at);

COMMENT ON TABLE tp_mock_spec_import_verdict IS 'Per-endpoint import suggestions shown on the contract-bulk preview (recommend/avoid + reason + impact reading); advisory rows only, never a write authorization';
COMMENT ON COLUMN tp_mock_spec_import_verdict.impact_json IS 'Impact reading: conflicting rule id/name, its 7-day hit count (hits7d); honest zeros for fresh endpoints';
COMMENT ON COLUMN tp_mock_spec_import_verdict.origin IS 'rule = deterministic single-point verdict, ai = refined by an AI backfill round';
