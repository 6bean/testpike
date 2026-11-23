-- Migration: declarative mock bindings for test cases and regression suites .
-- mock_space_id names a published mock space whose rules stand in for the real
-- environment during execution: NULL = run against the real system (default, zero
-- drift for all existing rows). The executor only rewrites base_url (HTTP cases) or
-- installs playwright route interception (web cases) when a space is bound — the
-- binding is a declarative scope, never an implicit environment change.
-- While a space is referenced by any live case or suite, the retire/delete edge is
-- blocked with a clickable reference list (ruling C-4); MockSpaceReferenceGate is the
-- single source of that question, so both the manual transition and the expiry sweep
-- see the new reasons without touching their own code.
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS mock_space_id BIGINT REFERENCES tp_mock_space (id);
ALTER TABLE tp_regression_suite ADD COLUMN IF NOT EXISTS mock_space_id BIGINT REFERENCES tp_mock_space (id);
CREATE INDEX IF NOT EXISTS idx_test_case_mock_space ON tp_test_case (mock_space_id) WHERE mock_space_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_regression_suite_mock_space ON tp_regression_suite (mock_space_id) WHERE mock_space_id IS NOT NULL;
COMMENT ON COLUMN tp_test_case.mock_space_id IS 'Published mock space this case runs against (NULL = real environment); blocks space retire while bound (C-4)';
COMMENT ON COLUMN tp_regression_suite.mock_space_id IS 'Published mock space this suite runs against (NULL = real environment); blocks space retire while bound (C-4)';
