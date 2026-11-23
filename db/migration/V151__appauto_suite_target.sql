
ALTER TABLE tp_regression_suite
    ADD COLUMN IF NOT EXISTS automation_target VARCHAR(10) NULL
        CONSTRAINT ck_regression_suite_automation_target CHECK (
            automation_target IS NULL OR automation_target IN ('web', 'app', 'mp')),
    ADD COLUMN IF NOT EXISTS appauto_engine VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS appauto_env_profile_id BIGINT NULL;

COMMENT ON COLUMN tp_regression_suite.automation_target IS '套件自动化目标 web/app/mp（NULL=未挂按 web 语义；同 迁移件 用例列口径）';
COMMENT ON COLUMN tp_regression_suite.appauto_engine IS 'APP/MP 目标引擎编码（tp_appauto_engine.code，启用面对账在服务层）';
COMMENT ON COLUMN tp_regression_suite.appauto_env_profile_id IS '环境档案引用（tp_appauto_env_profile.id，项目归属+projectPath 闸在服务层单点）';
