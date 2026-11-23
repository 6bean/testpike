-- API Mock module, migration shell part 2 : the remaining
-- four shells — scenario definitions, hit ledger, recordings, AI jobs.
-- What this file lands and why:
--   1) tp_mock_scenario = stateful machine DECLARATIONS (states/transitions as jsonb,
--      shape frozen for M5); runtime state (session -> current state) lives in Redis
--      under K8 TTL keys, never in this table — the DB stays the definition truth,
--      Redis stays the volatile run truth;
--   2) tp_mock_hit_log = the request inspector ledger written by core from the MQ
--      batch (mock-server never writes PG directly). request_snapshot/match_trace are
--      masked BEFORE landing (PII red line: real traffic is full of it); stub_id NULL
--      = a miss row, which is exactly what "why is my mock not hitting" is answered
--      with; month-partitioning is deferred on purpose (retention sweeper starts with
-- , this shell only fixes the column truth);
--   3) tp_mock_recording = cassette boxes (proxy-recorded traffic aggregated per
--      endpoint; the cassette body goes to MinIO, this row keeps stats + reference);
--   4) tp_mock_ai_job = async AI drafting jobs (nl->stub / edge scenarios / LLM
--      fallback cache): budget_snapshot_id pins the cost account at creation time,
--      result_preview_ref keeps the "preview first, ticked-in by a human" quality
--      gate — no AI output ever enters the rule source directly.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_mock_scenario (
    id              BIGSERIAL    PRIMARY KEY,
    space_id        BIGINT       NOT NULL REFERENCES tp_mock_space (id),
    name            VARCHAR(80)  NOT NULL,
    definition_json JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_mock_scenario_space_name UNIQUE (space_id, name),
    CONSTRAINT ck_mock_scenario_definition CHECK (jsonb_typeof(definition_json) = 'object')
);

COMMENT ON TABLE tp_mock_scenario IS 'Stateful scenario declaration (machine definition only; runtime session->state lives in Redis with K8 TTL, never here)';
COMMENT ON COLUMN tp_mock_scenario.definition_json IS 'States/transitions declaration as jsonb (shape frozen by M5; stub rows reference it by scenario_name)';

CREATE TABLE IF NOT EXISTS tp_mock_hit_log (
    id                BIGSERIAL   PRIMARY KEY,
    space_id          BIGINT      NOT NULL,
    stub_id           BIGINT,
    request_snapshot  JSONB       NOT NULL DEFAULT '{}',
    matched           BOOLEAN     NOT NULL,
    match_trace       JSONB       NOT NULL DEFAULT '[]',
    latency_ms        INT,
    fault_applied     VARCHAR(60),
    session_id        VARCHAR(80),
    hit_at            TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mock_hit_space_time ON tp_mock_hit_log (space_id, hit_at DESC);

COMMENT ON TABLE tp_mock_hit_log IS 'Mock hit ledger (inspector rows; written by core from the MQ batch, mock-server never writes PG)';
COMMENT ON COLUMN tp_mock_hit_log.stub_id IS 'Matched stub; NULL = miss row (miss reasons are read from match_trace)';
COMMENT ON COLUMN tp_mock_hit_log.request_snapshot IS 'Masked request summary as jsonb (sensitive headers/body fields pass the masking pipeline BEFORE landing — PII red line)';
COMMENT ON COLUMN tp_mock_hit_log.match_trace IS 'Per-matcher verdict rows (which matcher failed and why: expected vs actual) — the inspector light-up rows';
COMMENT ON COLUMN tp_mock_hit_log.fault_applied IS 'Fault kind applied to this hit (NULL = normal response; honest annotation, reports and badges consume it)';
COMMENT ON COLUMN tp_mock_hit_log.session_id IS 'Session lane of the hit (X-TP-Mock-Session; CI parallel runs stay isolated through it)';

CREATE TABLE IF NOT EXISTS tp_mock_recording (
    id               BIGSERIAL    PRIMARY KEY,
    space_id         BIGINT       NOT NULL REFERENCES tp_mock_space (id),
    target_base_url  VARCHAR(300) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'recording',
    summary_json     JSONB        NOT NULL DEFAULT '{}',
    cassette_ref     VARCHAR(300),
    promoted_count   INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_mock_recording_status CHECK (status IN ('recording', 'stopped', 'promoted', 'failed'))
);

COMMENT ON TABLE tp_mock_recording IS 'Recording cassette box (proxy traffic captured behind the allowlist gate; cassette body stored in MinIO, this row keeps stats + reference)';
COMMENT ON COLUMN tp_mock_recording.summary_json IS 'Per-endpoint cluster statistics as jsonb (dedup groups, sample counts — the promotion draft source, masked first)';
COMMENT ON COLUMN tp_mock_recording.cassette_ref IS 'MinIO artifact reference of the cassette body';
COMMENT ON COLUMN tp_mock_recording.promoted_count IS 'How many stubs were promoted from this recording (traceability badge counter)';

CREATE TABLE IF NOT EXISTS tp_mock_ai_job (
    id                  BIGSERIAL    PRIMARY KEY,
    space_id            BIGINT       NOT NULL REFERENCES tp_mock_space (id),
    job_type            VARCHAR(30)  NOT NULL,
    input_json          JSONB        NOT NULL DEFAULT '{}',
    budget_snapshot_id  BIGINT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'queued',
    result_preview_ref  VARCHAR(300),
    error_message       VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_mock_ai_job_type CHECK (job_type IN ('nl-stub', 'edge-scenarios', 'llm-fallback', 'record-summarize', 'spec-sync')),
    CONSTRAINT ck_mock_ai_job_status CHECK (status IN ('queued', 'running', 'preview', 'consumed', 'failed', 'cancelled'))
);
CREATE INDEX IF NOT EXISTS idx_mock_ai_job_space ON tp_mock_ai_job (space_id, status);

COMMENT ON TABLE tp_mock_ai_job IS 'Async mock AI job (every application point off by default; results only ever land as a human-ticked preview)';
COMMENT ON COLUMN tp_mock_ai_job.budget_snapshot_id IS 'Cost account pinned at creation time (budget_snapshot discipline, never settled after the fact)';
COMMENT ON COLUMN tp_mock_ai_job.result_preview_ref IS 'Preview artifact reference (draft stubs wait for human selection; AI output never writes the rule source directly)';
