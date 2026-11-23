-- piece 2: per-target issue-order sequence on the draft ledger.
-- Why: same-target pending cards now renew automatically behind the
-- confirmation door (DraftQueueRenewalPolicy). The stale-exclusivity gate
-- needs a deterministic "newest card" that survives status churn — ids
-- keep growing from unrelated drafts and voided/applied cards keep their
-- queue slot, so neither id order among pending rows nor a count is a
-- stable issue order. seq fills that role; create-style drafts (target_id
-- IS NULL) never join a queue and stay NULL by design.
-- Execution: manual psql (Flyway not enabled), then re-run
-- scripts/gen_schema_init.sh.
ALTER TABLE tp_ai_chat_draft ADD COLUMN IF NOT EXISTS seq INT;

COMMENT ON COLUMN tp_ai_chat_draft.seq IS 'Per-target issue order (1-based, allocated by DraftSeqAllocator at card mint): the queue order the renewal policy trusts; NULL for create-style drafts that have no target yet';

-- Backfill: renumber existing rows per target in id order (id is the
-- historical mint order). IS DISTINCT FROM keeps replays write-free.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY target_id ORDER BY id) AS rn
    FROM tp_ai_chat_draft
    WHERE target_id IS NOT NULL
)
UPDATE tp_ai_chat_draft d
SET seq = r.rn
FROM ranked r
WHERE d.id = r.id AND d.seq IS DISTINCT FROM r.rn;

CREATE INDEX IF NOT EXISTS idx_tp_ai_chat_draft_target_seq
    ON tp_ai_chat_draft (target_id, seq);
