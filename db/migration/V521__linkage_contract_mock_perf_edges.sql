-- Migration: contract, mock and performance downstream edge ledger .
-- Why these rows exist: api_spec, mock and perf_scenario saves now announce
-- {objectType}.changed on the linkage bus, and the dispatcher arms whatever
-- this ledger registers — an edge is data, not code. The five fans and their
-- shapes come from the batch design adjudication section 3.1/3.2:
--   api_spec -> apitest_request / mock_stub are folded-key comparisons whose
--       judgment source lives in Java (ContractMatchKeyRules), so they are
--       bean detectors (kind 1) naming live Spring beans, not SQL text;
--   mock -> mock_scenario is a plain scenario_name text join (kind 2), the
--       stateful scenario of an edited stub gets an automatic stale mark;
--   perf_scenario -> perf_approval / perf_run are plain scenario_id foreign
--       keys (kind 2): editing a scenario re-arms its pending approval as a
--       grouped draft (one-click human accept), while already-issued runs are
--       immutable execution history and may only be disclosed.
-- Risk shapes: 1 = reversible mark applied automatically, 2 = downstream
-- business content change surfacing as a grouped draft, 3 = immutable or
-- already-issued records disclosed only.
-- Idempotent replay: rows are keyed by edge_key and conflict-do-nothing.

INSERT INTO tp_change_linkage
    (edge_key, upstream_object_type, downstream_object_type, detector_kind,
     detector_ref, trigger_field_predicate, disposition_strategy, risk_level,
     exempt, exempt_reason, edge_note)
VALUES
('api_spec->apitest_request', 'api_spec', 'apitest_request', 1,
 'contractRequestFanDetector', NULL, 'grouped_draft', 2, 0, NULL,
 'Requests imported from the contract are matched by folded endpoint key; the Java match-key rules stay the single source, so this edge names a bean detector instead of duplicating the fold as SQL'),
('api_spec->mock_stub', 'api_spec', 'mock_stub', 1,
 'contractMockFanDetector', NULL, 'grouped_draft', 2, 0, NULL,
 'Contract-derived stubs are matched by the same folded key via DerivedMockKeyLookup; shared lookup single source, no second SELECT copy'),
('mock->mock_scenario', 'mock', 'mock_scenario', 2,
 'SELECT sc.id FROM tp_mock_scenario sc JOIN tp_mock_stub st ON st.scenario_name = sc.name WHERE st.id = :objectId',
 NULL, 'mark_only', 1, 0, NULL,
 'The stateful scenario a stub belongs to is re-marked automatically when the stub rule is edited ( reversible stale mark, no content rewrite)'),
('perf_scenario->perf_approval', 'perf_scenario', 'perf_approval', 2,
 'SELECT id FROM tp_perf_approval WHERE scenario_id = :objectId',
 NULL, 'grouped_draft', 2, 0, NULL,
 'Editing a scenario invalidates the approval granted for its old shape: surfaces a re-approval grouped draft for one-click human accept'),
('perf_scenario->perf_run', 'perf_scenario', 'perf_run', 2,
 'SELECT id FROM tp_perf_run WHERE scenario_id = :objectId',
 NULL, 'disclose_only', 3, 0, NULL,
 'Issued runs are execution history and are never rewritten; a scenario edit only discloses the affected runs')
ON CONFLICT (edge_key) DO NOTHING;
