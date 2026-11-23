-- V554__exec_step_heal_evidence.sql — direct-heal evidence rows relayed by
-- the executor (03 protocol 2.4 trailing key stepHealEvidence, Page Element
-- Library self-heal ledger). One row per healed step: which element healed,
-- which anchor failed, which anchor won, at what visual similarity and what
-- ledger revision — the audit trail behind a self-healed run (executor
-- records, core stores verbatim; no scoring happens here).
-- R57: rows are bounded by the task FK (ON DELETE CASCADE) — retention
-- cleanup of a task drops its evidence with it. element_id carries no FK on
-- purpose: a ledger element retired after the run must never destroy the
-- historical evidence (the row is the record, not a pointer).
CREATE TABLE IF NOT EXISTS tp_exec_step_heal_evidence (
    id                  BIGSERIAL PRIMARY KEY,
    task_id             BIGINT      NOT NULL REFERENCES tp_exec_task (id) ON DELETE CASCADE,
    element_id          BIGINT,
    step_no             INT,
    healed              BOOLEAN,
    first_anchor_failed BOOLEAN,
    from_anchor_type    VARCHAR(32),
    from_anchor_value   TEXT,
    used_anchor_type    VARCHAR(32),
    used_anchor_value   TEXT,
    viewport            VARCHAR(32),
    ledger_revision     INT,
    similarity_score    DOUBLE PRECISION,
    created_at          TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_step_heal_evidence_element
    ON tp_exec_step_heal_evidence (element_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_step_heal_evidence_task
    ON tp_exec_step_heal_evidence (task_id);

COMMENT ON TABLE tp_exec_step_heal_evidence IS 'Self-heal evidence rows relayed with execution results (stepHealEvidence trailing key); bounded by task cascade.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.task_id IS 'Owning execution task; ON DELETE CASCADE keeps the table bounded with task retention.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.element_id IS 'Ledger element that was healed; FK-free by design so a retired element never destroys historical evidence.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.step_no IS 'Action step index in the script where the first anchor failed and a later anchor healed.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.from_anchor_type IS 'Anchor class that failed first (css|xpath|text|placeholder|alt|testid|rel).';
COMMENT ON COLUMN tp_exec_step_heal_evidence.from_anchor_value IS 'Failed locator expression itself.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.used_anchor_type IS 'Anchor class that finally resolved the element.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.used_anchor_value IS 'Healing locator expression itself.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.viewport IS 'Execution bucket label (e.g. 1440x900) the heal happened under.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.ledger_revision IS 'Page Element Library revision the element ledger was at when the heal was reported.';
COMMENT ON COLUMN tp_exec_step_heal_evidence.similarity_score IS 'Visual similarity against the baseline screenshot at heal time; NULL = no baseline to compare against (unproven).';
