-- Migration: project memory entry table for the AP batch .
-- Semantic: distilled project-convention entries injected into the assembly
-- context (caps ai.ctx.memory-max-items, relevance-trimmed). The pin-facts
-- layer itself lives in tp_ai_chat_session.context_json and is independent of
-- this lossy-trimmed layer; distillation writes and the /skills ledger view
-- land with / and consume this table.
CREATE TABLE IF NOT EXISTS tp_ai_ctx_memory (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT      NOT NULL,
    content       TEXT        NOT NULL,
    source_ref    JSONB       NOT NULL,
    inject_status SMALLINT    NOT NULL DEFAULT 1,
    hit_count     BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ai_ctx_memory_project ON tp_ai_ctx_memory (project_id, inject_status);

COMMENT ON TABLE tp_ai_ctx_memory IS
    'Project memory entries distilled from review rejections, healing diffs, dialog downvotes and refusals (AP batch; injection cap via ai.ctx.memory-max-items)';
COMMENT ON COLUMN tp_ai_ctx_memory.project_id IS 'Owning project (tp_project.id); entries never cross projects';
COMMENT ON COLUMN tp_ai_ctx_memory.content IS 'The distilled project convention text injected into assembly';
COMMENT ON COLUMN tp_ai_ctx_memory.source_ref IS 'Traceability anchors [{type,id}] naming the rejection/downvote/healing-diff sources (T449 ledger view)';
COMMENT ON COLUMN tp_ai_ctx_memory.inject_status IS '1 = eligible for assembly injection, 0 = operator-disabled through the ledger view (governed by T449)';
COMMENT ON COLUMN tp_ai_ctx_memory.hit_count IS 'Times this entry took part in assembly, read-only accounting for the ledger view';
