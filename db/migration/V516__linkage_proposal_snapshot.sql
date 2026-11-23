-- Migration: linkage proposal snapshot table .
-- Why this table exists: when an armed content edge (disposition grouped_draft)
-- requests a downstream AI draft — today the script repair proposal reusing the
-- single-source repair proposal generator — the produced draft must land in a
-- durable snapshot keyed by the impact row it belongs to. Keeping snapshots on
-- the ledger side (instead of touching tp_script or tp_script_repair) preserves
-- the batch boundary: detection and proposal generation never modify downstream
-- business content; only the one-click human accept applies them.
-- Idempotency anchor: one snapshot per impact row (unique impact_id); a replay
-- of the same snapshot writes nothing, a newer script base version re-arms the
-- pending row, and a consumed row is frozen — a late proposal must never
-- overwrite what the human already accepted.
CREATE TABLE IF NOT EXISTS tp_linkage_proposal (
    id                  BIGSERIAL     PRIMARY KEY,
    impact_id           BIGINT        NOT NULL UNIQUE REFERENCES tp_linkage_impact(id) ON DELETE CASCADE,
    downstream_object_type VARCHAR(64) NOT NULL,
    downstream_object_id   BIGINT      NOT NULL,
    proposal_json       JSONB,
    schema_version      VARCHAR(16),
    model_name          VARCHAR(128),
    base_script_version INT,
    status              SMALLINT      NOT NULL DEFAULT 1,
    reject_reason       VARCHAR(512),
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT ck_linkage_proposal_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_linkage_proposal_shape CHECK (proposal_json IS NOT NULL OR reject_reason IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_linkage_proposal_downstream
    ON tp_linkage_proposal (downstream_object_type, downstream_object_id);

COMMENT ON TABLE tp_linkage_proposal IS 'Durable snapshots of AI drafts requested by armed grouped-draft impacts; applying them stays with the one-click human accept';
COMMENT ON COLUMN tp_linkage_proposal.impact_id IS 'Ledger row (tp_linkage_impact) this snapshot belongs to; one snapshot per impact, re-armed by a newer upstream version';
COMMENT ON COLUMN tp_linkage_proposal.proposal_json IS 'Validated repaired action JSON (null only for a rejected run, which must carry reject_reason)';
COMMENT ON COLUMN tp_linkage_proposal.base_script_version IS 'Script row version the draft was computed against — the accept step re-checks it before writing';
COMMENT ON COLUMN tp_linkage_proposal.status IS '1=pending (waits for the one-click accept), 2=consumed (applied), 3=rejected (unusable draft kept honestly with its reason)';
COMMENT ON COLUMN tp_linkage_proposal.reject_reason IS 'Plain reason of an unusable proposal; never blank for status 3';
