-- Migration: AI behavior outcome ledger (behavior-kernel item 7, result feedback loop).
-- Semantic: every real user verdict on AI-produced material — review pass/reject,
-- verification-chip decision, draft accept/reject — lands here as one weighted row,
-- tagged with the template domain and the template version pinned when the material
-- was produced. Aggregating rows per version yields the observed result rate, so a
-- gray-released template version that performs materially worse than its control can
-- be surfaced as a rollback suggestion. The ledger only counts: nothing here ever
-- flips a template status by itself (humans stay in the loop).
-- Dedup contract: (source, source_event_id) is unique — replaying the same label
-- event (retry, double submit, resync) is recorded exactly once.
--   ai.behavior.ab-roll-back-threshold = how many percentage points below the
--     control version's result rate make a candidate template version worth a
--     rollback suggestion (default 10; range 1~50; values outside the range fall
--     back to the default on read; suggestion only, never an automatic rollback;
--     takes effect immediately on change).
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration is
-- reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_ai_behavior_outcome (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_domain     VARCHAR(50)  NOT NULL,
    template_version_id BIGINT,
    scope               VARCHAR(20)  NOT NULL,
    scope_ref           VARCHAR(64),
    source              VARCHAR(30)  NOT NULL,
    source_event_id     VARCHAR(64)  NOT NULL,
    outcome             VARCHAR(20)  NOT NULL,
    weight              NUMERIC(6,2) NOT NULL DEFAULT 1.00,
    project_id          BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_behavior_outcome_source_event UNIQUE (source, source_event_id),
    CONSTRAINT ck_behavior_outcome_scope CHECK (scope IN ('session', 'document', 'task')),
    CONSTRAINT ck_behavior_outcome_outcome CHECK (outcome IN ('adopted', 'deprecated', 'corrected', 'partial')),
    CONSTRAINT ck_behavior_outcome_source CHECK (source IN ('review', 'chip', 'draft')),
    CONSTRAINT ck_behavior_outcome_weight CHECK (weight > 0 AND weight <= 10)
);

CREATE INDEX IF NOT EXISTS idx_behavior_outcome_domain_version
    ON tp_ai_behavior_outcome (template_domain, template_version_id);

COMMENT ON TABLE tp_ai_behavior_outcome IS 'Outcome ledger for AI-produced material: one weighted row per real user verdict, aggregated per prompt template version into an observed result rate (rollback advice is advisory only).';
COMMENT ON COLUMN tp_ai_behavior_outcome.template_domain IS 'Prompt template domain the producing template belongs to (tp_prompt_template.domain).';
COMMENT ON COLUMN tp_ai_behavior_outcome.template_version_id IS 'Row id of the pinned template version (tp_prompt_template.id); NULL when the material predates version pinning.';
COMMENT ON COLUMN tp_ai_behavior_outcome.scope IS 'What the verdict was about: session (dialog turn), document (extracted material), task (generated batch).';
COMMENT ON COLUMN tp_ai_behavior_outcome.scope_ref IS 'Business identifier of the judged object (session/document/task id) for trace-back.';
COMMENT ON COLUMN tp_ai_behavior_outcome.source IS 'Which human decision channel emitted the verdict: review, verification chip, or draft confirmation.';
COMMENT ON COLUMN tp_ai_behavior_outcome.source_event_id IS 'Identifier of the underlying verdict row inside its own channel; unique together with source so replays count once.';
COMMENT ON COLUMN tp_ai_behavior_outcome.outcome IS 'Observed reaction: adopted, deprecated, corrected (accepted after edits), partial (kept in part).';
COMMENT ON COLUMN tp_ai_behavior_outcome.weight IS 'Contribution of this verdict to the rate (default 1.00; heavier channels may weigh more).';
COMMENT ON COLUMN tp_ai_behavior_outcome.project_id IS 'Owning project, kept for per-project bucketing; reports stay aggregate, never a personal profile.';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.behavior.ab-roll-back-threshold', '10', '系统', 'int', 0, '新版本模板的结果率比对照版本低出多少个百分点时会提示建议回滚（默认 10；区间 1~50；只提示、不自动回滚，由人工确认；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
