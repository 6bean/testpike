-- Migration: generic AI draft ledger .
--
-- Why this table exists:
--   tp_ai_chat_repair_draft (V301) welded one object type into the schema:
--   columns named after scripts (case_id, action_json, applied_script_version)
--   and a confirmation chain that can only ever land a repaired script. The
--   platform now needs every AI write proposal (case create/update next) to
--   pass through the SAME human-confirmation door, and Red line 6 forbids a
--   second confirmation chain per object type. This table is that single
--   generic ledger: object_type says what kind of proposal it is, target_id
--   says which entity the proposal aims at (nullable — a "create" proposal
--   has no target yet), payload_json carries the proposal body the landing
--   strategy will write, base_version keeps the optimistic-lock anchor for
--   proposals cut against an existing version.
--   Card-side display anchors that are specific to one proposal kind (e.g.
--   the repair card's executionId) ride in context_json instead of gaining
--   dedicated columns.
--   Invariants carried over from V301 unchanged:
--     1) one message mints at most one confirmable draft (UNIQUE(message_id),
--        landing is ON CONFLICT DO NOTHING so a reconnect replay never doubles
--        the ticket surface);
--     2) status state machine 1=pending / 2=applied / 3=voided lives service-
--        side (K8 convention: no DB CHECK on status transitions);
--     3) NOTHING lands to any business table through this table by itself —
--        writing needs the confirm token plus the landing strategy for the
--        object_type (red line: proposals never self-apply).
--   The old repair table is NOT dropped by this file: the code rewire to the
--   generic ledger happens in the same stage, and the drop lands only after
--   that rewire is proven, so this migration can be applied while the repair
--   chain still reads V301.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=*** testpike-postgres psql -U *** -d testpike -f - < this file
--   then re-run scripts/gen_schema_init.sh

CREATE TABLE IF NOT EXISTS tp_ai_chat_draft (
    id             BIGSERIAL PRIMARY KEY,
    session_id     BIGINT NOT NULL,
    message_id     BIGINT NOT NULL UNIQUE,
    object_type    VARCHAR(32) NOT NULL,
    target_id      BIGINT,
    base_version   INT,
    schema_version VARCHAR(32),
    model_name     VARCHAR(128),
    summary        VARCHAR(500),
    payload_json   TEXT   NOT NULL,
    context_json   JSONB,
    status         SMALLINT NOT NULL DEFAULT 1,
    applied_version INT,
    applied_by     BIGINT,
    applied_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE tp_ai_chat_draft IS 'Generic AI write-proposal drafts: the single core-side trustworthy snapshot ledger every dialog draft frame lands into; consumed only behind the confirmation chain; never writes business tables by itself';
COMMENT ON COLUMN tp_ai_chat_draft.message_id IS 'The draft frame''s owning message (UNIQUE: one message mints at most one confirmable draft)';
COMMENT ON COLUMN tp_ai_chat_draft.object_type IS 'Landing strategy discriminator (SCRIPT_REPAIR / CASE_WRITE / ...); the confirmation chain is shared, only the write-side strategy differs';
COMMENT ON COLUMN tp_ai_chat_draft.target_id IS 'Entity the proposal aims at (NULL for create-style proposals that have no target yet)';
COMMENT ON COLUMN tp_ai_chat_draft.base_version IS 'Entity version the proposal was cut against; apply re-checks it (optimistic lock, stale proposal = 409; NULL = no version anchor)';
COMMENT ON COLUMN tp_ai_chat_draft.payload_json IS 'Proposal body handed to the landing strategy (for SCRIPT_REPAIR this is the repaired action JSON)';
COMMENT ON COLUMN tp_ai_chat_draft.context_json IS 'Kind-specific display anchors that do not deserve columns (e.g. repair card executionId); never read by the confirmation guards';
COMMENT ON COLUMN tp_ai_chat_draft.status IS '1=pending 2=applied 3=voided (transitions guarded service-side per K8 convention)';

CREATE INDEX IF NOT EXISTS idx_tp_ai_chat_draft_target
    ON tp_ai_chat_draft (target_id, status);
CREATE INDEX IF NOT EXISTS idx_tp_ai_chat_draft_type
    ON tp_ai_chat_draft (object_type, status);

-- Data migration of existing repair drafts (object_type=SCRIPT_REPAIR).
-- Idempotent on message_id: re-running after the code rewire catches any rows
-- that landed into the old table between this file and the rewire, so the
-- drop of tp_ai_chat_repair_draft never silently loses a pending draft.
INSERT INTO tp_ai_chat_draft (
    session_id, message_id, object_type, target_id, base_version,
    schema_version, model_name, summary, payload_json, context_json,
    status, applied_version, applied_by, applied_at, created_at, updated_at)
SELECT d.session_id, d.message_id, 'SCRIPT_REPAIR', d.case_id, d.base_version,
       d.schema_version, d.model_name, d.summary, d.action_json,
       jsonb_build_object('executionId', d.execution_id),
       d.status, d.applied_script_version, d.applied_by, d.applied_at,
       d.created_at, d.updated_at
FROM tp_ai_chat_repair_draft d
WHERE NOT EXISTS (
    SELECT 1 FROM tp_ai_chat_draft g WHERE g.message_id = d.message_id);
