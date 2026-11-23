-- Migration: golden-replay comparison axes . Until now the
-- verdict ledger could only record the template axis (two prompt-template
-- versions of the same model). A model-route switch (same template, two model
-- rows) or an assembler-head switch (same template + model, old vs new
-- assembly code) produces the same kind of regression risk, but had nowhere to
-- land, so a version gear change could never be replayed offline against the
-- golden pack. This adds an axis column plus free-text ref pair columns:
--   comparison_kind = 'template' (default, existing rows and semantics are
--     untouched — zero drift for the promotion gate), 'route' (candidate_ref /
--     control_ref name the two model-route rows), 'head' (the refs name the
--     old/new assembler heads under replay).
--   candidate_version_id / control_version_id stay NOT NULL for the legacy
--     shape; non-template axes record 0 there because the compared subjects
--     are not template rows (FK/identity checks are skipped per kind in the
--     gate, and the two-subject rule moves onto the ref columns).
-- Validation stays server-side (GoldenDriftGate.recordComparison); this file
-- only widens the ledger. Append-only history and the fail-closed promotion
-- semantics are unchanged: latestVerdict/requirePromotionAdmission still read
-- only what the template axis wrote, so route/head rows never touch the gate.
-- Applied manually to this chain's slot DB (Flyway is not enabled; db/migration
-- is reference), then db/schema-init.sql regenerated via tools/ops/gen_schema_init.sh.

ALTER TABLE tp_ai_golden_drift_verdict
    ADD COLUMN IF NOT EXISTS comparison_kind VARCHAR(16) NOT NULL DEFAULT 'template',
    ADD COLUMN IF NOT EXISTS control_ref    VARCHAR(200),
    ADD COLUMN IF NOT EXISTS candidate_ref  VARCHAR(200);

ALTER TABLE tp_ai_golden_drift_verdict
    DROP CONSTRAINT IF EXISTS ck_golden_drift_two_versions;

ALTER TABLE tp_ai_golden_drift_verdict
    ADD CONSTRAINT ck_golden_drift_kind CHECK (comparison_kind IN ('template', 'route', 'head'));

ALTER TABLE tp_ai_golden_drift_verdict
    ADD CONSTRAINT ck_golden_drift_two_versions CHECK (
        comparison_kind <> 'template' OR candidate_version_id <> control_version_id);

ALTER TABLE tp_ai_golden_drift_verdict
    ADD CONSTRAINT ck_golden_drift_axis_refs CHECK (
        comparison_kind = 'template'
        OR (control_ref IS NOT NULL AND candidate_ref IS NOT NULL
            AND btrim(control_ref) <> '' AND btrim(candidate_ref) <> ''
            AND btrim(control_ref) <> btrim(candidate_ref)));

COMMENT ON COLUMN tp_ai_golden_drift_verdict.comparison_kind IS '对照轴：template=同模型双模板版本（晋升门只读此轴） route=同模板双模型路由行 head=装配段新旧头；非 template 轴时两侧主体记在 control_ref/candidate_ref，版本 id 记 0';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.control_ref IS '非模板轴的对照侧名称（route=模型路由行标识，head=旧装配头标识）；template 轴为空';
COMMENT ON COLUMN tp_ai_golden_drift_verdict.candidate_ref IS '非模板轴的候选侧名称（route=新模型路由行标识，head=新装配头标识）；template 轴为空';
