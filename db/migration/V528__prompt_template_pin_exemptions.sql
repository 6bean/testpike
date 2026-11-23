-- Migration: prompt-template downstream pairs as written exemptions .
-- Why these rows exist: the batch design adjudication sketched one stale-marker
-- edge from a prompt-template edit to the execution batch. A live probe of this
-- schema disproves that pair: tp_exec_batch.template_id references
-- tp_batch_template (a batch template, not a prompt template), the save chain
-- comments the column as provenance only with no cascade, and pg_constraint
-- holds no foreign key referencing tp_prompt_template at all. The pairs that do
-- consume a prompt-template row are id-pinned or append-only records, and the
-- family is append-only by design: saving a template inserts a new version row
-- inside the (name, domain) family and never rewrites the row another object
-- pins, so the pinned row keeps the content it was pinned to. Under the
-- design's own rule that the append-only template semantics stay untouched,
-- those pairs are design-time non-propagation and belong in the ledger as
-- exemption rows with a written reason (same posture as the skill family in
-- V524), not as edges that would claim a propagation the semantics forbid.
-- Census effect: the bus member is in the announcement enum, so an exempt row
-- naming it upstream clears its "member without edges" entry while leaving the
-- member unarmed — an unarmed member adds no foreign-key coverage duty, which
-- matches the live schema holding no foreign key onto tp_prompt_template. The
-- emitter gap (this family never announces, like plan/tag/skill today) is a
-- separate account in the batch ledger, not papered over here.
-- Boundary pairs probed and deliberately NOT registered:
--   tp_test_case.template_modified is a boolean flag, not a template reference;
--   tp_project.template_pack_id and tp_project_mcp_auth.template_id reference
--   the project-template and MCP-auth-template families, other objects on
--   other save chains; tp_batch_template itself is not a bus member, so naming
--   it upstream would surface as a dead edge.
-- Version-number collision note: main was at V527 when this segment opened and
-- this chain owns V522-V527, so V528 is taken after a live re-scan.
-- Idempotent replay: rows are keyed by edge_key, conflict-do-nothing, and the
-- statements are single-executable so a replay never splits inside a reason.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
('prompt_template->ai_task_pinned', 'prompt_template', 'ai_task', 3, NULL, NULL,
 'exempt', 1, 1,
 'A task pins the template row id at creation and retry rounds re-read that pinned row; saving a template adds a new version row inside the family and never rewrites the pinned row, so an in-flight or finished task is unaffected by design',
 'append-only template family; pin is an id reference, no content dependency to stale'),
('prompt_template->behavior_outcome', 'prompt_template', 'ai_behavior_outcome', 3,
 NULL, NULL, 'exempt', 1, 1,
 'Outcome rows are an append-only attribution record of what a past call actually ran on; editing the template afterwards must not rewrite issued outcome facts',
 'measured history keyed by the version id used at run time'),
('prompt_template->golden_drift_verdict', 'prompt_template',
 'ai_golden_drift_verdict', 3, NULL, NULL, 'exempt', 1, 1,
 'A drift verdict is the frozen comparison between a candidate and a control version at the time it was computed; it gates later promotions and is never re-derived from a template edit',
 'promotion admission reads issued verdicts as-is'),
('prompt_template->prompt_template_rollback_lineage', 'prompt_template',
 'tp_prompt_template', 3, NULL, NULL, 'exempt', 1, 1,
 'rollback_to records which row a new version copied its content from; it is lineage provenance inside the family, not a downstream object that can go stale',
 'self-referencing lineage column, kept auditable instead of invisible to the scan')
ON CONFLICT (edge_key) DO NOTHING;
