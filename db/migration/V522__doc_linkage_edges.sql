-- Migration: document downstream edge ledger .
-- Why these rows exist: parse-successful uploads now announce doc.changed on
-- the linkage bus, and the dispatcher arms whatever this ledger registers —
-- an edge is data, not code (design section 3.2). The two rows encode the
-- "reuse, never a third detector" clause (section 3.3) for the doc member:
--   doc -> doc_change_analysis is the extraction QUEUE row itself. It is
--       produced in-transaction by the lineage auto-analysis extractor at
--       re-upload, so the bus must not re-derive it — registered as an
--       in-design exempt edge (kind 3, exempt=1) so the orphan-edge scan
--       reads the foreign keys as covered instead of flagging them;
--   doc -> requirement is the downstream content fan: pending analysis rows
-- name the requirement snapshot ids that already have revision
--       drafts pre-generated behind the 10c one-click confirm gate. The SQL
--       detector references the extractor's OWN output (tp_doc_change_analysis
--       new_document_id binding, status 0 = pending per DocChangeAnalysisStatus
--       PENDING, requirement ids read from the changes jsonb array written by
--       DocumentChangeAnalysisServiceImpl — the double jsonb_array_elements
--       form was probed executable (bigint ids out, 173 chars fitting the
--       200-char detector_ref column) against the live slot schema — instead
--       of re-running section diffing: the single consumption point, no
--       second judgment source.
-- Risk shapes (design 3.1): 2 = downstream business content surfacing as a
-- grouped draft with the one-click human accept; the requirement draft fan
-- rides that shape because it proposes content, not a display mark.
-- Idempotent replay: rows are keyed by edge_key and conflict-do-nothing.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
('doc->doc_change_analysis', 'doc', 'doc_change_analysis', 3, NULL, NULL, 'exempt', 1, 1,
 'The change-analysis queue row is produced by the lineage auto-analysis extractor inside the upload transaction itself; the bus announces but never re-derives it',
 NULL),
('doc->requirement', 'doc', 'requirement', 2,
 'SELECT (jsonb_array_elements(jsonb_array_elements(a.changes) -> ''requirementIds''))::bigint FROM tp_doc_change_analysis a WHERE a.new_document_id = :objectId AND a.status = 0',
 NULL, 'grouped_draft', 2, 0, NULL,
 'A re-parsed document arms the requirements whose revision drafts were pre-generated from its pending analysis rows; consuming the extractor output, no third detector family')
ON CONFLICT (edge_key) DO NOTHING;
