
ALTER TABLE tp_test_case DROP CONSTRAINT IF EXISTS ck_test_case_automation_target;
ALTER TABLE tp_test_case ADD CONSTRAINT ck_test_case_automation_target CHECK (
    automation_target IS NULL OR automation_target IN ('web', 'app', 'mp', 'harmony'));

ALTER TABLE tp_regression_suite DROP CONSTRAINT IF EXISTS ck_regression_suite_automation_target;
ALTER TABLE tp_regression_suite ADD CONSTRAINT ck_regression_suite_automation_target CHECK (
    automation_target IS NULL OR automation_target IN ('web', 'app', 'mp', 'harmony'));

COMMENT ON COLUMN tp_test_case.automation_target IS
    '自动化目标 web/app/mp/harmony（NULL=未挂按 web 语义；harmony=鸿蒙 Next，V219 扩值；end_type 是业务端属性两列语义正交）';
COMMENT ON COLUMN tp_regression_suite.automation_target IS
    '套件自动化目标 web/app/mp/harmony（NULL=未挂按 web 语义；与用例表同值域 V219）';
