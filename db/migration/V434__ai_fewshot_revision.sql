-- Migration: project-level few-shot revision sample pool .
-- Semantic: when a human corrects or discards an AI-generated case, the
-- before/after pair of the producing AI text and the human result lands here
-- so the generation few-shot segment can show "this is what humans fixed"
-- samples alongside the style references. The pool is derived observation
-- material only: nothing here gates or rewrites the case itself, and the
-- consumption path reuses the existing few-shot bridge switch
-- (ai.kb.style-fewshot.enabled) — no second toggle, no new channel.
-- Dedup contract: event_key is unique ("{caseId}:v{version}" for edits,
-- "{caseId}" for deletions) so replaying one observation writes exactly once.
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration
-- is reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_ai_fewshot_revision (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT,
    case_id     BIGINT       NOT NULL,
    event_key   VARCHAR(80)  NOT NULL,
    outcome     VARCHAR(20)  NOT NULL,
    ai_text     TEXT         NOT NULL,
    human_text  TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_fewshot_revision_event UNIQUE (event_key),
    CONSTRAINT ck_fewshot_revision_outcome CHECK (outcome IN ('corrected', 'deprecated'))
);

CREATE INDEX IF NOT EXISTS idx_fewshot_revision_project
    ON tp_ai_fewshot_revision (project_id, id DESC);

COMMENT ON TABLE tp_ai_fewshot_revision IS 'Few-shot negative sample pool: before/after pairs of AI text that humans corrected or deleted, consumed by the style few-shot bridge under its existing switch.';
COMMENT ON COLUMN tp_ai_fewshot_revision.event_key IS 'Observation dedup key: {caseId}:v{version} for the edit family, {caseId} for the delete family.';
COMMENT ON COLUMN tp_ai_fewshot_revision.outcome IS 'corrected = heavy rewrite kept half credit; deprecated = deleted or rewritten past recognition (human_text NULL for deletes).';
COMMENT ON COLUMN tp_ai_fewshot_revision.ai_text IS 'Producing AI text (v1 content snapshot rendering, content fields only).';
COMMENT ON COLUMN tp_ai_fewshot_revision.human_text IS 'Human result after the edit; NULL when the reaction was a deletion.';
