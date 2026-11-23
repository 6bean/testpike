-- Migration: widen the automation_target domain with 'api' .
-- Rationale: promoting a tuned sandbox request into a regression case creates a real
-- test case / suite asset whose automation target is api; it must flow through the
-- quality management chain (suite -> plan -> batch -> report -> exit gate). The domain
-- was web/app/mp/harmony, so an api target died at both the DB CHECK and the write-side
-- gate. Discipline (same as V219): tp_test_case AND tp_regression_suite widen together —
-- a half-widened domain (case accepts api, suite rejects it) breaks the asset chain.
-- Engine columns keep their existing constraints: NULL already means the runtime
-- "auto" default; the relaxation lives in the write-side gate, not the DB constraint.

ALTER TABLE tp_test_case DROP CONSTRAINT IF EXISTS ck_test_case_automation_target;
ALTER TABLE tp_test_case ADD CONSTRAINT ck_test_case_automation_target CHECK (
    automation_target IS NULL OR automation_target IN ('web', 'app', 'mp', 'harmony', 'api'));

ALTER TABLE tp_regression_suite DROP CONSTRAINT IF EXISTS ck_regression_suite_automation_target;
ALTER TABLE tp_regression_suite ADD CONSTRAINT ck_regression_suite_automation_target CHECK (
    automation_target IS NULL OR automation_target IN ('web', 'app', 'mp', 'harmony', 'api'));

COMMENT ON COLUMN tp_test_case.automation_target IS
    'automation target web/app/mp/harmony/api (NULL = unmounted, web semantics; harmony = HarmonyOS NEXT added in V219; api = interface regression case added in V247, runs inline http.request steps; end_type is an orthogonal business-end attribute)';
COMMENT ON COLUMN tp_regression_suite.automation_target IS
    'suite automation target web/app/mp/harmony/api (NULL = unmounted, web semantics; same domain as tp_test_case since V247)';
