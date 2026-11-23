-- Migration: repair-draft landing table .
--
-- Why this table exists:
--   When the dialog attaches a repair-proposal card (draft frame), the frame
--   meta already carries everything needed to apply it (execution/case anchors,
--   the script version the proposal was cut against, and the full action JSON).
--   But frame meta is a transport-side JSON string the confirmation door cannot
--   trust on its own: re-reading it from the ledger at confirm time would let a
--   forged payload sneak in through any client that renders the card. This table
--   is the core-side, write-once projection of exactly one draft frame — the
-- trustworthy snapshot the confirmation chain issue token -> apply
--   with token, re-checking base_version against tp_script) consumes.
--   Invariants:
--     1) one message mints at most one confirmable draft (UNIQUE(message_id),
--        landing is ON CONFLICT DO NOTHING so a reconnect replay never doubles
--        the ticket surface);
--     2) status state machine 1=pending / 2=applied / 3=voided lives service-
--        side (K8 convention: no DB CHECK on status transitions);
--     3) NOTHING lands to the script through this table — writing needs the
--        confirm token and a base_version match (red line: proposals never
--        self-apply).
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_ai_chat_repair_draft (
    id             BIGSERIAL PRIMARY KEY,
    session_id     BIGINT NOT NULL,
    message_id     BIGINT NOT NULL UNIQUE,
    execution_id   BIGINT NOT NULL,
    case_id        BIGINT NOT NULL,
    base_version   INT    NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    model_name     VARCHAR(128),
    summary        VARCHAR(500),
    action_json    TEXT   NOT NULL,
    status         SMALLINT NOT NULL DEFAULT 1,
    applied_script_version INT,
    applied_by     BIGINT,
    applied_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tp_ai_chat_repair_draft IS 'Dialog repair-proposal drafts: core-side trustworthy snapshot of one draft frame, consumed only behind the confirmation chain; never writes scripts by itself';
COMMENT ON COLUMN tp_ai_chat_repair_draft.message_id IS 'The draft frame''s owning message (UNIQUE: one message mints at most one confirmable draft)';
COMMENT ON COLUMN tp_ai_chat_repair_draft.base_version IS 'tp_script.version the proposal was cut against; apply re-checks it (optimistic lock, stale proposal = 409)';
COMMENT ON COLUMN tp_ai_chat_repair_draft.status IS '1=pending 2=applied 3=voided (transitions guarded service-side per K8 convention)';

CREATE INDEX IF NOT EXISTS idx_tp_ai_chat_repair_draft_case
    ON tp_ai_chat_repair_draft (case_id, status);
