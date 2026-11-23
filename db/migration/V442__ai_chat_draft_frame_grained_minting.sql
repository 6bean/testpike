-- Migration: grouped draft minting at frame granularity .
--
-- Why: tp_ai_chat_draft welded idempotency to message_id (V302 UNIQUE), so an
--   assistant answer proposing several changes could surface only ONE confirmable
--   card — the landing insert dropped every later proposal (ON CONFLICT DO
--   NOTHING on message_id) and pinned the first card's id onto the second frame.
--   The group-confirmation door (next piece) needs one card per proposal, with
--   the message kept as the GROUP key.
-- Shape:
--   * frame_seq  = session seq of the DRAFT frame that minted this row
--                  (provenance; frames are unique on (session_id, seq), so a row
--                  always points back at exactly one journaled frame);
--   * dedup_key  = md5 fingerprint of the proposal content as landed
--                  (object_type | target_id | payload_json). Idempotency moves
--                  from message-granularity to proposal-granularity: re-appending
--                  the SAME frame (emitter retry) still collapses to one ticket,
--                  while two DIFFERENT proposals on one message each get a row.
--   * UNIQUE(message_id) is dropped; UNIQUE(message_id, dedup_key) replaces it.
--     Rows minted without a message (system-origin drafts, V363) keep NULL
--     message_id and stay outside the constraint exactly as before.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U *** -d testpike -f - < this file
--   then re-run scripts/gen_schema_init.sh

ALTER TABLE tp_ai_chat_draft ADD COLUMN IF NOT EXISTS frame_seq BIGINT;
ALTER TABLE tp_ai_chat_draft ADD COLUMN IF NOT EXISTS dedup_key TEXT;

-- Backfill existing rows; payload_json is NOT NULL, target_id renders as plain
-- digits or empty, so the expression matches the Java-side digest byte for byte.
UPDATE tp_ai_chat_draft
   SET dedup_key = md5(object_type || '|' || COALESCE(target_id::text, '') || '|' || payload_json)
 WHERE dedup_key IS NULL;

ALTER TABLE tp_ai_chat_draft DROP CONSTRAINT IF EXISTS tp_ai_chat_draft_message_id_key;
ALTER TABLE tp_ai_chat_draft
    ADD CONSTRAINT tp_ai_chat_draft_message_frame_key
        UNIQUE (message_id, dedup_key);

COMMENT ON COLUMN tp_ai_chat_draft.message_id IS 'Group key: the draft frame''s owning message; several proposals in one answer share it (idempotency lives on (message_id, dedup_key), not here)';
COMMENT ON COLUMN tp_ai_chat_draft.frame_seq IS 'Session seq of the DRAFT frame that minted this row (NULL for rows not minted through the frame ledger)';
COMMENT ON COLUMN tp_ai_chat_draft.dedup_key IS 'Proposal-content fingerprint md5(object_type|target_id|payload_json); with message_id it is the idempotency anchor: an identical re-landed frame never doubles the ticket, a distinct proposal always gets its own card';
