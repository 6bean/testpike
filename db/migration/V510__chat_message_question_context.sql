-- Audit column for the per-question page context anchor :
-- every user turn records the whitelist context it was asked from, so the
-- ledger answers "which page did this question come from" per question, not
-- just per session. Nullable — rows written before this channel stay NULL.
ALTER TABLE tp_ai_chat_message ADD COLUMN IF NOT EXISTS question_context jsonb;

COMMENT ON COLUMN tp_ai_chat_message.question_context IS 'Whitelist page anchor of this question (route/projectId/objectId/executionId class), validated at the run door; NULL for rows predating the channel';
