-- Migration: tag downstream fan registered as written exemptions .
-- Why these rows exist: every table citing tp_tag_dict must be named by an edge
-- ledger row (design section 3.2: the relation is data, not code), and the tag
-- fan propagates inside the merge/delete transactions themselves — merge
-- re-points case bindings, skill bindings and batch pool tags to the surviving
-- row, and a delete against live references is refused with a readable
-- conflict. There is therefore no bus detector to arm; the rows are
-- design-time exemptions with written reasons, the same shape the skill family
-- uses (TC-LNK-05 first half, V524), so the orphan-edge scan reads the tag
-- coverage instead of reporting the relations as uncovered, and the FK
-- reverse-lookup census of the batch reconciliation closes.
-- Boundary note: tp_batch_case and other tables cite cases or batches, not
-- tp_tag_dict directly (live information_schema probe at segment open), so they
-- need no tag rows. The case-binding edge predates the bus but is registered
-- here for census completeness of the tag fan.
-- Version-number collision note: this chain takes V525; the main tree and all
-- chain trees were at V524 when this segment opened (live db/migration scan).
-- Idempotent replay: rows are keyed by edge_key and conflict-do-nothing.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
('tag->case_tag', 'tag', 'case_tag', 3, NULL, NULL, 'exempt', 1, 1,
 'Case tag bindings are re-pointed to the surviving row inside the merge transaction (overlap deleted, then moved); a delete against live case references is refused as a readable conflict, so no bus detector is armed for this edge',
 'tp_case_tag rows follow the tag merge in the same overlap-then-move shape as the tp_skill_tag fan'),
('tag->skill_tag', 'tag', 'skill_tag', 3, NULL, NULL, 'exempt', 1, 1,
 'Skill tag bindings are re-pointed inside the merge transaction before the source row is deleted; the tp_skill_tag foreign key plus a delete-time readable conflict naming the merge path keep the relation closed without a bus detector',
 'Registered alongside tag->case_tag so the tp_skill_tag FK surface is auditable instead of silently covered by code'),
('tag->exec_batch_pool', 'tag', 'exec_batch_pool', 3, NULL, NULL, 'exempt', 1, 1,
 'A batch pool tag is re-pointed to the surviving row inside the merge transaction; a delete against live pool references is refused as a readable conflict naming the batch count, so the dispatch pool never dangles',
 'tp_exec_batch.pool_tag_id has no foreign key — the in-transaction re-point plus the delete guard are the coverage for this citation')
ON CONFLICT (edge_key) DO NOTHING;
