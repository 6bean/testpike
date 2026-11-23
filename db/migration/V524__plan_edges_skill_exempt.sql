-- Migration: plan downstream fan + skill-family design-exempt rows .
-- Why these rows exist: saving a test plan repeatedly (scope, schedule, status)
-- moves the ground under every record that cites the plan, and an edited skill
-- asset must NOT rewrite the project bindings that reference it. Both facts
-- belong in the edge ledger as data, not code (design section 3.2), so the
-- orphan-edge scan and every future consumer read one source.
-- Plan fan (reference census from the batch analysis, section 10:
-- plan_id <- defect/exec_batch/plan_case/plan_deviation, plus tp_test_report
-- found by a live information_schema probe). tp_upgrade_plan_step.plan_id is
-- deliberately NOT registered here: it belongs to the upgrade-advisor plan
-- family (tp_upgrade_plan), a different table with a different save chain.
-- Risk shapes (design 3.1): not-yet-fired batches and plan rows are reversible
-- display state a re-check can mark automatically (risk 1); defects, issued
-- batches and generated reports are already-issued records that may only be
-- disclosed, never rewritten (risk 3). The status boundary mirrors the case
-- fan (case->plan_case arms status < 4, case->batch_case discloses status >= 4).
-- Skill exemptions (TC-LNK-05 first half): skills are opt-out assets (R34),
-- so a skill edit never propagates into the four tables that bind or snapshot
-- it. Each row carries the written reason in exempt_reason so the ledger is
-- auditable, and detector_kind = 3 (no detector) is legal only for exemptions
-- per the V513 table constraint. Boundary note: plugin/environment asset
-- tables carry no foreign keys onto any armed bus member (live pg_constraint
-- probe), so the scan cannot false-positive on them today; if they ever join
-- the bus as emitters, their binding surfaces register exempt rows the same
-- way — cleared as a named boundary in the batch ledger, not silently dropped.
-- Version-number collision note: this chain takes V524 because the main tree
-- already holds an in-flight V523 (OCR grid-assist key, other batch); the
-- main chain was at V522 when this segment opened.
-- Idempotent replay: rows are keyed by edge_key and conflict-do-nothing.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
('plan->exec_batch_pending', 'plan', 'exec_batch', 2,
 'SELECT id FROM tp_exec_batch WHERE plan_id = :objectId AND status < 4',
 NULL, 'mark_only', 1, 0, NULL,
 'A plan edit re-marks batches that were built from the plan but have not fired yet; the stale marker is reversible display state, zero clicks'),
('plan->exec_batch_issued', 'plan', 'exec_batch', 2,
 'SELECT id FROM tp_exec_batch WHERE plan_id = :objectId AND status >= 4',
 NULL, 'disclose_only', 3, 0, NULL,
 'Already-issued batches keep their frozen scope: a later plan edit is disclosed against them, never rewritten'),
('plan->defect', 'plan', 'defect', 2,
 'SELECT id FROM tp_defect WHERE plan_id = :objectId',
 NULL, 'disclose_only', 3, 0, NULL,
 'Defects cite the plan that found them; the citation is an issued record disclosed, not edited'),
('plan->plan_case', 'plan', 'plan_case', 2,
 'SELECT id FROM tp_plan_case WHERE plan_id = :objectId',
 NULL, 'mark_only', 1, 0, NULL,
 'Plan scope/schedule edits re-mark the membership rows so planning views show the change without any content rewrite'),
('plan->plan_deviation', 'plan', 'plan_deviation', 2,
 'SELECT id FROM tp_plan_deviation WHERE plan_id = :objectId',
 NULL, 'disclose_only', 3, 0, NULL,
 'Deviations are historical records measured against the plan at the time; disclosure only'),
('plan->test_report', 'plan', 'test_report', 2,
 'SELECT id FROM tp_test_report WHERE plan_id = :objectId',
 NULL, 'disclose_only', 3, 0, NULL,
 'Generated reports freeze the plan state at generation time; a later plan edit is disclosed against the report, never rewritten'),
('skill->project_skill', 'skill', 'project_skill', 3, NULL, NULL, 'exempt', 1, 1,
 'R34 opt-out design: the project row is an explicit binding/override (enabled=0 is the stop switch), not a content copy; a skill edit never rewrites bindings',
 NULL),
('skill->skill_tag', 'skill', 'skill_tag', 3, NULL, NULL, 'exempt', 1, 1,
 'R34 opt-out design: tag bindings reference the skill by id and carry no copied content, so a skill edit cannot stale them',
 NULL),
('skill->skill_usage', 'skill', 'skill_usage', 3, NULL, NULL, 'exempt', 1, 1,
 'Usage rows are an append-only accounting record of past calls; editing the skill asset must never rewrite issued usage facts',
 NULL),
('skill->skill_version', 'skill', 'skill_version', 3, NULL, NULL, 'exempt', 1, 1,
 'Version rows are append-only frozen snapshots; a save adds a new version and never invalidates stored history',
 NULL)
ON CONFLICT (edge_key) DO NOTHING;
