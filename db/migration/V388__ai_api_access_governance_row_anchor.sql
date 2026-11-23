-- Migration: row-level ownership anchors for the dialog pass-through door's governance overlay.
--
-- Why: the overlay could only say "this endpoint family is verified safe to expose". For entries
-- addressed by a single data row (GET .../cases/{caseId}) that verdict left a gap: once a family
-- row was verified, any project's records became readable for every logged-in person, because the
-- scope guard judges only the project named in the call parameters and these calls name none.
-- The two columns below let a governance row name the entity and the parameter carrying its id,
-- so the door resolves the concrete row's owning project and applies the same membership check it
-- already applies to project-anchored calls — one rule, one decision source, never a second one.
--
-- Semantics: absent anchor (both columns null) keeps the previous family-level verdict, so the
-- rows registered by earlier migrations are unaffected. The anchor is opt-in per entry and is
-- only honoured for entities whose row->project bridge is wired in the code (fail closed while a
-- family bridge has not landed).
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

ALTER TABLE tp_ai_api_access_governance
    ADD COLUMN IF NOT EXISTS row_owner_entity VARCHAR(64),
    ADD COLUMN IF NOT EXISTS row_owner_id_param VARCHAR(64);

COMMENT ON COLUMN tp_ai_api_access_governance.row_owner_entity IS
    'Entity whose concrete row the call addresses (e.g. case); null keeps the family-level verdict';
COMMENT ON COLUMN tp_ai_api_access_governance.row_owner_id_param IS
    'Parameter carrying that row id (e.g. caseId); the door reads the argument, resolves the owning project and applies the shared membership check';
