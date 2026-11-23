-- EE batch : extraction fidelity ledger (tp_doc_extract_fidelity).
-- The live incident this table makes visible: document 8639 carried 121,375
-- source tokens but the final extraction call read 4,924 (retention 4.06%,
-- a 24.6x lossy summary path) and all 50 requirement items arrived with an
-- empty chunkId anchor — no stage row or gate could name it because nothing
-- booked "injected vs source". This ledger lands one mechanical row per
-- (document, extraction generation) written by ai-service at run completion
-- from the single call seam, so it reconciles against tp_ai_call_log with
-- zero difference (zero model calls, pure bookkeeping).
-- * (document_id, extract_token) scopes the row to one generation, exactly
--   like the coverage/stage ledgers — a re-triggered extraction is a new face.
-- * a replayed identical entry writes nothing and never bumps the revision
--   (R28 idempotent-save posture, same WHERE-IS-DISTINCT-FROM discipline).
-- * plain_json_tokens_per_item / billed_tokens_per_item are the measured
-- per-item output coefficients the output-budget rule derives
--   factory values from — coefficients must come from real runs, never guesses.
-- * rows cascade with the document; the door is fail-soft: bookkeeping never
--   fails the extraction run itself.
CREATE TABLE IF NOT EXISTS tp_doc_extract_fidelity (
    id                         BIGSERIAL    PRIMARY KEY,
    document_id                BIGINT       NOT NULL REFERENCES tp_document(id) ON DELETE CASCADE,
    extract_token              VARCHAR(64)  NOT NULL,
    chunk_count                INT          NOT NULL DEFAULT 0,
    node_count                 INT          NOT NULL DEFAULT 0,
    injected_tokens            INT          NOT NULL DEFAULT 0,
    source_tokens              INT          NOT NULL DEFAULT 0,
    retention_percent          NUMERIC(7,2) NOT NULL DEFAULT 0,
    distinct_chapters          INT          NOT NULL DEFAULT 0,
    item_count                 INT          NOT NULL DEFAULT 0,
    anchored_item_count        INT          NOT NULL DEFAULT 0,
    anchor_rate_percent        NUMERIC(5,2) NOT NULL DEFAULT 0,
    estimate_tokens            INT          NOT NULL DEFAULT 0,
    usage_prompt_tokens        INT          NOT NULL DEFAULT 0,
    est_drift_percent          NUMERIC(7,2) NOT NULL DEFAULT 0,
    plain_json_tokens_per_item INT          NOT NULL DEFAULT 0,
    billed_tokens_per_item     INT          NOT NULL DEFAULT 0,
    summary_calls              INT          NOT NULL DEFAULT 0,
    reconcile_calls            INT          NOT NULL DEFAULT 0,
    revision                   INT          NOT NULL DEFAULT 1,
    created_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_doc_extract_fidelity IS 'Extraction fidelity ledger of one document generation: injected vs source tokens, retention, anchor fill, estimate-vs-usage drift and measured per-item output coefficients; written from the single call seam, reconciles against tp_ai_call_log';
COMMENT ON COLUMN tp_doc_extract_fidelity.extract_token IS 'Extraction generation token minted by core; (document_id, extract_token) scopes the fidelity face to one extraction run';
COMMENT ON COLUMN tp_doc_extract_fidelity.injected_tokens IS 'Provider-reported prompt tokens of the calls that read the injected package (final extract + reconcile top-ups), summed at the single seam';
COMMENT ON COLUMN tp_doc_extract_fidelity.source_tokens IS 'Estimated tokens of the whole source document the extraction should read; retention_percent = injected/source';
COMMENT ON COLUMN tp_doc_extract_fidelity.retention_percent IS 'Fidelity ratio of the run; the T588 retention gate (ai.doc.fidelity-retention-floor) judges against this number';
COMMENT ON COLUMN tp_doc_extract_fidelity.anchor_rate_percent IS 'Share of extracted items whose server-side evidence anchor (chunkId) is non-null; the T585 closure contract expects 100';
COMMENT ON COLUMN tp_doc_extract_fidelity.est_drift_percent IS '(estimate - provider usage)/provider usage of the same package, in percent; calibration stays visible, thresholds untouched';
COMMENT ON COLUMN tp_doc_extract_fidelity.plain_json_tokens_per_item IS 'Measured billed completion minus reasoning, per produced item (output-side coefficient, T582 input)';
COMMENT ON COLUMN tp_doc_extract_fidelity.billed_tokens_per_item IS 'Measured prompt+completion of the extract plane, per produced item (thinking-inclusive coefficient)';

CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_extract_fidelity_generation
    ON tp_doc_extract_fidelity (document_id, extract_token);
