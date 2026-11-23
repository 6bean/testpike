-- Assertion truth-source, level 1 : with the switch on
-- (factory default), every interface-type case generation run first reads the
-- latest contract schema brief of the project (method + path + parameter and
-- response field names from tp_api_spec) plus recent measured response
-- samples (tp_mock_hit_log snapshots, masked), and injects both into the
-- prompt so assertion fields cite real contract keys instead of invented ones.
-- When the contract store is empty or any read fails the injection degrades to
-- nothing and the generation flow is never interrupted. Existing projects with
-- no contract rows see exactly the legacy prompt (zero drift). The read side is
-- a single shared bridge consumed by generation and (later) by the
-- contract-change handling path; no second implementation is allowed.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.truth-source.api-spec-schema', 'true', '系统', 'bool', 0,
     '接口用例断言真值注入开关（默认开启；开启后接口类用例生成前注入本项目最新接口契约的字段名简读与最近的实测响应样例，断言字段以真实契约与实测数据为据；项目没有契约或读取失败时不注入、生成流程不受影响；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
