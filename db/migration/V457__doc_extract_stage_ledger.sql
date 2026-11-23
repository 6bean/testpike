-- DD batch extraction stage ledger (tp_doc_extract_stage).
-- The coverage ledger (tp_doc_extract_ledger) only lands at the END of a run,
-- so a document stuck in status=3 gave the preview page nothing to show —
-- the live incident was a 38-minute "extracting" with no progress face at
-- all. This table records one row per stage step AS IT HAPPENS (run start,
-- grouping i/n, final bucket j/m, reconcile round r/n), written by ai-service
-- through the same door as the coverage ledger and replayed by the preview
-- endpoint as stageLedger + lastActivityAt.
-- * one row per (document_id, extract_token, stage_code): the generation
--   token scopes the ledger exactly like the coverage ledger, so a
--   re-triggered extraction starts a fresh stage face.
-- * a replayed identical label writes nothing (WHERE ... IS DISTINCT FROM),
--   same idempotent-save discipline as the coverage ledger (R28 posture).
-- * rows cascade with the document; stage bookkeeping is advisory — a door
--   outage never fails the extraction run itself.
CREATE TABLE IF NOT EXISTS tp_doc_extract_stage (
    id            BIGSERIAL   PRIMARY KEY,
    document_id   BIGINT      NOT NULL REFERENCES tp_document(id) ON DELETE CASCADE,
    extract_token VARCHAR(64) NOT NULL,
    stage_code    VARCHAR(64) NOT NULL,
    stage_label   VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_doc_extract_stage IS 'Extraction stage ledger of one document generation: one row per step landed as it happens (grouping i/n, final bucket j/m, reconcile round), replayed by the preview as stageLedger';
COMMENT ON COLUMN tp_doc_extract_stage.extract_token IS 'Extraction generation token minted by core; (document_id, extract_token) scopes the stage face to one extraction run';
COMMENT ON COLUMN tp_doc_extract_stage.stage_code IS 'Step identity within the run (start / group-i / bucket-j / reconcile-r); unique per generation so a replayed identical label writes nothing';
COMMENT ON COLUMN tp_doc_extract_stage.stage_label IS 'User-facing step text (e.g. 封组 2/5); landed with the step, never backdated';

CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_extract_stage_generation_step
    ON tp_doc_extract_stage (document_id, extract_token, stage_code);
