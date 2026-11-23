-- Migration: online AI dialog storage foundation .
--
-- Why these tables exist:
--   The dialog kernel streams nine frame types (state/reason/trace/delta/draft/cite/
--   heartbeat/done/error) so the user never watches a spinner. That only works if every
--   persisted frame carries a monotonic per-session sequence: the browser cursor
--   (Last-Event-ID) is a session-level number, and reconnect must resend exactly the
--   tail of the still-streaming message block — finished blocks are served as the
--   message body, never re-faked as a live stream.
--     1) tp_ai_chat_session  = conversation shell (project + owner + context whitelist).
--        last_seq is the frame cursor, allocated under a row lock (no gaps, no reuse);
--     2) tp_ai_chat_message  = one turn (user/assistant/system). seq_from/seq_to bound
--        its frame block for replay; status 'aborted' keeps the partial output honest
--        (abort stops billing, it does not delete what the user already saw);
--     3) tp_ai_chat_frame    = append-only frame ledger. Heartbeat frames are transport
--        comments and are rejected here by CHECK (they must never consume a seq).
-- tool_trace/citations/drafts jsonb columns are shaped now
--   so later slices add no migration churn.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_ai_chat_session (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    title        VARCHAR(200) NOT NULL,
    source       SMALLINT     NOT NULL DEFAULT 1,
    context_json JSONB        NOT NULL DEFAULT '{}',
    status       SMALLINT     NOT NULL DEFAULT 0,
    total_tokens BIGINT       NOT NULL DEFAULT 0,
    total_cost   NUMERIC(14,6) NOT NULL DEFAULT 0,
    last_seq     BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_chat_session_source CHECK (source IN (1, 2, 3)),
    CONSTRAINT ck_ai_chat_session_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_ai_chat_session_context CHECK (jsonb_typeof(context_json) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_session_user_project
    ON tp_ai_chat_session (user_id, project_id, updated_at);

COMMENT ON TABLE tp_ai_chat_session IS 'AI dialog session shell; last_seq = monotonic frame cursor shared by all messages (replay anchor for Last-Event-ID)';
COMMENT ON COLUMN tp_ai_chat_session.source IS 'Entry surface: 1 dock / 2 standalone page / 3 badge deep-link';
COMMENT ON COLUMN tp_ai_chat_session.context_json IS 'Context whitelist object only (route/projectId/objectId/executionId class) — never bulk user data';
COMMENT ON COLUMN tp_ai_chat_session.status IS '0 active / 1 archived (archive keeps history, T451)';

CREATE TABLE IF NOT EXISTS tp_ai_chat_message (
    id                BIGSERIAL   PRIMARY KEY,
    session_id        BIGINT      NOT NULL REFERENCES tp_ai_chat_session (id),
    role              VARCHAR(16) NOT NULL,
    content           TEXT,
    reasoning         TEXT,
    tool_trace        JSONB       NOT NULL DEFAULT '[]',
    citations         JSONB       NOT NULL DEFAULT '[]',
    drafts            JSONB       NOT NULL DEFAULT '[]',
    status            VARCHAR(16) NOT NULL DEFAULT 'streaming',
    seq_from          BIGINT,
    seq_to            BIGINT,
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_chat_message_role CHECK (role IN ('user', 'assistant', 'system')),
    CONSTRAINT ck_ai_chat_message_status CHECK (status IN ('streaming', 'done', 'aborted', 'error'))
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session
    ON tp_ai_chat_message (session_id, id);

COMMENT ON TABLE tp_ai_chat_message IS 'One dialog turn; seq_from/seq_to bound its frame block — the replay window; aborted status keeps the partial body (never pretend completion)';
COMMENT ON COLUMN tp_ai_chat_message.tool_trace IS 'Tool call trajectory frames (auditable: what it based its answer on)';
COMMENT ON COLUMN tp_ai_chat_message.drafts IS 'Draft cards produced by write-class tools pending user confirmation (landing requires a confirmed ticket, no direct writes)';

CREATE TABLE IF NOT EXISTS tp_ai_chat_frame (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL,
    message_id BIGINT      NOT NULL REFERENCES tp_ai_chat_message (id),
    seq        BIGINT      NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    payload    TEXT,
    meta_json  JSONB       NOT NULL DEFAULT '{}',
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_chat_frame_session_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_ai_chat_frame_type CHECK (event_type IN
        ('state', 'reason', 'trace', 'delta', 'draft', 'cite', 'done', 'error'))
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_frame_message ON tp_ai_chat_frame (message_id, seq);

COMMENT ON TABLE tp_ai_chat_frame IS 'Append-only SSE frame ledger, one monotonic seq per session; heartbeat is a transport comment and is deliberately not a ledger type';
