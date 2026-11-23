-- Migration: case and requirement downstream edge ledger .
-- Why these rows exist: the linkage bus reads tp_change_linkage to know what a
-- save must re-check downstream. Until now the ledger was empty, so the case
-- and requirement members were never armed and their fan-out stayed invisible.
-- This seeds the full downstream fan of both members as DATA (per the batch
-- design adjudication section 3.2: "an edge is a row, not code").
-- Coverage facts taken from the live catalog at authoring time:
--   foreign keys onto tp_test_case  -> tp_case_step, tp_case_requirement,
--       tp_case_defect, tp_review_log, tp_script
--   foreign keys onto tp_requirement -> tp_case_requirement,
--       tp_requirement_review_log
--   soft case references (case_id columns without foreign keys) -> plan_case,
--       batch_case, exec_task, defect, journey_segment, plan_deviation,
--       flaky_probe, ai_task, appauto_step_suggestion, appauto_record_draft,
--       appauto_ai_sample, case_tag, case_version, script_version,
--       script_repair, apitest_request, ai_fewshot_revision, training_sample
-- Risk shapes (design 3.1): 1 = reversible mark applied automatically,
-- 2 = downstream business content change surfacing as a grouped draft with
-- the one-click human accept, 3 = immutable or already-issued records that may
-- only be disclosed. Exempt rows (aggregate-owned or append-only history)
-- carry a written reason so the orphan-edge scan reads them as covered.
-- Idempotent replay: rows are keyed by edge_key and conflict-do-nothing.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
-- ---- foreign-key fan onto tp_test_case (mandatory for the orphan scan) ----
('case->case_step', 'case', 'case_step', 3, NULL, NULL, 'exempt', 1, 1,
 'Steps are part of the case aggregate and are saved with the case itself',
 NULL),
('case->case_requirement', 'case', 'case_requirement', 3, NULL, NULL, 'exempt', 1, 1,
 'Trace links are the case-to-requirement binding, edited with the case',
 NULL),
('case->case_defect', 'case', 'case_defect', 2,
 'SELECT id FROM tp_case_defect WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Defect joins are historical evidence; a case edit only discloses them'),
('case->review_log', 'case', 'review_log', 2,
 'SELECT id FROM tp_review_log WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Review history is immutable and can only be disclosed'),
('case->script', 'case', 'script', 2,
 'SELECT id FROM tp_script WHERE case_id = :objectId',
 'steps,title,precondition', 'grouped_draft', 2, 0, NULL,
 'Derived scripts surface a repair-proposal grouped draft; the repair proposal generator is reused, never a second implementation'),
-- ---- soft references: downstream business rows without foreign keys ----
('case->plan_case', 'case', 'plan_case', 2,
 'SELECT id FROM tp_plan_case WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Planned-but-not-started references are marked stale automatically'),
('case->batch_case', 'case', 'batch_case', 2,
 'SELECT id FROM tp_batch_case WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Started batches disclose affected rows and never rewrite execution history'),
('case->exec_task', 'case', 'exec_task', 2,
 'SELECT id FROM tp_exec_task WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Dispatched execution tasks are historical records, disclosure only'),
('case->defect', 'case', 'defect', 2,
 'SELECT id FROM tp_defect WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Defects filed against the case keep their findings; only disclosure applies'),
('case->journey_segment', 'case', 'journey_segment', 2,
 'SELECT id FROM tp_journey_segment WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Journey segments embedding the case get an automatic stale mark'),
('case->plan_deviation', 'case', 'plan_deviation', 2,
 'SELECT id FROM tp_plan_deviation WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Recorded deviations are history, disclosure only'),
('case->flaky_probe', 'case', 'flaky_probe', 2,
 'SELECT id FROM tp_flaky_probe WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Flaky probes watching the case are re-marked for a fresh measurement'),
('case->ai_task', 'case', 'ai_task', 2,
 'SELECT id FROM tp_ai_task WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'AI generation tasks built from the case are re-marked on the next read'),
('case->appauto_step_suggestion', 'case', 'appauto_step_suggestion', 2,
 'SELECT id FROM tp_appauto_step_suggestion WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Pending app-auto step suggestions are marked stale automatically'),
('case->appauto_record_draft', 'case', 'appauto_record_draft', 2,
 'SELECT id FROM tp_appauto_record_draft WHERE case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Record drafts built on the case are marked stale automatically'),
('case->script_repair', 'case', 'script_repair', 2,
 'SELECT id FROM tp_script_repair WHERE case_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Open repair proposals disclose the newer case revision instead of being rewritten'),
('case->apitest_request', 'case', 'apitest_request', 2,
 'SELECT id FROM tp_apitest_request WHERE promoted_case_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Requests promoted from the case get an automatic stale mark'),
-- ---- exempt soft references: owned aggregates / immutable history ----
('case->case_tag', 'case', 'case_tag', 3, NULL, NULL, 'exempt', 1, 1,
 'Tag bindings belong to the case aggregate and are saved with it', NULL),
('case->case_version', 'case', 'case_version', 3, NULL, NULL, 'exempt', 1, 1,
 'Case versions are append-only history of the case itself', NULL),
('case->script_version', 'case', 'script_version', 3, NULL, NULL, 'exempt', 1, 1,
 'Script versions are append-only history produced by the repair chain', NULL),
('case->appauto_ai_sample', 'case', 'appauto_ai_sample', 3, NULL, NULL, 'exempt', 1, 1,
 'Learning samples are immutable training history', NULL),
('case->ai_fewshot_revision', 'case', 'ai_fewshot_revision', 3, NULL, NULL, 'exempt', 1, 1,
 'Few-shot revisions are immutable training history', NULL),
('case->training_sample', 'case', 'training_sample', 3, NULL, NULL, 'exempt', 1, 1,
 'Training samples are immutable history', NULL),
-- ---- requirement fan (foreign keys onto tp_requirement plus case link) ----
('requirement->ai_task', 'requirement', 'ai_task', 2,
 'SELECT id FROM tp_ai_task WHERE requirement_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'A requirement edit re-marks generation tasks built from it'),
('requirement->case_requirement', 'requirement', 'case_requirement', 2,
 'SELECT id FROM tp_case_requirement WHERE requirement_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Linked cases are marked for review automatically'),
('requirement->requirement_review_log', 'requirement', 'requirement_review_log', 2,
 'SELECT id FROM tp_requirement_review_log WHERE requirement_id = :objectId', NULL,
 'disclose_only', 3, 0, NULL,
 'Requirement review history is immutable and can only be disclosed'),
('requirement->case', 'requirement', 'case', 2,
 'SELECT case_id AS id FROM tp_case_requirement WHERE requirement_id = :objectId', NULL,
 'mark_only', 1, 0, NULL,
 'Cases traced to the requirement are marked stale automatically')
ON CONFLICT (edge_key) DO NOTHING;
