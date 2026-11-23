-- Per-turn thumbs feedback on dialog messages (self-evolution loop, observation signal intake).
--
-- Why this migration exists:
--   The self-evolution loop (AF-i) observes answer quality to propose
--   improvements, but the dialog message ledger had no per-turn signal column
--   - 👍/👎 could not be recorded per answer, so the "disliked" signal named in
--   the design never existed anywhere on disk. This adds the collection face:
--     feedback        - 1 = helpful, -1 = not helpful, 0 = cleared, NULL = never rated;
--     feedback_reason - one optional sentence captured with a 👎 (feeds the
--                       "answer inaccurate / retrieval missed" ledger the
--                       proposal generator reads later).
--   Scope: the column only records what the user said about one turn; judging
--   or aggregating over it is a read-side concern (no new tables, the
--   tp_tool_invocation ledger and E9 session face stay the other signal
--   sources - one observation ledger, never a second one).
--   Idempotent: guarded via IF NOT EXISTS so a re-run is a no-op.
--   Note: Flyway is not enabled - apply by hand (DBA channel), same as the
--   rest of db/migration.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

ALTER TABLE tp_ai_chat_message ADD COLUMN IF NOT EXISTS feedback smallint;
ALTER TABLE tp_ai_chat_message ADD COLUMN IF NOT EXISTS feedback_reason varchar(512);

-- Re-runnable guard: the constraint name must exist exactly once.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_ai_chat_message_feedback'
    ) THEN
        ALTER TABLE tp_ai_chat_message
            ADD CONSTRAINT ck_ai_chat_message_feedback CHECK (feedback IN (-1, 0, 1));
    END IF;
END $$;

COMMENT ON COLUMN tp_ai_chat_message.feedback IS 'Per-turn user rating: 1 helpful, -1 not helpful, 0 cleared, NULL never rated (observation signal for the self-evolution loop)';
COMMENT ON COLUMN tp_ai_chat_message.feedback_reason IS 'One optional sentence captured with a down rating; cleared whenever the rating is not -1 so a stale complaint never rides on a positive signal';
