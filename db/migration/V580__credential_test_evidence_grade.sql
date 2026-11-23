-- : the credential "test now" door answers "did the credential pass",
-- not "did the address answer". The last run's evidence grade becomes a
-- first-class column (VERIFIED / UNVERIFIED / EXPIRED / UNKNOWN, null = never
-- tested) so the list badge and the drawer verdict line read the same value;
-- the fingerprint knob gates whether a 200 login-shell body degrades the
-- verdict to UNKNOWN instead of reading as a pass. Ships ON per the
-- AI-feature default posture (off = plain 2xx passes again, honestly labeled).
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

ALTER TABLE tp_credential ADD COLUMN IF NOT EXISTS test_evidence VARCHAR(16);
COMMENT ON COLUMN tp_credential.test_evidence IS
    'Last "test now" evidence grade: VERIFIED / UNVERIFIED / EXPIRED / UNKNOWN (null = never tested)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('credential.test.login-fingerprint.enabled', 'true', 'APP 自动化', 'string', 0,
     '凭据立即测试的登录页特征识别开关（默认开）：目标返回 200 但响应体含口令输入框、登录标题或 SSO 跳转脚本时，判定降为「结果需人工复核」，不据此判定凭据失效。填 0 或 false 关闭后，200 一律按接受处理。修改即时生效')
ON CONFLICT (config_key) DO NOTHING;
