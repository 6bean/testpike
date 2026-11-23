-- AX batch extraction coverage ledger for the requirement pipeline.
-- F2 extraction previously kept whatever the model returned: a chunk nobody
-- cited stayed silently unmined, and the preview page could not tell "this
-- block has no requirements" apart from "the model skipped this block".
-- This table carries one ledger row per (document, extraction generation):
-- every chunk the model saw is a step, a step is done only when at least one
-- extracted requirement points back at it through its evidence anchor (the
-- anti fake-done rule is reused from the dialog GoalPlanLedger — single
-- source, no second implementation), and uncovered chunks persist as pending
-- so the preview can flag them honestly instead of guessing.
-- * one row per (document_id, extract_token): the generation token already
--   guards the core write-back (a stale generation 409s there), so the
--   ledger is naturally scoped to one extraction run.
-- * a replayed identical step set writes nothing and the revision only
--   rides a real change (idempotent-save discipline, same posture as the
--   goal-plan ledger).
CREATE TABLE IF NOT EXISTS tp_doc_extract_ledger (
    id            BIGSERIAL   PRIMARY KEY,
    document_id   BIGINT      NOT NULL REFERENCES tp_document(id) ON DELETE CASCADE,
    extract_token VARCHAR(64) NOT NULL,
    steps_json    TEXT        NOT NULL,
    revision      INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_doc_extract_ledger IS 'Extraction coverage ledger of one document generation: one step per chunk the model saw, done only with a citing requirement (targeted gap re-extraction bound)';
COMMENT ON COLUMN tp_doc_extract_ledger.extract_token IS 'Extraction generation token minted by core; (document_id, extract_token) is the ledger identity so a re-triggered extraction starts a fresh ledger';
COMMENT ON COLUMN tp_doc_extract_ledger.steps_json IS 'JSON array [{title,state=pending|done|skipped|blocked,evidence}] in the GoalPlanLedger canonical form; a done step without an evidence anchor is normalised back to pending';
COMMENT ON COLUMN tp_doc_extract_ledger.revision IS 'Bumps only on a real step-set change; a replayed identical set writes nothing';

CREATE UNIQUE INDEX IF NOT EXISTS uq_doc_extract_ledger_doc_generation
    ON tp_doc_extract_ledger (document_id, extract_token);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extract-reconcile-rounds', '3', '系统', 'int', 0,
     '需求点提取覆盖对账的定向补提轮数上限（默认 3，允许 1~5；提取完成后若仍有文档分块没有被任何需求点引用，系统只针对这些分块再次提取，最多补提到这里填写的轮数；轮数越大覆盖越全但消耗的模型用量越多；改值下次提取即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
