-- Migration: request-level orchestration columns .
-- Why: the orchestration vocabulary (ApiOrchestrationContract) lets one request carry
--      a precondition row (run / honestly skip) and a loop row (count / repeat-until).
--      These belong to the request definition itself, so they are stored next to the
--      other jsonb form columns instead of a side table.
-- Semantics: both default to '{}' = no gate / straight-line run (legacy rows keep
--      the exact old behavior). Shape validation happens at the core save gate;
--      the columns never hold free-form scripts.

ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS precondition_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS loop_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_apitest_request.precondition_json IS
    'Precondition form object {field,op,expect,...} (empty {} = always run). Domain single source = ApiOrchestrationContract; validated at save time.';

COMMENT ON COLUMN tp_apitest_request.loop_json IS
    'Loop form object {mode,times|until,maxTimes,delayMs} (empty {} = straight-line run). Domain single source = ApiOrchestrationContract; validated at save time.';
