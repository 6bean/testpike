-- Migration: seed the popup-rule envelope stamping limit key.
-- appauto.popup-rule-envelope-limit = how many human-approved popup rules
-- (tp_appauto_popup_rule status=2, own project only) a dispatch envelope may
-- carry for the executor's popup self-heal bonus path. Seeded 50 covers real
-- rule libraries; raising or lowering takes effect at runtime (hot key, read
-- per dispatch — no hardcode in the assembly face).
-- Idempotent: ON CONFLICT DO NOTHING. Flyway disabled: apply with manual psql.
-- Usage: psql < db/migration/V469__appauto_popup_rule_envelope_limit.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.popup-rule-envelope-limit', '50', 'APP自动化', 'int', 0,
     '派发信封可携带的已批准弹窗规则条数上限（默认 50；建单时按项目取已批准规则随信封下发，超限取最近创建的 N 条，调整即时生效）')
ON CONFLICT (config_key) DO NOTHING;
