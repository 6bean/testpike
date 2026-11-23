-- Migration: soft-delete flag for the project-memory ledger .
-- Semantic: the ledger page governed by exposes disable/enable + delete only;
-- delete moves an entry into the recycle bin instead of erasing it (mirrors the
-- tp_skill.deleted precedent, V77), so the assembly read point filters deleted = 0
-- and restored entries come back with their original inject_status.
ALTER TABLE tp_ai_ctx_memory ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_ai_ctx_memory_recycle ON tp_ai_ctx_memory (project_id, id) WHERE deleted = 1;
COMMENT ON COLUMN tp_ai_ctx_memory.deleted IS 'Soft-delete flag 0=active 1=recycle bin (ledger governed by AP T449, mirrors tp_skill.deleted)';
