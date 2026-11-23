-- Migration: open the draft ledger to system-originated proposals and restore the
-- memory pending-confirmation state (plan-A re-adjudication 11-03, ).
-- Semantics:
--   * tp_ai_chat_draft gains the system-origin shape: a distilled project-memory
--     proposal is minted by ai-service with NO dialog session and NO owning
--     message, so session_id/message_id relax to NULL-able. The confirmation
--     guard reads project_id for the system branch (membership + TP_PROJECT_MEMORY
--     permission behind the door); dialog drafts keep deriving the project from
--     the session through requireOwned, exactly as before (22-kind shared chain).
--   * tp_ai_ctx_memory: inject_status gains 2 = awaiting human confirmation
--     (the draft gate re-opened). Assembly reads inject_status=1 AND deleted=0,
--     so a pending entry is never injected — the "only active states inject"
--     contract holds without touching the read point.
-- Execution: manual psql (Flyway not enabled):
--   set -a; source .env; docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres \
--     psql -U *** -d testpike -f - < this file
--   then re-run tools/ops/gen_schema_init.sh

ALTER TABLE tp_ai_chat_draft ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE tp_ai_chat_draft ALTER COLUMN message_id DROP NOT NULL;
ALTER TABLE tp_ai_chat_draft ADD COLUMN IF NOT EXISTS project_id BIGINT;

COMMENT ON COLUMN tp_ai_chat_draft.session_id IS 'Owning dialog session (NULL = system-originated draft with no conversation, e.g. distilled project memory); the confirmation guard branches on this nullness: session drafts check ownership, system drafts check project membership';
COMMENT ON COLUMN tp_ai_chat_draft.message_id IS 'The draft frame''s owning message (UNIQUE: one message mints at most one confirmable draft; NULL = system-originated draft not tied to any dialog message)';
COMMENT ON COLUMN tp_ai_chat_draft.project_id IS 'Owning project for system-originated drafts (guard-checked for membership before the door opens); NULL for dialog drafts whose project is derived from the session';

COMMENT ON COLUMN tp_ai_ctx_memory.inject_status IS '2 = distilled but awaiting human confirmation through the draft gate (never injected), 1 = active and eligible for assembly injection, 0 = operator-disabled through the ledger view';
