-- LLM output-shape capability ledger (one row per model x call form).
-- Live probes proved an endpoint can accept a json_schema response_format
-- block on the wire and still not enforce it (OpenAI-compat gateways drop
-- the schema silently when tools ride the same request). tp_llm_config.
-- structured_output is a single binary per row and cannot express
-- "enforces schema only in the tool-free form", so the enforcement truth
-- observed at runtime lands here: a row (model_name, call_form) with
-- enforced = 0 records the accept-but-not-enforce observation, and the
-- generation chain reads it to steer schema-bearing calls into the form
-- the endpoint actually enforces. Re-observed failures bump hits.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_llm_output_capability (
    model_name varchar(128) NOT NULL,
    call_form varchar(32) NOT NULL,
    enforced smallint NOT NULL DEFAULT 0,
    hits integer NOT NULL DEFAULT 1,
    updated_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT pk_llm_output_capability PRIMARY KEY (model_name, call_form)
);

COMMENT ON TABLE tp_llm_output_capability IS
    'Runtime observation of schema-constrained-decoding enforcement per model and request form (enforced 0 = endpoint accepted the block without enforcing).';
COMMENT ON COLUMN tp_llm_output_capability.call_form IS
    'Wire form the schema block rode: tools_schema (function-calling loop present) / schema_only (plain completion).';
COMMENT ON COLUMN tp_llm_output_capability.hits IS
    'Number of accepted-but-not-enforced observations that landed this row.';
